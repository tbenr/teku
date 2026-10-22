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
import tech.pegasys.infrastructure.logging.LogCaptor;

class StoreConfigTest {

  @Test
  void stateDiffsDisabledByDefault() {
    final StoreConfig config = StoreConfig.createDefault();

    assertThat(config.isStateDiffsEnabled()).isFalse();
    assertThat(config.getHotStatePersistenceFrequencyInEpochs())
        .isEqualTo(StoreConfig.DEFAULT_HOT_STATE_PERSISTENCE_FREQUENCY_IN_EPOCHS);
  }

  @Test
  void stateDiffsPersistEveryEpoch() {
    final StoreConfig config =
        StoreConfig.builder()
            .hotStatePersistenceFrequencyInEpochs(0)
            .stateDiffsEnabled(true)
            .build();

    assertThat(config.isStateDiffsEnabled()).isTrue();
    assertThat(config.getHotStatePersistenceFrequencyInEpochs()).isEqualTo(1);
  }

  @Test
  void warnsThatHotStatePersistenceFrequencyIsIgnoredWithStateDiffs() {
    try (LogCaptor logCaptor = LogCaptor.forClass(StoreConfig.class)) {
      StoreConfig.builder().hotStatePersistenceFrequencyInEpochs(5).stateDiffsEnabled(true).build();

      logCaptor.assertWarnLog(
          "--Xhot-state-persistence-frequency (5) is ignored when state diff storage is enabled");
    }
  }

  @Test
  void doesNotWarnAboutDefaultHotStatePersistenceFrequency() {
    try (LogCaptor logCaptor = LogCaptor.forClass(StoreConfig.class)) {
      StoreConfig.builder().stateDiffsEnabled(true).build();
      StoreConfig.builder().hotStatePersistenceFrequencyInEpochs(5).build();

      assertThat(logCaptor.getWarnLogs()).isEmpty();
    }
  }

  @Test
  void withStateDiffsEnabledKeepsOtherSettings() {
    final StoreConfig config =
        StoreConfig.builder().stateCacheSize(3).hotStatePersistenceFrequencyInEpochs(4).build();

    final StoreConfig enabled = config.withStateDiffsEnabled();

    assertThat(enabled.isStateDiffsEnabled()).isTrue();
    assertThat(enabled.getStateCacheSize()).isEqualTo(3);
    assertThat(enabled.getHotStatePersistenceFrequencyInEpochs()).isEqualTo(1);
    assertThat(enabled).isNotEqualTo(config);
    assertThat(config.withStateDiffsEnabled()).isEqualTo(enabled);
  }
}
