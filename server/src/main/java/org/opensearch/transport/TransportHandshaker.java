/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.transport;

import org.opensearch.LegacyESVersion;
import org.opensearch.Version;
import org.opensearch.action.ActionListener;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.common.bytes.BytesReference;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.metrics.CounterMetric;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.threadpool.ThreadPool;

import java.io.EOFException;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends and receives transport-level connection handshakes. This class will send the initial handshake,
 * manage state/timeouts while the handshake is in transit, and handle the eventual response.
 */
final class TransportHandshaker {

    static final String HANDSHAKE_ACTION_NAME = "internal:tcp/handshake";
    private final ConcurrentMap<Long, HandshakeResponseHandler> pendingHandshakes = new ConcurrentHashMap<>();
    private final CounterMetric numHandshakes = new CounterMetric();

    private final Version version;
    private final ThreadPool threadPool;
    private final HandshakeRequestSender handshakeRequestSender;

    // @todo remove in 3.0.0
    static final Version V_3_0_0 = Version.fromId(3000099 ^ Version.MASK);

    TransportHandshaker(Version version, ThreadPool threadPool, HandshakeRequestSender handshakeRequestSender) {
        this.version = version;
        this.threadPool = threadPool;
        this.handshakeRequestSender = handshakeRequestSender;
    }

    void sendHandshake(long requestId, DiscoveryNode node, TcpChannel channel, TimeValue timeout, ActionListener<Version> listener) {
        numHandshakes.inc();
        final HandshakeResponseHandler handler = new HandshakeResponseHandler(requestId, version, listener);
        pendingHandshakes.put(requestId, handler);
        channel.addCloseListener(
            ActionListener.wrap(() -> handler.handleLocalException(new TransportException("handshake failed because connection reset")))
        );
        boolean success = false;
        try {
            // for the request we use the minCompatVersion since we don't know what's the version of the node we talk to
            // we also have no payload on the request but the response will contain the actual version of the node we talk
            // to as the payload.
            Version minCompatVersion = version.minimumCompatibilityVersion();
            if (version.onOrAfter(Version.V_1_0_0) && version.before(Version.V_2_0_0)) {
                // the minCompatibleVersion for OpenSearch 1.x is sent as 6.7.99 instead of 6.8.0
                // as this helps in (indirectly) identifying the remote node version during handle HandshakeRequest itself
                // and then send appropriate version (7.10.2/ OpenSearch 1.x version) in response.
                // The advantage of doing this is early identification of remote node version as otherwise
                // if OpenSearch node also sends 6.8.0, there is no way to differentiate ES 7.x version from
                // OpenSearch version and OpenSearch node will end up sending BC version to both ES & OpenSearch remote node.
                // Sending only BC version to ElasticSearch node provide easy deprecation path for this BC version logic
                // in OpenSearch 2.0.0.
                minCompatVersion = Version.fromId(6079999);
            } else if (version.onOrAfter(Version.V_2_0_0)) {
                minCompatVersion = Version.fromId(7099999);
            }
            handshakeRequestSender.sendRequest(node, channel, requestId, minCompatVersion);

            threadPool.schedule(
                () -> handler.handleLocalException(new ConnectTransportException(node, "handshake_timeout[" + timeout + "]")),
                timeout,
                ThreadPool.Names.GENERIC
            );
            success = true;
        } catch (Exception e) {
            handler.handleLocalException(new ConnectTransportException(node, "failure to send " + HANDSHAKE_ACTION_NAME, e));
        } finally {
            if (success == false) {
                TransportResponseHandler<?> removed = pendingHandshakes.remove(requestId);
                assert removed == null : "Handshake should not be pending if exception was thrown";
            }
        }
    }

    void handleHandshake(TransportChannel channel, long requestId, StreamInput stream) throws IOException {
        // Must read the handshake request to exhaust the stream
        HandshakeRequest handshakeRequest = new HandshakeRequest(stream);
        final int nextByte = stream.read();
        if (nextByte != -1) {
            throw new IllegalStateException(
                "Handshake request not fully read for requestId ["
                    + requestId
                    + "], action ["
                    + TransportHandshaker.HANDSHAKE_ACTION_NAME
                    + "], available ["
                    + stream.available()
                    + "]; resetting"
            );
        }
        // 1. if remote node is 7.x, then StreamInput version would be 6.8.0
        // 2. if remote node is 6.8 then it would be 5.6.0
        // 3. if remote node is OpenSearch 1.x then it would be 6.7.99
        if ((this.version.onOrAfter(Version.V_1_0_0) && this.version.before(V_3_0_0))
            && (stream.getVersion().equals(LegacyESVersion.fromId(6080099)) || stream.getVersion().equals(Version.fromId(5060099)))) {
            // send 7.10.2 in response to ensure compatibility w/ Legacy 7.10.x nodes for rolling upgrade support
            channel.sendResponse(new HandshakeResponse(LegacyESVersion.V_7_10_2));
        } else {
            channel.sendResponse(new HandshakeResponse(this.version));
        }
    }

    TransportResponseHandler<HandshakeResponse> removeHandlerForHandshake(long requestId) {
        return pendingHandshakes.remove(requestId);
    }

    int getNumPendingHandshakes() {
        return pendingHandshakes.size();
    }

    long getNumHandshakes() {
        return numHandshakes.count();
    }

    private class HandshakeResponseHandler implements TransportResponseHandler<HandshakeResponse> {

        private final long requestId;
        private final Version currentVersion;
        private final ActionListener<Version> listener;
        private final AtomicBoolean isDone = new AtomicBoolean(false);

        private HandshakeResponseHandler(long requestId, Version currentVersion, ActionListener<Version> listener) {
            this.requestId = requestId;
            this.currentVersion = currentVersion;
            this.listener = listener;
        }

        @Override
        public HandshakeResponse read(StreamInput in) throws IOException {
            return new HandshakeResponse(in);
        }

        @Override
        public void handleResponse(HandshakeResponse response) {
            if (isDone.compareAndSet(false, true)) {
                Version version = response.responseVersion;
                if (currentVersion.isCompatible(version) == false) {
                    listener.onFailure(
                        new IllegalStateException(
                            "Received message from unsupported version: ["
                                + version
                                + "] minimal compatible version is: ["
                                + currentVersion.minimumCompatibilityVersion()
                                + "]"
                        )
                    );
                } else {
                    listener.onResponse(version);
                }
            }
        }

        @Override
        public void handleException(TransportException e) {
            if (isDone.compareAndSet(false, true)) {
                listener.onFailure(new IllegalStateException("handshake failed", e));
            }
        }

        void handleLocalException(TransportException e) {
            if (removeHandlerForHandshake(requestId) != null && isDone.compareAndSet(false, true)) {
                listener.onFailure(e);
            }
        }

        @Override
        public String executor() {
            return ThreadPool.Names.SAME;
        }
    }

    static final class HandshakeRequest extends TransportRequest {

        private final Version version;

        HandshakeRequest(Version version) {
            this.version = version;
        }

        HandshakeRequest(StreamInput streamInput) throws IOException {
            super(streamInput);
            BytesReference remainingMessage;
            try {
                remainingMessage = streamInput.readBytesReference();
            } catch (EOFException e) {
                remainingMessage = null;
            }
            if (remainingMessage == null) {
                version = null;
            } else {
                try (StreamInput messageStreamInput = remainingMessage.streamInput()) {
                    this.version = Version.readVersion(messageStreamInput);
                }
            }
        }

        @Override
        public void writeTo(StreamOutput streamOutput) throws IOException {
            super.writeTo(streamOutput);
            assert version != null;
            try (BytesStreamOutput messageStreamOutput = new BytesStreamOutput(4)) {
                Version.writeVersion(version, messageStreamOutput);
                BytesReference reference = messageStreamOutput.bytes();
                streamOutput.writeBytesReference(reference);
            }
        }
    }

    static final class HandshakeResponse extends TransportResponse {

        private final Version responseVersion;

        HandshakeResponse(Version responseVersion) {
            this.responseVersion = responseVersion;
        }

        private HandshakeResponse(StreamInput in) throws IOException {
            super(in);
            responseVersion = Version.readVersion(in);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            assert responseVersion != null;
            Version.writeVersion(responseVersion, out);
        }

        Version getResponseVersion() {
            return responseVersion;
        }
    }

    @FunctionalInterface
    interface HandshakeRequestSender {

        void sendRequest(DiscoveryNode node, TcpChannel channel, long requestId, Version version) throws IOException;
    }
}
