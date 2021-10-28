/*
 * Copyright 2021 ConsenSys AG.
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

package tech.pegasys.teku.api.schema.merge;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.datastructures.execution.Transaction;
import tech.pegasys.teku.ssz.collections.SszByteList;
import tech.pegasys.teku.ssz.type.Bytes20;

public class ExecutionPayload {

  public final Bytes32 parentHash;
  public final Bytes20 coinbase;
  public final Bytes32 stateRoot;
  public final Bytes32 receiptRoot;
  public final Bytes logsBloom;
  public final Bytes32 random;
  public final UInt64 blockNumber;
  public final UInt64 gasLimit;
  public final UInt64 gasUsed;
  public final UInt64 timestamp;
  public final Bytes extraData;
  public final UInt256 baseFeePerGas;
  public final Bytes32 blockHash;
  public final List<Bytes> transactions;

  public ExecutionPayload(
      @JsonProperty("parent_hash") Bytes32 parentHash,
      @JsonProperty("coinbase") Bytes20 coinbase,
      @JsonProperty("state_root") Bytes32 stateRoot,
      @JsonProperty("receipt_root") Bytes32 receiptRoot,
      @JsonProperty("logs_bloom") Bytes logsBloom,
      @JsonProperty("random") Bytes32 random,
      @JsonProperty("number") UInt64 blockNumber,
      @JsonProperty("gas_limit") UInt64 gasLimit,
      @JsonProperty("gas_used") UInt64 gasUsed,
      @JsonProperty("timestamp") UInt64 timestamp,
      @JsonProperty("extra_data") Bytes extraData,
      @JsonProperty("base_fee_per_gas") UInt256 baseFeePerGas,
      @JsonProperty("block_hash") Bytes32 blockHash,
      @JsonProperty("transactions") List<Bytes> transactions) {
    this.parentHash = parentHash;
    this.coinbase = coinbase;
    this.stateRoot = stateRoot;
    this.receiptRoot = receiptRoot;
    this.logsBloom = logsBloom;
    this.random = random;
    this.blockNumber = blockNumber;
    this.gasLimit = gasLimit;
    this.gasUsed = gasUsed;
    this.timestamp = timestamp;
    this.extraData = extraData;
    this.baseFeePerGas = baseFeePerGas;
    this.blockHash = blockHash;
    this.transactions = transactions != null ? transactions : Collections.emptyList();
  }

  public ExecutionPayload(
      tech.pegasys.teku.spec.datastructures.execution.ExecutionPayload executionPayload) {
    this.parentHash = executionPayload.getParentHash();
    this.coinbase = executionPayload.getCoinbase();
    this.stateRoot = executionPayload.getStateRoot();
    this.receiptRoot = executionPayload.getReceiptRoot();
    this.logsBloom = executionPayload.getLogsBloom();
    this.random = executionPayload.getRandom();
    this.blockNumber = executionPayload.getBlockNumber();
    this.gasLimit = executionPayload.getGasLimit();
    this.gasUsed = executionPayload.getGasUsed();
    this.timestamp = executionPayload.getTimestamp();
    this.extraData = executionPayload.getExtraData();
    this.baseFeePerGas = executionPayload.getBaseFeePerGas();
    this.blockHash = executionPayload.getBlockHash();
    this.transactions =
        executionPayload.getTransactions().stream()
            .map(Transaction::getOpaqueTransaction)
            .map(SszByteList::getBytes)
            .collect(Collectors.toList());
  }

  public tech.pegasys.teku.spec.datastructures.execution.ExecutionPayload
      asInternalExecutionPayload() {
    return new tech.pegasys.teku.spec.datastructures.execution.ExecutionPayload(
        parentHash,
        coinbase,
        stateRoot,
        receiptRoot,
        logsBloom,
        random,
        blockNumber,
        gasLimit,
        gasUsed,
        timestamp,
        extraData,
        baseFeePerGas,
        blockHash,
        transactions);
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    final ExecutionPayload that = (ExecutionPayload) o;
    return Objects.equals(parentHash, that.parentHash)
        && Objects.equals(coinbase, that.coinbase)
        && Objects.equals(stateRoot, that.stateRoot)
        && Objects.equals(receiptRoot, that.receiptRoot)
        && Objects.equals(logsBloom, that.logsBloom)
        && Objects.equals(random, that.random)
        && Objects.equals(blockNumber, that.blockNumber)
        && Objects.equals(gasLimit, that.gasLimit)
        && Objects.equals(gasUsed, that.gasUsed)
        && Objects.equals(timestamp, that.timestamp)
        && Objects.equals(extraData, that.extraData)
        && Objects.equals(baseFeePerGas, that.baseFeePerGas)
        && Objects.equals(blockHash, that.blockHash)
        && Objects.equals(transactions, that.transactions);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        parentHash,
        coinbase,
        stateRoot,
        receiptRoot,
        logsBloom,
        random,
        blockNumber,
        gasLimit,
        gasUsed,
        timestamp,
        extraData,
        baseFeePerGas,
        blockHash,
        transactions);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("parentHash", parentHash)
        .add("coinbase", coinbase)
        .add("stateRoot", stateRoot)
        .add("receiptRoot", receiptRoot)
        .add("logsBloom", logsBloom)
        .add("random", random)
        .add("blockNumber", blockNumber)
        .add("gasLimit", gasLimit)
        .add("gasUsed", gasUsed)
        .add("timestamp", timestamp)
        .add("extraData", extraData)
        .add("baseFeePerGas", baseFeePerGas)
        .add("blockHash", blockHash)
        .add("transactions", transactions)
        .toString();
  }
}
