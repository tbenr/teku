# Progressive SSZ Gloas Design

## Context

Teku's local `progressive_ssz_gloas_testing` branch is a starting point for bringing the
progressive SSZ implementation from `tbenr/progressive_ssz_electra_testing` into Gloas,
removing the Electra experiment, and integrating the latest consensus-specs upper-bound
changes from ethereum/consensus-specs#4630.

The active target is the current `sn-7688` Gloas specification work, not the older Electra
experiment. The current spec introduces progressive SSZ types for Gloas data structures and
defines type-specific SSZ bounds for libp2p messages affected by unbounded progressive fields.

Consensys/teku#10395 raised three design concerns that this spec addresses:

- Progressive lists are unbounded, which weakens `SszLengthBounds` for gossip and RPC.
- BeaconState fork upgrades must rebuild fields when target schemas change from bounded lists
  to progressive lists.
- Progressive list implementations need specialized types that preserve existing Teku
  interfaces, such as `SszUInt64List`.

## Goals

- Implement progressive SSZ for Gloas in Teku without reintroducing Electra schema changes.
- Model upper bounds as schema metadata created by the schema registry, not as ad hoc consumer
  parameters.
- Keep gossip and RPC consumers on the existing `schema.getSszLengthBounds()` path.
- Add setup-time visibility for network-exposed schemas that remain unbounded.
- Replace the old BeaconState fork-upgrade `instanceof` approach with schema-aware field
  rematerialization.
- Preserve existing Teku list interfaces when progressive implementations are substituted.

## Non-Goals

- Do not make Electra state or Electra attestations progressive in this implementation.
- Do not add an optional parameter to `SszSchema.getSszLengthBounds()`.
- Do not remove `SszLengthBounds` saturation logic in the first pass.
- Do not implement partial column sidecar support unless the existing Teku partial-message
  code path is brought into scope separately.
- Do not implement Heze inclusion-list or EIP8025 execution-proof bounds in the first Gloas
  pass unless those local schemas and networking paths are explicitly included.

## Design Summary

Use schema/registry-owned upper bounds for network-exposed progressive types.

Progressive list, bitlist, and byte-list schemas may remain internally unbounded. Top-level
schemas that are used as gossip or RPC payloads can receive finite max-byte overrides from
the schema registry. Consumers keep calling `getSszLengthBounds()`, and the returned bounds
represent the correct network-facing type limit.

This keeps optionality at schema construction time, where the type is known, and avoids
injecting contextual limits into unrelated non-progressive schemas at the consumer side.

## Alternatives Considered

### Registry-Owned Type Bounds

Attach finite type-specific bounds during schema creation or by wrapping top-level registered
schemas. This is the recommended approach because it matches the consensus-specs model: bounds
belong to SSZ types, while gossip and RPC remain generic consumers.

Trade-offs:

- Requires registry/provider support for bound metadata.
- Requires care with schema equality and cache reuse.
- Keeps network logic simple and testable.

### Field-Level Progressive Bounds

Pass optional upper bounds to each progressive field schema, such as every
`SszProgressiveListSchema` or `SszProgressiveBitlistSchema`.

Trade-offs:

- Makes recursive length calculation naturally finite.
- Leaks top-level network policy into reusable field aliases and state internals.
- Still requires top-level type-specific values for spec conformance.

This is useful as a fallback for fields with real domain limits, but should not be the primary
network-bound mechanism.

### Consumer-Side Bound Parameters

Add an optional upper-bound parameter to gossip/RPC calls into `getSszLengthBounds`.

Trade-offs:

- Lets consumers override non-progressive schemas in odd ways.
- Makes boundedness depend on call site instead of schema identity.
- Spreads progressive-specific policy through networking APIs.

This approach should be avoided.

## SSZ Infrastructure

### Progressive Schemas

The implementation should support these progressive schemas:

- `SszProgressiveListSchema<T>`
- `SszProgressiveUInt64ListSchema`
- `SszProgressiveBitlistSchema`
- `SszProgressiveByteListSchema`

Progressive schemas default to unbounded length bounds. Where a finite bound is passed at
schema creation, that bound must be included in `equals`, `hashCode`, and diagnostics. Current
local code has `SszProgressiveListSchema.equals` comparing only element schema, which is unsafe
with the registry cache because bounded and unbounded variants could be reused incorrectly.

Specialized progressive schemas must keep Teku's existing interfaces intact. For example,
balances and inactivity scores need list instances compatible with `SszUInt64List`.

### Length Bounds

`SszLengthBounds` should gain explicit boundedness semantics, such as `isUnbounded()`.
Consumers and audits should not infer unboundedness only from `getMaxBytes() == Long.MAX_VALUE`,
because saturation arithmetic can also produce `Long.MAX_VALUE`.

Keep existing saturation arithmetic initially. Removing it is safe only after an audit proves
all unbounded progressive schema sources are either internal-only or replaced by finite
top-level bounds before any recursive network length calculation can overflow.

### Progressive Containers

`AbstractSszContainerSchema` already has progressive active-field support, but progressive
container store/load currently throws `UnsupportedOperationException`. Before BeaconState or
other persisted containers become progressive, the store/load implementation from the
progressive SSZ branch must be completed and tested.

Progressive container equality must continue to include active fields. If top-level bounds are
stored directly on container schemas instead of provider wrappers, bounds must also participate
in equality.

## Schema Registry

Schema providers should be able to attach a finite max SSZ byte bound to the schema they
produce. Two implementation shapes are acceptable:

- A provider-level wrapper that delegates to the created schema and overrides
  `getSszLengthBounds()`.
- A bound-aware container/list schema variant where the bound is part of schema state.

The provider wrapper is preferred for top-level network bounds because it avoids passing
network constants into every nested field.

The registry cache must not collapse schemas with different bounds. The implementation should
either:

- include bound metadata in schema equality, or
- use `alwaysCreateNewSchema()` for providers where the bound changes across milestones.

Bound overrides should be applied to top-level schema providers for Gloas types named by the
consensus-specs type-specific bounds:

- `SignedAggregateAndProof`: `MAX_SIGNED_AGGREGATE_AND_PROOF_SIZE = 16829`
- `AttesterSlashing`: `MAX_ATTESTER_SLASHING_SIZE = 2097616`
- `DataColumnSidecar`: `MAX_DATA_COLUMN_SIDECAR_SIZE = 8585272`
- `SignedExecutionPayloadBid`: `MAX_SIGNED_EXECUTION_PAYLOAD_BID_SIZE = 196932`
- `SignedBeaconBlock`: `MAX_SIGNED_BEACON_BLOCK_SIZE = 4034304`

`MAX_PARTIAL_DATA_COLUMN_SIDECAR_SIZE = 8585741` should be added to config/preset tracking,
but not wired into networking until Teku has a local `PartialDataColumnSidecar` message path.

## Gloas Schema Migration

Before converting classes, reconcile current Teku Gloas schemas with the latest
consensus-specs branch. The local `ExecutionRequestsSchemaGloas` currently has five fields
including builder deposits and exits, while the active raw Gloas spec example shows the three
Electra execution request lists. The progressive active-field shape must match the final
target spec, not an older local intermediate.

Convert these Gloas structures to progressive containers where specified:

- `Attestation`
- `IndexedAttestation`
- `BeaconBlockBody`
- `ExecutionRequests`
- `BeaconState`
- `ExecutionPayload`
- `DataColumnSidecar`

Convert these collection fields to progressive variants where specified:

- `Attestation.aggregation_bits`
- `IndexedAttestation.attesting_indices`
- `BeaconBlockBody` operation lists and `payload_attestations`
- `ExecutionRequests` request lists
- `ExecutionPayload.transactions`, `withdrawals`, and `block_access_list`
- `ExecutionPayloadBid.blob_kzg_commitments`
- `DataColumnSidecar.column` and `kzg_proofs`
- `BeaconState.validators`, `balances`, participation lists, inactivity scores, pending lists,
  builders, builder pending withdrawals, and payload expected withdrawals

Existing validation logic that enforces domain limits must remain. Progressive lists make SSZ
Merkleization forward-compatible; they do not replace checks such as max operation counts,
request counts, validator counts, blob counts, or withdrawal counts.

## BeaconState Upgrade

Do not revive the old Electra experiment's `instanceof MutableBeaconStateElectra` check in
`BeaconStateFields.copyCommonFieldsFromSource`.

Instead, introduce a target-schema-aware rematerialization helper for fork upgrades. The helper
should rebuild list-like fields using the destination field schema when the destination schema
differs from the source schema.

The intended shape is:

- Locate the target field schema by `BeaconStateFields` name or field index.
- If the field value is a list and the target schema is a list schema, create a new value from
  the source elements using the target schema.
- Preserve direct assignment for fixed fields and fields whose schema is unchanged.
- Keep casts localized inside the helper so fork-upgrade code stays readable.

`GloasStateUpgrade` must use this for common fields and inherited Fulu fields that become
progressive in Gloas, including validators, balances, participation lists, inactivity scores,
pending deposits, pending partial withdrawals, pending consolidations, and any Gloas-added
progressive list initialized from a previous bounded schema.

This makes schema migration a property of the target state schema, not a type check against a
specific mutable state implementation.

## Networking Audit

Keep RPC and gossip decode behavior unchanged: they should continue to call
`schema.getSszLengthBounds()`.

Add a small shared audit helper for network-exposed schemas:

- On gossip topic handler construction, inspect the message schema.
- On RPC method construction, inspect request schemas and response schemas available through
  the method context.
- If a schema is unbounded, log a warning with the protocol or topic name and schema name.
- Tests should assert that known Gloas network schemas with spec-defined type bounds are not
  unbounded.

Runtime warning is preferable to runtime failure during the transition because some internal or
not-yet-final spec paths may temporarily remain unbounded. Tests should be strict for the
network schemas that are explicitly covered by Gloas type-specific bounds.

## Testing Strategy

### SSZ Unit Tests

- Progressive list, bitlist, and byte-list serialization/deserialization.
- Progressive schema equality and hash code include bound metadata.
- `SszLengthBounds.isUnbounded()` distinguishes true unboundedness from saturated finite math.
- Progressive container active-field generalized indices remain stable.
- Progressive container store/load works for persisted schemas.

### Registry Tests

- Bound overrides are attached to the correct Gloas schema providers.
- Registry cache reuse does not collapse schemas with different bounds.
- Existing non-progressive schemas still return their computed recursive bounds.

### Gloas Schema Tests

- Gloas progressive container active fields match the target spec.
- Gloas progressive fields preserve expected Teku interfaces.
- Top-level type bounds match consensus-specs constants.
- Domain validators still reject over-limit operation and request counts.

### State Upgrade Tests

- Fulu-to-Gloas upgrade rebuilds changed list fields into destination progressive schemas.
- No fork-upgrade helper uses milestone-specific `instanceof` checks for schema migration.
- Hash tree roots and SSZ serialization match generated consensus reference tests once
  reference vectors are available.

### Networking Tests

- Gossip and RPC setup warn on unbounded network-exposed schemas.
- Known Gloas schemas with type-specific SSZ bounds do not warn.
- Snappy and RPC length checks reject payloads above the schema-provided max bound.

### Verification Commands

- `./gradlew --no-daemon :infrastructure:ssz:compileJava`
- `./gradlew --no-daemon :infrastructure:ssz:test`
- `./gradlew --no-daemon :ethereum:spec:test`
- `./gradlew --no-daemon :networking:eth2:test`
- Targeted reference tests for Gloas SSZ static and fork transition vectors when available.

## Implementation Sequence

1. Restore compile baseline in `infrastructure:ssz`.
2. Add explicit boundedness to `SszLengthBounds`.
3. Finish progressive schema equality, optional finite bounds, and byte-list support.
4. Finish progressive container store/load.
5. Add schema-registry bound override support.
6. Add Gloas SSZ bound constants to config and presets.
7. Wire type-specific bounds for top-level Gloas network schemas.
8. Convert Gloas operation, block, execution, data-column, and state schemas to progressive SSZ.
9. Add target-schema-aware BeaconState upgrade rematerialization.
10. Add gossip/RPC setup audits for unbounded network schemas.
11. Run module tests and reference tests as vectors become available.

## Risks And Mitigations

- **Spec drift:** Reconcile with the exact consensus-specs PR head before implementing each
  schema conversion. Keep spec-bound constants in one place so rebases are mechanical.
- **Registry cache reuse:** Include bound metadata in equality or force new schema creation for
  bound-changing providers.
- **State persistence:** Do not enable progressive BeaconState until progressive container
  store/load is implemented and tested.
- **Overflow assumptions:** Keep saturation until a complete boundedness audit proves it is safe
  to remove.
- **Interface substitution:** Add dedicated progressive schemas for primitive lists used through
  specialized Teku interfaces.
- **Network exposure:** Use setup warnings plus strict tests for the schemas covered by
  type-specific Gloas bounds.

## Acceptance Criteria

- Gloas schemas use progressive SSZ according to the target consensus-specs branch.
- Network-exposed Gloas progressive payloads return finite `SszLengthBounds` where the spec
  defines type-specific bounds.
- Gossip and RPC consumers do not accept contextual upper-bound parameters.
- Fulu-to-Gloas BeaconState upgrade rebuilds changed list fields without milestone-specific
  `instanceof` checks.
- Existing validation logic still enforces operation-count and request-count limits.
- `infrastructure:ssz`, `ethereum:spec`, and `networking:eth2` targeted tests pass.
