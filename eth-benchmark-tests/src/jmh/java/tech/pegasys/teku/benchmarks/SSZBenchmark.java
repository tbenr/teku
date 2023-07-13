/*
 * Copyright ConsenSys Software Inc., 2022
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

package tech.pegasys.teku.benchmarks;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import com.google.common.io.ByteStreams;
import org.apache.tuweni.bytes.Bytes;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import tech.pegasys.teku.benchmarks.util.CustomRunner;
import tech.pegasys.teku.infrastructure.ssz.SimpleOffsetSerializable;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayload;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconStateSchema;
import tech.pegasys.teku.spec.util.DataStructureUtil;

public class SSZBenchmark {
  private static DataStructureUtil dataStructureUtil =
      new DataStructureUtil(TestSpecFactory.createMinimalBellatrix());
  private static SimpleOffsetSerializable state = dataStructureUtil.randomBeaconState();

  private static Spec capellaSpec = TestSpecFactory.createMainnetCapella();
private static BeaconStateSchema<?, ?> beaconStateSchema = capellaSpec.getGenesisSchemaDefinitions().getBeaconStateSchema();

  private static Path inputPath = Path.of("finalizedstate.ssz");
  private static InputStream inputStream;
  private static Bytes inData;
  private static BeaconState deserializedBeaconState;

  static {
    try {
      inputStream = Files.newInputStream(inputPath);
      inData = Bytes.wrap(ByteStreams.toByteArray(inputStream));
      deserializedBeaconState = beaconStateSchema.sszDeserialize(inData);
      deserializedBeaconState.getBalances();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Benchmark @BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.MILLISECONDS)
  @Warmup(iterations = 2, time = 100, timeUnit = TimeUnit.MILLISECONDS)
  @Measurement(iterations = 5, time = 100, timeUnit = TimeUnit.MILLISECONDS)
  public void BeaconStateDeserialization() {
    beaconStateSchema.sszDeserialize(inData);
  }

  @Benchmark @BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.MILLISECONDS)
  @Warmup(iterations = 0, time = 100, timeUnit = TimeUnit.MILLISECONDS)
  @Measurement(iterations = 1, time = 10, timeUnit = TimeUnit.MILLISECONDS)
  public void BeaconStateGetBalances() {
    deserializedBeaconState.getBalances().get(1);
  }

  @Benchmark @BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.MILLISECONDS)
  @Warmup(iterations = 0, time = 100, timeUnit = TimeUnit.MILLISECONDS)
  @Measurement(iterations = 1, time = 10, timeUnit = TimeUnit.MILLISECONDS)
  public void BeaconStateHashing(Blackhole bh) {
    bh.consume(deserializedBeaconState.hashTreeRoot());
  }

  @Benchmark
  @Warmup(iterations = 2, time = 100, timeUnit = TimeUnit.MILLISECONDS)
  @Measurement(iterations = 5, time = 100, timeUnit = TimeUnit.MILLISECONDS)
  public void BeaconStateSerialization() {
    state.sszSerialize();
  }

  private static ExecutionPayload executionPayload = dataStructureUtil.randomExecutionPayload();

  @Benchmark
  @Warmup(iterations = 2, time = 100, timeUnit = TimeUnit.MILLISECONDS)
  @Measurement(iterations = 5, time = 100, timeUnit = TimeUnit.MILLISECONDS)
  public void ExecutionPayloadIsDefault() {
    executionPayload.isDefault();
  }

  public static void main(String[] args) {
    SSZBenchmark asd = new SSZBenchmark();

    new CustomRunner(1,1).withBench(asd::BeaconStateHashing).run();
  }
}
