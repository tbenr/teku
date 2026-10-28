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
import java.util.stream.Collectors;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.spec.datastructures.state.Checkpoint;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.storage.api.HotState;
import tech.pegasys.teku.storage.api.HotStateNodeInfo;
import tech.pegasys.teku.storage.api.StorageUpdate;
import tech.pegasys.teku.storage.server.DatabaseStorageException;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.KvStoreCombinedDao;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.KvStoreCombinedDao.HotUpdater;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff.StateDiffDag;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff.StateDiffDagTransaction;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff.StateDiffVerificationException;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff.StateDiffVerificationReport;

/**
 * Hot states and the finalized checkpoint block state stored as nodes of the state diff DAG, on a
 * database carrying the state diff mode marker.
 */
class StateDiffHotStateStorage implements HotStateStorage {
  private static final Bytes STATE_DIFF_MODE_MARKER = Bytes.of(1);

  private final KvStoreCombinedDao dao;
  private final StateDiffDag dag;
  private final boolean storesFinalizedStates;

  /**
   * The finalized anchor state reconstructed from the state diff DAG when the store was loaded,
   * reused while the finalized checkpoint keeps its block root and cleared when an update sets a
   * finalized checkpoint.
   */
  private volatile Optional<ReconstructedAnchor> reconstructedAnchor = Optional.empty();

  StateDiffHotStateStorage(
      final KvStoreCombinedDao dao, final StateDiffDag dag, final boolean storesFinalizedStates) {
    this.dao = dao;
    this.dag = dag;
    this.storesFinalizedStates = storesFinalizedStates;
  }

  @Override
  public boolean isStateDiffStorage() {
    return true;
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
    final Optional<ReconstructedAnchor> anchor = reconstructedAnchor;
    return dao.getFinalizedCheckpoint()
        .map(
            checkpoint ->
                anchor
                    .filter(reconstructed -> reconstructed.blockRoot().equals(checkpoint.getRoot()))
                    .map(ReconstructedAnchor::state)
                    .orElseGet(() -> reconstructFinalizedState(checkpoint)));
  }

  @Override
  public Optional<BeaconState> loadLatestFinalizedState() {
    final Optional<Checkpoint> finalizedCheckpoint = dao.getFinalizedCheckpoint();
    final Optional<BeaconState> finalizedState = getLatestFinalizedState();
    if (finalizedCheckpoint.isPresent() && finalizedState.isPresent()) {
      reconstructedAnchor =
          Optional.of(
              new ReconstructedAnchor(finalizedCheckpoint.get().getRoot(), finalizedState.get()));
    }
    return finalizedState;
  }

  private BeaconState reconstructFinalizedState(final Checkpoint checkpoint) {
    try {
      return dag.reconstruct(dao, checkpoint.getRoot())
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

  /** Writes the state diff mode marker and {@code anchor} as a snapshot node. */
  void writeAnchorSnapshot(
      final HotUpdater updater, final Checkpoint anchor, final BeaconState anchorState) {
    final StateDiffDagTransaction transaction = dag.begin(dao, updater);
    transaction.writeNode(
        anchor.getRoot(), anchorState, anchor.getEpoch(), anchor.getEpoch(), List.of(), true);
    transaction.commit();
    updater.setStateDiffMode(STATE_DIFF_MODE_MARKER);
  }

  @Override
  public HotStateUpdate startUpdate(final StorageUpdate update) {
    // The anchor being replaced, read before the new finalized checkpoint is written
    final Optional<Bytes32> previousAnchorRoot =
        dao.getFinalizedCheckpoint().map(Checkpoint::getRoot);
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
        updateStateDiffDag(updater, update, previousAnchorRoot);
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
   * when its chain cannot be loaded), then deletes the nodes of pruned fork blocks. The supernode
   * reference changes are written to {@code updater} before it is committed.
   */
  private void updateStateDiffDag(
      final HotUpdater updater,
      final StorageUpdate update,
      final Optional<Bytes32> previousAnchorRoot) {
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
                    storesFinalizedStates));
    transaction.deleteNodes(getPrunedForkBlockRoots(update));
    transaction.commit();
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
