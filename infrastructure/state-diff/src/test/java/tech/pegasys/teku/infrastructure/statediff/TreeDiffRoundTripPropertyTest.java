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

class TreeDiffRoundTripPropertyTest {

  private static final int ITERATIONS = 100;
  private static final int CHAIN_LENGTH = 5;
  private static final int MAX_MUTATIONS = 20;
  private static final int INITIAL_VALIDATORS = 300;
  private static final int VALIDATOR_FIELD_COUNT = 8;

  private final Map<Bytes32, Bytes> supernodeStore = new HashMap<>();
  private final TreeDiffer differ = new TreeDiffer(SCHEMA, SyntheticState.DELTA_FIELDS);
  private final TreeDiffApplier applier =
      new TreeDiffApplier(SCHEMA, hash -> Optional.ofNullable(supernodeStore.get(hash)));

  @Test
  void randomMutationSequencesRoundTrip() {
    final Random random = new Random(12345);
    final TreeNode genesis = SyntheticState.create(random, INITIAL_VALIDATORS);

    for (int iteration = 0; iteration < ITERATIONS; iteration++) {
      final List<TreeDiff> chain = new ArrayList<>();
      TreeNode base = genesis;
      for (int step = 0; step < CHAIN_LENGTH; step++) {
        final TreeNode target = mutate(random, base);
        final TreeDiff decoded =
            TreeDiffCodec.deserialize(
                TreeDiffCodec.serialize(differ.diff(base, target, supernodeStore::put)));

        assertReproduces(applier.apply(base, decoded), target, iteration, step);
        chain.add(decoded);
        base = target;
      }

      TreeNode reconstructed = genesis;
      for (final TreeDiff diff : chain) {
        reconstructed = applier.apply(reconstructed, diff);
      }
      assertReproduces(reconstructed, base, iteration, CHAIN_LENGTH);
    }
  }

  private static void assertReproduces(
      final TreeNode actual, final TreeNode expected, final int iteration, final int step) {
    assertThat(actual.hashTreeRoot())
        .describedAs("iteration %s step %s", iteration, step)
        .isEqualTo(expected.hashTreeRoot());
    assertThat(SyntheticState.serialize(actual))
        .describedAs("iteration %s step %s", iteration, step)
        .isEqualTo(SyntheticState.serialize(expected));
  }

  private static TreeNode mutate(final Random random, final TreeNode tree) {
    TreeNode result = tree;
    final int mutationCount = 1 + random.nextInt(MAX_MUTATIONS);
    for (int i = 0; i < mutationCount; i++) {
      result = mutateOnce(random, result);
    }
    return result;
  }

  private static TreeNode mutateOnce(final Random random, final TreeNode tree) {
    final int validatorCount = SyntheticState.validators(tree).size();
    final int balanceCount = SyntheticState.balances(tree).size();
    final int flagCount = SyntheticState.flags(tree).size();
    return switch (random.nextInt(10)) {
      case 0 -> SyntheticState.setA(tree, random.nextLong());
      case 1 -> SyntheticState.setB(tree, Bytes32.random(random));
      case 2 ->
          SyntheticState.setRandomValidatorField(
              tree, random, random.nextInt(validatorCount), random.nextInt(VALIDATOR_FIELD_COUNT));
      case 3 -> SyntheticState.appendValidators(tree, random, 1 + random.nextInt(40));
      case 4 ->
          balanceCount == 0
              ? SyntheticState.appendBalances(tree, random, 1)
              : SyntheticState.setBalance(tree, random.nextInt(balanceCount), random.nextLong());
      case 5 -> SyntheticState.appendBalances(tree, random, 1 + random.nextInt(40));
      case 6 ->
          flagCount == 0
              ? SyntheticState.withField(
                  tree,
                  SyntheticState.FLAGS,
                  SyntheticState.appendFlags(
                      tree.get(SyntheticState.fieldGIndex(SyntheticState.FLAGS)), random, 1))
              : SyntheticState.setFlag(tree, random.nextInt(flagCount), (byte) random.nextInt(8));
      case 7 -> SyntheticState.rotateFlags(tree);
      case 8 -> SyntheticState.clearFlags(tree);
      default -> SyntheticState.zeroFlags(tree, validatorCount);
    };
  }
}
