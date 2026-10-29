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
import static tech.pegasys.teku.infrastructure.statediff.SyntheticState.A;
import static tech.pegasys.teku.infrastructure.statediff.SyntheticState.BALANCES;
import static tech.pegasys.teku.infrastructure.statediff.SyntheticState.FLAGS;
import static tech.pegasys.teku.infrastructure.statediff.SyntheticState.PREV;
import static tech.pegasys.teku.infrastructure.statediff.SyntheticState.SCHEMA;
import static tech.pegasys.teku.infrastructure.statediff.SyntheticState.SUPERNODE_DEPTH;
import static tech.pegasys.teku.infrastructure.statediff.SyntheticState.VALIDATORS;
import static tech.pegasys.teku.infrastructure.statediff.SyntheticState.VALIDATOR_EFFECTIVE_BALANCE;
import static tech.pegasys.teku.infrastructure.statediff.SyntheticState.VALIDATOR_SCHEMA;
import static tech.pegasys.teku.infrastructure.statediff.SyntheticState.fieldGIndex;

import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tech.pegasys.teku.infrastructure.ssz.RandomSszDataGenerator;
import tech.pegasys.teku.infrastructure.ssz.impl.SszContainerImpl;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszUInt64;
import tech.pegasys.teku.infrastructure.ssz.schema.SszContainerSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.SszListSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.SszPrimitiveSchemas;
import tech.pegasys.teku.infrastructure.ssz.tree.BranchNode;
import tech.pegasys.teku.infrastructure.ssz.tree.GIndexUtil;
import tech.pegasys.teku.infrastructure.ssz.tree.LeafNode;
import tech.pegasys.teku.infrastructure.ssz.tree.SszSuperNode;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.CopiedEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.DeltaEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.LeafEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.PatchEntry;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff.SupernodeEntry;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

class TreeDifferTest {

  private static final int VALIDATOR_COUNT = 600;
  private static final int CHANGED_VALIDATOR = 300;
  private static final TreeNode BASE = SyntheticState.create(new Random(1), VALIDATOR_COUNT);

  private final Map<Bytes32, Bytes> supernodeStore = new HashMap<>();
  private final TreeDiffer differ = new TreeDiffer(SCHEMA, SyntheticState.DELTA_FIELDS);
  private final SupernodeSource source = hash -> Optional.ofNullable(supernodeStore.get(hash));
  private final TreeDiffApplier applier = new TreeDiffApplier(SCHEMA);

  @Test
  void baseValidatorsAreSupernodeBacked() {
    assertThat(SyntheticState.containsSupernode(BASE.get(fieldGIndex(VALIDATORS)))).isTrue();
  }

  @Test
  void identicalTreesProduceEmptyDiff() {
    final TreeDiff diff = diff(BASE, BASE);

    assertThat(diff.isEmpty()).isTrue();
    assertThat(supernodeStore).isEmpty();
  }

  @Test
  void changedFixedFieldProducesOneLeaf() {
    final TreeNode target = SyntheticState.setA(BASE, 42);

    final TreeDiff diff = diff(BASE, target);

    assertThat(diff.entryCount()).isEqualTo(1);
    assertThat(diff.leaves())
        .containsExactly(
            new LeafEntry(fieldGIndex(A), SszUInt64.of(UInt64.valueOf(42)).sszSerialize()));
  }

  @Test
  void changedBalancesProduceDeltaSectionOnly() {
    final TreeNode target = SyntheticState.setBalance(BASE, 7, 123);

    final TreeDiff diff = diff(BASE, target);

    assertThat(diff.entryCount()).isEqualTo(1);
    assertThat(diff.deltas()).extracting(DeltaEntry::fieldIndex).containsExactly(BALANCES);
  }

  @Test
  void changedValidatorProducesOnePatchAndSinksNothing() {
    final TreeNode target = changeValidator(BASE, CHANGED_VALIDATOR);

    final TreeDiff diff = diff(BASE, target);

    assertThat(diff.entryCount()).isEqualTo(1);
    assertThat(diff.patches()).hasSize(1);
    assertThat(supernodeStore).isEmpty();
    final PatchEntry patch = diff.patches().getFirst();
    final int elementSize = VALIDATOR_SCHEMA.getSszFixedPartSize();
    assertThat(patch.fieldIndex()).isEqualTo(VALIDATORS);
    assertThat(patch.depth()).isEqualTo(SUPERNODE_DEPTH);
    assertThat(patch.elementSize()).isEqualTo(elementSize);
    assertThat(patch.elementCount()).isEqualTo(1 << SUPERNODE_DEPTH);
    assertThat(BASE.get(patch.gIndex())).isInstanceOf(SszSuperNode.class);
    assertThat(target.get(patch.gIndex())).isInstanceOf(SszSuperNode.class);
    assertThat(patch.elementIndices().toIntArray())
        .containsExactly(CHANGED_VALIDATOR % (1 << SUPERNODE_DEPTH));
    assertThat(patch.elements())
        .isEqualTo(SyntheticState.validators(target).get(CHANGED_VALIDATOR).sszSerialize());
  }

  @Test
  void patchAppliesToTheTreeOfAWholeSupernodeReference() {
    final TreeNode target =
        changeValidator(changeValidator(BASE, CHANGED_VALIDATOR), CHANGED_VALIDATOR + 7);
    final TreeDiff patchDiff = diff(BASE, target);
    final PatchEntry patch = patchDiff.patches().getFirst();
    final SszSuperNode targetSupernode = (SszSuperNode) target.get(patch.gIndex());
    supernodeStore.put(targetSupernode.hashTreeRoot(), targetSupernode.getData());
    final TreeDiff wholeDiff =
        new TreeDiff(
            List.of(),
            List.of(
                new SupernodeEntry(
                    VALIDATORS, patch.gIndex(), SUPERNODE_DEPTH, targetSupernode.hashTreeRoot())),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    final TreeNode patched = applier.apply(BASE, roundTrip(patchDiff), source);
    final TreeNode whole = applier.apply(BASE, wholeDiff, source);

    assertThat(patch.elementIndices().toIntArray()).hasSize(2);
    assertThat(patched.get(patch.gIndex())).isInstanceOf(SszSuperNode.class);
    assertThat(((SszSuperNode) patched.get(patch.gIndex())).getData())
        .isEqualTo(targetSupernode.getData());
    assertThat(patched.hashTreeRoot())
        .isEqualTo(whole.hashTreeRoot())
        .isEqualTo(target.hashTreeRoot());
    assertThat(SyntheticState.serialize(patched)).isEqualTo(SyntheticState.serialize(target));
  }

  @Test
  void mostlyChangedSupernodeProducesWholeReference() {
    TreeNode target = BASE;
    for (int i = 0; i < 1 << SUPERNODE_DEPTH; i++) {
      target = changeValidator(target, i);
    }

    final TreeDiff diff = diff(BASE, target);

    assertThat(diff.patches()).isEmpty();
    assertThat(diff.supernodes()).hasSize(1);
    final SupernodeEntry entry = diff.supernodes().getFirst();
    assertThat(entry.fieldIndex()).isEqualTo(VALIDATORS);
    assertThat(entry.depth()).isEqualTo(SUPERNODE_DEPTH);
    assertThat(target.get(entry.gIndex()).hashTreeRoot()).isEqualTo(entry.hash());
    assertThat(supernodeStore).containsOnlyKeys(entry.hash());
    final Bytes supernodeSsz = supernodeStore.get(entry.hash());
    final int elementSize = VALIDATOR_SCHEMA.getSszFixedPartSize();
    assertThat(supernodeSsz.size()).isEqualTo((1 << SUPERNODE_DEPTH) * elementSize);
    assertThat(supernodeSsz.slice(0, elementSize))
        .isEqualTo(SyntheticState.validators(target).get(0).sszSerialize());
    assertThat(applier.apply(BASE, roundTrip(diff), source).hashTreeRoot())
        .isEqualTo(target.hashTreeRoot());
  }

  @Test
  void appendedValidatorsPatchTheLastSupernodeAndReferenceNewOnes() {
    final int perSupernode = 1 << SUPERNODE_DEPTH;
    final int tailCount = VALIDATOR_COUNT % perSupernode;
    final TreeNode grown = SyntheticState.appendValidators(BASE, new Random(2), 10);
    final TreeNode spilled = SyntheticState.appendValidators(BASE, new Random(2), perSupernode);

    final TreeDiff grownDiff = diff(BASE, grown);
    final TreeDiff spilledDiff = diff(BASE, spilled);

    // Ten validators appended to the partially filled last supernode
    assertThat(grownDiff.supernodes()).isEmpty();
    final PatchEntry patch = grownDiff.patches().getFirst();
    assertThat(patch.elementCount()).isEqualTo(tailCount + 10);
    assertThat(patch.elementIndices().toIntArray())
        .containsExactly(IntStream.range(tailCount, tailCount + 10).toArray());
    // A full supernode's worth fills the last supernode and starts one the base has no node for
    assertThat(spilledDiff.patches())
        .singleElement()
        .satisfies(entry -> assertThat(entry.elementCount()).isEqualTo(perSupernode));
    assertThat(spilledDiff.supernodes()).hasSize(1);
    assertThat(applier.apply(BASE, roundTrip(grownDiff), source).hashTreeRoot())
        .isEqualTo(grown.hashTreeRoot());
    assertThat(applier.apply(BASE, roundTrip(spilledDiff), source).hashTreeRoot())
        .isEqualTo(spilled.hashTreeRoot());
  }

  @Test
  void shrunkSupernodeProducesWholeReference() {
    final TreeNode grown = SyntheticState.appendValidators(BASE, new Random(2), 10);

    final TreeDiff diff = diff(grown, BASE);

    assertThat(diff.patches()).isEmpty();
    assertThat(diff.supernodes()).hasSize(1);
    final TreeNode applied = applier.apply(grown, roundTrip(diff), source);
    assertThat(applied.hashTreeRoot()).isEqualTo(BASE.hashTreeRoot());
    assertThat(SyntheticState.serialize(applied)).isEqualTo(SyntheticState.serialize(BASE));
  }

  @Test
  void applyRejectsPatchWithoutABaseSupernodeOfItsDepth() {
    final TreeDiff diff = diff(BASE, changeValidator(BASE, CHANGED_VALIDATOR));
    final PatchEntry patch = diff.patches().getFirst();
    final TreeDiff otherDepth =
        TestTreeDiffs.onlyPatches(
            new PatchEntry(
                patch.fieldIndex(),
                patch.gIndex(),
                patch.depth() - 1,
                patch.elementCount() / 2,
                patch.elementSize(),
                patch.elementIndices(),
                patch.elements()));

    final TreeNode leafBase = BASE.updated(patch.gIndex(), LeafNode.EMPTY_LEAF);

    assertThatThrownBy(() -> applier.apply(leafBase, diff, source))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(Long.toString(patch.gIndex()));
    assertThatThrownBy(() -> applier.apply(BASE, otherDepth, source))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(Long.toString(patch.gIndex()));
  }

  @Test
  void applyRejectsPatchShrinkingItsBaseSupernode() {
    final TreeNode grown = SyntheticState.appendValidators(BASE, new Random(2), 10);
    final TreeDiff diff = roundTrip(diff(BASE, grown));
    final long gIndex = diff.patches().getFirst().gIndex();
    // The base of the patch holds one more element than the patched supernode
    final TreeNode longerBase = SyntheticState.appendValidators(grown, new Random(3), 1);

    assertThatThrownBy(() -> applier.apply(longerBase, diff, source))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(Long.toString(gIndex));
  }

  @Test
  void applyRejectsPatchOfAnotherElementSize() {
    final PatchEntry patch =
        diff(BASE, changeValidator(BASE, CHANGED_VALIDATOR)).patches().getFirst();
    final TreeDiff wrongSize =
        TestTreeDiffs.onlyPatches(
            new PatchEntry(
                patch.fieldIndex(),
                patch.gIndex(),
                patch.depth(),
                patch.elementCount(),
                patch.elementSize() - 1,
                patch.elementIndices(),
                patch.elements().slice(1)));

    assertThatThrownBy(() -> applier.apply(BASE, wrongSize, source))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(Long.toString(patch.gIndex()));
  }

  @Test
  void rotatedListProducesCopiedEntry() {
    final TreeNode target = SyntheticState.rotateFlags(BASE);

    final TreeDiff diff = diff(BASE, target);

    assertThat(diff.entryCount()).isEqualTo(1);
    assertThat(diff.copied())
        .containsExactly(new CopiedEntry(fieldGIndex(PREV), fieldGIndex(FLAGS)));
  }

  @Test
  void clearedListProducesZeroedEntry() {
    final TreeNode target = SyntheticState.clearFlags(BASE);

    final TreeDiff diff = diff(BASE, target);

    assertThat(diff.zeroed()).hasSize(1);
    assertThat(diff.supernodes()).isEmpty();
    assertThat(diff.copied()).isEmpty();
    assertThat(diff.deltas()).isEmpty();
  }

  @Test
  void equalButDistinctTreesProduceEmptyDiff() {
    final TreeNode copy = SyntheticState.deserialize(SyntheticState.serialize(BASE));

    assertThat(copy).isNotSameAs(BASE);
    assertThat(diff(BASE, copy).isEmpty()).isTrue();
  }

  @Test
  void appendedZeroElementEmitsResizedChunk() {
    final TreeNode target = SyntheticState.appendZeroFlag(BASE);
    final long flagsDataGIndex = GIndexUtil.gIdxLeftGIndex(fieldGIndex(FLAGS));
    // The partially filled last chunk grows by one zero byte, which leaves its root unchanged
    assertThat(target.get(flagsDataGIndex).hashTreeRoot())
        .isEqualTo(BASE.get(flagsDataGIndex).hashTreeRoot());

    final TreeDiff diff = diff(BASE, target);

    assertThat(diff.leaves())
        .extracting(leaf -> leaf.data().size())
        .containsExactlyInAnyOrder(Long.BYTES, VALIDATOR_COUNT % 32 + 1);
  }

  @Test
  void zeroFilledListKeepsLeafData() {
    // A list reset to zeros hashes like the zero tree but its chunks hold 32 bytes of data each
    final TreeNode target = SyntheticState.zeroFlags(BASE, VALIDATOR_COUNT);

    final TreeDiff diff = diff(BASE, target);

    assertThat(diff.zeroed()).isEmpty();
    assertThat(diff.leaves())
        .isNotEmpty()
        .allSatisfy(leaf -> assertThat(leaf.data().size()).isPositive());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("targets")
  void applyReproducesTargetRoot(final String name, final UnaryOperator<TreeNode> mutation) {
    final TreeNode target = mutation.apply(BASE);
    final TreeDiff diff = TreeDiffCodec.deserialize(TreeDiffCodec.serialize(diff(BASE, target)));

    final TreeNode applied = applier.apply(BASE, diff, source);

    assertThat(applied.hashTreeRoot()).isEqualTo(target.hashTreeRoot());
    assertThat(SyntheticState.serialize(applied)).isEqualTo(SyntheticState.serialize(target));
  }

  static Stream<Arguments> targets() {
    return Stream.of(
        Arguments.of("identical", (UnaryOperator<TreeNode>) tree -> tree),
        Arguments.of("fixedField", (UnaryOperator<TreeNode>) tree -> SyntheticState.setA(tree, 42)),
        Arguments.of(
            "balances", (UnaryOperator<TreeNode>) tree -> SyntheticState.setBalance(tree, 7, 123)),
        Arguments.of(
            "validator",
            (UnaryOperator<TreeNode>)
                tree ->
                    SyntheticState.setValidatorField(
                        tree,
                        CHANGED_VALIDATOR,
                        VALIDATOR_EFFECTIVE_BALANCE,
                        SszUInt64.of(UInt64.valueOf(31)))),
        Arguments.of(
            "appendedValidators",
            (UnaryOperator<TreeNode>)
                tree -> SyntheticState.appendValidators(tree, new Random(2), 300)),
        Arguments.of("rotated", (UnaryOperator<TreeNode>) SyntheticState::rotateFlags),
        Arguments.of("cleared", (UnaryOperator<TreeNode>) SyntheticState::clearFlags),
        Arguments.of("appendedZero", (UnaryOperator<TreeNode>) SyntheticState::appendZeroFlag),
        Arguments.of(
            "zeroFilled",
            (UnaryOperator<TreeNode>) tree -> SyntheticState.zeroFlags(tree, VALIDATOR_COUNT)));
  }

  @Test
  void applyThrowsMissingSupernode() {
    final TreeDiff diff = diff(SCHEMA.getDefaultTree(), BASE);

    assertThatThrownBy(() -> applier.apply(SCHEMA.getDefaultTree(), diff, hash -> Optional.empty()))
        .isInstanceOfSatisfying(
            MissingSupernodeException.class,
            e -> assertThat(e.getHash()).isEqualTo(diff.supernodes().getFirst().hash()));
  }

  @Test
  void applyLoadsTheSupernodesOfADiffInOneBatch() {
    final TreeNode defaultTree = SCHEMA.getDefaultTree();
    final TreeDiff diff = diff(defaultTree, BASE);
    assertThat(diff.supernodes()).hasSizeGreaterThan(1);
    final List<List<Bytes32>> batches = new ArrayList<>();
    final SupernodeSource batchOnlySource =
        new SupernodeSource() {
          @Override
          public Optional<Bytes> load(final Bytes32 hash) {
            throw new AssertionError("Supernode " + hash + " loaded on its own");
          }

          @Override
          public Map<Bytes32, Bytes> loadAll(final Collection<Bytes32> hashes) {
            batches.add(List.copyOf(hashes));
            return source.loadAll(hashes);
          }
        };

    final TreeNode applied = applier.apply(defaultTree, diff, batchOnlySource);

    assertThat(applied.hashTreeRoot()).isEqualTo(BASE.hashTreeRoot());
    assertThat(batches)
        .containsExactly(diff.supernodes().stream().map(SupernodeEntry::hash).distinct().toList());
  }

  @Test
  void appliedSupernodeTakesItsRootFromTheEntryInsteadOfHashingItsContent() {
    final TreeNode target =
        SyntheticState.appendValidators(BASE, new Random(2), 1 << SUPERNODE_DEPTH);
    final TreeDiff diff = diff(BASE, target);
    final SupernodeEntry entry = diff.supernodes().getFirst();
    final Bytes stored = supernodeStore.get(entry.hash());
    final Bytes tampered =
        Bytes.concatenate(
            stored.slice(0, stored.size() - 1), Bytes.of((byte) ~stored.get(stored.size() - 1)));

    final TreeNode applied = applier.apply(BASE, diff, hash -> Optional.of(tampered));

    // The content is trusted to match its store key, so tampering goes unnoticed by the root
    assertThat(applied.get(entry.gIndex())).isInstanceOf(SszSuperNode.class);
    assertThat(((SszSuperNode) applied.get(entry.gIndex())).getData()).isEqualTo(tampered);
    assertThat(applied.get(entry.gIndex()).hashTreeRoot()).isEqualTo(entry.hash());
    assertThat(applied.hashTreeRoot()).isEqualTo(target.hashTreeRoot());
  }

  @Test
  void snapshotFromDefaultTreeReproducesRoot() {
    final TreeNode defaultTree = SCHEMA.getDefaultTree();
    final TreeDiff diff =
        TreeDiffCodec.deserialize(TreeDiffCodec.serialize(diff(defaultTree, BASE)));

    assertThat(diff.supernodes()).isNotEmpty();
    // A snapshot has no base supernodes to patch
    assertThat(diff.patches()).isEmpty();
    final TreeNode applied = applier.apply(defaultTree, diff, source);

    assertThat(applied.hashTreeRoot()).isEqualTo(BASE.hashTreeRoot());
    assertThat(SyntheticState.serialize(applied)).isEqualTo(SyntheticState.serialize(BASE));
  }

  @Test
  void compositeElementsOverZeroLeavesOfEmptyListApply() {
    // Without supernode hints the elements of a composite list are branches, while the empty
    // list's data tree has a zero leaf at each element position
    final SszListSchema<SszContainerImpl, ?> listSchema =
        SszListSchema.create(VALIDATOR_SCHEMA, 1024);
    final SszContainerSchema<SszContainerImpl> schema =
        SszContainerSchema.create(
            List.of(SszPrimitiveSchemas.UINT64_SCHEMA, listSchema), SszContainerImpl::new);
    final RandomSszDataGenerator generator = new RandomSszDataGenerator(new Random(3), 0);
    final TreeNode base = schema.getDefaultTree();
    final TreeNode target =
        base.updated(
            schema.getChildGeneralizedIndex(1),
            listSchema
                .createFromElements(
                    List.of(
                        generator.randomData(VALIDATOR_SCHEMA),
                        generator.randomData(VALIDATOR_SCHEMA),
                        generator.randomData(VALIDATOR_SCHEMA)))
                .getBackingNode());
    final TreeDiff diff =
        TreeDiffCodec.deserialize(
            TreeDiffCodec.serialize(
                new TreeDiffer(schema, IntSet.of()).diff(base, target, supernodeStore::put)));

    final TreeNode applied = new TreeDiffApplier(schema).apply(base, diff, source);

    assertThat(applied.hashTreeRoot()).isEqualTo(target.hashTreeRoot());
    assertThat(schema.createFromBackingNode(applied).sszSerialize())
        .isEqualTo(schema.createFromBackingNode(target).sszSerialize());
  }

  @Test
  @Timeout(value = 10, threadMode = ThreadMode.SEPARATE_THREAD)
  void deepZeroTreeWithSharedChildrenIsCheckedInLinearTime() {
    // A non-canonical zero tree whose branches hold one child instance on both sides, as list
    // padding built by sharing does: checking each side separately would visit 2^40 nodes
    final int depth = 40;
    final SszListSchema<SszContainerImpl, ?> listSchema =
        SszListSchema.create(VALIDATOR_SCHEMA, 1L << depth);
    final SszContainerSchema<SszContainerImpl> schema =
        SszContainerSchema.create(
            List.of(SszPrimitiveSchemas.UINT64_SCHEMA, listSchema), SszContainerImpl::new);
    TreeNode sharedZeroTree = LeafNode.EMPTY_LEAF;
    for (int i = 0; i < depth; i++) {
      sharedZeroTree = BranchNode.create(sharedZeroTree, sharedZeroTree);
    }
    final TreeNode base = schema.getDefaultTree();
    final TreeNode target =
        base.updated(GIndexUtil.gIdxLeftGIndex(schema.getChildGeneralizedIndex(1)), sharedZeroTree);

    final TreeDiff diff = new TreeDiffer(schema, IntSet.of()).diff(base, target, (h, s) -> {});

    assertThat(diff.isEmpty()).isTrue();
  }

  private static TreeNode changeValidator(final TreeNode tree, final int index) {
    return SyntheticState.setValidatorField(
        tree, index, VALIDATOR_EFFECTIVE_BALANCE, SszUInt64.of(UInt64.valueOf(31 + index)));
  }

  private static TreeDiff roundTrip(final TreeDiff diff) {
    return TreeDiffCodec.deserialize(TreeDiffCodec.serialize(diff));
  }

  private TreeDiff diff(final TreeNode base, final TreeNode target) {
    return differ.diff(base, target, supernodeStore::put);
  }
}
