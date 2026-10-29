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

package tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff;

import static com.google.common.base.Preconditions.checkState;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff.StateDiffDag.ChainLink;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff.StateDiffDag.NodeReader;

/**
 * Anchor recovery and finalized retention of one {@link StateDiffDagTransaction}: creating the
 * anchor node late, repairing it, and deleting the finalized-chain nodes that the anchor or the
 * archive window no longer needs. It reads and changes nodes only through the transaction, so it
 * sees the transaction's own writes and its deletions release supernode references like any other.
 */
class FinalizedChainMaintenance {

  private static final Logger LOG = LogManager.getLogger();

  private static final String ENSURE_FAILURE =
      "its missing node cannot be created because an ancestor node or its diff base cannot be read";
  private static final String RETAIN_FAILURE =
      "its chain or the previous anchor's cannot be loaded";

  private final StateDiffDag dag;
  private final StateDiffDagTransaction transaction;

  FinalizedChainMaintenance(final StateDiffDag dag, final StateDiffDagTransaction transaction) {
    this.dag = dag;
    this.transaction = transaction;
  }

  /** See {@link StateDiffDagTransaction#onAnchorFinalized}. */
  void onAnchorFinalized(
      final Optional<Bytes32> previousAnchorRoot,
      final Bytes32 anchorRoot,
      final BeaconState anchorState,
      final UInt64 anchorEpoch,
      final List<Bytes32> finalizedAncestorRoots,
      final Collection<Bytes32> newlyFinalizedRoots,
      final FinalizedRetention retention) {
    String failure = ENSURE_FAILURE;
    try {
      if (ensureNode(anchorRoot, anchorState, anchorEpoch, finalizedAncestorRoots)) {
        dag.metrics().onAnchorCreatedLate();
      }
      failure = RETAIN_FAILURE;
      retainFinalizedChain(previousAnchorRoot, anchorRoot, newlyFinalizedRoots, retention, false);
    } catch (final StateDiffVerificationException e) {
      LOG.error(
          "Repairing the state diff node of the finalized anchor {} at epoch {} (previous anchor"
              + " {}): {}; writing the anchor node as a snapshot",
          anchorRoot,
          anchorEpoch,
          previousAnchorRoot.map(Bytes32::toHexString).orElse("none"),
          failure,
          e);
      dag.metrics().onAnchorRepaired();
      repairAnchor(
          previousAnchorRoot, anchorRoot, anchorState, anchorEpoch, newlyFinalizedRoots, retention);
    }
  }

  /**
   * Rewrites the node of the finalized anchor {@code anchorRoot} as a snapshot of {@code state},
   * then deletes the finalized-chain nodes the anchor no longer needs, as {@link
   * #retainFinalizedChain} does reading only the part of the previous anchor's chain that can still
   * be read. Undecodable records are deleted without releasing their supernode references.
   *
   * @param anchorEpoch the epoch served by the anchor node when it has no readable node
   */
  private void repairAnchor(
      final Optional<Bytes32> previousAnchorRoot,
      final Bytes32 anchorRoot,
      final BeaconState state,
      final UInt64 anchorEpoch,
      final Collection<Bytes32> newlyFinalizedRoots,
      final FinalizedRetention retention) {
    UInt64 firstEpoch = anchorEpoch;
    UInt64 lastEpoch = anchorEpoch;
    try {
      final Optional<StateDiffNodeRecord> existing = transaction.reader().getNode(anchorRoot);
      if (existing.isPresent()) {
        firstEpoch = existing.get().firstEpoch();
        lastEpoch = existing.get().lastEpoch();
      }
    } catch (final StateDiffVerificationException e) {
      LOG.debug("Anchor node {} is unreadable, rewriting it for epoch {}", anchorRoot, anchorEpoch);
    }
    transaction.replaceNodeWithSnapshot(anchorRoot, state, firstEpoch, lastEpoch);
    retainFinalizedChain(previousAnchorRoot, anchorRoot, newlyFinalizedRoots, retention, true);
  }

  /**
   * Writes the node of {@code blockRoot} serving only {@code epoch}, unless it already exists.
   *
   * <p>A node written here is written late, after nodes of later epochs may already diff against
   * its ancestors. It is therefore written at the finest level whatever its epoch: its parent is
   * its previous node, so its reconstruction chain contains the previous node's and retention keeps
   * every node a later node may depend on. Coarse nodes written later walk past it through parent
   * pointers.
   *
   * @return whether the node was written
   */
  boolean ensureNode(
      final Bytes32 blockRoot,
      final BeaconState state,
      final UInt64 epoch,
      final List<Bytes32> ancestorCheckpointRoots) {
    if (transaction.reader().getEncodedNode(blockRoot).isPresent()) {
      return false;
    }
    transaction.writeNode(
        blockRoot,
        state,
        epoch,
        epoch,
        dag.levels().levelCount() - 1,
        ancestorCheckpointRoots,
        false);
    return true;
  }

  /**
   * Deletes the finalized-chain nodes no longer needed once the finalized anchor moves from {@code
   * previousAnchorRoot} to {@code newAnchorRoot}, as {@code retention} selects. For {@link
   * FinalizedRetention#anchorChain()}: the nodes of the previous anchor's reconstruction chain, of
   * the newly finalized blocks and of the new anchor, except those in the new anchor's
   * reconstruction chain. For a {@link FinalizedRetention.Window}, see {@link #retainWindow}.
   *
   * <p>The newly finalized blocks are given rather than found by following previous-node pointers
   * from the new anchor: a node created late for the anchor points straight at the previous anchor
   * and would hide the nodes written in between.
   *
   * <p>A candidate whose record or payload cannot be decoded is deleted without releasing its
   * supernode references (counted as {@code deleted_corrupt} and logged at ERROR): it is garbage
   * either way and a repair would not recover it. Only the chains of the two anchors must load.
   *
   * @param previousAnchorRoot the previous anchor; when empty there is no previous chain
   * @param newlyFinalizedRoots the roots of the blocks finalized by this anchor move; roots without
   *     a node are ignored
   * @param retention which finalized nodes to keep
   * @param previousChainPrefixOnly retaining the anchor chain, read only the part of the previous
   *     anchor's chain that can be read, as a repair does; a window always reads its previous
   *     oldest node's chain that way
   * @throws StateDiffVerificationException when, retaining the anchor chain, the chain of the new
   *     anchor, or without {@code previousChainPrefixOnly} that of the previous one, cannot be
   *     loaded; nothing is deleted in that case
   */
  void retainFinalizedChain(
      final Optional<Bytes32> previousAnchorRoot,
      final Bytes32 newAnchorRoot,
      final Collection<Bytes32> newlyFinalizedRoots,
      final FinalizedRetention retention,
      final boolean previousChainPrefixOnly) {
    switch (retention) {
      case FinalizedRetention.AnchorChain.INSTANCE ->
          deleteFinalizedNodesOutsideChain(
              previousAnchorRoot, newAnchorRoot, newlyFinalizedRoots, previousChainPrefixOnly);
      case FinalizedRetention.All.INSTANCE -> {}
      case FinalizedRetention.Window window -> retainWindow(window);
    }
  }

  /**
   * Moves the retention window to {@code window}'s start: deletes the nodes that left it and the
   * part of the previous oldest node's chain the new oldest node {@code O} does not need, never a
   * node of {@code O}'s chain, then records the new start. {@code O} is the last of the window's
   * checkpoint roots that has a node: the node of the start epoch's checkpoint block, or the latest
   * node before it when that one was skipped. A node inside the window resolves its coarse parents
   * to the nearest coarser node at or before {@code O}, which {@code O}'s chain passes through
   * (decision 43), so the window keeps everything its nodes and the hot nodes above them need.
   *
   * <p>When none of the checkpoint roots has a node, or {@code O}'s chain cannot be loaded (logged
   * at ERROR), nothing is deleted and the start is not moved: a later finalization retries. The
   * previous oldest node's chain is read only as far as it can be, a broken part of it being left
   * on disk. Neither case concerns the anchor, which needs no repair.
   */
  private void retainWindow(final FinalizedRetention.Window window) {
    final Optional<Bytes32> oldestRoot =
        window.checkpointRoots().reversed().stream()
            .filter(root -> transaction.reader().getEncodedNode(root).isPresent())
            .findFirst();
    if (oldestRoot.isEmpty()) {
      LOG.debug(
          "No state diff node at or before the checkpoint of epoch {}: keeping the window start",
          window.startEpoch());
      return;
    }
    try {
      deleteFinalizedNodesOutsideChain(
          window.previousOldestRoot(), oldestRoot.get(), window.checkpointRoots(), true);
    } catch (final StateDiffVerificationException e) {
      LOG.error(
          "Cannot load the reconstruction chain of the oldest retained state diff node {} for the"
              + " window starting at epoch {}; keeping every finalized node until a later"
              + " finalization",
          oldestRoot.get(),
          window.startEpoch(),
          e);
      return;
    }
    transaction.setWindowStart(window.startEpoch());
  }

  /**
   * Deletes the nodes of the reconstruction chain of the previous retained node {@code
   * previousRoot} and the nodes of {@code candidateRoots}, except those in the reconstruction chain
   * of the new retained node {@code retainedRoot}: the anchor in prune mode, the oldest node of the
   * window in an archive window. Both chains are loaded before anything is deleted.
   *
   * @param candidateRoots roots whose nodes are garbage unless in the retained chain; roots without
   *     a node are ignored
   * @param previousChainPrefixOnly read only the part of the previous chain that can be read;
   *     otherwise a previous chain that cannot be loaded fails
   */
  private void deleteFinalizedNodesOutsideChain(
      final Optional<Bytes32> previousRoot,
      final Bytes32 retainedRoot,
      final Collection<Bytes32> candidateRoots,
      final boolean previousChainPrefixOnly) {
    final NodeReader reader = transaction.reader();
    final Set<Bytes32> candidates = new LinkedHashSet<>();
    previousRoot.ifPresent(
        root ->
            (previousChainPrefixOnly
                    ? dag.loadChainPrefix(reader, root)
                    : dag.loadChain(reader, root))
                .forEach(link -> candidates.add(link.root())));
    candidates.addAll(candidateRoots);

    final List<ChainLink> retainedChain = dag.loadChain(reader, retainedRoot);
    // Without the retained node every candidate would be deleted, the previous chain included
    checkState(
        !retainedChain.isEmpty(), "No state diff node for the retained root %s", retainedRoot);
    retainedChain.forEach(link -> candidates.remove(link.root()));
    transaction.deleteNodes(candidates);
  }
}
