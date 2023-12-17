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

package tech.pegasys.teku.spec.datastructures.blocks.blockbody.versions.bellatrix;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import java.util.function.Function;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.ssz.primitive.SszBytes32;
import tech.pegasys.teku.spec.datastructures.blocks.blockbody.BeaconBlockBody;
import tech.pegasys.teku.spec.datastructures.blocks.blockbody.BeaconBlockBodyBuilder;
import tech.pegasys.teku.spec.datastructures.blocks.blockbody.BeaconBlockBodySchema;
import tech.pegasys.teku.spec.datastructures.blocks.blockbody.versions.altair.BeaconBlockBodyBuilderAltair;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayload;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayloadHeader;
import tech.pegasys.teku.spec.datastructures.type.SszSignature;

public class BeaconBlockBodyBuilderBellatrix extends BeaconBlockBodyBuilderAltair {
  protected SafeFuture<ExecutionPayload> executionPayload;
  protected SafeFuture<ExecutionPayloadHeader> executionPayloadHeader;

  public BeaconBlockBodyBuilderBellatrix() {}

  @Override
  public Boolean supportsExecutionPayload() {
    return true;
  }

  @Override
  public BeaconBlockBodyBuilder executionPayload(SafeFuture<ExecutionPayload> executionPayload) {
    this.executionPayload = executionPayload;
    return this;
  }

  @Override
  public BeaconBlockBodyBuilder executionPayloadHeader(
      SafeFuture<ExecutionPayloadHeader> executionPayloadHeader) {
    this.executionPayloadHeader = executionPayloadHeader;
    return this;
  }

  @Override
  @SuppressWarnings("unchecked")
  protected <T> T getAndValidateSchema(
      final Function<Boolean, BeaconBlockBodySchema<?>> blindedToSchemaResolver,
      final Class<? extends T> schemaType) {
    final BeaconBlockBodySchema<?> schema = blindedToSchemaResolver.apply(isBlinded());
    checkNotNull(schema, "schema must be specified");
    checkArgument(
        schemaType.isInstance(schema),
        String.format("Schema should be: %s", schemaType.getSimpleName()));
    return (T) schema;
  }

  @Override
  protected void validate() {
    super.validate();
    checkState(
        executionPayload != null ^ executionPayloadHeader != null,
        "only and only one of executionPayload or executionPayloadHeader must be set");
  }

  protected Boolean isBlinded() {
    return executionPayloadHeader != null;
  }

  @Override
  public SafeFuture<BeaconBlockBody> build(
      final Function<Boolean, BeaconBlockBodySchema<?>> blindedToSchemaResolver) {
    validate();
    if (isBlinded()) {
      final BlindedBeaconBlockBodySchemaBellatrixImpl schema =
          getAndValidateSchema(
              blindedToSchemaResolver, BlindedBeaconBlockBodySchemaBellatrixImpl.class);
      return executionPayloadHeader.thenApply(
          header ->
              new BlindedBeaconBlockBodyBellatrixImpl(
                  schema,
                  new SszSignature(randaoReveal),
                  eth1Data,
                  SszBytes32.of(graffiti),
                  proposerSlashings,
                  attesterSlashings,
                  attestations,
                  deposits,
                  voluntaryExits,
                  syncAggregate,
                  header.toVersionBellatrix().orElseThrow()));
    }

    final BeaconBlockBodySchemaBellatrixImpl schema =
        getAndValidateSchema(blindedToSchemaResolver, BeaconBlockBodySchemaBellatrixImpl.class);
    return executionPayload.thenApply(
        payload ->
            new BeaconBlockBodyBellatrixImpl(
                schema,
                new SszSignature(randaoReveal),
                eth1Data,
                SszBytes32.of(graffiti),
                proposerSlashings,
                attesterSlashings,
                attestations,
                deposits,
                voluntaryExits,
                syncAggregate,
                payload.toVersionBellatrix().orElseThrow()));
  }
}
