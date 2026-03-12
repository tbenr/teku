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

import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.datastructures.blocks.BlockCheckpoints;

/**
 * Default tree behavior for pre-Gloas forks. Creates a single node per block with no separate
 * execution payload handling.
 */
class ForkChoiceTreeBehaviorDefault implements ForkChoiceTreeBehavior {

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
    protoArray.onBlock(
        blockSlot,
        blockRoot,
        parentRoot,
        stateRoot,
        checkpoints,
        executionBlockNumber,
        executionBlockHash,
        optimisticallyProcessed);
  }

  @Override
  public void onExecutionPayload(
      final ProtoArray protoArray,
      final Bytes32 blockRoot,
      final UInt64 executionBlockNumber,
      final Bytes32 executionBlockHash) {
    // No-op: pre-Gloas blocks don't have separate execution payloads
  }
}
