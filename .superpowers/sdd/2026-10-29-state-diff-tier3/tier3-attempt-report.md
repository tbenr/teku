# Tier 3 attempt report: frozen exited-validator supernodes (2026-10-29)

Worktree `/Users/tbenr/teku/.claude/worktrees/state-diff-tier3`, branch
`worktree-state-diff-tier3` (base 2454573a3e). Everything is staged, nothing is
committed. Exploration, not a PR: the code is attempt quality (global counters,
a process-wide "any frozen node exists" switch, a static identity cache).

## TL;DR

- Coverage on the mainnet finalized state (epoch 483,376, 2,389,284
  validators): 65.1% of the registry is withdrawn and meets the freeze rule,
  but only **1,005 of 9,334 depth-8 supernodes (257,280 validators, 10.8%)**
  are entirely withdrawn. Granularity is the limit: 19.8% at depth 7, 30.4% at
  6, 40.1% at 5, 47.7% at 4, 53.7% at 3.
- Memory: a reconstructed state takes 607.8 MB plain and 579.0 MB frozen:
  **28.9 MB saved per distinct registry copy** (analytic content 29.7 MB), 4.8%.
- CPU, one empty-slot epoch transition (32 slots, Fulu, 16-core Mac):
  6,019 -> 5,617 ms cold (-6.7%), 5,293 -> 5,047 ms with a warm pubkey index
  (-4.6%; a second run measured 5,351 -> 4,815 ms, -10%). Parts, cold:
  `createValidatorStatuses` 3,187 -> 2,679 ms (-16%), `getActiveValidatorIndices`
  683 -> 563 ms (-18%). State roots equal.
- Loads: the fast paths load nothing; **one site loads every frozen
  supernode**: `ValidatorIndexCache.findIndexFromState` from
  `processPendingDeposits` on a state with a cold pubkey index (every freshly
  reconstructed state), 257,280 loads / 1,005 store reads per transition.
- The decisive negative: a full pubkey scan caches a `Validator` view per
  index in the list's view cache, and those views slice the loaded supernode
  content, so **the frozen content comes back into the heap and stays** for as
  long as that list view (and the views it hands its cache to) lives: +1,196 MB
  for plain and frozen alike after one scan.
- Consensus footprint: 7 methods in `ethereum/spec` plus a status flag and a
  helper; every fast path is a no-op without frozen nodes, equivalence tests
  pass on Electra, Fulu and Gloas.
- My read: not worth it at depth 8 (29 MB, ~5% epoch CPU, a cold-index load
  site that undoes the memory win). The question is whether a finer validator
  supernode depth is worth it on its own; see Alternatives.

## What was built

New files:

| file | what |
|---|---|
| `infrastructure/ssz/.../ssz/tree/FrozenSuperNode.java` | `TreeNode` + `LeafDataNode`; depth, template, element count, known root, `Supplier<Bytes>` loader; `SoftReference` memo; any non-trivial `updated` thaws into a plain `SszSuperNode`; `iterate` visits itself once. Attempt-only global `LongAdder` counters (loads incl. memo hits, memo hits, thaws), call-site recorder (`-Dteku.frozenSuperNode.recordLoadSites=N` or `setRecordLoadSites`, skips SSZ-internal and JDK frames), `anyCreated()` switch |
| `infrastructure/ssz/.../ssz/tree/FrozenRanges.java` | sorted disjoint `[start, end)` element ranges held by frozen nodes; `collect` iterates the list tree between the first and last element gIndex and maps a frozen node's first child gIndex to an element index by binary search over `getChildGeneralizedIndex`, so it works for bounded **and** progressive (Gloas) lists; `collectCached` (8-entry identity cache, weak keys); `Cursor` for rising-index scans; `replaceSupernodes` (tests and harness) |
| `infrastructure/state-diff/.../statediff/SupernodeFreezer.java` | hook called by `TreeDiffApplier` for every whole supernode reference (entry, template, loaded content), returns the node to place; `NONE` = today's `createWithKnownRoot` |
| `storage/.../statediff/ExitedValidatorFreezer.java` | the storage-side freezer: freezes a validators-field supernode when every validator has `effective_balance == 0`, `activation_epoch` set, `exit_epoch < E` and `withdrawable_epoch <= E` (E = epoch of the node reconstructed); offsets derived from `Validator.SSZ_SCHEMA`; eligibility memoized per hash (bounded LRU of 32k) as "eligible from epoch" |
| `ethereum/spec/.../logic/common/util/FrozenValidators.java` | `ranges(validators)`: `NONE` unless a frozen node ever existed, else the cached `FrozenRanges` |
| tests: `FrozenSuperNodeTest` (8), `FrozenSupernodeDiffTest` (5), `ExitedValidatorFreezerTest` (4), `FrozenValidatorsTest` (6 x Electra/Fulu/Gloas) | see Verification |
| `storage/src/integration-test/.../statediff/FrozenSupernodeHarness.java` | the P4 harness |

Changed files:

| file | change |
|---|---|
| `TreeDiffApplier` | `apply(..., freezer)` and `startChain(..., freezer)` overloads; `applySections` places `freezer.supernode(...)` for whole references; `patchedSupernode` accepts a frozen base (loads it, the patch thaws it) |
| `TreeDiffer` | frozen nodes compared like supernodes by root, depth and size, all known without loading (`isSupernode`, `supernodeDepth`, `supernodeSize`); `patch` checks depths and sizes before loading anything; new `diffLazy(base, target, SupernodeSink)` whose sink gets a `Supplier<Bytes>`, the old `diff(..., BiConsumer)` delegates |
| `PackedUInt64Lists` | chunk check treats a frozen node like a supernode |
| `StateDiffDag` | optional `ExitedValidatorFreezer` (config flag); `reconstruct(dao, ...)`, `reconstructAndCache` (hence `reconstructOrEmpty`) use it with a loader over the committed `dao.getStateSupernode`; transaction-internal reconstructions and `verify` never freeze |
| `StateDiffDagTransaction` | uses `diffLazy`; pending supernode contents are memoized suppliers, resolved only when inserting a supernode whose count is absent or when read as a pending source; so a snapshot written from a frozen tree loads nothing |
| `StateDiffDagConfig` | 4th component `freezeExited` (old constructors kept), `withFreezeExited` |
| `StorageConfiguration`, `BeaconNodeDataOptions` | hidden `--Xdata-storage-state-diffs-freeze-exited` (default false) |
| `ValidatorStatus` | private `frozen` flag, `ValidatorStatus.frozen()` canned status, `isFrozen()` |
| `AbstractValidatorStatusFactory` | fast path 1 |
| `BeaconStateAccessors` | fast path 2 |
| `AbstractEpochProcessor`, `EpochProcessorElectra` | fast paths 3 and 5 |
| `WithdrawalsHelpersCapella` | fast path 4 |

## Consensus sites touched

All read `FrozenValidators.ranges` (or the `isFrozen` status derived from it)
and are untouched code paths when nothing is frozen.

1. `AbstractValidatorStatusFactory.createInitialValidatorStatuses`: with frozen
   ranges, an ordered iterator hands frozen indices as `null` (not read) and
   the parallel map turns them into `ValidatorStatus.frozen()`: slashed false,
   withdrawable true, effective balance 0, withdrawable epoch 0, inactive in
   previous, current and next epoch.
   *Why exact*: the freeze rule checks `exit_epoch < E` and
   `withdrawable_epoch <= E` on the bytes (not from spec invariants), and
   E <= the current epoch C of every state holding the node (states only derive
   forward; a reconstruction freezes with its own node's epoch). So exit <= C-1,
   i.e. inactive at C-1, C, C+1; withdrawable <= C; effective balance 0 is read
   from the bytes and any write thaws. **Slashed**: only read as `slashed &&
   !withdrawableInCurrentEpoch` (rewards eligibility, false because withdrawable
   <= C) and `slashed && C + EPOCHS_PER_SLASHINGS_VECTOR/2 == withdrawable_epoch`
   (penalty; impossible since withdrawable <= C), so the canned `false` and `0`
   cannot change any result. Attester flags of an inactive validator are zero.
2. `BeaconStateAccessors.getActiveValidatorIndices` (cache miss): skips frozen
   ranges only for `epoch >= C - 1`, where the rule proves inactivity; older
   epochs read as before (tested).
3. `processRegistryUpdates` (`AbstractEpochProcessor` and `EpochProcessorElectra`)
   skip frozen statuses; the phase0 activation-queue filter too. Correct
   because effective balance 0 fails both queue predicates, ejection needs an
   active validator, activation needs `activation_epoch == FAR_FUTURE`, which
   the rule excludes.
4. `WithdrawalsHelpersCapella.processValidatorsSweepWithdrawals`: a frozen index
   whose balance after withdrawals is 0 is skipped without reading the
   validator but still counts toward `MAX_VALIDATORS_PER_WITHDRAWALS_SWEEP`, so
   `next_withdrawal_validator_index` is spec-equal. Both withdrawal kinds need a
   positive balance. (The check is in fact valid for any validator, frozen or
   not; gated on frozen ranges to keep it a no-op otherwise.)
5. Found by reading, not by the counter: `EpochProcessorElectra.processEffectiveBalanceUpdates`
   reads **every** validator every epoch (for `get_max_effective_balance`).
   For a frozen status it reads only when `balance > upward hysteresis
   threshold` (current effective balance 0 cannot decrease, and increases iff
   that holds since the maximum effective balance is never 0); a top-up thaws.
   Section 9 of the design assumed this method already avoids reads; that is
   true for phase0-Deneb only.

## Sites the load counter still shows

| site | loads / transition | why | fixable? |
|---|---|---|---|
| `ValidatorIndexCache.findIndexFromState` <- `ValidatorsUtil.getValidatorIndex` <- `EpochProcessorElectra.processPendingDeposits` | 257,280 (every frozen validator), 1,005 store reads, cold pubkey index only | the pubkey -> index cache of a state with fresh `TransitionCaches` is empty; the first pending deposit whose pubkey is not cached scans the whole registry reading pubkeys. Every state reconstructed from the DAG has fresh caches. A frozen range cannot be skipped: a top-up deposit to a withdrawn validator needs its index | only by supplying pubkeys without reading the registry views (a process-wide pubkey index, or reading raw bytes without caching views); not attempted |

With the index cache warm, a transition loads 0 frozen nodes and thaws 0. Not
exercised by this transition but known to load: REST validator queries and
full-state SSZ serialization (correct, slower), any operation addressed to a
frozen index (BLS change, deposit top-up: these thaw, as designed), the
`KvStoreTreeNodeStore` tree mode (stores via `getData`). Sync committee
selection and proposer lookahead read active candidates only.

## Measurements

Harness: `FrozenSupernodeHarness` on
`/private/tmp/claude-501/-Users-tbenr-teku/caeb5129-e57c-4d88-8d3f-eff177420cf2/scratchpad/state-483376.ssz`
(slot 15,468,032, epoch 483,376, Fulu, root `0xe8f6...70f6`), mainnet spec,
-Xmx12g, macOS 16 cores, 64 GB. Both variants are rebuilt the way the DAG does
it: snapshot diff of the state, supernodes to a file store (one fresh array per
read), snapshot applied to the default tree with `SupernodeFreezer.NONE` or
the `ExitedValidatorFreezer` (frozen content read back from the file).

### Coverage

| depth | per supernode | supernodes | eligible | validators covered | % registry | % eligible | MB |
|---|---|---|---|---|---|---|---|
| 8 (today) | 256 | 9,334 | 1,005 | 257,280 | 10.8 | 16.5 | 29.7 |
| 7 | 128 | 18,667 | 3,705 | 474,240 | 19.8 | 30.5 | 54.7 |
| 6 | 64 | 37,333 | 11,348 | 726,272 | 30.4 | 46.7 | 83.8 |
| 5 | 32 | 74,666 | 29,933 | 957,856 | 40.1 | 61.6 | 110.5 |
| 4 | 16 | 149,331 | 71,257 | 1,140,112 | 47.7 | 73.3 | 131.6 |
| 3 | 8 | 298,661 | 160,419 | 1,283,352 | 53.7 | 82.5 | 148.1 |

1,555,506 validators (65.1%) are withdrawn with zero effective balance and all
of them meet the whole rule (the extra `exit < E` and `activation set`
conditions exclude nobody), so 65.1% / 179.6 MB is the upper bound of any
freezing unit. Exits are spread over all index deciles (62-86% eligible in
deciles 0-7, 40% in 8, 16% in 9), which is why whole 256-blocks are rare. The
coordinator's numbers from the head state (2,394,880 validators: 11.0% at 256,
20.2% / 30.8% / 40.6% / 48.2% / 54.4% at 128 ... 8) agree within 0.7 points.

### Memory (used heap after full GC)

| | MB |
|---|---|
| (a) plain reconstructed state | 607.8 |
| (b) frozen reconstructed state | 579.0 |
| saved, (a) - (b) | **28.9** (run 2: 31.3) |
| analytic frozen content | 29.7 |
| heap added by one full pubkey scan (cached validator views), plain | 1,196.4 |
| same, frozen (memos dropped before measuring) | 1,196.5 |

Per distinct registry copy. A running node's hot states, anchor and base-cache
trees derived from one reconstruction share the frozen nodes by identity
(states derive by `updated`, the base cache keeps written trees), so the
saving is **not multiplied** by the number of states: it applies once per
independently reconstructed tree, i.e. once or a few times. The 1,196 MB line
is the real story: the list view cache keeps one `Validator` view per index
read, each backed by an `SszSuperNode(0, ...)` slice of the loaded content,
so after a full scan (cold pubkey index, REST listing) the frozen content is
pinned again for the life of that view chain, and the 29 MB saving is gone.
(That a cold full scan costs 1.2 GB of view cache is itself worth a look,
independently of tier 3.)

### CPU

| | plain median / p90 ms | frozen median / p90 ms | loads, store reads, thaws per run |
|---|---|---|---|
| epoch transition, cold (fresh state per run) | 6,019 / 6,171 | 5,617 / 6,188 | 257,280, 1,005, 0 |
| epoch transition, warm pubkey index (reused pre-state) | 5,293 / 5,400 | 5,047 / 5,403 | 0, 0, 0 |
| (run 2, same setup) cold | 6,086 / 6,243 | 5,759 / 6,219 | 257,280, 1,005, 0 |
| (run 2) warm | 5,351 / 5,384 | 4,815 / 4,897 | 0, 0, 0 |
| `createValidatorStatuses`, cold | 3,187 / 3,272 | 2,679 / 2,863 | 0, 0, - |
| `getActiveValidatorIndices`, cold | 683 / 697 | 563 / 682 | 0, 0, - |
| `FrozenRanges.collect`, uncached | 0.20 | 0.43 | - |

5 warm-up + 10 measured runs each; post-transition roots equal
(`0x749e3d8f...5734`); 257,280 validators still frozen after the transition.
Run-to-run noise is ~5%, so the warm transition gain is 5-10%. `collect`
costs under half a millisecond, so the identity cache is not needed.

### Harness command

```bash
# from the worktree; the init script raises the integrationTest heap to 12g and sets log4j to INFO
TEKU_TIER3_STATE_FILE=/path/to/state.ssz TEKU_TIER3_OUT=/path/to/out.md \
  ./gradlew -I tier3-heap.gradle :storage:integrationTest \
  --tests "tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff.FrozenSupernodeHarness"
```

`tier3-heap.gradle` (outside the repo):

```groovy
allprojects {
  tasks.withType(Test).configureEach {
    if (name == 'integrationTest') {
      maxHeapSize = '12g'
      testLogging.showStandardStreams = true
      outputs.upToDateWhen { false }
      systemProperty 'log4j2.configurationFile', '/path/to/log4j2-info.xml' // root INFO
    }
  }
}
```

On the AWS boxes: stop the node or use a copy, dump a state with
`teku debug-tools db get-latest-finalized-state` / `get-finalized-state`
(or `GET /eth/v2/debug/beacon/states/finalized` with `Accept:
application/octet-stream`) and run the same command on it
(`TEKU_TIER3_NETWORK` selects another network). The harness does not open the
database itself; a DB mode (reconstruct the anchor through `StateDiffDag` with
the flag on) would be ~30 lines if wanted.

## Gaps and risks

1. **Cold pubkey index loads everything** (above) and **view caches pin loaded
   content**: together they make the memory gain disappear on the first
   transition after any reconstruction unless the pubkey lookup stops reading
   registry views.
2. **When freezing happens**: only in `reconstruct`/`reconstructAndCache` with
   the flag. A checkpoint-synced fresh node (anchor from SSZ) freezes nothing
   until a restart or a DAG read; supernodes changed by a patch since the last
   snapshot are plain until a reconstruction from a later snapshot (patches are
   inline, their content is not in `STATE_SUPERNODES`, so they must not freeze).
3. **Pruning**: a frozen node is created only from a whole reference, so at
   creation its hash is counted by a link of the reconstructed node's chain.
   While the reconstructed node (or any retained node whose chain shares that
   link) is retained, the bytes stay. After that, live trees derived from it
   keep the frozen instance; newer nodes diff against bases holding the same
   hash and emit no entry for it, so their chains count it only if their
   snapshot (or a whole reference in their chain) does. Periodic snapshots
   re-reference every unchanged supernode whole, and every retained chain ends
   at a snapshot, so in the normal case the latest snapshot keeps the hash.
   **Hole** (theoretical, not fixed): if a retained chain reaches the content
   at that position through a *patch* (inline) while the frozen instance came
   from another chain's *whole* reference, and that other chain is pruned
   before a snapshot re-references the hash, the bytes are deleted under a
   live frozen node. That needs the patch-vs-reference decision to differ for
   the same target content from two different bases (e.g. a coarse-level node
   seeing >= 254 changes where a fine one saw a few). Failure is loud: the
   loader throws `IllegalStateException("Frozen supernode ... is no longer in
   the supernode column")`, and a snapshot commit needing the content throws
   too. A real fix would pin frozen hashes (an in-memory refcount the
   transaction respects) or force whole references for frozen positions.
4. **Progressive lists (Gloas)**: no wall. `FrozenRanges` maps by binary search
   over the schema's element gIndices, so the varying supernode depths of the
   progressive levels (element 0 alone, then 4, 16, 64, 256, 256...) work; the
   freezer works per whole reference regardless of depth. Equivalence tests
   pass on Gloas (frozen range `[1, 341)` for 512 withdrawn of 768). Element 0
   is never frozen (plain node).
5. **Process-wide state**: `FrozenSuperNode.anyCreated()` turns every fast
   path from free to "one 0.2-0.4 ms tree walk" for the rest of the process
   once any frozen node exists (tests in the same JVM included); the counters
   and the identity cache are static. Attempt-only.
6. The withdrawal sweep fast path is block processing; the harness's empty
   slots do not measure it (equivalence tested on minimal).
7. `loads` counts memo hits; `store reads` is the real I/O.
8. Not done: thaw metrics in the node, a DB mode for the harness, the
   `verify-state-diffs` interaction (it never freezes).

## Is it worth it?

No, not in this form. At depth 8 freezing covers 10.8% of the registry and
saves ~29 MB of a ~600 MB state once per distinct reconstruction, plus
5-10% of an epoch transition (0.25-0.5 s). Against that: seven consensus
methods carry fast paths whose correctness rests on a storage-side byte rule
(clean, tested, but a permanent coupling between `ethereum/spec` and the
state diff store), the pubkey index makes every fresh state load all frozen
nodes, and any full read pins the content back through the view cache. The
CPU part is better obtained directly: the Electra effective-balance loop
reading all 2.4M validators every epoch and the cold status creation (3.2 s,
dominated by building 2.4M views) are costs of every node, frozen or not.

## Alternatives

- **Finer validator supernodes** (the main lever, per the coordinator): depth 5
  quadruples coverage (40.1%, 110 MB content), depth 4 gives 47.7% (132 MB).
  Costs, estimated: per supernode object ~100-150 B (node, `Bytes` wrapper,
  array header, cached root) plus one branch node (~80 B with its cached hash)
  per extra level: depth 5 has 74,666 supernodes vs 9,334, i.e. ~15 MB more
  per distinct copy, small next to the content; hashing a changed supernode
  gets cheaper (32 validators instead of 256, plus 3 branch hashes); a snapshot
  carries 8x more whole references (~2.5 MB of hashes instead of ~0.3 MB) and
  `STATE_SUPERNODES`/refcount rows grow 8x (per-key RocksDB overhead
  ~4-6 MB); patches are unchanged per validator. It changes the tree shape
  of every state (hint is a schema property), so it needs a payload/format
  marker bump and a re-sync of DAG databases, and its effect on tree-mode
  storage and `SszSuperNode` read paths should be measured. Even at depth 4
  the ceiling is ~130 MB per copy and still subject to gap 1.
- **A different unit for partially withdrawn supernodes**: a "sparse
  supernode" that keeps only its live validators' bytes plus a bitmap of
  frozen slots (content of frozen slots in the store under the supernode
  hash) would reach the 65% / 180 MB upper bound without changing the tree
  shape; the fast paths stay the same (ranges become per-element), the
  differ and applier treat it like a frozen node. More code in the SSZ layer.
- **Fix the cold paths first, independent of tier 3**: a process-wide
  pubkey -> index map (pubkeys never change for an index) instead of a per-
  state cold scan, and not caching views for whole-list scans; both help
  every reconstructed state and are prerequisites for any freezing to keep
  its memory.
- **Not doing it**: the DAG works without it; the heap win is small next to
  the 0.7 GB the base cache costs (decision 42) and the 1.2 GB a full view
  scan costs.

## Verification

- `./gradlew :infrastructure:ssz:test :infrastructure:state-diff:test :storage:test :ethereum:spec:test :teku:compileJava :storage:compileIntegrationTestJava --continue`:
  BUILD SUCCESSFUL; ssz 28,318 tests (0 failures), state-diff 127, storage
  1,073, spec 2,135, all 0 failures.
- New tests: `FrozenSuperNodeTest` 8/8 (hash without load, get equals and
  memo, updated thaws, iterate once, frozen list root/elements/SSZ, ranges of a
  bounded list incl. merge of adjacent and a short last supernode, empty
  ranges, progressive list ranges); `FrozenSupernodeDiffTest` 5/5 (frozen
  reconstruction root, byte-identical diff payload against frozen and plain
  base with exactly 1 load for the one changed supernode, 0 loads when no
  supernode changed, derived-state diff + apply, snapshot of a frozen tree with
  0 loads); `ExitedValidatorFreezerTest` 4/4 (DAG reconstruction freezes and
  equals, not frozen when withdrawable after the node epoch, diff node written
  from a thawed frozen state reconstructs, eligibility rule);
  `FrozenValidatorsTest` 18/18 (6 tests x Electra, Fulu, Gloas: ranges, epoch
  transition equal with 0 loads, statuses equal field by field, active indices
  equal with 0 loads and the old-epoch fallback, withdrawal sweep across a
  frozen boundary equal with 0 loads, top-up thaws and stays equal).
- `./gradlew spotlessJavaCheck`: clean (formatting done with
  google-java-format 1.35.0 on the touched files, not `spotlessApply`).
- Harness run output: below.

## Harness output (final run)

```
# Tier 3 harness

State state-483376.ssz, slot 15468032, epoch 483376, root 0xe8f67ffe33930961df4aabc0b9bebb007090d00267b3cbc5b428a48694cb70f6, 2389284 validators, loaded and hashed in 2.1 s
Milestone FULU; transition target slot 15468064

## Coverage

2,389,284 validators; 1,555,506 withdrawn (effective balance 0, withdrawable epoch passed, 65.1%); 1,555,506 meet the whole freeze rule (65.1%), the upper bound of any freezing unit

| supernode depth | validators each | supernodes | eligible | validators covered | % of registry | % of eligible | bytes covered MB |
|---|---|---|---|---|---|---|---|
| 8 | 256 | 9,334 | 1,005 | 257,280 | 10.8 | 16.5 | 29.7 |
| 7 | 128 | 18,667 | 3,705 | 474,240 | 19.8 | 30.5 | 54.7 |
| 6 | 64 | 37,333 | 11,348 | 726,272 | 30.4 | 46.7 | 83.8 |
| 5 | 32 | 74,666 | 29,933 | 957,856 | 40.1 | 61.6 | 110.5 |
| 4 | 16 | 149,331 | 71,257 | 1,140,112 | 47.7 | 73.3 | 131.6 |
| 3 | 8 | 298,661 | 160,419 | 1,283,352 | 53.7 | 82.5 | 148.1 |

| index decile | validators | freeze-eligible | eligible % |
|---|---|---|---|
| 0 | 0-238,927 | 199,221 | 83.4 |
| 1 | 238,928-477,855 | 189,637 | 79.4 |
| 2 | 477,856-716,784 | 184,627 | 77.3 |
| 3 | 716,785-955,712 | 166,146 | 69.5 |
| 4 | 955,713-1,194,641 | 204,388 | 85.5 |
| 5 | 1,194,642-1,433,569 | 188,989 | 79.1 |
| 6 | 1,433,570-1,672,497 | 147,838 | 61.9 |
| 7 | 1,672,498-1,911,426 | 140,354 | 58.7 |
| 8 | 1,911,427-2,150,354 | 96,132 | 40.2 |
| 9 | 2,150,355-2,389,283 | 38,174 | 16.0 |

Snapshot payload 15,101,212 bytes; 9,334 whole supernode references, 289,103,364 bytes of supernodes stored

## Memory (used heap after full GC, reconstructed from the snapshot)

Frozen at reconstruction: 1,005 supernodes, 257,280 validators (601 ranges); the reconstruction read 9,334 supernodes from the store
| | heap MB |
|---|---|
| baseline (payload, spec, store index) | 132.6 |
| (a) plain state, over baseline | 607.8 |
| (b) frozen state, over baseline | 579.0 |
| (a) and (b) together, over baseline | 1186.3 |
| saved by freezing, (a) - (b) | 28.9 |
| analytic: frozen content bytes | 29.7 |

## CPU

FrozenRanges.collect (uncached) on the plain validators: median 0.20 ms, p90 0.21 ms
FrozenRanges.collect (uncached) on the frozen validators: median 0.43 ms, p90 0.47 ms

### Epoch transition, cold: a fresh state instance (empty caches) per run

plain: processSlots median 6019 ms, p90 6171 ms (10 measured runs after 5 warm-up)
frozen: processSlots median 5617 ms, p90 6188 ms; per run: loads [257280, 257280, 257280, 257280, 257280, 257280, 257280, 257280, 257280, 257280] (median 257280), store reads median 1005, thaws median 0
Load call sites of the last run (1 distinct, top frames):
257,280 loads
    at tech.pegasys.teku.spec.datastructures.state.beaconstate.common.ValidatorIndexCache.findIndexFromState(ValidatorIndexCache.java:86)
    at tech.pegasys.teku.spec.datastructures.state.beaconstate.common.ValidatorIndexCache.getValidatorIndex(ValidatorIndexCache.java:56)
    at tech.pegasys.teku.spec.logic.common.util.ValidatorsUtil.getValidatorIndex(ValidatorsUtil.java:62)
    at tech.pegasys.teku.spec.logic.versions.electra.statetransition.epoch.EpochProcessorElectra.processPendingDeposits(EpochProcessorElectra.java:266)
    at tech.pegasys.teku.spec.logic.common.statetransition.epoch.AbstractEpochProcessor.processEpoch(AbstractEpochProcessor.java:129)
    at tech.pegasys.teku.spec.logic.common.statetransition.epoch.AbstractEpochProcessor.lambda$processEpoch$0(AbstractEpochProcessor.java:104)
    at tech.pegasys.teku.spec.datastructures.state.beaconstate.common.AbstractBeaconState.updated(AbstractBeaconState.java:69)
    at tech.pegasys.teku.spec.logic.common.statetransition.epoch.AbstractEpochProcessor.processEpoch(AbstractEpochProcessor.java:104)
Post-transition state roots equal: 0x749e3d8ff71b9dd3b7f4714e6ab4013393de2fdf1f175ab328152f8f09675734
Frozen validators left after the transition: 257,280

### Epoch transition, warm pubkey index: one pre-state instance reused, its validator index cache warmed by one lookup of an unknown pubkey before timing

plain: processSlots median 5293 ms, p90 5400 ms (10 measured runs after 5 warm-up)
frozen: processSlots median 5047 ms, p90 5403 ms; per run: loads [0, 0, 0, 0, 0, 0, 0, 0, 0, 0] (median 0), store reads median 0, thaws median 0
Load call sites of the last run (0 distinct, top frames):
Heap added by the warming pubkey scan (validator views cached in the list view, memos dropped): plain 1196.4 MB, frozen 1196.5 MB

### Parts, cold (fresh state instance per run)

| measurement | variant | median ms | p90 ms | loads/run | store reads/run |
|---|---|---|---|---|---|
| createValidatorStatuses | plain | 3186.8 | 3271.7 | 0 | 0 |
| createValidatorStatuses | frozen | 2678.5 | 2863.3 | 0 | 0 |
| getActiveValidatorIndices | plain | 683.1 | 696.7 | 0 | 0 |
| getActiveValidatorIndices | frozen | 562.6 | 682.1 | 0 | 0 |
```

Run 2 (identical code apart from the depth-3 row and the decile table) gave:
saved 31.3 MB; cold 6,086 vs 5,759 ms; warm 5,351 vs 4,815 ms;
`createValidatorStatuses` 3,127 vs 2,772 ms; `getActiveValidatorIndices` 665
vs 572 ms; same loads and call site.
