/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package io.atomix.copycat.client.session;

import io.atomix.copycat.protocol.ProtocolClientConnection;
import io.atomix.copycat.protocol.error.UnknownSessionException;
import io.atomix.copycat.protocol.request.PublishRequest;
import io.atomix.copycat.protocol.response.ProtocolResponse;
import io.atomix.copycat.protocol.response.PublishResponse;
import io.atomix.copycat.util.Assert;
import io.atomix.copycat.util.buffer.BufferInput;
import io.atomix.copycat.util.buffer.HeapBuffer;
import io.atomix.copycat.util.concurrent.Futures;
import io.atomix.copycat.util.concurrent.Listener;
import io.atomix.copycat.util.concurrent.ThreadContext;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Client session message listener.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
final class ClientSessionListener {
  private final ClientSessionState state;
  private final ThreadContext context;
  private final List<Consumer<BufferInput>> eventListeners = new CopyOnWriteArrayList<>();
  private final ClientSequencer sequencer;

  public ClientSessionListener(ProtocolClientConnection connection, ClientSessionState state, ClientSequencer sequencer, ThreadContext context) {
    this.state = Assert.notNull(state, "state");
    this.context = Assert.notNull(context, "context");
    this.sequencer = Assert.notNull(sequencer, "sequencer");
    connection.onPublish(this::handlePublish);
  }

  /**
   * Registers a session event listener.
   */
  public Listener<BufferInput> onEvent(Consumer<BufferInput> listener) {
    eventListeners.add(listener);
    return new Listener<BufferInput>() {
      @Override
      public void accept(BufferInput event) {
        listener.accept(event);
      }

      @Override
      public void close() {
        eventListeners.remove(listener);
      }
    };
  }

  /**
   * Handles a publish request.
   *
   * @param request The publish request to handle.
   * @return A completable future to be completed with the publish response.
   */
  private CompletableFuture<PublishResponse> handlePublish(PublishRequest request, PublishResponse.Builder builder) {
    state.getLogger().debug("{} - Received {}", state.getSessionId(), request);

    // If the request is for another session ID, this may be a session that was previously opened
    // for this client.
    if (request.session() != state.getSessionId()) {
      state.getLogger().debug("{} - Inconsistent session ID: {}", state.getSessionId(), request.session());
      return Futures.exceptionalFuture(new UnknownSessionException("incorrect session ID"));
    }

    if (request.eventIndex() <= state.getEventIndex()) {
      return CompletableFuture.completedFuture(
        builder.withStatus(ProtocolResponse.Status.OK)
          .withIndex(state.getEventIndex())
          .build());
    }

    // If the request's previous event index doesn't equal the previous received event index,
    // respond with an undefined error and the last index received. This will cause the cluster
    // to resend events starting at eventIndex + 1.
    if (request.previousIndex() != state.getEventIndex()) {
      state.getLogger().debug("{} - Inconsistent event index: {}", state.getSessionId(), request.previousIndex());
      return CompletableFuture.completedFuture(
        builder.withStatus(ProtocolResponse.Status.ERROR)
          .withIndex(state.getEventIndex())
          .build());
    }

    // Store the event index. This will be used to verify that events are received in sequential order.
    state.setEventIndex(request.eventIndex());

    sequencer.sequenceEvent(request, () -> {
      for (byte[] event : request.events()) {
        for (Consumer<BufferInput> listener : eventListeners) {
          listener.accept(HeapBuffer.wrap(event));
        }
      }
    });

    return CompletableFuture.completedFuture(
      builder.withStatus(ProtocolResponse.Status.OK)
        .withIndex(request.eventIndex())
        .build());
  }

  /**
   * Closes the session event listener.
   *
   * @return A completable future to be completed once the listener is closed.
   */
  public CompletableFuture<Void> close() {
    return CompletableFuture.completedFuture(null);
  }

}
