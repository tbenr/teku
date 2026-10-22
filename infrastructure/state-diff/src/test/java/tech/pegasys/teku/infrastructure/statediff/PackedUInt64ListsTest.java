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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tech.pegasys.teku.infrastructure.ssz.collections.SszMutableUInt64List;
import tech.pegasys.teku.infrastructure.ssz.collections.SszUInt64List;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszUInt64;
import tech.pegasys.teku.infrastructure.ssz.schema.SszProgressiveUInt64ListSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.collections.SszUInt64ListSchema;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

/**
 * The chunk-level read and write of {@link PackedUInt64Lists} against the element API they replace,
 * kept here as the reference, on bounded and progressive (Gloas) uint64 lists.
 */
class PackedUInt64ListsTest {

  private static final SszUInt64ListSchema<?> BOUNDED = SszUInt64ListSchema.create(1L << 40);
  private static final SszUInt64ListSchema<?> PROGRESSIVE = SszProgressiveUInt64ListSchema.create();
  private static final int[] SIZES = {0, 1, 3, 4, 5, 1000, 100_003};

  static Stream<Arguments> schemasAndSizes() {
    return Stream.of(BOUNDED, PROGRESSIVE)
        .flatMap(
            schema ->
                IntStream.of(SIZES)
                    .mapToObj(
                        size ->
                            Arguments.of(
                                schema instanceof SszProgressiveUInt64ListSchema
                                    ? "progressive"
                                    : "bounded",
                                schema,
                                size)));
  }

  @ParameterizedTest(name = "{0} {2}")
  @MethodSource("schemasAndSizes")
  void readMatchesElementReadOnEveryTreeShape(
      final String name, final SszUInt64ListSchema<?> schema, final int size) {
    final long[] values = randomValues(size);
    final TreeNode fromElements = writeReference(schema, values);
    final TreeNode deserialized =
        schema
            .sszDeserialize(schema.createFromBackingNode(fromElements).sszSerialize())
            .getBackingNode();
    final TreeNode appended = appendAll(schema, values);
    final TreeNode written = PackedUInt64Lists.write(schema, values);

    for (final TreeNode listNode : List.of(fromElements, deserialized, appended, written)) {
      assertThat(readReference(schema, listNode)).isEqualTo(values);
      assertThat(PackedUInt64Lists.read(schema, listNode)).isEqualTo(values);
    }
  }

  @ParameterizedTest(name = "{0} {2}")
  @MethodSource("schemasAndSizes")
  void readMatchesElementReadAfterUpdates(
      final String name, final SszUInt64ListSchema<?> schema, final int size) {
    final long[] values = randomValues(size);
    final SszMutableUInt64List mutable =
        schema.createFromBackingNode(writeReference(schema, values)).createWritableCopy();
    final Random random = new Random(size);
    for (int i = 0; i < Math.min(size, 50); i++) {
      final int index = random.nextInt(size);
      values[index] = random.nextLong();
      mutable.setElement(index, UInt64.fromLongBits(values[index]));
    }
    final TreeNode updated = mutable.commitChanges().getBackingNode();

    assertThat(PackedUInt64Lists.read(schema, updated)).isEqualTo(readReference(schema, updated));
    assertThat(PackedUInt64Lists.read(schema, updated)).isEqualTo(values);
  }

  @ParameterizedTest(name = "{0} {2}")
  @MethodSource("schemasAndSizes")
  void writeMatchesElementWrite(
      final String name, final SszUInt64ListSchema<?> schema, final int size) {
    final long[] values = randomValues(size);
    final TreeNode reference = writeReference(schema, values);

    final TreeNode written = PackedUInt64Lists.write(schema, values);

    assertThat(written.hashTreeRoot()).isEqualTo(reference.hashTreeRoot());
    final Bytes writtenSsz = schema.createFromBackingNode(written).sszSerialize();
    assertThat(writtenSsz).isEqualTo(schema.createFromBackingNode(reference).sszSerialize());
    // The built tree behaves like any other list tree: it can be updated and appended to
    final SszMutableUInt64List mutable = schema.createFromBackingNode(written).createWritableCopy();
    final SszMutableUInt64List referenceMutable =
        schema.createFromBackingNode(reference).createWritableCopy();
    if (size > 0) {
      mutable.setElement(size - 1, UInt64.valueOf(7));
      referenceMutable.setElement(size - 1, UInt64.valueOf(7));
    }
    mutable.appendElement(UInt64.valueOf(9));
    referenceMutable.appendElement(UInt64.valueOf(9));
    assertThat(mutable.commitChanges().sszSerialize())
        .isEqualTo(referenceMutable.commitChanges().sszSerialize());
  }

  @Test
  void writeRejectsTooManyElements() {
    for (final SszUInt64ListSchema<?> schema :
        List.of(SszUInt64ListSchema.create(5), SszProgressiveUInt64ListSchema.create(5))) {
      assertThat(PackedUInt64Lists.write(schema, randomValues(5)).hashTreeRoot())
          .isEqualTo(writeReference(schema, randomValues(5)).hashTreeRoot());
      assertThatThrownBy(() -> PackedUInt64Lists.write(schema, randomValues(6)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static long[] randomValues(final int size) {
    final Random random = new Random(size);
    final long[] values = new long[size];
    for (int i = 0; i < size; i++) {
      // Include zeros and values with the top bit set
      values[i] = random.nextInt(4) == 0 ? 0 : random.nextLong();
    }
    return values;
  }

  private static TreeNode appendAll(final SszUInt64ListSchema<?> schema, final long[] values) {
    final SszMutableUInt64List mutable = schema.getDefault().createWritableCopy();
    for (final long value : values) {
      mutable.appendElement(UInt64.fromLongBits(value));
    }
    return mutable.commitChanges().getBackingNode();
  }

  private static long[] readReference(final SszUInt64ListSchema<?> schema, final TreeNode node) {
    final SszUInt64List list = schema.createFromBackingNode(node);
    final long[] values = new long[list.size()];
    for (int i = 0; i < values.length; i++) {
      values[i] = list.getElement(i).longValue();
    }
    return values;
  }

  private static TreeNode writeReference(final SszUInt64ListSchema<?> schema, final long[] values) {
    final List<SszUInt64> elements = new ArrayList<>(values.length);
    for (final long value : values) {
      elements.add(SszUInt64.of(UInt64.fromLongBits(value)));
    }
    return schema.createTreeFromElements(elements);
  }
}
