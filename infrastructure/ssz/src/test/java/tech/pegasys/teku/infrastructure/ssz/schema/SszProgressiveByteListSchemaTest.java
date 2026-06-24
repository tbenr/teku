/*
 * Copyright Consensys Software Inc., 2026
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

package tech.pegasys.teku.infrastructure.ssz.schema;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.ssz.collections.SszByteList;
import tech.pegasys.teku.infrastructure.ssz.collections.SszMutablePrimitiveList;
import tech.pegasys.teku.infrastructure.ssz.collections.impl.SszProgressiveByteListImpl;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszByte;

class SszProgressiveByteListSchemaTest {

  private static final SszProgressiveByteListSchema<SszByteList> SCHEMA =
      new SszProgressiveByteListSchema<>();

  @Test
  void fromBytes_shouldPreserveBytes() {
    final Bytes bytes = Bytes.fromHexString("0x010203");
    final SszByteList byteList = SCHEMA.fromBytes(bytes);

    assertThat(byteList).isInstanceOf(SszProgressiveByteListImpl.class);
    assertThat(byteList.size()).isEqualTo(3);
    assertThat(byteList.getBytes()).isEqualTo(bytes);
  }

  @Test
  void sszRoundTrip_shouldPreserveBytesAndRoot() {
    final Bytes bytes = Bytes.fromHexString("0x010203");
    final SszByteList byteList = SCHEMA.fromBytes(bytes);

    final Bytes ssz = byteList.sszSerialize();
    final SszByteList deserialized = SCHEMA.sszDeserialize(ssz);

    assertThat(deserialized.getBytes()).isEqualTo(bytes);
    assertThat(deserialized.hashTreeRoot()).isEqualTo(byteList.hashTreeRoot());
  }

  @Test
  void emptyList_roundTrips() {
    final SszByteList empty = SCHEMA.fromBytes(Bytes.EMPTY);
    assertThat(empty.size()).isZero();
    assertThat(empty.getBytes()).isEqualTo(Bytes.EMPTY);
    assertThat(SCHEMA.sszDeserialize(empty.sszSerialize()).size()).isZero();
  }

  @Test
  void getSszLengthBounds_shouldBeUnbounded() {
    assertThat(SCHEMA.getSszLengthBounds().isUnbounded()).isTrue();
  }

  @Test
  void mutationIsSupported() {
    final SszByteList byteList = SCHEMA.fromBytes(Bytes.fromHexString("0x010203"));
    assertThat(byteList.isWritableSupported()).isTrue();
  }

  @Test
  void writableCopy_canSetElement() {
    final SszByteList byteList = SCHEMA.fromBytes(Bytes.fromHexString("0x010203"));
    final SszMutablePrimitiveList<Byte, SszByte> mutable = byteList.createWritableCopy();
    mutable.set(1, SszByte.of((byte) 0xFF));
    final SszByteList updated = (SszByteList) mutable.commitChanges();

    assertThat(updated.getBytes()).isEqualTo(Bytes.fromHexString("0x01FF03"));
    // mutated list still round-trips
    assertThat(SCHEMA.sszDeserialize(updated.sszSerialize()).getBytes())
        .isEqualTo(Bytes.fromHexString("0x01FF03"));
  }

  @Test
  void writableCopy_canAppendElement() {
    final SszByteList byteList = SCHEMA.fromBytes(Bytes.fromHexString("0x0102"));
    final SszMutablePrimitiveList<Byte, SszByte> mutable = byteList.createWritableCopy();
    mutable.appendElement((byte) 0x03);
    final SszByteList updated = (SszByteList) mutable.commitChanges();

    assertThat(updated.getBytes()).isEqualTo(Bytes.fromHexString("0x010203"));
    assertThat(updated.size()).isEqualTo(3);
  }
}
