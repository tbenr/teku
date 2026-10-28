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

import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

/** Which finalized-chain nodes of the state diff DAG are kept when the finalized anchor moves. */
public sealed interface FinalizedRetention {

  /** Prune mode: only the reconstruction chain of the anchor (spec 5.8). */
  static FinalizedRetention anchorChain() {
    return AnchorChain.INSTANCE;
  }

  /** Unbounded archive: every finalized node. */
  static FinalizedRetention all() {
    return All.INSTANCE;
  }

  /**
   * Archive retention window (decision 43): every node serving an epoch from {@code startEpoch} on,
   * plus the reconstruction chain of the oldest retained node {@code O}, the last of {@code
   * checkpointRoots} that has a node. Garbage is the prune rule with the anchor replaced by {@code
   * O}: the nodes of {@code checkpointRoots} and of the previous oldest node's chain, except those
   * in {@code O}'s chain.
   *
   * @param startEpoch the new window start, recorded once the nodes that left are deleted
   * @param previousOldestRoot the oldest retained node of the previous window, if known
   * @param checkpointRoots the checkpoint block roots of the epochs from the previous window start
   *     to {@code startEpoch}, oldest first: the nodes that leave the window, and the candidates
   *     for {@code O}
   */
  static FinalizedRetention window(
      final UInt64 startEpoch,
      final Optional<Bytes32> previousOldestRoot,
      final List<Bytes32> checkpointRoots) {
    return new Window(startEpoch, previousOldestRoot, List.copyOf(checkpointRoots));
  }

  /** See {@link #anchorChain()}. */
  enum AnchorChain implements FinalizedRetention {
    INSTANCE
  }

  /** See {@link #all()}. */
  enum All implements FinalizedRetention {
    INSTANCE
  }

  /** See {@link #window}. */
  record Window(
      UInt64 startEpoch, Optional<Bytes32> previousOldestRoot, List<Bytes32> checkpointRoots)
      implements FinalizedRetention {}
}
