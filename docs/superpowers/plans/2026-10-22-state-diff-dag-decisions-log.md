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

9. **GREY. No application-level compression (user decision), but the delta section is versioned so a codec can be added for that section alone.**

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

41. **GREY. Changed supernodes are stored whole (31 KB) even when one validator changed.**
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
