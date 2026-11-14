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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.DeltaEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.PatchEntry;

/** Diffs, payloads, supernode sources and single-diff application, for tests. */
final class TestTreeDiffs {

  static final TreeDiff EMPTY =
      new TreeDiff(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

  private TestTreeDiffs() {}

  static TreeDiff onlyPatches(final PatchEntry... patches) {
    return new TreeDiff(List.of(), List.of(), List.of(patches), List.of(), List.of(), List.of());
  }

  static TreeDiff onlyDeltas(final DeltaEntry... deltas) {
    return new TreeDiff(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(deltas));
  }

  static int entryCount(final TreeDiff diff) {
    return diff.leaves().size()
        + diff.supernodes().size()
        + diff.patches().size()
        + diff.zeroed().size()
        + diff.copied().size()
        + diff.deltas().size();
  }

  /** The payload of {@code diff}, without a reserved prefix. */
  static Bytes serialize(final TreeDiff diff) {
    return TreeDiffCodec.encode(diff, 0).payload();
  }

  /** A source over {@code store}, read when the supernodes are loaded. */
  static SupernodeSource source(final Map<Bytes32, Bytes> store) {
    return hashes -> {
      final Map<Bytes32, Bytes> loaded = new HashMap<>();
      hashes.forEach(
          hash -> {
            final Bytes ssz = store.get(hash);
            if (ssz != null) {
              loaded.put(hash, ssz);
            }
          });
      return loaded;
    };
  }

  /** Applies {@code diff} alone to {@code base}: a chain of one diff. */
  static TreeNode apply(
      final TreeDiffApplier applier,
      final TreeNode base,
      final TreeDiff diff,
      final SupernodeSource source) {
    final TreeDiffApplier.ChainApplication chain = applier.startChain(base, source);
    chain.apply(diff);
    return chain.finish();
  }
}
