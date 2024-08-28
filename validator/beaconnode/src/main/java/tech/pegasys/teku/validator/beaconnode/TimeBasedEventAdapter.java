/*
 * Copyright Consensys Software Inc., 2022
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

package tech.pegasys.teku.validator.beaconnode;

import static tech.pegasys.teku.infrastructure.time.TimeUtilities.millisToSeconds;
import static tech.pegasys.teku.infrastructure.time.TimeUtilities.secondsToMillis;
import static tech.pegasys.teku.spec.constants.NetworkConstants.INTERVALS_PER_SLOT;
import static tech.pegasys.teku.spec.constants.NetworkConstants.INTERVALS_PER_SLOT_EIP7732;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.async.timed.RepeatingTaskScheduler;
import tech.pegasys.teku.infrastructure.async.timed.RepeatingTaskScheduler.RepeatingTask;
import tech.pegasys.teku.infrastructure.time.TimeProvider;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.validator.api.ValidatorTimingChannel;

public class TimeBasedEventAdapter implements BeaconChainEventAdapter {
  private static final Logger LOG = LogManager.getLogger();

  private final GenesisDataProvider genesisDataProvider;
  private final RepeatingTaskScheduler taskScheduler;
  private final TimeProvider timeProvider;
  private final ValidatorTimingChannel validatorTimingChannel;
  private final Spec spec;
  private UInt64 genesisTime;

  public TimeBasedEventAdapter(
      final GenesisDataProvider genesisDataProvider,
      final RepeatingTaskScheduler taskScheduler,
      final TimeProvider timeProvider,
      final ValidatorTimingChannel validatorTimingChannel,
      final Spec spec) {
    this.genesisDataProvider = genesisDataProvider;
    this.taskScheduler = taskScheduler;
    this.timeProvider = timeProvider;
    this.validatorTimingChannel = validatorTimingChannel;
    this.spec = spec;
  }

  void start(final UInt64 genesisTime) {
    this.genesisTime = genesisTime;
    final UInt64 currentSlot = getCurrentSlot();

    final UInt64 nextSlotStartTime = spec.getSlotStartTime(currentSlot.plus(1), genesisTime);
    final UInt64 secondsPerSlot = getSecondsPerSlot(currentSlot);

    // NOTE: seconds_per_slot currently based on genesis slot, and timings set up based on this
    //       if seconds_per_slot ever changes, timers would have to be updated, which isn't
    //       currently implemented.

    taskScheduler.scheduleRepeatingEvent(nextSlotStartTime, secondsPerSlot, this::onStartSlot);

    final UInt64 nextSlotStartTimeMillis = secondsToMillis(nextSlotStartTime);

    final UInt64 millisPerSlot = secondsToMillis(secondsPerSlot);

    final SpecMilestone currentMilestone = spec.atSlot(currentSlot).getMilestone();

    if (currentMilestone.equals(SpecMilestone.EIP7732)) {
      final UInt64 attestationDueSlotTimeOffset =
          millisPerSlot.dividedBy(INTERVALS_PER_SLOT_EIP7732);
      final UInt64 aggregationDueSlotTimeOffset =
          millisPerSlot.times(2).dividedBy(INTERVALS_PER_SLOT_EIP7732);
      final UInt64 timelinessAttestationDueSlotTimeOffset =
          millisPerSlot.times(3).dividedBy(INTERVALS_PER_SLOT_EIP7732);

      scheduleDuty(
          nextSlotStartTimeMillis,
          millisPerSlot,
          attestationDueSlotTimeOffset,
          this::onAttestationCreationDue);
      scheduleDuty(
          nextSlotStartTimeMillis,
          millisPerSlot,
          aggregationDueSlotTimeOffset,
          this::onAggregationDue);
      scheduleDuty(
          nextSlotStartTimeMillis,
          millisPerSlot,
          timelinessAttestationDueSlotTimeOffset,
          this::onTimelinessAttestationDue);
      return;
    }

    if (spec.getForkSchedule()
        .getHighestSupportedMilestone()
        .isGreaterThanOrEqualTo(SpecMilestone.EIP7732)) {
      final UInt64 eip7732StartTimeMillis =
          secondsToMillis(
              spec.getSlotStartTime(
                  spec.computeStartSlotAtEpoch(
                      spec.getForkSchedule().getFork(SpecMilestone.EIP7732).getEpoch()),
                  genesisTime));

      final UInt64 attestationDueSlotTimeOffset = millisPerSlot.dividedBy(INTERVALS_PER_SLOT);
      final UInt64 aggregationDueSlotTimeOffset =
          millisPerSlot.times(2).dividedBy(INTERVALS_PER_SLOT);

      final UInt64 newAttestationDueSlotTimeOffset =
          millisPerSlot.dividedBy(INTERVALS_PER_SLOT_EIP7732);
      final UInt64 newAggregationDueSlotTimeOffset =
          millisPerSlot.times(2).dividedBy(INTERVALS_PER_SLOT_EIP7732);
      final UInt64 timelinessAttestationDueSlotTimeOffset =
          millisPerSlot.times(3).dividedBy(INTERVALS_PER_SLOT_EIP7732);

      taskScheduler.scheduleRepeatingEventInMillis(
          nextSlotStartTimeMillis.plus(attestationDueSlotTimeOffset),
          millisPerSlot,
          this::onAttestationCreationDue,
          eip7732StartTimeMillis,
          (__, ___) ->
              scheduleDuty(
                  millisPerSlot, newAttestationDueSlotTimeOffset, this::onAttestationCreationDue));
      taskScheduler.scheduleRepeatingEventInMillis(
          nextSlotStartTimeMillis.plus(aggregationDueSlotTimeOffset),
          millisPerSlot,
          this::onAggregationDue,
          eip7732StartTimeMillis,
          (__, ___) -> {
            scheduleDuty(millisPerSlot, newAggregationDueSlotTimeOffset, this::onAggregationDue);
            scheduleDuty(
                millisPerSlot,
                timelinessAttestationDueSlotTimeOffset,
                this::onTimelinessAttestationDue);
          });
      return;
    }

    // no EIP7732 support

    final UInt64 attestationDueSlotTimeOffset = millisPerSlot.dividedBy(INTERVALS_PER_SLOT);
    final UInt64 aggregationDueSlotTimeOffset =
        millisPerSlot.times(2).dividedBy(INTERVALS_PER_SLOT);

    scheduleDuty(
        nextSlotStartTimeMillis,
        millisPerSlot,
        attestationDueSlotTimeOffset,
        this::onAttestationCreationDue);
    scheduleDuty(
        nextSlotStartTimeMillis,
        millisPerSlot,
        aggregationDueSlotTimeOffset,
        this::onAggregationDue);
  }

  private void scheduleDuty(
      final UInt64 period, final UInt64 offset, final RepeatingTask dutyTask) {
    scheduleDuty(getNextSlotStartMillis(), period, offset, dutyTask);
  }

  private void scheduleDuty(
      final UInt64 nextSlotStartTimeMillis,
      final UInt64 period,
      final UInt64 offset,
      final RepeatingTask dutyTask) {
    taskScheduler.scheduleRepeatingEventInMillis(
        nextSlotStartTimeMillis.plus(offset), period, dutyTask);
  }

  private UInt64 getNextSlotStartMillis() {
    final UInt64 currentSlot = getCurrentSlot();
    final UInt64 nextSlotStartTime = spec.getSlotStartTime(currentSlot.plus(1), genesisTime);
    return secondsToMillis(nextSlotStartTime);
  }

  private UInt64 getCurrentSlot() {
    return spec.getCurrentSlot(timeProvider.getTimeInSeconds(), genesisTime);
  }

  private UInt64 getSecondsPerSlot(final UInt64 slot) {
    return UInt64.valueOf(spec.getSecondsPerSlot(slot));
  }

  private void onStartSlot(final UInt64 scheduledTime, final UInt64 actualTime) {
    final UInt64 slot = spec.getCurrentSlot(scheduledTime, genesisTime);
    if (isTooLate(scheduledTime, actualTime)) {
      LOG.warn(
          "Skipping block creation for slot {} due to unexpected delay in slot processing", slot);
      return;
    }
    LOG.info("EVENT *** onStartSlot");
    validatorTimingChannel.onSlot(slot);
    validatorTimingChannel.onBlockProductionDue(slot);
  }

  private void onAttestationCreationDue(
      final UInt64 scheduledTimeInMillis, final UInt64 actualTimeInMillis) {
    final UInt64 slot = getCurrentSlotForMillis(scheduledTimeInMillis);
    if (isTooLateInMillis(scheduledTimeInMillis, actualTimeInMillis)) {
      LOG.warn("Skipping attestation for slot {} due to unexpected delay in slot processing", slot);
      return;
    }
    LOG.info("EVENT *** onAttestationCreationDue");
    validatorTimingChannel.onAttestationCreationDue(slot);
  }

  private void onAggregationDue(
      final UInt64 scheduledTimeInMillis, final UInt64 actualTimeInMillis) {
    final UInt64 slot = getCurrentSlotForMillis(scheduledTimeInMillis);
    if (isTooLateInMillis(scheduledTimeInMillis, actualTimeInMillis)) {
      LOG.warn("Skipping aggregation for slot {} due to unexpected delay in slot processing", slot);
      return;
    }
    LOG.info("EVENT *** onAggregationDue");
    validatorTimingChannel.onAttestationAggregationDue(slot);
  }

  private void onTimelinessAttestationDue(
      final UInt64 scheduledTimeInMillis, final UInt64 actualTimeInMillis) {
    final UInt64 slot = getCurrentSlotForMillis(scheduledTimeInMillis);
    if (isTooLateInMillis(scheduledTimeInMillis, actualTimeInMillis)) {
      LOG.warn(
          "Skipping timeliness attestation for slot {} due to unexpected delay in slot processing",
          slot);
      return;
    }
    LOG.info("EVENT *** onTimelinessAttestationDue");
  }

  private UInt64 getCurrentSlotForMillis(final UInt64 millis) {
    return spec.getCurrentSlot(millisToSeconds(millis), genesisTime);
  }

  private boolean isTooLate(final UInt64 scheduledTime, final UInt64 actualTime) {
    final UInt64 currentSlot = getCurrentSlot();
    final UInt64 secondsPerSlot = getSecondsPerSlot(currentSlot);
    return scheduledTime.plus(secondsPerSlot).isLessThan(actualTime);
  }

  private boolean isTooLateInMillis(
      final UInt64 scheduledTimeInMillis, final UInt64 actualTimeInMillis) {
    final UInt64 currentSlot = getCurrentSlot();
    final UInt64 millisPerSlot = getMillisPerSlot(currentSlot);
    return scheduledTimeInMillis.plus(millisPerSlot).isLessThan(actualTimeInMillis);
  }

  private UInt64 getMillisPerSlot(final UInt64 slot) {
    return secondsToMillis(getSecondsPerSlot(slot));
  }

  @Override
  public SafeFuture<Void> start() {
    // Don't wait for the genesis time to be available before considering startup complete
    // The beacon node may not be available or genesis may not yet be known.
    genesisDataProvider.getGenesisTime().thenAccept(this::start).ifExceptionGetsHereRaiseABug();
    return SafeFuture.COMPLETE;
  }

  @Override
  public SafeFuture<Void> stop() {
    return SafeFuture.COMPLETE;
  }
}
