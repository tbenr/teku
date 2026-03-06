/*
 * Copyright Consensys Software Inc., 2026
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

package tech.pegasys.teku.storage.server.kvstore.schema;

import java.util.Collection;
import java.util.Map;
import org.apache.tuweni.bytes.Bytes;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;

/**
 * Schema for diff-based state storage. Defines 7 columns, one per hierarchy level, keyed by epoch
 * (UInt64) and storing serialized diff bytes.
 */
public interface SchemaCombinedDiffState extends SchemaCombined {

  KvStoreColumn<UInt64, Bytes> getColumnStateDiffLevel0();

  KvStoreColumn<UInt64, Bytes> getColumnStateDiffLevel1();

  KvStoreColumn<UInt64, Bytes> getColumnStateDiffLevel2();

  KvStoreColumn<UInt64, Bytes> getColumnStateDiffLevel3();

  KvStoreColumn<UInt64, Bytes> getColumnStateDiffLevel4();

  KvStoreColumn<UInt64, Bytes> getColumnStateDiffLevel5();

  KvStoreColumn<UInt64, Bytes> getColumnStateDiffLevel6();

  default KvStoreColumn<UInt64, Bytes> getColumnStateDiffLevel(final int level) {
    return switch (level) {
      case 0 -> getColumnStateDiffLevel0();
      case 1 -> getColumnStateDiffLevel1();
      case 2 -> getColumnStateDiffLevel2();
      case 3 -> getColumnStateDiffLevel3();
      case 4 -> getColumnStateDiffLevel4();
      case 5 -> getColumnStateDiffLevel5();
      case 6 -> getColumnStateDiffLevel6();
      default -> throw new IllegalArgumentException("Invalid diff level: " + level);
    };
  }

  @Override
  Map<String, KvStoreColumn<?, ?>> getColumnMap();

  @Override
  Map<String, KvStoreVariable<?>> getVariableMap();

  @Override
  default Collection<KvStoreColumn<?, ?>> getAllColumns() {
    return getColumnMap().values();
  }

  @Override
  default Collection<KvStoreVariable<?>> getAllVariables() {
    return getVariableMap().values();
  }
}
