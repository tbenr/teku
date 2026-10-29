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

package tech.pegasys.teku.infrastructure.ssz.tree;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import tech.pegasys.teku.infrastructure.ssz.schema.SszCompositeSchema;
import tech.pegasys.teku.infrastructure.ssz.tree.GIndexUtil.NodeRelation;

/**
 * The element index ranges of a list that are held by {@link FrozenSuperNode}s, sorted and
 * disjoint, each {@code [start, end)}. Works for any collection schema whose element generalized
 * indices are ordered left to right by element index (bounded lists and progressive lists): a
 * frozen supernode's first element is found by a binary search over the schema's element gIndices.
 *
 * <p>Collecting iterates the list's tree down to its supernodes (one visit per supernode and per
 * branch above them). When no frozen supernode was ever created in the process it returns {@link
 * #NONE} without touching the tree, so callers can guard fast paths with it at no cost.
 *
 * <p>Exploration only (state diff tier 3 attempt, 2026-10-29).
 */
public final class FrozenRanges {

  public static final FrozenRanges NONE = new FrozenRanges(new int[0], new int[0]);

  private static final int CACHE_SIZE = 8;

  private static final CacheEntry[] CACHE = new CacheEntry[CACHE_SIZE];
  private static final AtomicInteger NEXT_CACHE_SLOT = new AtomicInteger();

  private final int[] starts;
  private final int[] ends;

  private FrozenRanges(final int[] starts, final int[] ends) {
    this.starts = starts;
    this.ends = ends;
  }

  /**
   * Collects the frozen ranges of the list of {@code length} elements whose backing node is {@code
   * listNode} and schema {@code listSchema}.
   */
  public static FrozenRanges collect(
      final SszCompositeSchema<?> listSchema, final TreeNode listNode, final int length) {
    if (!FrozenSuperNode.anyCreated() || length == 0) {
      return NONE;
    }
    final IntArrayList starts = new IntArrayList();
    final IntArrayList ends = new IntArrayList();
    final long firstElementGIndex = listSchema.getChildGeneralizedIndex(0);
    final long lastElementGIndex = listSchema.getChildGeneralizedIndex(length - 1);
    listNode.iterateRange(
        firstElementGIndex,
        lastElementGIndex,
        (node, gIndex) -> {
          if (node instanceof FrozenSuperNode frozen) {
            final long firstChildGIndex = gIndex << frozen.getDepth();
            final int start = elementIndexOf(listSchema, length, firstChildGIndex);
            final int end = Math.min(length, start + frozen.getElementCount());
            if (!ends.isEmpty() && ends.getInt(ends.size() - 1) == start) {
              ends.set(ends.size() - 1, end);
            } else {
              starts.add(start);
              ends.add(end);
            }
          }
          return true;
        });
    return starts.isEmpty() ? NONE : new FrozenRanges(starts.toIntArray(), ends.toIntArray());
  }

  /**
   * As {@link #collect}, memoized by the identity of {@code listNode} in a small process-wide cache
   * (a backing tree is immutable, so its frozen ranges never change).
   */
  @SuppressWarnings({"ReferenceEquality", "ReferenceComparison"})
  public static FrozenRanges collectCached(
      final SszCompositeSchema<?> listSchema, final TreeNode listNode, final int length) {
    if (!FrozenSuperNode.anyCreated()) {
      return NONE;
    }
    synchronized (CACHE) {
      for (final CacheEntry entry : CACHE) {
        if (entry != null && entry.key().get() == listNode) {
          return entry.ranges();
        }
      }
    }
    final FrozenRanges ranges = collect(listSchema, listNode, length);
    synchronized (CACHE) {
      CACHE[NEXT_CACHE_SLOT.getAndUpdate(slot -> (slot + 1) % CACHE_SIZE)] =
          new CacheEntry(new WeakReference<>(listNode), ranges);
    }
    return ranges;
  }

  /**
   * Replaces the {@link SszSuperNode}s of the list of {@code length} elements backed by {@code
   * listNode} with the node {@code freezer} returns for them, when it returns one. For tests and
   * the measurement harness, which build frozen variants of a state in memory.
   */
  public static TreeNode replaceSupernodes(
      final SszCompositeSchema<?> listSchema,
      final TreeNode listNode,
      final int length,
      final Function<SszSuperNode, Optional<TreeNode>> freezer) {
    if (length == 0) {
      return listNode;
    }
    final NavigableMap<Integer, NavigableMap<Long, TreeNode>> byDepth = new TreeMap<>();
    listNode.iterateRange(
        listSchema.getChildGeneralizedIndex(0),
        listSchema.getChildGeneralizedIndex(length - 1),
        (node, gIndex) -> {
          if (node instanceof SszSuperNode superNode) {
            freezer
                .apply(superNode)
                .ifPresent(
                    replacement ->
                        byDepth
                            .computeIfAbsent(GIndexUtil.gIdxGetDepth(gIndex), __ -> new TreeMap<>())
                            .put(gIndex, replacement));
          }
          return true;
        });
    TreeNode result = listNode;
    for (final Map.Entry<Integer, NavigableMap<Long, TreeNode>> depth :
        byDepth.descendingMap().entrySet()) {
      result =
          result.updated(
              new TreeUpdates(
                  new ArrayList<>(depth.getValue().keySet()),
                  List.copyOf(depth.getValue().values())));
    }
    return result;
  }

  /** The index of the element whose generalized index is {@code elementGIndex}. */
  private static int elementIndexOf(
      final SszCompositeSchema<?> listSchema, final int length, final long elementGIndex) {
    int low = 0;
    int high = length - 1;
    while (low <= high) {
      final int mid = (low + high) >>> 1;
      final NodeRelation relation =
          GIndexUtil.gIdxCompare(listSchema.getChildGeneralizedIndex(mid), elementGIndex);
      if (relation == NodeRelation.SAME) {
        return mid;
      } else if (relation == NodeRelation.LEFT) {
        low = mid + 1;
      } else if (relation == NodeRelation.RIGHT) {
        high = mid - 1;
      } else {
        throw new IllegalStateException(
            "Element gIndex " + elementGIndex + " is an ancestor or descendant of an element");
      }
    }
    throw new IllegalStateException("No element at gIndex " + elementGIndex);
  }

  public boolean isEmpty() {
    return starts.length == 0;
  }

  /** The number of ranges. */
  public int size() {
    return starts.length;
  }

  public int start(final int range) {
    return starts[range];
  }

  public int end(final int range) {
    return ends[range];
  }

  /** The number of elements in all ranges. */
  public long coveredElements() {
    long covered = 0;
    for (int i = 0; i < starts.length; i++) {
      covered += ends[i] - starts[i];
    }
    return covered;
  }

  /** Whether {@code index} is in a range. */
  public boolean contains(final int index) {
    int low = 0;
    int high = starts.length - 1;
    while (low <= high) {
      final int mid = (low + high) >>> 1;
      if (ends[mid] <= index) {
        low = mid + 1;
      } else if (starts[mid] > index) {
        high = mid - 1;
      } else {
        return true;
      }
    }
    return false;
  }

  private record CacheEntry(WeakReference<TreeNode> key, FrozenRanges ranges) {}

  /** A cursor answering {@link Cursor#isFrozen} in amortized constant time for rising indices. */
  public Cursor cursor() {
    return new Cursor();
  }

  /** Not thread-safe; indices passed to it must not decrease. */
  public final class Cursor {
    private int range = 0;

    private Cursor() {}

    public boolean isFrozen(final int index) {
      while (range < ends.length && ends[range] <= index) {
        range++;
      }
      return range < starts.length && starts[range] <= index;
    }

    /**
     * The end of the range holding {@code index}, or {@code index} when it is in none. Indices
     * passed to the cursor must not decrease.
     */
    public int frozenUntil(final int index) {
      return isFrozen(index) ? ends[range] : index;
    }
  }
}
