# Native Batch SSZ Merkleization Validation

## Status

Approved design for an experiment. This document does not approve production adoption.

## Decision

Prototype [OffchainLabs/hashtree](https://github.com/OffchainLabs/hashtree) through the
Java Foreign Function and Memory API as an optional batch SHA-256 provider for SSZ nodes that
already retain linear serialized bytes.

Keep long-lived SSZ backing data in its current `Bytes` representation. Use operation-scoped native
scratch only while calculating an uncached root. Do not make `Arena` or `MemorySegment` part of
`TreeNode` ownership in the first experiment.

The idea is technically sound when hashing is reorganized into whole Merkle layers. Calling native
SHA-256 once per pair is explicitly out of scope because it loses the multi-buffer advantage and
adds FFM and copying overhead.

## Motivation

Teku has two production-relevant linear-backed representations:

- `SszSuperNode`, currently used for validator registry groups of 256 validators.
- Packed fixed and progressive lists of transaction byte lists, used by pre-Gloas execution
  payloads and Gloas standalone payloads respectively.

Both implementations currently calculate cold roots with recursive pair-by-pair JCA SHA-256. The
roots are cached, so the optimization matters only when a node is newly deserialized or replaced by
an update.

`hashtree` accepts a contiguous input of `count` independent 64-byte blocks and writes `count`
32-byte digests. Its SIMD implementations are useful only when Teku presents independent pairs in
batches.

## Important Constraint

Linear serialized SSZ is not generally a ready-to-hash Merkle leaf array.

A serialized validator is 121 bytes, while its container fields require 32-byte padding and the
48-byte public key has its own inner hash. A validator supernode therefore requires a staging and
layout transformation even when the source bytes are off-heap. Packed transaction lists also
contain offsets and variable-size elements and require zero-subtree and length handling.

Permanent arena-backed storage would therefore not make the main BeaconState path zero-copy. It
would add difficult lifetime management around immutable tree sharing, cross-thread access, arena
closure, and retained native memory without first proving a workflow-level speedup.

## Considered Approaches

### 1. Targeted Batch Merkleizer

Recommended. Keep source bytes on heap, stage independent work into temporary native segments, and
invoke `hashtree_hash` once per Merkle layer. Add specialized planners for supernodes and packed
transaction lists.

This approach captures native batching while keeping the change isolated and reversible.

### 2. Arena-Backed Tree Storage

Rejected for the initial experiment. It is invasive, does not eliminate validator staging, and
conflicts with persistent tree sharing unless native-memory ownership is redesigned.

### 3. Native Hash Per Merkle Pair

Rejected. Thousands of downcalls and heap/native transfers are likely slower than the current JCA
implementation and do not exercise `hashtree` as designed.

## Architecture

### Native Provider

Add a narrow batch-hash provider in `infrastructure/crypto`:

- Resolve and cache the FFM downcall for
  `hashtree_hash(unsigned char *output, const unsigned char *input, uint64_t count)`.
- Call `hashtree_init(NULL)` once before concurrent use so CPU dispatch is complete.
- Report availability and a benchmark-derived minimum useful batch size.
- Validate input size, output size, count, and arithmetic overflow before every downcall.
- Fall back to the existing JCA implementation when the shared library cannot be loaded.

The experiment is disabled by default. Native loading failure produces one diagnostic event and
does not prevent Teku from starting.

`hashtree` 0.2.5 currently builds a static archive by default. The experiment must add a
reproducible shared-library build for each tested platform rather than assuming the archive can be
loaded by FFM.

### Scratch Memory

Each native root calculation owns a confined arena and two non-overlapping work segments. The
segments alternate between input and output across Merkle levels.

The final 32-byte root is copied to a heap `Bytes32` before the arena closes. No segment escapes the
synchronous hash operation. The prototype must include allocation and staging cost in benchmarks;
a reusable or pooled scratch strategy may be evaluated separately only if allocation is material.

The implementation must not rely on input/output aliasing because `hashtree` does not document
overlap as supported.

### Supernode Planner

Extend `SszNodeTemplate` with a compiled bottom-up hash plan:

- Load and pad serialized leaf data into planned 32-byte slots.
- Group independent inner-node pairs into hash rounds.
- Execute each round across all elements in the supernode.
- Batch the balanced outer supernode levels after element roots are available.

For validator supernodes, this batches public-key inner hashes, the three validator-container
levels, and the eight outer levels. Partial final supernodes use the same existing empty-element
semantics.

Small plans or unavailable native support continue through the current recursive implementation.

### Packed Transaction Planners

For fixed lists:

- Read transaction boundaries from the existing offsets.
- Batch real 32-byte chunks across transactions.
- Merkleize real chunks to their local power-of-two boundary.
- Fold precomputed zero roots to the declared transaction depth.
- Batch the length mix-ins and the outer transaction-list tree.

For progressive lists:

- Reuse chunk batching.
- Build each populated progressive level.
- Fold progressive spines and length mix-ins.
- Build the progressive outer transaction list.

The planners must avoid materializing declared-but-empty portions of large trees.

Native work is processed in benchmark-derived tiles rather than requiring one scratch allocation
large enough for every transaction. Tiling must preserve enough independent pairs to saturate the
selected SIMD implementation while bounding scratch independently of the transaction count.

### Cache And Concurrency

Preserve existing volatile root caches and lock-free behavior. Two threads may independently
calculate the same cold root with separate confined scratch and race to store the same value.

Cached-root calls must not enter the native provider.

## Validation Workloads

### Kernel

Compare current JCA SHA-256 with both native-only and staging-inclusive FFM `hashtree` measurements
for batch sizes around every SIMD width and power of two. Use the staging-inclusive result to find
the crossover threshold rather than assuming all batches benefit.

### SSZ Nodes

Measure:

- Full and partial validator supernodes with cold caches.
- Validator supernodes after one and many updates within the same 256-element group.
- Fixed packed transaction lists with varied transaction counts and sizes.
- Progressive packed transaction lists with the same payload data.

### Whole Objects

Use fresh objects per measured invocation:

- Cold root of a real checkpoint BeaconState.
- Root after realistic block and epoch-transition mutations.
- Root after 1, 32, and 2,000 validator updates distributed across the registry.
- Cold root of small, median, and large pre-Gloas execution payloads.
- Cold root of equivalent Gloas standalone execution payload envelopes.
- Deserialize-plus-hash for state and payload inputs.

Benchmark setup must prevent an earlier invocation from satisfying the measured call through the
root cache.

### Degenerate Many-Tiny-Transaction Payloads

Add an adversarial payload containing `1,048,576` one-byte transaction entries, matching the
mainnet pre-Gloas `MAX_TRANSACTIONS_PER_PAYLOAD`. Use the same count for Gloas even though its
progressive transaction list has no fixed maximum, so the fixed and progressive shapes receive
identical serialized transaction data.

Test both identical byte values and values cycling through all 256 byte values. The second form
prevents an implementation from making the comparison meaningless by memoizing one repeated
transaction root.

Measure intermediate counts as well as the maximum, including counts around SIMD and progressive
level boundaries, to show scaling and the native crossover point. For the maximum case, measure:

- Cold transaction-list root.
- Cold whole execution-payload root.
- Deserialize-plus-hash.
- Peak Java allocation and native scratch.
- Total hash pairs and FFM calls per planner stage.

Report pre-Gloas and Gloas absolute results separately. A one-byte pre-Gloas transaction still
folds its data root through the fixed `MAX_BYTES_PER_TRANSACTION` depth before mixing in its length.
The progressive Gloas transaction has a much shallower populated shape. Combining their results
would hide whether a gain comes from native batching or from the progressive schema itself.

One-byte transaction encodings are valid inputs to the SSZ schemas but need not be valid execution
transactions. This workload therefore evaluates SSZ deserialization and merkleization, not
execution-layer acceptance or a complete payload-validation workflow.

The maximum case must use bounded tiles and report peak scratch. An implementation that allocates
scratch proportional to all `1,048,576` transactions does not satisfy this workload even if its
latency improves.

### Workflows

Measure complete block processing/state transition and payload deserialization/validation. These
workflow results decide adoption because cached roots can make an isolated cold-root speedup
irrelevant.

Collect time, Java allocation, GC activity, CPU profiles, and peak native scratch.

Test at least:

- Linux x86-64 with SHA extensions.
- Linux x86-64 without SHA extensions when hardware is available.
- Linux or macOS ARM64.

## Adoption Gates

Production adoption requires all of the following:

- Exact parity with current Java roots for reference tests, fixtures, and randomized structures.
- At least 25% lower whole-object cold-root time on a common production platform.
- At least 5% improvement in block-processing or payload-validation workflow time.
- No meaningful small-batch regression because the Java threshold is selected correctly.
- Separate many-tiny-transaction results for fixed and progressive payloads; if either native
  planner regresses, that planner remains on Java regardless of results for the other shape.
- Bounded operation-scoped native memory with no arena ownership in backing nodes.
- Reliable Java fallback when the native library is absent or unsupported.
- Reproducible binaries, pinned upstream revision, license reporting, SBOM coverage, and platform
  CI.

If native kernel results are strong but workflow improvement remains below 5%, stop the experiment
without shipping native binaries.

## Correctness And Safety Tests

Before performance testing, compare native output with JCA for:

- Known SHA-256 answers and counts around every SIMD remainder.
- Empty handling at the Java boundary and arithmetic overflow rejection.
- Full and partial supernodes.
- Every fixed and progressive tree boundary.
- Random transaction counts, byte lengths, and offset layouts.
- Concurrent cold-root calls.
- All consensus reference tests and real state/payload fixtures.
- Library-load failure and unsupported-platform fallback.

Native CPU dispatch failures may terminate the JVM and cannot be handled as Java exceptions.
Architecture-specific known-answer CI is therefore mandatory.

## Experiment Sequence

1. Build a standalone FFM kernel benchmark with shared `hashtree` binaries.
2. Add the supernode planner and measure node and BeaconState workloads.
3. Continue only if the state results cross the object-level gate.
4. Add fixed and progressive packed-transaction planners.
5. Run full workflow and platform benchmarks.
6. Decide independently whether the measured benefit justifies production packaging.
