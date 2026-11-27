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


## Payload v2 soak on the local mainnet clone (2026-10-29, build 31634aa01c)

75. **DERIVED. Payload v2 on mainnet: deltas solved, participation leaves now dominate.**
    Fresh clone `soak/mainnet-v4` (archive + 4096-slot window, -Xmx8g), synced
    from the Oct-19 anchor to head ("Syncing completed" 10:41 local), stopped
    at slot 15,537,142 / finalized 485,533. Over 2,160 node writes:
    - Deltas: 10.85 GB raw -> 555 MB zstd, **19.5x** (~257 KB per node; the
      synthetic estimate was 4x on balances alone).
    - Leaves: 4.06 GB raw, 141.9 M entries = **~65.7k leaves (~1.9 MB) per
      node**, i.e. one full participation list per epoch node (the previous-
      epoch flags rewritten every epoch). Not application-compressed.
    - Patches 6.4 KB per node; whole supernode references 2.9 MB total.
    - Node write 70 ms average while syncing (walk 59 ms, encode 9.6 ms, base
      0.8 ms, store 0.1 ms); base cache 2,151 hits, 0 misses; 0 skipped, 0
      verification failures, 2 late anchors (migration only), 1 late block
      import warning in ~3 h.
    Offline on the stopped database: `verify-state-diffs` clean (145 nodes,
    9,357 supernodes all hash-checked, anchor chain 17 rebuilt in 1.23 s).
    `rocksdb usage --filter=STATE`: STATE_DIFF_NODES 266 MiB live / 313 MiB SST
    for 145 nodes = **~1.8 MiB per epoch on disk**, STATE_SUPERNODES 148 MiB
    (snapshot validator sets), refcounts 0.7 MiB. Against the v1 window soak
    (decision 61: ~3 MiB per epoch, 1.5 MiB nodes + 1.3 MiB supernodes) that is
    ~40% less per epoch and ~0.4 GiB per day of mainnet history. On-disk bytes
    per node are close to raw, so RocksDB compresses the participation leaves
    little (bottom-most compression disabled).
    Next candidate (payload v3): zstd the leaves section like the deltas;
    participation flags are low-entropy (mostly 0b111), so the expected per-
    epoch node is a few hundred KB. Gloas adds `ptc_window` (~0.4 MB per
    epoch, decision log fork-crossing notes) on top.
    Process: RSS 9.2 GB at -Xmx8g; GC 6,332 young (250 s total), 19 full
    (9.1 s) over 3 h of sync.


## Rebase onto master 715545a182 (2026-10-29)

76. **DERIVED. State diff columns renumbered: nodes 14, supernodes 15, refcounts 13.**
    Master commit 715545a182 (#11572, "Store light client bootstraps and fix
    gaps after checkpoint sync") added two hot columns in `V6SchemaCombined`:
    `LIGHT_CLIENT_BOOTSTRAPS_BY_SLOT` = 10 and
    `LIGHT_CLIENT_SYNC_COMMITTEES_BY_PERIOD` = 11, the IDs entry 10 had taken
    for `STATE_DIFF_NODES` and `STATE_SUPERNODES`. Master is authoritative,
    so the branch moves: `STATE_DIFF_NODES` to hot column 14 and
    `STATE_SUPERNODES` to 15; `STATE_SUPERNODE_REFCOUNTS` stays at 13 (entry
    24). 12 is still out (the hot blob sidecar column is `finalizedOffset +
    12`, i.e. 12 in a V4-layout hot store, entry 24); 13-15 are not hot
    columns on master and, in the V4 split layout, the hot store holds only
    hot columns 1-11 plus that blob sidecar column, so no `finalizedOffset +
    N` column lands there (finalized columns 13-15 and 17-18 of a V4 finalized
    store live in the separate finalized database; V6 and tree layouts use
    offset 128). The renumbering is applied in the phase 1 commit, so no
    commit of the rebased branch ever defines 10 or 11 for state diffs;
    `StateDiffDaoTest.columnIdsAreFourteenFifteenThirteen` pins the IDs.
    Variables are untouched (`STATE_DIFF_MODE` 13, `STATE_DIFF_WINDOW_START`
    14; master's variables stop at 12).
    Consequence: every database written by a branch build before this rebase
    (up to 31634aa01c, including the two AWS soak boxes, archive
    i-045302325bafe77fd and minimal i-06bfe88f23cd166e3) is incompatible with
    a rebased build. Its node records sit in column 10 and its supernodes in
    column 11, which a rebased build opens as light client bootstraps and
    sync committees, while columns 14 and 15 are empty: the `STATE_DIFF_MODE`
    marker says the DAG exists but no node is found (and the light client
    endpoints would decode garbage). No migration is written: those databases
    need a fresh data directory (re-sync from a checkpoint) before the rebased
    build runs on them, or a one-off migration that moves the column 10 and
    11 families to 14 and 15 and drops the light client entries. The format
    marker is not bumped: the payload bytes are unchanged, only their column.


## Tier 3 attempt (2026-10-29, exploration; code and report on branch worktree-state-diff-tier3, commit a430ed5776, not on this branch)

77. **DERIVED. Tier 3 attempt: frozen exited-validator supernodes.** Built per
    spec section 9 behind `--Xdata-storage-state-diffs-freeze-exited`:
    `FrozenSuperNode` (hash, depth, count, loader; `SoftReference` memo; any
    write thaws), `FrozenRanges` (works on bounded and progressive lists),
    a `SupernodeFreezer` hook in `TreeDiffApplier` for whole references, the
    storage `ExitedValidatorFreezer` (every validator: effective balance 0,
    activation set, exit < E, withdrawable <= E, E = node epoch), applied in
    `reconstruct`/`reconstructAndCache`; the differ compares frozen nodes
    without loading and the transaction's supernode sink is lazy, so writes
    (snapshots included) load nothing. Consensus fast paths in 7 methods
    (status creation, active indices, registry updates x2, Electra effective
    balance updates, withdrawal sweep), equivalence-tested on Electra, Fulu
    and Gloas. Harness on the mainnet finalized state (epoch 483,376,
    2,389,284 validators): 65.1% of validators meet the rule but only 1,005
    of 9,334 depth-8 supernodes (10.8%; 19.8 / 30.4 / 40.1 / 47.7 / 53.7% at
    depth 7 / 6 / 5 / 4 / 3) are wholly withdrawn; reconstructed state 607.8
    MB plain vs 579.0 MB frozen (28.9 MB saved per distinct registry copy,
    not multiplied by the states sharing it); epoch transition 6.0 -> 5.6 s
    cold, 5.3 -> 4.8-5.0 s with a warm pubkey index; status creation -16%,
    active indices -18%. Only load site: the cold `ValidatorIndexCache` scan in
    `processPendingDeposits` (all 257,280 frozen validators, every freshly
    reconstructed state); and a full scan pins the loaded content again
    through the list view cache (+1.2 GB for plain and frozen alike), which
    erases the memory gain. Theoretical pruning hole documented (a frozen
    hash counted only by a pruned chain while a retained chain reached the
    same content by a patch). Verdict: not worth it at depth 8; a finer
    validator supernode depth (or a sparse-supernode unit) and a process-wide
    pubkey index are the prerequisites for any gain. Full report:
    `.superpowers/sdd/2026-10-29-state-diff-tier3/tier3-attempt-report.md` on
    that branch.


## AWS mainnet soak, archive and minimal boxes (2026-10-29 to 2026-11-13, builds 2454573a3e and 29aa3cff11)

78. **DERIVED. Archive box filled its disk with backfilled blocks, and verify-state-diffs leaked the node records it read.**
    Both boxes ran 2454573a3e (the rebased branch, fresh data directories per
    entry 76) from 2026-10-29.
    - **Archive disk full, 2026-11-06 06:40 UTC to 2026-11-13 13:31 UTC.**
      Every database commit failed for a week, so archive soak data for that
      span is invalid. Cause: archive mode backfills blocks to genesis
      (`storesAllBlocks` -> `fetchAllHistoricBlocks`), not state diffs. RocksDB
      LOG stats: `FINALIZED_BLOCKS_BY_SLOT` 769.5 GiB of 783 GiB (slots 3.5M ->
      15.6M; LZ4 at ~0.53 of raw SSZ), `STATE_DIFF_NODES` 6.7 GiB (~1.8 MiB per
      epoch, as entry 75 predicted), data columns 5.9 GiB, supernodes 0.3 GiB.
      After `/data` was grown to 1.3 TiB, RocksDB stayed in its background-error
      state until Teku was restarted; it then resumed from the head it last
      saved on Nov 6 (slot 15,593,601). Sizing note for any archive soak: budget
      for full block history (~0.8 TiB on mainnet today), not for the DAG.
    - The archive box's 2026-11-02 00:05 reboot was an AWS SSM Run Command
      (CVE-2026-98432 check, nftables mitigation, `linux-aws` kernel upgrade,
      `systemctl reboot`), not Teku. Fleet patching can reboot the boxes again.
    - **Offline verify, Teku stopped (2026-11-13 14:00 UTC, 2454573a3e):** clean
      (4,096 nodes, 20,947 supernodes all hash-checked, anchor rebuilt from a
      16-link chain in 4.2 s), but only at `-Xmx13g`; it ran out of heap at
      `-Xmx10g`. The debug command ignores `--Xdata-storage-state-diffs`; the
      database marker decides.
    - **Cause and fix (29aa3cff11).** `StateDiffNodeRecordCodec.decode` built
      `stateRoot` and `parentNodeRoot` as `Bytes32.wrap(encoded.slice(...))`,
      views that keep the whole record array (payload included) alive.
      `StateDiffDag.verify` keeps every node's parent root in `parentRoots`
      until the report is built, so it held every node record read, about the
      6.7 GiB of `STATE_DIFF_NODES`. Decode now copies the two roots
      (`readRoot`); the payload is still read in place. The format is
      unchanged. Test: `StateDiffNodeRecordCodecTest.decodedRootsDoNotShareTheRecordArray`.
      Re-run on the archive at `-Xmx10g` (2026-11-13 16:24 UTC): 5,046 nodes,
      22,999 supernodes, 0 problems, anchor rebuilt from a 9-link chain in
      2.9 s, 79 s total, peak heap 1,088 MB, 788 MB live after GC, no full GC.
      The running node keeps decoded roots only for the length of a chain walk,
      so its heap is not expected to change; not measured.
    - **Catch-up GC on the archive (2454573a3e, -Xmx10g, 15.6 GB host).** After
      the 14:04 restart it caught up at ~225 slots/min net, with 98 full GCs
      (218 s) in the first 1.6 h, against 7 in 11 days at head before the disk
      filled. Syncing drives full GCs. Whether they stop at head is open (see
      below).
    - Historical block backfill below slot 3,510,451 did not move after the
      restart. That is by design: `HistoricalBlockSyncService.isActive()`
      requires the node to be in sync, so backfill waits until head.
    - One new warning after the restart, from master code: `ProtoArray: Initial
      canonical block root not found`, probably because the saved canonical
      head was not among the blocks loaded at startup. Benign so far.
    - **Minimal box (2454573a3e, at head throughout):** 3,452 nodes written and
      3,436 deleted since 2026-10-29, 0 verification failures, 0 storage
      errors; 210 full GCs (623.5 s) over 15 days.
    - **Redeploy:** both boxes run 29aa3cff11 (cb2dd56e6f plus the fix) since
      2026-11-13 16:33 UTC on their existing databases. cb2dd56e6f differs from
      2454573a3e only in tests and one import, and master changed no schema
      between the two bases. Both restarted cleanly: anchor rebuilt, 0
      verification failures.
    - **Archive at head (29aa3cff11, same evening).** Catch-up from the 16:33
      restart took until ~18:03 UTC (~15.6k slots), with 118 full GCs (256 s),
      a rate close to 2454573a3e's. From 18:44 to 19:04 UTC at head, with
      backfill running, there were 0 new full GCs, and old gen rose and fell
      between 5.5 and 7 GB. Full GCs come from syncing and stop at head. The
      20-minute window is short; the longer baseline is the 7 full GCs in 11
      days at head before the disk filled.
    - **Backfill resumed at head.** It reached slot 3,418,819 by 19:04 UTC at
      ~2,000 slots/min (~28 h to genesis), with 482 GiB free. So enough peers
      still serve blocks that old.


## Rebase onto master 21788914ee (2026-11-14)

79. **DERIVED. `STATE_DIFF_MODE` moved from hot variable 13 to 15; `STATE_DIFF_WINDOW_START` stays at 14.**
    Master commit 21788914ee (#11573, "FCR: Add a mechanism for providing a
    restart-resilient confirmed root") added the hot variable
    `FAST_CONFIRMED_ROOT` = 13 (`Bytes32`) in `V6SchemaCombined`, the ID entry
    10 had taken for `STATE_DIFF_MODE`. Master is authoritative, so the branch
    moves, as in entry 76: `STATE_DIFF_MODE` to hot variable 15.
    `STATE_DIFF_WINDOW_START` keeps 14 (entry 51), so only one
    variable changes and the mode marker takes the next free ID above it.
    Master's static variables now stop at 13; the `finalizedOffset + N`
    variables (1-7) are at 129-135 in the V6 and tree layouts and in the
    separate finalized database in the V4 split layout, so 14 and 15 collide
    with nothing. The renumbering is applied in the phase 1 commit (15, with
    14 left free until the retention window commit defines it), so no commit
    of the rebased branch defines 13 for state diffs;
    `StateDiffDaoTest.columnIdsAreFourteenFifteenThirteen` pins both variable
    IDs. Columns are untouched (nodes 14, supernodes 15, refcounts 13).
    The same master commit added `fastConfirmedRoot` to `OnDiskStoreData`,
    `StoreBuilder`, `Store` and `StorageUpdate`; the branch keeps it next to
    `stateDiffStorageActive` and the persistence policy, `fastConfirmedRoot`
    first.
    Consequence: every state diff database written by a branch build before
    this rebase (up to fc17eb9f59, including the two AWS soak boxes, archive
    i-045302325bafe77fd and minimal i-06bfe88f23cd166e3, on 29aa3cff11) is
    incompatible with a rebased build. Its format marker (one byte, `02`) sits
    at variable 13, which a rebased build reads as `FAST_CONFIRMED_ROOT`:
    `Bytes32.wrap` rejects the one-byte value, so loading the store at startup
    fails; and variable 15 is empty, so the database would not be recognised
    as a state diff database either. No migration is written. Before a
    rebased build runs on such a database it needs a one-off migration, with
    the node stopped: copy the value of hot variable 13 to hot variable 15 and
    delete variable 13 (master then sees no fast confirmed root, which is its
    normal first-start state). Variable 14 does not move. The alternative is
    a fresh data directory. The format marker is not bumped: the payload and
    record bytes are unchanged, only the marker's ID. Databases without state
    diffs never wrote variable 13 on the branch and are unaffected.


## Neutral simplify wave 2 (2026-11-14)

80. **DERIVED. Behaviour-neutral simplifications (wave "neutral 2": N1-N5, N8, N9, N11-N14 applied; N6 and N10 skipped).**
    No on-disk format, metric, log line or CLI output changes, except where
    noted.
    - **N1, members only tests called.** `TreeDiffApplier.apply` went with the
      `includeDeltas` branch; a single diff is a chain of one
      (`startChain`, `apply`, `finish`), and the per-link reference of entry 40
      is now a chain of one link per step (tests share one helper per module,
      `TestTreeDiffs.apply` in the `infrastructure/state-diff` and in the
      storage state diff test sources).
      `UInt64DeltaCodec` keeps only `applyInPlace`; `applyDeltaValues` and
      `readDeltaValues` are private. `SupernodeSource` is `loadAll` only.
      `DiffLevels.period` / `levelOf`, `TreeDiff.empty` / `isEmpty` /
      `entryCount`, `TreeDiffCodec.serialize`,
      `StateDiffNodeRecordCodec.encode(record)` and
      `StateDiffDag.reconstruct(NodeReader, Bytes32)` went (test helpers:
      `TestTreeDiffs` in both modules, `TestNodeRecords`).
      `StateDiffDag.reconstructionChain` stays, package-private and
      `@VisibleForTesting`: the tests assert chains of roots, which no
      remaining entry point returns.
    - **N2, amends 51 and 53.** `Database.getEarliestAvailableFinalizedStateSlot`
      and its four implementations went: no production caller. The archive
      tests read the window start from the DAO; the fact of entry 53 (served
      from the start slot of the window start epoch) is unchanged.
    - **N3, amends 45.** `StateDiffDagConfig(enabled, retainedSlots)`: no
      levels. `StateDiffDag` takes its `DiffLevels`; production passes
      `StateDiffDag.DEFAULT_LEVELS` (256/64/16/1, the prune levels, used by
      archive databases too); `retainedSlots` reaches
      `StateDiffHotStateStorage` directly. The effective-config rebuild in
      `KvStoreDatabase` is gone. One factory, `disabled()`; tests build an enabled record directly.
    - **N4.** Master's four-argument `KvStoreDatabase` constructor is back
      (state diffs disabled, no-op metrics; a marked database still opens in
      state diff mode); `createV4` and the nine upstream `KvStoreDatabaseTest`
      sites use it again.
    - **N5.** `StateDiffDagTransaction` keeps one node map (read-through on a
      miss, overwritten by a write or delete): `commit()` writes no node
      records (they go to the updater when put or deleted), so nothing there
      had to tell a read from a write. The written state trees come from
      `writtenNodes`; `WrittenNode` lost its root.
    - **N8.** `reconstructNode` is folded into `reconstructChain`, which counts
      every failure as before; three rungs remain.
    - **N9, amends 46.** `HotStateStorage.activateIfRequested(Supplier<HotUpdater>)`
      replaces `getPendingActivation` and `Activation`: the legacy storage
      opens the hot transaction from the database's own updater supplier,
      writes, commits and logs the same INFO line from the same logger after
      the commit; nothing is opened when no switch is due (a LevelDB
      transaction counts in a metric). `addFinalizedState(updater, root, state)`
      replaces `holdsFinalizedStates()` and the booleans threaded through
      `KvStoreDatabase`.
    - **N11, amends 48.** Report order uses `Bytes32`'s natural order (tuweni
      2.8.0 `Bytes.compareTo`: bit length, then unsigned bytes from the
      first; for equal lengths the order of the lower-case hex strings). The
      once-a-minute reconstruction ERROR uses `Throttler` in milliseconds with
      the same text and count. One helper writes the chain suffix of the four
      reconstruction messages. The DAO's batch supernode and refcount defaults
      return `Map.of()`.
    - **N12, amends 73.** The dedicated message for marker `01` is gone; any
      marker other than `02` fails as an unknown format.
    - **N13.** `StateDiffDagConfig` and `StateDiffVerificationReport` moved to
      `tech.pegasys.teku.storage.server`.
    - **N14.** Citations in `StateDiffHotStateStorage` use the log numbering
      (13, 43, 54, 62); `Database.verifyStateDiffs` names the supernode
      content-hash check (47), `StateDiffVerificationException` the missing
      supernode (48). `Store`'s `latestEpochBoundary` is master's line and
      kept its name.
    - Skipped, **N6** (`writeNode` without its `forceSnapshot` flag, `findPrev`
      only when a parent is wanted): the split added about 30 lines, and
      resolving the previous node lazily changes a corruption path. Latent at
      this tip and kept as is: `writeNodeOrSnapshot`'s snapshot fallback calls
      `findPrev` again, so when the nearest ancestor record is undecodable it
      re-throws instead of writing the snapshot.
    - Skipped, **N10** (`Store.getBlockStateIfAvailable` back to master's
      body): the Store's own regeneration base walk
      (`getClosestAvailableBlockRootAndState`) calls it, and in state diff mode
      finds checkpoint block states that sit only in the epoch states cache (the
      block state cache holds 8 entries); master's body would load the persisted
      base from the DAG instead, a behaviour and performance change no soak
      covered. The wave's test
      `StoreTransactionUpdatesFactoryTest.diffMode_finalizedStatesIncludeEpochStatesWhenBlockStateEvicted`
      stays: it pins that an archive update collects finalized states from the
      epoch states cache in state diff mode.

81. **DERIVED. A snapshot write reads no earlier node (fixes the fallback of entry 80, N6).**
    `StateDiffDagTransaction.writeNode` looked up the previous node
    (`findPrev`) before deciding whether the node is a snapshot, so an
    undecodable record of the nearest ancestor checkpoint block failed writes
    that need no parent: a periodic snapshot threw, and
    `writeNodeOrSnapshot`'s forced-snapshot fallback threw a second time,
    failing the storage update it exists to save. The previous node is now
    looked up only when a parent is wanted (not forced, not periodic); the
    `forceSnapshot` flag and the single write path stay, so this is the
    three-line form of N6 without its restructuring. Behaviour change, in a
    corruption path only: with an unreadable previous record a periodic node
    is written as a `periodic` snapshot without an ERROR, and any other node
    logs the fallback's ERROR and is written as a `forced` snapshot. Healthy
    databases see the same reasons, the same `no_parent` WARN and the same
    counters; the `base` write phase of forced and periodic snapshots no
    longer includes the previous record's read. Tests (both failed before the
    change with "Corrupt state diff node record"):
    `StateDiffDagTest.writeNodeOrSnapshotWritesSnapshotWhenPreviousNodeRecordIsUnreadable`,
    the first unit test of that fallback, and
    `periodicSnapshotIsWrittenWhenPreviousNodeRecordIsUnreadable`.


## AWS soak: hot variable migration and redeploys (2026-11-14, builds acd8591d5c and 3eccf1f8d5)

82. **DERIVED. Soak boxes migrated to hot variable 15, then moved to acd8591d5c and 3eccf1f8d5; the soak check now requests states.**
    - **Fleet reboot.** AWS patching rebooted both boxes at 10:06 UTC
      (`linux-aws` 7.0.0-1014 -> 1017), as on Nov 2 (entry 78). Teku came back
      up by itself on 29aa3cff11.
    - **Entry 79 migration.** A one-off tool, kept outside the repository
      (`~/soak-migrate/` in the sandbox VM; on the boxes
      `/opt/teku/migrate-statediff-mode.jar`), opens the hot RocksDB with
      Teku's rocksdbjni, with auto-compaction off. In one synced write batch it
      puts variable 15 = `02` and deletes variable 13, then reads both back.
      It refuses to write when variable 13 holds 32 bytes (a fast confirmed
      root), when variable 15 is already set, or when neither variable holds the
      marker. It defaults to a dry run, and a second run does nothing.
      `--revert` moves the marker back to 13. It was tested on synthetic
      RocksDB fixtures, then run with Teku stopped: minimal at 14:43 UTC,
      archive at 14:50 UTC. Variable 14 is epoch 483,376 (`0x76030`) on the
      archive and absent on minimal. Verify with acd8591d5c at `-Xmx10g`:
      minimal 12 nodes and 9,403 supernodes; archive 5,801 nodes and 26,129
      supernodes in 91 s; 0 problems on both. Teku was down ~20 s on minimal
      and ~2 min on the archive. Rolling back to a build from before the
      rebase now needs `--revert` first.
    - **acd8591d5c at head.** Each box rebuilt its anchor and wrote its first
      node with 0 verification failures. Writes took <= 250 ms, except 2-3 right
      after the restart that missed the cold base cache (~2.8 s rebuild per
      miss). Archive full GCs: 11 in the first 48 min after the restart
      (timing unknown, no GC log), 2 within ~2 min of historical state
      requests, then 0 in 25 min at head without requests (15:52-16:17 UTC);
      old gen stayed between 4.8 and 6.3 GB of 10 GB. Minimal had none.
    - **State requests in the soak check.** `soak-check.sh` now calls
      `/eth/v1/beacon/states/{slot}/root`, so the node rebuilds the state but
      returns only 32 bytes, and compares the root with the block header's
      `state_root`. The archive gets a random historical slot since its anchor
      (slot 15,468,032). Minimal gets a random slot in the first unfinalized
      epoch, because head minus a few slots is served from memory (4 ms) and
      proves nothing. On acd8591d5c: archive 7.8-11.6 s, minimal 0.3-2.0 s,
      every root matched. Each historical request is expected to cost the
      archive about one full GC. Two additions on 2026-11-15:
      - The archive also gets an unfinalized request.
      - Each root is also compared with the header `state_root` that a public
        mainnet beacon node (PublicNode) serves for the same slot. The request
        goes out from the sandbox VM, so only the slot number leaves it.

      The first run matched on all three requests, locally and against
      PublicNode.
    - **Backfill toward genesis.** ~2,000 slots/min near slot 3.4M on Nov 13,
      ~750 slots/min near 2.4M on Nov 14, at 2,160,171 by 21:43 UTC. The
      change in rate was not investigated.
    - **Redeploy of 3eccf1f8d5 (entries 80-81, no format change).** Each box's
      deploy was gated on a verify: with Teku stopped, the new build verified
      the database at `-Xmx10g` before `current` was switched, and a failed
      verify would have gone back to the previous build. Minimal at 21:31 UTC
      (9 nodes, 9,406 supernodes) and archive at 21:37 UTC (5,865 nodes,
      27,145 supernodes, 91 s) were both clean. After the restart, both wrote
      nodes on the new write path with 0 verification failures, and both state
      requests matched: archive historical in 15.9 s right after the restart,
      minimal unfinalized in 2.8-3.9 s.

83. **DERIVED. `storage_state_diff_reconstruct_list_builds_total` removed; the write phase counter stays (round 3 items M2 and M1).**
    The list builds counter (entry 40) proved that a reconstruction folds the
    delta sections of its chain and builds each packed uint64 list once. Since
    the per-link fallback went (neutral wave of 2026-10-28) the applier builds
    lists in one place only, at the end of a chain, so on a running node the
    counter is the number of delta fields the chains touched, at most two per
    reconstruction: it cannot show a regression and no soak cites it. The
    counter, `StateDiffMetrics.onReconstructListBuilds` and its call in
    `StateDiffDag.reconstructChain` are gone. `ChainApplication.listBuilds()`
    stays, package-private, for `TreeDiffChainApplicationTest`, which pins
    one build per field over a chain of several links; `StateDiffDagTest`'s
    two fold tests now assert only that a reconstruction equals per-link
    application (renamed `reconstructChainMatchesPerLinkApplication*`).
    `storage_state_diff_write_phase_seconds_total{phase}` is kept: it is the
    only source of the base / walk / encode / store split of a node write
    (entry 75 and the results table of `docs/state-diff-storage.md` quote it),
    and a leaves-compression payload or a differ change would be read off it.
    To be reconsidered when the metric surface is trimmed for upstream.

84. **DERIVED. The window's previous oldest node is looked up through the transaction (round 3 item E2, record half); E1 not applied.**
    With a retention window, `StateDiffHotStateStorage.finalizedRetention`
    tested whether the previous window start's checkpoint block has a node
    with `dao.getStateDiffNode(root).isPresent()`, outside the transaction's
    record memo, and `retainWindow` then loaded that node's chain through the
    transaction: the same record, about 2 MB on mainnet, was read twice per
    finalization that moves the window (entry 74's profile had named the
    site). `StateDiffDagTransaction.hasNode(root)` reads through the memo and
    `finalizedRetention` calls it, so the chain load finds the record already
    read. Neutral: the root is an old finalized checkpoint block the
    transaction has neither written nor deleted, so its view equals the
    committed one. Test, failing before with a duplicate read:
    `StateDiffDagTest.windowRetentionAfterLookingUpThePreviousOldestNodeReadsEachNodeRecordOnce`
    (the call from `finalizedRetention` itself is covered for behaviour by
    the window tests of `StateDiffDatabaseTest`, not for its read count). Not
    done: `checkpointBlock(previousStart)` is still loaded twice, once for the
    lookup and once for the window's checkpoint roots; avoiding it needs a
    special case in front of the fallback walk for one block load. Unbounded
    archive and prune databases never ran this path.
    **E1 (drop base cache slot 0) is not applied:** the claim that the
    coarsest slot is never a diff base holds on a linear chain only. A probe
    with levels 4/2/1 (epochs 0..3 written, then a second node for epoch 2)
    found the snapshot in slot 0 alone and served from it as the sibling's
    diff base, a hit and no miss. Slot 0 also ends the chains of reads of
    nodes older than the latest level-1 node. What it retains at head is
    unmeasured since the resize of entry 49.

## Test suite tidy (2026-11-15)

85. **DERIVED. Test-only tidy (round 3 items T1-T4): T1a, T1b, T3 (KDT fixture, DAGT helpers) and T4 applied; T2 and three T3 parts skipped. No `src/main` change.**
    **T1a.** `StateDiffDatabaseTest` makes its "state diffs only" assumption
    once, in a `@BeforeEach` that runs after `AbstractDatabaseTest.setup`
    (which only builds the genesis, as it did before the per-test
    assumption), instead of in 17 tests. Its one legacy test,
    `legacyBehaviourUnchangedWhenDisabled`, moved unchanged to `DatabaseTest`
    next to the other legacy-only templates: the unit test
    `KvStoreDatabaseTest.stateDiffs_legacyStorageWithoutFlagOrMarker` does not
    cover it (no blocks, no finalization, so neither "no state diff node after
    import and finalization" nor "finalization writes LATEST_FINALIZED_STATE",
    and only the in-memory V6 database).
    **T1b.** In memory, LEVELDB2 runs as V6 and LEVELDB_TREE as ROCKSDB_TREE
    all the way down (`InMemoryStorageSystemBuilder.build`, its restart copy,
    `InMemoryKvStoreDatabaseFactory`, `DatabaseContext`; nothing else reads
    the version on that path), so `TestDatabaseInvocationContextProvider`
    maps each in-memory state diff version to its equivalent and keeps one
    context per pair. File-backed and legacy contexts are unchanged. 22 → 20
    contexts per template.
    **T3.** `KvStoreDatabaseTest`: the corrupt-anchor, legacy-bootstrap and
    finalized-block-summary tests use `StateDiffFixture` (the last one spies
    the fixture's DAO and takes the genesis block from the fixture's anchor).
    `StateDiffDagTest`: `overwritePayload` / `overwriteStateRoot` replace the
    four hand-built record rewrites, `verificationFailures()` the four inline
    counter reads, `updateLoggingOneError` the three identical LogCaptor
    blocks (the window test's differently shaped log assertion stays).
    **T4.** `StorageSystemTest` sets every `StoreConfig` field off its default,
    compares `StorageSystem.requestingStateDiffs`'s copy field by field
    (reflection, flag excepted) and pins the field list, so a new field fails
    until the copy carries it. Checked by dropping `blockCacheSize` from the
    copy: the test failed on that field.
    Skipped: **T2**, every one of the four mode-resolution unit tests asserts
    something no integration test does (state before `storeInitialAnchor` or
    before the store loads, the verify error message, verification and
    `stateDiffStorageActive` after reopening on the marker alone), so none is
    deleted. **T3 shared DAO factory**: the five blocks need a spied
    accessor, the schema or the db alongside the DAO, so a factory would
    only serve the existing `createDao`. **T3 state-diff test holder**: the
    four one-line fields are used at ~70 sites, a holder makes each longer.
    **T3 StorageUpdate literal**: no testFixtures home serves both source
    sets without a new class for one literal.
    Counts (tests/failures/skipped) before → after: state-diff 121/0/0 →
    121/0/0 (up to date), storage test 1118/0/17 → 1119/0/17 (+1
    `StorageSystemTest`), storage integrationTest 3334/0/279 → 3042/0/269
    (`DatabaseTest` 2816/0/32 → 2580/0/30, `StateDiffDatabaseTest` 396/0/246
    → 340/0/238), teku 532/0/2 → 532/0/2, chainstorage 15/0/0 → 15/0/0 (up
    to date).

## Parked until the feature is in master (2026-11-16)

86. **RULING (Enrico). Before the merge, simple code and a small diff win; what the third simplification round left is parked here.**
    Until the branch is in master, no change goes in that complicates the code
    or widens the diff for speed or structure alone. A slow implementation is
    still not accepted: good enough is the bar. Changes that make the code
    shorter stay welcome (entries 80, 83, 84 and 85 were of that kind). This
    entry is the one list of what round 3 (2026-11-14, four reviewers on the
    whole branch) raised and did not apply. Items marked "review" are the
    reviewers' reading and were not re-verified; the others were checked
    against the code or measured.

    **Design calls, undecided**
    - **Header column for node records** (v2 simplify skips 5/6; review for
      the counts). Parent-pointer walks and presence checks read whole 1-3 MB
      records for a 91-byte header: C + 2 full reads per prune finalization
      (C = length of the previous anchor's chain), about 25 MB on average,
      time unmeasured. A fixed-size header column, optionally carrying the
      supernode hash list so that deletion needs no payload, would remove
      those reads, the tolerant deletion of undecodable payloads, and about
      115 lines that exist only because header and payload share one value
      (`PayloadView`, the reserved prefix, `readRoot`, `patchLastEpoch`, the
      raw record memo). Cost: a schema change (one column, a marker bump,
      re-synced databases).
      Possible without a format change: `firstEpoch` and `lastEpoch` of a
      stored record are read only for their own upkeep and a DEBUG line
      (checked by grep), so the `lastEpoch` patch could stop and records
      become write-once, about 35 lines less.
    - **Storage mode resolved once, at open** (review). "Flag or marker" is
      known at open, but the switch happens later, at `createMemoryStore` or
      `storeInitialAnchor`; hence the volatile strategy field, the legacy
      strategy holding its successor, `isStateDiffStorageActive() ||
      enabled()` in `StorageService`, and the flag-only checks that miss a
      marker-only database (entries 46, 62). Reconciling in one place at open
      would delete about 70 lines and move the migration to database open.
    - **Anchor chain repair exists in prune mode only** (review). Archive
      retention never loads the anchor chain (entry 55), and a restart treats
      the same condition as unrecoverable. The tolerant paths catch only
      `StateDiffVerificationException`, and no soak has recorded a
      verification failure or a repair. Either check "the anchor chain loads"
      in every mode before retention (drops `loadChainPrefix`, about 35
      lines), or delete the repair (about 90 lines and its tests).
    - **SSZ list layout outside infrastructure/ssz** (first round A8, never
      ruled; review). `PackedUInt64Lists` builds and reads the packed uint64
      list tree itself; the write side could call the schema's bulk
      deserialiser and the read side `TreeUtil.iterateLeavesData`, about 90
      lines less. The 1.5 ms list read of entry 40 would have to be measured
      again, and PR 11627 edits the same schema interfaces, so order matters.
    - **`Store.getBlockStateIfAvailable` also returns epoch states in state
      diff mode** (entry 80, N10). Narrowing it back to master's meaning for
      outside callers while the store's own regeneration base walk keeps the
      wide lookup is a design choice nobody has made; all soaks ran wide.
    - **Tests:** the persistence policy is tested at policy, factory and store
      level (left alone, entry 85); `StateDiffDatabaseTest` still generates
      and skips its templates for the legacy contexts, which a per-class
      filter on `@TestDatabaseContext`, master's fixture API, would avoid.

    **Optimisations, deferred**
    - **Applier leaf section (round 3 item E3), measured.**
      `TreeDiffApplier.UpdatesByDepth` groups every entry of a section into
      `TreeMap<Integer, TreeMap<Long, TreeNode>>`, copies keys and values into
      two lists, and `expandNonBranchAncestors` walks from the root to every
      entry. Benchmark (throwaway, not kept): mainnet Fulu state schema, 2.2M
      validators, `previous_epoch_participation` rewritten on every link, so
      68,750 leaves per link at depth 42 (the v2 soak had ~65.7k leaves per
      node, entry 75); 16-link chain, median of 8 rounds after 6 warm-up
      rounds, two runs, Apple silicon, JaCoCo agent and assertions off (the
      Gradle test JVM attaches both by default; an init script has to switch
      them off inside `gradle.projectsEvaluated`, and the run should print its
      JVM arguments to prove it). Per link:

      | step | ms |
      |---|---|
      | whole `ChainApplication.apply` | 13.6-14.5 |
      | grouping into the `TreeMap`s | 7.1-8.5 |
      | `expandNonBranchAncestors` | 6.2-6.8 |
      | key and value list copies | 0.9-1.0 |
      | `TreeNode.updated(TreeUpdates)` itself | 1.6-1.8 |
      | `LeafNode.create` for the entries | 0.5 |
      | payload decode, for scale | 0.7-0.8 |

      About 12 of the 14 ms are bookkeeping. On the same input, one pass
      appending to per-depth lists with a strictly-increasing check (the codec
      decodes entries in gIndex order) takes 0.4 ms, and an ancestor check
      that starts each entry where its path leaves the previous entry's path
      takes 0.55-0.6 ms: about 3 ms per link in all. That is about 11 ms per
      link, roughly 0.2 s of the 1.23 s a 17-link anchor chain took on the
      same machine (entry 75), by arithmetic and not measured on a real
      reconstruction; writes gain nothing. Cost: 30-40 more lines. To get
      right: the supernode section merges whole supernodes and patches, so its
      entries are not sorted and the grouping must sort on an out-of-order
      entry and still reject duplicates; and once an expansion replaces a
      subtree the kept path is stale, so the check must fall back to the full
      walk. Folding the leaf sections of a chain so they are applied once
      would add a few tens of ms, by estimate, for a last-writer-wins rule
      across links: not worth it.
    - **Second load of the window's previous start block** (entry 84): one
      finalized block deserialised and hashed twice per finalization on an
      archive database with a retention window.
    - **Storage updates with nothing for the DAG** (round 3 item E4; review):
      `StateDiffHotStateStorage` reads the finalized checkpoint and builds a
      transaction for every update. Microseconds per block; an early return.
    - **Smaller or speculative, unmeasured** (review): `patchLastEpoch` copies
      a whole record to change 8 bytes (rare); the ancestor checkpoint list is
      built under the store write lock (64-96 lookups at head); coarse base
      cache entries could hold their delta fields as `long[]` instead of
      trees; multi-MB transient arrays per write may be humongous allocations
      while syncing (no GC evidence).

    **Closed, not to be raised again without new evidence**
    - Dropping the coarsest base cache slot (E1): it is a diff base for fork
      siblings (entry 84).
    - Canonical packed leaves in infrastructure/ssz to replace the differ's
      leaf data-length rules (first round C2): base seeding (entry 49) and
      the participation data of entry 75 removed most of its value.
    - `expandNonBranchAncestors` moved into the differ or into
      `LeafNode.updated` (first round C3): both alternatives are worse.
    - Pinning checkpoint block states so no node is skipped (first round C5):
      a migration produces the same gaps, so the gap handling stays.
    - `write_phase_seconds_total{phase}` stays for now (entry 83).

## External review and non-finality (2026-11-23, tip 7b88f16309, no code change)

87. **RULING (Enrico). The design must stay a good choice under adversarial conditions, long non-finality included; the fixes of entries 88 and 89 are parked until a stall test gives evidence.**
    "Our client must be rock solid." Nothing from entries 88 and 89 is applied
    now, and entry 86 still holds. The requirement and the parking are
    Enrico's; the release gate is the proposal of the review exchange, recorded
    as agreed:
    - **The test:** the same workload with progressively longer finality
      stalls, on the branch and on master, with competing branches and skipped
      checkpoints, a cold restart during the stall, then finality restored and
      the recovery interrupted by another restart.
    - **Reported separately:** peak disk before finality returns, and the
      longest import delay while it recovers. Master is the baseline: what
      counts is the delay the DAG adds, not the total (entry 89).
    - **Correctness:** after interrupting recovery and restarting with cold
      caches, the finalized anchor and every checkpoint expected to remain
      readable reconstruct to their expected state roots. Without this a
      cleanup that deletes too much would score well on both numbers.
    - **The result that matters is the slope:** doubling the stall may double
      the retained history; it should not double the cost of writing the next
      checkpoint or of moving the anchor.
    - **The ancestor checkpoint walk is left alone** unless measured tail
      latency shows it delays import. `collectAncestorCheckpointRoots` visits
      every hot block back to the finalized root once per checkpoint per
      branch, in memory, one fork choice lookup per block; under the store
      write lock according to entry 86, a review reading that was not
      re-verified. After a one-week stall that is about 50,000
      blocks per walk: single-digit milliseconds by estimate, not measured.

88. **DERIVED. Four findings of an external review of 7b88f16309 against master bc4bce09c6: all four hold in the code; two were already recorded as accepted limits.**
    The reviewer ran the state-diff module tests, the DAG and persistence
    policy unit tests and `StateDiffDatabaseTest`, all passing. The scenarios
    below were traced in code; they were not reproduced by a dedicated test.
    Each finding gives the reviewer's severity, then ours. The fixes are
    proposals and none is applied.
    - **A retained state that the window does not serve (P1; ours low).**
      Scenario: the window start is epoch 4 and has a node, the checkpoint
      blocks of epochs 5 and 6 are distinct and both were skipped, and the
      start moves to 6 in one finalization. `retainWindow` picks node 4 as `O`
      (the last of the roots of 4..6 with a node) and records start 6; a read
      of epoch 6 tries 6, then 5, and returns empty (`findCheckpointNode`
      stops at the one checkpoint block below its lowest epoch). This is the
      "second consecutive skip" that entry 62 left empty. It needs two
      consecutive skips and a finalization jump of two or more epochs. The
      result is an empty read for the edge epoch, and for the following epochs
      of the same skipped run, until the window moves past them; nothing is
      lost. Moving one epoch at a time does not expose it: from start 5 the
      roots of 5..6 have no node, the start is kept, and reads walk down to
      node 4. Proposed fix: persist the root of `O` next to the start epoch,
      for reads below the start and for retention in place of its walk to
      epoch 0. Cost: a new variable and a fallback for databases without it.
    - **Archive to prune strands the archive's nodes (P2; ours P2, new).**
      `finalizedRetention` clears the window start at the first finalization
      in prune mode and returns `anchorChain()`, whose candidates are the
      previous anchor's chain and the newly finalized roots. The older window
      nodes and their supernode references are never visited again. Legacy
      storage reclaims the equivalent space through the state pruner. Proposed
      fix: drain through the window path (target start = the anchor epoch,
      clear the start once caught up), or reject the transition; test it
      across a restart with history spanning a snapshot boundary.
    - **A read racing a window advance reports a verification failure (P2;
      ours low).** `CommittedNodeReader` reads each node and its supernodes in
      separate DAO calls, queries run on another runner than updates, and
      `KvStoreAccessor` has no snapshot read. For this retention race, a node
      that stays in the window keeps its whole chain before and after the
      commit, so only a read of an epoch leaving the window in that
      finalization can lose a node mid-chain: it gets a
      `StateDiffVerificationException` where the answer should be empty. An
      unbounded archive deletes nothing, and prune mode serves these reads
      from the legacy column. Proposed fix: on failure, re-read the window
      start and return empty when the epoch is now below it. That fixes the
      result only: the verification failure counter is incremented inside the
      DAG (`reconstructChain`) before the storage layer sees the exception, so
      the fix must also keep the false count out of the metric. Not traced to
      its callers: the same kind of race in prune mode for
      `getLatestFinalizedState` and for `getHotState` of the anchor root,
      which read the finalized checkpoint and then its node.
    - **The storage format has two sources of truth (P2; ours P2).**
      `TekuConfiguration` rejects `--reconstruct-historic-states` on the flag,
      while the database picks the DAG on the flag or the marker. With the
      flag removed after the switch the option is accepted and writes legacy
      states that DAG-backed reads do not serve. Already in entry 62 ("left as
      is") and entry 86 ("Storage mode resolved once, at open"). The smallest
      fix that stops the unused writes is a guard in
      `storeReconstructedFinalizedState` on an archive database in state diff
      mode. It is not a substitute for rejecting the unsupported configuration
      once the effective format is known.

    The review's merge recommendation is agreed: approve the representation
    separately from archive migration, and do not make it the default on
    existing archives while pre-switch history is unreadable although on disk
    (entries 13 and 57, a known scope limit).

89. **GREY. Cleanup when finality returns is the non-finality gap; the options are recorded and none is chosen.**
    **Established, from the code.** In prune mode
    `deleteFinalizedNodesOutsideChain` takes every newly finalized root as a
    candidate, and `deleteNodes` reads each one that has a node in full to
    release its supernode references. The release parser
    (`TreeDiffCodec.supernodeEntries`) checks the delta frame headers and
    skips the compressed bodies, but parses the other sections, leaves
    included. The nodes of pruned fork blocks are deleted the same way. All of
    it runs in the hot transaction that moves the anchor, and storage updates
    are applied in order, so imports queue behind it when finality returns.
    `MAX_WINDOW_STEP_EPOCHS` bounds the archive window only. This is the known
    limit of `docs/state-diff-storage.md` ("Retention after a long
    non-finality period"). Entry 86's header column carrying the supernode
    hash list would remove the payload reads.

    **Unmeasured: what it costs.** Extrapolating from the mainnet payload-v2
    section averages of entry 75, a one-week stall with one stored canonical
    checkpoint per epoch represents roughly 3.5 GB of encoded node records
    (1,575 x ~2.2 MB), before counting competing branches. This sizes the
    backlog; it is not a measurement of physical disk reads, peak heap, or
    recovery delay. An earlier 8 GB figure used the 5.35 MB per node of entry
    61, which predates payload v2, and is superseded.
    Since measured in part: see entry 93 (the delete itself, warm, and the
    archive reconstruction loop; live nodes are about 1.3 MB).

    **Baseline.** Master copies each finalized block's body from the hot
    column to the finalized column in transactions of up to 500 blocks
    (`TX_BATCH_SIZE`), all within one storage update, and deletes the hot
    blocks in the hot transaction of that update. The batches bound the
    transaction size, not the time. The stall test therefore measures the
    recovery delay the DAG adds over master, not the total.

    **Options.**
    - **Reuse the window cursor in prune mode** (target start = the anchor
      epoch): bounded steps for the canonical nodes, and it would also drain
      the archive-to-prune leftovers of entry 88. To prove first: canonical
      roots stay discoverable through the finalized block lookup until the
      cursor passes them, in minimal mode and across a restart; and fork
      nodes, which the finalized index cannot enumerate, get a bounded path of
      their own. The 256-epoch step bounds epochs, not latency; the stall test
      says whether it is a useful batch size.
    - **Fork nodes without a persisted list:** a resumable sweep of the node
      column (`KvStoreAccessor.streamKeys(column, from, to)`) that tests each
      node, with the resume key held in memory since the test is idempotent.
      Known counterexample for "in neither block index, hence garbage": after
      a checkpoint sync the anchor's block body can be absent from both the
      hot blocks and the finalized index (`createMemoryStore` and
      `addFinalizedBlock` special-case it), while the persisted finalized
      checkpoint names an anchor node that must survive. Absence from both
      block indexes does not prove that a node is garbage.
    - **Candidate prune-mode rule:** keep the hot nodes and the finalized
      anchor's reconstruction chain. Before using this as a deletion
      predicate, prove that it also keeps every reconstruction dependency of
      every retained hot node, including after skipped or late checkpoint
      writes. This is a separate open question from the counterexample above.
    - **Deferral.** Node deletion is pure garbage and could move to a bounded
      step in later storage updates. Block body migration is a move that
      readers depend on (finalized blocks are read by slot from the finalized
      column): deferring it needs a small slot-to-root index written
      synchronously, or a read fallback for the range not yet migrated. That
      is master-side work with its own design.

    **Constraints.**
    - Releasing supernode references is a read-modify-write of the counts in
      `commit()`. The existing pruners run on their own runner with their own
      transactions, which would race the storage update: a deferred cleanup
      step has to go through the ordered update path.
    - Startup streams and deserialises every hot block to build its metadata
      (`buildHotBlockMetadata`; the bodies are not kept). Hot blocks left at
      or below the finalized checkpoint would have to be filtered or
      tolerated.
    - In minimal mode the block pruner keeps finalized blocks back to the
      finalized epoch minus the network's `MIN_EPOCHS_FOR_BLOCK_REQUESTS`
      (`BlockPruner`): 33,024 epochs, about five months, in the mainnet-style
      configs and 272 in the swift ones. A network constant, not a guarantee a
      cleanup cursor can rely on.

    **Not verified.** The read cost of a key-only sweep without blob files
    (node values are inline in the SST blocks, so it is believed to read
    them); the finalized by-slot lookup in minimal mode; what startup does
    with leftover hot blocks; how many fork nodes a realistic stall leaves.

## Merge strategy (2026-11-23, tip 387bfba947, proposal only, nothing started)

This section tracks how the branch could reach master. Update it in place as
decisions are taken. No PR exists and, by Enrico's call on 2026-11-23, no PR
branch is cut yet.

90. **GREY. Proposed path: staged PRs behind the hidden flag, prune and minimal modes first, archive mode later, default-on last.**
    The branch is too big for one review: 108 files, +18,460/−392 against
    master bc4bce09c6; 66 files and +7,044/−115 without docs and tests. The
    stages follow the diff's directory layout. The split has not been tried.
    1. **The `infrastructure/state-diff` module.** Codec, differ, applier and
       their tests, about 4,400 lines, with the four small additions in
       `infrastructure/ssz` (124 lines with their test) and the
       `settings.gradle` line. No dependency on storage, no behaviour change,
       and no consumer in master yet.
    2. **Storage plumbing with no behaviour change.** The schema columns
       (13, 14, 15) and variables (14, 15), the `HotStateStorage` strategy
       with `LegacyHotStateStorage`, and `HotStatePersistencePolicy` in the
       `Store`. Landing the IDs early ends the collisions with master that
       entry 79 migrated around.
    3. **The DAG in prune and minimal modes behind
       `--Xdata-storage-state-diffs`,** with the metrics and
       `verify-state-diffs`.
    4. **Archive mode, as its own PR,** after findings 2 to 4 of entry 88 are
       fixed.
    5. **Default-on,** gated by entry 87: the stall test against master, the
       bounded cleanup of entry 89 if the test shows the need, and the
       multi-week soak. The header column parked in entry 86 is a format
       change (marker bump, re-synced databases) and is cheaper to settle
       before this stage than after.

    **Why prune first.** All four findings of entry 88 are archive-side, and
    the review asked for the representation to be approved apart from archive
    migration.

    **Independent of the rest:** the live data size column in the
    `RocksDbCommand` output can go as its own small PR at any time.

91. **GREY. Open decisions before the first PR. All four are Enrico's; none is taken.**
    Each gives the recommendation made on 2026-11-23.
    - **Scope of stage 3.** Archive mode is not separable by file: the window
      retention lives in the same classes as the prune rule
      (`FinalizedChainMaintenance`, `FinalizedRetention`,
      `StateDiffHotStateStorage`). Recommendation: keep the code and reject
      archive mode when the database is opened, in `resolveHotStateStorage`,
      where the marker is known. A check on the flag alone would repeat
      finding 4 of entry 88.
    - **What the stall test gates.** Recommendation: default-on only. The
      feature is hidden and opt-in, and the test is easier to repeat once the
      code is in master. The case for gating stage 3: it already carries the
      cleanup gap of entry 89 for anyone who enables the flag.
    - **Which docs go upstream.** The branch adds 3,400 lines under `docs/`,
      a directory master does not have. Recommendation: only
      `docs/state-diff-storage.md`; this log, the plan and the design spec
      stay on the fork.
    - **Team buy-in before stage 1.** That PR has no consumer in master, so
      reviewers need the reason first. Recommendation: a design issue on
      Consensys/teku linking the doc, and a walkthrough before stage 3, since
      the format is one-way for anyone who tries it (sticky marker, no
      downgrade).

92. **DERIVED. State of the branch for a merge, read in the code on 2026-11-23; no test was run.**
    - It sits on upstream master bc4bce09c6 with a clean tree, and the added
      code has no leftover TODO.
    - No new third-party dependency: `aircompressor-v3` and `fastutil` are
      already in `gradle/versions.gradle`.
    - The schema additions look inert with the flag off. On RocksDB only the
      column families that already exist are opened
      (`RocksDbInstanceFactory.getExistingColumnIds`), and master already
      opens unknown ones, so an older build would still open the database.
    - Without the flag or the marker the database gets
      `LegacyHotStateStorage` with no state diff storage attached.
      `DatabaseTest` has `legacyBehaviourUnchangedWhenDisabled`; its existing
      cases were kept and moved onto `AbstractDatabaseTest`.
    - The legacy path now runs through `HotStatePersistencePolicy` at four
      places in `Store`: the in-memory state lookup, the epoch start check,
      the regeneration base and the replay tolerance.

    **Not verified.** That stage 1 builds on its own; that the four `Store`
    call sites behave exactly as before, which stage 2 should show by landing
    with the existing tests untouched; the full unit, integration and
    reference suites on this tip.

## Stall cleanup measurement (2026-11-26, tip 7b3a59f45a, no code change)

93. **DERIVED. Measured locally: the delete of entry 89 is cheap; the archive finalization loop, which reconstructs every finalized checkpoint node, is the larger cost.**
    This is a micro measurement of the two operations, not the stall test of
    entry 87, and it changes no code. The fixes stay parked.

    **Method.** A measurement test, never committed (kept with its result in
    `tmp/stall-cleanup-harness` of the main checkout), run with the coverage
    agent and assertions off. From a mainnet finalized state (slot 15695168,
    2,411,440 validators, Fulu) it builds one non-finalizing branch of 400
    epochs with no blocks: each epoch a third of the validators are marked as
    having attested (4% of them without the head flag) and every validator of
    35 supernodes is changed, then the epoch transition runs. One node per
    epoch is written through `StateDiffDagTransaction.writeNode` into a
    RocksDB with the production column settings. Everything is then read from
    a warm cache, on a 16-core laptop.

    **Synthetic nodes against live ones.** Level 3 nodes averaged 2.68 MB
    with 77,400 leaves and 35 supernode references; all 400 nodes averaged
    2.80 MB. The live boxes (Grafana, 7 days to 2026-11-26, both boxes alike)
    write 1.23 MB at level 3, 2.46 MB at level 2, 3.39 MB at level 1 and
    13.0 MB per snapshot, with 40,500 leaves and 35 supernode references per
    node, about 1.3 MB on average. The synthetic nodes are therefore about
    twice as large, and the delete figures below are pessimistic by about that
    factor. The live figures also disagree with the section averages of entry
    75 (65,700 leaves, about 2.2 MB per node), which entry 89 uses for its
    3.5 GB per week; the cause is not traced. At 1.3 MB a one-week stall is
    about 2.1 GB per branch.

    **Delete (`deleteNodes`, one transaction per row).**
    - 50 nodes, 137 MB: 76 ms, 1.5 ms per node.
    - 100 nodes with one snapshot, 299 MB: 207 ms, 2.1 ms per node.
    - 200 nodes, 558 MB: 343 ms, 1.7 ms per node.
    - Prune-mode `retainFinalizedChain` moving the anchor over 50 epochs: 37
      nodes deleted (two snapshots among them), 97 ms, 2.6 ms per node.
    - 6,200 roots without a node, as fork pruning passes for the non-checkpoint
      blocks: 28 ms in all.

    The cost is linear, about 0.6 ms per MB. Reading and parsing a node are
    of the same order (0.6 ms and 0.9 ms per synthetic node); the reference
    count work in `commit()` and the database commit are about 10% of the
    total. Extrapolated to the 9,450 nodes of a two-week stall with three
    branches in prune mode: 10 to 20 seconds on this machine.

    **Not covered by that figure: cold reads.** After two weeks the nodes are
    not in any cache. The highest read rate the archive box's data disk
    showed in 14 days is about 100 MB/s (an observed peak, not a capacity
    figure); at that rate 8 to 12 GB is one to two minutes, which would
    dominate the delete there. Entry 86's header column would remove these
    reads; bounded steps would only spread them.

    **Archive finalization loop (not in entry 89).** In archive mode
    `updateFinalizedDataArchiveMode` asks for the state of every newly
    finalized block: `finalizedStates.get(blockRoot)` or else
    `getHotState(blockRoot)`. With state diffs `getHotState` reconstructs the
    block's node (`dag.reconstructOrEmpty`), and
    `StoreTransactionUpdatesFactory.collectFinalizedStates` only supplies the
    state of the newly finalized block. In steady state that is the only
    checkpoint node of the step, so nothing is reconstructed (the archive box
    shows 19 reconstructions in 7 days). After a stall every canonical
    checkpoint node of the stall is reconstructed, before the hot transaction,
    in the same storage update. The result is not stored
    (`addFinalizedState` writes nothing in this mode); it only feeds
    `StateRootRecorder`.
    - Measured with an empty base cache, which is what the running node has
      for nodes written long before: 0.3 s for a snapshot alone plus about
      45 ms per chain link; 0.69 s on average over the 100 oldest nodes
      (average chain 9.5), 0.98 s over the 20 newest (chain 12.3), 2.2 s at
      worst.
    - Extrapolated to the 3,150 canonical nodes of a two-week stall: 35 to 50
      minutes on this machine. The live archive box reconstructs in 2.3 s at
      the median and 2.6 s on average (7.4 links, 95th percentile 7.9 s), so
      on that hardware the same loop is two hours or more. Neither figure was
      run end to end.
    - Baseline: master reads one stored hot state per finalized block that
      has one at the same place and writes it to the finalized store, so this
      is not shown to be a regression. It belongs in the "delay the DAG adds"
      of the stall test.
    - Likely fix, not tested: `StateRootRecorder` reads the state roots vector,
      which covers 8,192 slots, so one state per 256 epochs would fill the
      same index.

    **What this changes for entry 89.** The CPU cost of the delete does not
    by itself justify bounded cleanup steps; the cold reads and the archive
    loop are what the stall test should size first.

    **Not measured.** Cold disk reads; the box hardware; heap during either
    operation; nodes of a long leak, where effective balances change for most
    validators (400 epochs is too short to reach that); a stall with real
    blocks and competing branches; master's cost for the same event.

## Archive finalization loop fix (2026-11-27, on tip a8b5b44f45)

94. **RULING (Enrico), then DERIVED. Build the fix of entry 93 for the archive loop: a state diff archive reads a stored finalized state only when the state root index needs one.**

    **Ruling.** Asked on 2026-11-27 whether to build the prototype described
    under "Likely fix" in entry 93, Enrico answered "Yes, build it". It is the
    first code change for the non-finality gap; entry 87 still parks the
    fixes of entries 88 and 89.

    **Change.** In `updateFinalizedDataArchiveMode`, when the storage holds
    the finalized states itself (`HotStateStorage.holdsFinalizedStates`, true
    for a state diff archive), a state that is not in memory is read back
    only for the newly finalized block and when `StateRootRecorder.isStateDue`
    says so: the block is more than three quarters of the state roots vector
    past the last recorded slot (6,144 slots on mainnet). The last quarter
    (2,048 slots, 64 epochs) is the room to find a node before roots are
    missed; a miss logs the recorder's existing "Missing state root
    mappings" warning. Legacy archive storage reads every state as before,
    because it stores them.

    **Bug found on the way, also on master.** The loop created a recorder
    per transaction of 500 roots, starting at the last block of the previous
    transaction. The slots between the last state of a transaction and its
    last block were never indexed. The new test fails on the unchanged loop
    at slot 497. The recorder position is now carried from one transaction to
    the next. Master's loop reads the same in the code; not run there.

    **Verified.** `stateDiffs_archiveIndexesEveryStateRootAfterLongNonFinality`
    (minimal spec, 70 epochs without finality, 562 blocks, empty slots): the
    state root of every slot is indexed. With a temporary print, removed
    since, the finalization read 9 stored states per database with the gate
    and 67 without it. The gate itself is unit tested in
    `StateRootRecorderTest`; no automated test counts the reads.

    **Expected on mainnet, not run.** One reconstruction per 6,144 to about
    6,200 slots: about 17 for a two-week stall (100,800 slots) in place of
    3,150, so about 12 s at the 0.7 s measured locally and about 40 s at the
    2.3 s median of the archive box.

    **Left as it is.** The delete and its cold reads (entries 89, 93).

## Devnet stall runs (2026-11-27, tip 7ccb826764, no code change)

95. **DERIVED. On a kurtosis devnet the fix of entry 94 cut the state reads of the first finalization after a stall from 119 to 17 and closed a three-slot hole in the state root index; this is not the full stall test of entry 87.**
    Run at Enrico's request ("Yes, run it"), recorded at his request. The
    fixes of entries 88 and 89 stay parked.

    **Setup.** ethereum-package at 6886eac7b8, minimal preset (8 slots per
    epoch, 6 s slots, state roots vector of 64 slots, so the gate of entry 94
    is 48 slots), geth, 160 validators. Images built from 7ccb826764 (the
    fix) and from 63f15c73e3 (the commit before it), both with the fork
    partition test patch (a8290fb912 on `tbenr/teku:fork-partition-test-mode`,
    inert without its control file). Every node ran in archive mode, which
    the package forces. Reads are the increase of
    `storage_state_diff_reconstruct_seconds_count` across the finalization
    update, taken before any historical query. Harness, logs and samples are
    kept in `tmp/stall-kurtosis` of the main checkout, never committed.

    **Run 1, competing forks, 40 epochs.** Three nodes (64, 64 and 32
    validators), each building on its own blocks only from slot 40 to slot
    356. Finality then went from epoch 4 to epoch 46 in one update: 336
    slots, 134 canonical blocks, one transaction.
    - Reads: 4 with the fix (4 ms), 41 without it (23 ms).
    - Both state diff nodes deleted the 68 nodes of the losing forks.
    - State root index, slots 25 to 368: every slot found by state root on
      the three nodes.
    - No verification failure, no "Missing state root mappings".
    - Sync committee duties partly failed on all three nodes after the
      reorg, the legacy node included. Not investigated.

    **Run 1, second stall of 180 epochs: lost to geth, not to Teku.** At
    slot 720 the surviving branch reached 129 execution blocks. Geth keeps
    128 state layers in memory and dropped the state of the other branches
    ("missing trie node" on the three geth nodes in the same second); two
    nodes could no longer build blocks and the chain did not recover once
    the forks were merged. Offline `verify-state-diffs` on both state diff
    databases of that network: 195 and 197 nodes, no problems found. With
    geth, a fork stall cannot go deeper than 128 blocks per branch.

    **Run 2, one chain, 120 epochs.** Four nodes: fix with state diffs (38
    validators), pre-fix with state diffs (38), and two legacy archive nodes
    on the fix build (20 and 64). The 64-validator node was stopped from
    slot 40 to slot 1001, so 60% of the validators kept one chain growing
    without finality. It resynced in about two minutes, and finality went
    from epoch 4 to epoch 127 in one update: 984 slots, 584 canonical
    blocks, two transactions.

    | | Fix, state diffs | Pre-fix, state diffs | Legacy archive (fix build) |
    |---|---|---|---|
    | Reads during the finalization update | 17 | 119 | n/a |
    | Time in them | 108 ms | 248 ms | n/a |
    | Finalized slots not found by state root (25 to 1030) | 0 | 3 (865, 866, 867) | 0 |
    | By-slot state roots differing between nodes | 0 | 0 | 0 |
    | "Missing state root mappings" | 0 | 0 | 0 |
    | Verification failures, skipped nodes | 0, 0 | 0, 0 | n/a |
    | Offline `verify-state-diffs` | 132 nodes, no problems | 132 nodes, no problems | n/a |

    - The 17 reads match the gate: a read is due more than 48 slots after
      the last one and happens at the next checkpoint node, so one per 7
      epochs (984 / 56 = 17.6).
    - The hole on the pre-fix node is the bug of entry 94, now seen on a
      running node: three slots at the boundary between the two
      transactions, all three with a block. A state request by those state
      roots returns 404 there and succeeds on the other nodes.
    - The times mean nothing at 160 validators; the counts are the result.

    **Observed on every node, not investigated.** The slots of the epoch
    right after the finalized checkpoint, other than its first, return 404
    by state root until the next finalization indexes them. Fix, pre-fix and
    legacy nodes behave the same, so it is not a state diff matter.

    **What entry 87 asks for and these runs do not give.** No master
    baseline (the legacy nodes ran the fix build, so master's own index hole
    was not shown either); no prune or minimal mode; no cold restart during
    the stall and no interrupted recovery; no peak disk and no import delay;
    two stall lengths with different designs, so no slope; minimal preset
    and 160 validators, so no mainnet cost. The delete of entry 89 was
    exercised once, on 68 small nodes.
