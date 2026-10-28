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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;

/**
 * Per-level diff base cache of the state diff DAG writer.
 *
 * <p>Slot {@code L} holds the {@value #ENTRIES_PER_LEVEL} most recently written nodes at level
 * {@code L} or coarser together with their state trees, most recent first. The most recent one is
 * exactly the parent the next node at level {@code L} resolves to on a linear chain; the older ones
 * serve a sibling written for the same epoch, as after a reorg of the epoch's checkpoint block,
 * without a reconstruction. Putting a node at level {@code L} therefore adds it to slots {@code L}
 * and every finer slot. The trees are the backing nodes of the live states that were written, so
 * they share their unchanged subtrees with the live state and with each other, and the differ can
 * skip them by identity.
 *
 * <p>Only the {@code cachedLevels} finest levels have a slot; a node at a coarser level misses and
 * its parent is reconstructed from the DAG instead.
 */
class LevelBaseCache {

  static final int ENTRIES_PER_LEVEL = 3;

  private final int firstCachedLevel;
  private final Deque<Entry>[] slots;

  @SuppressWarnings({"unchecked", "rawtypes"})
  LevelBaseCache(final int levelCount, final int cachedLevels) {
    checkArgument(
        cachedLevels >= 0 && cachedLevels <= levelCount,
        "Cached levels %s must be between 0 and %s",
        cachedLevels,
        levelCount);
    this.firstCachedLevel = levelCount - cachedLevels;
    this.slots = new Deque[levelCount];
    for (int level = 0; level < levelCount; level++) {
      slots[level] = new ArrayDeque<>(ENTRIES_PER_LEVEL);
    }
  }

  synchronized Optional<TreeNode> get(final int level, final Bytes32 root) {
    for (final Entry entry : slots[level]) {
      if (entry.root().equals(root)) {
        return Optional.of(entry.tree());
      }
    }
    return Optional.empty();
  }

  synchronized void put(final int level, final Bytes32 root, final BeaconState state) {
    final Entry entry = new Entry(root, state.getBackingNode());
    for (int slotLevel = Math.max(level, firstCachedLevel); slotLevel < slots.length; slotLevel++) {
      final Deque<Entry> slot = slots[slotLevel];
      slot.removeIf(existing -> existing.root().equals(root));
      slot.addFirst(entry);
      while (slot.size() > ENTRIES_PER_LEVEL) {
        slot.removeLast();
      }
    }
  }

  private record Entry(Bytes32 root, TreeNode tree) {}
}
