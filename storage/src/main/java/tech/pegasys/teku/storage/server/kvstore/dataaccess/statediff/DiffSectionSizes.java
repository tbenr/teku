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
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.DeltaEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.LeafEntry;

/**
 * Entry counts and approximate payload bytes of the sections of a {@link TreeDiff}, for metrics and
 * logs. Bytes count the data each entry carries, not the varint gIndexes and length prefixes around
 * it: the leaf data, 32 per supernode reference and per zeroed subtree root, 16 per copied subtree
 * (its two gIndexes) and the raw encoded deltas. The compressed size of the deltas, as stored, is
 * given separately.
 *
 * @param compressedDeltaBytes the bytes of the delta entries after compression, as stored
 */
record DiffSectionSizes(
    Section leaves,
    Section supernodes,
    Section zeroed,
    Section copied,
    Section deltas,
    long compressedDeltaBytes) {

  private static final int COPIED_ENTRY_BYTES = 2 * Long.BYTES;

  /**
   * @param compressedDeltaBytes the bytes of {@code diff}'s delta entries once compressed
   */
  static DiffSectionSizes of(final TreeDiff diff, final long compressedDeltaBytes) {
    long leafBytes = 0;
    for (final LeafEntry leaf : diff.leaves()) {
      leafBytes += leaf.data().size();
    }
    long deltaBytes = 0;
    for (final DeltaEntry delta : diff.deltas()) {
      deltaBytes += delta.encodedDeltas().length;
    }
    return new DiffSectionSizes(
        new Section("leaves", diff.leaves().size(), leafBytes),
        new Section(
            "supernodes", diff.supernodes().size(), (long) Bytes32.SIZE * diff.supernodes().size()),
        new Section("zeroed", diff.zeroed().size(), (long) Bytes32.SIZE * diff.zeroed().size()),
        new Section(
            "copied", diff.copied().size(), (long) COPIED_ENTRY_BYTES * diff.copied().size()),
        new Section("deltas", diff.deltas().size(), deltaBytes),
        compressedDeltaBytes);
  }

  List<Section> sections() {
    return List.of(leaves, supernodes, zeroed, copied, deltas);
  }

  record Section(String label, int entries, long bytes) {}
}
