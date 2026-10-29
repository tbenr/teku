# State Diff DAG: decisions log and grey areas

Companion to `docs/superpowers/specs/2026-10-22-state-diff-dag-design.md`.
Every entry is a call made without an explicit answer from Enrico, so it can be
reviewed and reverted. Newest entries at the bottom. Entries marked **GREY**
are judgment calls where a different choice is defensible; entries marked
**DERIVED** follow from the code and are unlikely to be wrong.

## Design-stage calls (before implementation)

1. **DERIVED. Node identity = post-state of the epoch's checkpoint block, keyed by block root.**
   Today's finalized anchor is exactly that state (`StoreTransaction.getLatestFinalized`
   builds it from the finalized checkpoint block's state). Using the first block
   of the epoch instead (today's hot-state choice) would leave the anchor
   unreachable in MINIMAL mode, where blocks below finality are deleted.
   Cost accepted: hot-state regeneration from a node pays one epoch transition
   that today's first-block base did not.

2. **GREY. Snapshots are diffs against the empty state tree, in the same column as diffs.**
   One writer and one reader instead of two formats. Validators become
   supernode references, so a snapshot is tens of MB instead of ~300 MB. A
   separate snapshot column was dropped because the static-data flag already
   sends large values to blob files on RocksDB.

3. **GREY. The mode is sticky via a `STATE_DIFF_MODE` marker variable.**
   Once enabled, the flag is ignored and the database keeps using diffs.
   Alternative was failing at startup when the flag is removed. Sticky avoids a
   node that cannot start after a config edit.

4. **GREY. Skipped nodes are tolerated; the anchor node is guaranteed at finalization.**
   If the checkpoint block's state is not in the `Store` cache when its first
   child is imported, no node is written for that epoch. The finalization
   transaction always carries the anchor state and creates the node then. A
   stricter alternative, regenerating the state synchronously at write time,
   was rejected because it can block the store write lock.

5. **GREY. Garbage rule at finalization: (chain(A') ∪ nodes between A' and A) \ chain(A).**
   Chosen for being O(chain length) per finalization with no secondary index.
   Requires the previous anchor root in the update, which
   `StoreTransactionUpdatesFactory` has.

6. **GREY. Supernode reference counts live in the value prefix.**
   Alternative was mark-and-sweep on a schedule. Refcounts are deterministic
   and transactional; a `debug-db` recount tool guards against drift.

7. **GREY. Frozen supernode criteria: all validators withdrawable and effective balance zero.**
   Effective balance zero is required because an Electra top-up to a withdrawn
   validator raises it again; freezing only on `withdrawable_epoch` would make
   thaws common on busy supernodes.

8. **GREY. `prevNodeRoot` computed in the `Store` from fork choice, not by the writer.**
   Fork choice has the ancestry in memory; the writer would need hot-block
   reads. Left to the builder in the design but this is the intended choice.

9. **GREY. No application-level compression (user decision), but the delta section is versioned so a codec can be added for that section alone.** Superseded by 63.

10. **DERIVED. Column IDs 10 and 11, variable 13.** Verified free on master
    `77aa8f474b`; 8 and 9 are light-client columns, 12 is a variable in use.

11. **GREY. Gloas node = post-block (pre-envelope) state.** Master's `Store`
    keeps envelopes (`putExecutionPayload`) but no post-envelope states, and
    the anchor is the block state. Keying nodes by root only keeps one node
    kind; a post-envelope kind is reserved as a future format version.

12. **GREY. Blob DB not forced.** `--Xdata-storage-rocksdb-blob-db-enabled`
    defaults to off, so `containsStaticData` has no effect by default.
    Forcing it on would change every V6 RocksDB node's layout; instead the
    soak runs both settings. Nothing else in the design depends on blob files.

13. **GREY. Archive nodes do not dual-read legacy history** (phase 2). An
    existing archive node switches via `--reconstruct-historic-states` or a
    fresh database. Out of scope for the non-archive focus.

14. **DERIVED. Skipped nodes cannot break pointer chains.** The `Store` passes
    the list of ancestor checkpoint-block roots down to the anchor; the writer
    links to the first one that exists as a node. The anchor always exists.

15. **DERIVED. State lookup at write time checks `blockStates` then `epochStates`.**
    `getBlockStateIfAvailable` only reads `blockStates`; the parent state of
    an epoch-crossing block is deliberately cached in `epochStates`.

16. **DERIVED. `TreeUpdates` is applied per depth.** It rejects mixed depths.

## Implementation-stage calls

17. **GREY. No commits during implementation.** CLAUDE.md forbids `git commit`
    without explicit instruction, so the work accumulates staged in the
    worktree `state-diff-dag` (branch `worktree-state-diff-dag`). Review
    packages are diffs against `git write-tree` snapshots. Say the word and
    the staged work gets committed per task.

18. **GREY. Implementers on Opus, reviewers on Fable subagents.** Each task is
    implemented by a fresh Opus agent and reviewed by a fresh Fable agent;
    findings are adjudicated in the main (Fable) session. Reports and review
    packages are kept under `.superpowers/sdd/2026-10-22-state-diff-dag-phase1/`
    (git-ignored) for inspection.

19. **GREY. `retainFinalizedChain` takes an `Optional` previous anchor.** The
    first finalization after bootstrap has no previous anchor chain to
    enumerate; only the new chain is retained.

21. **DERIVED. Equal hash tree roots are not enough to skip a subtree.** Teku
    leaf nodes carry a data length that element reads and serialization rely
    on; hashing zero-pads, so two trees can hash equal and still differ. The
    walker skips on node identity, descends hash-free on equal-root/different-
    instance pairs, and hash-guided otherwise (spec 6.1 updated). Cost: one
    hash-free full walk per level after a restart.

22. **GREY. Zero-filled lists are stored as leaves, not as a zero-tree reference.**
    Same root cause: the canonical zero tree has 32-byte leaves, a reset
    participation list has a shorter last leaf. Roughly 1 MB raw per epoch,
    near-zero after LZ4. A `ZeroFilled(gIndex, depth, dataLength)` entry can
    be added later under a new payload version if measurements justify it.

23. **DERIVED. `SszSuperNode.updated` now handles `isFinal()` like `SimpleBranchNode`.**
    Replacing an existing supernode via `TreeUpdates` hit an assertion
    without it; the applier needs that path. Covered by a new
    `SszSuperNodeTest` in `infrastructure:ssz`.

24. **GREY. Supernode reference counts live in their own column (hot column 13; 12 is the blob-sidecar column in V4-layout hot stores).**
    The Task 6-8 implementer pointed out that a count prefix inside a 31 KB
    static-data value forces a full value rewrite on every count change,
    which churns RocksDB blob garbage collection. Column 11 now holds
    immutable SSZ bytes; column 13 holds the u64 count. Supersedes entry 6's
    "value prefix" wording.

25. **DERIVED. V4-era split databases simply host the new columns** rather
    than throwing; their hot store is separate, so no ID clash.
    (2026-10-28: V4 now exposes the columns but not the DAO operations: reads are empty and writes throw, since V4 cannot enable state diffs.)

26. **DERIVED. Two engine fixes forced by real beacon states.** The applier
    expands a non-branch node on the update path into a zero tree before
    inserting below it (composite list elements sit over an empty list's zero
    leaves); and the zero-tree / same-leaf checks short-circuit when both
    children are one shared instance, which was exponential before. Wire
    format unchanged. Both have regression tests.

27. **DERIVED. Write-time parent resolution follows `parentNodeRoot`, not `prevNodeRoot`.**
    Retention deletes the level-1 nodes between coarse nodes, so a prev-pointer
    walk dead-ends after the first finalization and would force a snapshot at
    every coarse boundary. Parent chains are monotone in level and always
    retained, and they reach the same ancestor. A snapshot is a valid parent for
    every level. The invariants fixture therefore does not require
    `prevNodeRoot` targets to exist (spec 5.2 updated).

28. **DERIVED. The level cache fills every finer slot when a node is written.**
    The base for level L is the most recent node of level coarser-or-equal to
    L, so a coarse node is also the newest base for every finer level.

29. **DERIVED. Retention enumerates newly finalized roots explicitly instead of walking prev pointers.**
    An anchor node created late at finalization (because its epoch node was
    skipped) has `prev = previous anchor`, so a prev-pointer walk never visits
    the canonical nodes written in between and they would leak in prune mode.
    `KvStoreDatabase` already has the set of just-finalized block roots, so
    `retainFinalizedChain` takes it as a parameter (spec 5.8 amended).

30. **DERIVED (amended). A late-created anchor node gets its real ancestor list.**
    First version passed `[previousAnchorRoot]`; review showed that makes
    chain(anchor) skip the nodes between the two anchors, so retention
    deletes them while a hot node written before the late finalization still
    has one of them as parent, and the next finalization throws. Now the
    ancestors are walked from the finalized child-to-parent map nearest-first
    plus the previous anchor, so chain(anchor) includes every retained
    intermediate node and the invariant "a hot node's parent at or below the
    anchor is in chain(anchor)" holds.

31. **DERIVED. A marked database builds the DAG even when the flag is absent**
    (spec 5.10 sticky mode), logging at INFO.

32. **GREY. Four legacy hot-state tests are skipped in state-diff contexts**
    (`shouldPersistHotStates_*`, `shouldClearStaleHotStates`): they assert
    full hot states that the feature no longer writes; the `stateDiffs_*`
    tests cover the replacement behaviour.

33. **DERIVED (spec correction). A block on an epoch start slot is its own checkpoint block.**
    The first draft of spec 5.4 keyed every epoch's node by the parent of the
    first block in the epoch. The Task 13 implementer noticed that on mainnet
    the first slot usually has a block, so the finalized checkpoint root is
    that block and its node would be created late at every finalization, with
    two nodes per epoch. Corrected: a start-slot block's own post-state is the
    node (always in the transaction, never skipped); the parent is the node
    only for empty first slots and empty epochs. Regeneration base uses the
    same rule.

34. **GREY. The CLI flag also enables diff mode in `StoreConfig` directly**, not
    only through `OnDiskStoreData`, because a brand-new database starts its
    store without `createMemoryStore` and would otherwise skip every node
    until the first restart.

35. **GREY. The ancestor list contains checkpoint blocks only** and ends with
    the store's finalized root at the start of the transaction.

20. **GREY. Gloas and progressive lists are covered by the engine, not special-cased.**
    The walker compares hashes and treats any `SszSuperNode` as a unit, so
    the smaller supernodes of progressive list levels work unchanged. The fuzz
    test runs on Electra, Fulu and Gloas to prove it.

36. **DERIVED (final review C2). A node at the coarsest level is always a snapshot.**
    `writeNode` checks, after `forced` and before `no_parent`/`milestone`,
    whether the node's level is the coarsest and then writes a snapshot with
    reason `periodic`. Before, a level-0 node was a diff against the previous
    level-0 node, so reconstruction chains and the retained finalized chain
    grew by one node every 256 epochs without bound, contradicting D6, 5.2 and
    5.8. Chains are now bounded by `1 + sum(p_{L-1}/p_L - 1)` and the whole
    older chain becomes garbage in one step once the anchor passes the next
    periodic snapshot. The first node of a fresh chain (epoch 0) is counted as
    `periodic` rather than `no_parent`.

37. **DERIVED (final review C1). A late anchor node is written at the finest
    level, and an anchor whose chain cannot be loaded is repaired.**
    `ensureNode` writes at level `levelCount - 1` whatever the epoch, so its
    parent is its previous node and `chain(anchor) ⊇ chain(prev)`: nodes of
    later epochs that diffed against the anchor's ancestors before it existed
    keep their parents through retention. Before, a skipped coarse epoch
    (e.g. 16) got a coarse late node whose parent was far back, retention
    deleted the parent of the next epoch's node, and every later finalization
    failed in `loadChain`: a permanent crash loop across restarts. Coarse nodes
    written later walk past the finest-level node through parent pointers, as
    `resolvesCoarseParentAfterRetention` proves. As a second line of defence,
    `KvStoreDatabase.updateStateDiffDag` catches a `StateDiffVerificationException`
    or `MissingSupernodeException` from `ensureNode`/`retainFinalizedChain`
    (`ensureNode` throws before writing anything; `retainFinalizedChain` as
    first written could have deleted some candidates before a later corrupt
    one threw, which the repair tolerates because its reader sees the
    pending deletions; since decision 40 it loads both anchor chains before
    deleting anything and deletes corrupt candidates without throwing), logs
    the trigger at ERROR (an anchor node that could not be created, or a chain
    that could not be loaded), counts
    `nodes_total{state=anchor_repaired}`, rewrites the anchor as a forced
    snapshot from `update.getLatestFinalizedState()` (overwritten, never
    deleted and re-put in one batch) and retains against it, reading only the
    readable prefix of the previous anchor's chain. Nodes below a lost node
    can no longer be reached and stay on disk (a leak, not a hole). Likewise a
    hot node whose diff base cannot be reconstructed is written as a forced
    snapshot instead of failing the storage update.

38. **DERIVED (final review I1, I2). Corruption of a non-anchor node degrades to
    replay; corruption of the anchor is an actionable startup error.**
    `getHotState` catches reconstruction failures for any root other than the
    finalized checkpoint root, logs them at ERROR at most once a minute (with
    the count of suppressed ones) and returns empty, so
    `StateRegenerationBaseSelector` replays from the closest available state.
    `StateDiffDag.reconstruct` counts every failure in
    `verification_failures_total`. `getLatestFinalizedState` (and so
    `createMemoryStore`) turns a failure into an unrecoverable
    `DatabaseStorageException` naming `teku debug-tools db verify-state-diffs`
    and checkpoint re-sync. Codec `IllegalArgumentException`s from a node
    record (`NodeReader.getNode`) or payload (`reconstruct`) are wrapped in
    `StateDiffVerificationException` naming the node, so one type carries all
    corruption besides a missing supernode. Since the startup error sends the
    operator to `verify-state-diffs`, the tool reports undecodable records
    as `corruptNodes` instead of failing on them.

39. **DERIVED (final review I3). The per-level base cache keeps the 3 most recent
    roots per level.** A start-slot reorg writes a second checkpoint block for
    the same epoch with the same parent; with one entry per level the first
    write evicted that parent and the second reconstructed it on the storage
    thread. Entries share structure, so the extra memory is the changed data
    between them. Metrics, the node's base cache entry and the per-section
    payload metrics (`section_bytes_total`, `section_entries_total`, one DEBUG
    line per node) are recorded at `commit()`, so a failed transaction neither
    counts nor caches a node that is not on disk; a node written earlier in
    the same transaction is a cache hit through the transaction's own map.

40. **DERIVED (performance wave). Delta sections are folded along a
    reconstruction chain, and packed uint64 lists are read and built at leaf
    level.** The mainnet soak measured ~540 ms per node write and ~3 s per
    reconstruction, dominated by boxing every element of the ~2M-element
    balances and inactivity scores (`getElement`, `createTreeFromElements`)
    and by rebuilding both lists once per chain link. `PackedUInt64Lists` now
    walks the data tree of a bounded list (or each level subtree along the
    spine of a progressive one) and decodes 4 little-endian longs per chunk
    leaf, and builds lists from 32-byte chunk leaves: a balanced data tree of
    the schema's depth plus the length node for bounded lists,
    `ListSchemaUtil.createPackedProgressiveListTree`, whose contract (packed
    bytes split into 32-byte leaves, element count in the length node) fits,
    for progressive ones. Both are checked against the element API on random
    lists of 0 to 100003 elements built by every path (elements, SSZ,
    appends, updates) for equal values, roots and SSZ. A node packing several
    chunks resolves them through `get`; an unknown `SszUInt64ListSchema`
    implementation keeps the element path. Deltas compose, so
    `StateDiffDag.reconstructChain` hands the chain to a
    `TreeDiffApplier.ChainApplication`: each link's delta entries are folded
    into a per-field `long[]` (starting from the field's values in the tree,
    the empty default list for a snapshot) and the other sections applied with
    `applyExcludingDeltas`; `finish()` writes each folded field once with
    `applyDeltaValues`. A link with any other entry addressing a delta field,
    which the differ never emits (it only emits deltas for those fields), would
    see unwritten values, so the pending values are written first and the link
    applied in full: the result always equals per-link `apply`, which stays
    unchanged. All links of a chain have the snapshot's milestone (a parent
    from another milestone forces a snapshot); a link that does not fails
    verification. `storage_state_diff_reconstruct_list_builds_total` counts
    the lists built and `storage_state_diff_write_phase_seconds_total{phase}`
    splits node writes into base, walk, encode and store. Micro-benchmark on a
    200k-element list (see the perf wave report): read ~18.5 to 1-3 ms, write
    (incl. hashing) 16-20 to 5-6 ms, one delta apply 35 to 6 ms, a 10-link
    chain 291 to 17-18 ms (30-36 ms link by link with the new list code). Also in this wave: a
    node written twice in one transaction (late anchor, then repaired) is
    recorded once, as last written; retention deletes a candidate whose
    record or payload cannot be decoded without releasing its supernode
    references (`nodes_total{state=deleted_corrupt}`, ERROR) instead of
    triggering an anchor repair, which is now reserved for anchor-node
    creation and chain-load failures and logs which of the two happened.
    (2026-10-28 cleanup: the element-API fallback for unknown `SszUInt64ListSchema` implementations and the write-pending fallback of `ChainApplication` are gone; an unknown schema is rejected, a link addressing a delta field outside its delta section is rejected, and the differ no longer offers delta fields as copy sources, which no beacon state schema had a use for.)

## Soak notes (2026-10-22, local Mac, copies of the user's sepolia and mainnet MINIMAL databases, stub execution engine)

- Sepolia (Gloas, epoch ~356570): in-place bootstrap worked on the copied V6
  database ("Switched the database to state diff storage at finalized
  checkpoint ..."). First numbers: snapshot 3.4 MB; per-epoch level-3 diffs
  avg ~430 KB raw (8 nodes, 3.45 MB); level-2 node 470 KB; node write ~18 ms
  avg; reconstruction chains 1, 1, 4 in 0.14 s total; RSS 1.4 GB. Two anchors
  were created late right after the switch: those checkpoint blocks were
  imported by the previous run before the DAG existed, so no node could
  exist for them; expected only after migration.
- Open question from the first numbers: ~430 KB per epoch is large for a
  ~2k-validator state. Likely whole 31 KB supernodes rewritten because
  effective balances on a small, high-reward validator set cross their
  hysteresis thresholds often; per-section byte metrics are being added to
  confirm before judging mainnet sizes.
- Mainnet first launch failed on a QUIC port clash with the sepolia node
  (both defaulted to 9001); relaunched with distinct UDP and QUIC ports.
- Mainnet (Fulu, finalized epoch ~483580, syncing ~3 days of blocks): 197
  level-3 nodes totalling 1.65 GB raw = ~8.4 MB per epoch before RocksDB
  LZ4; level-2 ~9.7 MB, level-1 ~10 MB, level-0 11.9 MB (a 256-epoch diff;
  becomes a snapshot after fix C2). Node write ~540 ms average on the storage
  thread; reconstruction ~3 s each over short chains; base cache 208 hits /
  3 misses; 0 skipped nodes; 2 late anchors right after migration (expected).
  Raw size is dominated by the per-validator lists (balance deltas ~2-3 bytes
  each, inactivity zeros, participation flags); on-disk size after LZ4 is to
  be measured from database growth once in sync.
- Planned performance wave (after the review fix wave): read packed UInt64
  leaves directly instead of boxing 2M elements per list; fold the delta
  sections of a whole reconstruction chain into one pass (deltas compose
  additively) and build each list once; build delta leaves directly instead
  of `createTreeFromElements`; split write-time metrics into walk / encode /
  commit.
- Retention cadence observed: consecutive finest-level finalizations delete
  nothing (chain(E+1) contains chain(E)); deletions come in bursts of up to
  15 at each 16-aligned epoch and the whole older chain at each snapshot.
  Mainnet post-sync database growth (1.4 GB in 23 min with no net node
  growth) is therefore not the DAG; it is data columns / blocks / compaction
  lag and is being attributed via RocksDB per-column-family stats.
- Perf wave (200k-element list, ms): read 18.5 -> ~1.5, write+hash 16-20 ->
  ~6, single apply 35 -> 6.3, 10-link chain 291 -> 17.5 (folded).

## Soak results after the fix and performance waves (2026-10-23, offline tools on the stopped databases)

`verify-state-diffs`: sepolia 13 nodes / 11 supernodes, anchor chain 11,
reconstruction 126 ms, no problems; mainnet 24 nodes / 12,159 supernodes,
anchor chain 22, reconstruction 3,147 ms (old build; the perf wave was
measured at ~17x faster per chain in isolation), no problems.

`get-column-sizes` (RocksDB SST bytes, i.e. on disk):
(2026-10-28: the tool was folded into `teku debug-tools rocksdb usage --filter=STATE_`, which now also prints the estimated live data size.)

| column | sepolia | mainnet |
|---|---|---|
| STATE_DIFF_NODES | 5.5 MiB (13 nodes) | 72.7 MiB (24 nodes, ~3 MB each) |
| STATE_SUPERNODES | 164 KiB (11) | 181 MiB (12,159, ~14.5 KB each) |
| STATE_SUPERNODE_REFCOUNTS | 1.7 KiB | 458 KiB |
| HOT_STATES_BY_ROOT (legacy) | 5 KiB | 2.7 KiB |

So the steady-state footprint of all state storage on a mainnet prune node
is ~255 MB, against roughly 300 MB for `LATEST_FINALIZED_STATE` plus two
full hot states before; and bytes written per epoch are ~3 MB of node plus
~2.7 MB of changed supernodes on disk (~8.4 MB + ~5.8 MB raw), against
~300 MB plus ~150 MB before: a 25-50x reduction in state bytes written.

Two findings for Enrico to decide on (both are payload-version-2 changes,
neither blocks the working version):

41. **GREY. Changed supernodes are stored whole (31 KB) even when one validator changed.** Implemented by 69-71 (wave v2b).
    Mainnet touches ~190 supernodes per epoch, so ~40% of the per-epoch bytes
    are supernode copies. The design table mentioned splitting a changed
    supernode per validator; no task specified it. A `SupernodePatch` section
    (base supernode gIndex + changed 121-byte validator records) would cut
    this to tens of KB per epoch and remove nearly all refcount churn; the
    content-addressed column would then hold only snapshot-referenced and
    (phase 3) frozen supernodes.

42. **GREY. Relying on RocksDB compression under-delivers.** The database is
    configured with LZ4 on upper levels and `DISABLE_COMPRESSION_OPTION` on the
    bottom-most level (Teku's existing default), so long-lived nodes end up
    stored uncompressed after compaction, and LZ4 itself only gives ~2.8x on
    the balance deltas (sepolia nodes show ~1x). The hackmd's 35 KB balances
    figure came from gzip. Application-level compression of the delta section
    (zstd or gzip) is the contained change the design reserved for this case
    (D9, spec 6.3); expected to bring a mainnet epoch node from ~3 MB to well
    under 1 MB on disk regardless of compaction level.

Correction to entry 41 from the per-section metrics on the final build: a
finest-level mainnet epoch node sampled after the restart had deltas 4.03 MB
raw (balances + inactivity scores), leaves 220 KB (12,383 leaves), and only 8
changed supernodes (~250 KB raw); the 12,159 supernodes on disk are mostly the
bootstrap snapshot's full validator set plus the coarse (16/64-epoch) nodes.
So the delta section, not the supernodes, is the dominant per-epoch cost, and
entry 42 (compressing the delta section) is the higher-value change; entry 41
mainly matters for coarse nodes and refcount churn.

Restart on the final build: sepolia reconstructed its anchor (chain 11) in
137 ms; mainnet's startup reconstruction (chain 22) landed in the 2.5-5 s
bucket, but the first node written after the restart spent 14.5 s in the
`base` phase (per-level cache empty, base reconstructed from a 9-link chain),
while its walk took 139 ms, encode 2.7 ms and store 0.9 ms. Steady-state node
writes are therefore ~140 ms (down from ~540 ms); the cold-cache base
reconstruction on the live node is far slower than the offline verifier's
3.1 s and needs profiling (candidates: thousands of supernode point reads for
coarse nodes, GC during sync start-up).

Memory A/B on mainnet (2026-10-23, same build, same -Xmx8g, both nodes in sync
at head 15,490,920 / finalized 484,089, full GC forced by the class histogram):

| | state-diff node | stock node (flag off, unmigrated copy) |
|---|---|---|
| live heap after full GC | 5.30 GB | 4.59 GB |
| byte[] | 1.30 GB | 0.96 GB |
| SimpleLeafNode | 5.04 M | 3.25 M |
| SimpleBranchNode | 5.93 M | 4.05 M |
| ArrayWrappingBytes32 | 13.2 M | 9.4 M |
| SszSuperNode | 7.20 M | 7.18 M |
| non-heap / direct buffers | 137 MB / 231 MB | 135 MB / 201 MB |

The DAG adds ~0.7 GB of live heap: ~1.8 M extra packed-list leaves and ~1.9 M
branch nodes, i.e. the diverged balances / inactivity / participation subtrees
of the coarse base trees held by the per-level cache (about 150 MB per distinct
coarse base, 2-3 bases resident). Supernode object counts are identical, so
the 7.2 M per-validator view slices are Teku's existing per-state view caches,
not the DAG. `baseCacheLevels` trades this memory for a reconstruction every
16 / 64 / 256 epochs. Single snapshot; RSS was not comparable because the stock
node had just finished a 3-day catch-up sync.

Soak status 2026-10-23 06:12 UTC (7 h on the final build): both nodes in sync at
the wall-clock slot, 0 errors/warnings since restart, 0 verification failures.
Mainnet: 68 nodes written / 72 deleted, first periodic snapshot at epoch
484096 = 20.9 MB raw, level-3 nodes avg 5.4 MB raw, base cache 66 hits / 1
miss, 1 skipped node (state evicted) handled by a late anchor; steady-state
write ~48 ms per node (walk 2.95 s + encode 0.30 s + store 0.03 s over 68
nodes; the 14.7 s "base" total is the single cold-cache reconstruction after
restart). Sepolia (Gloas): 65 written / 63 deleted, write ~9 ms per node, 4
reconstructions in 0.35 s.
Database growth (mainnet 8 -> 42 GB, sepolia 15 -> 28 GB) is Teku's
HistoricalBlockSyncService backfilling ~1M slots of blocks after checkpoint
sync (33 GB flushed into FINALIZED_BLOCKS_BY_SLOT, 27 GB of compaction output
on SIDECAR_BY_COLUMN_SLOT_AND_IDENTIFIER), not the DAG; the state diff columns
flushed ~6 MB and compacted ~200 MB in the same period. Sepolia node stopped
after 7 h to preserve disk for the mainnet backfill.

## Phase 2 design additions (2026-10-23, discussed with Enrico)

43. **GREY. Retention window reuses the prune retention rule.** Keep every
    canonical node of the last W epochs plus the reconstruction chain of the
    oldest node in the window; the garbage rule is the prune-mode one with the
    anchor replaced by the oldest retained node. Justification: a node inside
    the window resolves its coarse parents to the nearest coarser node below
    the boundary, which the oldest node's chain passes through, so one backbone
    chain serves the whole window. W comes from the existing
    `--Xdata-storage-archive-finalized-states-retained` option (slots).

44. **GREY. Reconstruction anchor instead of genesis for fresh nodes.** An
    operator-supplied older finalized state (CLI or API) becomes the start of
    `--reconstruct-historic-states`; the block backfill target is lowered to
    its slot, and the state is verified against the block's state root before
    use. An anchor older than `MIN_EPOCHS_FOR_BLOCK_REQUESTS` only logs a
    warning: the backfill keeps retrying until peers serving those blocks are
    found, which may be the operator's own nodes.

45. **DERIVED. Chain bounds.** Prune levels: snapshot + 21 diffs; archive
    levels `[65536, 8192, 2048, 256, 64, 16, 1]`: snapshot + 38 diffs. Reads of
    stored states never replay blocks beyond the target epoch; only producing
    states the database never had replays forward from an older state.

46. **DERIVED. Mode wiring resolved once (2026-10-28 wiring cleanup).**
    `KvStoreDatabase` resolves flag || marker once and hands it to the Store as
    `OnDiskStoreData.stateDiffStorageActive`. `StoreBuilder` turns that into a
    `HotStatePersistencePolicy` (`everyNthEpoch` / `checkpointBlocks`).
    `StoreConfig.stateDiffsEnabled` stays only because a chain started from an
    anchor builds its store before the new database stores the anchor (34);
    `StoreBuilder` ignores it for a loaded store. `KvStoreDatabase` delegates to
    one `HotStateStorage` strategy (Legacy / StateDiff), swapped at most once,
    after the activating commit. `StorageUpdate` carries `HotState(state,
    Optional placement)`; on a state diff database, a hot state without a
    placement now fails the update instead of being skipped, since that path is
    unreachable (`skipped_no_info` removed). The
    `--data-storage-archive-frequency` WARN still does not fire on a marker-only
    database (pre-existing gap, left as is).

47. **GREY. Applier per milestone, known-root supernodes (2026-10-28 applier
    cleanup).** One `TreeDiffApplier` per milestone; the `SupernodeSource` is
    passed per application (`apply` / `startChain`), so there is no
    source-bound memo and no synchronized committed reader. Supernodes are
    rebuilt with their store key as known root (`SszSuperNode.createWithKnownRoot`)
    and the supernodes of a diff are loaded in one batched read (`multiGet`).
    The reconstruction root check therefore no longer detects a supernode whose
    content does not match its key. Ruling: RocksDB block checksums cover bit
    rot, and `verify-state-diffs` hashes every referenced supernode's content
    against its key (`supernodesHashChecked` / `supernodeHashMismatches`).
    Measured ~10x faster reconstruction on a 100k-validator synthetic chain
    (85 -> 8 ms snapshot, 88 -> 12 ms 10-link chain), nearly all from not
    rehashing. The template getter is named `getSupernodeElementTemplate` so
    AssertJ's recursive comparison does not build templates through it.

48. **DERIVED. Recovery owned by the DAG, one exception type (2026-10-28
    recovery cleanup).** Every reason the DAG cannot serve a state leaves
    `StateDiffDag` / `StateDiffDagTransaction` as a `StateDiffVerificationException`:
    `reconstructChain` wraps the applier's `MissingSupernodeException` naming the
    hash, the referencing node and the chain; codec `IllegalArgumentException`s
    were already wrapped (38) and no other one can escape, so callers no longer
    catch either. `StateDiffDagTransaction.onAnchorFinalized` does ensure ->
    retain and, on failure, logs one ERROR naming the failed step, counts
    `anchor_repaired` (and `anchor_created_late`) itself and repairs; repair and
    retention share one candidate-and-delete method (prefix vs strict loading of
    the previous chain). `writeNodeOrSnapshot` owns the hot-node snapshot
    fallback and `StateDiffDag.reconstructOrEmpty` the once-a-minute ERROR
    throttle; the anchor-root rethrow and the unrecoverable startup error stay
    in `StateDiffHotStateStorage`. The shadow `AtomicLong` counters are gone;
    tests read `state_diff_nodes_total` from the database's `StubMetricsSystem`.

49. **DERIVED. Base cache sizing and the chain shortcut (2026-10-28 base cache
    wave).** The base cache keeps 3 trees at the finest level and 1 at each
    coarser one, every level cached (`baseCacheLevels` removed); a coarse entry
    pins up to its period of diverged state, so the extra coarse slots plausibly
    held most of the measured +0.7 GB, for rare coarse-boundary siblings (not
    re-measured). Slots are `LimitedMap` LRUs; a diff base is looked up by root
    in any slot. `loadLatestFinalizedState` reconstructs the anchor with
    `reconstructAndCache`, so it is cached at its level from startup. Every
    reconstruction (diff base or committed read) loads its chain only down to
    the first node below the requested one whose tree is cached or written in
    the transaction and whose tree root equals that node's recorded state root,
    and folds the links above it onto that tree (delta fields start from its
    values). The requested node's own payload is always applied, and retention
    still loads whole chains.

50. **DERIVED. Mainnet soak on the rebased tip ea51ab069c (2026-10-28).** Fresh
    clone of the user's MINIMAL V6 mainnet database (legacy state storage, Oct 19,
    finalized epoch 483376), stub execution engine, migrated in place at first
    start ("Switched the database to state diff storage"), syncing ~9 days of
    blocks at ~470 slots/min. After 224 node writes: 222 base-cache hits, 0
    misses, 2 late anchors right after the migration (expected), 0 skipped, 0
    verification failures; node write ~86-100 ms while syncing on a loaded
    machine (walk ~90% of it), level-3 node 8.7 MB raw as in the earlier syncing
    numbers. Restart tests of the base cache wave (49): restart 1 at a level-1
    anchor: two reconstructions of 2 links each, 0.94 s in total; restart 2 at
    finalized epoch 483624 (mod 16 = 8): the startup anchor reconstruction
    walked 12 links in ~1.2 s (whole chain, as it must), and the first write
    after the restart missed the cache but rebuilt its parent in 1 link, under
    0.25 s, stopping at the seeded anchor; that case took 14.5 s before (open
    item 3 of docs/state-diff-storage.md, now closed). Heap vs stock not
    re-measured. The 2026-10-22 soak copies under the earlier session's /tmp
    scratchpad had been removed by the macOS temporary-file cleanup.

## Fork crossing on a kurtosis devnet (2026-10-28, Fulu genesis, Gloas at epoch 3)

Enclave `sdfork` (ethereum-package, mainnet preset, geth v1.17.8): a Lighthouse
node (128 validators), a stock Teku node and a Teku node with
`--Xdata-storage-state-diffs` (64 validators each), image
`local/teku:develop-ea51ab069c`. The package forces `--data-storage-mode=ARCHIVE`
(a package constant), so finalized states stayed in the legacy column; the hot
checkpoint nodes went through the DAG.

- Node records after epoch 6 (dumped offline): genesis snapshot 2.28 MB; epochs
  1-2 Fulu diffs of 6.7 KB chained to genesis; epoch 3 (first Gloas block, slot
  96) written as a **snapshot with no parent**, 2.58 MB (reason `milestone`);
  epochs 4-6 Gloas diffs of 424 KB chained to it.
- `verify-state-diffs` on the copied database: 7 nodes, 5 supernodes, 0
  mismatches of any kind, anchor (epoch 4) chain length 2, reconstruction OK in
  139 ms.
- Node restarted at slot ~206 with finalized epoch 4: the anchor was
  reconstructed from the Gloas chain (no legacy finalized state exists), the
  node resynced and resumed duties; the only ERROR was one missed
  sync_signature at the restart slot.
- REST `GET /eth/v2/debug/beacon/states/{slot}` as SSZ, compared byte for byte
  with the stock node, before and after the restart: slots 16, 32, 40, 49, 63,
  64, 70, 95, 96, 97, 100, 103, 128, 129, 131, 140, 145, 159, 160, 170, 177,
  190, 195 all identical (Fulu states 2.77 MB, Gloas 3.18 MB), served in 40-350
  ms; the regeneration log lines show 2-45 blocks replayed from the nearest
  persisted node or in-memory state.
- The Gloas diffs are 60x the Fulu ones because of `ptc_window` (96 slots x 512
  validator indices x 8 B = 393 KB), which rotates by one epoch every epoch, so
  every leaf changes. Same absolute size on mainnet (PTC_SIZE is fixed): about
  +0.4 MB per Gloas epoch node. Two thirds of it is a shift by 32 slots that the
  payload format can already express as gIndex-level `copied` entries; the
  differ only looks for field-level copies today. Candidate for the payload v2
  wave.

## Phase 2, first slice: archive finalized states and the retention window (2026-10-28)

51. **DERIVED. Archive finalized states live in the DAG; window retention
    (wave "window").** With state diff storage on an archive database:
    `getLatestAvailableFinalizedState(maxSlot)` reconstructs the node of the
    checkpoint block of `epoch(maxSlot)` (floor lookup on the finalized blocks at
    the epoch start slot, decision 33) and `StateCacheLoader` replays as before;
    a reconstruction failure surfaces to the caller. `updateFinalizedDataArchiveMode`
    and `storeInitialAnchor` no longer write `addFinalizedState`; the
    `StateRootRecorder` (state root -> slot index) is still fed every finalized
    state the update has, as before. The legacy `StatePruner` is not started
    when the flag or the marker is on. `--Xdata-storage-archive-finalized-states-retained`
    (W slots, 0 = unbounded) travels in `StateDiffDagConfig.retainedSlots`. At
    each finalization the window start `S = anchor_epoch - W / slots_per_epoch`
    advances from the persisted start `S'` by at most 256 epochs; the
    transaction's `FinalizedRetention.Window` resolves `O` as the last
    checkpoint root of epochs `S'..S` that has a node and calls the prune
    deletion (renamed `deleteFinalizedNodesOutsideChain`, the protected root a
    parameter) with candidates `chain(O') ∪ roots(S'..S)` and protected
    `chain(O)`. The start is a new hot variable `STATE_DIFF_WINDOW_START` (14),
    gauge `storage_state_diff_window_start_epoch`. The delta section is broken
    down per field in `storage_state_diff_delta_field_{bytes,entries}_total{field}`.
    `Database.getEarliestAvailableFinalizedStateSlot()` is new: no REST or
    service code read the earliest finalized state slot before (only the legacy
    pruner, through the DAO), so it serves tests and tools. Legacy-column
    readers left: `debug-tools db get-state-summary` and
    `get-finalized-state-indices` (`streamFinalizedStateSlots`), the legacy
    pruner (not started) and `storeReconstructedFinalizedState`'s own lookup of
    the previous reconstructed state, which keeps reading the legacy column.

52. **GREY. The window start also bounds unbounded archive history.** The
    variable is written with the anchor snapshot when an archive database
    switches to state diff storage or is created with it (the anchor's epoch),
    and reads below it return empty in both archive variants. A database
    without it (prune mode switched to archive, or an archive switched before
    this wave) records the previous anchor's epoch at its next finalization and
    deletes nothing that time; a prune database drops a leftover variable.
    Rejected: scanning the node column for the earliest finalized node (reads
    every record, MBs each) or assuming epoch 0 (would walk and claim history
    that does not exist). Cost: an archive switched before this wave stops
    serving the nodes it kept before its previous anchor and never deletes them.

53. **GREY. The earliest available slot is the start slot of the window start
    epoch,** not the block slot of the earliest node as spec 5.11 said: reads
    are decided by epoch, so that is what is actually served.

54. **GREY. A skipped checkpoint node falls back to the previous checkpoint
    block's node,** for reads (down to the window start) and for choosing `O`
    (the latest checkpoint root of `S'..S` with a node; the previous `O` is
    re-found the same way, walking back from `S'` on committed nodes). Rejected:
    returning empty / deleting nothing for that epoch. The fallback keeps the
    chain argument of decision 43 valid, since no node lies between the earlier
    node and `S`.

55. **GREY. Window retention never repairs the anchor.** It does not load the
    anchor's chain (as unbounded archive retention did not), reads the previous
    `O`'s chain prefix-only, and when `O`'s chain cannot be loaded it logs ERROR,
    deletes nothing and keeps the start for a retry at the next finalization.
    Rejected: running it under the existing repair path, which would rewrite
    the anchor as a snapshot at every finalization while the broken node stays
    in range, and would not fix a broken `O` chain anyway. Consequence: as
    before for archive mode, a broken anchor chain is caught only by the
    ensure step, by reads, or at restart.

56. **GREY. The window start moves by at most 256 epochs per finalization,**
    which bounds the floor lookups (one finalized block read per epoch) and the
    candidates of one transaction; the number of nodes leaving is at most that
    of epochs. A raised W keeps the old start (deleted history does not
    return).

57. **GREY. The legacy pruner is skipped when `isStateDiffStorageActive()` or
    the flag is set,** checked in `StorageService.doStart`: a legacy database
    with the flag only switches when the store is loaded, after the service
    starts. A switched archive database's pre-switch legacy finalized states
    are therefore never pruned (they are not served either); a one-off cleanup
    is left for later.

58. **GREY. Five legacy archive `DatabaseTest`s compare regenerated states in
    state-diff contexts** (`assertFinalizedStateAvailable`: the state at the
    slot from `ChainStorage.getLatestFinalizedStateAtSlot`) instead of being
    skipped: the database now stores checkpoint-block states only, and the
    regenerated per-slot states are what the tests are really about.

59. **GREY. `--reconstruct-historic-states` with state diffs is left as is,**
    not rejected in `TekuConfiguration`: it still writes legacy reconstructed
    states (and their state roots), which DAG-backed reads do not serve. Its
    integration is the next slice (spec 5.11). Superseded by 62.

60. **GREY. Per-field delta metrics are separate counters**
    (`delta_field_bytes_total{field}`, `delta_field_entries_total{field}`)
    rather than a second label on the section metrics, so the existing
    dashboards' series keep their label set. The window gauge is a
    lazily-registered label-less `SettableLabelledGauge`, because several DAG
    instances share one metrics system in tests and a plain gauge cannot be
    registered twice. The per-field counters were removed in 66.

61. **DERIVED. Mainnet soak of the window slice (2026-10-28, build 84e5d83a1a +
    the staged window wave).** The running prune soak node was restarted in
    archive mode with `--Xdata-storage-archive-finalized-states-retained=4096`
    (128 epochs) while still syncing at ~15 epochs/min: the pruner was skipped
    (INFO), the window start was recorded at the previous anchor's epoch
    485220 (decision 52), nothing was deleted until the window filled, then the
    start followed finalization exactly (485291 at finalized 485419) with
    deletions in bursts at level-16 boundaries (63 of 199 nodes written), 0
    errors, 0 verification failures. REST `/eth/v2/debug/beacon/states/{slot}`
    inside the window: 339 MB state in 1.6 s and 3.0 s at two checkpoint slots,
    10.5 s for a slot 13 blocks into an epoch (block replay dominates); below
    the window start: 404 in milliseconds. Offline `verify-state-diffs` on the
    stopped database: 146 nodes, 11137 supernodes all hash-checked, anchor
    chain 14 reconstructed in 1.3 s, no problems. `rocksdb usage`: 214 MiB of
    nodes (179 MiB live, 1.47 MiB per node on disk against 5.35 MB raw, LZ4
    ~3.6x) and 167 MiB of supernodes for the 128-epoch window plus the oldest
    node's chain: about 3 MiB per epoch, about 0.65 GB per day of mainnet
    history at today's format, the low end of the 0.7-1.3 GB estimate. Per-field
    delta bytes over 199 nodes: balances 784 MB (3.9 MB per node, every node),
    inactivity scores 127 MB (53 nodes, 2.4 MB each, 0.64 MB per node on
    average): the inactivity share of the delta section is ~14%, so sparse
    deltas (B7) are a minor gain and compression of the balance deltas (42) is
    the lever for the payload version 2 wave. Node write 63 ms average over the
    run (sync plus steady state). Restart with the window on: start persisted,
    one 1.3 s startup reconstruction, no deletions on restart.

## Pre-v2 cleanup wave (2026-10-29)

62. **GREY. `--reconstruct-historic-states` is rejected with state diffs; a
    skipped window-start node reads the oldest retained node (wave "pre-v2
    cleanup", supersedes 59).** `TekuConfiguration` throws
    `InvalidConfigurationException` ("Cannot reconstruct historic states with
    state diff storage: the integration is not available yet") when
    `StorageConfiguration`'s `StateDiffDagConfig` is enabled and the option is
    on, instead of letting it write legacy states the DAG-backed reads do not
    serve. A database in state diff mode by its marker alone (flag removed) is
    not known at configuration time and is left as is: it still writes those
    legacy states. Reads (decision 54): at exactly the window start `S`, when
    `S`'s checkpoint node was skipped, `O` lies below `S` and is kept by
    retention, but the fallback stopped at `S` and returned empty.
    `findCheckpointNode` now walks down to the checkpoint block of its lowest
    epoch and then tries the one checkpoint block before it: that is `O` when a
    single node was skipped at `S`; when that block has no node (deleted, or a
    second consecutive skip) the read is empty, rather than walking on to an
    older node far below and replaying its blocks. Reads below `S` stay empty;
    without a recorded start only the epoch's own checkpoint block is looked up,
    as before; retention's lookup of the previous `O` walks to epoch 0 and is
    unchanged. Rejected: an unbounded walk below `S` (one finalized block read
    per epoch on a broken database, and a possibly long replay from a node of
    `O`'s chain when `O` itself is missing). Test:
    `StateDiffDatabaseTest.stateDiffs_archiveWindowStartWithSkippedNodeReadsOldestRetainedNode`.
    Same wave, behaviour-neutral: (1) `PackedUInt64ListsBenchmarkTest` deleted
    (its checks are covered by `PackedUInt64ListsTest`,
    `TreeDiffChainApplicationTest` and `TreeDiffRoundTripPropertyTest`),
    `StateDiffDagInvariants` replaced by `verify` reports (it checked nothing
    `verify` does not), `StateDiffVerificationReport.anchorReconstructionOk`
    derived from the error, and `verify` loads the anchor chain once and applies
    it whole from its snapshot (no base-cache shortcut); (2) one
    `applyRetention` method serves `repairAnchor` and `retainFinalizedChain`;
    (5) the state diff `DatabaseTest`s moved to `StateDiffDatabaseTest`, sharing
    the fixture through `AbstractDatabaseTest`.

## Payload version 2, first wave: v2a (2026-10-29)

63. **GREY. Delta entries are compressed with zstd, one frame per field
    (payload version 2; supersedes the "no application-level compression" of
    9, as entry 42 proposed).** Each `DELTA` entry stores its field index, the
    raw `UInt64DeltaCodec` length, the compressed length and one zstd frame,
    written by aircompressor's pure Java `ZstdJavaCompressor` (fixed level 3,
    stateless, no native library, so payloads are the same on every platform)
    and read by a new `ZstdJavaDecompressor` per block (it holds per-call
    state). Measured on this Mac (best of 15 runs, synthetic blocks: a
    100k-element list of 1-9 gwei rewards with 20 large changes; a mainnet-like
    2M-element list where half the registry is exited, active validators earn
    one of a handful of attestation outcomes scaled by effective balance, 2%
    pay a penalty, 512 sync committee rewards, 512 withdrawal sweeps, 32
    proposers; 2M inactivity deltas with 300 non-zero; 2M all zero):

    | block (raw bytes) | zstd (Java, 3) | Deflater 1 | Deflater 6 |
    |---|---|---|---|
    | balances 100k, few gwei (100,066) | 2.2x, 1.6 / 1.9 ms | 2.1x, 0.5 / 0.2 ms | 2.2x, 4.3 / 0.2 ms |
    | balances 2M, mainnet-like (3,999,591) | 4.0x (996,183), 16.3 / 5.7 ms | 3.4x, 27.6 / 8.6 ms | 4.3x (919,850), 175.6 / 6.2 ms |
    | inactivity 2M, 300 non-zero (2,000,114) | 897x (2,229), 0.4 / 0.3 ms | 193x, 1.6 / 0.3 ms | 605x, 6.5 / 0.3 ms |
    | inactivity 2M, all zero (2,000,006) | 9434x (212), 0.3 / 0.2 ms | 229x, 1.6 / 0.3 ms | 1021x, 3.4 / 0.3 ms |

    (ratio, compress / decompress). Deflater 6 has the best balance ratio but
    takes 176 ms per 2M block, over the ~50 ms node encode budget; among the
    candidates within it zstd has the best ratio on every block and is the
    fastest on the large ones, so zstd. A synthetic mainnet-like node (12k
    leaves, 8 supernodes, both blocks) encodes in 17-19 ms (was 1.3 ms
    without compression) and decodes in 7 ms (was 1.0 ms); payload 6.4 MB ->
    1.4 MB. The 100k few-gwei block compresses only 2.2x because uniformly
    random 1-9 gwei deltas carry ~3 bits per element; real rewards are more
    regular. The real ratio is to be read off
    `section_bytes_total{section=deltas|deltas_compressed}` on the next soak;
    entry 61's LZ4 figure (nodes 1.47 MiB on disk for 5.35 MB raw) is the
    comparison, and compression before RocksDB also covers the bottom-most
    level, which Teku leaves uncompressed. Not chosen: the native zstd (a
    platform-dependent path for the same format), a higher zstd level (not
    offered by the Java compressor), one frame for the whole section (fields
    differ in statistics and a per-field frame keeps each block independent).
    Before allocating a block a reader checks that the raw length equals the
    frame header's content size and is at most 32768 times the frame size
    (zstd stores at least 4 bytes per block of at most 128 KiB), so a corrupt
    length cannot trigger a huge allocation.

64. **DERIVED (brief decision), GREY in the details. Version 1 payloads are
    not read.** There is no downgrade path and the only version 1 databases
    are soak copies, so `TreeDiffCodec` rejects version 1 with an
    `IllegalArgumentException` reading "State diff node payload version 1,
    written by an earlier experimental build, is no longer readable: the
    database must be re-synced into a fresh data directory". The anchor
    reconstruction wraps it as a corrupt payload (decision 38), so startup
    fails with the unrecoverable `DatabaseStorageException` carrying that
    text. GREY: `StateDiffVerificationReport.corruptNodes` became a list of
    `CorruptNode(root, reason)` so `verify-state-diffs` prints the same text
    per node (it printed roots only). GREY: `supernodeEntries` /
    `supernodeHashes` skip delta blocks by their lengths and check only the
    frame header (raw length against the declared content size), so they still
    reject every truncation, trailing byte, bad count or length and version
    that `deserialize` rejects, but a corrupt compressed body inside a
    well-framed block is detected by `deserialize` only. As before (v1 never
    validated delta content outside `apply`), delta content corruption is
    found when a chain is applied; retention can still release the supernode
    references of such a node. The `STATE_DIFF_MODE` marker value stays 1: the
    payload version byte is the format check, read on the startup anchor
    reconstruction. Tests: `TreeDiffCodecTest.rejectsVersionOnePayloadWithResyncMessage`,
    `KvStoreDatabaseTest.stateDiffs_versionOnePayloadFailsStartupAndVerifyWithResyncMessage`.

65. **GREY. Sparse deltas (simplify B7) are not implemented: compression
    subsumes them.** A 2M-element inactivity-score block of zero deltas
    (2,000,006 raw bytes) compresses to 212 bytes, and one with 300 non-zero
    scores to 2,229 bytes (63), so a lockstep walk emitting sparse chunk runs
    would save at most a few KB per node while adding a second delta encoding
    to the format. The varint encoding of the zeros (~2 ms per 2M elements in
    the walk) is the remaining cost.

66. **GREY. Per-field delta counters removed; compressed delta bytes added.**
    `delta_field_bytes_total{field}` and `delta_field_entries_total{field}`
    (60) served their purpose, sizing B7 (61: inactivity ~14% of the delta
    section). `section_bytes_total{section="deltas"}` keeps the raw delta
    bytes and `section_bytes_total{section="deltas_compressed"}` adds the
    compressed bytes as stored (from `TreeDiffCodec.encode`), bytes only: it
    has no `section_entries_total` series, which would repeat the `deltas`
    count. `DiffSectionSizes` lost its per-field list and the field names it
    needed, and carries the compressed byte count; the DEBUG line per node
    prints it. `write_phase_seconds_total{phase="encode"}` now includes the
    compression.

67. **DERIVED. Anchor recovery and finalized retention moved out of
    `StateDiffDagTransaction` into `FinalizedChainMaintenance`
    (behaviour-neutral).** It holds `onAnchorFinalized`, `repairAnchor`,
    `ensureNode`, `retainFinalizedChain`, `applyRetention`, `retainWindow`,
    `deleteFinalizedNodesOutsideChain` and the anchor step enum, and works on
    the transaction through a narrow `TransactionNodes` interface (reader,
    write a node at a level, delete nodes, discard a node about to be
    rewritten, set the window start). The transaction keeps writes, reads,
    refcounts, commit and thin delegations for its entry points
    (`onAnchorFinalized`, package-private `ensureNode` and
    `retainFinalizedChain` used by tests), 886 -> 755 lines. GREY in one
    detail: `deleteNodesTolerantly` and `releaseReferencesTolerantly` stay in
    the transaction (the brief listed them for the move) because the public
    `deleteNodes` of pruned fork nodes uses them too and they are supernode
    reference bookkeeping; maintenance reaches them through `deleteNodes` and
    `discardNode`. The repair now passes the level computed from the existing
    record's epochs (`maxLevelInRange`, what the public `writeNode` did) to
    the level-taking write.

68. **GREY. Encoder without intermediate copies (simplify B3).**
    `UInt64DeltaCodec.encode` sums the varint lengths in a first pass and
    writes into an array of exactly that size (`VarInts` now writes into
    arrays at a position and reports `unsignedLength`). The brief suggested
    sizing from the element count at 10 bytes per varint and trimming:
    measured 10.0 ms against 11.5 ms two-pass on 2M balances, but it allocates
    a 20 MB array per field per write and copies 4 MB to trim, so two-pass.
    Either replaces the stream's 17.3 ms (per-byte synchronized writes and
    growth copies). `TreeDiffCodec.encode` sizes one buffer for the worst
    case (maximum varint lengths, zstd's maximum compressed length per
    block), compresses each block straight into it past room for its length
    prefix and moves the frame down next to the prefix, and copies the buffer
    once to its exact length; leaf data and hashes are copied straight from
    their `Bytes` into the buffer. `deserialize` reads the payload in place
    when it wraps a whole array (`toArrayUnsafe`; a record's payload is a
    slice of the record, which tuweni copies once since it exposes no backing
    array), returns leaf data as views (tree leaf nodes copy their data),
    copies supernode and zero roots (32 bytes each; `verify` keeps hashes in
    maps for the whole run, which views would make pin every payload), and
    decompresses each delta block from the payload into its exact-size array.
    The public API is unchanged apart from the added `encode(TreeDiff)`
    returning the payload and its compressed delta byte count. V1 and V2 were
    implemented as one codec rewrite; the before/after figures in 63 therefore
    compare the v1 stream codec with the v2 one, compression included, and the
    compression time is given separately.

## Payload version 2, second wave: v2b (2026-10-29)

69. **GREY. Supernode patches: a `PATCHES` section in payload version 2
    (implements 41).** No version 2 database exists yet, so the section is
    added to version 2 in place, between `SUPERNODES` and `ZEROED`. Format:
    patches grouped by field index like supernode references; each group
    stores the field index, the element size (one per group: the encoder
    rejects two sizes in one field) and the entry count; each entry its gIndex
    delta, depth, element count, written count, the written indices as gaps
    (`index - previous - 1`, the first from -1, so strictly increasing by
    construction) and the written elements back to back, decoded as one view
    of the payload (the applier copies them). The element size is stored
    rather than taken from the schema so the codec stays schema-free; the
    applier checks it against the field's `SszNodeTemplate`. `PatchEntry`
    validates itself (depth at most 30, element count within `2^depth`,
    indices strictly increasing and below the count, element bytes =
    written x size), so a corrupt entry fails decoding with an
    `IllegalArgumentException`, which the DAG reports as a corrupt payload
    (38). Applier: a patch reads the node at its gIndex in the unmodified
    base tree (like copy sources), which must be an `SszSuperNode` of the
    entry's depth whose element count is at most the entry's; it copies the
    bytes, zero-extends them to the element count, writes the elements and
    builds a plain `SszSuperNode` (root computed lazily, the state root check
    covers it). Violations throw `IllegalArgumentException` naming the gIndex.
    Patches go into the same per-depth update set as supernode references
    (so a duplicate gIndex is rejected), are applied link by link in a chain
    application and are included in the chain's check that no entry
    addresses a delta field. `supernodeEntries` reads the patch section with
    the same checks as `deserialize` (patches are few and small) so it keeps
    rejecting everything `deserialize` rejects. Refcounts: patched supernodes
    are inline, nothing is stored or counted; whole references keep the
    content-addressed store. `verify-state-diffs` is unchanged (it hashes the
    stored supernodes; a patch has nothing stored, and a wrong patch fails
    the anchor reconstruction's root check like any other payload error).
    `section_bytes_total` / `section_entries_total` gain `section="patches"`
    (bytes = written element bytes) and the DEBUG line prints them.

70. **GREY. Patch threshold: a patch when it is smaller than a whole
    reference including the content the reference stores.** The differ
    compares the base and target supernode element by element
    (`Arrays.equals` over the packed slices) and accumulates the patch cost:
    two varint counts, plus per written element its gap varint and its bytes.
    The whole-reference cost is 32 (hash) + the target supernode's content
    bytes (the gIndex and depth are paid by both and left out). It patches
    while the patch cost stays below the reference cost and gives up at the
    first element that reaches it, so for full 256 x 121-byte validator
    supernodes it patches up to 254 changed validators, i.e. all but a
    rewrite of nearly the whole supernode. Not counted: deduplication (when
    the target content is already in the store from another node, a
    reference costs only its 32 bytes; the differ cannot see the store, and
    identical content written by two nodes only happens across sibling
    forks), RocksDB compression of either side, and read cost (a reference
    is one more batched point read; a patch is inline). A patch is only
    considered when the base node at the position is an `SszSuperNode` of the
    same depth with at least one element and the target has at least as many
    elements. So a snapshot never patches (its base, the default tree, holds
    only empty supernodes, also for bounded lists), a supernode the list grows
    into is a whole reference (also on progressive lists, whose base holds an
    empty supernode there), and a shrunk supernode falls back to a whole
    reference. The brief asked to assert the no-shrink case; it falls back
    silently instead, since tests diff unrelated random states, where an
    `assert` would fire under `-ea` (TreeDifferTest
    `shrunkSupernodeProducesWholeReference` pins the fallback). The applier
    accepts a patch over an empty base supernode, which the differ never
    emits.

71. **DERIVED. Patch measurements (temporary harnesses, removed; copies in
    the session scratchpad).** Synthetic node (SyntheticState schema, 102,500
    validators, 25 changed supernodes: 20 with one changed validator, 4 with
    three, 1 with ten, plus 4 deposits into the partially filled last
    supernode): 26 patches with 46 written elements. Before (whole
    references): 888 payload bytes of references + 786,984 bytes of
    supernode content = 787,872 bytes; after: 5,757 payload bytes, nothing in
    the supernode column (137x less). Real Electra state
    (`DataStructureUtil.randomBeaconState(8192)`, mainnet Electra spec, 5
    effective-balance changes and one exit epoch change, one appended
    validator): 5 patches with 6 elements, 1 whole reference (the appended
    validator starts a new supernode); before 177 + 154,880 = 155,057 bytes,
    after 767 bytes; the whole node payload is 861 bytes. Cost (best of 15):
    applying the 26 patches and hashing the resulting tree takes 5.15 ms
    against 0.08 ms for the equivalent whole references, whose roots are
    known (47): a patched supernode is rehashed when the state root is
    checked, ~0.2 ms each, once per distinct supernode left patched in the
    final tree (intermediate links are never hashed). A mainnet epoch node
    (~20-25 patched supernodes) adds ~5 ms to its reconstruction; a 22-link
    chain at most a few hundred distinct supernodes, ~0.1 s, against
    reconstructions measured at 1.2-3 s. The differ's element comparison is
    0.17 ms for the synthetic diff. Expected effect on the window soak
    (decision 61, 167 MiB of supernodes for 128 epochs): the per-epoch
    changed supernodes (~0.7 MB raw) and the coarse nodes' references leave
    the column, which keeps the snapshots' validator sets. StateDiffDagTest
    needed no refcount adaptation: its nodes with a changed validator are
    snapshots (written without ancestors) and its chains change no
    validators, so every counted supernode was a whole reference already; a
    new test (`changedValidatorIsPatchedInlineWithoutStoringOrCountingASupernode`)
    covers a patched diff node: no insert, unchanged counts on write and
    delete, `patches` section metrics, reconstruction, clean verify.

72. **GREY. Node record header version 2 without `prevNodeRoot` (C6).**
    `StateDiffNodeRecordCodec.VERSION = 2`; the header is version, block
    slot, state root, first and last epoch, level, snapshot flag, parent node
    root (91 bytes, was 123 with the 32-byte prev root); `patchLastEpoch`
    keeps its offset (the dropped field came after it). `StateDiffNodeRecord`
    has no prev field. No production reader remained since 27/40; the writer
    still resolves the previous node (first ancestor checkpoint root with a
    node) to start the parent walk and to name it in the no-parent WARN. A
    version 1 record is rejected with `TreeDiffCodec.VERSION_ONE_MESSAGE`
    verbatim (now public): every version 1 record holds a version 1 payload,
    so "payload version 1 ... must be re-synced" is accurate, and startup and
    `verify-state-diffs` show the same text whichever of the two is read
    first. The version is checked before the header length, so even a
    truncated version 1 record gets the message. Tests that read
    `prevNodeRoot`: assertions next to a parent assertion were dropped
    (StateDiffDagTest, 9); the late anchor of
    `StateDiffDatabaseTest` now asserts its parent, which for a node written
    late at the finest level is its previous node; the forced snapshot of
    the recovery integration test lost its prev assertion (not observable
    any more). Corrupt-record tests that wrote `01 02 03` (now read as a
    version 1 record) write `02 02 03` so they still test a truncated
    current record. New: `StateDiffNodeRecordCodecTest.rejectsVersionOneRecordWithResyncMessage`,
    `KvStoreDatabaseTest.stateDiffs_versionOneRecordFailsStartupAndVerifyWithResyncMessage`.

## v2 simplify wave (2026-10-29)

73. **GREY. The `STATE_DIFF_MODE` marker is the format gate; codec and seam
    tidy (simplify-v2 A-K).** B: this build writes and opens only marker `02`;
    a database marked `01` (version 1 nodes, or 534b4f39e0 soak clones with
    the old patch layout) fails at open with an unrecoverable
    `DatabaseStorageException` asking to re-sync (`verify-state-diffs` prints it
    and exits 1), any other marker as unknown: 64/72's outcome, earlier and
    once; the codecs lost their version 1 branches. G: `section_bytes_total`
    is now each section's exact encoded span, framing included (an empty
    section counts its count byte), summing to the payload less its version
    byte; `deltas` stays raw, `deltas_compressed` is a regular section with
    entries. I: `PATCHES` stores the element size per entry, not per group (the
    encoder check is gone), changing version 2 bytes in place under B's
    re-clone. A: no `TransactionNodes` seam; maintenance works on the
    transaction, whose `replaceNodeWithSnapshot` owns the discard-then-put
    ordering. C-F, H, J, K are behaviour-neutral.

74. **DERIVED. Allocation profile of the state diff code (2026-10-29, async-profiler
    4.3 attached to the mainnet soak node at 2661ed1c17, -Xmx8g, while syncing at
    ~15 finalizations per minute, i.e. ~150 node writes per 10-minute window).**
    `alloc` for 600 s: 820,801 samples, of which 6,891 (0.8%) have a frame in
    the state diff packages; the largest state diff sites are the transaction's
    committed-record memo reading node records (0.23%), `TreeDiffer.diffSubtree`
    (0.13%) and the codec's views and compression (<0.1% each); the profile is
    dominated by epoch processing (`UInt64` 18%, `byte[]` 14%, tuweni wrappers
    14%, `TreeUpdates`, `SimpleLeafNode`, `ValidatorStatus`). `alloc --live` for
    600 s (objects still alive at the end): 1,024 samples, 30 (2.9%) with state
    diff frames, from `diffSubtree`, the window's checkpoint lookups
    (`findCheckpointNode`/`checkpointBlock`, which read whole records for a
    presence check, efficiency finding 6) and the record memo; the retained
    heap is Teku's state views and trees (`SszUInt64`, `SszSuperNode`,
    `Validator`, branch nodes). No allocation or retention regression is
    attributable to the state diff storage; the 6 GB OOM at the chain head (run
    1 of this soak) is the known baseline plus the base cache's diverged trees
    (decision 42's A/B: +0.7 GB), not payload version 2. Flame graphs:
    tmp/statediff-alloc.html and tmp/statediff-alloc-live.html in the main
    checkout. Steady-state repeat still to do once the node is in sync.

