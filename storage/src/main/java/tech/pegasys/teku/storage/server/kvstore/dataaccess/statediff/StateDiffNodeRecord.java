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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

/**
 * A node of the state diff DAG, stored by block root.
 *
 * @param blockSlot slot of the block whose post-state this node holds
 * @param stateRoot hash tree root of that state, checked after reconstruction
 * @param firstEpoch first epoch this node serves as a checkpoint state for
 * @param lastEpoch last epoch this node serves as a checkpoint state for
 * @param level diff level of the node, coarser levels being lower
 * @param snapshot whether {@code payload} is a diff against the schema's default tree
 * @param prevNodeRoot block root of the previous node on this chain, or zero for none
 * @param parentNodeRoot block root of the node {@code payload} is a diff against, zero for a
 *     snapshot
 * @param payload the encoded tree diff
 */
public record StateDiffNodeRecord(
    UInt64 blockSlot,
    Bytes32 stateRoot,
    UInt64 firstEpoch,
    UInt64 lastEpoch,
    int level,
    boolean snapshot,
    Bytes32 prevNodeRoot,
    Bytes32 parentNodeRoot,
    Bytes payload) {

  public StateDiffNodeRecord(
      final UInt64 blockSlot,
      final Bytes32 stateRoot,
      final UInt64 firstEpoch,
      final UInt64 lastEpoch,
      final int level,
      final boolean snapshot,
      final Bytes32 prevNodeRoot,
      final Bytes32 parentNodeRoot,
      final Bytes payload) {
    checkArgument(level >= 0 && level <= 0xFF, "Level %s does not fit in a byte", level);
    checkArgument(
        !snapshot || parentNodeRoot.isZero(),
        "Snapshot must have a zero parent node root but was %s",
        parentNodeRoot);
    this.blockSlot = checkNotNull(blockSlot, "blockSlot");
    this.stateRoot = checkNotNull(stateRoot, "stateRoot");
    this.firstEpoch = checkNotNull(firstEpoch, "firstEpoch");
    this.lastEpoch = checkNotNull(lastEpoch, "lastEpoch");
    this.level = level;
    this.snapshot = snapshot;
    this.prevNodeRoot = checkNotNull(prevNodeRoot, "prevNodeRoot");
    this.parentNodeRoot = checkNotNull(parentNodeRoot, "parentNodeRoot");
    this.payload = checkNotNull(payload, "payload");
  }
}
