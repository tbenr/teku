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

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.infrastructure.ssz.schema.ListSchemaUtil;
import tech.pegasys.teku.infrastructure.ssz.schema.SszContainerSchema;
import tech.pegasys.teku.infrastructure.ssz.schema.SszFieldName;
import tech.pegasys.teku.infrastructure.ssz.schema.SszListSchema;
import tech.pegasys.teku.infrastructure.ssz.tree.LeafNode;
import tech.pegasys.teku.infrastructure.ssz.tree.SszPackedByteListsNode;
import tech.pegasys.teku.infrastructure.ssz.tree.SszPackedProgressiveByteListsNode;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.blocks.BeaconBlock;
import tech.pegasys.teku.spec.datastructures.blocks.BeaconBlockFields;
import tech.pegasys.teku.spec.datastructures.blocks.BeaconBlockSchema;
import tech.pegasys.teku.spec.datastructures.blocks.blockbody.BeaconBlockBody;
import tech.pegasys.teku.spec.datastructures.blocks.blockbody.BeaconBlockBodySchema;
import tech.pegasys.teku.spec.datastructures.blocks.blockbody.common.BlockBodyFields;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayload;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayloadFields;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayloadSchema;
import tech.pegasys.teku.spec.datastructures.execution.Transaction;
import tech.pegasys.teku.spec.schemas.SchemaDefinitions;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsBellatrix;

/** Real fork fixtures for the pathological one-byte transaction benchmark. */
public final class TinyTransactionPayloadFixture {

  private TinyTransactionPayloadFixture() {}

  public static FuluBlockFixture createFulu(
      final int transactionCount, final TinyTransactionBatchMerkleizer merkleizer) {
    final SchemaDefinitions definitions = definitions(TestSpecFactory.createMainnetFulu());
    final ExecutionPayloadSchema<?> payloadSchema =
        SchemaDefinitionsBellatrix.required(definitions).getExecutionPayloadSchema();
    final SszListSchema<Transaction, ?> transactionsSchema =
        payloadSchema.toVersionDenebRequired().getTransactionsSchema();
    final SszList<Transaction> transactions =
        transactionsSchema.sszDeserialize(oneByteTransactionsSsz(transactionCount));

    final TreeNode payloadTree =
        replaceField(
            payloadSchema,
            payloadSchema.getDefaultTree(),
            ExecutionPayloadFields.TRANSACTIONS,
            transactions.getBackingNode());
    final ExecutionPayload payload = payloadSchema.createFromBackingNode(payloadTree);

    final BeaconBlockBodySchema<?> bodySchema = definitions.getBeaconBlockBodySchema();
    final TreeNode bodyTree =
        replaceField(
            bodySchema,
            bodySchema.getDefaultTree(),
            BlockBodyFields.EXECUTION_PAYLOAD,
            payload.getBackingNode());
    final BeaconBlockBody body = bodySchema.createFromBackingNode(bodyTree);
    final BeaconBlockSchema blockSchema = definitions.getBeaconBlockSchema();
    final BeaconBlock block =
        blockSchema.create(UInt64.ZERO, UInt64.ZERO, Bytes32.ZERO, Bytes32.ZERO, body);

    return new FuluBlockFixture(blockSchema, block.sszSerialize(), merkleizer);
  }

  public static GloasPayloadFixture createGloas(
      final int transactionCount, final TinyTransactionBatchMerkleizer merkleizer) {
    final ExecutionPayloadSchema<?> payloadSchema =
        SchemaDefinitionsBellatrix.required(definitions(TestSpecFactory.createMainnetGloas()))
            .getExecutionPayloadSchema();
    final SszListSchema<Transaction, ?> transactionsSchema =
        payloadSchema.toVersionGloasRequired().getTransactionsSchema();
    final SszList<Transaction> transactions =
        transactionsSchema.sszDeserialize(oneByteTransactionsSsz(transactionCount));
    final TreeNode payloadTree =
        replaceField(
            payloadSchema,
            payloadSchema.getDefaultTree(),
            ExecutionPayloadFields.TRANSACTIONS,
            transactions.getBackingNode());
    final ExecutionPayload payload = payloadSchema.createFromBackingNode(payloadTree);

    return new GloasPayloadFixture(payloadSchema, payload.sszSerialize(), merkleizer);
  }

  static Bytes oneByteTransactionsSsz(final int count) {
    final int dataOffset = Math.multiplyExact(count, Integer.BYTES);
    final byte[] serialized = new byte[Math.addExact(dataOffset, count)];
    for (int i = 0; i < count; i++) {
      final int offset = dataOffset + i;
      serialized[i * Integer.BYTES] = (byte) offset;
      serialized[i * Integer.BYTES + 1] = (byte) (offset >>> 8);
      serialized[i * Integer.BYTES + 2] = (byte) (offset >>> 16);
      serialized[i * Integer.BYTES + 3] = (byte) (offset >>> 24);
      serialized[dataOffset + i] = (byte) i;
    }
    return Bytes.wrap(serialized);
  }

  private static SchemaDefinitions definitions(final Spec spec) {
    return spec.atSlot(UInt64.ZERO).getSchemaDefinitions();
  }

  private static TreeNode replaceField(
      final SszContainerSchema<?> schema,
      final TreeNode container,
      final SszFieldName field,
      final TreeNode value) {
    return container.updated(schema.getChildGeneralizedIndex(schema.getFieldIndex(field)), value);
  }

  private static ExecutionPayload executionPayload(final BeaconBlock block) {
    return block.getBody().getOptionalExecutionPayload().orElseThrow();
  }

  public record FuluBlockFixture(
      BeaconBlockSchema blockSchema,
      Bytes serializedBlock,
      TinyTransactionBatchMerkleizer merkleizer) {

    public BeaconBlock freshBlock() {
      return blockSchema.sszDeserialize(serializedBlock);
    }

    public Bytes32 hashJavaTransactions(final BeaconBlock block) {
      return executionPayload(block).getTransactions().hashTreeRoot();
    }

    public Bytes32 hashNativeTransactions(final BeaconBlock block) {
      final ExecutionPayload payload = executionPayload(block);
      final SszPackedByteListsNode packedNode =
          (SszPackedByteListsNode)
              ListSchemaUtil.getVectorNode(payload.getTransactions().getBackingNode());
      return merkleizer.hashFixed(
          packedNode,
          payload.getSchema().getTransactionSchema().treeDepth(),
          payload.getTransactions().getSchema().treeDepth());
    }

    public Bytes32 hashNativeBlock(final BeaconBlock block) {
      final ExecutionPayload payload = executionPayload(block);
      final Bytes32 nativeTransactionsRoot = hashNativeTransactions(block);
      final TreeNode nativePayloadTree =
          replaceField(
              payload.getSchema(),
              payload.getBackingNode(),
              ExecutionPayloadFields.TRANSACTIONS,
              LeafNode.create(nativeTransactionsRoot));
      final Bytes32 nativePayloadRoot = nativePayloadTree.hashTreeRoot();

      final BeaconBlockBody body = block.getBody();
      final Bytes32 nativeBodyRoot =
          replaceField(
                  body.getSchema(),
                  body.getBackingNode(),
                  BlockBodyFields.EXECUTION_PAYLOAD,
                  LeafNode.create(nativePayloadRoot))
              .hashTreeRoot();

      return replaceField(
              block.getSchema(),
              block.getBackingNode(),
              BeaconBlockFields.BODY,
              LeafNode.create(nativeBodyRoot))
          .hashTreeRoot();
    }
  }

  public record GloasPayloadFixture(
      ExecutionPayloadSchema<?> payloadSchema,
      Bytes serializedPayload,
      TinyTransactionBatchMerkleizer merkleizer) {

    public ExecutionPayload freshPayload() {
      return payloadSchema.sszDeserialize(serializedPayload);
    }

    public Bytes32 hashJavaTransactions(final ExecutionPayload payload) {
      return payload.getTransactions().hashTreeRoot();
    }

    public Bytes32 hashNativeTransactions(final ExecutionPayload payload) {
      final SszPackedProgressiveByteListsNode packedNode =
          (SszPackedProgressiveByteListsNode)
              ListSchemaUtil.getVectorNode(payload.getTransactions().getBackingNode());
      return merkleizer.hashProgressive(packedNode);
    }

    public Bytes32 hashNativePayload(final ExecutionPayload payload) {
      return replaceField(
              payload.getSchema(),
              payload.getBackingNode(),
              ExecutionPayloadFields.TRANSACTIONS,
              LeafNode.create(hashNativeTransactions(payload)))
          .hashTreeRoot();
    }
  }
}
