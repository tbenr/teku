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
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.forkchoice.ForkChoiceNode;
import tech.pegasys.teku.spec.datastructures.forkchoice.ForkChoicePayloadStatus;
import tech.pegasys.teku.spec.datastructures.forkchoice.ProtoNodeData;
import tech.pegasys.teku.storage.api.StoredBlockMetadata;

/**
 * Storage-side fork-aware model for fork choice.
 *
 * <p>This is the block/payload variants layer that maps beacon blocks onto node identities, while
 * {@link ProtoArray} remains a milestone-agnostic node engine.
 */
interface ForkChoiceModel {

  void processBlock(
      ProtoArray protoArray,
      BlockNodeVariantsIndex blockNodeIndex,
      UInt64 blockSlot,
      Bytes32 blockRoot,
      Bytes32 parentRoot,
      Bytes32 stateRoot,
      BlockCheckpoints checkpoints,
      UInt64 executionBlockNumber,
      Bytes32 executionBlockHash,
      boolean optimisticallyProcessed);

  void onExecutionPayload(
      ProtoArray protoArray,
      BlockNodeVariantsIndex blockNodeIndex,
      Bytes32 blockRoot,
      UInt64 executionBlockNumber,
      Bytes32 executionBlockHash);

  void rebuildTrackedBlock(
      ProtoArray protoArray,
      BlockNodeVariantsIndex blockNodeIndex,
      StoredBlockMetadata block,
      Optional<SignedBeaconBlock> maybeBlock,
      boolean optimisticallyProcessed);

  VoteScoringResolver getVoteScoringResolver();

  HeadSelectionPolicy createHeadSelectionPolicy(
      ProtoArray protoArray,
      BlockNodeVariantsIndex blockNodeIndex,
      UInt64 currentSlot,
      Optional<Bytes32> proposerBoostRoot);

  Optional<ProtoNodeData> getNodeData(ProtoArray protoArray, ForkChoiceNode node);

  default Optional<ProtoNodeData> getBlockData(
      final ProtoArray protoArray,
      final BlockNodeVariantsIndex blockNodeIndex,
      final Bytes32 blockRoot) {
    return blockNodeIndex
        .getBaseNode(blockRoot)
        .flatMap(nodeIdentity -> getNodeData(protoArray, nodeIdentity));
  }

  /**
   * Returns whether the supplied node is a valid head candidate for this fork-aware model.
   *
   * <p>Pre-Gloas head candidates are the base nodes. In Gloas, the structural base node is never a
   * head and only the EMPTY/FULL children are valid terminal heads.
   */
  default boolean isHeadCandidate(final ProtoNode node) {
    return node.getPayloadStatus() == ForkChoicePayloadStatus.PAYLOAD_STATUS_PENDING;
  }

  ForkChoiceNode resolveBaseNode(BlockNodeVariantsIndex blockNodeIndex, Bytes32 blockRoot);

  ForkChoiceNode resolveExecutionNode(
      ProtoArray protoArray, BlockNodeVariantsIndex blockNodeIndex, Bytes32 blockRoot);

  default Optional<ForkChoicePayloadStatus> payloadStatus(
      final ProtoArray protoArray,
      final BlockNodeVariantsIndex blockNodeIndex,
      final Bytes32 blockRoot) {
    return getBlockData(protoArray, blockNodeIndex, blockRoot).map(ProtoNodeData::getPayloadStatus);
  }

  default void pullUpBlockCheckpoints(
      final ProtoArray protoArray,
      final BlockNodeVariantsIndex blockNodeIndex,
      final Bytes32 blockRoot) {
    blockNodeIndex
        .getVariants(blockRoot)
        .ifPresent(variants -> variants.allNodes().forEach(protoArray::pullUpCheckpoints));
  }

  default void onPtcVote(
      final Bytes32 blockRoot,
      final UInt64 validatorIndex,
      final boolean payloadPresent,
      final boolean blobDataAvailable) {}

  default void onRemovedBlockRoot(
      final ProtoArray protoArray,
      final BlockNodeVariantsIndex blockNodeIndex,
      final Bytes32 blockRoot) {
    blockNodeIndex
        .getVariants(blockRoot)
        .ifPresent(variants -> variants.allNodes().forEach(protoArray::removeNode));
    blockNodeIndex.remove(blockRoot);
  }

  default void onPrunedBlocks(final BlockNodeVariantsIndex blockNodeIndex) {}
}
