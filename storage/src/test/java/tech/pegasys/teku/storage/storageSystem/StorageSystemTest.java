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

package tech.pegasys.teku.storage.storageSystem;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.storage.store.StoreConfig;

class StorageSystemTest {

  @Test
  void requestingStateDiffsCopiesEveryStoreConfigField() {
    // Every field differs from its default, so a field the copy drops shows up as a difference
    final StoreConfig storeConfig =
        StoreConfig.builder()
            .stateCacheSize(3)
            .epochStateCacheSize(4)
            .blockCacheSize(5)
            .checkpointStateCacheSize(6)
            .hotStatePersistenceFrequencyInEpochs(7)
            .initialCanonicalBlockRoot(
                "0x0102030405060708091011121314151617181920212223242526272829303132")
            .build();

    final StoreConfig requested = StorageSystem.requestingStateDiffs(storeConfig);

    assertThat(requested.isStateDiffsEnabled()).isTrue();
    assertThat(requested)
        .usingRecursiveComparison()
        .ignoringFields("stateDiffsEnabled")
        .isEqualTo(storeConfig);
    // A field added to StoreConfig must be added to the copy, and set above
    assertThat(
            Arrays.stream(StoreConfig.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName))
        .describedAs("fields of StoreConfig, see StorageSystem.requestingStateDiffs")
        .containsExactlyInAnyOrder(
            "stateCacheSize",
            "epochStateCacheSize",
            "blockCacheSize",
            "checkpointStateCacheSize",
            "hotStatePersistenceFrequencyInEpochs",
            "initialCanonicalBlockRoot",
            "stateDiffsEnabled");
  }
}
