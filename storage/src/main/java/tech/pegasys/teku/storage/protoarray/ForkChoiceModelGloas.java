/*
 * Copyright Consensys Software Inc., 2026
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

package tech.pegasys.teku.storage.protoarray;

import com.google.common.annotations.VisibleForTesting;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.config.SpecConfigGloas;
import tech.pegasys.teku.spec.datastructures.blocks.BlockCheckpoints;
import tech.pegasys.teku.spec.datastructures.forkchoice.ForkChoiceNode;
import tech.pegasys.teku.spec.datastructures.forkchoice.ForkChoicePayloadStatus;
import tech.pegasys.teku.spec.datastructures.forkchoice.ProtoNodeData;
import tech.pegasys.teku.spec.executionlayer.ExecutionPayloadStatus;
import tech.pegasys.teku.storage.api.GloasForkChoiceRebuildData;
import tech.pegasys.teku.storage.api.StoredBlockMetadata;

/**
 * Storage-side implementation of the Gloas three-state fork-choice tree.
 *
 * <p>This class is the fork-aware model-side implementation of the Python helpers and handlers that
 * introduce the EMPTY/FULL child nodes:
 * https://github.com/ethereum/consensus-specs/blob/master/specs/gloas/fork-choice.md#modified-on_block
 * https://github.com/ethereum/consensus-specs/blob/master/specs/gloas/fork-choice.md#new-on_execution_payload
 * https://github.com/ethereum/consensus-specs/blob/master/specs/gloas/fork-choice.md#new-get_node_children
 * https://github.com/ethereum/consensus-specs/blob/master/specs/gloas/fork-choice.md#new-get_parent_payload_status
 * https://github.com/ethereum/consensus-specs/blob/master/specs/gloas/fork-choice.md#new-is_parent_node_full
 */
class ForkChoiceModelGloas implements ForkChoiceModel {

  private final int payloadTimelyThreshold;
  private final int dataAvailabilityTimelyThreshold;
  private final PtcVoteTracker ptcVoteTracker;

  ForkChoiceModelGloas(final SpecConfigGloas specConfig) {
    this(
        specConfig,
        specConfig.getPayloadTimelyThreshold(),
        specConfig.getDataAvailabilityTimelyThreshold(),
        new PtcVoteTracker());
  }

  ForkChoiceModelGloas(
      final SpecConfigGloas specConfig,
      final int payloadTimelyThreshold,
      final int dataAvailabilityTimelyThreshold,
      final PtcVoteTracker ptcVoteTracker) {
    this.payloadTimelyThreshold = payloadTimelyThreshold;
    this.dataAvailabilityTimelyThreshold = dataAvailabilityTimelyThreshold;
    this.ptcVoteTracker = ptcVoteTracker;
  }

  @Override
  public void processBlock(
      final ProtoArray protoArray,
      final BlockNodeVariantsIndex blockNodeIndex,
      final UInt64 blockSlot,
      final Bytes32 blockRoot,
      final Bytes32 parentRoot,
      final Bytes32 stateRoot,
      final BlockCheckpoints checkpoints,
      final UInt64 executionBlockNumber,
      final Bytes32 executionBlockHash,
      final boolean optimisticallyProcessed) {
    // Spec mapping: modified on_block(store, signed_block)
    // The parent choice follows get_parent_payload_status / is_parent_node_full and the node
    // layout follows get_node_children with a base PENDING node plus an immediate EMPTY child.
    final ForkChoiceNode baseNode = ForkChoiceNode.createBase(blockRoot);
    protoArray.addNode(
        baseNode,
        blockSlot,
        blockRoot,
        parentRoot,
        resolveParentNode(protoArray, blockNodeIndex, parentRoot, executionBlockHash),
        stateRoot,
        checkpoints,
        executionBlockNumber,
        executionBlockHash,
        optimisticallyProcessed);
    blockNodeIndex.putBaseNode(blockRoot, blockSlot, baseNode);

    final ForkChoiceNode emptyNode = ForkChoiceNode.createEmpty(blockRoot);
    protoArray.addNode(
        emptyNode,
        blockSlot,
        blockRoot,
        parentRoot,
        Optional.of(baseNode),
        stateRoot,
        checkpoints,
        executionBlockNumber,
        executionBlockHash,
        optimisticallyProcessed);
    blockNodeIndex.attachEmptyNode(blockRoot, emptyNode);
  }

  @VisibleForTesting
  Optional<ForkChoiceNode> resolveParentNode(
      final ProtoArray protoArray,
      final BlockNodeVariantsIndex blockNodeIndex,
      final Bytes32 parentRoot,
      final Bytes32 childParentBlockHash) {
    // Spec mapping: get_parent_payload_status(store, block)
    // FULL wins only when the child bid's parent_block_hash matches the parent's FULL execution
    // block hash. Otherwise we attach to EMPTY, falling back to the base node for pre-Gloas
    // parents that have no EMPTY/FULL variants.
    final Optional<ForkChoiceNode> fullNode = blockNodeIndex.getFullNode(parentRoot);
    if (fullNode.isPresent()) {
      final Optional<ProtoNode> maybeFullNode = protoArray.getNode(fullNode.get());
      if (maybeFullNode.isPresent()
          && maybeFullNode.get().getExecutionBlockHash().equals(childParentBlockHash)) {
        return fullNode;
      }
    }

    return blockNodeIndex.getEmptyNode(parentRoot).or(() -> blockNodeIndex.getBaseNode(parentRoot));
  }

  @Override
  public void onExecutionPayload(
      final ProtoArray protoArray,
      final BlockNodeVariantsIndex blockNodeIndex,
      final Bytes32 blockRoot,
      final UInt64 executionBlockNumber,
      final Bytes32 executionBlockHash) {
    // Spec mapping: on_execution_payload(store, signed_execution_payload_envelope)
    if (blockNodeIndex.getFullNode(blockRoot).isPresent()) {
      return;
    }
    final Optional<ProtoNodeData> maybeBaseNode =
        getNodeData(protoArray, resolveBaseNode(blockNodeIndex, blockRoot));
    if (maybeBaseNode.isEmpty()) {
      return;
    }

    final ForkChoiceNode fullNode = ForkChoiceNode.createFull(blockRoot);
    final ProtoNodeData baseNode = maybeBaseNode.get();
    protoArray.addNode(
        fullNode,
        baseNode.getSlot(),
        blockRoot,
        baseNode.getParentRoot(),
        Optional.of(resolveBaseNode(blockNodeIndex, blockRoot)),
        baseNode.getStateRoot(),
        baseNode.getCheckpoints(),
        executionBlockNumber,
        executionBlockHash,
        baseNode.isOptimistic());
    blockNodeIndex.attachFullNode(blockRoot, fullNode);
  }

  @Override
  public void rebuildBlockNodesFromMetadata(
      final ProtoArray protoArray,
      final BlockNodeVariantsIndex blockNodeIndex,
      final StoredBlockMetadata block,
      final boolean optimisticallyProcessed) {
    // Recovery replays the same modified on_block logic as live import so the EMPTY/FULL layout
    // matches the Gloas store model after restart.
    final Bytes32 parentBlockHash =
        block
            .getGloasForkChoiceRebuildData()
            .map(GloasForkChoiceRebuildData::payloadParentBlockHash)
            .orElse(ProtoNode.NO_EXECUTION_BLOCK_HASH);
    final UInt64 executionBlockNumber =
        block
            .getExecutionBlockNumber()
            .or(
                () ->
                    blockNodeIndex
                        .getBaseNode(block.getParentRoot())
                        .flatMap(protoArray::getNode)
                        .map(ProtoNode::getExecutionBlockNumber))
            .orElse(ProtoNode.NO_EXECUTION_BLOCK_NUMBER);
    processBlock(
        protoArray,
        blockNodeIndex,
        block.getBlockSlot(),
        block.getBlockRoot(),
        block.getParentRoot(),
        block.getStateRoot(),
        block.getCheckpointEpochs().orElseThrow(),
        executionBlockNumber,
        parentBlockHash,
        optimisticallyProcessed);
    block
        .getGloasForkChoiceRebuildData()
        .flatMap(this::getFullNodeRebuildPayload)
        .ifPresent(
            rebuildPayload ->
                onExecutionPayload(
                    protoArray,
                    blockNodeIndex,
                    block.getBlockRoot(),
                    rebuildPayload.executionBlockNumber(),
                    rebuildPayload.executionBlockHash()));
  }

  @Override
  public Optional<ForkChoiceNode> resolveVoteNode(
      final Bytes32 voteRoot,
      final UInt64 voteSlot,
      final boolean payloadPresent,
      final ProtoArray protoArray,
      final BlockNodeVariantsIndex blockNodeIndex) {
    final Optional<ForkChoiceNode> maybeBaseNode = blockNodeIndex.getBaseNode(voteRoot);
    if (maybeBaseNode.isEmpty()) {
      return Optional.empty();
    }

    final Optional<UInt64> blockSlot =
        protoArray.getNode(maybeBaseNode.get()).map(ProtoNode::getBlockSlot);
    if (blockSlot.isPresent() && voteSlot.isLessThanOrEqualTo(blockSlot.get())) {
      return maybeBaseNode;
    }
    if (payloadPresent) {
      return blockNodeIndex.getFullNode(voteRoot).or(() -> maybeBaseNode);
    }
    return blockNodeIndex.getEmptyNode(voteRoot).or(() -> maybeBaseNode);
  }

  @Override
  public int compareViableChildren(
      final ProtoNode candidateChild,
      final ProtoNode currentBestChild,
      final ProtoNode parent,
      final ProtoArray protoArray,
      final BlockNodeVariantsIndex blockNodeIndex,
      final UInt64 currentSlot,
      final Optional<Bytes32> proposerBoostRoot) {
    // Spec mapping: the extra Gloas sort key in modified get_head only applies to the EMPTY/FULL
    // children returned by get_node_children(parent_root).
    if (!candidateChild.getBlockRoot().equals(parent.getBlockRoot())
        || !currentBestChild.getBlockRoot().equals(parent.getBlockRoot())) {
      return 0;
    }

    final UInt64 candidateEffectiveWeight = effectiveWeight(candidateChild, currentSlot);
    final UInt64 currentEffectiveWeight = effectiveWeight(currentBestChild, currentSlot);
    final int weightComparison = candidateEffectiveWeight.compareTo(currentEffectiveWeight);
    if (weightComparison != 0) {
      return weightComparison;
    }

    return Integer.compare(
        computePayloadStatusTiebreaker(
            candidateChild, protoArray, blockNodeIndex, currentSlot, proposerBoostRoot),
        computePayloadStatusTiebreaker(
            currentBestChild, protoArray, blockNodeIndex, currentSlot, proposerBoostRoot));
  }

  private UInt64 effectiveWeight(final ProtoNode node, final UInt64 currentSlot) {
    if (node.getPayloadStatus() == ForkChoicePayloadStatus.PAYLOAD_STATUS_PENDING
        || !node.getBlockSlot().plus(1).equals(currentSlot)) {
      return node.getWeight();
    }
    return UInt64.ZERO;
  }

  private int computePayloadStatusTiebreaker(
      final ProtoNode node,
      final ProtoArray protoArray,
      final BlockNodeVariantsIndex blockNodeIndex,
      final UInt64 currentSlot,
      final Optional<Bytes32> proposerBoostRoot) {
    if (node.getPayloadStatus() == ForkChoicePayloadStatus.PAYLOAD_STATUS_PENDING
        || !node.getBlockSlot().plus(1).equals(currentSlot)) {
      return node.getPayloadStatus().getValue();
    }
    if (node.getPayloadStatus() == ForkChoicePayloadStatus.PAYLOAD_STATUS_EMPTY) {
      return 1;
    }
    return shouldExtendPayload(blockNodeIndex, node.getBlockRoot(), protoArray, proposerBoostRoot)
        ? 2
        : 0;
  }

  private boolean shouldExtendPayload(
      final BlockNodeVariantsIndex blockNodeIndex,
      final Bytes32 blockRoot,
      final ProtoArray protoArray,
      final Optional<Bytes32> proposerBoostRoot) {
    if (isPayloadTimely(blockNodeIndex, blockRoot)
        && isPayloadDataAvailable(blockNodeIndex, blockRoot)) {
      return true;
    }
    if (proposerBoostRoot.isEmpty()) {
      return true;
    }
    final Optional<ProtoNode> proposerNode =
        blockNodeIndex.getBaseNode(proposerBoostRoot.get()).flatMap(protoArray::getNode);
    if (proposerNode.isEmpty()) {
      return true;
    }
    if (!proposerNode.get().getParentRoot().equals(blockRoot)) {
      return true;
    }
    return blockNodeIndex
        .getFullNode(blockRoot)
        .flatMap(protoArray::getNode)
        .map(
            fullNode ->
                fullNode.getExecutionBlockHash().equals(proposerNode.get().getExecutionBlockHash()))
        .orElse(false);
  }

  private boolean isPayloadTimely(
      final BlockNodeVariantsIndex blockNodeIndex, final Bytes32 blockRoot) {
    if (blockNodeIndex.getFullNode(blockRoot).isEmpty()) {
      return false;
    }
    return ptcVoteTracker.getPayloadPresentVoteCount(blockRoot) > payloadTimelyThreshold;
  }

  private boolean isPayloadDataAvailable(
      final BlockNodeVariantsIndex blockNodeIndex, final Bytes32 blockRoot) {
    if (blockNodeIndex.getFullNode(blockRoot).isEmpty()) {
      return false;
    }
    return ptcVoteTracker.getDataAvailableVoteCount(blockRoot) > dataAvailabilityTimelyThreshold;
  }

  @Override
  public void onExecutionPayloadResult(
      final ProtoArray protoArray,
      final BlockNodeVariantsIndex blockNodeIndex,
      final Bytes32 blockRoot,
      final ExecutionPayloadStatus status,
      final Optional<Bytes32> latestValidHash,
      final boolean verifiedInvalidTransition,
      final HeadSelectionContext headSelectionContext) {
    if (status.isValid()) {
      // Only the FULL node needs validation marking — base/EMPTY are already VALID from block
      // import
      blockNodeIndex.getFullNode(blockRoot).ifPresent(protoArray::markNodeValid);
    } else if (status.isInvalid()) {
      if (verifiedInvalidTransition) {
        // In Gloas, an invalid execution payload only invalidates the FULL path.
        // The beacon block (base + EMPTY) remains valid — the builder, not the proposer, is at
        // fault.
        blockNodeIndex
            .getFullNode(blockRoot)
            .ifPresent(
                node -> protoArray.markNodeInvalid(node, latestValidHash, headSelectionContext));
      } else {
        // Unverified: a child's payload was invalid, pointing at this parent.
        // The base node is the correct anchor for parent-chain invalidation search.
        blockNodeIndex
            .getBaseNode(blockRoot)
            .ifPresent(
                node ->
                    protoArray.markParentChainInvalid(node, latestValidHash, headSelectionContext));
      }
    }
  }

  @Override
  public Optional<ProtoNodeData> getNodeData(
      final ProtoArray protoArray, final ForkChoiceNode node) {
    return protoArray.getNode(node).map(ProtoNode::getBlockData);
  }

  @Override
  public Optional<ProtoNodeData> getBlockData(
      final ProtoArray protoArray,
      final BlockNodeVariantsIndex blockNodeIndex,
      final Bytes32 blockRoot) {
    return blockNodeIndex
        .getBaseNode(blockRoot)
        .flatMap(nodeIdentity -> getNodeData(protoArray, nodeIdentity));
  }

  @Override
  public boolean isHeadCandidate(final ProtoNode node) {
    // Spec mapping: modified get_head(store)
    // In the Gloas three-state tree the base PENDING node is structural only. Candidate heads are
    // the EMPTY/FULL children selected from get_node_children(...).
    return node.getPayloadStatus() != ForkChoicePayloadStatus.PAYLOAD_STATUS_PENDING;
  }

  @Override
  public ForkChoiceNode resolveBaseNode(
      final BlockNodeVariantsIndex blockNodeIndex, final Bytes32 blockRoot) {
    return blockNodeIndex.getBaseNode(blockRoot).orElse(ForkChoiceNode.createBase(blockRoot));
  }

  @Override
  public ForkChoiceNode resolveExecutionNode(
      final ProtoArray protoArray,
      final BlockNodeVariantsIndex blockNodeIndex,
      final Bytes32 blockRoot) {
    // FULL is the execution-state node selected when the payload has been revealed.
    return blockNodeIndex
        .getFullNode(blockRoot)
        .orElseGet(() -> resolveBaseNode(blockNodeIndex, blockRoot));
  }

  @Override
  public Optional<ForkChoicePayloadStatus> payloadStatus(
      final ProtoArray protoArray,
      final BlockNodeVariantsIndex blockNodeIndex,
      final Bytes32 blockRoot) {
    // Compatibility read for block-root-only callers. This mirrors the best-available payload
    // status without promoting it to a first-class "preferred node" abstraction.
    if (blockNodeIndex.getFullNode(blockRoot).isPresent()) {
      return Optional.of(ForkChoicePayloadStatus.PAYLOAD_STATUS_FULL);
    }
    if (blockNodeIndex.getEmptyNode(blockRoot).isPresent()) {
      return Optional.of(ForkChoicePayloadStatus.PAYLOAD_STATUS_EMPTY);
    }
    return Optional.of(ForkChoicePayloadStatus.PAYLOAD_STATUS_PENDING);
  }

  @Override
  public void pullUpBlockCheckpoints(
      final ProtoArray protoArray,
      final BlockNodeVariantsIndex blockNodeIndex,
      final Bytes32 blockRoot) {
    blockNodeIndex
        .getVariants(blockRoot)
        .ifPresent(variants -> variants.allNodes().forEach(protoArray::pullUpCheckpoints));
  }

  @Override
  public void onPtcVote(
      final Bytes32 blockRoot,
      final UInt64 validatorIndex,
      final boolean payloadPresent,
      final boolean blobDataAvailable) {
    // Spec mapping: on_payload_attestation_message / notify_ptc_messages
    ptcVoteTracker.recordVote(blockRoot, validatorIndex, payloadPresent, blobDataAvailable);
  }

  @Override
  public void onRemovedBlockRoot(
      final ProtoArray protoArray,
      final BlockNodeVariantsIndex blockNodeIndex,
      final Bytes32 blockRoot) {
    blockNodeIndex
        .getVariants(blockRoot)
        .ifPresent(variants -> variants.allNodes().forEach(protoArray::removeNode));
    blockNodeIndex.remove(blockRoot);
    ptcVoteTracker.remove(blockRoot);
  }

  @Override
  public void onPrunedBlocks(final BlockNodeVariantsIndex blockNodeIndex) {
    ptcVoteTracker.removeIf(root -> !blockNodeIndex.containsBlock(root));
  }

  private Optional<FullNodeRebuildPayload> getFullNodeRebuildPayload(
      final GloasForkChoiceRebuildData rebuildData) {
    return rebuildData
        .payloadBlockNumber()
        .map(
            executionBlockNumber ->
                new FullNodeRebuildPayload(executionBlockNumber, rebuildData.payloadBlockHash()));
  }

  private record FullNodeRebuildPayload(UInt64 executionBlockNumber, Bytes32 executionBlockHash) {}
}
