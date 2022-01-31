/*
 * Copyright 2020 ConsenSys AG.
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

package tech.pegasys.teku.validator.client;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

import com.google.common.collect.Sets;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tech.pegasys.teku.bls.BLSPublicKey;
import tech.pegasys.teku.infrastructure.async.AsyncRunner;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.validator.api.ValidatorApiChannel;
import tech.pegasys.teku.validator.client.loader.OwnedValidators;

public class ValidatorIndexProvider {

  private static final Logger LOG = LogManager.getLogger();
  public static final Duration RETRY_DELAY = Duration.ofSeconds(5);
  private final OwnedValidators ownedValidators;
  private final ValidatorApiChannel validatorApiChannel;
  private final AsyncRunner asyncRunner;
  private final Map<BLSPublicKey, Integer> validatorIndexesByPublicKey = new ConcurrentHashMap<>();

  private final AtomicBoolean requestInProgress = new AtomicBoolean(false);
  private final SafeFuture<Void> firstSuccessfulRequest = new SafeFuture<>();

  public ValidatorIndexProvider(
      final OwnedValidators ownedValidators,
      final ValidatorApiChannel validatorApiChannel,
      final AsyncRunner asyncRunner) {
    this.ownedValidators = ownedValidators;
    this.validatorApiChannel = validatorApiChannel;
    this.asyncRunner = asyncRunner;
  }

  public void lookupValidators() {
    final Collection<BLSPublicKey> unknownValidators = getUnknownValidators();
    if (unknownValidators.isEmpty()) {
      return;
    }

    if (!requestInProgress.compareAndSet(false, true)) {
      return;
    }
    LOG.trace("Looking up {} unknown validators", unknownValidators.size());
    validatorApiChannel
        .getValidatorIndices(unknownValidators)
        .thenAccept(
            knownValidators -> {
              logNewValidatorIndices(knownValidators);
              validatorIndexesByPublicKey.putAll(knownValidators);
              firstSuccessfulRequest.complete(null);
            })
        .orTimeout(30, TimeUnit.SECONDS)
        .whenComplete((result, error) -> requestInProgress.set(false))
        .finish(
            error -> {
              LOG.warn("Failed to load validator indexes. Retrying.", error);
              asyncRunner.runAfterDelay(this::lookupValidators, RETRY_DELAY).reportExceptions();
            });
  }

  private Collection<BLSPublicKey> getUnknownValidators() {
    return Sets.difference(ownedValidators.getPublicKeys(), validatorIndexesByPublicKey.keySet());
  }

  private void logNewValidatorIndices(final Map<BLSPublicKey, Integer> knownValidators) {
    if (!knownValidators.isEmpty()) {
      LOG.debug(
          "Discovered new indices for validators: {}",
          () ->
              knownValidators.entrySet().stream()
                  .map(entry -> entry.getKey().toString() + "=" + entry.getValue())
                  .collect(joining(", ")));
    }
  }

  public Optional<Integer> getValidatorIndex(final BLSPublicKey publicKey) {
    return Optional.ofNullable(validatorIndexesByPublicKey.get(publicKey));
  }

  public SafeFuture<Collection<Integer>> getValidatorIndices() {
    // Wait for at least one successful load of validator indices before attempting to read
    return firstSuccessfulRequest.thenApply(
        __ ->
            ownedValidators.getActiveValidators().stream()
                .flatMap(validator -> getValidatorIndex(validator.getPublicKey()).stream())
                .collect(toList()));
  }

  public SafeFuture<Map<BLSPublicKey, Integer>> getValidatorIndexesByPublicKey() {
    // Wait for at least one successful load of validator indices before attempting to read
    return firstSuccessfulRequest.thenApply(
        __ ->
            ownedValidators.getActiveValidators().stream()
                .map(Validator::getPublicKey)
                .collect(
                    Collectors.<BLSPublicKey, BLSPublicKey, Integer>toMap(
                        Function.identity(), validatorIndexesByPublicKey::get)));
  }
}
