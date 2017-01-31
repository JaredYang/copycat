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
 * limitations under the License.
 */
package io.atomix.copycat.server.state;

import io.atomix.copycat.protocol.error.InternalException;
import io.atomix.copycat.protocol.error.UnknownSessionException;
import io.atomix.copycat.server.Commit;
import io.atomix.copycat.server.Snapshottable;
import io.atomix.copycat.server.StateMachine;
import io.atomix.copycat.server.session.SessionListener;
import io.atomix.copycat.server.storage.Indexed;
import io.atomix.copycat.server.storage.Log;
import io.atomix.copycat.server.storage.LogReader;
import io.atomix.copycat.server.storage.Reader;
import io.atomix.copycat.server.storage.compaction.Compaction;
import io.atomix.copycat.server.storage.entry.*;
import io.atomix.copycat.server.storage.snapshot.Snapshot;
import io.atomix.copycat.server.storage.snapshot.SnapshotReader;
import io.atomix.copycat.server.storage.snapshot.SnapshotWriter;
import io.atomix.copycat.util.Assert;
import io.atomix.copycat.util.buffer.Buffer;
import io.atomix.copycat.util.concurrent.ComposableFuture;
import io.atomix.copycat.util.concurrent.Futures;
import io.atomix.copycat.util.concurrent.ThreadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Internal server state machine.
 * <p>
 * The internal state machine handles application of commands to the user provided {@link StateMachine}
 * and keeps track of internal state like sessions and the various indexes relevant to log compaction.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
final class ServerStateMachine implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(ServerStateMachine.class);
  private final StateMachine stateMachine;
  private final ServerContext state;
  private final ThreadContext executor;
  private final Log log;
  private final LogReader reader;
  private final ServerStateMachineContext context;
  private volatile long lastApplied;
  private long lastCompleted;
  private Snapshot pendingSnapshot;

  ServerStateMachine(StateMachine stateMachine, ServerContext state, ThreadContext stateContext, ThreadContext applicationContext) {
    this.stateMachine = Assert.notNull(stateMachine, "stateMachine");
    this.state = Assert.notNull(state, "state");
    this.executor = Assert.notNull(stateContext, "stateContext");
    this.log = state.getLog();
    this.reader = log.createReader(1, Reader.Mode.ALL_COMMITS);
    this.context = new ServerStateMachineContext(applicationContext, new ServerSessionManager(state));
    init();
  }

  /**
   * Initializes the state machine.
   */
  private void init() {
    stateMachine.init(context);
  }

  /**
   * Takes a snapshot of the state machine state if necessary.
   * <p>
   * Snapshots of the state machine are taken only once the log becomes compactable. This means snapshots
   * are largely dependent on the storage configuration and ensures that snapshots are not taken more
   * frequently than will benefit log compaction.
   */
  private void takeSnapshot() {
    // If no snapshot has been taken, take a snapshot and hold it in memory until the complete
    // index has met the snapshot index. Note that this will be executed in the state machine thread.
    // Snapshots are only taken of the state machine when the log becomes compactable. If the log compactor's
    // compactIndex is greater than the last snapshot index and the lastApplied index is greater than the
    // last snapshot index, take the snapshot.
    Snapshot currentSnapshot = state.getSnapshotStore().currentSnapshot();
    if (pendingSnapshot == null && stateMachine instanceof Snapshottable
      && (currentSnapshot == null || (log.compactor().compactIndex() > currentSnapshot.index() && lastApplied > currentSnapshot.index()))) {
      pendingSnapshot = state.getSnapshotStore().createSnapshot(lastApplied);

      // Write the snapshot data. Note that we don't complete the snapshot here since the completion
      // of a snapshot is predicated on session events being received by clients up to the snapshot index.
      LOGGER.info("{} - Taking snapshot {}", state.getCluster().member().address(), pendingSnapshot.index());
      synchronized (pendingSnapshot) {
        try (SnapshotWriter writer = pendingSnapshot.writer()) {
          ((Snapshottable) stateMachine).snapshot(writer);
        }
      }
    }
  }

  /**
   * Installs a snapshot of the state machine state if necessary.
   * <p>
   * Snapshots are installed only if there's a local snapshot stored with a version equal to the
   * last applied index.
   */
  private void installSnapshot() {
    // If the last stored snapshot has not yet been installed and its index matches the last applied state
    // machine index, install the snapshot. This requires that the state machine see all indexes sequentially
    // even for entries that have been compacted from the log.
    Snapshot currentSnapshot = state.getSnapshotStore().currentSnapshot();
    if (currentSnapshot != null && currentSnapshot.index() > log.compactor().snapshotIndex() && currentSnapshot.index() == lastApplied && stateMachine instanceof Snapshottable) {

      // Install the snapshot in the state machine thread. Multiple threads can access snapshots, so we
      // synchronize on the snapshot object. In practice, this probably isn't even necessary and could prove
      // to be an expensive operation. Snapshots can be read concurrently with separate SnapshotReaders since
      // memory snapshots are copied to the reader and file snapshots open a separate FileBuffer for each reader.
      LOGGER.info("{} - Installing snapshot {}", state.getCluster().member().address(), currentSnapshot.index());
      executor.execute(() -> {
        synchronized (currentSnapshot) {
          try (SnapshotReader reader = currentSnapshot.reader()) {
            ((Snapshottable) stateMachine).install(reader);
          }
        }
      });

      // Once a snapshot has been applied, snapshot dependent entries can be cleaned from the log.
      log.compactor().snapshotIndex(currentSnapshot.index());
    }
  }

  /**
   * Completes a snapshot of the state machine state.
   * <p>
   * When a snapshot of the state machine is taken, the snapshot isn't immediately made available for
   * recovery or replication. Session events are dependent on original commands being retained in the log
   * for fault tolerance. Thus, a snapshot cannot be completed until all prior events have been received
   * by clients. So, we take a snapshot of the state machine state and complete the snapshot only after
   * prior events have been received.
   */
  private void completeSnapshot() {
    // If a snapshot is pending to be persisted and the last completed index is greater than the
    // waiting snapshot index and no current or newer snapshot exists,
    // persist the snapshot and update the last snapshot index.
    if (pendingSnapshot != null && lastCompleted >= pendingSnapshot.index()) {
      long snapshotIndex = pendingSnapshot.index();
      LOGGER.debug("{} - Completing snapshot {}", state.getCluster().member().address(), snapshotIndex);
      synchronized (pendingSnapshot) {
        Snapshot currentSnapshot = state.getSnapshotStore().currentSnapshot();
        if (currentSnapshot == null || snapshotIndex > currentSnapshot.index()) {
          pendingSnapshot.complete();
        } else {
          LOGGER.debug("Discarding pending snapshot at index {} since the current snapshot is at index {}", pendingSnapshot.index(), currentSnapshot.index());
        }
        pendingSnapshot = null;
      }

      // Once the snapshot has been completed, snapshot dependent entries can be cleaned from the log.
      log.compactor().snapshotIndex(snapshotIndex);
      log.compactor().compact();
    }
  }

  /**
   * Returns the server state machine context.
   *
   * @return The server state machine context.
   */
  ServerStateMachineContext context() {
    return context;
  }

  /**
   * Returns the last applied index.
   *
   * @return The last applied index.
   */
  long getLastApplied() {
    return lastApplied;
  }

  /**
   * Sets the last applied index.
   * <p>
   * The last applied index is updated *after* each time a non-query entry is applied to the state machine.
   *
   * @param lastApplied The last applied index.
   */
  private void setLastApplied(long lastApplied) {
    // Iterate through applied indices and set last applied for each index. This is necessary since
    // entries may be missing from the log.
    for (long i = this.lastApplied; i <= lastApplied; i++) {
      this.lastApplied = i;

      // Update the index for each session. This will be used to trigger queries that are awaiting the
      // application of specific indexes to the state machine. Setting the session index may cause query
      // callbacks to be called and queries to be evaluated.
      for (ServerSession session : context.sessions().sessions.values()) {
        session.setLastApplied(i);
      }

      // Take a state machine snapshot if necessary.
      takeSnapshot();

      // Install a state machine snapshot if necessary.
      installSnapshot();
    }
  }

  /**
   * Returns the highest index completed for all sessions.
   * <p>
   * The lastCompleted index is representative of the highest index for which related events have been
   * received by *all* clients. In other words, no events lower than the given index should remain in
   * memory.
   *
   * @return The highest index completed for all sessions.
   */
  long getLastCompleted() {
    return lastCompleted > 0 ? lastCompleted : lastApplied;
  }

  /**
   * Calculates the last completed session event index.
   */
  private long calculateLastCompleted(long index) {
    // Calculate the last completed index as the lowest index acknowledged by all clients.
    long lastCompleted = index;
    for (ServerSession session : context.sessions().sessions.values()) {
      lastCompleted = Math.min(lastCompleted, session.getLastCompleted());
    }
    return lastCompleted;
  }

  /**
   * Updates the last completed event index based on a commit at the given index.
   */
  private void setLastCompleted(long lastCompleted) {
    if (!log.isOpen()) {
      return;
    }

    this.lastCompleted = Math.max(this.lastCompleted, lastCompleted);

    // Update the log compaction minor index.
    log.compactor().minorIndex(this.lastCompleted);

    completeSnapshot();
  }

  /**
   * Applies all commits up to the given index.
   * <p>
   * Calls to this method are assumed not to expect a result. This allows some optimizations to be
   * made internally since linearizable events don't have to be waited to complete the command.
   *
   * @param index The index up to which to apply commits.
   */
  public void applyAll(long index) {
    if (!log.isOpen()) {
      return;
    }

    // Only apply valid indices.
    if (index > 0) {
      executor.execute(() -> {
        // Don't attempt to apply indices that have already been applied.
        if (index > lastApplied) {
          applyIndex(index);
        }
      });
    }
  }

  /**
   * Applies the entry at the given index to the state machine.
   * <p>
   * Calls to this method are assumed to expect a result. This means linearizable session events
   * triggered by the application of the command at the given index will be awaited before completing
   * the returned future.
   *
   * @param index The index to apply.
   * @return A completable future to be completed once the commit has been applied.
   */
  public <T> CompletableFuture<T> apply(long index) {
    ComposableFuture<T> future = new ComposableFuture<>();
    executor.execute(() -> this.<T>applyIndex(index).whenComplete(future));
    return future;
  }

  /**
   * Applies the entry at the given index.
   */
  private <T> CompletableFuture<T> applyIndex(long index) {
    executor.checkThread();

    reader.lock();

    try {
      // Apply entries prior to this entry.
      while (reader.hasNext()) {
        // If the next index is less than or equal to the given index, read and apply the entry.
        if (reader.nextIndex() < index) {
          Indexed<? extends Entry<?>> entry = reader.next();
          if (!entry.isCompacted()) {
            applyEntry(entry);
            setLastApplied(entry.index());
          }
        }
        // If the index has been reached, break.
        else {
          break;
        }
      }

      // Read the entry from the log. If the entry is non-null them apply the entry, otherwise
      // simply update the last applied index and return a null result.
      try {
        Indexed<? extends Entry<?>> entry = reader.next();
        if (!entry.isCompacted()) {
          if (entry.index() != index) {
            throw new IllegalStateException("inconsistent index applying entry " + index + ": " + entry);
          }
          return applyEntry(entry);
        } else {
          return CompletableFuture.completedFuture(null);
        }
      } finally {
        setLastApplied(index);
      }
    } finally {
      reader.unlock();
    }
  }

  /**
   * Applies an entry to the state machine.
   * <p>
   * Calls to this method are assumed to expect a result. This means linearizable session events
   * triggered by the application of the given entry will be awaited before completing the returned future.
   *
   * @param entry The entry to apply.
   * @return A completable future to be completed with the result.
   */
  public <T> CompletableFuture<T> apply(Indexed<? extends Entry<?>> entry) {
    ComposableFuture<T> future = new ComposableFuture<>();
    executor.execute(() -> this.<T>applyEntry(entry).whenComplete(future));
    return future;
  }

  /**
   * Applies an entry to the state machine.
   * <p>
   * Calls to this method are assumed to expect a result. This means linearizable session events
   * triggered by the application of the given entry will be awaited before completing the returned future.
   *
   * @param entry The entry to apply.
   * @return A completable future to be completed with the result.
   */
  @SuppressWarnings("unchecked")
  private <T> CompletableFuture<T> applyEntry(Indexed<? extends Entry<?>> entry) {
    LOGGER.debug("{} - Applying {}", state.getCluster().member().address(), entry);
    if (entry.type() == Entry.Type.QUERY) {
      return (CompletableFuture<T>) applyQuery((Indexed<QueryEntry>) entry);
    } else if (entry.type() == Entry.Type.COMMAND) {
      return (CompletableFuture<T>) applyCommand((Indexed<CommandEntry>) entry);
    } else if (entry.type() == Entry.Type.REGISTER) {
      return (CompletableFuture<T>) applyRegister((Indexed<RegisterEntry>) entry);
    } else if (entry.type() == Entry.Type.KEEP_ALIVE) {
      return (CompletableFuture<T>) applyKeepAlive((Indexed<KeepAliveEntry>) entry);
    } else if (entry.type() == Entry.Type.UNREGISTER) {
      return (CompletableFuture<T>) applyUnregister((Indexed<UnregisterEntry>) entry);
    } else if (entry.type() == Entry.Type.INITIALIZE) {
      return (CompletableFuture<T>) applyInitialize((Indexed<InitializeEntry>) entry);
    } else if (entry.type() == Entry.Type.CONNECT) {
      return (CompletableFuture<T>) applyConnect((Indexed<ConnectEntry>) entry);
    } else if (entry.type() == Entry.Type.CONFIGURATION) {
      return (CompletableFuture<T>) applyConfiguration((Indexed<ConfigurationEntry>) entry);
    }
    return Futures.exceptionalFuture(new InternalException("unknown state machine operation"));
  }

  /**
   * Applies a configuration entry to the internal state machine.
   * <p>
   * Configuration entries are applied to internal server state when written to the log. Thus, no significant
   * logic needs to take place in the handling of configuration entries. We simply release the previous configuration
   * entry since it was overwritten by a more recent committed configuration entry.
   */
  private CompletableFuture<Void> applyConfiguration(Indexed<ConfigurationEntry> entry) {
    // Clean the configuration entry from the log. The entry will be retained until it has been stored
    // on all servers.
    entry.compact(Compaction.Mode.SEQUENTIAL);
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Applies connect entry to the state machine.
   * <p>
   * Connect entries are applied to internal server state when written to the log. Thus, no significant logic needs
   * to take place in the handling of connect entries. We simply release the previous connect entry for the session
   * from the log. This ensures that the most recent connection is always retained in the log and replicated. Note
   * that connection indexes are not stored when applied to the internal state since a ConnectEntry may be applied
   * but never committed. Storing indexes in the internal state machine ensures that the stored index is committed
   * and will therefore be retained in the log.
   */
  private CompletableFuture<Void> applyConnect(Indexed<ConnectEntry> entry) {
    // Connections are stored in the state machine when they're *written* to the log, so we need only
    // release them once they're committed.
    ServerSession session = context.sessions().getSession(entry.entry().client());
    if (session != null) {
      // Update the session connect entry.
      session.setConnectEntry(entry);

      // Set the session as trusted. This will prevent the leader from explicitly unregistering the
      // session if it hasn't done so already.
      session.trust();

      // Update the session's timestamp with the current state machine time.
      session.setTimestamp(entry.entry().timestamp());

      // Connections are also treated like keep-alive operations if a session exists for the client.
      session.setKeepAliveEntry(entry);
    }
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Applies register session entry to the state machine.
   * <p>
   * Register entries are applied to the state machine to create a new session. The resulting session ID is the
   * index of the RegisterEntry. Once a new session is registered, we call register() on the state machine.
   * In the event that the {@code synchronous} flag is set, that indicates that the registration command expects a
   * response, i.e. it was applied by a leader. In that case, any events published during the execution of the
   * state machine's register() method must be completed synchronously prior to the completion of the returned future.
   */
  private CompletableFuture<Long> applyRegister(Indexed<RegisterEntry> entry) {
    // Allow the executor to execute any scheduled events.
    long timestamp = context.timestamp(entry.entry().timestamp());

    ServerSession session = new ServerSession(entry, entry.entry().client(), log, context, entry.entry().timeout());
    context.sessions().registerSession(session);

    // Update the session timestamp *after* executing any scheduled operations. The executor's timestamp
    // is guaranteed to be monotonically increasing, whereas the RegisterEntry may have an earlier timestamp
    // if, e.g., it was written shortly after a leader change.
    session.setTimestamp(timestamp);

    // Determine whether any sessions appear to be expired. This won't immediately expire the session(s),
    // but it will make them available to be unregistered by the leader.
    suspectSessions(0, timestamp);

    long index = entry.index();

    // Call the register() method on the user-provided state machine to allow the state machine to react to
    // a new session being registered. User state machine methods are always called in the state machine thread.
    CompletableFuture<Long> future = new CompletableFuture<>();
    context.executor.execute(() -> registerSession(index, timestamp, session, future));
    return future;
  }

  /**
   * Registers a session.
   */
  private void registerSession(long index, long timestamp, ServerSession session, CompletableFuture<Long> future) {
    context.checkThread();

    if (!log.isOpen()) {
      executor.execute(() -> future.completeExceptionally(new IllegalStateException("log closed")));
      return;
    }

    // Trigger scheduled callbacks in the state machine.
    context.tick(index, timestamp);

    // Update the state machine context with the register entry's index. This ensures that events published
    // within the register method will be properly associated with the unregister entry's index. All events
    // published during registration of a session are linearizable to ensure that clients receive related events
    // before the registration is completed.
    context.init(index, Instant.ofEpochMilli(timestamp), ServerStateMachineContext.Type.COMMAND);

    // Register the session and then open it. This ensures that state machines cannot publish events to this
    // session before the client has learned of the session ID.
    for (SessionListener listener : context.sessions().listeners) {
      listener.register(session);
    }
    session.open();

    // Calculate the last completed index.
    long lastCompleted = calculateLastCompleted(index);

    // Once register callbacks have been completed, ensure that events published during the callbacks are
    // received by clients. The state machine context will generate an event future for all published events
    // to all sessions.
    context.commit();
    executor.execute(() -> {
      setLastCompleted(lastCompleted);
      future.complete(index);
    });
  }

  /**
   * Applies a session keep alive entry to the state machine.
   * <p>
   * Keep alive entries are applied to the internal state machine to reset the timeout for a specific session.
   * If the session indicated by the KeepAliveEntry is still held in memory, we mark the session as trusted,
   * indicating that the client has committed a keep alive within the required timeout. Additionally, we check
   * all other sessions for expiration based on the timestamp provided by this KeepAliveEntry. Note that sessions
   * are never completely expired via this method. Leaders must explicitly commit an UnregisterEntry to expire
   * a session.
   * <p>
   * When a KeepAliveEntry is committed to the internal state machine, two specific fields provided in the entry
   * are used to update server-side session state. The {@code commandSequence} indicates the highest command for
   * which the session has received a successful response in the proper sequence. By applying the {@code commandSequence}
   * to the server session, we clear command output held in memory up to that point. The {@code eventVersion} indicates
   * the index up to which the client has received event messages in sequence for the session. Applying the
   * {@code eventVersion} to the server-side session results in events up to that index being removed from memory
   * as they were acknowledged by the client. It's essential that both of these fields be applied via entries committed
   * to the Raft log to ensure they're applied on all servers in sequential order.
   * <p>
   * Keep alive entries are retained in the log until the next time the client sends a keep alive entry or until the
   * client's session is expired. This ensures for sessions that have long timeouts, keep alive entries cannot be cleaned
   * from the log before they're replicated to some servers.
   */
  private CompletableFuture<Void> applyKeepAlive(Indexed<KeepAliveEntry> entry) {
    ServerSession session = context.sessions().getSession(entry.entry().session());

    // Update the deterministic executor time and allow the executor to execute any scheduled events.
    long timestamp = context.timestamp(entry.entry().timestamp());

    // Determine whether any sessions appear to be expired. This won't immediately expire the session(s),
    // but it will make them available to be unregistered by the leader. Note that it's safe to trigger
    // scheduled executor callbacks even if the keep-alive entry is for an unknown session since the
    // leader still committed the entry with its time and so time will still progress deterministically.
    suspectSessions(entry.entry().session(), timestamp);

    CompletableFuture<Void> future;

    // If the server session is null, the session either never existed or already expired.
    if (session == null) {
      entry.compact(Compaction.Mode.QUORUM);
      future = Futures.exceptionalFuture(new UnknownSessionException("unknown session: " + entry.entry().session()));
    }
    // If the session is in an inactive state, return an UnknownSessionException.
    else if (!session.state().active()) {
      entry.compact(Compaction.Mode.QUORUM);
      future = Futures.exceptionalFuture(new UnknownSessionException("inactive session: " + entry.entry().session()));
    }
    // If the session exists, don't allow it to expire even if its expiration has passed since we still
    // managed to receive a keep alive request from the client before it was removed. This allows the
    // client some arbitrary leeway in keeping its session alive. It's up to the leader to explicitly
    // expire a session by committing an UnregisterEntry in order to ensure sessions can't be expired
    // during leadership changes.
    else {
      long index = entry.index();

      // Set the session as trusted. This will prevent the leader from explicitly unregistering the
      // session if it hasn't done so already.
      session.trust();

      // Update the session's timestamp with the current state machine time.
      session.setTimestamp(timestamp);

      // Store the command/event sequence and event index instead of acquiring a reference to the entry.
      long commandSequence = entry.entry().commandSequence();
      long eventIndex = entry.entry().eventIndex();

      future = new CompletableFuture<>();

      // The keep-alive entry also serves to clear cached command responses and events from memory.
      // Remove responses and clear/resend events in the state machine thread to prevent thread safety issues.
      context.executor.execute(() -> keepAliveSession(index, timestamp, commandSequence, eventIndex, session, future));

      // Update the session keep alive index for log cleaning.
      session.setKeepAliveEntry(entry).setRequestSequence(commandSequence);
    }

    return future;
  }

  /**
   * Applies a keep alive for a session.
   */
  private void keepAliveSession(long index, long timestamp, long commandSequence, long eventIndex, ServerSession session, CompletableFuture<Void> future) {
    context.checkThread();

    if (!log.isOpen()) {
      executor.execute(() -> future.completeExceptionally(new IllegalStateException("log closed")));
      return;
    }

    // If the session is already in an inactive state, complete the future exceptionally.
    if (!session.state().active()) {
      executor.execute(() -> future.completeExceptionally(new UnknownSessionException("inactive session: " + session.id())));
      return;
    }

    // Trigger scheduled callbacks in the state machine.
    context.tick(index, timestamp);

    // Update the state machine context with the keep-alive entry's index. This ensures that events published
    // as a result of asynchronous callbacks will be executed at the proper index with SEQUENTIAL consistency.
    context.init(index, Instant.ofEpochMilli(timestamp), ServerStateMachineContext.Type.COMMAND);

    session.clearResults(commandSequence).resendEvents(eventIndex);

    // Calculate the last completed index.
    long lastCompleted = calculateLastCompleted(index);

    // Callbacks in the state machine may have been triggered by the execution of the keep-alive.
    // Get any futures for scheduled tasks and await their completion, then update the highest
    // index completed for all sessions to allow log compaction to progress.
    context.commit();
    context.executor.execute(() -> executor.execute(() -> {
      setLastCompleted(lastCompleted);
      future.complete(null);
    }));
  }

  /**
   * Applies an unregister session entry to the state machine.
   * <p>
   * Unregister entries may either be committed by clients or by the cluster's leader. Clients will commit
   * an unregister entry when closing their session normally. Leaders will commit an unregister entry when
   * an expired session is detected. This ensures that sessions are never expired purely on gaps in the log
   * which may result from normal log cleaning or lengthy leadership changes.
   * <p>
   * If the session was unregistered by the client, the isExpired flag will be false. Sessions expired by
   * the client are only close()ed on the state machine but not expire()d. Alternatively, entries where
   * isExpired is true were committed by a leader. For expired sessions, the state machine's expire() method
   * is called before close().
   * <p>
   * State machines may publish events during the handling of session expired or closed events. If the
   * {@code synchronous} flag passed to this method is true, events published during the commitment of the
   * UnregisterEntry must be synchronously completed prior to the completion of the returned future. This
   * ensures that state changes resulting from the expiration or closing of a session are completed before
   * the session close itself is completed.
   */
  private CompletableFuture<Void> applyUnregister(Indexed<UnregisterEntry> entry) {
    // Get the session from the context sessions. Note that we do not unregister the session here. Sessions
    // can only be unregistered once all references to session commands have been released by the state machine.
    ServerSession session = context.sessions().getSession(entry.entry().session());

    // Update the deterministic executor time and allow the executor to execute any scheduled events.
    long timestamp = context.timestamp(entry.entry().timestamp());

    // Determine whether any sessions appear to be expired. This won't immediately expire the session(s),
    // but it will make them available to be unregistered by the leader. Note that it's safe to trigger
    // scheduled executor callbacks even if the keep-alive entry is for an unknown session since the
    // leader still committed the entry with its time and so time will still progress deterministically.
    suspectSessions(entry.entry().session(), timestamp);

    CompletableFuture<Void> future;

    // If the server session is null, the session either never existed or already expired.
    if (session == null) {
      entry.compact(Compaction.Mode.QUORUM);
      future = Futures.exceptionalFuture(new UnknownSessionException("unknown session: " + entry.entry().session()));
    }
    // If the session is not in an active state, return an UnknownSessionException.
    else if (!session.state().active()) {
      entry.compact(Compaction.Mode.QUORUM);
      future = Futures.exceptionalFuture(new UnknownSessionException("inactive session: " + entry.entry().session()));
    }
    // If the session exists, don't allow it to expire even if its expiration has passed since we still
    // managed to receive a keep alive request from the client before it was removed.
    else {
      future = new CompletableFuture<>();
      context.executor.execute(() -> unregisterSession(entry, session, future));
    }

    return future;
  }

  /**
   * Unregisters the given session.
   */
  private void unregisterSession(Indexed<UnregisterEntry> entry, ServerSession session, CompletableFuture<Void> future) {
    context.checkThread();

    if (!log.isOpen()) {
      executor.execute(() -> future.completeExceptionally(new IllegalStateException("log closed")));
      return;
    }

    // If the session is already in an inactive state, complete the future exceptionally.
    if (!session.state().active()) {
      executor.execute(() -> future.completeExceptionally(new UnknownSessionException("inactive session: " + session.id())));
      return;
    }

    // Trigger scheduled callbacks in the state machine.
    context.tick(entry.index(), entry.entry().timestamp());

    // Update the state machine context with the unregister entry's index. This ensures that events published
    // within the expire or close methods will be properly associated with the unregister entry's index.
    // All events published during expiration or closing of a session are linearizable to ensure that clients
    // receive related events before the expiration is completed.
    context.init(entry.index(), Instant.ofEpochMilli(entry.entry().timestamp()), ServerStateMachineContext.Type.COMMAND);

    // Unregister the session.
    session.unregister(entry);

    // Call state machine callbacks based on whether the session was expired or unregistered.
    if (entry.entry().expired()) {
      for (SessionListener listener : context.sessions().listeners) {
        listener.expire(session);
        listener.close(session);
      }
    } else {
      for (SessionListener listener : context.sessions().listeners) {
        listener.unregister(session);
        listener.close(session);
      }
    }

    // Calculate the last completed index.
    long lastCompleted = calculateLastCompleted(entry.index());

    // Once expiration callbacks have been completed, ensure that events published during the callbacks
    // are published in batch. The state machine context will generate an event future for all published events
    // to all sessions. If the event future is non-null, that indicates events are pending which were published
    // during the call to expire(). Wait for the events to be received by the client before completing the future.
    context.commit();
    executor.execute(() -> {
      setLastCompleted(lastCompleted);
      future.complete(null);
    });
  }

  /**
   * Applies a command entry to the state machine.
   * <p>
   * Command entries result in commands being executed on the user provided {@link StateMachine} and a
   * response being sent back to the client by completing the returned future. All command responses are
   * cached in the command's {@link ServerSession} for fault tolerance. In the event that the same command
   * is applied to the state machine more than once, the original response will be returned.
   * <p>
   * Command entries are written with a sequence number. The sequence number is used to ensure that
   * commands are applied to the state machine in sequential order. If a command entry has a sequence
   * number that is less than the next sequence number for the session, that indicates that it is a
   * duplicate of a command that was already applied. Otherwise, commands are assumed to have been
   * received in sequential order. The reason for this assumption is because leaders always sequence
   * commands as they're written to the log, so no sequence number will be skipped.
   */
  @SuppressWarnings("unchecked")
  private CompletableFuture<Result> applyCommand(Indexed<CommandEntry> entry) {
    final CompletableFuture<Result> future = new CompletableFuture<>();

    // First check to ensure that the session exists.
    ServerSession session = context.sessions().getSession(entry.entry().session());

    // If the session is null, return an UnknownSessionException. Commands applied to the state machine must
    // have a session. We ensure that session register/unregister entries are not compacted from the log
    // until all associated commands have been cleaned.
    if (session == null) {
      entry.compact(Compaction.Mode.QUORUM);
      return Futures.exceptionalFuture(new UnknownSessionException("unknown session: " + entry.entry().session()));
    }
    // If the session is not in an active state, return an UnknownSessionException. Sessions are retained in the
    // session registry until all prior commands have been released by the state machine, but new commands can
    // only be applied for sessions in an active state.
    else if (!session.state().active()) {
      entry.compact(Compaction.Mode.QUORUM);
      return Futures.exceptionalFuture(new UnknownSessionException("inactive session: " + entry.entry().session()));
    }
    // If the command's sequence number is less than the next session sequence number then that indicates that
    // we've received a command that was previously applied to the state machine. Ensure linearizability by
    // returning the cached response instead of applying it to the user defined state machine.
    else if (entry.entry().sequence() > 0 && entry.entry().sequence() < session.nextCommandSequence()) {
      // Ensure the response check is executed in the state machine thread in order to ensure the
      // command was applied, otherwise there will be a race condition and concurrent modification issues.
      long sequence = entry.entry().sequence();

      // Switch to the state machine thread and get the existing response.
      context.executor.execute(() -> sequenceCommand(sequence, session, future));
      return future;
    }
    // If we've made it this far, the command must have been applied in the proper order as sequenced by the
    // session. This should be the case for most commands applied to the state machine.
    else {
      // Allow the executor to execute any scheduled events.
      long index = entry.index();
      long sequence = entry.entry().sequence();

      // Calculate the updated timestamp for the command.
      long timestamp = context.timestamp(entry.entry().timestamp());

      // Execute the command in the state machine thread. Once complete, the CompletableFuture callback will be completed
      // in the state machine thread. Register the result in that thread and then complete the future in the caller's thread.
      ServerCommit commit = new ServerCommit(Commit.Type.COMMAND, entry, session, timestamp);
      context.executor.execute(() -> executeCommand(index, sequence, timestamp, commit, session, future));

      // Update the last applied index prior to the command sequence number. This is necessary to ensure queries sequenced
      // at this index receive the index of the command.
      setLastApplied(index);

      // Update the session timestamp and command sequence number. This is done in the caller's thread since all
      // timestamp/index/sequence checks are done in this thread prior to executing operations on the state machine thread.
      session.setTimestamp(timestamp).setCommandSequence(sequence);
      return future;
    }
  }

  /**
   * Sequences a command according to the command sequence number.
   */
  private void sequenceCommand(long sequence, ServerSession session, CompletableFuture<Result> future) {
    context.checkThread();

    if (!log.isOpen()) {
      executor.execute(() -> future.completeExceptionally(new IllegalStateException("log closed")));
      return;
    }

    Result result = session.getResult(sequence);
    if (result == null) {
      executor.execute(() -> future.completeExceptionally(new RuntimeException("missing result")));
    } else {
      executor.execute(() -> future.complete(result));
    }
  }

  /**
   * Executes a state machine command.
   */
  private void executeCommand(long index, long sequence, long timestamp, ServerCommit commit, ServerSession session, CompletableFuture<Result> future) {
    context.checkThread();

    if (!log.isOpen()) {
      executor.execute(() -> future.completeExceptionally(new IllegalStateException("log closed")));
      return;
    }

    // If the session is already in an inactive state, complete the future exceptionally.
    if (!session.state().active()) {
      executor.execute(() -> future.completeExceptionally(new UnknownSessionException("inactive session: " + session.id())));
      return;
    }

    // Trigger scheduled callbacks in the state machine.
    context.tick(index, timestamp);

    // Update the state machine context with the commit index and local server context. The synchronous flag
    // indicates whether the server expects linearizable completion of published events. Events will be published
    // based on the configured consistency level for the context.
    context.init(commit.index(), commit.time(), ServerStateMachineContext.Type.COMMAND);

    // Store the event index to return in the command response.
    long eventIndex = session.getEventIndex();

    try {
      // Execute the state machine operation and get the result.e
      Buffer output = stateMachine.apply(commit);

      // Once the operation has been applied to the state machine, commit events published by the command.
      // The state machine context will build a composite future for events published to all sessions.
      context.commit();

      // Store the result for linearizability and complete the command.
      Result result = new Result(index, eventIndex, output, null);
      session.registerResult(sequence, result);
      executor.execute(() -> future.complete(result));
    } catch (Exception e) {
      // If an exception occurs during execution of the command, store the exception.
      Result result = new Result(index, eventIndex, null, e);
      session.registerResult(sequence, result);
      executor.execute(() -> future.complete(result));
    }
  }

  /**
   * Applies a query entry to the state machine.
   * <p>
   * Query entries are applied to the user {@link StateMachine} for read-only operations.
   * Because queries are read-only, they may only be applied on a single server in the cluster,
   * and query entries do not go through the Raft log. Thus, it is critical that measures be taken
   * to ensure clients see a consistent view of the cluster event when switching servers. To do so,
   * clients provide a sequence and version number for each query. The sequence number is the order
   * in which the query was sent by the client. Sequence numbers are shared across both commands and
   * queries. The version number indicates the last index for which the client saw a command or query
   * response. In the event that the lastApplied index of this state machine does not meet the provided
   * version number, we wait for the state machine to catch up before applying the query. This ensures
   * clients see state progress monotonically even when switching servers.
   * <p>
   * Because queries may only be applied on a single server in the cluster they cannot result in the
   * publishing of session events. Events require commands to be written to the Raft log to ensure
   * fault-tolerance and consistency across the cluster.
   */
  @SuppressWarnings("unchecked")
  private CompletableFuture<Result> applyQuery(Indexed<QueryEntry> entry) {
    ServerSession session = context.sessions().getSession(entry.entry().session());

    // If the session is null then that indicates that the session already timed out or it never existed.
    // Return with an UnknownSessionException.
    if (session == null) {
      return Futures.exceptionalFuture(new UnknownSessionException("unknown session " + entry.entry().session()));
    }
    // If the session is not in an active state, return an UnknownSessionException. Sessions are retained in the
    // session registry until all prior commands have been released by the state machine, but new operations can
    // only be applied for sessions in an active state.
    else if (!session.state().active()) {
      return Futures.exceptionalFuture(new UnknownSessionException("inactive session: " + entry.entry().session()));
    } else {
      CompletableFuture<Result> future = new CompletableFuture<>();
      ServerCommit commit = new ServerCommit(Commit.Type.QUERY, new Indexed<>(lastApplied, entry.term(), entry.entry(), entry.size()), session, context.timestamp());
      context.executor.execute(() -> executeQuery(commit, session, future));
      return future;
    }
  }

  /**
   * Executes a state machine query.
   */
  private void executeQuery(ServerCommit commit, ServerSession session, CompletableFuture<Result> future) {
    context.checkThread();

    if (!log.isOpen()) {
      executor.execute(() -> future.completeExceptionally(new IllegalStateException("log closed")));
      return;
    }

    // If the session is already in an inactive state, complete the future exceptionally.
    if (!session.state().active()) {
      executor.execute(() -> future.completeExceptionally(new UnknownSessionException("inactive session: " + session.id())));
      return;
    }

    long index = commit.index();
    long eventIndex = session.getEventIndex();

    // Update the state machine context with the query entry's index. We set a null consistency
    // level to indicate that events cannot be published in this context. Publishing events in
    // response to state machine queries is non-deterministic as queries are not replicated.
    context.init(index, commit.time(), ServerStateMachineContext.Type.QUERY);

    try {
      Buffer result = stateMachine.apply(commit);
      executor.execute(() -> future.complete(new Result(index, eventIndex, result, null)));
    } catch (Exception e) {
      executor.execute(() -> future.complete(new Result(index, eventIndex, null, e)));
    }
  }

  /**
   * Applies an initialize entry to the state machine.
   * <p>
   * Initialize entries are committed by leaders at the start of their term. Typically, no-op entries
   * serve as a mechanism to allow leaders to commit entries from prior terms. However, we extend
   * the functionality of the no-op entry to use it as an indicator that a leadership change occurred.
   * In order to ensure timeouts do not expire during lengthy leadership changes, we use no-op entries
   * to reset timeouts for client sessions and server heartbeats.
   */
  private CompletableFuture<Long> applyInitialize(Indexed<InitializeEntry> entry) {
    // Iterate through all the server sessions and reset timestamps. This ensures that sessions do not
    // timeout during leadership changes or shortly thereafter.
    long timestamp = context.timestamp(entry.entry().timestamp());
    for (ServerSession session : context.sessions().sessions.values()) {
      session.setTimestamp(timestamp);
    }
    entry.compact(Compaction.Mode.SEQUENTIAL);
    return Futures.completedFuture(entry.index());
  }

  /**
   * Marked as suspicious any sessions that have timed out according to the given timestamp.
   * <p>
   * Sessions are marked suspicious instead of being expired since log cleaning can result in large
   * gaps in time between entries in the log. Thus, once log compaction has occurred, it's possible
   * that a session could be marked expired when in fact its keep alive entries were simply compacted
   * from the log. Forcing the leader to expire sessions ensures that keep alives are not missed with
   * regard to session expiration.
   */
  private void suspectSessions(long exclude, long timestamp) {
    for (ServerSession session : context.sessions().sessions.values()) {
      if (session.id() != exclude && timestamp - session.timeout() > session.getTimestamp()) {
        session.suspect();
      }
    }
  }

  @Override
  public void close() {
    executor.close();
  }

  /**
   * State machine result.
   */
  static final class Result {
    final long index;
    final long eventIndex;
    final Buffer result;
    final Exception error;

    Result(long index, long eventIndex, Buffer result, Exception error) {
      this.index = index;
      this.eventIndex = eventIndex;
      this.result = result;
      this.error = error;
    }
  }

}
