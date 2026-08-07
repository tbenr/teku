# Hashtree FFM Tiny-Transaction Payload Results: macOS ARM64

## Environment

- Date: 2026-08-07
- Host: macOS 26.6, ARM64 T6041
- JVM: Azul Zulu JDK 25 (25+36-LTS), aarch64
- Teku commit: `a730c2ec08a16f21ff161f0fd3f4eb2ff6d484ad`
- Hashtree commit: `30497cff98a06362eadde897202634f91d504fd8`
- Native library SHA-256:
  `7c16b00feba1dee681470abc6029361d9fda2e86a9f0c4ab6573d363be094459`
- JMH: 1.37, two forks, three 1-second warmups, five 1-second measurements,
  average time in milliseconds, GC profiler enabled
- Native tile size: 16,384 transaction roots
- Maximum off-heap scratch per operation: 1,572,864 bytes (1.5 MiB)

The raw JMH output is stored alongside this report as
`2026-08-07-macos-arm64-tiny-transactions.json` with SHA-256
`edacaf56f630934777c7564b3c8edd81030d10dd7a680618339ce370459d07ec`.

## Workload

The Fulu workload is a synthetic `BeaconBlock` built with the mainnet pre-Gloas
schema and containing an `ExecutionPayload`. The Gloas workload is a synthetic
standalone `ExecutionPayload` built with the mainnet Gloas schema. Non-transaction
fields use schema defaults and the block slot is zero. Both contain the same
cycling one-byte values and include 1,048,576 entries, the pre-Gloas
`MAX_TRANSACTIONS_PER_PAYLOAD`.

The entries are valid SSZ transaction values but are not valid Ethereum
execution transactions. This is an intentionally pathological SSZ hashing and
allocation workload.

Every trial verifies current and native transaction-list and whole-object roots
for equality. Cold-root invocations use fresh deserialization in
`@Setup(Level.Invocation)`. JMH excludes that setup from the primary root timer,
while the GC profiler includes its allocations in iteration accounting.
Consequently, cold-root time is hash-only but its allocation values include the
fresh-object setup. Deserialize-plus-hash performs both operations inside the
primary timer, so its timing and allocation scopes match.

Native scores include heap-to-native staging, operation-scoped arena allocation
and cleanup, and sparse JCA joins. The GC profiler does not count the 1.5 MiB
off-heap scratch.

Scores below are average ms/op with JMH 99.9% errors. Allocation-rate columns
are current/native MB/s; normalized allocation columns are current/native B/op.

## Fulu Beacon Block

| Operation | Transactions | Current | Native | Time reduction | GC MB/s | Heap B/op |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Transaction root | 4,096 | 6.667 +/- 0.765 | 4.184 +/- 0.060 | 37.2% | 1373.8 / 8.5 | 9,579,606 / 37,439 |
| Transaction root | 5,461 | 8.791 +/- 0.411 | 5.563 +/- 0.197 | 36.7% | 1429.2 / 7.6 | 13,189,253 / 44,423 |
| Transaction root | 1,048,576 | 1739.983 +/- 136.533 | 1004.812 +/- 29.371 | 42.3% | 1388.2 / 4.3 | 2,529,189,608 / 4,568,883 |
| Whole block | 4,096 | 6.580 +/- 0.216 | 4.005 +/- 0.055 | 39.1% | 1398.6 / 10.6 | 9,673,214 / 44,550 |
| Whole block | 5,461 | 9.380 +/- 1.431 | 5.218 +/- 0.032 | 44.4% | 1349.7 / 9.4 | 13,195,479 / 51,534 |
| Whole block | 1,048,576 | 1785.781 +/- 161.089 | 1080.388 +/- 6.611 | 39.5% | 1353.5 / 4.0 | 2,529,195,821 / 4,577,869 |
| Deserialize + hash | 4,096 | 6.656 +/- 0.530 | 4.062 +/- 0.166 | 39.0% | 1382.9 / 10.5 | 9,629,518 / 44,550 |
| Deserialize + hash | 5,461 | 8.773 +/- 0.181 | 5.386 +/- 0.041 | 38.6% | 1434.5 / 9.1 | 13,195,472 / 51,536 |
| Deserialize + hash | 1,048,576 | 1925.668 +/- 274.389 | 999.020 +/- 42.420 | 48.1% | 1262.3 / 4.4 | 2,529,195,824 / 4,574,812 |

The native path clears the 25% whole-object gate and the 5%
deserialize-plus-hash proxy gate at all three counts. At the maximum, normalized
heap allocation is about 552 times lower for both whole-block and
deserialize-plus-hash operations.

## Gloas Execution Payload

| Operation | Transactions | Current | Native | Time reduction | GC MB/s | Heap B/op |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Transaction root | 4,096 | 0.947 +/- 0.231 | 0.442 +/- 0.026 | 53.3% | 1929.8 / 55.6 | 1,891,751 / 26,159 |
| Transaction root | 5,461 | 1.183 +/- 0.088 | 0.543 +/- 0.009 | 54.1% | 2020.2 / 53.7 | 2,519,169 / 31,060 |
| Transaction root | 1,048,576 | 231.070 +/- 10.620 | 103.029 +/- 4.465 | 55.4% | 1980.3 / 39.5 | 482,354,429 / 4,329,681 |
| Whole payload | 4,096 | 0.896 +/- 0.063 | 0.463 +/- 0.017 | 48.3% | 2006.2 / 61.2 | 1,894,687 / 30,143 |
| Whole payload | 5,461 | 1.191 +/- 0.082 | 0.549 +/- 0.003 | 53.9% | 2008.2 / 61.2 | 2,522,105 / 35,828 |
| Whole payload | 1,048,576 | 225.666 +/- 17.985 | 100.772 +/- 0.562 | 55.3% | 2030.9 / 40.4 | 482,357,326 / 4,333,049 |
| Deserialize + hash | 4,096 | 0.987 +/- 0.207 | 0.459 +/- 0.007 | 53.5% | 1857.8 / 62.6 | 1,894,687 / 30,143 |
| Deserialize + hash | 5,461 | 1.193 +/- 0.127 | 0.551 +/- 0.022 | 53.8% | 2025.4 / 60.8 | 2,522,105 / 35,128 |
| Deserialize + hash | 1,048,576 | 227.709 +/- 12.158 | 108.035 +/- 0.797 | 52.6% | 2022.1 / 38.2 | 482,357,365 / 4,333,219 |

The native path clears both the whole-object and deserialize-plus-hash proxy
gates at all three counts. At the maximum, normalized heap allocation is about
111 times lower. Progressive SSZ is already materially cheaper than the fixed
pre-Gloas representation, but native tiled hashing still roughly halves elapsed
time.

## Decision

The idea is sound for these synthetic pathological packed/hinted structures on
this host. Operation-scoped FFM arenas and bounded tiling deliver substantial
whole-object and deserialize-plus-hash gains without retaining long-lived native
memory:

- Fulu whole block: 39.1% to 44.4% faster.
- Fulu deserialize-plus-hash: 38.6% to 48.1% faster.
- Gloas whole payload: 48.3% to 55.3% faster.
- Gloas deserialize-plus-hash: 52.6% to 53.8% faster.

This supports continuing to a production-shaped prototype behind an optional
native provider. It is not yet a production adoption decision or a complete
workflow result: payload validation, block processing/state transition,
representative mainnet transaction-size distributions, BeaconState workloads,
Linux x86-64/ARM64 runs, native availability/fallback behavior, and operational
packaging still need validation.

This run covers the cycling-byte variant selected by the implementation plan.
The broader design's identical-byte variant and maximum-case hash-pair/FFM-call
telemetry remain unmeasured.
