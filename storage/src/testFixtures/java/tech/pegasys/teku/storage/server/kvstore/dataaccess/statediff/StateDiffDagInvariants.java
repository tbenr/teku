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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.SupernodeEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiffCodec;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.storage.server.kvstore.ColumnEntry;
import tech.pegasys.teku.storage.server.kvstore.dataaccess.KvStoreCombinedDao;

/** Checks the stored state diff DAG for reference count and pointer consistency. */
public final class StateDiffDagInvariants {

  private StateDiffDagInvariants() {}

  /**
   * Asserts that:
   *
   * <ul>
   *   <li>every supernode referenced by a stored node has its SSZ stored and a count equal to the
   *       number of stored nodes referencing it;
   *   <li>no count is zero and there are no counts or SSZ entries without a referencing node;
   *   <li>every node that is not a snapshot has its parent node stored.
   * </ul>
   *
   * <p>Previous-node pointers are not required to resolve: retention deletes the nodes between
   * retained ones, and writers resolve those pointers only against nodes that exist.
   */
  public static void assertConsistent(final KvStoreCombinedDao dao) {
    final Map<Bytes32, StateDiffNodeRecord> nodes = new HashMap<>();
    try (final Stream<ColumnEntry<Bytes32, Bytes>> stream = dao.streamStateDiffNodes()) {
      stream.forEach(
          entry -> nodes.put(entry.getKey(), StateDiffNodeRecordCodec.decode(entry.getValue())));
    }

    final Map<Bytes32, Long> expectedCounts = new HashMap<>();
    nodes.forEach(
        (root, record) -> {
          if (!record.snapshot()) {
            assertThat(nodes)
                .describedAs("Parent of node %s", root)
                .containsKey(record.parentNodeRoot());
          }
          final Set<Bytes32> hashes = new HashSet<>();
          for (final SupernodeEntry entry :
              TreeDiffCodec.deserialize(record.payload()).supernodes()) {
            hashes.add(entry.hash());
          }
          hashes.forEach(hash -> expectedCounts.merge(hash, 1L, Long::sum));
        });

    final Map<Bytes32, Long> storedCounts = new HashMap<>();
    try (final Stream<ColumnEntry<Bytes32, UInt64>> stream = dao.streamStateSupernodeRefCounts()) {
      stream.forEach(entry -> storedCounts.put(entry.getKey(), entry.getValue().longValue()));
    }
    final Set<Bytes32> storedHashes;
    try (final Stream<Bytes32> stream = dao.streamStateSupernodeHashes()) {
      storedHashes = stream.collect(Collectors.toSet());
    }

    assertThat(storedCounts).describedAs("Supernode reference counts").isEqualTo(expectedCounts);
    assertThat(storedCounts.values()).describedAs("Supernode reference counts").doesNotContain(0L);
    assertThat(storedHashes).describedAs("Stored supernodes").isEqualTo(expectedCounts.keySet());
  }
}
