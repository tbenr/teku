/*
 * Copyright Consensys Software Inc., 2025
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

package tech.pegasys.teku.statetransition.attestation;

import com.google.common.annotations.VisibleForTesting;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import tech.pegasys.teku.infrastructure.metrics.SettableGauge;
import tech.pegasys.teku.infrastructure.metrics.TekuMetricCategory;
import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.infrastructure.ssz.schema.SszListSchema;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.attestation.ValidatableAttestation;
import tech.pegasys.teku.spec.datastructures.operations.Attestation;
import tech.pegasys.teku.spec.datastructures.operations.AttestationData;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.logic.common.helpers.MiscHelpers;
import tech.pegasys.teku.spec.schemas.SchemaDefinitions;
import tech.pegasys.teku.statetransition.attestation.utils.AggregatingAttestationPoolProfiler;
import tech.pegasys.teku.statetransition.attestation.utils.AggregatingAttestationPoolProfilerCSV;
import tech.pegasys.teku.statetransition.attestation.utils.RewardBasedAttestationSorter;
import tech.pegasys.teku.statetransition.attestation.utils.RewardBasedAttestationSorter.AttestationWithRewardInfo;
import tech.pegasys.teku.storage.client.RecentChainData;

/**
 * Maintains a pool of attestations. Attestations can be retrieved either for inclusion in a block
 * or as an aggregate to publish as part of the naive attestation aggregation algorithm. In both
 * cases the returned attestations are aggregated to maximise the number of validators that can be
 * included.
 *
 * <p>This V2 implementation uses concurrent collections to reduce contention.
 */
public class AggregatingAttestationPoolV2 implements AggregatingAttestationPool {
  private static final Logger LOG = LogManager.getLogger();

  /**
   * Default maximum number of attestations to store in the pool.
   *
   * <p>With 2 million active validators, we'd expect around 62_500 attestations per slot; so 3
   * slots worth of attestations is almost 187_500.
   *
   * <p>Strictly to cache all attestations for a full 2 epochs is significantly larger than this
   * cache.
   */

  // Use Concurrent collections for thread-safety without broad synchronization
  private final ConcurrentMap<Bytes, MatchingDataAttestationGroup> attestationGroupByDataHash =
      new ConcurrentHashMap<>();

  private final ConcurrentNavigableMap<UInt64, Set<Bytes>> dataHashBySlot =
      new ConcurrentSkipListMap<>();

  private final Spec spec;
  private final RecentChainData recentChainData;
  private final SettableGauge sizeGauge;
  private final int maximumAttestationCount;
  private final AggregatingAttestationPoolProfiler aggregatingAttestationPoolProfiler;

  private final long maxBlockAggregationTimeNanos;
  private final boolean earlyDropSingleAttestations;
  private final boolean parallel;

  private final AtomicInteger size = new AtomicInteger(0);

  public AggregatingAttestationPoolV2(
      final Spec spec,
      final RecentChainData recentChainData,
      final MetricsSystem metricsSystem,
      final int maximumAttestationCount,
      final AggregatingAttestationPoolProfiler aggregatingAttestationPoolProfiler,
      final int maxBlockAggregationTimeMillis,
      final boolean earlyDropSingleAttestations,
      final boolean parallel) {
    this.spec = spec;
    this.recentChainData = recentChainData;
    this.sizeGauge =
        SettableGauge.create(
            metricsSystem,
            TekuMetricCategory.BEACON,
            "attestation_pool_size",
            "The number of attestations available to be included in proposed blocks (V2 Pool)");
    this.maximumAttestationCount = maximumAttestationCount;
    this.aggregatingAttestationPoolProfiler = aggregatingAttestationPoolProfiler;
    this.maxBlockAggregationTimeNanos = maxBlockAggregationTimeMillis * 1_000_000L;
    this.earlyDropSingleAttestations = earlyDropSingleAttestations;
    this.parallel = parallel;
  }

  @VisibleForTesting
  public AggregatingAttestationPoolV2(
      final Spec spec,
      final RecentChainData recentChainData,
      final MetricsSystem metricsSystem,
      final int maximumAttestationCount) {
    this.spec = spec;
    this.recentChainData = recentChainData;
    this.sizeGauge =
        SettableGauge.create(
            metricsSystem,
            TekuMetricCategory.BEACON,
            "attestation_pool_size",
            "The number of attestations available to be included in proposed blocks (V2 Pool)");
    this.maximumAttestationCount = maximumAttestationCount;
    this.aggregatingAttestationPoolProfiler = AggregatingAttestationPoolProfilerCSV.NOOP;
    this.maxBlockAggregationTimeNanos = Integer.MAX_VALUE * 1_000_000L;
    this.earlyDropSingleAttestations = false;
    this.parallel = false;
  }

  // No longer synchronized
  @Override
  public void add(final ValidatableAttestation attestation) {
    final Optional<Int2IntMap> committeesSize =
        attestation.getCommitteesSize().or(() -> getCommitteesSize(attestation.getAttestation()));
    getOrCreateAttestationGroup(attestation.getAttestation(), committeesSize)
        .ifPresent(attestationGroup -> attestationGroup.add(attestation));
    // Pruning is moved to onSlot to avoid contention during burst adds.
    // A slight overshoot of maximumAttestationCount between onSlot calls is acceptable.
  }

  private Optional<Int2IntMap> getCommitteesSize(final Attestation attestation) {
    if (attestation.requiresCommitteeBits()) {
      return getCommitteesSizeUsingTheState(attestation.getData());
    }
    return Optional.empty();
  }

  /**
   * @param committeesSize Required for aggregating attestations as per <a
   *     href="https://eips.ethereum.org/EIPS/eip-7549">EIP-7549</a>
   */
  private Optional<MatchingDataAttestationGroup> getOrCreateAttestationGroup(
      final Attestation attestation, final Optional<Int2IntMap> committeesSize) {
    final AttestationData attestationData = attestation.getData();
    // if an attestation has committee bits, committees size should have been computed. If this is
    // not the case, we should ignore this attestation and not add it to the pool
    if (attestation.requiresCommitteeBits() && committeesSize.isEmpty()) {
      LOG.debug(
          "Committees size couldn't be retrieved for attestation at slot {}, block root {} and target root {}. Will NOT add this attestation to the pool.",
          attestationData.getSlot(),
          attestationData.getBeaconBlockRoot(),
          attestationData.getTarget().getRoot());
      return Optional.empty();
    }

    final Bytes dataHash = attestationData.hashTreeRoot();

    // Atomically get or create the set for the slot
    dataHashBySlot
        // Use ConcurrentHashMap::newKeySet for a concurrent set implementation
        .computeIfAbsent(attestationData.getSlot(), __ -> ConcurrentHashMap.newKeySet())
        .add(dataHash);

    // Atomically get or create the MatchingDataAttestationGroupV2
    final MatchingDataAttestationGroup attestationGroup =
        attestationGroupByDataHash.computeIfAbsent(
            dataHash,
            __ ->
                new MatchingDataAttestationGroupV2(
                    spec,
                    attestationData,
                    committeesSize,
                    earlyDropSingleAttestations)); // Pass spec, data, committeesSize

    return Optional.of(attestationGroup);
  }

  // This method primarily reads immutable/thread-safe data or state likely protected
  // by RecentChainData's own synchronization. It should be safe without external locks here.
  private Optional<Int2IntMap> getCommitteesSizeUsingTheState(
      final AttestationData attestationData) {
    // we can use the first state of the epoch to get committees for an attestation
    final MiscHelpers miscHelpers = spec.atSlot(attestationData.getSlot()).miscHelpers();
    final Optional<UInt64> maybeEpoch = recentChainData.getCurrentEpoch();
    // the only reason this can happen is we don't have a store yet.
    if (maybeEpoch.isEmpty()) {
      return Optional.empty();
    }
    final UInt64 currentEpoch = maybeEpoch.get();
    final UInt64 attestationEpoch = miscHelpers.computeEpochAtSlot(attestationData.getSlot());

    LOG.debug("currentEpoch {}, attestationEpoch {}", currentEpoch, attestationEpoch);
    if (attestationEpoch.equals(currentEpoch)
        || attestationEpoch.equals(currentEpoch.minusMinZero(1))) {

      return recentChainData
          .getBestState()
          .flatMap(
              state -> {
                try {
                  // Assuming getBeaconCommitteesSize is thread-safe or state access is handled
                  return Optional.of(
                      spec.getBeaconCommitteesSize(
                          state.getImmediately(), attestationData.getSlot()));
                } catch (IllegalStateException e) {
                  LOG.debug(
                      "Couldn't retrieve state for committee calculation of slot {}",
                      attestationData.getSlot());
                  return Optional.empty();
                }
              });
    }

    // attestation is not from the current or previous epoch
    // this is really an edge case because the current or previous epoch is at least 31 slots
    // and the attestation is only valid for 64 slots, so it may be epoch-2 but not beyond.
    final UInt64 attestationEpochStartSlot = miscHelpers.computeStartSlotAtEpoch(attestationEpoch);
    LOG.debug("State at slot {} needed", attestationEpochStartSlot);
    try {
      // Assuming retrieveStateInEffectAtSlot and getBeaconCommitteesSize are thread-safe
      return recentChainData
          .retrieveStateInEffectAtSlot(attestationEpochStartSlot)
          .getImmediately()
          .map(state -> spec.getBeaconCommitteesSize(state, attestationData.getSlot()));
    } catch (final IllegalStateException e) {
      LOG.debug(
          "Couldn't retrieve state in effect at slot {} for committee calculation of slot {}",
          attestationEpochStartSlot,
          attestationData.getSlot());
      return Optional.empty();
    }
  }

  // No longer synchronized
  @Override
  public void onSlot(final UInt64 slot) {
    final int currentActualSize =
        attestationGroupByDataHash.values().stream()
            .mapToInt(
                MatchingDataAttestationGroup
                    ::size) // Uses the group's potentially approximate size method
            .sum();

    size.set(currentActualSize); // Atomically set the definitive size for this slot boundary
    sizeGauge.set(currentActualSize);
    LOG.trace("Attestation pool size recalculated to {}", currentActualSize);

    // Prune attestations older than ATTESTATION_RETENTION_SLOTS
    if (slot.compareTo(ATTESTATION_RETENTION_SLOTS) > 0) {
      final UInt64 firstValidAttestationSlot = slot.minus(ATTESTATION_RETENTION_SLOTS);
      removeAttestationsPriorToSlot(firstValidAttestationSlot);
    }

    // Let's go with Option A for simplicity, accepting minor inaccuracy in the pruning target for
    // this cycle:
    int sizeForPruningCheck = currentActualSize; // Use the size calculated at the start of onSlot
    while (dataHashBySlot.size() > 1 && sizeForPruningCheck > maximumAttestationCount) {
      LOG.trace(
          "V2 Attestation cache at {} (pre-prune estimate) exceeds {}. Pruning...",
          sizeForPruningCheck,
          maximumAttestationCount);
      final UInt64 oldestSlot = dataHashBySlot.firstKey();
      if (oldestSlot == null) {
        break; // Handle empty map case
      }

      // Estimate the size reduction (since removeAttestationsPriorToSlot no longer updates 'size')
      // This is tricky because group.size() is approximate.
      // We might need to actually get the groups to be removed and sum their sizes *before*
      // removal.
      int estimatedRemovalCount = 0;
      final Set<Bytes> hashesToRemove =
          dataHashBySlot.get(oldestSlot.plus(1)); // Check slot *after* oldest
      if (hashesToRemove != null) {
        for (final Bytes hash : hashesToRemove) {
          MatchingDataAttestationGroup group = attestationGroupByDataHash.get(hash);
          if (group != null) {
            estimatedRemovalCount += group.size();
          }
        }
      }

      removeAttestationsPriorToSlot(oldestSlot.plus(1)); // Remove the items

      if (estimatedRemovalCount == 0) {
        // If we estimated 0 removed, or failed to find the slot, break to avoid potential infinite
        // loop
        LOG.warn(
            "Failed to prune oldest slot {} or estimated 0 removals. Skipping further pruning this cycle.",
            oldestSlot);
        break;
      }
      sizeForPruningCheck -= estimatedRemovalCount; // Reduce the size we are checking against
    }

    aggregatingAttestationPoolProfiler.execute(spec, slot, recentChainData, this);
  }

  // Internal method, not synchronized, careful with concurrent access
  private void removeAttestationsPriorToSlot(final UInt64 firstValidAttestationSlot) {
    // headMap provides a view, collect keys to avoid issues with concurrent modification during
    // removal
    final NavigableMap<UInt64, Set<Bytes>> headMap =
        dataHashBySlot.headMap(firstValidAttestationSlot, false);
    final List<UInt64> slotsToRemove = List.copyOf(headMap.keySet());

    if (slotsToRemove.isEmpty()) {
      return;
    }

    LOG.trace(
        "V2 Pruning attestations before slot {}. Slots to remove: {}",
        firstValidAttestationSlot,
        slotsToRemove.size());

    for (final UInt64 slot : slotsToRemove) {
      // Remove from dataHashBySlot first
      final Set<Bytes> dataHashes = dataHashBySlot.remove(slot);
      if (dataHashes != null) {
        // Now remove corresponding entries from attestationGroupByDataHash
        for (final Bytes key : dataHashes) {
          final MatchingDataAttestationGroup removedGroup = attestationGroupByDataHash.remove(key);
          if (removedGroup != null) {
            // Get size (needs read lock internally in MatchingDataAttestationGroupV2)
            removedGroup.size();
          }
        }
      }
    }
  }

  // No longer synchronized
  @Override
  public void onAttestationsIncludedInBlock(
      final UInt64 slot, final Iterable<Attestation> attestations) {
    attestations.forEach(attestation -> onAttestationIncludedInBlock(slot, attestation));
  }

  // Internal helper, not synchronized
  private void onAttestationIncludedInBlock(final UInt64 slot, final Attestation attestation) {
    // getOrCreateAttestationGroup is safe for concurrency
    getOrCreateAttestationGroup(attestation, getCommitteesSize(attestation))
        .ifPresent(
            attestationGroup -> {
              // MatchingDataAttestationGroupV2 must handle concurrency internally
              final int numRemoved =
                  attestationGroup.onAttestationIncludedInBlock(slot, attestation);
              if (numRemoved > 0) {
                updateSize(-numRemoved);
              }
            });
  }

  private void updateSize(final int delta) {
    if (delta != 0) {
      final int currentSize = size.addAndGet(delta);
      sizeGauge.set(currentSize);
    }
  }

  // No longer synchronized
  @Override
  public int getSize() {
    return size.get();
  }

  public record ValidatableAttestationWithSortingReward(
      ValidatableAttestation validatableAttestation, long sortingRewardNumerator) {}

  static final Comparator<ValidatableAttestationWithSortingReward> REWARD_COMPARATOR =
      Comparator.comparingLong(ValidatableAttestationWithSortingReward::sortingRewardNumerator)
          .reversed();

  // No longer synchronized
  @Override
  public SszList<Attestation> getAttestationsForBlock(
      final BeaconState stateAtBlockSlot, final AttestationForkChecker forkChecker) {
    final UInt64 currentEpoch = spec.getCurrentEpoch(stateAtBlockSlot);
    final int previousEpochLimit = spec.getPreviousEpochAttestationCapacity(stateAtBlockSlot);

    final RewardBasedAttestationSorter rewardBasedAttestationSorter =
        RewardBasedAttestationSorter.create(spec, stateAtBlockSlot);
    final SchemaDefinitions schemaDefinitions =
        spec.atSlot(stateAtBlockSlot.getSlot()).getSchemaDefinitions();

    final SszListSchema<Attestation, ?> attestationsSchema =
        schemaDefinitions.getBeaconBlockBodySchema().getAttestationsSchema();

    final boolean blockRequiresAttestationsWithCommitteeBits =
        schemaDefinitions.getAttestationSchema().requiresCommitteeBits();

    final AtomicInteger prevEpochCount = new AtomicInteger(0);

    final long timeLimitNanos = System.nanoTime() + maxBlockAggregationTimeNanos;

    // Iterating ConcurrentSkipListMap is weakly consistent and safe
    var dataHashes =
        dataHashBySlot
            // We can immediately skip any attestations from the block slot or later
            .headMap(stateAtBlockSlot.getSlot(), false)
            .descendingMap() // Safe view
            .values();

    var aggregates =
        (parallel ? dataHashes.parallelStream() : dataHashes.stream())
            .flatMap(
                dataHashSetForSlot ->
                    streamAggregatesForDataHashesBySlot(
                        dataHashSetForSlot, // dataHashSetForSlot is expected to be a Concurrent Set
                        stateAtBlockSlot,
                        forkChecker,
                        blockRequiresAttestationsWithCommitteeBits))
            .filter(
                attestation -> {
                  if (spec.computeEpochAtSlot(attestation.getData().getSlot())
                      .isLessThan(currentEpoch)) {
                    final int currentCount = prevEpochCount.getAndIncrement();
                    return currentCount < previousEpochLimit;
                  }
                  return true;
                });
    return rewardBasedAttestationSorter
        .sort(aggregates.sequential(), Math.toIntExact(attestationsSchema.getMaxLength()))
        .stream()
        .peek(
            attestation ->
                aggregatingAttestationPoolProfiler.onPreFillUp(stateAtBlockSlot, attestation))
        .map(validatableAttestation -> fillUpAttestation(validatableAttestation, timeLimitNanos))
        .peek(
            attestation ->
                aggregatingAttestationPoolProfiler.onPostFillUp(stateAtBlockSlot, attestation))
        .map(
            validatableAttestationWithSortingReward ->
                validatableAttestationWithSortingReward.attestation().getAttestation())
        .collect(attestationsSchema.collector());
  }

  private AttestationWithRewardInfo fillUpAttestation(
      final AttestationWithRewardInfo attestationWithRewards, final long timeLimitNanos) {
    if (System.nanoTime() > timeLimitNanos) {
      LOG.info("Time limit reached, skipping fillUpAttestation");
      return attestationWithRewards;
    }

    var attestation = attestationWithRewards.attestation();
    return Optional.ofNullable(attestationGroupByDataHash.get(attestation.getData().hashTreeRoot()))
        .map(
            group ->
                new AttestationWithRewardInfo(
                    group.fillUpAggregation(attestation, timeLimitNanos),
                    attestationWithRewards.attestingIndices(),
                    attestationWithRewards.participationFlagIndices(),
                    attestationWithRewards.validatorBaseRewards(),
                    Map.of(),
                    attestationWithRewards.isCurrentEpoch(),
                    attestationWithRewards.rewardNumerator()))
        .orElse(attestationWithRewards);
  }

  // Internal helper, not synchronized
  private Stream<ValidatableAttestation> streamAggregatesForDataHashesBySlot(
      final Set<Bytes> dataHashSetForSlot, // Assumed concurrent set
      final BeaconState stateAtBlockSlot,
      final AttestationForkChecker forkChecker,
      final boolean blockRequiresAttestationsWithCommitteeBits) {

    //    var maybeSlot =
    // dataHashSetForSlot.stream().findFirst().map(attestationGroupByDataHash::get).filter(Objects::nonNull).map(validatableAttestations -> validatableAttestations.getAttestationData().getSlot());
    //
    //    maybeSlot.ifPresent(slot->
    //    System.out.println("streamAggregatesForDataHashesBySlot slot: " + slot +" size: " +
    // dataHashSetForSlot.size())
    //    );

    // Stream over the concurrent set is safe
    return dataHashSetForSlot.stream()
        .map(attestationGroupByDataHash::get) // ConcurrentHashMap.get is safe
        .filter(Objects::nonNull)
        // MatchingDataAttestationGroupV2 methods must be thread-safe
        .filter(group -> group.isValid(stateAtBlockSlot, spec)) // Add spec param
        .filter(forkChecker::areAttestationsFromCorrectFork) // Assumed thread-safe or stateless
        .flatMap(MatchingDataAttestationGroup::stream) // Must return a safe stream
        // .map(ValidatableAttestation::getAttestation)
        .filter(
            attestation ->
                attestation.getAttestation().requiresCommitteeBits()
                    == blockRequiresAttestationsWithCommitteeBits);
    // .sorted(ATTESTATION_INCLUSION_COMPARATOR); // Sorting happens on collected stream elements
  }

  // No longer synchronized
  @Override
  public List<Attestation> getAttestations(
      final Optional<UInt64> maybeSlot, final Optional<UInt64> maybeCommitteeIndex) {

    final Predicate<Map.Entry<UInt64, Set<Bytes>>> filterForSlot =
        (entry) -> maybeSlot.map(slot -> entry.getKey().equals(slot)).orElse(true);

    final UInt64 slot = maybeSlot.orElse(recentChainData.getCurrentSlot().orElse(UInt64.ZERO));
    final SchemaDefinitions schemaDefinitions = spec.atSlot(slot).getSchemaDefinitions();

    final boolean requiresCommitteeBits =
        schemaDefinitions.getAttestationSchema().requiresCommitteeBits();

    // Iterate concurrent map safely
    return dataHashBySlot.descendingMap().entrySet().stream()
        .filter(filterForSlot)
        .map(Map.Entry::getValue) // Gets the concurrent Set<Bytes>
        .flatMap(Collection::stream) // Streams the concurrent Set<Bytes> safely
        .map(attestationGroupByDataHash::get) // ConcurrentHashMap.get is safe
        .filter(Objects::nonNull)
        .flatMap(
            matchingDataAttestationGroup ->
                // stream must be thread-safe
                matchingDataAttestationGroup.stream(maybeCommitteeIndex, requiresCommitteeBits))
        .map(ValidatableAttestation::getAttestation)
        .toList(); // Collect results
  }

  // No longer synchronized
  @Override
  public Optional<ValidatableAttestation> createAggregateFor(
      final Bytes32 attestationHashTreeRoot, final Optional<UInt64> committeeIndex) {
    // ConcurrentHashMap.get is safe
    return Optional.ofNullable(attestationGroupByDataHash.get(attestationHashTreeRoot))
        // stream(committeeIndex).findFirst() must be thread-safe
        .flatMap(attestations -> attestations.stream(committeeIndex).findFirst());
  }

  // No longer synchronized
  @Override
  public void onReorg(final UInt64 commonAncestorSlot) {
    // .values() on ConcurrentHashMap provides a weakly consistent view, safe to iterate
    attestationGroupByDataHash.values().forEach(group -> group.onReorg(commonAncestorSlot));
  }
}
