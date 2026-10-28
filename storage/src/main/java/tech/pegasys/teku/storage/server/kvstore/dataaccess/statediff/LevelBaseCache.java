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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.collections.LimitedMap;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;

/**
 * Per-level diff base cache of the state diff DAG.
 *
 * <p>Slot {@code L} holds the most recently used nodes at level {@code L} or coarser together with
 * their state trees, evicting the least recently used. The most recent one is exactly the parent
 * the next node at level {@code L} resolves to on a linear chain. Putting a node at level {@code L}
 * therefore adds it to slot {@code L} and every finer slot. The trees are the backing nodes of the
 * live states that were written, so they share their unchanged subtrees with the live state and
 * with each other, and the differ can skip them by identity. A cached tree also starts a
 * reconstruction whose chain passes through its node.
 *
 * <p>The finest slot keeps {@value #FINEST_LEVEL_ENTRIES} entries, so that sibling nodes written
 * for one epoch, as after a reorg of the epoch's start slot block, find their parent without a
 * reconstruction; every coarser slot keeps one. A coarse node's state diverges from the live state
 * by up to its level's period, so every extra coarse entry would pin megabytes of diverged subtrees
 * for a rare sibling, which reconstructs from the nearest cached node of its chain instead.
 */
class LevelBaseCache {

  static final int FINEST_LEVEL_ENTRIES = 3;

  /** Slots by level, coarsest first; each guarded by this cache's lock. */
  private final List<Map<Bytes32, TreeNode>> slots;

  LevelBaseCache(final int levelCount) {
    checkArgument(levelCount > 0, "Level count %s must be positive", levelCount);
    this.slots =
        IntStream.range(0, levelCount)
            .<Map<Bytes32, TreeNode>>mapToObj(
                level ->
                    LimitedMap.createNonSynchronized(
                        level == levelCount - 1 ? FINEST_LEVEL_ENTRIES : 1))
            .toList();
  }

  /** The level a node's state is cached at: a snapshot is a valid base for every level. */
  static int cacheLevel(final StateDiffNodeRecord record) {
    return record.snapshot() ? 0 : record.level();
  }

  /** The state tree of the node of {@code root}, from whichever slot holds it. */
  synchronized Optional<TreeNode> find(final Bytes32 root) {
    for (final Map<Bytes32, TreeNode> slot : slots) {
      final TreeNode tree = slot.get(root);
      if (tree != null) {
        return Optional.of(tree);
      }
    }
    return Optional.empty();
  }

  synchronized void put(final int level, final Bytes32 root, final BeaconState state) {
    final TreeNode tree = state.getBackingNode();
    slots.subList(level, slots.size()).forEach(slot -> slot.put(root, tree));
  }
}
