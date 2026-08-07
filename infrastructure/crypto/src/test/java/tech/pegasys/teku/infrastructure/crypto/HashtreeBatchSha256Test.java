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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HashtreeBatchSha256Test {
  private static final MethodHandle NOOP = createNoopHandle();
  private final BatchSha256 hasher = new HashtreeBatchSha256(NOOP);

  @Test
  void rejectsNegativeCount() {
    try (Arena arena = Arena.ofConfined()) {
      assertThatThrownBy(() -> hasher.hash64(arena.allocate(64), arena.allocate(32), -1))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsHeapInput() {
    try (Arena arena = Arena.ofConfined()) {
      assertThatThrownBy(
              () -> hasher.hash64(MemorySegment.ofArray(new byte[64]), arena.allocate(32), 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("native");
    }
  }

  @Test
  void rejectsReadOnlyOutput() {
    try (Arena arena = Arena.ofConfined()) {
      assertThatThrownBy(
              () -> hasher.hash64(arena.allocate(64), arena.allocate(32).asReadOnly(), 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("writable");
    }
  }

  @Test
  void acceptsZeroWithoutCallingNative() {
    hasher.hash64(MemorySegment.NULL, MemorySegment.NULL, 0);
  }

  @Test
  void rejectsUndersizedInput() {
    try (Arena arena = Arena.ofConfined()) {
      assertThatThrownBy(() -> hasher.hash64(arena.allocate(63), arena.allocate(32), 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("input");
    }
  }

  @Test
  void rejectsUndersizedOutput() {
    try (Arena arena = Arena.ofConfined()) {
      assertThatThrownBy(() -> hasher.hash64(arena.allocate(64), arena.allocate(31), 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("output");
    }
  }

  @Test
  void rejectsHeapOutput() {
    try (Arena arena = Arena.ofConfined()) {
      assertThatThrownBy(
              () -> hasher.hash64(arena.allocate(64), MemorySegment.ofArray(new byte[32]), 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("native");
    }
  }

  @Test
  void rejectsOverflowingCount() {
    assertThatThrownBy(() -> hasher.hash64(MemorySegment.NULL, MemorySegment.NULL, Long.MAX_VALUE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(Long.toString(Long.MAX_VALUE));
  }

  @Test
  void rejectsOverlappingSegments() {
    try (Arena arena = Arena.ofConfined()) {
      final MemorySegment shared = arena.allocate(96);
      assertThatThrownBy(() -> hasher.hash64(shared.asSlice(0, 64), shared.asSlice(32, 32), 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("overlap");
    }
  }

  @Test
  void reportsMissingLibraryAsUnavailable(@TempDir final Path tempDir) {
    assertThat(HashtreeBatchSha256Loader.tryLoad(tempDir.resolve("missing-library"))).isEmpty();
  }

  @SuppressWarnings({"UnusedMethod", "UnusedVariable"})
  private static void noop(
      final MemorySegment output, final MemorySegment input, final long count) {}

  private static MethodHandle createNoopHandle() {
    try {
      return MethodHandles.lookup()
          .findStatic(
              HashtreeBatchSha256Test.class,
              "noop",
              MethodType.methodType(
                  void.class, MemorySegment.class, MemorySegment.class, long.class));
    } catch (final ReflectiveOperationException e) {
      throw new LinkageError(e.getMessage(), e);
    }
  }
}
