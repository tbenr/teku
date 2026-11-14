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

import java.util.Map;
import java.util.stream.Collectors;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.infrastructure.statediff.SupernodeSource;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff;
import tech.pegasys.teku.infrastructure.statediff.TreeDiffApplier;
import tech.pegasys.teku.infrastructure.statediff.TreeDiffCodec;

/** Payloads, supernode sources and single-diff application, for storage tests. */
final class TestTreeDiffs {

  private TestTreeDiffs() {}

  /** The payload of {@code diff}, without a reserved prefix. */
  static Bytes serialize(final TreeDiff diff) {
    return TreeDiffCodec.encode(diff, 0).payload();
  }

  /** A source over {@code store}, read when the supernodes are loaded. */
  static SupernodeSource source(final Map<Bytes32, Bytes> store) {
    return hashes ->
        hashes.stream()
            .filter(store::containsKey)
            .collect(Collectors.toMap(hash -> hash, store::get));
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
