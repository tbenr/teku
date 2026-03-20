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
 * Storage-side fork-aware model for fork choice. This isolates milestone-specific node layout, vote
 * routing, head-selection overrides, and recovery behavior from the generic strategy and protoarray
 * engine.
 */
interface ForkChoiceModel {

  void processBlock(
      ProtoArray protoArray,
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
      Bytes32 blockRoot,
      UInt64 executionBlockNumber,
      Bytes32 executionBlockHash);

  void rebuildTrackedBlock(
      ProtoArray protoArray,
      StoredBlockMetadata block,
      Optional<SignedBeaconBlock> maybeBlock,
      boolean optimisticallyProcessed);

  VoteScoringResolver getVoteScoringResolver();

  HeadSelectionPolicy createHeadSelectionPolicy(
      UInt64 currentSlot, Optional<Bytes32> proposerBoostRoot);

  Optional<ProtoNodeData> getNodeData(ProtoArray protoArray, ForkChoiceNode node);

  default Optional<ProtoNodeData> getBlockData(
      final ProtoArray protoArray, final Bytes32 blockRoot) {
    return getNodeData(protoArray, resolveCanonicalNode(blockRoot));
  }

  ForkChoiceNode resolveCanonicalNode(Bytes32 blockRoot);

  ForkChoiceNode resolveExecutionNode(ProtoArray protoArray, Bytes32 blockRoot);

  ForkChoiceNode resolvePreferredNode(ProtoArray protoArray, Bytes32 blockRoot);

  default Optional<ForkChoicePayloadStatus> payloadStatus(
      final ProtoArray protoArray, final Bytes32 blockRoot) {
    return getNodeData(protoArray, resolvePreferredNode(protoArray, blockRoot))
        .map(ProtoNodeData::getPayloadStatus);
  }

  default void onPtcVote(
      final Bytes32 blockRoot,
      final UInt64 validatorIndex,
      final boolean payloadPresent,
      final boolean blobDataAvailable) {}

  default void onRemovedBlockRoot(final Bytes32 blockRoot) {}

  default void onPrunedBlocks(final ProtoArray protoArray) {}
}
