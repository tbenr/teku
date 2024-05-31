/*
 * Copyright Consensys Software Inc., 2024
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

package tech.pegasys.teku.spec.datastructures.execution.versions.electra;

import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.bls.BLSPublicKey;
import tech.pegasys.teku.bls.BLSSignature;
import tech.pegasys.teku.infrastructure.ssz.containers.ContainerSchema5;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszBytes32;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszUInt64;
import tech.pegasys.teku.infrastructure.ssz.schema.SszPrimitiveSchemas;
import tech.pegasys.teku.infrastructure.ssz.schema.impl.NamedSchema;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.datastructures.type.SszPublicKey;
import tech.pegasys.teku.spec.datastructures.type.SszPublicKeySchema;
import tech.pegasys.teku.spec.datastructures.type.SszSignature;
import tech.pegasys.teku.spec.datastructures.type.SszSignatureSchema;

public class DepositReceiptSchema
    extends ContainerSchema5<
        DepositReceipt, SszPublicKey, SszBytes32, SszUInt64, SszSignature, SszUInt64> {

  public DepositReceiptSchema() {
    super(
        "DepositReceipt",
        NamedSchema.namedSchema("pubkey", SszPublicKeySchema.INSTANCE),
        NamedSchema.namedSchema("withdrawal_credentials", SszPrimitiveSchemas.BYTES32_SCHEMA),
        NamedSchema.namedSchema("amount", SszPrimitiveSchemas.UINT64_SCHEMA),
        NamedSchema.namedSchema("signature", SszSignatureSchema.INSTANCE),
        NamedSchema.namedSchema("index", SszPrimitiveSchemas.UINT64_SCHEMA));
  }

  public DepositReceipt create(
      final BLSPublicKey pubkey,
      final Bytes32 withdrawalCredentials,
      final UInt64 amount,
      final BLSSignature signature,
      final UInt64 index) {
    return new DepositReceipt(this, pubkey, withdrawalCredentials, amount, signature, index);
  }

  @Override
  public DepositReceipt createFromBackingNode(final TreeNode node) {
    return new DepositReceipt(this, node);
  }
}
