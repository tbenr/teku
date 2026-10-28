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

package tech.pegasys.teku.storage.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StoreConfigTest {

  @Test
  void stateDiffsDisabledByDefault() {
    final StoreConfig config = StoreConfig.createDefault();

    assertThat(config.isStateDiffsEnabled()).isFalse();
    assertThat(config.getHotStatePersistenceFrequencyInEpochs())
        .isEqualTo(StoreConfig.DEFAULT_HOT_STATE_PERSISTENCE_FREQUENCY_IN_EPOCHS);
  }

  @Test
  void stateDiffsRequestKeepsTheConfiguredFrequency() {
    // The store's persistence policy, not the configuration, ignores the frequency
    final StoreConfig config =
        StoreConfig.builder()
            .hotStatePersistenceFrequencyInEpochs(0)
            .stateDiffsEnabled(true)
            .build();

    assertThat(config.isStateDiffsEnabled()).isTrue();
    assertThat(config.getHotStatePersistenceFrequencyInEpochs()).isZero();
    assertThat(config)
        .isNotEqualTo(StoreConfig.builder().hotStatePersistenceFrequencyInEpochs(0).build());
  }
}
