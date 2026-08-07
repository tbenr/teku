# Tiny-Transaction Payload Benchmark Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Compare current Teku SSZ hashing with tiled hashtree FFM hashing for a mainnet Fulu beacon block and a standalone Gloas execution payload containing up to 1,048,576 one-byte transactions.

**Architecture:** Add a benchmark-only streaming merkleizer to `eth-benchmark-tests`. It reads the real `SszPackedByteListsNode` or `SszPackedProgressiveByteListsNode`, computes transaction roots in bounded native tiles, and grafts the resulting list root into fresh real Fulu/Gloas backing trees. JMH measures cold transaction-list roots, cold whole-object roots, and deserialize-plus-hash without changing production SSZ behavior.

**Tech Stack:** Java 25 FFM, Teku SSZ backing trees, JMH 1.37, OffchainLabs hashtree pinned at `30497cff98a06362eadde897202634f91d504fd8`.

## Global Constraints

- Use mainnet schemas and exactly the same transaction count and one-byte values for Fulu and Gloas.
- Include `1,048,576`, the pre-Gloas `MAX_TRANSACTIONS_PER_PAYLOAD`.
- Cycle byte values through all 256 values; do not memoize repeated transaction roots.
- Keep native scratch bounded by a configurable power-of-two tile size, defaulting to 16,384 transaction roots.
- Include operation-scoped arena allocation and heap/native staging in native benchmark scores.
- Rebuild fresh objects before cold-root measurements so cached roots cannot satisfy an invocation.
- Verify every native list and whole-object root against current Teku hashing before measurement.
- Treat one-byte entries as valid SSZ values, not valid execution transactions.
- Do not wire the prototype into production hashing or native-library loading.

---

### Task 1: Tiled Tiny-Transaction Merkleizer

**Files:**
- Create: `eth-benchmark-tests/src/main/java/tech/pegasys/teku/benchmarks/ssz/TinyTransactionBatchMerkleizer.java`
- Test: `eth-benchmark-tests/src/test/java/tech/pegasys/teku/benchmarks/ssz/TinyTransactionBatchMerkleizerTest.java`

**Interfaces:**
- Consumes: `BatchSha256`, packed SSZ transaction bytes, fixed element/list depths, and a tile size.
- Produces:
  - `Bytes32 hashFixed(SszPackedByteListsNode node, int elementDepth, int listDepth)`
  - `Bytes32 hashProgressive(SszPackedProgressiveByteListsNode node)`
  - `long nativeScratchBytes()`

- [ ] **Step 1: Write fixed-list parity tests**

Create a pure-Java `BatchSha256` test implementation that hashes each 64-byte input block with
`MessageDigestFactory.createSha256()`. Deserialize cycling one-byte transactions through the actual
mainnet Fulu transactions schema and assert planner parity at counts
`1, 2, 4, 5, 16, 21, 341`.

- [ ] **Step 2: Run the fixed-list test and verify RED**

Run:

```bash
./gradlew :eth-benchmark-tests:test --tests '*TinyTransactionBatchMerkleizerTest.fixed*'
```

Expected: compilation fails because `TinyTransactionBatchMerkleizer` does not exist.

- [ ] **Step 3: Implement fixed one-byte transaction hashing**

Implement operation-scoped confined scratch with two non-overlapping segments:

```java
public final class TinyTransactionBatchMerkleizer {
  private static final int ROOT_SIZE = 32;
  private static final int PAIR_SIZE = 64;

  private final BatchSha256 hasher;
  private final int tileSize;
  private final Sha256 fallback = Hash.getSha256Instance();

  public TinyTransactionBatchMerkleizer(final BatchSha256 hasher, final int tileSize) {
    if (tileSize <= 0 || Integer.bitCount(tileSize) != 1) {
      throw new IllegalArgumentException("tileSize must be a positive power of two");
    }
    this.hasher = hasher;
    this.tileSize = tileSize;
  }
}
```

For every tile:

1. Read each byte from the packed variable section after the `count * 4` offset table.
2. Batch `hash(valueChunk, zeroRoot[0])`.
3. Batch-fold through the fixed transaction byte-list depth.
4. Batch the transaction length mix-in.
5. Pad only the outer-list tile with empty element roots and reduce it in place.

Combine tile roots with JCA at tile boundaries and fold zero subtrees to the mainnet list depth.
Mix in the transaction count last. Do not allocate storage proportional to the transaction count.

- [ ] **Step 4: Run fixed-list parity tests and verify GREEN**

Run the command from Step 2.

Expected: all fixed-list parity cases pass.

- [ ] **Step 5: Write progressive-list parity tests**

Use the actual mainnet Gloas transactions schema and counts
`1, 4, 5, 16, 21, 256, 341, 4_096, 5_461`. Assert the deserialized vector node is
`SszPackedProgressiveByteListsNode` and its Java root equals `hashProgressive`.

- [ ] **Step 6: Run the progressive test and verify RED**

Run:

```bash
./gradlew :eth-benchmark-tests:test --tests '*TinyTransactionBatchMerkleizerTest.progressive*'
```

Expected: test fails because progressive hashing is unsupported.

- [ ] **Step 7: Implement progressive element, level, and spine hashing**

For each transaction, batch `hash(valueChunk, EMPTY_LEAF)` followed by the length mix-in. For
progressive outer level `L`, hash the range beginning at
`L == 0 ? 0 : ProgressiveTreeUtil.cumulativeCapacity(L - 1)` to depth
`ProgressiveTreeUtil.levelDepth(L)`, padding only that level. Fold populated level roots from right
to left with `EMPTY_LEAF`, then mix in the transaction count.

- [ ] **Step 8: Run all merkleizer tests**

Run:

```bash
./gradlew :eth-benchmark-tests:test --tests '*TinyTransactionBatchMerkleizerTest'
```

Expected: all fixed and progressive roots match Teku.

- [ ] **Step 9: Commit**

```bash
git add eth-benchmark-tests/src/main/java/tech/pegasys/teku/benchmarks/ssz/TinyTransactionBatchMerkleizer.java \
  eth-benchmark-tests/src/test/java/tech/pegasys/teku/benchmarks/ssz/TinyTransactionBatchMerkleizerTest.java
git commit -m "Add tiled tiny transaction merkleizer"
```

---

### Task 2: Real Fulu Block And Gloas Payload Fixtures

**Files:**
- Create: `eth-benchmark-tests/src/main/java/tech/pegasys/teku/benchmarks/ssz/TinyTransactionPayloadFixture.java`
- Test: `eth-benchmark-tests/src/test/java/tech/pegasys/teku/benchmarks/ssz/TinyTransactionPayloadFixtureTest.java`

**Interfaces:**
- Consumes: mainnet Fulu/Gloas schemas, transaction count, and `TinyTransactionBatchMerkleizer`.
- Produces:
  - `FuluBlockFixture` with serialized block, fresh deserialization, Java list/block roots, and native list/block roots.
  - `GloasPayloadFixture` with serialized payload, fresh deserialization, Java list/payload roots, and native list/payload roots.

- [ ] **Step 1: Write fixture correctness tests**

For counts `1, 21, 341`, assert:

- Fulu deserializes to `SszPackedByteListsNode`.
- Gloas deserializes to `SszPackedProgressiveByteListsNode`.
- Both report the requested transaction count and cycling byte values.
- Native transaction-list and whole-object roots equal current Teku roots.

- [ ] **Step 2: Run fixture tests and verify RED**

Run:

```bash
./gradlew :eth-benchmark-tests:test --tests '*TinyTransactionPayloadFixtureTest'
```

Expected: compilation fails because the fixture does not exist.

- [ ] **Step 3: Implement allocation-conscious serialized inputs**

Build list SSZ directly in one `byte[]`:

```java
static Bytes oneByteTransactionsSsz(final int count) {
  final int dataOffset = Math.multiplyExact(count, Integer.BYTES);
  final byte[] serialized = new byte[Math.addExact(dataOffset, count)];
  for (int i = 0; i < count; i++) {
    final int offset = dataOffset + i;
    serialized[i * 4] = (byte) offset;
    serialized[i * 4 + 1] = (byte) (offset >>> 8);
    serialized[i * 4 + 2] = (byte) (offset >>> 16);
    serialized[i * 4 + 3] = (byte) (offset >>> 24);
    serialized[dataOffset + i] = (byte) i;
  }
  return Bytes.wrap(serialized);
}
```

Deserialize this through each real transactions schema. Create a Fulu payload by replacing the
transactions child of the default payload tree, place that payload in the default Fulu body, and
place the body in a real `BeaconBlock`. Create the Gloas payload by replacing the transactions child
of its default progressive-container tree.

- [ ] **Step 4: Implement native-root grafting**

Compute the native list root, replace the transactions child with
`LeafNode.create(nativeListRoot)`, and hash the updated parents:

- Fulu: execution payload, block body, then beacon block.
- Gloas: standalone execution payload.

Replacing at the list-root boundary must not materialize the packed transaction node.

- [ ] **Step 5: Run fixture tests and verify GREEN**

Run the command from Step 2.

Expected: all list and whole-object root comparisons pass.

- [ ] **Step 6: Commit**

```bash
git add eth-benchmark-tests/src/main/java/tech/pegasys/teku/benchmarks/ssz/TinyTransactionPayloadFixture.java \
  eth-benchmark-tests/src/test/java/tech/pegasys/teku/benchmarks/ssz/TinyTransactionPayloadFixtureTest.java
git commit -m "Add pathological payload fixtures"
```

---

### Task 3: JMH Workload And Native Task

**Files:**
- Modify: `eth-benchmark-tests/build.gradle`
- Create: `eth-benchmark-tests/src/jmh/java/tech/pegasys/teku/benchmarks/ssz/TinyTransactionPayloadBenchmark.java`

**Interfaces:**
- Consumes: `HASHTREE_LIBRARY`, both fixtures, fresh objects, and the tiled merkleizer.
- Produces: `eth-benchmark-tests/build/reports/jmh/tiny-transactions.json`.

- [ ] **Step 1: Add benchmark states and methods**

Use average time in milliseconds, one thread, Java 25 native access, and:

```java
@Param({"4", "5", "16", "21", "256", "341", "4096", "5461",
    "65536", "87381", "1048576"})
public int transactionCount;
```

Add separate Fulu and Gloas states with trial fixture creation and invocation-level fresh
deserialization. Benchmark:

- Current and native cold transaction-list roots.
- Current and native cold whole-object roots.
- Current and native deserialize-plus-hash.

Trial setup must compare current and native list/whole-object roots before JMH starts.

- [ ] **Step 2: Add `tinyTransactionsJmh`**

Register a `JavaExec` task depending on `jmhClasses` and
`:infrastructure:crypto:buildHashtreeNative`. Pass the built library through
`HASHTREE_LIBRARY`, enable native access, use a 4 GiB heap, enable the GC profiler, and write JSON to
`build/reports/jmh/tiny-transactions.json`.

- [ ] **Step 3: Compile and format**

Run:

```bash
./gradlew :eth-benchmark-tests:spotlessApply :eth-benchmark-tests:jmhClasses
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run a correctness smoke benchmark**

Run:

```bash
./gradlew :eth-benchmark-tests:tinyTransactionsJmh \
  --args='TinyTransactionPayloadBenchmark -p transactionCount=21 -f 1 -wi 1 -i 1 -r 200ms -w 200ms'
```

Expected: all Fulu and Gloas methods complete with matching setup roots and no FFM warning.

- [ ] **Step 5: Commit**

```bash
git add eth-benchmark-tests/build.gradle \
  eth-benchmark-tests/src/jmh/java/tech/pegasys/teku/benchmarks/ssz/TinyTransactionPayloadBenchmark.java
git commit -m "Benchmark pathological execution payloads"
```

---

### Task 4: Execute The Pathological Workload

**Files:**
- Generated: `eth-benchmark-tests/build/reports/jmh/tiny-transactions.json`
- Create: `docs/benchmarks/hashtree-ffm/2026-08-07-macos-arm64-tiny-transactions.md`
- Create: `docs/benchmarks/hashtree-ffm/2026-08-07-macos-arm64-tiny-transactions.json`

- [ ] **Step 1: Run crossover and maximum cases**

Run:

```bash
./gradlew :eth-benchmark-tests:tinyTransactionsJmh \
  --args='TinyTransactionPayloadBenchmark -p transactionCount=4096,5461,1048576 -f 2 -wi 3 -i 5 -r 1s -w 1s -prof gc -rf json -rff eth-benchmark-tests/build/reports/jmh/tiny-transactions.json'
```

- [ ] **Step 2: Analyze separately**

Report Fulu and Gloas separately for:

- Transaction-list cold root.
- Whole block or standalone payload cold root.
- Deserialize-plus-hash.
- JMH 99.9% error, `gc.alloc.rate`, and `gc.alloc.rate.norm`.
- Maximum native scratch from `nativeScratchBytes()`.

The whole-object gate is a native time at least 25% below current Teku. The workflow proxy gate is
deserialize-plus-hash at least 5% below current Teku.

- [ ] **Step 3: Preserve the raw output and summary**

Copy the JSON under `docs/benchmarks/hashtree-ffm`, write the environment, scores, and independent
Fulu/Gloas decision in the Markdown report, and commit only those files.

- [ ] **Step 4: Final verification**

Run:

```bash
./gradlew :eth-benchmark-tests:spotlessCheck \
  :eth-benchmark-tests:test \
  :eth-benchmark-tests:jmhClasses
git status --short
```

Expected: all tasks pass and only pre-existing unrelated worktree changes remain.
