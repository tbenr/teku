/*
 * Copyright Consensys Software Inc., 2022
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

package tech.pegasys.teku.spec.datastructures.execution.versions.eip7732;

import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import tech.pegasys.teku.infrastructure.bytes.Bytes20;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.datastructures.execution.versions.electra.ExecutionPayloadHeaderElectra;

public interface ExecutionPayloadHeaderEip7732 extends ExecutionPayloadHeaderElectra {

  Bytes32 getParentBlockHash();

  Bytes32 getParentBlockRoot();

  UInt64 getBuilderIndex();

  UInt64 getSlot();

  UInt256 getValue();

  Bytes32 getBlobKzgCommitmentsRoot();

  @Override
  default UInt64 getGasUsed() {
    throw new UnsupportedOperationException("Not supported in Eip7732");
  }

  @Override
  default UInt64 getBlockNumber() {
    throw new UnsupportedOperationException("Not supported in Eip7732");
  }

  @Override
  default UInt256 getBaseFeePerGas() {
    throw new UnsupportedOperationException("Not supported in Eip7732");
  }

  @Override
  default UInt64 getTimestamp() {
    throw new UnsupportedOperationException("Not supported in Eip7732");
  }

  @Override
  default Bytes32 getPrevRandao() {
    throw new UnsupportedOperationException("Not supported in Eip7732");
  }

  @Override
  default Bytes32 getReceiptsRoot() {
    throw new UnsupportedOperationException("Not supported in Eip7732");
  }

  @Override
  default Bytes32 getStateRoot() {
    throw new UnsupportedOperationException("Not supported in Eip7732");
  }

  @Override
  default Bytes20 getFeeRecipient() {
    throw new UnsupportedOperationException("Not supported in Eip7732");
  }

  @Override
  default Bytes32 getParentHash() {
    throw new UnsupportedOperationException("Not supported in Eip7732");
  }

  @Override
  default Bytes getLogsBloom() {
    throw new UnsupportedOperationException("Not supported in Eip7732");
  }

  @Override
  default Bytes getExtraData() {
    throw new UnsupportedOperationException("Not supported in Eip7732");
  }

  @Override
  default Bytes32 getPayloadHash() {
    throw new UnsupportedOperationException("Not supported in Eip7732");
  }

  @Override
  default Bytes32 getTransactionsRoot() {
    throw new UnsupportedOperationException("Not supported in Eip7732");
  }

  @Override
  default Bytes32 getWithdrawalsRoot() {
    throw new UnsupportedOperationException("Not supported in Eip7732");
  }

  @Override
  default UInt64 getBlobGasUsed() {
    throw new UnsupportedOperationException("Not supported in Eip7732");
  }

  @Override
  default UInt64 getExcessBlobGas() {
    throw new UnsupportedOperationException("Not supported in Eip7732");
  }

  @Override
  default Bytes32 getDepositRequestsRoot() {
    throw new UnsupportedOperationException("Not supported in Eip7732");
  }

  @Override
  default Bytes32 getWithdrawalRequestsRoot() {
    throw new UnsupportedOperationException("Not supported in Eip7732");
  }

  @Override
  default Bytes32 getConsolidationRequestsRoot() {
    throw new UnsupportedOperationException("Not supported in Eip7732");
  }

  @Override
  default Optional<ExecutionPayloadHeaderEip7732> toVersionEip7732() {
    return Optional.of(this);
  }
}
