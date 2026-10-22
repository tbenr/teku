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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tech.pegasys.teku.infrastructure.statediff.SyntheticState.BALANCES;
import static tech.pegasys.teku.infrastructure.statediff.SyntheticState.PREV;
import static tech.pegasys.teku.infrastructure.statediff.SyntheticState.SCHEMA;
import static tech.pegasys.teku.infrastructure.statediff.SyntheticState.VALIDATOR_EFFECTIVE_BALANCE;
import static tech.pegasys.teku.infrastructure.statediff.SyntheticState.fieldGIndex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.CopiedEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.DeltaEntry;

class TreeDiffChainApplicationTest {

  private static final int VALIDATOR_COUNT = 300;
  private static final int LINKS = 10;

  private final Random random = new Random(5);
  private final Map<Bytes32, Bytes> supernodeStore = new HashMap<>();
  private final TreeDiffer differ = new TreeDiffer(SCHEMA, SyntheticState.DELTA_FIELDS);
  private final TreeDiffApplier applier =
      new TreeDiffApplier(SCHEMA, hash -> Optional.ofNullable(supernodeStore.get(hash)));

  @Test
  void foldedChainEqualsPerLinkApplicationAndBuildsEachListOnce() {
    final List<TreeNode> states = new ArrayList<>();
    TreeNode state = SyntheticState.create(random, VALIDATOR_COUNT);
    for (int link = 0; link < LINKS; link++) {
      states.add(state);
      state = evolve(state, link);
    }
    final List<TreeDiff> diffs = chainDiffs(states);

    final TreeDiffApplier.ChainApplication chain = applier.startChain(SCHEMA.getDefaultTree());
    diffs.forEach(chain::apply);
    final TreeNode folded = chain.finish();

    final TreeNode perLink = applyPerLink(diffs);
    assertThat(perLink.hashTreeRoot()).isEqualTo(states.getLast().hashTreeRoot());
    assertThat(folded.hashTreeRoot()).isEqualTo(perLink.hashTreeRoot());
    assertThat(SyntheticState.serialize(folded)).isEqualTo(SyntheticState.serialize(perLink));
    assertThat(chain.listBuilds()).isEqualTo(1);
    assertThat(diffs).allSatisfy(diff -> assertThat(diff.deltas()).hasSize(1));
  }

  @Test
  void diffAddressingDeltaFieldIsAppliedAfterPendingValues() {
    final TreeNode s0 = SyntheticState.create(random, VALIDATOR_COUNT);
    final TreeNode s1 = SyntheticState.setBalance(s0, 3, 1234);
    final TreeNode s2 = SyntheticState.setBalance(s1, 4, 5678);
    final List<TreeDiff> diffs = new ArrayList<>(chainDiffs(List.of(s0, s1)));
    // Copies the balances of s1 into another field: a fold would still see those of s0
    diffs.add(
        new TreeDiff(
            List.of(),
            List.of(),
            List.of(),
            List.of(new CopiedEntry(fieldGIndex(PREV), fieldGIndex(BALANCES))),
            diff(s1, s2).deltas()));

    final TreeDiffApplier.ChainApplication chain = applier.startChain(SCHEMA.getDefaultTree());
    diffs.forEach(chain::apply);
    final TreeNode folded = chain.finish();

    assertThat(folded.hashTreeRoot()).isEqualTo(applyPerLink(diffs).hashTreeRoot());
    assertThat(folded.get(fieldGIndex(PREV)).hashTreeRoot())
        .isEqualTo(s1.get(fieldGIndex(BALANCES)).hashTreeRoot());
    // The pending balances, then the fallback diff's own delta
    assertThat(chain.listBuilds()).isEqualTo(2);
  }

  @Test
  void rejectsDuplicateDeltaField() {
    final TreeNode s0 = SyntheticState.create(random, VALIDATOR_COUNT);
    final DeltaEntry delta = diff(SCHEMA.getDefaultTree(), s0).deltas().getFirst();
    final TreeDiff duplicate =
        new TreeDiff(List.of(), List.of(), List.of(), List.of(), List.of(delta, delta));

    assertThatThrownBy(() -> applier.apply(SCHEMA.getDefaultTree(), duplicate))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> applier.startChain(SCHEMA.getDefaultTree()).apply(duplicate))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsUseAfterFinish() {
    final TreeDiffApplier.ChainApplication chain = applier.startChain(SCHEMA.getDefaultTree());
    chain.finish();

    assertThatThrownBy(chain::finish).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> chain.apply(TreeDiff.empty()))
        .isInstanceOf(IllegalStateException.class);
  }

  /** Balance changes every link, plus a validator, fixed field or appended element on some. */
  private TreeNode evolve(final TreeNode state, final int link) {
    TreeNode next = state;
    for (int i = 0; i < 20; i++) {
      next = SyntheticState.setBalance(next, random.nextInt(VALIDATOR_COUNT), random.nextLong());
    }
    next = SyntheticState.setA(next, link);
    if (link % 3 == 0) {
      next =
          SyntheticState.setRandomValidatorField(
              next, random, random.nextInt(VALIDATOR_COUNT), VALIDATOR_EFFECTIVE_BALANCE);
    }
    if (link % 4 == 1) {
      next = SyntheticState.appendBalances(next, random, 3);
    }
    if (link % 2 == 1) {
      next = SyntheticState.rotateFlags(next);
    }
    return next;
  }

  private List<TreeDiff> chainDiffs(final List<TreeNode> states) {
    final List<TreeDiff> diffs = new ArrayList<>();
    TreeNode base = SCHEMA.getDefaultTree();
    for (final TreeNode state : states) {
      diffs.add(TreeDiffCodec.deserialize(TreeDiffCodec.serialize(diff(base, state))));
      base = state;
    }
    return diffs;
  }

  private TreeDiff diff(final TreeNode base, final TreeNode target) {
    return differ.diff(base, target, supernodeStore::putIfAbsent);
  }

  private TreeNode applyPerLink(final List<TreeDiff> diffs) {
    TreeNode tree = SCHEMA.getDefaultTree();
    for (final TreeDiff diff : diffs) {
      tree = applier.apply(tree, diff);
    }
    return tree;
  }
}
