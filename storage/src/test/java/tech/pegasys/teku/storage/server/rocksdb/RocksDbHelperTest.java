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

package tech.pegasys.teku.storage.server.rocksdb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class RocksDbHelperTest {

  @Test
  void columnFilterIsCaseInsensitiveSubstring() {
    assertThat(RocksDbHelper.matchesColumnFilter("STATE_DIFF_NODES", Optional.empty())).isTrue();
    assertThat(RocksDbHelper.matchesColumnFilter("STATE_DIFF_NODES", Optional.of("STATE_")))
        .isTrue();
    assertThat(RocksDbHelper.matchesColumnFilter("STATE_SUPERNODES", Optional.of("supernode")))
        .isTrue();
    assertThat(RocksDbHelper.matchesColumnFilter("HOT_BLOCKS_BY_ROOT", Optional.of("STATE_")))
        .isFalse();
    assertThat(RocksDbHelper.matchesColumnFilter(null, Optional.of("STATE_"))).isFalse();
    assertThat(RocksDbHelper.matchesColumnFilter(null, Optional.empty())).isTrue();
  }
}
