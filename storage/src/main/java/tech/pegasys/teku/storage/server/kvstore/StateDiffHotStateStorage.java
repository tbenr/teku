/*
 * Copyright (c) 2026 Consensys Incorporated. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.storage.server.kvstore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.state.Checkpoint;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.storage.api.HotState;
import tech.pegasys.teku.storage.api.HotStateNodeInfo;
import tech.pegasys.teku.storage.api.StorageUpdate;
import tech.pegasys.teku.storage.server.DatabaseStorageException;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.KvStoreCombinedDao;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.KvStoreCombinedDao.HotUpdater;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff.FinalizedRetention;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff.StateDiffDag;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff.StateDiffDagConfig;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff.StateDiffDagTransaction;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff.StateDiffVerificationException;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff.StateDiffVerificationReport;

/**
 * Hot states and the finalized checkpoint block state stored as nodes of the state diff DAG, on a
 * database carrying the state diff mode marker.
 *
 * <p>On an archive database the DAG also holds the finalized states: every finalized node from the
 * window start on is kept and served, either all of them since the switch (no retention window) or
 * those of the last {@link StateDiffDagConfig#retainedSlots()} slots before the finalized
 * checkpoint plus the reconstruction chain of the oldest one (decision 43).
 */
class StateDiffHotStateStorage implements HotStateStorage {
  private static final Logger LOG = LogManager.getLogger();
  private static final Bytes STATE_DIFF_MODE_MARKER = Bytes.of(1);

  /**
   * How far the window start moves at one finalization, so that a backlog (a lowered window, or a
   * database that kept everything) is deleted in bounded steps.
   */
  static final int MAX_WINDOW_STEP_EPOCHS = 256;

  private final KvStoreCombinedDao dao;
  private final StateDiffDag dag;
  private final Spec spec;
  private final boolean storesFinalizedStates;

  /**
   * The finalized anchor state reconstructed from the state diff DAG when the store was loaded,
   * reused while the finalized checkpoint keeps its block root and cleared when an update sets a
   * finalized checkpoint.
   */
  private volatile Optional<ReconstructedAnchor> reconstructedAnchor = Optional.empty();

  StateDiffHotStateStorage(
      final KvStoreCombinedDao dao,
      final StateDiffDag dag,
      final Spec spec,
      final boolean storesFinalizedStates) {
    this.dao = dao;
    this.dag = dag;
    this.spec = spec;
    this.storesFinalizedStates = storesFinalizedStates;
  }

  @Override
  public boolean isStateDiffStorage() {
    return true;
  }

  @Override
  public boolean holdsFinalizedStates() {
    return storesFinalizedStates;
  }

  /**
   * {@inheritDoc}
   *
   * <p>On an archive database, the state of the node of the checkpoint block of {@code maxSlot}'s
   * epoch (the latest finalized block at or before the epoch's start slot), or of the latest
   * earlier checkpoint block with a node when that node was skipped; empty before the window start
   * (history before the switch to state diff storage is not served, decision 19).
   *
   * @throws StateDiffVerificationException when the node cannot be reconstructed
   */
  @Override
  public Optional<BeaconState> getLatestAvailableFinalizedState(final UInt64 maxSlot) {
    if (!storesFinalizedStates) {
      return dao.getLatestAvailableFinalizedState(maxSlot);
    }
    final UInt64 epoch = spec.computeEpochAtSlot(maxSlot);
    // Without a recorded start, only the checkpoint block of the epoch itself is looked up
    final UInt64 windowStart = dao.getStateDiffWindowStart().orElse(epoch);
    if (epoch.isLessThan(windowStart)) {
      return Optional.empty();
    }
    return findCheckpointNode(epoch, windowStart, root -> dag.reconstruct(dao, root));
  }

  /**
   * {@inheritDoc}
   *
   * <p>On an archive database, the start slot of the window start epoch.
   */
  @Override
  public Optional<UInt64> getEarliestAvailableFinalizedStateSlot() {
    if (!storesFinalizedStates) {
      return dao.getEarliestFinalizedStateSlot();
    }
    return dao.getStateDiffWindowStart().map(spec::computeStartSlotAtEpoch);
  }

  /**
   * {@inheritDoc}
   *
   * <p>The reconstructed anchor node.
   *
   * @throws DatabaseStorageException an unrecoverable one naming the way out when the anchor node
   *     cannot be reconstructed
   */
  @Override
  public Optional<BeaconState> getLatestFinalizedState() {
    return dao.getFinalizedCheckpoint().map(checkpoint -> finalizedState(checkpoint, false));
  }

  /**
   * {@inheritDoc}
   *
   * <p>The reconstructed anchor node, which is also put in the DAG's base cache: the first nodes
   * written after a restart diff against it or reconstruct their base from it.
   *
   * @throws DatabaseStorageException an unrecoverable one naming the way out when the anchor node
   *     cannot be reconstructed
   */
  @Override
  public Optional<BeaconState> loadLatestFinalizedState() {
    final Optional<Checkpoint> finalizedCheckpoint = dao.getFinalizedCheckpoint();
    final Optional<BeaconState> finalizedState =
        finalizedCheckpoint.map(checkpoint -> finalizedState(checkpoint, true));
    if (storesFinalizedStates) {
      dao.getStateDiffWindowStart().ifPresent(dag::recordWindowStart);
    }
    finalizedState.ifPresent(
        state ->
            reconstructedAnchor =
                Optional.of(
                    new ReconstructedAnchor(finalizedCheckpoint.orElseThrow().getRoot(), state)));
    return finalizedState;
  }

  /**
   * The memoized anchor state when it is the state of {@code checkpoint}, or else reconstructs it.
   */
  private BeaconState finalizedState(final Checkpoint checkpoint, final boolean cacheAsDiffBase) {
    return reconstructedAnchor
        .filter(reconstructed -> reconstructed.blockRoot().equals(checkpoint.getRoot()))
        .map(ReconstructedAnchor::state)
        .orElseGet(() -> reconstructFinalizedState(checkpoint, cacheAsDiffBase));
  }

  private BeaconState reconstructFinalizedState(
      final Checkpoint checkpoint, final boolean cacheAsDiffBase) {
    try {
      return (cacheAsDiffBase
              ? dag.reconstructAndCache(dao, checkpoint.getRoot())
              : dag.reconstruct(dao, checkpoint.getRoot()))
          .orElseThrow(
              () ->
                  new StateDiffVerificationException(
                      "No state diff node for the finalized checkpoint root "
                          + checkpoint.getRoot()));
    } catch (final StateDiffVerificationException e) {
      throw DatabaseStorageException.unrecoverable(
          "State diff storage cannot reconstruct the finalized state (epoch "
              + checkpoint.getEpoch()
              + ", block root "
              + checkpoint.getRoot()
              + "): "
              + e.getMessage()
              + ". Run `teku debug-tools db verify-state-diffs --data-path=<data path>` to"
              + " inspect; recover by re-syncing from a checkpoint into a fresh data directory.",
          e);
    }
  }

  @Override
  public Optional<Activation> getPendingActivation() {
    return Optional.empty();
  }

  @Override
  public HotStateStorage storeInitialAnchor(
      final HotUpdater updater, final Checkpoint anchorCheckpoint, final BeaconState anchorState) {
    writeAnchorSnapshot(updater, anchorCheckpoint, anchorState);
    return this;
  }

  /**
   * Writes the state diff mode marker and {@code anchor} as a snapshot node; on an archive
   * database, the finalized history served from the DAG starts at the anchor's epoch.
   */
  void writeAnchorSnapshot(
      final HotUpdater updater, final Checkpoint anchor, final BeaconState anchorState) {
    final StateDiffDagTransaction transaction = dag.begin(dao, updater);
    transaction.writeNode(
        anchor.getRoot(), anchorState, anchor.getEpoch(), anchor.getEpoch(), List.of(), true);
    if (storesFinalizedStates) {
      transaction.setWindowStart(anchor.getEpoch());
    }
    transaction.commit();
    updater.setStateDiffMode(STATE_DIFF_MODE_MARKER);
  }

  @Override
  public HotStateUpdate startUpdate(final StorageUpdate update) {
    // The anchor being replaced, read before the new finalized checkpoint is written
    final Optional<Checkpoint> previousAnchor = dao.getFinalizedCheckpoint();
    if (update.getFinalizedCheckpoint().isPresent()) {
      reconstructedAnchor = Optional.empty();
    }
    return new HotStateUpdate() {
      @Override
      public void writeLatestFinalizedState(final HotUpdater updater) {
        // The finalized checkpoint block state is its node, ensured by writeHotStates
      }

      @Override
      public void writeHotStates(final HotUpdater updater) {
        updateStateDiffDag(updater, update, previousAnchor);
      }

      @Override
      public String describeHotStatesWriteTime(final long millis) {
        return String.format(", state diff time: %d ms", millis);
      }
    };
  }

  /**
   * Applies the state diff DAG changes of {@code update} to the hot transaction: writes the nodes
   * of the hot states, then on finalization moves the DAG's anchor (which repairs the anchor node
   * when its chain cannot be loaded) and applies the finalized retention, then deletes the nodes of
   * pruned fork blocks. The supernode reference changes are written to {@code updater} before it is
   * committed.
   */
  private void updateStateDiffDag(
      final HotUpdater updater,
      final StorageUpdate update,
      final Optional<Checkpoint> previousAnchor) {
    final Optional<Bytes32> previousAnchorRoot = previousAnchor.map(Checkpoint::getRoot);
    final StateDiffDagTransaction transaction = dag.begin(dao, updater);
    // Oldest first, so a node can be the previous node of a later one in the same update
    update.getHotStates().entrySet().stream()
        .sorted(Comparator.comparing(entry -> entry.getValue().state().getSlot()))
        .forEach(entry -> writeHotStateNode(transaction, entry.getKey(), entry.getValue()));
    update
        .getFinalizedCheckpoint()
        .ifPresent(
            checkpoint ->
                transaction.onAnchorFinalized(
                    previousAnchorRoot,
                    checkpoint.getRoot(),
                    update.getLatestFinalizedState().orElseThrow(),
                    checkpoint.getEpoch(),
                    getFinalizedAncestorRoots(
                        update.getFinalizedChildToParentMap(),
                        checkpoint.getRoot(),
                        previousAnchorRoot),
                    update.getFinalizedChildToParentMap().keySet(),
                    finalizedRetention(transaction, previousAnchor, checkpoint.getEpoch())));
    transaction.deleteNodes(getPrunedForkBlockRoots(update));
    transaction.commit();
  }

  /**
   * The finalized nodes to keep when the anchor moves to {@code anchorEpoch}: the anchor's chain in
   * prune mode; on an archive database every node without a retention window, else the window of
   * the configured slots before the anchor, whose start moves by at most {@value
   * #MAX_WINDOW_STEP_EPOCHS} epochs at a time. The window's checkpoint roots are read from the
   * finalized blocks, which the update has already written.
   *
   * <p>An archive database without a recorded window start (switched to archive mode, or to state
   * diff storage before windows existed) kept no finalized node before the previous anchor's chain:
   * its history is recorded to start at the previous anchor, and nothing is deleted this time. A
   * prune database drops a window start left from archive mode.
   */
  private FinalizedRetention finalizedRetention(
      final StateDiffDagTransaction transaction,
      final Optional<Checkpoint> previousAnchor,
      final UInt64 anchorEpoch) {
    final Optional<UInt64> recordedStart = dao.getStateDiffWindowStart();
    if (!storesFinalizedStates) {
      if (recordedStart.isPresent()) {
        transaction.clearWindowStart();
      }
      return FinalizedRetention.anchorChain();
    }
    if (recordedStart.isEmpty()) {
      final UInt64 start = previousAnchor.map(Checkpoint::getEpoch).orElse(anchorEpoch);
      LOG.info(
          "State diff storage serves finalized states from epoch {}: no earlier finalized state was"
              + " kept",
          start);
      transaction.setWindowStart(start);
      return FinalizedRetention.all();
    }
    final long retainedSlots = dag.getConfig().retainedSlots();
    final UInt64 previousStart = recordedStart.get();
    if (retainedSlots == 0) {
      return FinalizedRetention.all();
    }
    final UInt64 targetStart =
        anchorEpoch.minusMinZero(retainedSlots / spec.slotsPerEpoch(anchorEpoch));
    if (!targetStart.isGreaterThan(previousStart)) {
      // Nothing left the window, or it was widened: history already deleted cannot come back
      return FinalizedRetention.all();
    }
    final UInt64 newStart = targetStart.min(previousStart.plus(MAX_WINDOW_STEP_EPOCHS));
    if (newStart.isLessThan(targetStart)) {
      LOG.info(
          "Deleting finalized states before epoch {} from state diff storage, {} epochs at a time:"
              + " the retention window starts at epoch {} now and at epoch {} once caught up",
          newStart,
          MAX_WINDOW_STEP_EPOCHS,
          newStart,
          targetStart);
    }
    return FinalizedRetention.window(
        newStart,
        findCheckpointNode(
            previousStart,
            UInt64.ZERO,
            root -> dao.getStateDiffNode(root).isPresent() ? Optional.of(root) : Optional.empty()),
        checkpointRoots(previousStart, newStart));
  }

  /**
   * Looks up the node of the checkpoint block of {@code epoch} with {@code load}, or when it has
   * none, of the latest earlier checkpoint block that has one, down to the checkpoint block of
   * {@code lowestEpoch}. A skipped node is the only reason a checkpoint block in that range has no
   * node.
   */
  private <T> Optional<T> findCheckpointNode(
      final UInt64 epoch, final UInt64 lowestEpoch, final Function<Bytes32, Optional<T>> load) {
    UInt64 current = epoch;
    while (true) {
      final Optional<SignedBeaconBlock> checkpointBlock = checkpointBlock(current);
      if (checkpointBlock.isEmpty()) {
        return Optional.empty();
      }
      final Optional<T> loaded = load.apply(checkpointBlock.get().getRoot());
      if (loaded.isPresent()) {
        return loaded;
      }
      // The next earlier checkpoint block is the one of this block's epoch, or of the epoch before
      // when this block sits on its epoch's start slot
      final UInt64 blockSlot = checkpointBlock.get().getSlot();
      final UInt64 blockEpoch = spec.computeEpochAtSlot(blockSlot);
      final boolean onStartSlot = spec.computeStartSlotAtEpoch(blockEpoch).equals(blockSlot);
      if (onStartSlot && blockEpoch.isZero()) {
        return Optional.empty();
      }
      final UInt64 previous = onStartSlot ? blockEpoch.decrement() : blockEpoch;
      if (previous.isLessThan(lowestEpoch)) {
        return Optional.empty();
      }
      current = previous;
    }
  }

  /** The distinct checkpoint block roots of the epochs {@code fromEpoch..toEpoch}, oldest first. */
  private List<Bytes32> checkpointRoots(final UInt64 fromEpoch, final UInt64 toEpoch) {
    final List<Bytes32> roots = new ArrayList<>();
    for (UInt64 epoch = fromEpoch; epoch.isLessThanOrEqualTo(toEpoch); epoch = epoch.increment()) {
      checkpointBlock(epoch)
          .map(SignedBeaconBlock::getRoot)
          .filter(root -> roots.isEmpty() || !roots.getLast().equals(root))
          .ifPresent(roots::add);
    }
    return roots;
  }

  /** The latest finalized block at or before the start slot of {@code epoch}. */
  private Optional<SignedBeaconBlock> checkpointBlock(final UInt64 epoch) {
    return dao.getLatestFinalizedBlockAtSlot(spec.computeStartSlotAtEpoch(epoch));
  }

  /**
   * Writes the node of the hot state of {@code blockRoot}.
   *
   * @throws IllegalStateException when the state has no place in the DAG, which a store persisting
   *     checkpoint block states always gives
   */
  private static void writeHotStateNode(
      final StateDiffDagTransaction transaction, final Bytes32 blockRoot, final HotState hotState) {
    final BeaconState state = hotState.state();
    final HotStateNodeInfo nodeInfo =
        hotState
            .nodeInfo()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "The state of block "
                            + blockRoot
                            + " at slot "
                            + state.getSlot()
                            + " has no place in the state diff DAG: the store does not persist"
                            + " checkpoint block states"));
    transaction.writeNodeOrSnapshot(
        blockRoot,
        state,
        nodeInfo.firstEpoch(),
        nodeInfo.lastEpoch(),
        nodeInfo.ancestorCheckpointRoots());
  }

  /**
   * The roots of the ancestors of the new anchor {@code anchorRoot} finalized by this update,
   * nearest first, followed by the previous anchor.
   */
  private static List<Bytes32> getFinalizedAncestorRoots(
      final Map<Bytes32, Bytes32> finalizedChildToParent,
      final Bytes32 anchorRoot,
      final Optional<Bytes32> previousAnchorRoot) {
    final List<Bytes32> ancestorRoots = new ArrayList<>();
    Bytes32 current = anchorRoot;
    // Bounded by the map size, so a malformed map cannot loop
    for (int i = 0; i < finalizedChildToParent.size(); i++) {
      final Bytes32 parent = finalizedChildToParent.get(current);
      if (parent == null || previousAnchorRoot.map(parent::equals).orElse(false)) {
        break;
      }
      ancestorRoots.add(parent);
      current = parent;
    }
    previousAnchorRoot.ifPresent(ancestorRoots::add);
    return ancestorRoots;
  }

  /** The pruned hot blocks that are not on the finalized chain. */
  private static Set<Bytes32> getPrunedForkBlockRoots(final StorageUpdate update) {
    if (update.getDeletedHotBlocks().isEmpty()) {
      return Set.of();
    }
    final Map<Bytes32, Bytes32> finalizedChildToParent = update.getFinalizedChildToParentMap();
    final Set<Bytes32> finalizedRoots = new HashSet<>(finalizedChildToParent.keySet());
    finalizedRoots.addAll(finalizedChildToParent.values());
    return update.getDeletedHotBlocks().keySet().stream()
        .filter(root -> !finalizedRoots.contains(root))
        .collect(Collectors.toSet());
  }

  /**
   * {@inheritDoc}
   *
   * <p>A node that cannot be reconstructed is reported as absent, so the state is regenerated by
   * replaying blocks from an earlier state, unless it is the finalized anchor's node, which has no
   * fallback.
   *
   * @throws StateDiffVerificationException when the finalized anchor's node cannot be reconstructed
   */
  @Override
  public Optional<BeaconState> getHotState(final Bytes32 root) {
    final boolean finalizedAnchor =
        dao.getFinalizedCheckpoint().map(Checkpoint::getRoot).orElse(Bytes32.ZERO).equals(root);
    final Optional<BeaconState> nodeState =
        finalizedAnchor ? dag.reconstruct(dao, root) : dag.reconstructOrEmpty(dao, root);
    // States written before the switch to state diffs stay in the legacy column until pruned
    return nodeState.or(() -> dao.getHotState(root));
  }

  @Override
  public StateDiffVerificationReport verify() {
    return dag.verify(dao);
  }

  private record ReconstructedAnchor(Bytes32 blockRoot, BeaconState state) {}
}
