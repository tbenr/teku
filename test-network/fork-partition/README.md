# Fork partition test mode

TEST ONLY. This scenario creates several contending forks that do not finalize, then restores
consensus on demand. It needs a patch that adds `ForkPartitionTestMode` (in
`storage/.../protoarray/`).

## Get the patch

The reference commit is the tip of
[`tbenr/teku:fork-partition-test-mode`](https://github.com/tbenr/teku/tree/fork-partition-test-mode),
a single commit on top of master. Cherry-pick it onto the branch you want to test:

```bash
git fetch git@github.com:tbenr/teku.git fork-partition-test-mode
git cherry-pick FETCH_HEAD
```

The cherry-pick brings in this directory too. Drop the commit before opening a PR.

## How it works

The mode is on while the control file exists inside the Teku container. The file is
`$TEKU_FORK_PARTITION_FILE` and defaults to `/tmp/teku-fork-partition`. While the mode is on:

- The node still imports every block it receives, so peers' blocks are in its store, its
  proto-array and its hot state storage.
- Only two kinds of block can become head:
  - blocks this node published through its validator API;
  - blocks up to the slot at which the mode was switched on.
- As a result, the node's validators attest to and build on the node's own fork only. With three
  nodes, no fork gets 2/3 of the target votes, so nothing justifies or finalizes.

Deleting the file restores normal fork choice. The file is re-read before every head
recomputation.

## Run

```bash
./gradlew distDocker            # builds consensys/teku:develop
kurtosis run --enclave fork-partition github.com/ethpandaops/ethereum-package@6886eac7b858d8f28dc2f3cf4e7985cbdb926e56 \
  --args-file test-network/fork-partition/network_params.yaml
P=test-network/fork-partition/partition.sh
$P fork-partition status        # wait until finalized > 1
```

### Scenario A: three forks, then everybody recovers

```bash
$P fork-partition on            # 3 forks, finality stops
$P fork-partition status        # different head roots, finalized epoch frozen
$P fork-partition off           # nodes converge, finality resumes after ~2-3 epochs
```

### Scenario B: the majority recovers, one node stays partitioned

```bash
$P fork-partition on
$P fork-partition off cl-1 cl-2 # cl-1 + cl-2 hold 80% of the stake and finalize
$P fork-partition status        # watch cl-3: its own fork is pruned on finalization
$P fork-partition off cl-3      # cl-3 rejoins
```

What the first run showed (2026-11-15):
- Once cl-1 and cl-2 justify, cl-3's own fork stops being viable, and its head trails the
  canonical chain.
- cl-3 reports optimistic: geth says it is syncing a branch cl-3 never sent a forkchoiceUpdated
  for. Its duties are skipped as a result.
- After `off cl-3`, it is back on the canonical head and fully validated within one slot.

The validator split is 64/64/32. With an even split, two nodes would hold exactly 2/3, and a
single missed attestation would block justification.

Dora (see `kurtosis enclave inspect fork-partition`) shows the forks. Each node logs
`Fork partition test mode ENABLED/DISABLED` when it sees the switch.

## Caveats

- `ethereum-package` is pinned to the last commit that runs with kurtosis CLI 1.22. Newer commits use
  `PortSpec(host_ip=...)`, which needs a newer CLI.
- The list of a node's own blocks is kept in memory. A node restarted during a partition forgets
  its own blocks and joins the heaviest fork.
- Recovery reorgs the EL by the length of the partition. Use geth (path scheme); Besu Bonsai only
  reorgs back 512 blocks.
- Given enough time, the inactivity leak lets every fork finalize on its own. On minimal that
  takes thousands of epochs.
