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
import static tech.pegasys.teku.infrastructure.statediff.SyntheticState.SCHEMA;
import static tech.pegasys.teku.infrastructure.statediff.SyntheticState.VALIDATORS;
import static tech.pegasys.teku.infrastructure.statediff.SyntheticState.VALIDATOR_EFFECTIVE_BALANCE;
import static tech.pegasys.teku.infrastructure.statediff.SyntheticState.fieldGIndex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszUInt64;
import tech.pegasys.teku.infrastructure.ssz.tree.FrozenSuperNode;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

/** The writer and the applier over a base whose validator supernodes are frozen. */
class FrozenSupernodeDiffTest {

  private static final TreeNode BASE = SyntheticState.create(new Random(3), 600);

  private final Map<Bytes32, Bytes> store = new HashMap<>();
  private final SupernodeSource source = hash -> Optional.ofNullable(store.get(hash));
  private final AtomicInteger loads = new AtomicInteger();
  private final TreeDiffer differ = new TreeDiffer(SCHEMA, SyntheticState.DELTA_FIELDS);
  private final TreeDiffApplier applier = new TreeDiffApplier(SCHEMA);

  /** Freezes every whole reference of the validators field, loading from {@link #store}. */
  private final SupernodeFreezer freezer =
      (entry, template, ssz) ->
          entry.fieldIndex() == VALIDATORS
              ? new FrozenSuperNode(
                  entry.depth(),
                  template,
                  ssz.size() / template.getSszLength(),
                  entry.hash(),
                  () -> {
                    loads.incrementAndGet();
                    return store.get(entry.hash());
                  })
              : SupernodeFreezer.NONE.supernode(entry, template, ssz);

  /** {@link #BASE} rebuilt from its snapshot with every validator supernode frozen. */
  private TreeNode frozenBase() {
    final TreeDiff snapshot = differ.diff(SCHEMA.getDefaultTree(), BASE, store::put);
    final TreeNode frozen =
        applier.apply(SCHEMA.getDefaultTree(), roundTrip(snapshot), source, freezer);
    assertThat(frozen.hashTreeRoot()).isEqualTo(BASE.hashTreeRoot());
    assertThat(frozenSupernodes(frozen)).isEqualTo(3);
    loads.set(0);
    return frozen;
  }

  @Test
  void frozenTreeHasTheRootAndContentOfThePlainTree() {
    final TreeNode frozen = frozenBase();

    assertThat(loads).hasValue(0);
    assertThat(SyntheticState.serialize(frozen)).isEqualTo(SyntheticState.serialize(BASE));
    assertThat(loads).hasValue(3);
  }

  @Test
  void diffAgainstAFrozenBaseEqualsTheDiffAgainstThePlainBase() {
    final TreeNode frozen = frozenBase();
    final TreeNode target = changeValidator(BASE, 300);

    final Bytes plainPayload = TreeDiffCodec.serialize(differ.diff(BASE, target, store::put));
    final Bytes frozenPayload = TreeDiffCodec.serialize(differ.diff(frozen, target, store::put));

    assertThat(frozenPayload).isEqualTo(plainPayload);
    // Only the changed supernode's base is loaded, to build its patch
    assertThat(loads).hasValue(1);
  }

  @Test
  void unchangedFrozenSupernodesAreNeverLoaded() {
    final TreeNode frozen = frozenBase();
    final TreeNode target = SyntheticState.setBalance(SyntheticState.setA(BASE, 77), 5, 9);

    final Bytes plainPayload = TreeDiffCodec.serialize(differ.diff(BASE, target, store::put));
    final Bytes frozenPayload = TreeDiffCodec.serialize(differ.diff(frozen, target, store::put));

    assertThat(frozenPayload).isEqualTo(plainPayload);
    assertThat(loads).hasValue(0);
  }

  @Test
  void stateDerivedFromAFrozenTreeDiffsAndAppliesLikeThePlainOne() {
    final TreeNode frozen = frozenBase();
    final TreeNode plainTarget = changeValidator(BASE, 300);
    final TreeNode frozenTarget = changeValidator(frozen, 300);
    final int loadsToUpdate = loads.get();

    final TreeDiff diff = differ.diff(frozen, frozenTarget, store::put);
    final TreeNode applied = applier.apply(frozen, roundTrip(diff), source, freezer);

    assertThat(frozenTarget.hashTreeRoot()).isEqualTo(plainTarget.hashTreeRoot());
    assertThat(TreeDiffCodec.serialize(diff))
        .isEqualTo(TreeDiffCodec.serialize(differ.diff(BASE, plainTarget, store::put)));
    assertThat(applied.hashTreeRoot()).isEqualTo(plainTarget.hashTreeRoot());
    assertThat(frozenSupernodes(frozenTarget)).isEqualTo(2);
    // The update thawed one supernode; the differ and the patch load the frozen base once each
    assertThat(loadsToUpdate).isEqualTo(1);
    assertThat(loads.get() - loadsToUpdate).isLessThanOrEqualTo(2);
  }

  @Test
  void snapshotOfAFrozenTreeLoadsNothingWhenTheSinkDoesNotAskForContent() {
    final TreeNode frozen = frozenBase();
    final List<Bytes32> referenced = new ArrayList<>();

    final TreeDiff frozenSnapshot =
        differ.diffLazy(SCHEMA.getDefaultTree(), frozen, (hash, content) -> referenced.add(hash));
    final TreeDiff plainSnapshot = differ.diff(SCHEMA.getDefaultTree(), BASE, (h, s) -> {});

    assertThat(loads).hasValue(0);
    assertThat(referenced).hasSize(3);
    assertThat(TreeDiffCodec.serialize(frozenSnapshot))
        .isEqualTo(TreeDiffCodec.serialize(plainSnapshot));
  }

  private static TreeNode changeValidator(final TreeNode tree, final int index) {
    return SyntheticState.setValidatorField(
        tree, index, VALIDATOR_EFFECTIVE_BALANCE, SszUInt64.of(UInt64.valueOf(31 + index)));
  }

  private static int frozenSupernodes(final TreeNode tree) {
    final AtomicInteger count = new AtomicInteger();
    tree.get(fieldGIndex(VALIDATORS))
        .iterateAll(
            (final TreeNode node) -> {
              if (node instanceof FrozenSuperNode) {
                count.incrementAndGet();
              }
            });
    return count.get();
  }

  private static TreeDiff roundTrip(final TreeDiff diff) {
    return TreeDiffCodec.deserialize(TreeDiffCodec.serialize(diff));
  }
}
