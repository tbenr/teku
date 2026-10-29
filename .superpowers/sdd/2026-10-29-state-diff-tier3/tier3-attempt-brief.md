# Tier 3 attempt brief: frozen exited-validator supernodes (2026-10-29)

Worktree: /Users/tbenr/teku/.claude/worktrees/state-diff-tier3 (branch worktree-state-diff-tier3,
cut from worktree-state-diff-dag at 2454573a3e). Work on the working tree; stage with `git add`;
never `git commit`, never `git push`, never `spotlessApply` (it has deleted files on this machine:
hand-format and verify with `./gradlew spotlessJavaCheck`). `-Werror` is on. Run only one gradle
invocation at a time, never in parallel with another.

This is an EXPLORATION, not a PR: the goal is to learn, with numbers, (a) what freezing buys in
memory and epoch-transition CPU on a real mainnet state, and (b) how much of the consensus code
has to know about frozen ranges. Keep the implementation as small as the measurement needs,
keep every fast path behaviour-neutral when nothing is frozen, and write down what you learn,
including negative results. Production quality is secondary to a clean, measurable attempt.

Read first: docs/superpowers/specs/2026-10-22-state-diff-dag-design.md section 9 (the design of
this feature, follow it unless this brief says otherwise) and decisions D4/D5/D15; decisions log
docs/superpowers/plans/2026-10-22-state-diff-dag-decisions-log.md entries 41, 63-73 (payload v2:
supernode references vs patches); docs/state-diff-storage.md; infrastructure/ssz
SszSuperNode, LazyBranchNode, TreeNode, TreeVisitor, SszNodeTemplate; infrastructure/state-diff
TreeDiffApplier (loadSupernodes / createWithKnownRoot, patchedSupernode), TreeDiffer (how it
compares supernodes and emits patches), SupernodeSource; storage .../statediff/StateDiffDag
(reconstruct, reconstructAndCache, NodeReader), StateDiffDagConfig, LevelBaseCache;
ethereum/spec AbstractValidatorStatusFactory.createInitialValidatorStatuses,
BeaconStateAccessors.getActiveValidatorIndices, AbstractEpochProcessor.processRegistryUpdates
and processEffectiveBalanceUpdates, WithdrawalsHelpersCapella/Electra (the sweep),
BeaconStateFields (validators list: SszSuperNode hint depth 8 = 256 validators of 121 bytes).

Known numbers (mainnet head, 2026-10-29, from the REST API of the minimal box): 2,393,377
validators, 1,575,247 of them `withdrawal_done` with effective_balance 0 (66% of the registry),
but only 1,027 depth-8 supernodes are entirely withdrawn: whole-supernode freezing covers about
263k validators (11% of the registry, 17% of the exited). Exits cluster in the low index range.
The harness below must confirm this on the state file and report the coverage that finer
supernodes (depth 7, 6, 5) would give, so we learn whether the granularity is the limit.

## P1. `FrozenSuperNode` (infrastructure/ssz, package ...ssz.tree)
- Implements `TreeNode` and `LeafDataNode`. Holds depth, `SszNodeTemplate`, element count, the
  known `hashTreeRoot`, and a loader (`Supplier<Bytes>` or a `Function<Bytes32, Optional<Bytes>>`
  plus the hash; your call) that returns the packed SSZ of the elements.
- `hashTreeRoot()` (both overloads) returns the stored hash without loading. `get(gIndex)`:
  self for the self index, else load and delegate to a transient `SszSuperNode` (memoize the
  loaded node in a `SoftReference` so an epoch's repeated reads do not hit the store each time
  while the GC can still drop it). `updated(TreeUpdates)`: empty → this; final → the new node;
  else load and delegate to `SszSuperNode.updated` so any write thaws into a plain SszSuperNode.
  `iterate` visits itself as one node exactly like SszSuperNode. `getData()` loads.
- Instrumentation for the attempt: two global `LongAdder` counters (loads, thaws) exposed by
  static accessors, plus a debug switch (system property) that records the first N distinct
  call-site stacks of loads, so the harness can report WHICH consensus paths still load frozen
  supernodes. This is attempt-only; say so in the javadoc.
- A small `FrozenRanges` helper (same package or infrastructure/state-diff, where it fits
  without new module dependencies): given an `SszList`'s backing tree and the list's element
  gIndex layout, collects the frozen supernodes as sorted element index ranges
  `[start, start + elementCount)`. Needs the depth of the list tree: derive it from the list
  schema (`SszListSchema` / progressive list variant used by Gloas, see BeaconStateFields
  `toProgressiveListSchema`): check both shapes; if the progressive list makes the range mapping
  hard, support the bounded list first and record the gap.
- Unit tests: hash equals the real supernode's, get(i) equals, updated thaws, iterate visits once,
  FrozenRanges over a list with two frozen supernodes.

## P2. Freezing at reconstruction (infrastructure/state-diff + storage)
- Add an optional `SupernodeFreezer` hook to `TreeDiffApplier` (interface in
  infrastructure/state-diff; the applier stays ignorant of Validator): called for every
  whole-reference supernode it creates in `applySections` (the `createWithKnownRoot` site) with
  the field index, entry, ssz bytes and hash; returns the `TreeNode` to place (frozen or not).
  Patches (`patchedSupernode`) and the differ must handle a `FrozenSuperNode` base: load it
  (count as a load) and proceed as with an SszSuperNode. Check TreeDiffer's supernode
  comparison: equal hashes must short-circuit WITHOUT loading (that is the whole point: the
  write path never materialises frozen nodes); only a changed supernode loads the base bytes.
- Storage-side freezer (storage .../statediff): freezes a validators-field supernode when every
  element has `withdrawable_epoch <= epoch of the reconstructed node` and `effective_balance ==
  0` (fixed Validator layout: effective_balance at offset 80, withdrawable_epoch at offset 113,
  both little-endian uint64; derive the offsets from `Validator.SSZ_SCHEMA` / the node template
  rather than hard-coding if that is easy). The epoch comes from the node record being
  reconstructed (slot of the node). Loader = `dao.getStateSupernode(hash)` through the DAG's
  committed `NodeReader` (the dao outlives any state). Memoise eligibility by hash in a small
  bounded map so a supernode is scanned once.
- Wire it behind a new boolean in `StateDiffDagConfig` and a hidden CLI flag
  `--Xdata-storage-state-diffs-freeze-exited` (default false) so a node can be A/B'd. Apply in
  `reconstruct` and `reconstructAndCache` (the anchor at startup is what seeds the hot chain
  after a restart: states derived from it by `updated()` keep the frozen nodes). Note: the base
  cache trees are then frozen too; verify the writer (TreeDiffer against a frozen base) still
  produces identical diffs: a unit test that diffs a target against a frozen base and an
  unfrozen base must give byte-identical payloads and zero loads when nothing changed in those
  supernodes.
- Pruning: a frozen node's bytes must still exist in STATE_SUPERNODES when loaded. Argue in the
  report why refcounts keep them (every later snapshot re-references an unchanged supernode);
  if you find a hole, document it, do not fix it in this attempt.

## P3. Consensus fast paths (ethereum/spec), each a no-op when no range is frozen
Guard every one with a `FrozenRanges` query on `state.getValidators()` that is cheap when the
list holds no frozen nodes (one tree iterate over the list's supernodes is ~10k visits on
mainnet: measure it, and if it matters cache the ranges per validators-list backing node
identity, e.g. a tiny identity cache or a field on the TransitionCaches).
1. `AbstractValidatorStatusFactory.createInitialValidatorStatuses`: frozen ranges yield canned
   statuses (not active in current/previous/next epoch, withdrawable, effective balance 0,
   slashed false: argue in the report why slashed does not matter for frozen validators given
   `withdrawable_epoch` is in the past, as section 9 does).
2. `BeaconStateAccessors.getActiveValidatorIndices` (cache miss path): skip frozen ranges.
3. `processRegistryUpdates` and the activation-queue filter: skip frozen ranges.
4. Withdrawals sweep (Capella/Electra helpers): check `balances[i]` first; a frozen range with
   zero balances advances without loading, still counting toward
   MAX_VALIDATORS_PER_WITHDRAWALS_SWEEP so `next_withdrawal_validator_index` is spec-equal.
5. Anything else the harness's load counter shows: implement only if cheap, else list it.
Equivalence tests (minimal spec, Electra + Fulu; Gloas if the progressive list works): same
epoch transition on a state with and without frozen supernodes gives the same state root and
the frozen variant records the expected number of loads (zero for the fast paths). Build the
frozen variant in tests with an in-memory loader. Use `@TestSpecContext` where it fits.

## P4. Measurement harness (storage/src/integration-test or storage/src/jmh, your call; a JUnit
class gated on a system property is fine)
Input: the mainnet finalized state at slot 15468032 (epoch 483376, Fulu), SSZ at
/private/tmp/claude-501/-Users-tbenr-teku/caeb5129-e57c-4d88-8d3f-eff177420cf2/scratchpad/state-483376.ssz
(338 MB; deserialize with the mainnet `Spec`; run the harness with a 10-12 GB heap). Report:
- Coverage: supernodes total / eligible under the freeze rule, validators covered, bytes covered
  (sum of eligible supernode SSZ sizes), and the same coverage if the supernode depth were 7, 6
  and 5 (compute from the validator array; no code change for those).
- Memory: used heap after `System.gc()` holding (a) the plain state, (b) the state with eligible
  supernodes replaced by FrozenSuperNodes whose loader reads from a temp file (write the
  eligible supernodes' bytes to a file with an offset index so the bytes really leave the heap),
  plus the analytic number (bytes covered). Say which number is per distinct registry copy and
  how many copies a running node keeps (hot states of ~2 epochs plus the anchor plus the base
  cache share supernodes structurally: structural sharing means the gain is NOT multiplied).
- CPU: `spec.processSlots(state, 15468064)` (one empty-slot epoch transition) on (a) and (b),
  5 warm-up + 10 measured runs each, median and p90 wall time; state roots must be equal.
  Also time `createValidatorStatuses` alone and `getActiveValidatorIndices` (cold cache) alone.
  For (b) report FrozenSuperNode loads and thaws per transition and the distinct call-site
  stacks (the debug switch) so we see which consensus paths still touch frozen validators.
- Keep the harness runnable later against the AWS boxes' databases (document the command).

## P5. Report
Write .superpowers/sdd/2026-10-29-state-diff-tier3/tier3-attempt-report.md: what was built (file
list), the measurements as tables, the list of consensus sites touched and the list of sites the
load counter shows still loading frozen nodes (with why), gaps and risks found (pruning,
progressive lists, Gloas), your honest read of whether the gain justifies the consensus-code
footprint, and alternatives you see (finer supernodes for the validators list; freezing
partially-exited supernodes with a different unit; not doing this). Add a decisions-log entry
(next free number after 75; read the tail of the log for the format) titled "Tier 3 attempt:
frozen exited-validator supernodes" summarising the numbers. Do not touch docs/state-diff-storage.md
or the spec beyond the decisions-log entry.

Verification before you stop: `./gradlew :infrastructure:ssz:test :infrastructure:state-diff:test
:storage:test :ethereum:spec:test` green (spec tests are long; run them once at the end),
`./gradlew spotlessJavaCheck` clean, `git status` shows only your intended files, the harness
run output pasted into the report. Stage everything with `git add -A` at the end (the SDD folder
too).
