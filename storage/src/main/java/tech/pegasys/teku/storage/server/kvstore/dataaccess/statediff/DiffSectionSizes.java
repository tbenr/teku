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
import tech.pegasys.teku.infrastructure.statediff.TreeDiff;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.DeltaEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiffCodec;
import tech.pegasys.teku.infrastructure.statediff.UInt64DeltaCodec;

/**
 * Entry counts and payload bytes of the sections of a {@link TreeDiff}, for metrics and logs. The
 * bytes of a section are those it takes in the payload, its count, group headers, gIndexes and
 * lengths included, so that the sections other than {@code deltas} sum to the payload size less its
 * version byte. The delta entries are given twice: {@code deltas} counts their raw {@link
 * UInt64DeltaCodec} bytes, before compression, and {@code deltasCompressed} the section as stored.
 */
record DiffSectionSizes(
    Section leaves,
    Section supernodes,
    Section patches,
    Section zeroed,
    Section copied,
    Section deltas,
    Section deltasCompressed) {

  static DiffSectionSizes of(final TreeDiff diff, final TreeDiffCodec.SectionBytes bytes) {
    long rawDeltaBytes = 0;
    for (final DeltaEntry delta : diff.deltas()) {
      rawDeltaBytes += delta.encodedDeltas().length;
    }
    return new DiffSectionSizes(
        new Section("leaves", diff.leaves().size(), bytes.leaves()),
        new Section("supernodes", diff.supernodes().size(), bytes.supernodes()),
        new Section("patches", diff.patches().size(), bytes.patches()),
        new Section("zeroed", diff.zeroed().size(), bytes.zeroed()),
        new Section("copied", diff.copied().size(), bytes.copied()),
        new Section("deltas", diff.deltas().size(), rawDeltaBytes),
        new Section("deltas_compressed", diff.deltas().size(), bytes.deltas()));
  }

  List<Section> sections() {
    return List.of(leaves, supernodes, patches, zeroed, copied, deltas, deltasCompressed);
  }

  record Section(String label, int entries, long bytes) {}
}
