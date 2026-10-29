/*
 * Copyright (c) 2026 Consensys Incorporated. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.storage.server.kvstore.dataaccess.statediff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.bls.BLSPublicKey;
import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.infrastructure.ssz.tree.FrozenRanges;
import tech.pegasys.teku.infrastructure.ssz.tree.FrozenSuperNode;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;
import tech.pegasys.teku.infrastructure.statediff.SupernodeFreezer;
import tech.pegasys.teku.infrastructure.statediff.SupernodeSource;
import tech.pegasys.teku.infrastructure.statediff.TreeDiff;
import tech.pegasys.teku.infrastructure.statediff.TreeDiffApplier;
import tech.pegasys.teku.infrastructure.statediff.TreeDiffCodec;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecFactory;
import tech.pegasys.teku.spec.datastructures.state.Validator;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconStateSchema;
import tech.pegasys.teku.spec.logic.common.statetransition.epoch.status.ValidatorStatuses;

/**
 * Measurement harness of the state diff tier 3 attempt (frozen exited-validator supernodes): run
 * against a real beacon state, it reports how much of the registry the freeze rule covers (also for
 * finer supernode depths), the heap a reconstructed state takes with and without freezing, the CPU
 * of one epoch transition on both, and which code paths still load frozen supernodes.
 *
 * <p>Skipped unless the system property {@code teku.tier3.stateFile} or the environment variable
 * {@code TEKU_TIER3_STATE_FILE} names a state SSZ file of the mainnet network (or the network named
 * by {@code TEKU_TIER3_NETWORK}). Results go to stdout and to {@code TEKU_TIER3_OUT} when set.
 * Needs a 10-12 GB heap for a mainnet state; see the tier 3 attempt report for the command.
 *
 * <p>Both variants are reconstructed as the state diff storage does: the state is written as a
 * snapshot diff whose supernodes go to a file store, and the snapshot is applied to the default
 * tree, so every supernode has its own content array (a state deserialized from one SSZ array keeps
 * that whole array alive through its supernode slices). The frozen variant applies the snapshot
 * with {@link ExitedValidatorFreezer}, loading frozen content back from the file.
 */
class FrozenSupernodeHarness {

  private static final int WARMUP_RUNS = 5;
  private static final int MEASURED_RUNS = 10;
  private static final int VALIDATOR_SIZE = Validator.SSZ_SCHEMA.getSszFixedPartSize();

  private final PrintStream out = System.out;
  private final StringBuilder report = new StringBuilder();

  private static Optional<String> setting(final String property, final String variable) {
    return Optional.ofNullable(System.getProperty(property))
        .or(() -> Optional.ofNullable(System.getenv(variable)));
  }

  @Test
  void measure() throws Exception {
    final Optional<String> stateFile = setting("teku.tier3.stateFile", "TEKU_TIER3_STATE_FILE");
    assumeTrue(stateFile.isPresent(), "No state file given");
    final Spec spec =
        SpecFactory.create(setting("teku.tier3.network", "TEKU_TIER3_NETWORK").orElse("mainnet"));
    final Path storeFile = Files.createTempFile("tier3-supernodes", ".bin");
    try (final FileSupernodeStore store = new FileSupernodeStore(storeFile)) {
      run(spec, Path.of(stateFile.get()), store);
    } finally {
      Files.deleteIfExists(storeFile);
      final Optional<String> outFile = setting("teku.tier3.out", "TEKU_TIER3_OUT");
      if (outFile.isPresent()) {
        Files.writeString(Path.of(outFile.get()), report.toString(), StandardCharsets.UTF_8);
      }
    }
  }

  @FormatMethod
  private void print(@FormatString final String format, final Object... args) {
    final String line = String.format(format, args);
    out.println(line);
    report.append(line).append('\n');
  }

  private void run(final Spec spec, final Path stateFile, final FileSupernodeStore store)
      throws Exception {
    // 1. Load and describe the state
    final long loadStart = System.nanoTime();
    BeaconState loaded = spec.deserializeBeaconState(Bytes.wrap(Files.readAllBytes(stateFile)));
    final Bytes32 stateRoot = loaded.hashTreeRoot();
    final UInt64 slot = loaded.getSlot();
    final UInt64 epoch = spec.computeEpochAtSlot(slot);
    final UInt64 targetSlot = spec.computeStartSlotAtEpoch(epoch.plus(1));
    print("# Tier 3 harness");
    print("");
    print(
        "State %s, slot %s, epoch %s, root %s, %s validators, loaded and hashed in %.1f s",
        stateFile.getFileName(),
        slot,
        epoch,
        stateRoot,
        loaded.getValidators().size(),
        (System.nanoTime() - loadStart) / 1e9);
    print("Milestone %s; transition target slot %s", spec.atSlot(slot).getMilestone(), targetSlot);

    // 2. Coverage, from the raw validator bytes
    coverage(loaded.getValidators().sszSerialize(), epoch.longValue());

    // 3. Snapshot diff, its supernodes stored in the file store
    final BeaconStateDiffSupport support = new BeaconStateDiffSupport(spec);
    final BeaconStateSchema<?, ?> schema = support.schemaFor(slot);
    final TreeDiff snapshot =
        support.differFor(slot).diff(schema.getDefaultTree(), loaded.getBackingNode(), store::put);
    final Bytes payload = TreeDiffCodec.serialize(snapshot);
    print("");
    print(
        "Snapshot payload %,d bytes; %,d whole supernode references, %,d bytes of supernodes"
            + " stored",
        payload.size(), snapshot.supernodes().size(), store.storedBytes());
    loaded = null;

    // 4. Memory
    final TreeDiffApplier applier = support.applierFor(slot);
    final TreeDiff diff = TreeDiffCodec.deserialize(payload);
    final long baseline = usedHeapAfterGc();
    BeaconState plain = reconstruct(schema, applier, diff, store, SupernodeFreezer.NONE);
    assertThat(plain.hashTreeRoot()).isEqualTo(stateRoot);
    final long withPlain = usedHeapAfterGc();
    final ExitedValidatorFreezer exitedFreezer = new ExitedValidatorFreezer(spec);
    final long readsBeforeFreeze = store.reads();
    final BeaconState frozen =
        reconstruct(
            schema,
            applier,
            diff,
            store,
            exitedFreezer.forSlot(slot, hash -> store.load(hash).orElseThrow()));
    assertThat(frozen.hashTreeRoot()).isEqualTo(stateRoot);
    final long withBoth = usedHeapAfterGc();
    plain = null;
    final long withFrozen = usedHeapAfterGc();
    final FrozenRanges frozenRanges = rangesOf(frozen);
    print("");
    print("## Memory (used heap after full GC, reconstructed from the snapshot)");
    print("");
    print(
        "Frozen at reconstruction: %,d supernodes, %,d validators (%d ranges); the reconstruction"
            + " read %,d supernodes from the store",
        exitedFreezer.frozenSupernodes(),
        exitedFreezer.frozenValidators(),
        frozenRanges.size(),
        store.reads() - readsBeforeFreeze);
    print("| | heap MB |");
    print("|---|---|");
    print("| baseline (payload, spec, store index) | %.1f |", mb(baseline));
    print("| (a) plain state, over baseline | %.1f |", mb(withPlain - baseline));
    print("| (b) frozen state, over baseline | %.1f |", mb(withFrozen - baseline));
    print("| (a) and (b) together, over baseline | %.1f |", mb(withBoth - baseline));
    print("| saved by freezing, (a) - (b) | %.1f |", mb(withPlain - withFrozen));
    print(
        "| analytic: frozen content bytes | %.1f |",
        mb(exitedFreezer.frozenValidators() * VALIDATOR_SIZE));

    // 5. CPU
    plain = reconstruct(schema, applier, diff, store, SupernodeFreezer.NONE);
    final TreeNode plainTree = plain.getBackingNode();
    final TreeNode frozenTree = frozen.getBackingNode();
    plain = null;
    print("");
    print("## CPU");
    print("");
    timeRanges(schema, plainTree, frozenTree);
    print("");
    print("### Epoch transition, cold: a fresh state instance (empty caches) per run");
    print("");
    final BeaconState plainResult =
        timeTransitions("plain", () -> schema.createFromBackingNode(plainTree), spec, targetSlot);
    final BeaconState frozenResult =
        timeFrozenTransitions(
            "frozen",
            () -> schema.createFromBackingNode(frozenTree),
            frozenTree,
            schema,
            spec,
            targetSlot,
            store);
    assertThat(frozenResult.hashTreeRoot()).isEqualTo(plainResult.hashTreeRoot());
    print("Post-transition state roots equal: %s", frozenResult.hashTreeRoot());
    print(
        "Frozen validators left after the transition: %,d",
        rangesOf(frozenResult).coveredElements());

    print("");
    print(
        "### Epoch transition, warm pubkey index: one pre-state instance reused, its validator"
            + " index cache warmed by one lookup of an unknown pubkey before timing");
    print("");
    final BLSPublicKey unknownKey = BLSPublicKey.empty();
    final BeaconState plainWarm = schema.createFromBackingNode(plainTree);
    final long beforeWarmPlain = usedHeapAfterGc();
    spec.getValidatorIndex(plainWarm, unknownKey);
    final long afterWarmPlain = usedHeapAfterGc();
    final BeaconState plainWarmResult = timeTransitions("plain", () -> plainWarm, spec, targetSlot);
    final BeaconState frozenWarm = schema.createFromBackingNode(frozenTree);
    final long beforeWarmFrozen = usedHeapAfterGc();
    spec.getValidatorIndex(frozenWarm, unknownKey);
    dropMemos(schema, frozenTree);
    final long afterWarmFrozen = usedHeapAfterGc();
    final BeaconState frozenWarmResult =
        timeFrozenTransitions(
            "frozen", () -> frozenWarm, frozenTree, schema, spec, targetSlot, store);
    assertThat(frozenWarmResult.hashTreeRoot()).isEqualTo(plainWarmResult.hashTreeRoot());
    print(
        "Heap added by the warming pubkey scan (validator views cached in the list view, memos"
            + " dropped): plain %.1f MB, frozen %.1f MB",
        mb(afterWarmPlain - beforeWarmPlain), mb(afterWarmFrozen - beforeWarmFrozen));

    print("");
    print("### Parts, cold (fresh state instance per run)");
    print("");
    print("| measurement | variant | median ms | p90 ms | loads/run | store reads/run |");
    print("|---|---|---|---|---|---|");
    timePart(
        "createValidatorStatuses",
        "plain",
        () -> schema.createFromBackingNode(plainTree),
        state -> createStatuses(spec, state),
        store,
        Optional.empty(),
        schema);
    timePart(
        "createValidatorStatuses",
        "frozen",
        () -> schema.createFromBackingNode(frozenTree),
        state -> createStatuses(spec, state),
        store,
        Optional.of(frozenTree),
        schema);
    timePart(
        "getActiveValidatorIndices",
        "plain",
        () -> schema.createFromBackingNode(plainTree),
        state -> spec.getActiveValidatorIndices(state, epoch),
        store,
        Optional.empty(),
        schema);
    timePart(
        "getActiveValidatorIndices",
        "frozen",
        () -> schema.createFromBackingNode(frozenTree),
        state -> spec.getActiveValidatorIndices(state, epoch),
        store,
        Optional.of(frozenTree),
        schema);
    final ValidatorStatuses plainStatuses =
        createStatuses(spec, schema.createFromBackingNode(plainTree));
    final ValidatorStatuses frozenStatuses =
        createStatuses(spec, schema.createFromBackingNode(frozenTree));
    assertThat(frozenStatuses.getTotalBalances().getCurrentEpochActiveValidators())
        .isEqualTo(plainStatuses.getTotalBalances().getCurrentEpochActiveValidators());
    assertThat(
            spec.getActiveValidatorIndices(schema.createFromBackingNode(frozenTree), epoch)
                .toIntArray())
        .isEqualTo(
            spec.getActiveValidatorIndices(schema.createFromBackingNode(plainTree), epoch)
                .toIntArray());
  }

  private static ValidatorStatuses createStatuses(final Spec spec, final BeaconState state) {
    return spec.atSlot(state.getSlot()).getValidatorStatusFactory().createValidatorStatuses(state);
  }

  private static BeaconState reconstruct(
      final BeaconStateSchema<?, ?> schema,
      final TreeDiffApplier applier,
      final TreeDiff diff,
      final SupernodeSource source,
      final SupernodeFreezer freezer) {
    return schema.createFromBackingNode(
        applier.apply(schema.getDefaultTree(), diff, source, freezer));
  }

  /** Coverage of the freeze rule over the registry, at the real and at finer supernode depths. */
  private void coverage(final Bytes validators, final long epoch) {
    final int count = validators.size() / VALIDATOR_SIZE;
    final boolean[] eligible = new boolean[count];
    int eligibleCount = 0;
    int withdrawn = 0;
    for (int i = 0; i < count; i++) {
      final Bytes validator = validators.slice(i * VALIDATOR_SIZE, VALIDATOR_SIZE);
      eligible[i] = ExitedValidatorFreezer.eligibleFromEpoch(validator) <= epoch;
      if (eligible[i]) {
        eligibleCount++;
      }
      final long effectiveBalance = validator.getLong(80, ByteOrder.LITTLE_ENDIAN);
      final long withdrawableEpoch = validator.getLong(113, ByteOrder.LITTLE_ENDIAN);
      if (effectiveBalance == 0 && withdrawableEpoch >= 0 && withdrawableEpoch <= epoch) {
        withdrawn++;
      }
    }
    print("");
    print("## Coverage");
    print("");
    print(
        "%,d validators; %,d withdrawn (effective balance 0, withdrawable epoch passed, %.1f%%);"
            + " %,d meet the whole freeze rule (%.1f%%), the upper bound of any freezing unit",
        count, withdrawn, 100.0 * withdrawn / count, eligibleCount, 100.0 * eligibleCount / count);
    print("");
    print(
        "| supernode depth | validators each | supernodes | eligible | validators covered | %% of"
            + " registry | %% of eligible | bytes covered MB |");
    print("|---|---|---|---|---|---|---|---|");
    for (final int depth : new int[] {8, 7, 6, 5, 4, 3}) {
      final int size = 1 << depth;
      final int supernodes = (count + size - 1) / size;
      int eligibleSupernodes = 0;
      long covered = 0;
      for (int start = 0; start < count; start += size) {
        final int end = Math.min(count, start + size);
        boolean all = true;
        for (int i = start; i < end && all; i++) {
          all = eligible[i];
        }
        if (all) {
          eligibleSupernodes++;
          covered += end - start;
        }
      }
      print(
          "| %d | %d | %,d | %,d | %,d | %.1f | %.1f | %.1f |",
          depth,
          size,
          supernodes,
          eligibleSupernodes,
          covered,
          100.0 * covered / count,
          eligibleCount == 0 ? 0.0 : 100.0 * covered / eligibleCount,
          mb(covered * VALIDATOR_SIZE));
    }
    print("");
    print("| index decile | validators | freeze-eligible | eligible %% |");
    print("|---|---|---|---|");
    for (int decile = 0; decile < 10; decile++) {
      final int start = (int) ((long) count * decile / 10);
      final int end = (int) ((long) count * (decile + 1) / 10);
      int decileEligible = 0;
      for (int i = start; i < end; i++) {
        if (eligible[i]) {
          decileEligible++;
        }
      }
      print(
          "| %d | %,d-%,d | %,d | %.1f |",
          decile, start, end - 1, decileEligible, 100.0 * decileEligible / (end - start));
    }
  }

  private void timeRanges(
      final BeaconStateSchema<?, ?> schema, final TreeNode plainTree, final TreeNode frozenTree) {
    for (final Map.Entry<String, TreeNode> variant :
        Map.of("plain", plainTree, "frozen", frozenTree).entrySet()) {
      final SszList<Validator> validators =
          schema.createFromBackingNode(variant.getValue()).getValidators();
      final long[] nanos = new long[WARMUP_RUNS + MEASURED_RUNS];
      for (int i = 0; i < nanos.length; i++) {
        final long start = System.nanoTime();
        FrozenRanges.collect(
            validators.getSchema(), validators.getBackingNode(), validators.size());
        nanos[i] = System.nanoTime() - start;
      }
      print(
          "FrozenRanges.collect (uncached) on the %s validators: median %.2f ms, p90 %.2f ms",
          variant.getKey(), median(nanos), p90(nanos));
    }
  }

  private BeaconState timeTransitions(
      final String variant,
      final Supplier<BeaconState> preState,
      final Spec spec,
      final UInt64 targetSlot)
      throws Exception {
    final long[] nanos = new long[WARMUP_RUNS + MEASURED_RUNS];
    BeaconState result = null;
    for (int i = 0; i < nanos.length; i++) {
      final BeaconState state = preState.get();
      final long start = System.nanoTime();
      result = spec.processSlots(state, targetSlot);
      nanos[i] = System.nanoTime() - start;
    }
    print(
        "%s: processSlots median %.0f ms, p90 %.0f ms (%d measured runs after %d warm-up)",
        variant, median(nanos), p90(nanos), MEASURED_RUNS, WARMUP_RUNS);
    return result;
  }

  private BeaconState timeFrozenTransitions(
      final String variant,
      final Supplier<BeaconState> preState,
      final TreeNode frozenTree,
      final BeaconStateSchema<?, ?> schema,
      final Spec spec,
      final UInt64 targetSlot,
      final FileSupernodeStore store)
      throws Exception {
    final long[] nanos = new long[WARMUP_RUNS + MEASURED_RUNS];
    final long[] loads = new long[nanos.length];
    final long[] reads = new long[nanos.length];
    final long[] thaws = new long[nanos.length];
    BeaconState result = null;
    for (int i = 0; i < nanos.length; i++) {
      final BeaconState state = preState.get();
      dropMemos(schema, frozenTree);
      final boolean recordSites = i == nanos.length - 1;
      FrozenSuperNode.resetCounters();
      FrozenSuperNode.setRecordLoadSites(recordSites ? 40 : 0);
      final long readsBefore = store.reads();
      final long start = System.nanoTime();
      result = spec.processSlots(state, targetSlot);
      nanos[i] = System.nanoTime() - start;
      loads[i] = FrozenSuperNode.loads();
      thaws[i] = FrozenSuperNode.thaws();
      reads[i] = store.reads() - readsBefore;
    }
    FrozenSuperNode.setRecordLoadSites(0);
    print(
        "%s: processSlots median %.0f ms, p90 %.0f ms; per run: loads %s (median %d), store reads"
            + " median %d, thaws median %d",
        variant,
        median(nanos),
        p90(nanos),
        Arrays.toString(Arrays.copyOfRange(loads, WARMUP_RUNS, loads.length)),
        medianCount(loads),
        medianCount(reads),
        medianCount(thaws));
    final Map<String, Long> sites = FrozenSuperNode.loadSites();
    print("Load call sites of the last run (%d distinct, top frames):", sites.size());
    print("```");
    sites.forEach((site, count) -> print("%,d loads\n%s", count, site));
    print("```");
    FrozenSuperNode.resetCounters();
    return result;
  }

  private void timePart(
      final String name,
      final String variant,
      final Supplier<BeaconState> preState,
      final Consumer<BeaconState> part,
      final FileSupernodeStore store,
      final Optional<TreeNode> frozenTree,
      final BeaconStateSchema<?, ?> schema) {
    final long[] nanos = new long[WARMUP_RUNS + MEASURED_RUNS];
    final long[] loads = new long[nanos.length];
    final long[] reads = new long[nanos.length];
    for (int i = 0; i < nanos.length; i++) {
      final BeaconState state = preState.get();
      frozenTree.ifPresent(tree -> dropMemos(schema, tree));
      FrozenSuperNode.resetCounters();
      final long readsBefore = store.reads();
      final long start = System.nanoTime();
      part.accept(state);
      nanos[i] = System.nanoTime() - start;
      loads[i] = FrozenSuperNode.loads();
      reads[i] = store.reads() - readsBefore;
    }
    print(
        "| %s | %s | %.1f | %.1f | %d | %d |",
        name, variant, median(nanos), p90(nanos), medianCount(loads), medianCount(reads));
  }

  private static FrozenRanges rangesOf(final BeaconState state) {
    final SszList<Validator> validators = state.getValidators();
    return FrozenRanges.collect(
        validators.getSchema(), validators.getBackingNode(), validators.size());
  }

  /** Drops the memoized content of every frozen supernode of {@code tree}'s validators. */
  private static void dropMemos(final BeaconStateSchema<?, ?> schema, final TreeNode tree) {
    forEachFrozen(schema.createFromBackingNode(tree).getValidators(), FrozenSuperNode::dropMemo);
  }

  private static void forEachFrozen(
      final SszList<Validator> validators, final Consumer<FrozenSuperNode> action) {
    if (validators.isEmpty()) {
      return;
    }
    validators
        .getBackingNode()
        .iterateRange(
            validators.getSchema().getChildGeneralizedIndex(0),
            validators.getSchema().getChildGeneralizedIndex(validators.size() - 1),
            (node, gIndex) -> {
              if (node instanceof FrozenSuperNode frozenNode) {
                action.accept(frozenNode);
              }
              return true;
            });
  }

  private static long usedHeapAfterGc() throws InterruptedException {
    for (int i = 0; i < 4; i++) {
      System.gc();
      Thread.sleep(300);
    }
    return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
  }

  private static double mb(final long bytes) {
    return bytes / (1024.0 * 1024.0);
  }

  /** Median of the measured runs, in milliseconds. */
  private static double median(final long[] nanos) {
    return percentile(nanos, 0.5) / 1e6;
  }

  private static double p90(final long[] nanos) {
    return percentile(nanos, 0.9) / 1e6;
  }

  private static long medianCount(final long[] counts) {
    return percentile(counts, 0.5);
  }

  /** Nearest-rank percentile of the measured runs, the warm-up runs left out. */
  private static long percentile(final long[] values, final double fraction) {
    final long[] measured = Arrays.copyOfRange(values, WARMUP_RUNS, values.length);
    Arrays.sort(measured);
    final int rank = (int) Math.ceil(fraction * measured.length);
    return measured[Math.max(0, rank - 1)];
  }

  /**
   * Supernode contents in a file, indexed by hash in memory, so that the content of frozen
   * supernodes really leaves the heap; each read returns a fresh array, as a database read does.
   */
  private static final class FileSupernodeStore implements SupernodeSource, AutoCloseable {
    private final FileChannel channel;
    private final Map<Bytes32, long[]> index = new HashMap<>();
    private final AtomicLong reads = new AtomicLong();
    private long size = 0;

    private FileSupernodeStore(final Path file) throws IOException {
      this.channel =
          FileChannel.open(
              file, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
    }

    private synchronized void put(final Bytes32 hash, final Bytes content) {
      if (index.containsKey(hash)) {
        return;
      }
      try {
        final ByteBuffer buffer = ByteBuffer.wrap(content.toArrayUnsafe());
        long position = size;
        while (buffer.hasRemaining()) {
          position += channel.write(buffer, position);
        }
        index.put(hash, new long[] {size, content.size()});
        size += content.size();
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    private long storedBytes() {
      return size;
    }

    private long reads() {
      return reads.get();
    }

    @Override
    public Optional<Bytes> load(final Bytes32 hash) {
      final long[] entry;
      synchronized (this) {
        entry = index.get(hash);
      }
      if (entry == null) {
        return Optional.empty();
      }
      reads.incrementAndGet();
      final ByteBuffer buffer = ByteBuffer.allocate((int) entry[1]);
      try {
        long position = entry[0];
        while (buffer.hasRemaining()) {
          final int read = channel.read(buffer, position);
          if (read < 0) {
            throw new IOException("Unexpected end of the supernode file");
          }
          position += read;
        }
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
      return Optional.of(Bytes.wrap(buffer.array()));
    }

    @Override
    public void close() throws IOException {
      channel.close();
    }
  }
}
