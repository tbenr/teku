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
import tech.pegasys.teku.spec.datastructures.blocks.BlockCheckpoints;
import tech.pegasys.teku.spec.datastructures.forkchoice.ForkChoiceNode;
import tech.pegasys.teku.spec.datastructures.forkchoice.ProtoNodeData;
import tech.pegasys.teku.spec.executionlayer.ExecutionPayloadStatus;
import tech.pegasys.teku.storage.api.StoredBlockMetadata;

/** Pre-Gloas single-node-per-block model. */
class ForkChoiceModelDefault implements ForkChoiceModel {

  static final ForkChoiceModelDefault INSTANCE = new ForkChoiceModelDefault();

  private ForkChoiceModelDefault() {}

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
    final ForkChoiceNode baseNode = ForkChoiceNode.createBase(blockRoot);
    protoArray.addNode(
        baseNode,
        blockSlot,
        blockRoot,
        parentRoot,
        blockNodeIndex.getBaseNode(parentRoot),
        stateRoot,
        checkpoints,
        executionBlockNumber,
        executionBlockHash,
        optimisticallyProcessed);
    blockNodeIndex.putBaseNode(blockRoot, blockSlot, baseNode);
  }

  @Override
  public void onExecutionPayload(
      final ProtoArray protoArray,
      final BlockNodeVariantsIndex blockNodeIndex,
      final Bytes32 blockRoot,
      final UInt64 executionBlockNumber,
      final Bytes32 executionBlockHash) {
    // No-op
  }

  @Override
  public void rebuildTrackedBlock(
      final ProtoArray protoArray,
      final BlockNodeVariantsIndex blockNodeIndex,
      final StoredBlockMetadata block,
      final boolean optimisticallyProcessed) {
    processBlock(
        protoArray,
        blockNodeIndex,
        block.getBlockSlot(),
        block.getBlockRoot(),
        block.getParentRoot(),
        block.getStateRoot(),
        block.getCheckpointEpochs().orElseThrow(),
        block.getExecutionBlockNumber().orElse(ProtoNode.NO_EXECUTION_BLOCK_NUMBER),
        block.getExecutionBlockHash().orElse(ProtoNode.NO_EXECUTION_BLOCK_HASH),
        optimisticallyProcessed);
  }

  @Override
  public VoteScoringResolver getVoteScoringResolver() {
    return DefaultVoteScoringResolver.INSTANCE;
  }

  @Override
  public HeadSelectionPolicy createHeadSelectionPolicy(
      final ProtoArray protoArray,
      final BlockNodeVariantsIndex blockNodeIndex,
      final UInt64 currentSlot,
      final Optional<Bytes32> proposerBoostRoot) {
    return DefaultHeadSelectionPolicy.INSTANCE;
  }

  @Override
  public Optional<ProtoNodeData> getNodeData(
      final ProtoArray protoArray, final ForkChoiceNode node) {
    return protoArray.getNode(node).map(ProtoNode::getBlockData);
  }

  @Override
  public void onExecutionPayloadResult(
      final ProtoArray protoArray,
      final BlockNodeVariantsIndex blockNodeIndex,
      final Bytes32 blockRoot,
      final ExecutionPayloadStatus status,
      final Optional<Bytes32> latestValidHash,
      final boolean verifiedInvalidTransition) {
    if (status.isValid()) {
      blockNodeIndex.getBaseNode(blockRoot).ifPresent(protoArray::markNodeValid);
    } else if (status.isInvalid()) {
      if (verifiedInvalidTransition) {
        blockNodeIndex
            .getBaseNode(blockRoot)
            .ifPresent(node -> protoArray.markNodeInvalid(node, latestValidHash));
      } else {
        blockNodeIndex
            .getBaseNode(blockRoot)
            .ifPresent(node -> protoArray.markParentChainInvalid(node, latestValidHash));
      }
    }
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
    return resolveBaseNode(blockNodeIndex, blockRoot);
  }
}
