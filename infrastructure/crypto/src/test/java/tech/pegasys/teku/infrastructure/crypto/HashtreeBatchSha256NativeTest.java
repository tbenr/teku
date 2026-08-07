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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HashtreeBatchSha256NativeTest {
  private static BatchSha256 hasher;

  @BeforeAll
  static void loadNativeHasher() {
    final String library = System.getProperty("teku.hashtree.library");
    assumeTrue(library != null, "Native hashtree library was not configured");
    hasher =
        HashtreeBatchSha256Loader.tryLoad(Path.of(library))
            .orElseThrow(() -> new LinkageError("Unable to load " + library));
    assertThat(
            HashtreeBatchSha256Loader.tryLoad(Path.of(library))
                .orElseThrow(() -> new LinkageError("Unable to reload " + library)))
        .isSameAs(hasher);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 7, 8, 15, 16, 17, 64, 257})
  void matchesJca(final int count) {
    final byte[] inputBytes = new byte[Math.multiplyExact(count, 64)];
    new Random(1).nextBytes(inputBytes);
    final byte[] expected = jcaHash64(inputBytes, count);

    try (Arena arena = Arena.ofConfined()) {
      final MemorySegment input = arena.allocate(inputBytes.length);
      final MemorySegment output = arena.allocate(Math.multiplyExact(count, 32L));
      input.copyFrom(MemorySegment.ofArray(inputBytes));
      hasher.hash64(input, output, count);
      assertThat(output.toArray(ValueLayout.JAVA_BYTE)).isEqualTo(expected);
    }
  }

  @Test
  void supportsConcurrentCallersWithIndependentScratch() throws Exception {
    final byte[] inputBytes = new byte[64 * 257];
    new Random(2).nextBytes(inputBytes);
    final byte[] expected = jcaHash64(inputBytes, 257);
    try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
      final List<Future<byte[]>> results = new ArrayList<>();
      for (int i = 0; i < 32; i++) {
        results.add(
            executor.submit(
                () -> {
                  try (Arena arena = Arena.ofConfined()) {
                    final MemorySegment input = arena.allocate(inputBytes.length);
                    final MemorySegment output = arena.allocate(expected.length);
                    input.copyFrom(MemorySegment.ofArray(inputBytes));
                    hasher.hash64(input, output, 257);
                    return output.toArray(ValueLayout.JAVA_BYTE);
                  }
                }));
      }
      for (final Future<byte[]> result : results) {
        assertThat(result.get()).isEqualTo(expected);
      }
    }
  }

  private static byte[] jcaHash64(final byte[] input, final int count) {
    final MessageDigest digest = MessageDigestFactory.createSha256();
    final byte[] output = new byte[Math.multiplyExact(count, 32)];
    for (int block = 0; block < count; block++) {
      digest.update(input, block * 64, 64);
      System.arraycopy(digest.digest(), 0, output, block * 32, 32);
    }
    return output;
  }
}
