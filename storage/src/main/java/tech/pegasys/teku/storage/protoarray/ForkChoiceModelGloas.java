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
 * <p>This class is the fork-aware projection of the Python helpers and handlers that introduce the
 * EMPTY/FULL child nodes:
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
    // layout follows get_node_children with a canonical PENDING node plus an immediate EMPTY child.
    final Optional<Integer> resolvedParentIndex =
        resolveParentIndex(protoArray, parentRoot, executionBlockHash);
    protoArray.onBlock(
        blockSlot,
        blockRoot,
        parentRoot,
        resolvedParentIndex,
        stateRoot,
        checkpoints,
        executionBlockNumber,
        executionBlockHash,
        optimisticallyProcessed);
    protoArray
        .getProtoNode(blockRoot)
        .ifPresent(node -> node.setPayloadStatus(ForkChoicePayloadStatus.PAYLOAD_STATUS_PENDING));
    protoArray.createEmptyNode(blockRoot);
  }

  Optional<Integer> resolveParentIndex(
      final ProtoArray protoArray, final Bytes32 parentRoot, final Bytes32 childParentBlockHash) {
    // Spec mapping: get_parent_payload_status(store, block)
    // FULL wins only when the child bid's parent_block_hash matches the parent's FULL execution
    // block hash. Otherwise we attach to EMPTY, falling back to the canonical block node for
    // pre-Gloas parents that have no EMPTY/FULL projection.
    final Optional<Integer> fullIndex = protoArray.getFullNodeIndex(parentRoot);
    if (fullIndex.isPresent()) {
      final ProtoNode fullNode = protoArray.getNodeByIndex(fullIndex.get());
      if (fullNode.getExecutionBlockHash().equals(childParentBlockHash)) {
        return fullIndex;
      }
    }

    final Optional<Integer> emptyIndex = protoArray.getEmptyNodeIndex(parentRoot);
    if (emptyIndex.isPresent()) {
      return emptyIndex;
    }
    return protoArray.getIndexByRoot(parentRoot);
  }

  @Override
  public void onExecutionPayload(
      final ProtoArray protoArray,
      final Bytes32 blockRoot,
      final UInt64 executionBlockNumber,
      final Bytes32 executionBlockHash) {
    // Spec mapping: on_execution_payload(store, signed_execution_payload_envelope)
    protoArray.onExecutionPayload(blockRoot, executionBlockNumber, executionBlockHash);
  }

  @Override
  public void rebuildTrackedBlock(
      final ProtoArray protoArray,
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
                    protoArray
                        .getProtoNode(block.getParentRoot())
                        .map(ProtoNode::getExecutionBlockNumber))
            .orElse(ProtoNode.NO_EXECUTION_BLOCK_NUMBER);
    processBlock(
        protoArray,
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
      final UInt64 currentSlot, final Optional<Bytes32> proposerBoostRoot) {
    return new GloasHeadSelectionPolicy(
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
    // Read-side projection of get_node_children / get_head node identities.
    if (node.payloadStatus() == ForkChoicePayloadStatus.PAYLOAD_STATUS_FULL) {
      return protoArray
          .getFullNodeIndex(node.blockRoot())
          .map(protoArray::getNodeByIndex)
          .map(ProtoNode::getBlockData);
    }
    if (node.payloadStatus() == ForkChoicePayloadStatus.PAYLOAD_STATUS_EMPTY) {
      return protoArray
          .getEmptyNodeIndex(node.blockRoot())
          .map(protoArray::getNodeByIndex)
          .map(ProtoNode::getBlockData);
    }
    return protoArray.getProtoNode(node.blockRoot()).map(ProtoNode::getBlockData);
  }

  @Override
  public ForkChoiceNode resolveCanonicalNode(final Bytes32 blockRoot) {
    return new ForkChoiceNode(blockRoot, ForkChoicePayloadStatus.PAYLOAD_STATUS_PENDING);
  }

  @Override
  public ForkChoiceNode resolveExecutionNode(final ProtoArray protoArray, final Bytes32 blockRoot) {
    // FULL is the execution-state node selected when the payload has been revealed.
    if (protoArray.hasFullNode(blockRoot)) {
      return new ForkChoiceNode(blockRoot, ForkChoicePayloadStatus.PAYLOAD_STATUS_FULL);
    }
    return resolveCanonicalNode(blockRoot);
  }

  @Override
  public ForkChoiceNode resolvePreferredNode(final ProtoArray protoArray, final Bytes32 blockRoot) {
    // Preferred read-side ordering mirrors the best-available-state rule used around modified
    // on_block and get_head: FULL first, then EMPTY, then canonical PENDING.
    if (protoArray.hasFullNode(blockRoot)) {
      return new ForkChoiceNode(blockRoot, ForkChoicePayloadStatus.PAYLOAD_STATUS_FULL);
    }
    if (protoArray.getEmptyNodeIndex(blockRoot).isPresent()) {
      return ForkChoiceNode.createEmpty(blockRoot);
    }
    return resolveCanonicalNode(blockRoot);
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
  public void onRemovedBlockRoot(final Bytes32 blockRoot) {
    ptcVoteTracker.remove(blockRoot);
  }

  @Override
  public void onPrunedBlocks(final ProtoArray protoArray) {
    ptcVoteTracker.removeIf(root -> !protoArray.contains(root));
  }
}
