package tech.pegasys.teku.statetransition.datacolumns.retriever.recovering;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tech.pegasys.teku.infrastructure.async.AsyncRunner;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.kzg.KZG;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.DataColumnSidecar;
import tech.pegasys.teku.spec.datastructures.blocks.SlotAndBlockRoot;
import tech.pegasys.teku.spec.datastructures.util.DataColumnSlotAndIdentifier;
import tech.pegasys.teku.spec.logic.versions.fulu.helpers.MiscHelpersFulu;
import tech.pegasys.teku.statetransition.datacolumns.db.DataColumnSidecarDbAccessor;
import tech.pegasys.teku.statetransition.datacolumns.retriever.DataColumnSidecarRetriever;

/**
 * Manages the state and logic for recovering all data column sidecars for a single block.
 * This involves gathering a quorum of columns (>=50%) from the database or peers, and then
 * performing KZG reconstruction.
 */
public class BlockSidecarsRecoveryTask {
  private static final Logger LOG = LogManager.getLogger();

  private final SlotAndBlockRoot slotAndBlockRoot;
  private final DataColumnSidecarRetriever delegate;
  private final KZG kzg;
  private final MiscHelpersFulu miscHelpers;
  private final DataColumnSidecarDbAccessor sidecarDB;
  private final AsyncRunner asyncRunner;
  private final int numberOfColumns;
  private final int numberOfColumnsRequired;

  private final Map<UInt64, DataColumnSidecar> existingSidecars = new ConcurrentHashMap<>();
  private final Map<UInt64, List<SafeFuture<DataColumnSidecar>>> pendingFuturesByColumn =
          new ConcurrentHashMap<>();
  private final SafeFuture<Void> completionFuture = new SafeFuture<>();
  private final AtomicBoolean reconstructionStarted = new AtomicBoolean(false);

  private volatile List<SafeFuture<DataColumnSidecar>> recoveryPeerRequests;

  public BlockSidecarsRecoveryTask(
          final SlotAndBlockRoot slotAndBlockRoot,
          final DataColumnSidecarRetriever delegate,
          final KZG kzg,
          final MiscHelpersFulu miscHelpers,
          final DataColumnSidecarDbAccessor sidecarDB,
          final AsyncRunner asyncRunner,
          final int numberOfColumns,
          final int numberOfColumnsRequired) {
    this.slotAndBlockRoot = slotAndBlockRoot;
    this.delegate = delegate;
    this.kzg = kzg;
    this.miscHelpers = miscHelpers;
    this.sidecarDB = sidecarDB;
    this.asyncRunner = asyncRunner;
    this.numberOfColumns = numberOfColumns;
    this.numberOfColumnsRequired = numberOfColumnsRequired;
  }

  /** Kicks off the recovery process, starting with a database check. */
  public void start() {
    sidecarDB
            .getColumnIdentifiers(slotAndBlockRoot)
            .thenAccept(this::processSidecarsFromDb)
            .finish(err -> LOG.error("Failed to query DB for existing sidecars for block {}",
                    slotAndBlockRoot.getBlockRoot(), err));
  }

  /**
   * Adds a request for a specific column to this recovery task. If the column is already
   * available, the future is completed immediately.
   */
  public void addRequest(final UInt64 columnIndex, final SafeFuture<DataColumnSidecar> response) {
    if (completionFuture.isDone()) {
      response.cancel(true);
      return;
    }

    final DataColumnSidecar existing = existingSidecars.get(columnIndex);
    if (existing != null) {
      response.complete(existing);
      return;
    }

    pendingFuturesByColumn
            .computeIfAbsent(columnIndex, __ -> new CopyOnWriteArrayList<>())
            .add(response);

    // Add a listener to react if this specific request is cancelled.
    response.whenComplete((__, err) -> {
      if (response.isCancelled()) {
        onRequestCancelled(columnIndex, response);
      }
    });
  }

  /**
   * Notifies the task of a newly available sidecar, which may have been received from the network.
   * This can trigger reconstruction if the quorum is met.
   */
  public void onNewValidatedSidecar(final DataColumnSidecar sidecar) {
    if (completionFuture.isDone() || !sidecar.getBlockRoot().equals(slotAndBlockRoot.getBlockRoot())) {
      return;
    }
    addSidecar(sidecar);
    checkAndAttemptReconstruction();
  }

  private void processSidecarsFromDb(final List<DataColumnSlotAndIdentifier> columnIds) {
    final List<SafeFuture<Void>> futures = columnIds.stream()
            .limit(numberOfColumnsRequired)
            .map(id -> sidecarDB.getSidecar(id).thenAccept(maybeSidecar -> maybeSidecar.ifPresent(this::addSidecar)))
            .toList();

    SafeFuture.allOf(futures.toArray(new SafeFuture[0])).always(() -> {
      // After checking the DB, see if we have enough to reconstruct.
      // If not, fall back to fetching from peers.
      if (!checkAndAttemptReconstruction()) {
        attemptRecoveryViaPeers();
      }
    });
  }

  private void addSidecar(final DataColumnSidecar sidecar) {
    existingSidecars.put(sidecar.getIndex(), sidecar);
    final List<SafeFuture<DataColumnSidecar>> pending = pendingFuturesByColumn.remove(sidecar.getIndex());
    if (pending != null) {
      pending.forEach(future -> future.complete(sidecar));
    }
  }

  private boolean checkAndAttemptReconstruction() {
    if (existingSidecars.size() >= numberOfColumnsRequired && reconstructionStarted.compareAndSet(false, true)) {
      LOG.info("Quorum of {}/{} sidecars met for block {}. Starting reconstruction.",
              existingSidecars.size(), numberOfColumnsRequired, slotAndBlockRoot.getBlockRoot());
      asyncRunner.runAsync(this::reconstruct).propagateTo(completionFuture);
      return true;
    }
    return false;
  }

  private void attemptRecoveryViaPeers() {
    if (completionFuture.isDone() || recoveryPeerRequests != null) {
      return;
    }
    LOG.info("Attempting to recover missing sidecars for {} via peers.", slotAndBlockRoot.getBlockRoot());
    recoveryPeerRequests = IntStream.range(0, numberOfColumns)
            .mapToObj(UInt64::valueOf)
            .filter(idx -> !existingSidecars.containsKey(idx))
            .map(this::fetchSidecarFromPeer)
            .toList();
  }



  private SafeFuture<DataColumnSidecar> fetchSidecarFromPeer(final UInt64 columnIndex) {
    final DataColumnSlotAndIdentifier columnId = new DataColumnSlotAndIdentifier(
            slotAndBlockRoot.getSlot(), slotAndBlockRoot.getBlockRoot(), columnIndex);
    final SafeFuture<DataColumnSidecar> future = delegate.retrieve(columnId);
    future.thenAccept(sidecar -> {
      LOG.info("Successfully retrieved sidecar {} for recovery of block {}",
              sidecar.getIndex(), slotAndBlockRoot.getBlockRoot());
      onNewValidatedSidecar(sidecar);
    }).ignoreCancelException()
            .finish(err -> LOG.info("Failed to retrieve sidecar {} for recovery", columnId, err));

    return future;
  }

  private SafeFuture<Void> reconstruct() {
    try {
      final Map<UInt64, DataColumnSidecar> reconstructed = miscHelpers
              .reconstructAllDataColumnSidecars(existingSidecars.values(), kzg)
              .stream()
              .collect(Collectors.toUnmodifiableMap(DataColumnSidecar::getIndex, s -> s));

      // Fulfill all remaining pending requests
      pendingFuturesByColumn.forEach((colIdx, futures) -> {
        final DataColumnSidecar sidecar = reconstructed.get(colIdx);
        if (sidecar != null) {
          futures.forEach(future -> future.complete(sidecar));
        } else {
          futures.forEach(future -> future.completeExceptionally(
                  new IllegalStateException("Reconstruction failed to produce column " + colIdx)));
        }
      });
      reconstructionComplete();
      return SafeFuture.COMPLETE;
    } catch (final Exception e) {
      LOG.error("Fatal error during sidecar reconstruction for block {}", slotAndBlockRoot.getBlockRoot(), e);
      // In case of reconstruction failure, fail all pending futures and attempt recovery again
      reconstructionStarted.set(false);
      attemptRecoveryViaPeers();
      return SafeFuture.failedFuture(e);
    }
  }

  private void reconstructionComplete() {
    LOG.info("Reconstruction complete for block {}", slotAndBlockRoot.getBlockRoot());
    pendingFuturesByColumn.clear();
    cancelPeerRequests();
    completionFuture.complete(null);
  }

  /**
   * Handles the cancellation of a single pending request. If all requests for this recovery task
   * have been cancelled, the entire task will be shut down.
   */
  private synchronized void onRequestCancelled(
          final UInt64 columnIndex, final SafeFuture<DataColumnSidecar> response) {
    final List<SafeFuture<DataColumnSidecar>> futures = pendingFuturesByColumn.get(columnIndex);
    if (futures != null) {
      futures.remove(response);
      if (futures.isEmpty()) {
        pendingFuturesByColumn.remove(columnIndex);
      }
    }

    // If there are no more pending requests waiting for a result,
    // there's no reason for this task to continue running.
    if (pendingFuturesByColumn.isEmpty()) {
      LOG.info(
              "All requests for block {} have been cancelled. Shutting down recovery task.",
              slotAndBlockRoot.getBlockRoot());
      this.cancel(); // This will cancel all recoveryPeerRequests
    }
  }

  /** Cancels the entire recovery task, failing any pending futures. */
  public void cancel() {
    if (!completionFuture.isDone()) {
      LOG.info("Cancelling recovery task for block {}", slotAndBlockRoot.getBlockRoot());
      pendingFuturesByColumn.values().stream()
              .flatMap(Collection::stream)
              .forEach(future -> future.cancel(true));
      pendingFuturesByColumn.clear();
      cancelPeerRequests();
      completionFuture.cancel(true);
    }
  }

  private void cancelPeerRequests() {
    if (recoveryPeerRequests != null) {
      recoveryPeerRequests.forEach(request -> request.cancel(true));
    }
  }

  /**
   * Returns a future that completes when this task is finished (either by successful
   * reconstruction, cancellation, or fatal error).
   */
  public SafeFuture<Void> getCompletionFuture() {
    return completionFuture;
  }
}
