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

package tech.pegasys.teku.infrastructure.crypto;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 2, jvmArgsAppend = "--enable-native-access=ALL-UNNAMED")
@State(Scope.Thread)
public class NativeSha256BatchBenchmark {
  @Param({"1", "2", "4", "8", "16", "64", "256", "1024", "4096", "16384"})
  private int count;

  private BatchSha256 hasher;
  private byte[] heapInput;
  private MessageDigest digest;
  private Arena reusableArena;
  private MemorySegment reusableInput;
  private MemorySegment reusableOutput;

  @Setup(Level.Trial)
  public void setup() {
    final String library = System.getenv("HASHTREE_LIBRARY");
    if (library == null) {
      throw new IllegalStateException("HASHTREE_LIBRARY is not configured");
    }
    hasher =
        HashtreeBatchSha256Loader.tryLoad(Path.of(library))
            .orElseThrow(() -> new LinkageError("Unable to load " + library));
    heapInput = new byte[Math.multiplyExact(count, 64)];
    new Random(1).nextBytes(heapInput);
    digest = MessageDigestFactory.createSha256();
    reusableArena = Arena.ofConfined();
    reusableInput = reusableArena.allocate(Math.multiplyExact(count, 64L));
    reusableOutput = reusableArena.allocate(Math.multiplyExact(count, 32L));

    final byte[] expected = jcaHash64();
    reusableInput.copyFrom(MemorySegment.ofArray(heapInput));
    hasher.hash64(reusableInput, reusableOutput, count);
    if (!Arrays.equals(reusableOutput.toArray(ValueLayout.JAVA_BYTE), expected)) {
      throw new IllegalStateException("Native SHA-256 result does not match JCA");
    }
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    reusableArena.close();
  }

  @Benchmark
  public void jcaCurrent(final Blackhole blackhole) {
    byte result = 0;
    for (int i = 0; i < count; i++) {
      digest.update(heapInput, i * 64, 64);
      result ^= digest.digest()[0];
    }
    blackhole.consume(result);
  }

  @Benchmark
  public void nativeOnly(final Blackhole blackhole) {
    hasher.hash64(reusableInput, reusableOutput, count);
    blackhole.consume(reusableOutput.get(ValueLayout.JAVA_BYTE, 0));
  }

  @Benchmark
  public void nativeReusableScratch(final Blackhole blackhole) {
    reusableInput.copyFrom(MemorySegment.ofArray(heapInput));
    hasher.hash64(reusableInput, reusableOutput, count);
    blackhole.consume(reusableOutput.get(ValueLayout.JAVA_BYTE, 0));
  }

  @Benchmark
  public void nativeOperationArena(final Blackhole blackhole) {
    try (Arena arena = Arena.ofConfined()) {
      final MemorySegment input = arena.allocate(Math.multiplyExact(count, 64L));
      final MemorySegment output = arena.allocate(Math.multiplyExact(count, 32L));
      input.copyFrom(MemorySegment.ofArray(heapInput));
      hasher.hash64(input, output, count);
      blackhole.consume(output.get(ValueLayout.JAVA_BYTE, 0));
    }
  }

  private byte[] jcaHash64() {
    final byte[] output = new byte[Math.multiplyExact(count, 32)];
    for (int i = 0; i < count; i++) {
      digest.update(heapInput, i * 64, 64);
      System.arraycopy(digest.digest(), 0, output, i * 32, 32);
    }
    return output;
  }
}
