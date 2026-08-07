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

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

final class HashtreeBatchSha256 implements BatchSha256 {
  private static final long INPUT_BLOCK_SIZE = 64;
  private static final long OUTPUT_BLOCK_SIZE = 32;

  private final MethodHandle hashHandle;

  HashtreeBatchSha256(final MethodHandle hashHandle) {
    this.hashHandle = hashHandle;
  }

  @Override
  public void hash64(final MemorySegment input, final MemorySegment output, final long count) {
    if (count < 0) {
      throw new IllegalArgumentException("count must be non-negative");
    }
    if (count == 0) {
      return;
    }
    final long requiredInput;
    final long requiredOutput;
    try {
      requiredInput = Math.multiplyExact(count, INPUT_BLOCK_SIZE);
      requiredOutput = Math.multiplyExact(count, OUTPUT_BLOCK_SIZE);
    } catch (final ArithmeticException error) {
      throw new IllegalArgumentException("count is too large: " + count, error);
    }
    if (!input.isNative() || !output.isNative()) {
      throw new IllegalArgumentException("input and output must be native segments");
    }
    if (output.isReadOnly()) {
      throw new IllegalArgumentException("output must be writable");
    }
    if (input.byteSize() < requiredInput) {
      throw new IllegalArgumentException("input segment is too small");
    }
    if (output.byteSize() < requiredOutput) {
      throw new IllegalArgumentException("output segment is too small");
    }
    final MemorySegment usedInput = input.asSlice(0, requiredInput);
    final MemorySegment usedOutput = output.asSlice(0, requiredOutput);
    if (usedInput.asOverlappingSlice(usedOutput).isPresent()) {
      throw new IllegalArgumentException("input and output must not overlap");
    }
    try {
      hashHandle.invokeExact(output, input, count);
    } catch (final RuntimeException | Error error) {
      throw error;
    } catch (final Throwable error) {
      throw new IllegalStateException("hashtree_hash downcall failed", error);
    }
  }
}
