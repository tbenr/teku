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
import tech.pegasys.teku.spec.datastructures.forkchoice.ForkChoicePayloadStatus;

/**
 * Gloas tree behavior implementing the three-state fork choice model. Each block creates a PENDING
 * node with an immediate EMPTY child. Execution payload arrival creates a FULL child of PENDING
 * (sibling of EMPTY). New child blocks attach to FULL (if exists) or EMPTY — no rerouting needed.
 */
class ForkChoiceTreeBehaviorGloas implements ForkChoiceTreeBehavior {

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
    // Resolve correct parent using get_parent_payload_status:
    // FULL if child's parent_block_hash matches parent's FULL execution hash, else EMPTY
    final Optional<Integer> resolvedParentIndex =
        protoArray.resolveGloasParentIndex(parentRoot, executionBlockHash);

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

    // Mark the node as PENDING — payload status not yet determined
    protoArray
        .getProtoNode(blockRoot)
        .ifPresent(node -> node.setPayloadStatus(ForkChoicePayloadStatus.PAYLOAD_STATUS_PENDING));

    // Create the EMPTY child node immediately
    protoArray.createEmptyNode(blockRoot);
  }

  @Override
  public void onExecutionPayload(
      final ProtoArray protoArray,
      final Bytes32 blockRoot,
      final UInt64 executionBlockNumber,
      final Bytes32 executionBlockHash) {
    protoArray.onExecutionPayload(blockRoot, executionBlockNumber, executionBlockHash);
  }
}
