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

import static org.assertj.core.api.Assertions.assertThat;

import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.ssz.collections.SszUInt64List;
import tech.pegasys.teku.infrastructure.ssz.impl.SszContainerImpl;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszUInt64;
import tech.pegasys.teku.infrastructure.ssz.schema.SszContainerSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.SszPrimitiveSchemas;
import tech.pegasys.teku.infrastructure.ssz.schema.collections.SszUInt64ListSchema;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

/**
 * Times the packed uint64 list paths on a 200k-element list: read, write, applying one delta diff
 * and reconstructing a 10-link chain (a snapshot and 9 diffs) link by link and with folded deltas.
 * Prints the timings; asserts only that the results are correct.
 */
class PackedUInt64ListsBenchmarkTest {

  private static final int SIZE = 200_000;
  private static final int CHAIN_LINKS = 10;
  private static final int WARMUP = 3;
  private static final int RUNS = 5;

  private static final SszUInt64ListSchema<SszUInt64List> LIST_SCHEMA =
      SszUInt64ListSchema.create(1L << 40);
  private static final SszContainerSchema<SszContainerImpl> SCHEMA =
      SszContainerSchema.create(
          List.of(SszPrimitiveSchemas.UINT64_SCHEMA, LIST_SCHEMA), SszContainerImpl::new);
  private static final int LIST_FIELD = 1;

  private final Random random = new Random(42);
  private final TreeDiffer differ = new TreeDiffer(SCHEMA, IntSet.of(LIST_FIELD));
  private final SupernodeSource source = hash -> Optional.empty();
  private final TreeDiffApplier applier = new TreeDiffApplier(SCHEMA);

  @Test
  void timePackedListPaths() {
    final long[] values = new long[SIZE];
    for (int i = 0; i < SIZE; i++) {
      values[i] = 32_000_000_000L + random.nextInt(1_000_000_000);
    }
    // Deserialized, as states loaded from disk are
    final TreeNode listNode =
        LIST_SCHEMA
            .sszDeserialize(
                LIST_SCHEMA.createFromBackingNode(writeReference(values)).sszSerialize())
            .getBackingNode();

    final double readMs = time(() -> PackedUInt64Lists.read(LIST_SCHEMA, listNode));
    assertThat(PackedUInt64Lists.read(LIST_SCHEMA, listNode)).isEqualTo(values);
    final double writeMs = time(() -> PackedUInt64Lists.write(LIST_SCHEMA, values));

    final List<TreeNode> states = new ArrayList<>();
    long[] current = values;
    for (int link = 0; link < CHAIN_LINKS; link++) {
      states.add(state(link, current));
      current = rewards(current);
    }
    final List<TreeDiff> diffs = new ArrayList<>();
    TreeNode base = SCHEMA.getDefaultTree();
    for (final TreeNode state : states) {
      diffs.add(differ.diff(base, state, (hash, ssz) -> {}));
      base = state;
    }

    final TreeDiff single = diffs.get(1);
    final TreeNode singleBase = states.get(0);
    final double applyMs = time(() -> applier.apply(singleBase, single, source));
    assertThat(applier.apply(singleBase, single, source).hashTreeRoot())
        .isEqualTo(states.get(1).hashTreeRoot());

    final double chainMs = time(() -> reconstructPerLink(diffs));
    assertThat(reconstructPerLink(diffs).hashTreeRoot()).isEqualTo(states.getLast().hashTreeRoot());
    final double foldedMs = time(() -> reconstructFolded(diffs));
    assertThat(reconstructFolded(diffs).hashTreeRoot()).isEqualTo(states.getLast().hashTreeRoot());

    System.out.printf(
        "PackedUInt64Lists benchmark (%d elements, avg of %d runs): read %.2f ms, write %.2f ms,"
            + " single apply %.2f ms, %d-link chain per link %.2f ms, folded %.2f ms%n",
        SIZE, RUNS, readMs, writeMs, applyMs, CHAIN_LINKS, chainMs, foldedMs);
  }

  private TreeNode reconstructPerLink(final List<TreeDiff> diffs) {
    TreeNode tree = SCHEMA.getDefaultTree();
    for (final TreeDiff diff : diffs) {
      tree = applier.apply(tree, diff, source);
    }
    return tree;
  }

  private TreeNode reconstructFolded(final List<TreeDiff> diffs) {
    final TreeDiffApplier.ChainApplication chain =
        applier.startChain(SCHEMA.getDefaultTree(), source);
    diffs.forEach(chain::apply);
    return chain.finish();
  }

  private long[] rewards(final long[] values) {
    final long[] next = values.clone();
    for (int i = 0; i < next.length; i++) {
      next[i] += random.nextInt(20_000) - 2_000;
    }
    return next;
  }

  private static TreeNode state(final long a, final long[] values) {
    TreeNode tree = SCHEMA.getDefaultTree();
    tree =
        tree.updated(
            SCHEMA.getChildGeneralizedIndex(0), SszUInt64.of(UInt64.valueOf(a)).getBackingNode());
    tree = tree.updated(SCHEMA.getChildGeneralizedIndex(LIST_FIELD), writeReference(values));
    tree.hashTreeRoot();
    return tree;
  }

  private static TreeNode writeReference(final long[] values) {
    final List<SszUInt64> elements = new ArrayList<>(values.length);
    for (final long value : values) {
      elements.add(SszUInt64.of(UInt64.fromLongBits(value)));
    }
    return LIST_SCHEMA.createTreeFromElements(elements);
  }

  private static double time(final Supplier<?> action) {
    for (int i = 0; i < WARMUP; i++) {
      consume(action.get());
    }
    final long start = System.nanoTime();
    for (int i = 0; i < RUNS; i++) {
      consume(action.get());
    }
    return (System.nanoTime() - start) / 1e6 / RUNS;
  }

  private static void consume(final Object result) {
    if (result instanceof TreeNode node) {
      node.hashTreeRoot();
    }
  }
}
