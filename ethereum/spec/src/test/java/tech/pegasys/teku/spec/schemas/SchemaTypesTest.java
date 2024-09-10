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

package tech.pegasys.teku.spec.schemas;

import static java.lang.reflect.Modifier.isFinal;
import static java.lang.reflect.Modifier.isStatic;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.spec.schemas.SchemaTypes.SchemaId;

public class SchemaTypesTest {

  @Test
  public void testStaticFieldNamesMatchCreateArguments() throws IllegalAccessException {
    // Get all declared fields in the SchemaTypes class
    final Field[] fields = SchemaTypes.class.getDeclaredFields();

    for (final Field field : fields) {
      // Ensure the field is static and final
      if (isStatic(field.getModifiers()) && isFinal(field.getModifiers())) {

        // Get the field name
        final String fieldName = field.getName();

        // Get the value of the field
        field.setAccessible(true);
        if (field.get(null) instanceof SchemaId<?> schemaId) {
          assertEquals(
              fieldName,
              schemaId.getName(),
              "Field name does not match the create argument for field: " + fieldName);
        }
      }
    }
  }
}
