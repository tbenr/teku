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

package tech.pegasys.teku.infrastructure.ssz.tree;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.crypto.Hash;
import tech.pegasys.teku.infrastructure.ssz.SszContainer;
import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.infrastructure.ssz.impl.SszContainerImpl;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszBytes32;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszUInt64;
import tech.pegasys.teku.infrastructure.ssz.schema.SszContainerSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.SszListSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.SszPrimitiveSchemas;
import tech.pegasys.teku.infrastructure.ssz.schema.SszProgressiveListSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.SszSchemaHints;
import tech.pegasys.teku.infrastructure.ssz.schema.impl.AbstractSszContainerSchema.NamedSchema;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

class FrozenSuperNodeTest {

  private static final int SUPERNODE_DEPTH = 2;

  private static final SszContainerSchema<SszContainer> ELEMENT_SCHEMA =
      SszContainerSchema.create(
          "FixedElem",
          List.of(
              NamedSchema.of("a", SszPrimitiveSchemas.UINT64_SCHEMA),
              NamedSchema.of("b", SszPrimitiveSchemas.BYTES32_SCHEMA)),
          SszContainerImpl::new);

  private static final SszListSchema<SszContainer, ?> BOUNDED_SCHEMA =
      SszListSchema.create(ELEMENT_SCHEMA, 1L << 10, SszSchemaHints.sszSuperNode(SUPERNODE_DEPTH));
  private static final SszListSchema<SszContainer, ?> PLAIN_SCHEMA =
      SszListSchema.create(ELEMENT_SCHEMA, 1L << 10);
  private static final SszProgressiveListSchema<SszContainer> PROGRESSIVE_SCHEMA =
      SszProgressiveListSchema.create(ELEMENT_SCHEMA, SszSchemaHints.sszSuperNode(SUPERNODE_DEPTH));

  private final AtomicInteger loaderCalls = new AtomicInteger();

  private static SszContainer element(final int i) {
    return ELEMENT_SCHEMA.createFromBackingNode(
        ELEMENT_SCHEMA.createTreeFromFieldValues(
            List.of(
                SszUInt64.of(UInt64.valueOf(i)),
                SszBytes32.of(Hash.sha256(Bytes.ofUnsignedInt(i))))));
  }

  private static List<SszContainer> elements(final int count) {
    final List<SszContainer> result = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      result.add(element(i));
    }
    return result;
  }

  /** A list packed into supernodes, built through SSZ deserialization as the state is. */
  private static SszList<SszContainer> packed(
      final SszListSchema<SszContainer, ?> schema, final int size) {
    return schema.sszDeserialize(PLAIN_SCHEMA.createFromElements(elements(size)).sszSerialize());
  }

  private FrozenSuperNode freeze(final SszSuperNode superNode) {
    final Bytes ssz = superNode.getData();
    return new FrozenSuperNode(
        superNode.getDepth(),
        BOUNDED_SCHEMA.getSupernodeElementTemplate(),
        ssz.size() / ELEMENT_SCHEMA.getSszFixedPartSize(),
        superNode.hashTreeRoot(),
        () -> {
          loaderCalls.incrementAndGet();
          return ssz;
        });
  }

  /** The list with the supernodes holding element {@code firstElements} frozen. */
  private SszList<SszContainer> withFrozen(
      final SszListSchema<SszContainer, ?> schema,
      final SszList<SszContainer> list,
      final Set<Integer> firstElements) {
    final TreeNode tree =
        FrozenRanges.replaceSupernodes(
            schema,
            list.getBackingNode(),
            list.size(),
            superNode -> {
              final int first = firstElementOf(list, superNode);
              return firstElements.contains(first)
                  ? Optional.of(freeze(superNode))
                  : Optional.empty();
            });
    return schema.createFromBackingNode(tree);
  }

  private static int firstElementOf(final SszList<SszContainer> list, final SszSuperNode node) {
    final Bytes first = node.getData().slice(0, ELEMENT_SCHEMA.getSszFixedPartSize());
    for (int i = 0; i < list.size(); i++) {
      if (list.get(i).sszSerialize().equals(first)) {
        return i;
      }
    }
    throw new IllegalStateException("Supernode element not in list");
  }

  private SszSuperNode supernodeAt(final SszList<SszContainer> list, final int element) {
    return (SszSuperNode)
        list.getBackingNode()
            .get(BOUNDED_SCHEMA.getChildGeneralizedIndex(element) >>> SUPERNODE_DEPTH);
  }

  @Test
  void hashTreeRootIsTheSupernodeRootWithoutLoading() {
    final SszSuperNode superNode = supernodeAt(packed(BOUNDED_SCHEMA, 14), 4);
    final FrozenSuperNode frozen = freeze(superNode);

    assertThat(frozen.hashTreeRoot()).isEqualTo(superNode.hashTreeRoot());
    assertThat(frozen.hashTreeRoot(Hash.getSha256Instance())).isEqualTo(superNode.hashTreeRoot());
    assertThat(frozen.getSszSize()).isEqualTo(superNode.getData().size());
    assertThat(loaderCalls).hasValue(0);
  }

  @Test
  void getReturnsTheSupernodeDescendantsAndMemoizesTheContent() {
    final SszSuperNode superNode = supernodeAt(packed(BOUNDED_SCHEMA, 14), 4);
    final FrozenSuperNode frozen = freeze(superNode);

    assertThat(frozen.get(GIndexUtil.SELF_G_INDEX)).isSameAs(frozen);
    for (int i = 0; i < 4; i++) {
      final long childGIndex = GIndexUtil.gIdxChildGIndex(GIndexUtil.SELF_G_INDEX, i, 2);
      assertThat(frozen.get(childGIndex).hashTreeRoot())
          .isEqualTo(superNode.get(childGIndex).hashTreeRoot());
    }
    assertThat(frozen.getData()).isEqualTo(superNode.getData());
    assertThat(loaderCalls).hasValue(1);

    frozen.dropMemo();
    frozen.getData();
    assertThat(loaderCalls).hasValue(2);
  }

  @Test
  void updatedThawsIntoAPlainSupernode() {
    final SszSuperNode superNode = supernodeAt(packed(BOUNDED_SCHEMA, 14), 4);
    final FrozenSuperNode frozen = freeze(superNode);
    final long thawsBefore = FrozenSuperNode.thaws();
    final long childGIndex = GIndexUtil.gIdxChildGIndex(GIndexUtil.SELF_G_INDEX, 1, 2);
    final TreeNode newElement = element(1000).getBackingNode();

    final TreeNode updated = frozen.updated(childGIndex, newElement);

    assertThat(updated).isInstanceOf(SszSuperNode.class);
    assertThat(updated.hashTreeRoot())
        .isEqualTo(superNode.updated(childGIndex, newElement).hashTreeRoot());
    assertThat(FrozenSuperNode.thaws()).isGreaterThan(thawsBefore);
    assertThat(frozen.updated(new TreeUpdates(List.of()))).isSameAs(frozen);
  }

  @Test
  void iterateVisitsTheNodeOnce() {
    final FrozenSuperNode frozen = freeze(supernodeAt(packed(BOUNDED_SCHEMA, 14), 4));
    final List<TreeNode> visited = new ArrayList<>();

    frozen.iterateAll((final TreeNode node) -> visited.add(node));

    assertThat(visited).containsExactly(frozen);
    assertThat(loaderCalls).hasValue(0);
  }

  @Test
  void frozenListKeepsItsRootAndElements() {
    final SszList<SszContainer> list = packed(BOUNDED_SCHEMA, 14);
    final SszList<SszContainer> frozen = withFrozen(BOUNDED_SCHEMA, list, Set.of(0, 8));

    assertThat(frozen.hashTreeRoot()).isEqualTo(list.hashTreeRoot());
    assertThat(loaderCalls).hasValue(0);
    for (int i = 0; i < list.size(); i++) {
      assertThat(frozen.get(i)).isEqualTo(list.get(i));
    }
    assertThat(frozen.sszSerialize()).isEqualTo(list.sszSerialize());
  }

  @Test
  void frozenRangesOfABoundedList() {
    final SszList<SszContainer> list = packed(BOUNDED_SCHEMA, 14);
    final SszList<SszContainer> frozen = withFrozen(BOUNDED_SCHEMA, list, Set.of(0, 8, 12));

    final FrozenRanges ranges =
        FrozenRanges.collect(BOUNDED_SCHEMA, frozen.getBackingNode(), frozen.size());

    assertThat(ranges.size()).isEqualTo(2);
    assertThat(List.of(ranges.start(0), ranges.end(0))).containsExactly(0, 4);
    // The last supernode holds 2 elements and is merged with the adjacent range
    assertThat(List.of(ranges.start(1), ranges.end(1))).containsExactly(8, 14);
    assertThat(ranges.coveredElements()).isEqualTo(10);
    assertThat(ranges.contains(3)).isTrue();
    assertThat(ranges.contains(4)).isFalse();
    assertThat(ranges.contains(13)).isTrue();
    final FrozenRanges.Cursor cursor = ranges.cursor();
    assertThat(cursor.isFrozen(0)).isTrue();
    assertThat(cursor.frozenUntil(2)).isEqualTo(4);
    assertThat(cursor.isFrozen(7)).isFalse();
    assertThat(cursor.frozenUntil(9)).isEqualTo(14);
    assertThat(loaderCalls).hasValue(0);
  }

  @Test
  void frozenRangesOfAListWithoutFrozenNodesAreEmpty() {
    final SszList<SszContainer> list = packed(BOUNDED_SCHEMA, 14);

    assertThat(FrozenRanges.collect(BOUNDED_SCHEMA, list.getBackingNode(), list.size()).isEmpty())
        .isTrue();
  }

  @Test
  void frozenRangesOfAProgressiveList() {
    // Levels hold 1, 4, 16, 64 elements: element 0 is a plain element node, 1-4 one supernode of
    // depth 2, 5-20 four supernodes of depth 2, 21-84 sixteen
    final SszList<SszContainer> list =
        PROGRESSIVE_SCHEMA.sszDeserialize(
            PLAIN_SCHEMA.createFromElements(elements(30)).sszSerialize());
    final TreeNode tree =
        FrozenRanges.replaceSupernodes(
            PROGRESSIVE_SCHEMA,
            list.getBackingNode(),
            list.size(),
            superNode -> {
              final Bytes first =
                  superNode.getData().slice(0, ELEMENT_SCHEMA.getSszFixedPartSize());
              final boolean freeze =
                  first.equals(element(1).sszSerialize())
                      || first.equals(element(9).sszSerialize())
                      || first.equals(element(25).sszSerialize());
              return freeze ? Optional.of(freeze(superNode)) : Optional.empty();
            });
    final SszList<SszContainer> frozen = PROGRESSIVE_SCHEMA.createFromBackingNode(tree);

    final FrozenRanges ranges = FrozenRanges.collect(PROGRESSIVE_SCHEMA, tree, frozen.size());

    assertThat(frozen.hashTreeRoot()).isEqualTo(list.hashTreeRoot());
    assertThat(ranges.size()).isEqualTo(3);
    assertThat(List.of(ranges.start(0), ranges.end(0))).containsExactly(1, 5);
    assertThat(List.of(ranges.start(1), ranges.end(1))).containsExactly(9, 13);
    assertThat(List.of(ranges.start(2), ranges.end(2))).containsExactly(25, 29);
    assertThat(loaderCalls).hasValue(0);
    for (int i = 0; i < list.size(); i++) {
      assertThat(frozen.get(i)).isEqualTo(list.get(i));
    }
  }
}
