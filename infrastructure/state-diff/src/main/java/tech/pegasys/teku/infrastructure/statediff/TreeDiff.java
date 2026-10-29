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

package tech.pegasys.teku.infrastructure.statediff;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntLists;
import java.util.Arrays;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * The set of changes that turns a base state tree into a target state tree, addressed by
 * generalized index.
 *
 * @param leaves leaf nodes whose data changed
 * @param supernodes packed supernodes whose content changed, stored by hash outside the diff
 * @param patches packed supernodes of the base tree with some elements rewritten or appended,
 *     carried inline
 * @param zeroed subtrees replaced by the zero tree with the given root
 * @param copied subtrees equal to another subtree of the base tree
 * @param deltas uint64 list fields stored as {@link UInt64DeltaCodec} element deltas
 */
public record TreeDiff(
    List<LeafEntry> leaves,
    List<SupernodeEntry> supernodes,
    List<PatchEntry> patches,
    List<ZeroedEntry> zeroed,
    List<CopiedEntry> copied,
    List<DeltaEntry> deltas) {

  private static final TreeDiff EMPTY =
      new TreeDiff(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

  public TreeDiff(
      final List<LeafEntry> leaves,
      final List<SupernodeEntry> supernodes,
      final List<PatchEntry> patches,
      final List<ZeroedEntry> zeroed,
      final List<CopiedEntry> copied,
      final List<DeltaEntry> deltas) {
    this.leaves = List.copyOf(leaves);
    this.supernodes = List.copyOf(supernodes);
    this.patches = List.copyOf(patches);
    this.zeroed = List.copyOf(zeroed);
    this.copied = List.copyOf(copied);
    this.deltas = List.copyOf(deltas);
  }

  public static TreeDiff empty() {
    return EMPTY;
  }

  public boolean isEmpty() {
    return entryCount() == 0;
  }

  public int entryCount() {
    return leaves.size()
        + supernodes.size()
        + patches.size()
        + zeroed.size()
        + copied.size()
        + deltas.size();
  }

  public record LeafEntry(long gIndex, Bytes data) {}

  public record SupernodeEntry(int fieldIndex, long gIndex, int depth, Bytes32 hash) {}

  /**
   * The supernode of depth {@code depth} at {@code gIndex} of the base tree, a supernode of the
   * list field {@code fieldIndex}, resized to {@code elementCount} elements of {@code elementSize}
   * bytes and with the elements at {@code elementIndices} replaced by the consecutive {@code
   * elementSize} byte slices of {@code elements}. Elements past the base supernode's count that are
   * not written are zero. An inconsistent entry is rejected with an {@link
   * IllegalArgumentException}.
   *
   * @param elementIndices strictly increasing element indices, each below {@code elementCount}
   * @param elements the written elements, {@code elementSize} bytes each, in index order
   */
  public record PatchEntry(
      int fieldIndex,
      long gIndex,
      int depth,
      int elementCount,
      int elementSize,
      IntList elementIndices,
      Bytes elements) {

    /** The deepest supernode a patch can address, so its element count fits in an int. */
    static final int MAX_DEPTH = 30;

    public PatchEntry(
        final int fieldIndex,
        final long gIndex,
        final int depth,
        final int elementCount,
        final int elementSize,
        final IntList elementIndices,
        final Bytes elements) {
      if (depth < 0 || depth > MAX_DEPTH) {
        throw new IllegalArgumentException("Invalid patch depth " + depth + " at gIndex " + gIndex);
      }
      if (elementCount < 0 || elementCount > 1 << depth) {
        throw new IllegalArgumentException(
            "Patch at gIndex "
                + gIndex
                + " has "
                + elementCount
                + " elements, more than a supernode of depth "
                + depth
                + " holds");
      }
      if (elementSize <= 0) {
        throw new IllegalArgumentException(
            "Invalid patch element size " + elementSize + " at gIndex " + gIndex);
      }
      int previous = -1;
      for (int i = 0; i < elementIndices.size(); i++) {
        final int index = elementIndices.getInt(i);
        if (index <= previous || index >= elementCount) {
          throw new IllegalArgumentException(
              "Patch at gIndex "
                  + gIndex
                  + " writes element "
                  + index
                  + " out of order or beyond its "
                  + elementCount
                  + " elements");
        }
        previous = index;
      }
      if (elements.size() != (long) elementIndices.size() * elementSize) {
        throw new IllegalArgumentException(
            "Patch at gIndex "
                + gIndex
                + " carries "
                + elements.size()
                + " bytes for "
                + elementIndices.size()
                + " elements of "
                + elementSize
                + " bytes");
      }
      this.fieldIndex = fieldIndex;
      this.gIndex = gIndex;
      this.depth = depth;
      this.elementCount = elementCount;
      this.elementSize = elementSize;
      this.elementIndices = IntLists.unmodifiable(new IntArrayList(elementIndices));
      this.elements = elements;
    }
  }

  public record ZeroedEntry(long gIndex, Bytes32 zeroRoot) {}

  public record CopiedEntry(long targetGIndex, long sourceGIndex) {}

  /** Compares {@code encodedDeltas} by content rather than by array identity. */
  @SuppressWarnings("ArrayRecordComponent")
  public record DeltaEntry(int fieldIndex, byte[] encodedDeltas) {

    @Override
    public boolean equals(final Object o) {
      if (this == o) {
        return true;
      }
      return o instanceof DeltaEntry other
          && fieldIndex == other.fieldIndex
          && Arrays.equals(encodedDeltas, other.encodedDeltas);
    }

    @Override
    public int hashCode() {
      return 31 * Integer.hashCode(fieldIndex) + Arrays.hashCode(encodedDeltas);
    }

    @Override
    public String toString() {
      return "DeltaEntry[fieldIndex="
          + fieldIndex
          + ", encodedDeltas="
          + Bytes.wrap(encodedDeltas)
          + "]";
    }
  }
}
