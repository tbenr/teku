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

import java.util.Optional;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.config.SpecConfigGloas;
import tech.pegasys.teku.spec.datastructures.blocks.BlockCheckpoints;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.ExecutionPayloadBid;
import tech.pegasys.teku.spec.datastructures.epbs.versions.gloas.SignedExecutionPayloadBid;
import tech.pegasys.teku.spec.datastructures.forkchoice.ForkChoiceNode;
import tech.pegasys.teku.spec.datastructures.forkchoice.ForkChoicePayloadStatus;
import tech.pegasys.teku.spec.datastructures.forkchoice.ProtoNodeData;
import tech.pegasys.teku.storage.api.StoredBlockMetadata;

/**
 * Storage-side implementation of the Gloas three-state fork-choice tree.
 *
 * <p>This class is the fork-aware model-side implementation of the Python helpers and handlers
 * that introduce the EMPTY/FULL child nodes:
 * https://github.com/ethereum/consensus-specs/blob/master/specs/gloas/fork-choice.md#modified-on_block
 * https://github.com/ethereum/consensus-specs/blob/master/specs/gloas/fork-choice.md#new-on_execution_payload
 * https://github.com/ethereum/consensus-specs/blob/master/specs/gloas/fork-choice.md#new-get_node_children
 * https://github.com/ethereum/consensus-specs/blob/master/specs/gloas/fork-choice.md#new-get_parent_payload_status
 * https://github.com/ethereum/consensus-specs/blob/master/specs/gloas/fork-choice.md#new-is_parent_node_full
 */
class ForkChoiceModelGloas implements ForkChoiceModel {

  private final SpecConfigGloas specConfig;
  private final PtcVoteTracker ptcVoteTracker = new PtcVoteTracker();

  ForkChoiceModelGloas(final SpecConfigGloas specConfig) {
    this.specConfig = specConfig;
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
  public void rebuildTrackedBlock(
      final ProtoArray protoArray,
      final BlockNodeVariantsIndex blockNodeIndex,
      final StoredBlockMetadata block,
      final Optional<SignedBeaconBlock> maybeBlock,
      final boolean optimisticallyProcessed) {
    // Recovery replays the same modified on_block logic as live import so the EMPTY/FULL layout
    // matches the Gloas store model after restart.
    final Bytes32 parentBlockHash =
        maybeBlock
            .flatMap(
                signedBlock ->
                    signedBlock
                        .getMessage()
                        .getBody()
                        .getOptionalSignedExecutionPayloadBid()
                        .map(SignedExecutionPayloadBid::getMessage)
                        .map(ExecutionPayloadBid::getParentBlockHash))
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
  }

  @Override
  public VoteScoringResolver getVoteScoringResolver() {
    return GloasVoteScoringResolver.INSTANCE;
  }

  @Override
  public HeadSelectionPolicy createHeadSelectionPolicy(
      final ProtoArray protoArray,
      final BlockNodeVariantsIndex blockNodeIndex,
      final UInt64 currentSlot,
      final Optional<Bytes32> proposerBoostRoot) {
    return new GloasHeadSelectionPolicy(
        blockNodeIndex,
        currentSlot,
        proposerBoostRoot,
        specConfig.getPayloadTimelyThreshold(),
        specConfig.getDataAvailabilityTimelyThreshold(),
        ptcVoteTracker::getPayloadPresentVoteCount,
        ptcVoteTracker::getDataAvailableVoteCount);
  }

  @Override
  public Optional<ProtoNodeData> getNodeData(
      final ProtoArray protoArray, final ForkChoiceNode node) {
    return protoArray.getNode(node).map(ProtoNode::getBlockData);
  }

  @Override
  public ForkChoiceNode resolveBaseNode(
      final BlockNodeVariantsIndex blockNodeIndex, final Bytes32 blockRoot) {
    return blockNodeIndex.getBaseNode(blockRoot).orElse(ForkChoiceNode.createBase(blockRoot));
  }

  @Override
  public ForkChoiceNode resolveExecutionNode(
      final ProtoArray protoArray, final BlockNodeVariantsIndex blockNodeIndex, final Bytes32 blockRoot) {
    // FULL is the execution-state node selected when the payload has been revealed.
    return blockNodeIndex
        .getFullNode(blockRoot)
        .orElseGet(() -> resolveBaseNode(blockNodeIndex, blockRoot));
  }

  @Override
  public Optional<ForkChoicePayloadStatus> payloadStatus(
      final ProtoArray protoArray, final BlockNodeVariantsIndex blockNodeIndex, final Bytes32 blockRoot) {
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
      final ProtoArray protoArray, final BlockNodeVariantsIndex blockNodeIndex, final Bytes32 blockRoot) {
    ForkChoiceModel.super.onRemovedBlockRoot(protoArray, blockNodeIndex, blockRoot);
    ptcVoteTracker.remove(blockRoot);
  }

  @Override
  public void onPrunedBlocks(final BlockNodeVariantsIndex blockNodeIndex) {
    ptcVoteTracker.removeIf(root -> !blockNodeIndex.containsBlock(root));
  }
}
