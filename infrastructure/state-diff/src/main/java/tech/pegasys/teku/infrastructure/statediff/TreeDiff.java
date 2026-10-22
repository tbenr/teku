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
 * @param zeroed subtrees replaced by the zero tree with the given root
 * @param copied subtrees equal to another subtree of the base tree
 * @param deltas uint64 list fields stored as {@link UInt64DeltaCodec} element deltas
 */
public record TreeDiff(
    List<LeafEntry> leaves,
    List<SupernodeEntry> supernodes,
    List<ZeroedEntry> zeroed,
    List<CopiedEntry> copied,
    List<DeltaEntry> deltas) {

  private static final TreeDiff EMPTY =
      new TreeDiff(List.of(), List.of(), List.of(), List.of(), List.of());

  public TreeDiff(
      final List<LeafEntry> leaves,
      final List<SupernodeEntry> supernodes,
      final List<ZeroedEntry> zeroed,
      final List<CopiedEntry> copied,
      final List<DeltaEntry> deltas) {
    this.leaves = List.copyOf(leaves);
    this.supernodes = List.copyOf(supernodes);
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
    return leaves.size() + supernodes.size() + zeroed.size() + copied.size() + deltas.size();
  }

  public record LeafEntry(long gIndex, Bytes data) {}

  public record SupernodeEntry(int fieldIndex, long gIndex, int depth, Bytes32 hash) {}

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
