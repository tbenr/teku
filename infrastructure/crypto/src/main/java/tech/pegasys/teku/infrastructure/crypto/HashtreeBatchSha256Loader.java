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
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class HashtreeBatchSha256Loader {
  private static final FunctionDescriptor INIT_DESCRIPTOR =
      FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);
  private static final FunctionDescriptor HASH_DESCRIPTOR =
      FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);
  private static final ConcurrentMap<Path, Optional<BatchSha256>> PROVIDERS =
      new ConcurrentHashMap<>();

  private HashtreeBatchSha256Loader() {}

  public static Optional<BatchSha256> tryLoad(final Path library) {
    final Path normalizedLibrary = library.toAbsolutePath().normalize();
    return PROVIDERS.computeIfAbsent(normalizedLibrary, HashtreeBatchSha256Loader::load);
  }

  private static Optional<BatchSha256> load(final Path library) {
    try {
      final Linker linker = Linker.nativeLinker();
      final SymbolLookup symbols = SymbolLookup.libraryLookup(library, Arena.global());
      final MethodHandle init =
          linker.downcallHandle(requiredSymbol(symbols, "hashtree_init"), INIT_DESCRIPTOR);
      final MethodHandle hash =
          linker.downcallHandle(requiredSymbol(symbols, "hashtree_hash"), HASH_DESCRIPTOR);
      initialize(init);
      return Optional.of(new HashtreeBatchSha256(hash));
    } catch (final RuntimeException | LinkageError error) {
      return Optional.empty();
    }
  }

  private static void initialize(final MethodHandle init) {
    try {
      init.invokeExact(MemorySegment.NULL);
    } catch (final RuntimeException | Error error) {
      throw error;
    } catch (final Throwable error) {
      throw new IllegalStateException("hashtree_init downcall failed", error);
    }
  }

  private static MemorySegment requiredSymbol(final SymbolLookup symbols, final String symbol) {
    return symbols
        .find(symbol)
        .orElseThrow(() -> new IllegalStateException("Missing native symbol: " + symbol));
  }
}
