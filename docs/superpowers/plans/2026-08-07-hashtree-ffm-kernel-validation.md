# Hashtree FFM Kernel Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and measure a safe Java 25 FFM binding to `hashtree` before implementing any SSZ node planner.

**Architecture:** A reproducible local build script pins upstream `hashtree` 0.2.5 at commit `30497cff98a06362eadde897202634f91d504fd8` and emits a host shared library under the crypto module's build directory. A narrow `BatchSha256` interface validates native segments and delegates batches of independent 64-byte blocks through one cached FFM downcall. JMH compares JCA, native-only, reusable-scratch, and operation-scoped-arena hashing for individual layers and complete balanced trees.

**Tech Stack:** Java 25 FFM (`java.lang.foreign`), Gradle, Bash, host C/assembler toolchain, OffchainLabs/hashtree 0.2.5, JUnit 5, AssertJ, JMH 1.37.

## Global Constraints

- This phase does not modify `infrastructure/ssz`, `TreeNode`, `SszSuperNode`, or packed transaction nodes.
- Do not retain an `Arena` or `MemorySegment` in an SSZ object.
- Do not rely on overlapping native input and output buffers.
- Include heap-to-native staging and final 32-byte copy costs in the decisive benchmark.
- Treat native load failure as optional-provider unavailability; the existing JCA path remains usable.
- Validate native segment kind, writability, count, byte sizes, and multiplication overflow before downcalling.
- Run `hashtree_init(NULL)` exactly once for each loaded library before concurrent hashing.
- Do not proceed to an SSZ planner merely because native-only numbers are faster.
- The local host result is evidence for that host only; production adoption still requires the platform matrix in the design.

## File Map

- `infrastructure/crypto/src/main/native/hashtree/build-hashtree.sh`: fetches the pinned source and links the static archive into a host shared library.
- `infrastructure/crypto/build.gradle`: defines `buildHashtreeNative`, `hashtreeTest`, and `hashtreeJmh`.
- `infrastructure/crypto/src/main/java/tech/pegasys/teku/infrastructure/crypto/BatchSha256.java`: provider contract for hashing independent 64-byte blocks.
- `infrastructure/crypto/src/main/java/tech/pegasys/teku/infrastructure/crypto/HashtreeBatchSha256.java`: checked FFM downcall implementation.
- `infrastructure/crypto/src/main/java/tech/pegasys/teku/infrastructure/crypto/HashtreeBatchSha256Loader.java`: symbol lookup, CPU-dispatch initialization, and optional loading.
- `infrastructure/crypto/src/test/java/tech/pegasys/teku/infrastructure/crypto/HashtreeBatchSha256Test.java`: provider boundary and argument tests without a native dependency.
- `infrastructure/crypto/src/test/java/tech/pegasys/teku/infrastructure/crypto/HashtreeBatchSha256NativeTest.java`: host-library known-answer and concurrent tests.
- `infrastructure/crypto/src/jmh/java/tech/pegasys/teku/infrastructure/crypto/NativeSha256BatchBenchmark.java`: one-layer throughput and staging crossover.
- `infrastructure/crypto/src/jmh/java/tech/pegasys/teku/infrastructure/crypto/NativeMerkleTreeBenchmark.java`: complete balanced-tree comparison.

---

### Task 1: Reproducible Host Shared-Library Build

**Files:**
- Create: `infrastructure/crypto/src/main/native/hashtree/build-hashtree.sh`
- Modify: `infrastructure/crypto/build.gradle`

**Interfaces:**
- Consumes: host `git`, `make`, `cc`, and the pinned upstream commit.
- Produces: Gradle task `:infrastructure:crypto:buildHashtreeNative` and either `build/native/hashtree/libhashtree.dylib` or `build/native/hashtree/libhashtree.so`.

- [ ] **Step 1: Add the pinned build script**

Create the executable script with this behavior:

```bash
#!/usr/bin/env bash
set -euo pipefail

readonly REPOSITORY="https://github.com/OffchainLabs/hashtree.git"
readonly COMMIT="30497cff98a06362eadde897202634f91d504fd8"
readonly OUTPUT_DIR="${1:?output directory is required}"
readonly SOURCE_DIR="${OUTPUT_DIR}/source"
readonly UPSTREAM_BUILD_DIR="${OUTPUT_DIR}/upstream"

mkdir -p "${OUTPUT_DIR}"
if [[ ! -d "${SOURCE_DIR}/.git" ]]; then
  git init "${SOURCE_DIR}"
  git -C "${SOURCE_DIR}" remote add origin "${REPOSITORY}"
fi

git -C "${SOURCE_DIR}" fetch --depth=1 origin "${COMMIT}"
git -C "${SOURCE_DIR}" checkout --detach FETCH_HEAD
test "$(git -C "${SOURCE_DIR}" rev-parse HEAD)" = "${COMMIT}"

readonly ARCHIVE="${UPSTREAM_BUILD_DIR}/lib/libhashtree.a"
make -C "${SOURCE_DIR}/src" clean OUT_DIR="${UPSTREAM_BUILD_DIR}"
make -C "${SOURCE_DIR}/src" \
  OUT_DIR="${UPSTREAM_BUILD_DIR}" \
  CFLAGS="-g -Wall -Werror -O3 -fPIC" \
  ASFLAGS="-g -fPIC" \
  "${ARCHIVE}"

case "$(uname -s)" in
  Darwin)
    cc -dynamiclib -Wl,-force_load,"${ARCHIVE}" \
      -o "${OUTPUT_DIR}/libhashtree.dylib"
    ;;
  Linux)
    cc -shared -Wl,--whole-archive "${ARCHIVE}" -Wl,--no-whole-archive \
      -o "${OUTPUT_DIR}/libhashtree.so"
    ;;
  *)
    echo "Unsupported prototype host: $(uname -s)" >&2
    exit 1
    ;;
esac
```

Run:

```bash
chmod +x infrastructure/crypto/src/main/native/hashtree/build-hashtree.sh
```

Expected: the executable bit is set and `git diff --summary` reports mode `100755`.

- [ ] **Step 2: Add the Gradle build task**

Append this host-only prototype configuration to `infrastructure/crypto/build.gradle`:

```groovy
def hashtreeOsName = System.getProperty('os.name').toLowerCase(Locale.ROOT)
def hashtreeLibraryName = hashtreeOsName.contains('mac')
		? 'libhashtree.dylib'
		: 'libhashtree.so'
def hashtreeOutputDir = layout.buildDirectory.dir('native/hashtree')
def hashtreeLibrary = hashtreeOutputDir.map { it.file(hashtreeLibraryName) }

tasks.register('buildHashtreeNative', Exec) {
	group = 'build'
	description = 'Builds the pinned hashtree shared library for local validation.'
	inputs.file('src/main/native/hashtree/build-hashtree.sh')
	outputs.file(hashtreeLibrary)
	doFirst {
		commandLine(
				'bash',
				file('src/main/native/hashtree/build-hashtree.sh').absolutePath,
				hashtreeOutputDir.get().asFile.absolutePath)
	}
}
```

Add `import java.util.Locale` at the top of the Gradle file.

- [ ] **Step 3: Build the shared library**

Run:

```bash
./gradlew :infrastructure:crypto:buildHashtreeNative
```

Expected: `BUILD SUCCESSFUL` and the platform library exists beneath
`infrastructure/crypto/build/native/hashtree/`.

- [ ] **Step 4: Verify exported symbols**

Run on macOS:

```bash
nm -gU infrastructure/crypto/build/native/hashtree/libhashtree.dylib | rg '_hashtree_(hash|init)$'
```

Run on Linux:

```bash
nm -D infrastructure/crypto/build/native/hashtree/libhashtree.so | rg 'hashtree_(hash|init)$'
```

Expected: exactly one exported `hashtree_hash` and one exported `hashtree_init`.

- [ ] **Step 5: Commit the build support**

```bash
git add infrastructure/crypto/build.gradle \
  infrastructure/crypto/src/main/native/hashtree/build-hashtree.sh
git commit -m "Add reproducible hashtree native build"
```

---

### Task 2: Checked FFM Batch Provider

**Files:**
- Create: `infrastructure/crypto/src/main/java/tech/pegasys/teku/infrastructure/crypto/BatchSha256.java`
- Create: `infrastructure/crypto/src/main/java/tech/pegasys/teku/infrastructure/crypto/HashtreeBatchSha256.java`
- Create: `infrastructure/crypto/src/main/java/tech/pegasys/teku/infrastructure/crypto/HashtreeBatchSha256Loader.java`
- Create: `infrastructure/crypto/src/test/java/tech/pegasys/teku/infrastructure/crypto/HashtreeBatchSha256Test.java`

**Interfaces:**
- Consumes: two non-overlapping native `MemorySegment` instances and a non-negative block count.
- Produces: `BatchSha256.hash64(MemorySegment input, MemorySegment output, long count)` and `HashtreeBatchSha256Loader.tryLoad(Path library)`.

- [ ] **Step 1: Write the failing provider-boundary tests**

Create tests using a no-op method handle so they do not need the native library:

```java
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
              () ->
                  hasher.hash64(
                      MemorySegment.ofArray(new byte[64]), arena.allocate(32), 1))
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
              () ->
                  hasher.hash64(
                      arena.allocate(64), MemorySegment.ofArray(new byte[32]), 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("native");
    }
  }

  @Test
  void rejectsOverflowingCount() {
    assertThatThrownBy(
            () -> hasher.hash64(MemorySegment.NULL, MemorySegment.NULL, Long.MAX_VALUE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(Long.toString(Long.MAX_VALUE));
  }

  @Test
  void rejectsOverlappingSegments() {
    try (Arena arena = Arena.ofConfined()) {
      final MemorySegment shared = arena.allocate(96);
      assertThatThrownBy(
              () ->
                  hasher.hash64(
                      shared.asSlice(0, 64), shared.asSlice(32, 32), 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("overlap");
    }
  }

  @Test
  void reportsMissingLibraryAsUnavailable(@TempDir final Path tempDir) {
    assertThat(HashtreeBatchSha256Loader.tryLoad(tempDir.resolve("missing-library")))
        .isEmpty();
  }

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
      throw new AssertionError(e);
    }
  }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:

```bash
./gradlew :infrastructure:crypto:test --tests '*HashtreeBatchSha256Test'
```

Expected: compilation fails because `BatchSha256` and `HashtreeBatchSha256` do not exist.

- [ ] **Step 3: Add the provider contract**

Create:

```java
package tech.pegasys.teku.infrastructure.crypto;

import java.lang.foreign.MemorySegment;

@FunctionalInterface
public interface BatchSha256 {
  void hash64(MemorySegment input, MemorySegment output, long count);
}
```

- [ ] **Step 4: Implement checked downcall invocation**

Implement `HashtreeBatchSha256` with this exact boundary:

```java
final class HashtreeBatchSha256 implements BatchSha256 {
  private static final long INPUT_BLOCK_SIZE = 64;
  private static final long OUTPUT_BLOCK_SIZE = 32;

  private final MethodHandle hashHandle;

  HashtreeBatchSha256(final MethodHandle hashHandle) {
    this.hashHandle = hashHandle;
  }

  @Override
  public void hash64(
      final MemorySegment input, final MemorySegment output, final long count) {
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
```

- [ ] **Step 5: Implement symbol loading and CPU initialization**

Create `HashtreeBatchSha256Loader` with:

```java
public final class HashtreeBatchSha256Loader {
  private static final FunctionDescriptor INIT_DESCRIPTOR =
      FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);
  private static final FunctionDescriptor HASH_DESCRIPTOR =
      FunctionDescriptor.ofVoid(
          ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);
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

  private static MemorySegment requiredSymbol(
      final SymbolLookup symbols, final String symbol) {
    return symbols
        .find(symbol)
        .orElseThrow(() -> new IllegalStateException("Missing native symbol: " + symbol));
  }
}
```

The process-lifetime `Arena.global()` is permitted only for the loaded library and method handles,
not for SSZ data or hash scratch.

- [ ] **Step 6: Run focused tests**

Run:

```bash
./gradlew :infrastructure:crypto:test --tests '*HashtreeBatchSha256Test'
```

Expected: all provider-boundary tests pass.

- [ ] **Step 7: Format and commit**

```bash
./gradlew :infrastructure:crypto:spotlessApply
./gradlew :infrastructure:crypto:test --tests '*HashtreeBatchSha256Test'
git add infrastructure/crypto/src/main/java \
  infrastructure/crypto/src/test/java
git commit -m "Add checked hashtree FFM binding"
```

Expected: formatting succeeds, focused tests pass, and the commit contains only the provider and
its unit tests.

---

### Task 3: Native Known-Answer And Concurrency Tests

**Files:**
- Modify: `infrastructure/crypto/build.gradle`
- Create: `infrastructure/crypto/src/test/java/tech/pegasys/teku/infrastructure/crypto/HashtreeBatchSha256NativeTest.java`

**Interfaces:**
- Consumes: `teku.hashtree.library` through the Gradle task and the provider from Task 2.
- Produces: `:infrastructure:crypto:hashtreeTest`, which always builds and exercises the host library.

- [ ] **Step 1: Write the native known-answer test**

For counts `{1, 2, 3, 4, 7, 8, 15, 16, 17, 64, 257}`, generate deterministic input, hash every
64-byte block with `MessageDigestFactory.createSha256()`, and compare all native output bytes:

```java
@ParameterizedTest
@ValueSource(ints = {1, 2, 3, 4, 7, 8, 15, 16, 17, 64, 257})
void matchesJca(final int count) {
  final byte[] inputBytes = new byte[Math.multiplyExact(count, 64)];
  new Random(1).nextBytes(inputBytes);
  final byte[] expected = jcaHash64(inputBytes, count);

  try (Arena arena = Arena.ofConfined()) {
    final MemorySegment input = arena.allocate(inputBytes.length);
    final MemorySegment output = arena.allocate(Math.multiplyExact(count, 32));
    input.copyFrom(MemorySegment.ofArray(inputBytes));
    hasher.hash64(input, output, count);
    assertThat(output.toArray(ValueLayout.JAVA_BYTE)).isEqualTo(expected);
  }
}
```

Load `hasher` from the absolute path in system property `teku.hashtree.library`. Add a test that
submits the same provider from at least eight threads, with each thread owning its confined arena,
and compares every result to JCA.

Use this setup and concurrency test:

```java
@BeforeAll
static void loadNativeHasher() {
  final String library = System.getProperty("teku.hashtree.library");
  assumeTrue(library != null, "Native hashtree library was not configured");
  hasher =
      HashtreeBatchSha256Loader.tryLoad(Path.of(library))
          .orElseThrow(() -> new AssertionError("Unable to load " + library));
  assertThat(
          HashtreeBatchSha256Loader.tryLoad(Path.of(library))
              .orElseThrow(() -> new AssertionError("Unable to reload " + library)))
      .isSameAs(hasher);
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
```

Implement `jcaHash64` by creating one SHA-256 `MessageDigest`, calling
`update(input, block * 64, 64)` followed by `digest()` for each block, and copying each digest into
the corresponding 32-byte output range.

- [ ] **Step 2: Run the test to verify the task is missing**

Run:

```bash
./gradlew :infrastructure:crypto:hashtreeTest
```

Expected: Gradle fails because task `hashtreeTest` does not exist.

- [ ] **Step 3: Add the dedicated native test task**

Append:

```groovy
tasks.register('hashtreeTest', Test) {
	dependsOn testClasses, buildHashtreeNative
	group = 'verification'
	description = 'Runs hashtree FFM tests against the locally built shared library.'
	testClassesDirs = sourceSets.test.output.classesDirs
	classpath = sourceSets.test.runtimeClasspath
	filter {
		includeTestsMatching(
				'tech.pegasys.teku.infrastructure.crypto.HashtreeBatchSha256NativeTest')
	}
	systemProperty(
			'teku.hashtree.library',
			hashtreeLibrary.get().asFile.absolutePath)
	jvmArgs '--enable-native-access=ALL-UNNAMED'
}
```

- [ ] **Step 4: Run the native tests**

Run:

```bash
./gradlew :infrastructure:crypto:hashtreeTest
```

Expected: all parameterized and concurrent known-answer tests pass with no JVM crash.

- [ ] **Step 5: Run the ordinary crypto tests without native build coupling**

Run:

```bash
./gradlew :infrastructure:crypto:test
```

Expected: ordinary tests pass without depending on `buildHashtreeNative`. The native test class
must use a JUnit assumption to skip when `teku.hashtree.library` is absent.

- [ ] **Step 6: Format and commit**

```bash
./gradlew :infrastructure:crypto:spotlessApply
./gradlew :infrastructure:crypto:hashtreeTest
git add infrastructure/crypto/build.gradle \
  infrastructure/crypto/src/test/java/tech/pegasys/teku/infrastructure/crypto/HashtreeBatchSha256NativeTest.java
git commit -m "Test hashtree native hashing"
```

---

### Task 4: Staging-Inclusive JMH Benchmarks

**Files:**
- Modify: `infrastructure/crypto/build.gradle`
- Create: `infrastructure/crypto/src/jmh/java/tech/pegasys/teku/infrastructure/crypto/NativeSha256BatchBenchmark.java`
- Create: `infrastructure/crypto/src/jmh/java/tech/pegasys/teku/infrastructure/crypto/NativeMerkleTreeBenchmark.java`

**Interfaces:**
- Consumes: `HASHTREE_LIBRARY`, `BatchSha256`, deterministic heap inputs, and confined or reusable native scratch.
- Produces: JMH JSON at `infrastructure/crypto/build/reports/jmh/hashtree-kernel.json`.

- [ ] **Step 1: Add the one-layer benchmark**

Use `@State(Scope.Thread)`, average time in microseconds, and
`@Param({"1", "2", "4", "8", "16", "64", "256", "1024", "4096", "16384"})`.
Implement these benchmark methods:

```java
@Benchmark
public void jcaCurrent(final Blackhole blackhole) {
  for (int i = 0; i < count; i++) {
    digest.update(heapInput, i * 64, 64);
    blackhole.consume(digest.digest());
  }
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
    final MemorySegment input = arena.allocate(Math.multiplyExact(count, 64));
    final MemorySegment output = arena.allocate(Math.multiplyExact(count, 32));
    input.copyFrom(MemorySegment.ofArray(heapInput));
    hasher.hash64(input, output, count);
    blackhole.consume(output.get(ValueLayout.JAVA_BYTE, 0));
  }
}
```

The trial setup loads the library from `HASHTREE_LIBRARY`, fills `heapInput` deterministically,
creates the reusable confined arena and segments, and checks one native result against JCA before
measurement. Trial teardown closes the reusable arena.

- [ ] **Step 2: Add the complete balanced-tree benchmark**

Use first-layer pair counts `@Param({"8", "32", "128", "512", "2048", "8192", "32768"})`.
Implement a JCA tree reducer with two heap arrays and a native reducer with two non-overlapping
segments:

```java
private MemorySegment nativeTree(
    MemorySegment input, MemorySegment output, long pairCount) {
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
```

Add `jcaTree`, `nativeTreeReusableScratch`, and `nativeTreeOperationArena`. Both native methods copy
the initial heap leaves into native memory and copy only the final 32-byte root back to heap.
Assert in setup that every configured pair count is a power of two and that native and JCA roots
match.

- [ ] **Step 3: Add the dedicated JMH task**

Append:

```groovy
tasks.register('hashtreeJmh', JavaExec) {
	dependsOn jmhClasses, buildHashtreeNative
	group = 'benchmark'
	description = 'Benchmarks JCA against the local hashtree FFM provider.'
	mainClass = 'org.openjdk.jmh.Main'
	classpath = sourceSets.jmh.compileClasspath + sourceSets.jmh.runtimeClasspath
	environment(
			'HASHTREE_LIBRARY',
			hashtreeLibrary.get().asFile.absolutePath)
	args(
			'tech.pegasys.teku.infrastructure.crypto.Native.*Benchmark',
			'-f', '2',
			'-wi', '3',
			'-i', '5',
			'-r', '1s',
			'-w', '1s',
			'-prof', 'gc',
			'-rf', 'json',
			'-rff',
			file("$buildDir/reports/jmh/hashtree-kernel.json").absolutePath)
	doFirst {
		file("$buildDir/reports/jmh").mkdirs()
	}
}
```

Annotate both benchmarks with:

```java
@Fork(value = 2, jvmArgsAppend = "--enable-native-access=ALL-UNNAMED")
```

- [ ] **Step 4: Compile the benchmarks**

Run:

```bash
./gradlew :infrastructure:crypto:jmhClasses
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run a smoke benchmark**

Run:

```bash
./gradlew :infrastructure:crypto:hashtreeJmh --args='NativeMerkleTreeBenchmark -p pairCount=128 -f 1 -wi 1 -i 1 -r 200ms -w 200ms'
```

Expected: all three tree methods produce results without an FFM warning or JVM crash.

- [ ] **Step 6: Run focused correctness and formatting**

```bash
./gradlew :infrastructure:crypto:spotlessApply \
  :infrastructure:crypto:test \
  :infrastructure:crypto:hashtreeTest \
  :infrastructure:crypto:jmhClasses
```

Expected: all tasks succeed.

- [ ] **Step 7: Commit the benchmarks**

```bash
git add infrastructure/crypto/build.gradle \
  infrastructure/crypto/src/jmh/java/tech/pegasys/teku/infrastructure/crypto/NativeSha256BatchBenchmark.java \
  infrastructure/crypto/src/jmh/java/tech/pegasys/teku/infrastructure/crypto/NativeMerkleTreeBenchmark.java
git commit -m "Benchmark hashtree FFM hashing"
```

---

### Task 5: Execute The Kernel Gate

**Files:**
- No source changes.
- Generated result: `infrastructure/crypto/build/reports/jmh/hashtree-kernel.json`

**Interfaces:**
- Consumes: Task 4 benchmarks on the current host.
- Produces: a path-specific proceed/stop decision for supernodes and packed payloads.

- [ ] **Step 1: Record the environment**

Run:

```bash
uname -a
./gradlew :infrastructure:crypto:javaToolchains
git rev-parse HEAD
git -C infrastructure/crypto/build/native/hashtree/source rev-parse HEAD
```

Expected: the Java toolchain report includes Java 25 and the native source reports
`30497cff98a06362eadde897202634f91d504fd8`. Confirm the full JMH output in Step 2 identifies its
benchmark fork as Java 25; the shell's default `java` is not evidence for the fork runtime.

- [ ] **Step 2: Run the full benchmark**

Run:

```bash
./gradlew :infrastructure:crypto:hashtreeJmh
```

Expected: JMH completes every configured parameter and writes JSON results.

- [ ] **Step 3: Check allocation and crossover**

For each parameter, compare:

- `nativeOnly` versus `jcaCurrent` to confirm the upstream kernel has an advantage.
- `nativeReusableScratch` versus `jcaCurrent` to include mandatory heap staging.
- `nativeOperationArena` versus `jcaCurrent` to price per-operation allocation.
- `nativeTreeReusableScratch` and `nativeTreeOperationArena` versus `jcaTree` for the decisive
  full-tree result.

Proceed with a supernode plan only when a staging-inclusive full tree is at least 20% faster for
first-layer pair counts between 128 and 2,048 on at least one common production platform.

Proceed with a packed-payload plan only when a staging-inclusive full tree is at least 20% faster
at pair counts of 4,096 or greater on at least one common production platform.

A local macOS ARM64 miss does not reject x86-64; it records ARM64 as Java-only pending Linux ARM64
and x86-64 measurements.

- [ ] **Step 4: Report the gate result**

Report the JMH score, error, and allocation rate for the crossover-adjacent sizes and state one of:

- stop both planner paths;
- continue supernodes only;
- continue payloads only;
- continue both paths.

Do not create the subsequent implementation plan until this decision is supported by the
staging-inclusive full-tree measurements.

## Completion Verification

Run:

```bash
./gradlew :infrastructure:crypto:spotlessCheck \
  :infrastructure:crypto:test \
  :infrastructure:crypto:hashtreeTest \
  :infrastructure:crypto:jmhClasses
git status --short
```

Expected: all Gradle tasks succeed. Only pre-existing unrelated worktree changes remain. The raw
JMH JSON exists under the crypto module's build reports and is not committed.
