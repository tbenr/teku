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
 * Encapsulates fork-specific tree mechanics for the fork-choice protoarray. This separates "how
 * does the tree change?" (tree mechanics) from "what should happen?" (consensus rules in
 * ForkChoiceUtil).
 *
 * <p>Default: single node per block. Gloas: PENDING node at onBlock with EMPTY child; FULL child at
 * onExecutionPayload.
 */
public interface ForkChoiceTreeBehavior {

  /**
   * Process a new block and add appropriate node(s) to the protoarray.
   *
   * <p>Default: creates a single node. Gloas: creates a PENDING node with an EMPTY child, resolving
   * the parent to the FULL or EMPTY child of the parent block.
   */
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

  /**
   * Process an execution payload arrival and create the FULL node in the tree.
   *
   * <p>Default: no-op (pre-Gloas blocks don't have separate execution payloads). Gloas: creates a
   * FULL child node of the PENDING block node.
   */
  void onExecutionPayload(
      ProtoArray protoArray,
      Bytes32 blockRoot,
      UInt64 executionBlockNumber,
      Bytes32 executionBlockHash);
}
