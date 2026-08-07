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
import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.infrastructure.ssz.schema.ListSchemaUtil;
import tech.pegasys.teku.infrastructure.ssz.schema.SszListSchema;
import tech.pegasys.teku.infrastructure.ssz.tree.SszPackedByteListsNode;
import tech.pegasys.teku.infrastructure.ssz.tree.SszPackedProgressiveByteListsNode;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayloadSchema;
import tech.pegasys.teku.spec.datastructures.execution.Transaction;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsBellatrix;

class TinyTransactionBatchMerkleizerTest {

  private static final int TILE_SIZE = 16;
  private static final BatchSha256 JAVA_BATCH_HASHER = new JavaBatchSha256();

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 4, 5, 16, 21, 341})
  void fixedRootMatchesMainnetFulu(final int count) {
    final ExecutionPayloadSchema<?> payloadSchema =
        payloadSchema(TestSpecFactory.createMainnetFulu());
    final SszListSchema<Transaction, ?> transactionsSchema =
        payloadSchema.toVersionDenebRequired().getTransactionsSchema();
    final SszList<Transaction> transactions =
        transactionsSchema.sszDeserialize(oneByteTransactionsSsz(count));
    final SszPackedByteListsNode packedNode =
        (SszPackedByteListsNode) ListSchemaUtil.getVectorNode(transactions.getBackingNode());

    final TinyTransactionBatchMerkleizer merkleizer =
        new TinyTransactionBatchMerkleizer(JAVA_BATCH_HASHER, TILE_SIZE);

    assertThat(
            merkleizer.hashFixed(
                packedNode,
                payloadSchema.getTransactionSchema().treeDepth(),
                transactionsSchema.treeDepth()))
        .isEqualTo(transactions.hashTreeRoot());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 4, 5, 16, 21, 256, 341, 4096, 5461})
  void progressiveRootMatchesMainnetGloas(final int count) {
    final ExecutionPayloadSchema<?> payloadSchema =
        payloadSchema(TestSpecFactory.createMainnetGloas());
    final SszListSchema<Transaction, ?> transactionsSchema =
        payloadSchema.toVersionGloasRequired().getTransactionsSchema();
    final SszList<Transaction> transactions =
        transactionsSchema.sszDeserialize(oneByteTransactionsSsz(count));
    final SszPackedProgressiveByteListsNode packedNode =
        (SszPackedProgressiveByteListsNode)
            ListSchemaUtil.getVectorNode(transactions.getBackingNode());

    final TinyTransactionBatchMerkleizer merkleizer =
        new TinyTransactionBatchMerkleizer(JAVA_BATCH_HASHER, TILE_SIZE);

    assertThat(merkleizer.hashProgressive(packedNode)).isEqualTo(transactions.hashTreeRoot());
  }

  private static ExecutionPayloadSchema<?> payloadSchema(final Spec spec) {
    return SchemaDefinitionsBellatrix.required(spec.atSlot(UInt64.ZERO).getSchemaDefinitions())
        .getExecutionPayloadSchema();
  }

  static Bytes oneByteTransactionsSsz(final int count) {
    final int dataOffset = Math.multiplyExact(count, Integer.BYTES);
    final byte[] serialized = new byte[Math.addExact(dataOffset, count)];
    for (int i = 0; i < count; i++) {
      final int offset = dataOffset + i;
      serialized[i * 4] = (byte) offset;
      serialized[i * 4 + 1] = (byte) (offset >>> 8);
      serialized[i * 4 + 2] = (byte) (offset >>> 16);
      serialized[i * 4 + 3] = (byte) (offset >>> 24);
      serialized[dataOffset + i] = (byte) i;
    }
    return Bytes.wrap(serialized);
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
