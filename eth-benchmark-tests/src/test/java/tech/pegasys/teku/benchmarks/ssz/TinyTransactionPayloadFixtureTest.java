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

package tech.pegasys.teku.benchmarks.ssz;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.security.MessageDigest;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tech.pegasys.teku.infrastructure.crypto.BatchSha256;
import tech.pegasys.teku.infrastructure.crypto.MessageDigestFactory;
import tech.pegasys.teku.infrastructure.ssz.schema.ListSchemaUtil;
import tech.pegasys.teku.infrastructure.ssz.tree.SszPackedByteListsNode;
import tech.pegasys.teku.infrastructure.ssz.tree.SszPackedProgressiveByteListsNode;
import tech.pegasys.teku.spec.datastructures.blocks.BeaconBlock;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayload;

class TinyTransactionPayloadFixtureTest {

  private static final TinyTransactionBatchMerkleizer MERKLEIZER =
      new TinyTransactionBatchMerkleizer(new JavaBatchSha256(), 16);

  @ParameterizedTest
  @ValueSource(ints = {1, 21, 341})
  void createsRealFuluBlockWithMatchingNativeRoots(final int count) {
    final TinyTransactionPayloadFixture.FuluBlockFixture fixture =
        TinyTransactionPayloadFixture.createFulu(count, MERKLEIZER);
    final BeaconBlock block = fixture.freshBlock();
    final ExecutionPayload payload = block.getBody().getOptionalExecutionPayload().orElseThrow();

    assertThat(payload.getTransactions()).hasSize(count);
    assertCyclingValues(payload, count);
    assertThat(ListSchemaUtil.getVectorNode(payload.getTransactions().getBackingNode()))
        .isInstanceOf(SszPackedByteListsNode.class);
    assertThat(block.sszSerialize()).isEqualTo(fixture.serializedBlock());
    assertThat(fixture.hashNativeTransactions(block))
        .isEqualTo(fixture.hashJavaTransactions(block));
    assertThat(fixture.hashNativeBlock(block)).isEqualTo(block.hashTreeRoot());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 21, 341})
  void createsRealGloasPayloadWithMatchingNativeRoots(final int count) {
    final TinyTransactionPayloadFixture.GloasPayloadFixture fixture =
        TinyTransactionPayloadFixture.createGloas(count, MERKLEIZER);
    final ExecutionPayload payload = fixture.freshPayload();

    assertThat(payload.getTransactions()).hasSize(count);
    assertCyclingValues(payload, count);
    assertThat(ListSchemaUtil.getVectorNode(payload.getTransactions().getBackingNode()))
        .isInstanceOf(SszPackedProgressiveByteListsNode.class);
    assertThat(payload.sszSerialize()).isEqualTo(fixture.serializedPayload());
    assertThat(fixture.hashNativeTransactions(payload))
        .isEqualTo(fixture.hashJavaTransactions(payload));
    assertThat(fixture.hashNativePayload(payload)).isEqualTo(payload.hashTreeRoot());
  }

  private static void assertCyclingValues(
      final ExecutionPayload payload, final int transactionCount) {
    for (int i : new int[] {0, Math.min(255, transactionCount - 1), transactionCount - 1}) {
      assertThat(payload.getTransactions().get(i).getBytes()).isEqualTo(Bytes.of((byte) i));
    }
  }

  private static class JavaBatchSha256 implements BatchSha256 {
    private final MessageDigest digest = MessageDigestFactory.createSha256();

    @Override
    public void hash64(final MemorySegment input, final MemorySegment output, final long count) {
      for (long i = 0; i < count; i++) {
        final byte[] block = input.asSlice(i * 64, 64).toArray(ValueLayout.JAVA_BYTE);
        final byte[] hash = digest.digest(block);
        output.asSlice(i * 32, 32).copyFrom(MemorySegment.ofArray(hash));
      }
    }
  }
}
