# State Diff DAG Phase 1 (prune node) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace per-epoch full-state writes (`LATEST_FINALIZED_STATE`, `HOT_STATES_BY_ROOT`) with a DAG of tree-based state diffs keyed by checkpoint-block root, behind a hidden flag, on both RocksDB and LevelDB, for non-archive nodes.

**Architecture:** A new `infrastructure:state-diff` module holds the generic engine: a hash-guided walk of two SSZ backing trees producing a `TreeDiff` (changed leaves, supernode references, zeroed subtrees, copied subtrees, UInt64 delta sections), its codec, and the applier. The `storage` module adds two hot columns and one variable, a `StateDiffDag` that writes one node per epoch per branch with a level hierarchy [256, 64, 16, 1], reconstructs nodes by chain, reference-counts supernodes, and prunes; `KvStoreDatabase` routes hot-state writes, finalization and startup through it; `Store` persists the checkpoint-block state instead of the first block of the epoch.

**Tech Stack:** Java 25, Gradle, JUnit 5, AssertJ, tuweni `Bytes`, Teku `infrastructure:ssz` tree API (`TreeNode`, `TreeUpdates`, `SszSuperNode`, `SszNodeTemplate`, `GIndexUtil`, `TreeUtil`), RocksDB and LevelDB through `KvStoreAccessor`.

**Spec:** `docs/superpowers/specs/2026-10-22-state-diff-dag-design.md` (sections 5-8, 11-13). Decisions log: `docs/superpowers/plans/2026-10-22-state-diff-dag-decisions-log.md` (append implementation-stage calls there).

## Global Constraints

- `-Werror` is on: no unused imports, variables or parameters.
- Google Java format via Spotless. Run `./gradlew spotlessJavaCheck` and hand-fix; if you run `spotlessApply`, check `git status` afterwards for ` D ` lines (it has deleted modified test files before) and restore any.
- Never run two Gradle invocations concurrently.
- No `git commit` and no `git push` (repository rule). Each task ends by staging its files with `git add`.
- Declare parameters and locals `final`; fields `final` unless mutable by design.
- Use `SafeFuture`, never `.join()`/`.get()` on futures in production code.
- New hot column IDs are exactly 10 (`STATE_DIFF_NODES`) and 11 (`STATE_SUPERNODES`); new hot variable ID is exactly 13 (`STATE_DIFF_MODE`). Both columns are created with `containsStaticData = true`.
- Prune levels are exactly `[256, 64, 16, 1]` epochs. Format version byte is `1` for both the diff payload and the node record.
- Hidden CLI flag name is exactly `--Xdata-storage-state-diffs`.
- No application-level compression anywhere.
- Every reconstruction verifies the resulting `hashTreeRoot()` against the node's recorded state root and throws `StateDiffVerificationException` on mismatch.
- Spec-mapped methods are not touched by this plan; `ethspecify` is not affected.

## Review Focus

1. **A fork at a coarse boundary.** Two branches whose checkpoint blocks for a 16-aligned epoch differ: each must diff against its own ancestor and both must reconstruct. Test in Task 9 (`writesTwoBranchesAtCoarseBoundaryIndependently`).
2. **Restart with cold caches right after a snapshot.** The anchor is reconstructed with no level cache; the next node at every level must reconstruct its parent from the DAG. Test in Task 9 (`reconstructsParentsAfterRestart`).
3. **Checkpoint block spanning several empty epochs.** One block serves epochs E..E+3, one of which is 16-aligned; served range must be extended, level must be the max, and later nodes must still resolve parents. Test in Task 9 (`extendsServedEpochsAcrossEmptyEpochs`).
4. **Spec milestone change between a node and its parent.** The first node whose state milestone differs from its parent's must be a snapshot. Test in Task 9 (`forcesSnapshotAtMilestoneChange`).
5. **Supernode shared by a deleted orphan and a retained node.** Deleting the orphan must not remove the supernode. Test in Task 10 (`keepsSupernodeReferencedByRetainedNode`).

---

### Task 1: Module skeleton and varint codec

**Files:**
- Create: `infrastructure/state-diff/build.gradle`
- Modify: `settings.gradle` (add `include 'infrastructure:state-diff'` after `include 'infrastructure:ssz:generator'`)
- Create: `infrastructure/state-diff/src/main/java/tech/pegasys/teku/infrastructure/statediff/VarInts.java`
- Test: `infrastructure/state-diff/src/test/java/tech/pegasys/teku/infrastructure/statediff/VarIntsTest.java`

**Interfaces:**
- Produces: `public final class VarInts` with `static void writeUnsigned(ByteArrayOutputStream out, long value)`, `static long readUnsigned(ByteBuffer in)`, `static void writeSigned(ByteArrayOutputStream out, long value)` (zigzag then unsigned), `static long readSigned(ByteBuffer in)`, `static long zigzagEncode(long v)`, `static long zigzagDecode(long v)`. Unsigned LEB128, 7 bits per byte, little-endian groups, up to 10 bytes.

- [ ] **Step 1: Write `build.gradle`** with dependencies: `implementation project(':infrastructure:ssz')`, `implementation project(':infrastructure:bytes')`, `implementation project(':infrastructure:unsigned')`, `implementation 'io.consensys.tuweni:tuweni-bytes'`, `implementation 'it.unimi.dsi:fastutil'`, `testImplementation testFixtures(project(':infrastructure:ssz'))`. Add the `include` line to `settings.gradle`.
- [ ] **Step 2: Write the failing tests** `VarIntsTest`: `roundTripsUnsignedBoundaries` (0, 127, 128, 16383, 16384, `Long.MAX_VALUE`, -1L as unsigned max), `roundTripsSignedBoundaries` (0, 1, -1, 63, -64, 64, `Long.MIN_VALUE`, `Long.MAX_VALUE`), `zigzagKnownValues` (0→0, -1→1, 1→2, -2→3, 2147483647→4294967294L, -2147483648→4294967295L), `encodingSizes` (0 and 127 → 1 byte, 128 → 2 bytes).
- [ ] **Step 3: Run** `./gradlew :infrastructure:state-diff:test --tests '*VarIntsTest*'` — expected: compilation failure (class missing).
- [ ] **Step 4: Implement `VarInts`.**
- [ ] **Step 5: Run** the same command — expected: PASS.
- [ ] **Step 6: Stage** `git add settings.gradle infrastructure/state-diff`.

### Task 2: UInt64 delta codec

**Files:**
- Create: `infrastructure/state-diff/src/main/java/tech/pegasys/teku/infrastructure/statediff/UInt64DeltaCodec.java`
- Test: `infrastructure/state-diff/src/test/java/tech/pegasys/teku/infrastructure/statediff/UInt64DeltaCodecTest.java`

**Interfaces:**
- Consumes: `VarInts` (Task 1).
- Produces: `public final class UInt64DeltaCodec` with `static byte[] encode(long[] base, long[] target)` and `static long[] apply(long[] base, byte[] encoded)`. Encoding: `writeUnsigned(baseCount)`, `writeUnsigned(targetCount)`, then for `i < min(baseCount, targetCount)` `writeSigned(target[i] - base[i])` (wrapping arithmetic), then for `i in [baseCount, targetCount)` `writeUnsigned(target[i])`. `apply` throws `IllegalArgumentException` if `base.length != baseCount`. Shrinking (targetCount < baseCount) keeps the surviving prefix.

- [ ] **Step 1: Write the failing tests**: `roundTripsIdenticalArrays` (encoded length ≤ 2 + count bytes), `roundTripsGrowth` (base 3 → target 5), `roundTripsShrink` (base 5 → target 3), `roundTripsNegativeAndLargeDeltas` (values near `Long.MAX_VALUE` and wrap-around), `roundTripsEmptyToEmpty`, `rejectsBaseCountMismatch`, `randomRoundTrip` (seeded `Random(42)`, 200 iterations, lengths 0..1000, values `rng.nextLong()`).
- [ ] **Step 2: Run** `./gradlew :infrastructure:state-diff:test --tests '*UInt64DeltaCodecTest*'` — expected: FAIL (class missing).
- [ ] **Step 3: Implement `UInt64DeltaCodec`.** Port the arithmetic from `git show ssz-diff:infrastructure/state-diff/src/main/java/tech/pegasys/teku/infrastructure/statediff/UInt64DeltaDiff.java` but on `long[]`, no SSZ bytes, no format byte (the enclosing payload is versioned).
- [ ] **Step 4: Run** — expected: PASS.
- [ ] **Step 5: Stage** `git add infrastructure/state-diff`.

### Task 3: TreeDiff model and codec

**Files:**
- Create: `infrastructure/state-diff/src/main/java/tech/pegasys/teku/infrastructure/statediff/TreeDiff.java`
- Create: `infrastructure/state-diff/src/main/java/tech/pegasys/teku/infrastructure/statediff/TreeDiffCodec.java`
- Test: `infrastructure/state-diff/src/test/java/tech/pegasys/teku/infrastructure/statediff/TreeDiffCodecTest.java`

**Interfaces:**
- Produces: `public record TreeDiff(List<LeafEntry> leaves, List<SupernodeEntry> supernodes, List<ZeroedEntry> zeroed, List<CopiedEntry> copied, List<DeltaEntry> deltas)` with nested records `LeafEntry(long gIndex, Bytes data)`, `SupernodeEntry(int fieldIndex, long gIndex, int depth, Bytes32 hash)`, `ZeroedEntry(long gIndex, Bytes32 zeroRoot)`, `CopiedEntry(long targetGIndex, long sourceGIndex)`, `DeltaEntry(int fieldIndex, byte[] encodedDeltas)`; `static TreeDiff empty()`; `boolean isEmpty()`; `int entryCount()`.
- Produces: `public final class TreeDiffCodec` with `static Bytes serialize(TreeDiff diff)` and `static TreeDiff deserialize(Bytes bytes)`; throws `IllegalArgumentException` on unknown version. Layout: `u8 version=1`, then five sections in the order leaves, supernodes, zeroed, copied, deltas; each section starts with `writeUnsigned(count)`. Within leaves/zeroed, gIndexes are written sorted ascending as unsigned deltas from the previous gIndex (first from 0). Supernodes are written grouped: `writeUnsigned(groupCount)`, per group `writeUnsigned(fieldIndex)`, `writeUnsigned(count)`, entries with gIndex delta, `writeUnsigned(depth)`, 32 raw bytes. Copied: `writeUnsigned(targetGIndex)`, `writeUnsigned(sourceGIndex)`. Deltas: `writeUnsigned(fieldIndex)`, `writeUnsigned(length)`, raw bytes. Leaf data: `writeUnsigned(length)`, raw bytes.

- [ ] **Step 1: Write the failing tests**: `roundTripsEmptyDiff` (serialized size is 6 bytes: version + five zero counts), `roundTripsAllSections` (one entry of each kind with gIndexes 5, 7, 13 and a 40-byte leaf), `sortsLeavesByGIndex` (input unsorted, deserialized sorted), `rejectsUnknownVersion` (first byte 2), `roundTripsLargeGIndexes` (gIndex `1L << 40` and `Long.MAX_VALUE >>> 1`).
- [ ] **Step 2: Run** `./gradlew :infrastructure:state-diff:test --tests '*TreeDiffCodecTest*'` — expected: FAIL.
- [ ] **Step 3: Implement `TreeDiff` and `TreeDiffCodec`.**
- [ ] **Step 4: Run** — expected: PASS.
- [ ] **Step 5: Stage** `git add infrastructure/state-diff`.

### Task 4: TreeDiffer and TreeDiffApplier

**Files:**
- Create: `infrastructure/state-diff/src/main/java/tech/pegasys/teku/infrastructure/statediff/TreeDiffer.java`
- Create: `infrastructure/state-diff/src/main/java/tech/pegasys/teku/infrastructure/statediff/TreeDiffApplier.java`
- Create: `infrastructure/state-diff/src/main/java/tech/pegasys/teku/infrastructure/statediff/SupernodeSink.java`
- Create: `infrastructure/state-diff/src/main/java/tech/pegasys/teku/infrastructure/statediff/SupernodeSource.java`
- Create: `infrastructure/state-diff/src/main/java/tech/pegasys/teku/infrastructure/statediff/MissingSupernodeException.java`
- Create: `infrastructure/state-diff/src/main/java/tech/pegasys/teku/infrastructure/statediff/PackedUInt64Lists.java`
- Test: `infrastructure/state-diff/src/test/java/tech/pegasys/teku/infrastructure/statediff/TreeDifferTest.java`
- Test: `infrastructure/state-diff/src/test/java/tech/pegasys/teku/infrastructure/statediff/TreeDiffRoundTripPropertyTest.java`

**Interfaces:**
- Consumes: `TreeDiff`, `TreeDiffCodec` (Task 3), `UInt64DeltaCodec` (Task 2).
- Produces:
  - `@FunctionalInterface public interface SupernodeSink { void accept(Bytes32 hash, Bytes ssz); }`
  - `@FunctionalInterface public interface SupernodeSource { Optional<Bytes> load(Bytes32 hash); }`
  - `public class MissingSupernodeException extends RuntimeException` with `Bytes32 getHash()`.
  - `public final class PackedUInt64Lists` with `static long[] read(SszUInt64ListSchema<?> schema, TreeNode listNode)` and `static TreeNode write(SszUInt64ListSchema<?> schema, long[] values)`. Read through `schema.createFromBackingNode(listNode)` and `getElement(i).longValue()`; write through `schema.createTreeFromElements(List<SszUInt64>)`.
  - `public final class TreeDiffer` with constructor `TreeDiffer(SszContainerSchema<?> schema, IntSet deltaFieldIndices)` and `TreeDiff diff(TreeNode base, TreeNode target, SupernodeSink sink)`. Algorithm: for each field index `i` of the container: `fieldGIndex = schema.getChildGeneralizedIndex(i)`, `baseNode = base.get(fieldGIndex)`, `targetNode = target.get(fieldGIndex)`; skip if `baseNode.hashTreeRoot().equals(targetNode.hashTreeRoot())`. If `i ∈ deltaFieldIndices`: emit `DeltaEntry(i, UInt64DeltaCodec.encode(read(base list), read(target list)))`. Else if `targetNode.hashTreeRoot()` equals the root of some other field `j` of `base`: emit `CopiedEntry(fieldGIndex, schema.getChildGeneralizedIndex(j))`. Else recurse `diffSubtree(fieldIndex i, gIndex, baseNode, targetNode)`: if `TreeUtil.ZERO_TREES_BY_ROOT.containsKey(targetRoot)` emit `ZeroedEntry(gIndex, targetRoot)`; else if `targetNode instanceof SszSuperNode sn` emit `SupernodeEntry(i, gIndex, sn depth, root)` and `sink.accept(root, sn.getData())`; else if `targetNode instanceof LeafDataNode` emit `LeafEntry(gIndex, data)`; else (branch) recurse into left (`gIndex*2`) and right (`gIndex*2+1`) children, descending only where child roots differ; when `baseNode` is not a branch (shape mismatch), treat every target child as changed. `SszSuperNode` exposes `depth` through a new package-visible or public getter `getDepth()` added to `infrastructure/ssz/.../tree/SszSuperNode.java` (one-line addition, allowed).
  - `public final class TreeDiffApplier` with constructor `TreeDiffApplier(SszContainerSchema<?> schema, SupernodeSource supernodes)` and `TreeNode apply(TreeNode base, TreeDiff diff)`. Order: copied entries first (`base.get(sourceGIndex)` inserted at `targetGIndex`), then deltas (`PackedUInt64Lists.write` on the field's `SszUInt64ListSchema` obtained via `schema.getChildSchema(fieldIndex)`), then zeroed (`TreeUtil.ZERO_TREES_BY_ROOT.get(zeroRoot)`), then supernodes (`new SszSuperNode(depth, template, bytes)` where `template = SszNodeTemplate.createFromType(((SszCollectionSchema<?, ?>) schema.getChildSchema(fieldIndex)).getElementSchema())`, memoized per field index), then leaves (`LeafNode.create(data)`). Group updates by `GIndexUtil.gIdxGetDepth(gIndex)` and apply one `TreeUpdates` per depth, deepest first, since `TreeUpdates` rejects mixed depths. Snapshot application is `apply(schema.getDefaultTree(), diff)`.

- [ ] **Step 1: Write the failing tests** in `TreeDifferTest` using a synthetic `SszContainerSchema` built in the test with fields: `a: SszUInt64`, `b: SszBytes32`, `validators: SszListSchema.create(Validator-like ContainerSchema8 of 121 bytes, 1 << 20, SszSchemaHints.sszSuperNode(8))`, `balances: SszUInt64ListSchema.create(1 << 20)`, `flags: SszByteListSchema.create(1 << 20)`, `prev: SszByteListSchema.create(1 << 20)`; delta field = balances index. Tests: `identicalTreesProduceEmptyDiff`, `changedFixedFieldProducesOneLeaf`, `changedBalancesProduceDeltaSectionOnly`, `changedValidatorProducesOneSupernodeAndSinksBytes`, `rotatedListProducesCopiedEntry` (target.prev tree == base.flags tree), `clearedListProducesZeroedEntry`, `applyReproducesTargetRoot` for each of the above, `applyThrowsMissingSupernode` when the source returns empty, `snapshotFromDefaultTreeReproducesRoot`.
- [ ] **Step 2: Write the failing property test** `TreeDiffRoundTripPropertyTest.randomMutationSequencesRoundTrip`: seeded `Random(12345)`, 100 iterations, each applying 1..20 random mutations (set a, set b, set a validator field, append validators, set balances, append balances, set flag bytes, rotate prev←flags, clear flags) to the synthetic container, then `apply(base, decode(encode(diff)))` must have `hashTreeRoot()` equal to target, and after applying a chain of 5 consecutive diffs the final root must match.
- [ ] **Step 3: Run** `./gradlew :infrastructure:state-diff:test` — expected: FAIL.
- [ ] **Step 4: Implement** `SupernodeSink`, `SupernodeSource`, `MissingSupernodeException`, `PackedUInt64Lists`, `TreeDiffer`, `TreeDiffApplier`, and the `SszSuperNode.getDepth()` getter.
- [ ] **Step 5: Run** `./gradlew :infrastructure:state-diff:test :infrastructure:ssz:test` — expected: PASS.
- [ ] **Step 6: Stage** `git add infrastructure/state-diff infrastructure/ssz/src/main/java/tech/pegasys/teku/infrastructure/ssz/tree/SszSuperNode.java`.

### Task 5: Diff levels

**Files:**
- Create: `infrastructure/state-diff/src/main/java/tech/pegasys/teku/infrastructure/statediff/DiffLevels.java`
- Test: `infrastructure/state-diff/src/test/java/tech/pegasys/teku/infrastructure/statediff/DiffLevelsTest.java`

**Interfaces:**
- Produces: `public final class DiffLevels` with `static DiffLevels of(List<Long> periodsDescending)` (validates: non-empty, strictly descending, each divides the previous, last is 1; throws `IllegalArgumentException`), `static final DiffLevels PRUNE_DEFAULT = of(List.of(256L, 64L, 16L, 1L))`, `int levelCount()`, `long period(int level)`, `int levelOf(UInt64 epoch)` (0 = coarsest; the smallest index whose period divides the epoch), `int maxLevelInRange(UInt64 firstEpoch, UInt64 lastEpoch)` (the coarsest, i.e. smallest index, over the inclusive range), `boolean isCoarsest(int level)`.

- [ ] **Step 1: Write the failing tests**: `rejectsNonDividingPeriods` ([256, 100, 1]), `rejectsLastNotOne`, `levelOfAlignedEpochs` (0→0, 256→0, 64→1, 320→1, 16→2, 17→3), `maxLevelInRangeSpansBoundary` (range 15..17 → 2; 1..3 → 3; 250..260 → 0).
- [ ] **Step 2: Run** `./gradlew :infrastructure:state-diff:test --tests '*DiffLevelsTest*'` — expected: FAIL.
- [ ] **Step 3: Implement `DiffLevels`.**
- [ ] **Step 4: Run** — expected: PASS.
- [ ] **Step 5: Stage.**

### Task 6: Beacon state adapter and fuzz test against real states

**Files:**
- Modify: `storage/build.gradle` (add `implementation project(':infrastructure:state-diff')`; `testImplementation project(':infrastructure:state-diff')`)
- Create: `storage/src/main/java/tech/pegasys/teku/storage/server/kvstore/dataaccess/statediff/BeaconStateDiffSupport.java`
- Test: `storage/src/test/java/tech/pegasys/teku/storage/server/kvstore/dataaccess/statediff/BeaconStateDiffFuzzTest.java`

**Interfaces:**
- Consumes: `TreeDiffer`, `TreeDiffApplier`, `SupernodeSink`, `SupernodeSource` (Task 4).
- Produces: `public class BeaconStateDiffSupport` with constructor `BeaconStateDiffSupport(Spec spec)`, `TreeDiffer differFor(UInt64 slot)`, `TreeDiffApplier applierFor(UInt64 slot, SupernodeSource source)`, `BeaconStateSchema<?, ?> schemaFor(UInt64 slot)` (via `spec.atSlot(slot).getSchemaDefinitions().getBeaconStateSchema()`), `IntSet deltaFieldIndices(BeaconStateSchema<?, ?> schema)` (indices of `BeaconStateFields.BALANCES` and, when present, `BeaconStateFields.INACTIVITY_SCORES`, found with `schema.getFieldIndex(field)`), `boolean sameMilestone(UInt64 slotA, UInt64 slotB)`, `BeaconState diff...` is not provided: callers work on `state.getBackingNode()` and `schema.createFromBackingNode(tree)`. Differs and appliers are memoized per milestone.

- [ ] **Step 1: Write the failing fuzz test** `BeaconStateDiffFuzzTest` annotated `@TestSpecContext(milestone = {ELECTRA, FULU, GLOAS})` with `@TestTemplate` methods: `randomMutationsRoundTrip` (port the 12 mutation kinds from `git show ssz-diff:storage/src/test/java/tech/pegasys/teku/storage/server/kvstore/dataaccess/BeaconStateDiffFuzzTest.java`, 50 trials × 1..20 mutations on `dataStructureUtil.randomBeaconState(64)`; assert `applierFor(slot, map::get).apply(base.getBackingNode(), decode(encode(diff)))` hash equals target state root), `snapshotFromDefaultTreeRoundTrip`, `chainOfDiffsRoundTrip` (10 consecutive mutated states, each diffed against the previous, applied in sequence), `epochTransitionProducesOnlyDeltaAndSmallSections` (take a state from `ChainBuilder`, process to the next epoch with `spec.processSlots`, assert leaves count < 1000 and that balances appear only in the delta section). Supernodes captured in a `Map<Bytes32, Bytes>` by the sink.
- [ ] **Step 2: Run** `./gradlew :storage:test --tests '*BeaconStateDiffFuzzTest*'` — expected: FAIL.
- [ ] **Step 3: Implement `BeaconStateDiffSupport`** and the build.gradle lines.
- [ ] **Step 4: Run** — expected: PASS for all three milestones (Gloas exercises progressive lists).
- [ ] **Step 5: Stage.**

### Task 7: Node record and supernode value codecs

**Files:**
- Create: `storage/src/main/java/tech/pegasys/teku/storage/server/kvstore/dataaccess/statediff/StateDiffNodeRecord.java`
- Create: `storage/src/main/java/tech/pegasys/teku/storage/server/kvstore/dataaccess/statediff/StateDiffNodeRecordCodec.java`
- Create: `storage/src/main/java/tech/pegasys/teku/storage/server/kvstore/dataaccess/statediff/SupernodeValueCodec.java`
- Test: `storage/src/test/java/tech/pegasys/teku/storage/server/kvstore/dataaccess/statediff/StateDiffNodeRecordCodecTest.java`

**Interfaces:**
- Produces: `public record StateDiffNodeRecord(UInt64 blockSlot, Bytes32 stateRoot, UInt64 firstEpoch, UInt64 lastEpoch, int level, boolean snapshot, Bytes32 prevNodeRoot, Bytes32 parentNodeRoot, Bytes payload)` with `StateDiffNodeRecord withLastEpoch(UInt64)`; snapshots have `parentNodeRoot == Bytes32.ZERO`.
- Produces: `public final class StateDiffNodeRecordCodec` with `static Bytes encode(StateDiffNodeRecord)` and `static StateDiffNodeRecord decode(Bytes)`. Layout: `u8 version=1`, `u64 blockSlot` (big-endian), 32 stateRoot, `u64 firstEpoch`, `u64 lastEpoch`, `u8 level`, `u8 snapshot`, 32 prevNodeRoot, 32 parentNodeRoot, remaining bytes = payload. Fixed header so `withLastEpoch` can be applied by patching 8 bytes without re-encoding the payload: also provide `static Bytes patchLastEpoch(Bytes encoded, UInt64 lastEpoch)`.
- Produces: `public final class SupernodeValueCodec` with `static Bytes encode(int refCount, Bytes ssz)`, `static int refCount(Bytes value)`, `static Bytes ssz(Bytes value)`, `static Bytes withRefCount(Bytes value, int refCount)`. Layout: `u32 refCount` big-endian then ssz.

- [ ] **Step 1: Write the failing tests**: `roundTripsRecord`, `patchLastEpochOnlyChangesThatField`, `supernodeValueRoundTrip`, `withRefCountKeepsSsz`.
- [ ] **Step 2: Run** `./gradlew :storage:test --tests '*StateDiffNodeRecordCodecTest*'` — expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — expected: PASS.
- [ ] **Step 5: Stage.**

### Task 8: Schema columns, variable and DAO plumbing

**Files:**
- Modify: `storage/src/main/java/tech/pegasys/teku/storage/server/kvstore/schema/SchemaCombined.java` (add getters)
- Modify: `storage/src/main/java/tech/pegasys/teku/storage/server/kvstore/schema/V6SchemaCombined.java` (define columns 10, 11 with `containsStaticData=true`, variable 13; add to `getColumnMap()` as `"STATE_DIFF_NODES"`, `"STATE_SUPERNODES"` and `getVariableMap()` as `"STATE_DIFF_MODE"`)
- Modify: `storage/src/main/java/tech/pegasys/teku/storage/server/kvstore/schema/SchemaHotAdapter.java` (delegate getters; add to its column/variable maps)
- Modify: `storage/src/main/java/tech/pegasys/teku/storage/server/kvstore/dataaccess/KvStoreCombinedDao.java` (reader methods; `HotUpdater` methods)
- Modify: `storage/src/main/java/tech/pegasys/teku/storage/server/kvstore/dataaccess/V4HotKvStoreDao.java`, `CombinedKvStoreDao.java`, `KvStoreCombinedDaoAdapter.java` (implement)
- Test: `storage/src/test/java/tech/pegasys/teku/storage/server/kvstore/dataaccess/statediff/StateDiffDaoTest.java` (uses `MockKvStoreInstance` from storage testFixtures and `V6SchemaCombinedSnapshot.createV6(spec)`)

**Interfaces:**
- Produces on `SchemaCombined`: `KvStoreColumn<Bytes32, Bytes> getColumnStateDiffNodes()`, `KvStoreColumn<Bytes32, Bytes> getColumnStateSupernodes()`, `KvStoreVariable<Bytes> getVariableStateDiffMode()`.
- Produces on `KvStoreCombinedDao` (reader): `Optional<Bytes> getStateDiffNode(Bytes32 blockRoot)`, `Optional<Bytes> getStateSupernode(Bytes32 hash)`, `Optional<Bytes> getStateDiffMode()`.
- Produces on `KvStoreCombinedDao.HotUpdater`: `void putStateDiffNode(Bytes32 blockRoot, Bytes record)`, `void deleteStateDiffNode(Bytes32 blockRoot)`, `void putStateSupernode(Bytes32 hash, Bytes value)`, `void deleteStateSupernode(Bytes32 hash)`, `void setStateDiffMode(Bytes marker)`, `void deleteLatestFinalizedState()`.
- Serializers: raw `Bytes` with `BYTES_SERIALIZER` / `BYTES32_SERIALIZER` from `KvStoreSerializer` (check the existing constant names in `V6SchemaCombined` imports and reuse them).
- Any V4-era schema class that implements `SchemaCombined` and cannot host the columns (check `git grep "implements SchemaCombined"`) gets getters that throw `UnsupportedOperationException("State diff storage requires a V6 database")`.

- [ ] **Step 1: Write the failing tests** `StateDiffDaoTest`: `putAndGetNode`, `deleteNode`, `putAndGetSupernode`, `modeVariableRoundTrip`, `deleteLatestFinalizedStateRemovesVariable`, `columnIdsAreTenAndEleven` (assert `getColumnStateDiffNodes().getId()` equals the id `KvStoreColumn.create(10, ...)` would produce; see `KvStoreColumn.asColumnId`), `columnsAreStaticData`.
- [ ] **Step 2: Run** `./gradlew :storage:test --tests '*StateDiffDaoTest*'` — expected: FAIL.
- [ ] **Step 3: Implement** the schema and DAO changes.
- [ ] **Step 4: Run** `./gradlew :storage:test --tests '*StateDiffDaoTest*' --tests '*Schema*' --tests '*Dao*'` — expected: PASS, including any existing test that asserts column maps or ID uniqueness.
- [ ] **Step 5: Stage.**

### Task 9: StateDiffDag write, reconstruct and level cache

**Files:**
- Create: `storage/src/main/java/tech/pegasys/teku/storage/server/kvstore/dataaccess/statediff/StateDiffDagConfig.java`
- Create: `storage/src/main/java/tech/pegasys/teku/storage/server/kvstore/dataaccess/statediff/StateDiffDag.java`
- Create: `storage/src/main/java/tech/pegasys/teku/storage/server/kvstore/dataaccess/statediff/StateDiffDagTransaction.java`
- Create: `storage/src/main/java/tech/pegasys/teku/storage/server/kvstore/dataaccess/statediff/LevelBaseCache.java`
- Create: `storage/src/main/java/tech/pegasys/teku/storage/server/kvstore/dataaccess/statediff/StateDiffVerificationException.java`
- Create: `storage/src/main/java/tech/pegasys/teku/storage/server/kvstore/dataaccess/statediff/StateDiffMetrics.java`
- Test: `storage/src/test/java/tech/pegasys/teku/storage/server/kvstore/dataaccess/statediff/StateDiffDagTest.java`

**Interfaces:**
- Consumes: Tasks 5-8.
- Produces: `public record StateDiffDagConfig(boolean enabled, List<Long> levels, int baseCacheLevels)` with `static StateDiffDagConfig disabled()` and `static StateDiffDagConfig prune()` (= enabled, `[256, 64, 16, 1]`, cache all 4 levels).
- Produces: `public class StateDiffDag` with constructor `StateDiffDag(Spec spec, StateDiffDagConfig config, MetricsSystem metricsSystem)` and:
  - `Optional<BeaconState> reconstruct(KvStoreCombinedDao dao, Bytes32 blockRoot)`: follow `parentNodeRoot` to the snapshot, apply snapshot to `schemaFor(blockSlot).getDefaultTree()`, then each diff; verify `hashTreeRoot()` equals `stateRoot` at the end (throw `StateDiffVerificationException` with the chain roots in the message); record `storage_state_diff_reconstruct_seconds` and chain length. Returns empty when no node exists for the root.
  - `List<Bytes32> reconstructionChain(KvStoreCombinedDao dao, Bytes32 blockRoot)` (from the node up to and including the snapshot).
  - `StateDiffDagTransaction begin(KvStoreCombinedDao dao, KvStoreCombinedDao.HotUpdater updater)`.
- Produces: `public class StateDiffDagTransaction` with:
  - `void writeNode(Bytes32 blockRoot, BeaconState state, UInt64 firstEpoch, UInt64 lastEpoch, List<Bytes32> ancestorCheckpointRoots, boolean forceSnapshot)`. If a node for `blockRoot` exists: only `patchLastEpoch` when `lastEpoch` is greater, return. Otherwise `prev` = first root in `ancestorCheckpointRoots` that has a node (may be none → snapshot). `level = levels.maxLevelInRange(firstEpoch, lastEpoch)`. Parent = walk `prevNodeRoot` pointers from `prev` until a node with `level <= L` (coarser or equal) is found; none → snapshot. Snapshot also when `forceSnapshot` or when `!support.sameMilestone(state.getSlot(), parentRecord.blockSlot())`. Base tree = `LevelBaseCache.get(level, parentRoot)` or `reconstruct(parentRoot)` (cache miss metric). Diff with `differFor(state.getSlot())`, collecting supernodes into the transaction's pending map. Put the record; `cache.put(level, blockRoot, state)`; count `storage_state_diff_nodes_total{state=written}` and `storage_state_diff_node_bytes` by level/snapshot.
  - `void ensureNode(Bytes32 blockRoot, BeaconState state, UInt64 epoch, List<Bytes32> ancestorCheckpointRoots)`: `writeNode(..., epoch, epoch, ancestors, false)` only if absent.
  - `void deleteNodes(Collection<Bytes32> blockRoots)` and `void retainFinalizedChain(Bytes32 previousAnchorRoot, Bytes32 newAnchorRoot, boolean retainAll)` are Task 10.
  - `void commit()`: apply accumulated supernode reference-count changes: for each hash with net delta, read the current count via `dao.getStateSupernodeRefCount(hash)` (absent = 0); when the supernode bytes are absent and delta > 0, `putStateSupernode(hash, pendingBytes)`; new count = current + delta; if it reaches 0, `deleteStateSupernode(hash)` (removes bytes and count); otherwise `setStateSupernodeRefCount(hash, newCount)`. Throw `IllegalStateException` if a decrement targets a missing count or would go negative. (Refcounts live in hot column 13 `STATE_SUPERNODE_REFCOUNTS`, `Bytes32 -> UInt64`; column 11 holds immutable raw SSZ. Decision log entry 24.)
- Produces: `class LevelBaseCache` with `Optional<TreeNode> get(int level, Bytes32 root)`, `void put(int level, Bytes32 root, BeaconState state)` (stores `getBackingNode()`; one slot per level, replaces), `void clear()`.
- Produces: `class StateDiffMetrics` wrapping the counters/histograms named in spec section 11 under `TekuMetricCategory.STORAGE`.

- [ ] **Step 1: Write the failing tests** `StateDiffDagTest` on `MockKvStoreInstance.createEmpty(schema.getAllColumns(), schema.getAllVariables())` + `new CombinedKvStoreDao<>(db, schema, new V4FinalizedStateSnapshotStorageLogic<>(1), new StubMetricsSystem())` with `schema = V6SchemaCombinedSnapshot.createV6(spec)` and `TestSpecFactory.createMinimalElectra()` and a helper that builds a chain of states by `spec.processSlots` per epoch from `dataStructureUtil.randomBeaconState(32)` (states are deterministic per epoch so roots differ): `writesSnapshotForFirstNode`, `writesDiffAgainstPreviousEpoch`, `reconstructsEveryNodeInChainOf40Epochs` (levels 16 and 64 exercised; assert each reconstructed root equals the original and the chain length for epoch 39 is ≤ 1 + 3 + 7), `reconstructsParentsAfterRestart` (new `StateDiffDag` instance, cold cache, write epoch 41 and reconstruct), `writesTwoBranchesAtCoarseBoundaryIndependently` (two different states both served at epoch 16 with different roots, different ancestor lists), `extendsServedEpochsAcrossEmptyEpochs` (one root written for 15..15 then extended to 15..17; record lastEpoch 17; node at 18 resolves parent correctly), `forcesSnapshotAtMilestoneChange` (spec `TestSpecFactory.createMinimalWithFuluForkEpoch(UInt64.valueOf(4))`; the node whose state is in Fulu while its parent node's state is in Electra is a snapshot), `verificationFailureThrows` (corrupt a stored record's stateRoot byte, reconstruct throws `StateDiffVerificationException`), `commitWritesSupernodesWithRefCounts` (after two nodes referencing the same supernode, refcount is 2), `missingSupernodeFailsReconstruction`.
- [ ] **Step 2: Run** `./gradlew :storage:test --tests '*StateDiffDagTest*'` — expected: FAIL.
- [ ] **Step 3: Implement** the classes.
- [ ] **Step 4: Run** — expected: PASS.
- [ ] **Step 5: Stage.**

### Task 10: Pruning and retention

**Files:**
- Modify: `StateDiffDagTransaction.java` (add `deleteNodes`, `retainFinalizedChain`)
- Create: `storage/src/testFixtures/java/tech/pegasys/teku/storage/server/kvstore/dataaccess/statediff/StateDiffDagInvariants.java`
- Test: `StateDiffDagTest.java` (extend)

**Interfaces:**
- Produces: `void deleteNodes(Collection<Bytes32> blockRoots)`: for each existing node, decode, decrement every supernode in its payload, `deleteStateDiffNode`, count `nodes_total{state=deleted}`.
- Produces: `void retainFinalizedChain(Bytes32 previousAnchorRoot, Bytes32 newAnchorRoot, boolean retainAll)`: if `retainAll` return. Else `keep = reconstructionChain(newAnchorRoot)` as a set; candidates = `reconstructionChain(previousAnchorRoot)` ∪ nodes reached by following `prevNodeRoot` from `newAnchorRoot` until `previousAnchorRoot` (inclusive) or a missing node; `deleteNodes(candidates \ keep)`.
- Produces test fixture `StateDiffDagInvariants.assertConsistent(KvStoreCombinedDao dao)`: every supernode hash referenced by any stored node exists in column 11 and has a count in column 13; every count equals the number of nodes referencing that hash; no count is zero; no orphan bytes or orphan counts; every node's `parentNodeRoot` and `prevNodeRoot` (when non-zero) exist or the node is a snapshot. Enumerate nodes and supernodes with `KvStoreAccessor.stream(column)` through the DAO (add a reader method `Stream<ColumnEntry<Bytes32, Bytes>> streamStateDiffNodes()` and `Stream<ColumnEntry<Bytes32, UInt64>> streamStateSupernodeRefCounts()` to `KvStoreCombinedDao` and its implementations if Task 8 did not provide them).

- [ ] **Step 1: Write the failing tests**: `deleteNodesReleasesSupernodes`, `keepsSupernodeReferencedByRetainedNode`, `retainFinalizedChainKeepsOnlyAnchorChain` (chain of 40 epochs, anchors advance 5→20→39; after each, every node in `reconstructionChain(anchor)` exists, nodes strictly between older chain entries are gone, invariants hold), `retainFinalizedChainDropsOldSnapshotAfterNewOne` (levels `[4, 2, 1]` via config to keep the test small; anchor passes a new snapshot; old snapshot deleted), `retainAllKeepsEverything`.
- [ ] **Step 2: Run** `./gradlew :storage:test --tests '*StateDiffDagTest*'` — expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — expected: PASS.
- [ ] **Step 5: Stage.**

### Task 11: Configuration, CLI flag and factory threading

**Files:**
- Modify: `storage/src/main/java/tech/pegasys/teku/storage/server/StorageConfiguration.java` (field `StateDiffDagConfig stateDiffDagConfig`, builder `stateDiffsEnabled(boolean)`, getter `getStateDiffDagConfig()`; default disabled; when enabled and `dataStorageMode.storesFinalizedStates()` log WARN "state diffs with archive mode: archive states still use legacy storage")
- Modify: `teku/src/main/java/tech/pegasys/teku/cli/options/BeaconNodeDataOptions.java` (`@Option(names = {"--Xdata-storage-state-diffs"}, hidden = true, paramLabel = "<BOOLEAN>", fallbackValue = "true", arity = "0..1")` → `stateDiffsEnabled`, wired in `configure`)
- Modify: `storage/src/main/java/tech/pegasys/teku/storage/server/VersionedDatabaseFactory.java`, `rocksdb/RocksDbDatabaseFactory.java`, `leveldb/LevelDbDatabaseFactory.java`, `kvstore/KvStoreDatabase.java` (`createWithStateSnapshots`, `createWithStateTree`, `create`, constructor gain a `StateDiffDagConfig` parameter; `createV4` passes `disabled()`; V4/V5/LEVELDB1 with enabled config throw `DatabaseStorageException("State diff storage requires a V6 database")` in `VersionedDatabaseFactory`)
- Modify: `storage/src/testFixtures/.../storageSystem/FileBackedStorageSystemBuilder.java`, `InMemoryStorageSystemBuilder.java` (builder method `stateDiffsEnabled(boolean)` passed through), `storage/src/testFixtures/.../server/DatabaseContext.java` (field `stateDiffsEnabled`, passed to both builders), and `storage/src/testFixtures/java/tech/pegasys/teku/storage/server/TestDatabaseContext.java` (the extension behind `@TestDatabaseContext`) to emit, for each V6-family version (`V6`, `LEVELDB2`, `ROCKSDB_TREE`, `LEVELDB_TREE`), a second context with `stateDiffsEnabled = true`; `DatabaseContext.toString()` includes the flag so test names differ.
- Test: `teku/src/test/java/tech/pegasys/teku/cli/options/BeaconNodeDataOptionsTest.java` (add `stateDiffsFlagEnables` and `stateDiffsDefaultOff`), `storage/src/test/java/tech/pegasys/teku/storage/server/StorageConfigurationTest.java` or `VersionedDatabaseFactoryTest.java` (add `rejectsStateDiffsOnV5`).

**Interfaces:**
- Consumes: `StateDiffDagConfig` (Task 9).
- Produces: `StorageConfiguration.getStateDiffDagConfig()`; `KvStoreDatabase` constructor `KvStoreDatabase(KvStoreCombinedDao dao, StateStorageMode stateStorageMode, boolean storeNonCanonicalBlocks, Spec spec, StateDiffDagConfig stateDiffDagConfig, MetricsSystem metricsSystem)`.

- [ ] **Step 1: Write the failing tests** named above.
- [ ] **Step 2: Run** `./gradlew :teku:test --tests '*BeaconNodeDataOptionsTest*' :storage:test --tests '*VersionedDatabaseFactoryTest*'` — expected: FAIL.
- [ ] **Step 3: Implement** the threading. `KvStoreDatabase` stores `Optional<StateDiffDag>` built when `config.enabled()`; no behaviour change yet.
- [ ] **Step 4: Run** the two test commands plus `./gradlew :storage:compileIntegrationTestJava` — expected: PASS.
- [ ] **Step 5: Stage.**

### Task 12: KvStoreDatabase integration

**Files:**
- Modify: `storage/src/main/java/tech/pegasys/teku/storage/server/kvstore/KvStoreDatabase.java`
- Modify: `storage/src/main/java/tech/pegasys/teku/storage/server/Database.java` (add `boolean isStateDiffStorageActive()`)
- Modify: `storage/api/src/main/java/tech/pegasys/teku/storage/api/OnDiskStoreData.java` (add trailing component `boolean stateDiffStorageActive`; keep a compatibility constructor defaulting to false)
- Create: `storage/api/src/main/java/tech/pegasys/teku/storage/api/HotStateNodeInfo.java` (`public record HotStateNodeInfo(UInt64 firstEpoch, UInt64 lastEpoch, List<Bytes32> ancestorCheckpointRoots)`)
- Modify: `storage/api/src/main/java/tech/pegasys/teku/storage/api/StorageUpdate.java` (add `Map<Bytes32, HotStateNodeInfo> hotStateNodeInfos` after `hotStates`, with getter; included in `isEmpty` only through `hotStates`)
- Modify: `storage/src/main/java/tech/pegasys/teku/storage/store/StoreTransactionUpdatesFactory.java`, `StoreTransactionUpdates.java` (pass `Collections.emptyMap()` for the new field; real values come in Task 13)
- Test: `storage/src/integration-test/java/tech/pegasys/teku/storage/server/kvstore/DatabaseTest.java` (new `@TestTemplate` methods, skipped via `Assumptions.assumeTrue(context.isStateDiffsEnabled())` where diff-only)
- Test: `storage/src/test/java/tech/pegasys/teku/storage/server/kvstore/KvStoreDatabaseTest.java` if present (bootstrap unit test), else in `DatabaseTest`.

**Interfaces:**
- Consumes: `StateDiffDag`, `StateDiffDagTransaction` (Tasks 9-10), DAO methods (Task 8).
- Produces: in `KvStoreDatabase`:
  - `boolean isStateDiffStorageActive()`: true when `dao.getStateDiffMode()` is present (cached in a volatile after activation).
  - Activation: in `createMemoryStore`, when `stateDiffDag` is present, marker absent and `dao.getLatestFinalizedState()` present: one hot transaction writing a snapshot node for `dao.getFinalizedCheckpoint().getRoot()` (firstEpoch = lastEpoch = checkpoint epoch, `forceSnapshot = true`), `setStateDiffMode(Bytes.of(1))`, `deleteLatestFinalizedState()`; then `activate()`. In `storeInitialAnchor`, when `stateDiffDag` is present: write the anchor as a snapshot node, set the marker, do not call `setLatestFinalizedState`.
  - Startup: `createMemoryStore` reads the finalized state via `stateDiffDag.reconstruct(dao, finalizedCheckpoint.getRoot()).orElseThrow(() -> new IllegalStateException("..."))` when active; `getLatestFinalizedBlockSummary` likewise. `OnDiskStoreData.stateDiffStorageActive` set.
  - `getHotState(root)`: when active, `stateDiffDag.reconstruct(dao, root)` then fall back to `dao.getHotState(root)`.
  - `doUpdate`, hot section, when active: read `previousAnchorRoot = dao.getFinalizedCheckpoint().map(Checkpoint::getRoot)` before `setFinalizedCheckpoint`; `tx = stateDiffDag.begin(dao, updater)`; for each `(root, state)` in `update.getHotStates()`: `info = update.getHotStateNodeInfos().get(root)` (skip with a WARN if absent) then `tx.writeNode(root, state, info.firstEpoch(), info.lastEpoch(), info.ancestorCheckpointRoots(), false)`. Do **not** call `updater.addHotStates`. On finalization: `tx.ensureNode(checkpoint.getRoot(), latestFinalizedState, checkpoint.getEpoch(), List.of())` then `tx.retainFinalizedChain(previousAnchorRoot, checkpoint.getRoot(), stateStorageMode.storesFinalizedStates())`; skip `setLatestFinalizedState`. Orphans: `tx.deleteNodes(deletedHotBlocks.keySet() minus update.getFinalizedChildToParentMap().keySet())`. `tx.commit()` before `updater.commit()`. Timing goes into the existing `DB_LOGGER.onDbOpAlertThreshold` message as "state diff time".
  - When not active, behaviour is byte-for-byte unchanged.

- [ ] **Step 1: Write the failing integration tests** in `DatabaseTest` (all `@TestTemplate`, diff-enabled contexts only unless noted): `stateDiffs_activatesOnExistingDatabaseAndStopsWritingLatestFinalizedState` (create with flag off, add blocks and finalize, restart with flag on, assert `getStateDiffMode` present, `LATEST_FINALIZED_STATE` absent via `dao`/raw variable read, store recreated with the right anchor), `stateDiffs_freshDatabaseWritesAnchorSnapshot`, `stateDiffs_hotStateIsReconstructedForCheckpointBlock` (import 3 epochs of blocks from `chainBuilder`, `database.getHotState(checkpointRoot)` equals chain state), `stateDiffs_finalizationKeepsAnchorChainAndDropsOrphans` (fork, finalize past it, orphan node gone, `StateDiffDagInvariants.assertConsistent`), `stateDiffs_restartRecreatesStoreFromDag` (finalize, `recreateStore()`, `getLatestFinalized().getState()` root equals expected), `stateDiffs_doesNotWriteHotStatesColumn` (`dao.getHotState(root)` via raw column is empty for new roots). And one for both contexts: `legacyBehaviourUnchangedWhenDisabled` guarded by `assumeFalse(context.isStateDiffsEnabled())` asserting `LATEST_FINALIZED_STATE` is still written.
- [ ] **Step 2: Run** `./gradlew :storage:integrationTest --tests '*DatabaseTest*stateDiffs*'` — expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew :storage:integrationTest --tests '*DatabaseTest*' :storage:test` — expected: PASS for every context.
- [ ] **Step 5: Stage.**

### Task 13: Store integration

**Files:**
- Modify: `storage/src/main/java/tech/pegasys/teku/storage/store/StoreConfig.java` (field `boolean stateDiffsEnabled`, builder method; `getHotStatePersistenceFrequencyInEpochs()` returns 1 when enabled)
- Modify: `storage/src/main/java/tech/pegasys/teku/storage/store/StoreBuilder.java`, `Store.java` (field `stateDiffMode`; `getBlockStateIfAvailable` also consults `maybeEpochStates`; `createStateGenerationTask` captures the **parent** of the first block of the epoch as `latestEpochBoundary` when in diff mode, with slot from `forkChoiceStrategy.blockSlot(parent)`)
- Modify: `storage/src/main/java/tech/pegasys/teku/storage/client/StorageBackedRecentChainData.java` (set `StoreConfig.stateDiffsEnabled` from `OnDiskStoreData.stateDiffStorageActive()` when building the store; `StoreBuilder` reads it)
- Modify: `storage/src/main/java/tech/pegasys/teku/storage/store/StoreTransactionUpdatesFactory.java` (`getHotStatesToPersist` in diff mode returns `{R → state(R)}` for every tx block `B` with `isSlotAtNthEpochBoundary(B.slot, R.slot, 1)` where `R = B.parentRoot`, `state(R) = tx.getBlockStateIfAvailable(R)`, skipping with `LOG.warn` when absent; also builds `hotStateNodeInfos[R] = HotStateNodeInfo(firstEpoch, lastEpoch, ancestors)` where `firstEpoch = epoch(R.slot)` if `R.slot` is an epoch start slot else `epoch(R.slot) + 1`, `lastEpoch = epoch(B.slot)` (max over all such `B` in the tx), and `ancestors` is built by walking from `R`'s parent through tx blocks then `forkChoiceStrategy.processBeaconBlockChain`, collecting every block whose child's epoch is greater than its own epoch, ending with the finalized root. `StoreConfig.Builder.build()` logs WARN when `stateDiffsEnabled` and `hotStatePersistenceFrequencyInEpochs` differs from the default, since it is ignored)
- Test: `storage/src/test/java/tech/pegasys/teku/storage/store/StoreTransactionUpdatesFactoryTest.java` (new or extended), `StoreTest.java` (regeneration base), `storage/src/integration-test/.../DatabaseTest.java` (`stateDiffs_everyBlockStateRegeneratesAfterRestart`: 4 epochs with a fork, restart, `retrieveBlockState(root)` for every block equals `chainBuilder` state)

**Interfaces:**
- Consumes: `StorageUpdate.getHotStateNodeInfos()` and `HotStateNodeInfo` (Task 12), `OnDiskStoreData.stateDiffStorageActive()` (Task 12).
- Produces: `StoreConfig.Builder stateDiffsEnabled(boolean)`, `StoreConfig.isStateDiffsEnabled()`.

- [ ] **Step 1: Write the failing tests**: `StoreTransactionUpdatesFactoryTest.diffMode_persistsCheckpointBlockStateWithAncestors` (chain crossing two epochs, assert `hotStates` keyed by the parent of the first block of epoch 2 and ancestors list ends with finalized root), `diffMode_skipsWhenParentStateNotCached`, `legacyMode_unchanged`; `StoreTest.diffMode_regenerationBaseIsCheckpointBlock`; the `DatabaseTest` template above.
- [ ] **Step 2: Run** `./gradlew :storage:test --tests '*StoreTransactionUpdatesFactoryTest*' --tests '*StoreTest*'` — expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew :storage:test :storage:integrationTest` — expected: PASS.
- [ ] **Step 5: Stage.**

### Task 14: debug-db verification subcommand

**Files:**
- Modify: `teku/src/main/java/tech/pegasys/teku/cli/subcommand/debug/DebugDbCommand.java` (new `@Command(name = "verify-state-diffs")`)
- Modify: `storage/src/main/java/tech/pegasys/teku/storage/server/Database.java` and `KvStoreDatabase.java` (add `StateDiffVerificationReport verifyStateDiffs()`)
- Create: `storage/src/main/java/tech/pegasys/teku/storage/server/kvstore/dataaccess/statediff/StateDiffVerificationReport.java` (record: node count, supernode count, refcount mismatches list, missing supernodes list, anchor chain length, anchor reconstruction ok, anchor reconstruction millis)
- Test: `storage/src/test/java/.../statediff/StateDiffDagTest.java` (`verifyReportsConsistentDag`, `verifyReportsRefCountMismatch` by writing a tampered supernode value)

- [ ] **Step 1: Write the failing tests.**
- [ ] **Step 2: Run** — expected: FAIL.
- [ ] **Step 3: Implement** `StateDiffDag.verify(dao)` (scan both columns, recount, reconstruct anchor) and the subcommand printing the report, exit code 1 on any problem.
- [ ] **Step 4: Run** `./gradlew :storage:test --tests '*StateDiffDagTest*' :teku:compileJava` — expected: PASS.
- [ ] **Step 5: Stage.**

### Task 15: Whole-module verification

- [ ] **Step 1: Run** `./gradlew spotlessJavaCheck` — fix by hand.
- [ ] **Step 2: Run** `./gradlew :infrastructure:state-diff:test :storage:test :storage:integrationTest :teku:test` — expected: PASS.
- [ ] **Step 3: Run** `./gradlew assemble` — expected: BUILD SUCCESSFUL.
- [ ] **Step 4: Run** `./gradlew :storage:test --tests '*DatabaseCompatibilityTest*'` — expected: PASS (no schema regression for existing databases).
- [ ] **Step 5: Append** implementation-stage decisions to the decisions log; stage everything.

### Task 16: Network soak (manual, after Task 15)

- [ ] **Step 1:** `./gradlew installDist`; run `build/install/teku/bin/teku --network=sepolia --data-storage-mode=minimal --Xdata-storage-state-diffs --checkpoint-sync-url=<sepolia checkpoint>` with an EL endpoint or `--Xee-...` as available; watch `storage_state_diff_*` metrics and the `KvStoreDatabase::doUpdate` timing log for 3+ epochs; restart; confirm `verify-state-diffs` passes and startup reconstructs the anchor.
- [ ] **Step 2:** Same on mainnet; record per-epoch node bytes, write time, reconstruct time and the hot-DB on-disk growth in the decisions log.
