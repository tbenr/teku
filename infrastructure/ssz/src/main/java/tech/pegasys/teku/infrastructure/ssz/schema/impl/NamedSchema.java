/*
 * Copyright Consensys Software Inc., 2024
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

package tech.pegasys.teku.infrastructure.ssz.schema.impl;

import tech.pegasys.teku.infrastructure.ssz.SszData;
import tech.pegasys.teku.infrastructure.ssz.schema.SszFieldName;
import tech.pegasys.teku.infrastructure.ssz.schema.SszSchema;

public class NamedSchema<T extends SszData> {
  private final String name;
  private final SszSchema<T> schema;

  public static <T extends SszData> NamedSchema<T> of(
      final String name, final SszSchema<T> schema) {
    return new NamedSchema<>(name, schema);
  }

  NamedSchema(final String name, final SszSchema<T> schema) {
    this.name = name;
    this.schema = schema;
  }

  public static <T extends SszData> NamedSchema<T> namedSchema(
      final SszFieldName fieldName, final SszSchema<T> schema) {
    return namedSchema(fieldName.getSszFieldName(), schema);
  }

  public static <T extends SszData> NamedSchema<T> namedSchema(
      final String fieldName, final SszSchema<T> schema) {
    return new NamedSchema<>(fieldName, schema);
  }

  public String getName() {
    return name;
  }

  public SszSchema<T> getSchema() {
    return schema;
  }
}
