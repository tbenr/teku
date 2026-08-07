# Hashtree FFM Kernel Results: macOS ARM64

## Environment

- Date: 2026-08-07
- Host: macOS 26.6, ARM64 T6041
- JVM: Azul Zulu JDK 25 (25+36-LTS), aarch64
- Teku commit: `0562bc212623caa92214e5706585b41eefd1dc2b`
- Hashtree commit: `30497cff98a06362eadde897202634f91d504fd8`
- JMH: 1.37, two forks, three 1-second warmups, five 1-second measurements

The raw JMH output is stored alongside this report as
`2026-08-07-macos-arm64-kernel.json`.

## Full-Tree Gate

Scores are average microseconds per operation with JMH 99.9% confidence errors.

| First-layer pairs | JCA | Native reusable scratch | Gain | Native operation arena | Gain |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 128 | 10.996 +/- 0.269 | 7.645 +/- 0.045 | 30.5% | 8.310 +/- 0.227 | 24.4% |
| 512 | 46.121 +/- 5.319 | 30.595 +/- 0.168 | 33.7% | 31.355 +/- 0.327 | 32.0% |
| 2,048 | 193.037 +/- 32.725 | 123.541 +/- 1.231 | 36.0% | 126.528 +/- 0.668 | 34.5% |
| 8,192 | 760.852 +/- 100.778 | 516.794 +/- 8.514 | 32.1% | 505.573 +/- 3.980 | 33.6% |
| 32,768 | 2,965.516 +/- 578.345 | 2,059.248 +/- 85.243 | 30.6% | 2,074.576 +/- 78.948 | 30.0% |

JCA normalized allocation rises from 12,288 B/op at 128 pairs to 3,145,750 B/op at
32,768 pairs. Reusable native scratch generally reports 48-59 B/op through 8,192
pairs, while operation-scoped arenas report 287-376 B/op around the gate sizes.
The GC profiler does not count off-heap arena storage; its allocation and cleanup
time is included in the benchmark score.

## One-Layer Crossover

The native kernel and reusable-staging variants beat JCA from count 1 on this
host. The operation-arena variant nominally crosses JCA at count 4, but the
99.9% confidence intervals overlap at counts 4 and 8. It is clearly faster by
count 16:

| Count | JCA | Native operation arena | Difference |
| ---: | ---: | ---: | ---: |
| 2 | 0.103 +/- 0.021 | 0.118 +/- 0.001 | 15.0% slower |
| 4 | 0.190 +/- 0.036 | 0.184 +/- 0.002 | 3.0% faster |
| 8 | 0.363 +/- 0.053 | 0.316 +/- 0.002 | 13.0% faster |
| 16 | 0.770 +/- 0.104 | 0.584 +/- 0.020 | 24.1% faster |

## Decision

Continue both the supernode and packed-payload experiment paths. Both
staging-inclusive full-tree variants exceed the 20% kernel threshold throughout
the configured supernode range and at both configured payload-scale sizes.

This is a local macOS ARM64 result, not the production adoption decision.
Linux x86-64 or Linux ARM64 measurements and the whole-object/workflow gates
remain required.
