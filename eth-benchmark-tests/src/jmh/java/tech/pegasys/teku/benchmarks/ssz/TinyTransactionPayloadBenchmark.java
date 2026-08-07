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

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.apache.tuweni.bytes.Bytes32;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import tech.pegasys.teku.infrastructure.crypto.BatchSha256;
import tech.pegasys.teku.infrastructure.crypto.HashtreeBatchSha256Loader;
import tech.pegasys.teku.spec.datastructures.blocks.BeaconBlock;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayload;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = "--enable-native-access=ALL-UNNAMED")
@Threads(1)
public class TinyTransactionPayloadBenchmark {

  private static final int TILE_SIZE = 16_384;

  @Benchmark
  public Bytes32 fuluCurrentTransactionRoot(final FuluState state) {
    return state.fixture.hashJavaTransactions(state.block);
  }

  @Benchmark
  public Bytes32 fuluNativeTransactionRoot(final FuluState state) {
    return state.fixture.hashNativeTransactions(state.block);
  }

  @Benchmark
  public Bytes32 fuluCurrentBlockRoot(final FuluState state) {
    return state.block.hashTreeRoot();
  }

  @Benchmark
  public Bytes32 fuluNativeBlockRoot(final FuluState state) {
    return state.fixture.hashNativeBlock(state.block);
  }

  @Benchmark
  public Bytes32 fuluCurrentDeserializeAndHash(final FuluDeserializeState state) {
    return state.fixture.freshBlock().hashTreeRoot();
  }

  @Benchmark
  public Bytes32 fuluNativeDeserializeAndHash(final FuluDeserializeState state) {
    return state.fixture.hashNativeBlock(state.fixture.freshBlock());
  }

  @Benchmark
  public Bytes32 gloasCurrentTransactionRoot(final GloasState state) {
    return state.fixture.hashJavaTransactions(state.payload);
  }

  @Benchmark
  public Bytes32 gloasNativeTransactionRoot(final GloasState state) {
    return state.fixture.hashNativeTransactions(state.payload);
  }

  @Benchmark
  public Bytes32 gloasCurrentPayloadRoot(final GloasState state) {
    return state.payload.hashTreeRoot();
  }

  @Benchmark
  public Bytes32 gloasNativePayloadRoot(final GloasState state) {
    return state.fixture.hashNativePayload(state.payload);
  }

  @Benchmark
  public Bytes32 gloasCurrentDeserializeAndHash(final GloasDeserializeState state) {
    return state.fixture.freshPayload().hashTreeRoot();
  }

  @Benchmark
  public Bytes32 gloasNativeDeserializeAndHash(final GloasDeserializeState state) {
    return state.fixture.hashNativePayload(state.fixture.freshPayload());
  }

  @State(Scope.Thread)
  public static class FuluState {
    @Param({"4", "5", "16", "21", "256", "341", "4096", "5461", "65536", "87381", "1048576"})
    public int transactionCount;

    private TinyTransactionPayloadFixture.FuluBlockFixture fixture;
    private BeaconBlock block;

    @Setup(Level.Trial)
    public void setupTrial() {
      final TinyTransactionBatchMerkleizer merkleizer =
          new TinyTransactionBatchMerkleizer(loadHasher(), TILE_SIZE);
      fixture = TinyTransactionPayloadFixture.createFulu(transactionCount, merkleizer);
      final BeaconBlock validationBlock = fixture.freshBlock();
      requireEqual(
          fixture.hashJavaTransactions(validationBlock),
          fixture.hashNativeTransactions(validationBlock),
          "Fulu transaction list");
      requireEqual(
          validationBlock.hashTreeRoot(),
          fixture.hashNativeBlock(validationBlock),
          "Fulu beacon block");
    }

    @Setup(Level.Invocation)
    public void setupInvocation() {
      block = fixture.freshBlock();
    }
  }

  @State(Scope.Thread)
  public static class FuluDeserializeState {
    @Param({"4", "5", "16", "21", "256", "341", "4096", "5461", "65536", "87381", "1048576"})
    public int transactionCount;

    private TinyTransactionPayloadFixture.FuluBlockFixture fixture;

    @Setup(Level.Trial)
    public void setupTrial() {
      fixture =
          TinyTransactionPayloadFixture.createFulu(
              transactionCount, new TinyTransactionBatchMerkleizer(loadHasher(), TILE_SIZE));
      final BeaconBlock validationBlock = fixture.freshBlock();
      requireEqual(
          validationBlock.hashTreeRoot(),
          fixture.hashNativeBlock(validationBlock),
          "Fulu beacon block");
    }
  }

  @State(Scope.Thread)
  public static class GloasState {
    @Param({"4", "5", "16", "21", "256", "341", "4096", "5461", "65536", "87381", "1048576"})
    public int transactionCount;

    private TinyTransactionPayloadFixture.GloasPayloadFixture fixture;
    private ExecutionPayload payload;

    @Setup(Level.Trial)
    public void setupTrial() {
      final TinyTransactionBatchMerkleizer merkleizer =
          new TinyTransactionBatchMerkleizer(loadHasher(), TILE_SIZE);
      fixture = TinyTransactionPayloadFixture.createGloas(transactionCount, merkleizer);
      final ExecutionPayload validationPayload = fixture.freshPayload();
      requireEqual(
          fixture.hashJavaTransactions(validationPayload),
          fixture.hashNativeTransactions(validationPayload),
          "Gloas transaction list");
      requireEqual(
          validationPayload.hashTreeRoot(),
          fixture.hashNativePayload(validationPayload),
          "Gloas execution payload");
    }

    @Setup(Level.Invocation)
    public void setupInvocation() {
      payload = fixture.freshPayload();
    }
  }

  @State(Scope.Thread)
  public static class GloasDeserializeState {
    @Param({"4", "5", "16", "21", "256", "341", "4096", "5461", "65536", "87381", "1048576"})
    public int transactionCount;

    private TinyTransactionPayloadFixture.GloasPayloadFixture fixture;

    @Setup(Level.Trial)
    public void setupTrial() {
      fixture =
          TinyTransactionPayloadFixture.createGloas(
              transactionCount, new TinyTransactionBatchMerkleizer(loadHasher(), TILE_SIZE));
      final ExecutionPayload validationPayload = fixture.freshPayload();
      requireEqual(
          validationPayload.hashTreeRoot(),
          fixture.hashNativePayload(validationPayload),
          "Gloas execution payload");
    }
  }

  private static BatchSha256 loadHasher() {
    final String library = System.getenv("HASHTREE_LIBRARY");
    if (library == null) {
      throw new IllegalStateException("HASHTREE_LIBRARY is not configured");
    }
    return HashtreeBatchSha256Loader.tryLoad(Path.of(library))
        .orElseThrow(() -> new LinkageError("Unable to load " + library));
  }

  private static void requireEqual(
      final Bytes32 expected, final Bytes32 actual, final String description) {
    if (!expected.equals(actual)) {
      throw new IllegalStateException(description + " native root does not match Teku");
    }
  }
}
