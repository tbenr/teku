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
public class NativeMerkleTreeBenchmark {
  @Param({"8", "32", "128", "512", "2048", "8192", "32768"})
  private int pairCount;

  private BatchSha256 hasher;
  private byte[] heapLeaves;
  private MessageDigest digest;
  private byte[] jcaInput;
  private byte[] jcaOutput;
  private Arena reusableArena;
  private MemorySegment reusableInput;
  private MemorySegment reusableOutput;

  @Setup(Level.Trial)
  public void setup() {
    if (pairCount <= 0 || Integer.bitCount(pairCount) != 1) {
      throw new IllegalArgumentException("pairCount must be a positive power of two");
    }
    final String library = System.getenv("HASHTREE_LIBRARY");
    if (library == null) {
      throw new IllegalStateException("HASHTREE_LIBRARY is not configured");
    }
    hasher =
        HashtreeBatchSha256Loader.tryLoad(Path.of(library))
            .orElseThrow(() -> new LinkageError("Unable to load " + library));
    heapLeaves = new byte[Math.multiplyExact(pairCount, 64)];
    new Random(2).nextBytes(heapLeaves);
    digest = MessageDigestFactory.createSha256();
    jcaInput = new byte[heapLeaves.length];
    jcaOutput = new byte[heapLeaves.length];
    reusableArena = Arena.ofConfined();
    reusableInput = reusableArena.allocate(heapLeaves.length);
    reusableOutput = reusableArena.allocate(heapLeaves.length);

    final byte[] expected = jcaTreeRoot();
    reusableInput.copyFrom(MemorySegment.ofArray(heapLeaves));
    final byte[] actual =
        nativeTree(reusableInput, reusableOutput, pairCount).toArray(ValueLayout.JAVA_BYTE);
    if (!Arrays.equals(actual, expected)) {
      throw new IllegalStateException("Native Merkle root does not match JCA");
    }
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    reusableArena.close();
  }

  @Benchmark
  public void jcaTree(final Blackhole blackhole) {
    blackhole.consume(jcaTreeRoot());
  }

  @Benchmark
  public void nativeTreeReusableScratch(final Blackhole blackhole) {
    reusableInput.copyFrom(MemorySegment.ofArray(heapLeaves));
    final byte[] root =
        nativeTree(reusableInput, reusableOutput, pairCount).toArray(ValueLayout.JAVA_BYTE);
    blackhole.consume(root);
  }

  @Benchmark
  public void nativeTreeOperationArena(final Blackhole blackhole) {
    try (Arena arena = Arena.ofConfined()) {
      final MemorySegment input = arena.allocate(heapLeaves.length);
      final MemorySegment output = arena.allocate(heapLeaves.length);
      input.copyFrom(MemorySegment.ofArray(heapLeaves));
      final byte[] root = nativeTree(input, output, pairCount).toArray(ValueLayout.JAVA_BYTE);
      blackhole.consume(root);
    }
  }

  private byte[] jcaTreeRoot() {
    System.arraycopy(heapLeaves, 0, jcaInput, 0, heapLeaves.length);
    byte[] input = jcaInput;
    byte[] output = jcaOutput;
    int currentPairCount = pairCount;
    while (currentPairCount > 0) {
      for (int i = 0; i < currentPairCount; i++) {
        digest.update(input, i * 64, 64);
        System.arraycopy(digest.digest(), 0, output, i * 32, 32);
      }
      if (currentPairCount == 1) {
        return Arrays.copyOf(output, 32);
      }
      final byte[] swap = input;
      input = output;
      output = swap;
      currentPairCount /= 2;
    }
    throw new IllegalArgumentException("pairCount must be positive");
  }

  private MemorySegment nativeTree(MemorySegment input, MemorySegment output, long pairCount) {
    while (pairCount > 0) {
      hasher.hash64(input, output, pairCount);
      if (pairCount == 1) {
        return output.asSlice(0, 32);
      }
      final MemorySegment swap = input;
      input = output;
      output = swap;
      pairCount /= 2;
    }
    throw new IllegalArgumentException("pairCount must be positive");
  }
}
