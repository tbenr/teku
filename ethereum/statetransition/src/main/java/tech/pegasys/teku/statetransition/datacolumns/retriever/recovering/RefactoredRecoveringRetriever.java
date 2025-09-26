package tech.pegasys.teku.statetransition.datacolumns.retriever.recovering;

import com.google.common.annotations.VisibleForTesting;
import java.time.Duration;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.Comparator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.async.AsyncRunner;
import tech.pegasys.teku.infrastructure.async.Cancellable;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.time.TimeProvider;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.kzg.KZG;
import tech.pegasys.teku.spec.datastructures.blobs.versions.fulu.DataColumnSidecar;
import tech.pegasys.teku.spec.datastructures.blocks.BeaconBlock;
import tech.pegasys.teku.spec.datastructures.blocks.SlotAndBlockRoot;
import tech.pegasys.teku.spec.datastructures.util.DataColumnSlotAndIdentifier;
import tech.pegasys.teku.spec.logic.versions.fulu.helpers.MiscHelpersFulu;
import tech.pegasys.teku.statetransition.datacolumns.CanonicalBlockResolver;
import tech.pegasys.teku.statetransition.datacolumns.db.DataColumnSidecarDbAccessor;
import tech.pegasys.teku.statetransition.datacolumns.retriever.DataColumnSidecarRetriever;

/**
 * A decorator for a DataColumnSidecarRetriever that adds a fault-tolerant recovery layer.
 * If the delegate retriever is too slow, this class initiates a BlockRecoveryTask to reconstruct
 * the required sidecar by gathering at least 50% of the columns for the same block.
 */
public class RefactoredRecoveringRetriever implements DataColumnSidecarRetriever {
  private static final Logger LOG = LogManager.getLogger();

  // Dependencies
  private final DataColumnSidecarRetriever delegate;
  private final CanonicalBlockResolver canonicalBlockResolver;
  private final AsyncRunner asyncRunner;
  private final TimeProvider timeProvider;
  private final Duration recoveryInitiationTimeout;
  private final Duration recoveryCheckInterval;
  private final BlockSidecarsRecoveryTaskFactory taskFactory;

  // State
  private final Set<PendingRequest> pendingRequests = new ConcurrentSkipListSet<>(
          Comparator.comparing(PendingRequest::timestamp)
                  .thenComparing(PendingRequest::columnId));
  private final Map<Bytes32, BlockSidecarsRecoveryTask> recoveryTasksByBlockRoot = new ConcurrentHashMap<>();
  private Cancellable pendingRequestsChecker;

  private record PendingRequest(
          DataColumnSlotAndIdentifier columnId,
          SafeFuture<DataColumnSidecar> response,
          UInt64 timestamp) {}

  public RefactoredRecoveringRetriever(
          final DataColumnSidecarRetriever delegate,
          final KZG kzg,
          final MiscHelpersFulu miscHelpersFulu,
          final CanonicalBlockResolver canonicalBlockResolver,
          final DataColumnSidecarDbAccessor sidecarDB,
          final AsyncRunner asyncRunner,
          final Duration recoveryInitiationTimeout,
          final Duration recoveryCheckInterval,
          final TimeProvider timeProvider,
          final int numberOfColumns) {
    this.delegate = delegate;
    this.canonicalBlockResolver = canonicalBlockResolver;
    this.asyncRunner = asyncRunner;
    this.timeProvider = timeProvider;
    this.recoveryInitiationTimeout = recoveryInitiationTimeout;
    this.recoveryCheckInterval = recoveryCheckInterval;
    this.taskFactory = new BlockSidecarsRecoveryTaskFactory(
            delegate, kzg, miscHelpersFulu, sidecarDB, asyncRunner, numberOfColumns);
  }

  @Override
  public synchronized void start() {
    if (pendingRequestsChecker != null) {
      return;
    }
    pendingRequestsChecker = asyncRunner.runWithFixedDelay(
            this::checkPendingRequests,
            recoveryCheckInterval,
            recoveryCheckInterval,
            err -> LOG.error("Error while checking for slow sidecar requests", err));
  }

  @Override
  public synchronized void stop() {
    if (pendingRequestsChecker != null) {
      pendingRequestsChecker.cancel();
      pendingRequestsChecker = null;
    }
    recoveryTasksByBlockRoot.values().forEach(BlockSidecarsRecoveryTask::cancel);
    recoveryTasksByBlockRoot.clear();
    pendingRequests.clear();
  }

  final private Random random = new Random();
  private boolean dropForTesting = false;

  @Override
  public SafeFuture<DataColumnSidecar> retrieve(final DataColumnSlotAndIdentifier columnId) {
    final SafeFuture<DataColumnSidecar> response;
    if(dropForTesting && random.nextInt(4) == 0) {
      LOG.info("Dropping DAS retrieval for testing");
      response = new SafeFuture<>();
    } else {
      response = delegate.retrieve(columnId);
    }

    final PendingRequest pendingRequest =
            new PendingRequest(columnId, response, timeProvider.getTimeInMillis());
    pendingRequests.add(pendingRequest);

    // Immediate cleanup on completion
    response.always(() -> pendingRequests.remove(pendingRequest));

    return response;
  }

  private void checkPendingRequests() {
    final UInt64 currentTime = timeProvider.getTimeInMillis();
    final UInt64 timeoutThreshold = currentTime.minus(recoveryInitiationTimeout.toMillis());

    pendingRequests.removeIf(request -> {
      if (request.response().isDone()) {
        return true; // Already handled by the .always() callback, but good for safety
      }
      if (request.timestamp().isGreaterThan(timeoutThreshold)) {
        return false; // Not timed out yet
      }

      // Timed out, initiate recovery
      LOG.info("Request for {} has timed out. Initiating recovery.", request.columnId());
      initiateRecovery(request.columnId(), request.response());
      return true; // Remove from pending and hand over to a recovery task
    });
  }

  private void initiateRecovery(
          final DataColumnSlotAndIdentifier columnId, final SafeFuture<DataColumnSidecar> response) {
//    canonicalBlockResolver
//            .getBlockAtSlot(columnId.slot())
//            .thenAccept(
//                    maybeBlock -> {
//                      if (maybeBlock.map(b -> b.getRoot().equals(columnId.blockRoot())).orElse(false)) {
                        final BlockSidecarsRecoveryTask task = findOrCreateRecoveryTask(columnId.getSlotAndBlockRoot());
                        task.addRequest(columnId.columnIndex(), response);
//                      } else {
//                        LOG.warn("Cannot recover sidecar for non-canonical block: {}", columnId);
//                        response.completeExceptionally(
//                                new NotOnCanonicalChainException(columnId, maybeBlock));
//                      }
//                    })
//            .finish(err -> {
//              LOG.error("Failed to check canonical chain for sidecar recovery: {}", columnId, err);
//              response.completeExceptionally(err);
//            });
  }

  private BlockSidecarsRecoveryTask findOrCreateRecoveryTask(final SlotAndBlockRoot slotAndBlockRoot) {
    return recoveryTasksByBlockRoot.computeIfAbsent(
            slotAndBlockRoot.getBlockRoot(),
            blockRoot -> {
              LOG.info("Creating new recovery task for block: {}", slotAndBlockRoot.getBlockRoot());
              final BlockSidecarsRecoveryTask newTask = taskFactory.create(slotAndBlockRoot);
              // When the task completes (succeeds, fails, or is cancelled), remove it from our map.
              newTask
                      .getCompletionFuture()
                      .always(() -> recoveryTasksByBlockRoot.remove(blockRoot, newTask));
              newTask.start();
              return newTask;
            });
  }

  @Override
  public void flush() {
    delegate.flush();
  }

  @Override
  public void onNewValidatedSidecar(final DataColumnSidecar sidecar) {
    delegate.onNewValidatedSidecar(sidecar);
    // Also notify any relevant recovery tasks
    final BlockSidecarsRecoveryTask task = recoveryTasksByBlockRoot.get(sidecar.getBlockRoot());
    if (task != null) {
      task.onNewValidatedSidecar(sidecar);
    }
  }

  @VisibleForTesting
  public int pendingRequestsCount() {
    return pendingRequests.size();
  }

  @VisibleForTesting
  public int recoveryTaskCount() {
    return recoveryTasksByBlockRoot.size();
  }
}