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

package tech.pegasys.teku.storage.api;

import java.util.List;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

/**
 * Where a hot state stored as a state diff DAG node sits in the chain: the post-state of checkpoint
 * block {@code R} serves epochs {@code firstEpoch..lastEpoch}.
 *
 * @param ancestorCheckpointRoots roots of the checkpoint blocks among {@code R}'s ancestors,
 *     nearest first, ending with the finalized anchor root
 */
public record HotStateNodeInfo(
    UInt64 firstEpoch, UInt64 lastEpoch, List<Bytes32> ancestorCheckpointRoots) {

  public HotStateNodeInfo(
      final UInt64 firstEpoch,
      final UInt64 lastEpoch,
      final List<Bytes32> ancestorCheckpointRoots) {
    this.firstEpoch = firstEpoch;
    this.lastEpoch = lastEpoch;
    this.ancestorCheckpointRoots = List.copyOf(ancestorCheckpointRoots);
  }
}
