# Provider Reconciliation Design

> Date: 2026-08-21
> Status: design

## Goal

Give providers an explicit, core-controlled way to resolve parked `NEEDS_PROVIDER_ACTION` instances caused by transient effect-handler failures, without provider callbacks that can mutate state, without auto-resume, and without resuming missing-definition or `RUNNING` cases.

## Background

`RuntimeEngine.applyEffects` parks an instance in `NEEDS_PROVIDER_ACTION` when an effect handler is missing, a handler throws, or `returnClaims` fails. Once parked, the block stays busy until a provider calls `dismissInstance`. A provider may re-register the missing handler or fix the throwing code, but there is currently no API to request a re-attempt.

## In scope

- Public API `CraftingManagerApi.reconcileInstance(UUID instanceId) -> ProcessReconcileResult`.
- Core-internal `ParkedReason` metadata persisted in `craftingmanager.process_instances`.
- Re-attempting `NEEDS_PROVIDER_ACTION` only for:
  - `MISSING_EFFECT_HANDLER` — the effect was never executed and the handler is now registered.
  - `EFFECT_HANDLER_EXCEPTION` — the effect is recorded `UNKNOWN` and its handler is now registered and has an `IdempotencyMode` of `IDEMPOTENT` or `PROVIDER_DEDUPLICATES`.
- Synchronized, revision-checked core operation.

## Out of scope

- Auto-reconciliation on `registerProcess`, `registerEffectHandler`, or chunk load.
- Resuming `RUNNING` instances.
- Resuming `MISSING_DEFINITION` instances (requires persisted definition identity/shape and a rehydration redesign).
- Reconciling `RETURN_CLAIM_FAILED` in v1. `returnClaims` can partially return claims and then short-circuit on retry because it sets `reservationState = FAILED` and only retries when state is `RESERVED`. Making this safe requires per-claim, idempotent returns with persisted progress.
- Reconciling `EFFECT_HANDLER_EXCEPTION` for `NON_RETRYABLE` handlers.

## API

```java
// CraftingManagerApi
ProcessReconcileResult reconcileInstance(UUID instanceId);

public record ProcessReconcileResult(boolean reconciled, ProcessState state, String reason) {}
```

No new public fields on `ProcessInstanceSnapshot` for v1.

## Data model

- Add internal `ParkedReason` enum:
  - `NONE`
  - `MISSING_EFFECT_HANDLER`
  - `EFFECT_HANDLER_EXCEPTION`
  - `RETURN_CLAIM_FAILED`
  - `MISSING_DEFINITION`
  - `UNKNOWN`
- Add `parked_reason` text column to `craftingmanager.process_instances` (migration `V004__parked_reason.sql`).
- `ProcessInstanceRecord` stores `parkedReason` as a string; `RuntimeEngine` maps to/from the internal enum.

## Behavior

`reconcileInstance` runs under the `RuntimeEngine` lock:

1. Reject if the instance is missing or not in `NEEDS_PROVIDER_ACTION`.
2. Capture the current `revision`. Re-check that the instance is still `NEEDS_PROVIDER_ACTION` with the same `revision` before invoking `applyEffects`; `applyEffects` runs under the engine lock, so the revision and state cannot change during re-execution, and each `persist` writes the current revision.
3. Read `parkedReason`. Reject unless it is `MISSING_EFFECT_HANDLER` or `EFFECT_HANDLER_EXCEPTION`.
4. Verify the process definition is still registered for the instance's `processId`.
5. For `EFFECT_HANDLER_EXCEPTION`, find the `UNKNOWN` ledger entry and verify its handler is now present and not `NON_RETRYABLE`.
6. Verify all effects before the target one are still `APPLIED`.
7. Set the instance state to `OUTPUT_PENDING` and call `applyEffects`.
8. `applyEffects` skips `APPLIED` effects, re-sets the target effect to `RUNNING`, and re-executes it. It either completes or re-parks with an updated `ParkedReason` and ledger.
9. Return `ProcessReconcileResult` with the resulting `ProcessState` and a reason.

The core never refunds claims, never re-runs `APPLIED` effects, and never treats a missing definition as a success.

## Invariant checks

- `reconcileInstance` is a `synchronized` `RuntimeEngine` operation.
- Re-execution is gated by the effect handler's `IdempotencyMode`.
- `MISSING_DEFINITION`, `RETURN_CLAIM_FAILED`, `NON_RETRYABLE` exception cases, and `UNKNOWN` reasons are rejected.
- The `revision` captured at entry is re-checked before `applyEffects` is invoked and on return, guarding against stale calls even though the current engine path is synchronous.

## Test approach

- `reconcileInstance` rejects for missing or non-parked instances.
- `MISSING_EFFECT_HANDLER` reconciles after the handler is re-registered.
- `EFFECT_HANDLER_EXCEPTION` with `IDEMPOTENT`/`PROVIDER_DEDUPLICATES` reconciles; `NON_RETRYABLE` rejects.
- `RETURN_CLAIM_FAILED` rejects.
- A second handler throw re-parks and updates `ParkedReason`/`EffectExecution` without duplicating already-`APPLIED` effects.
- Restart hydration preserves `ParkedReason` and rejects reconciliation for `MISSING_DEFINITION`.

## Tradeoffs

- Very narrow v1: only effect-handler retry, but safe.
- No provider callback and no auto-trigger; providers must explicitly call `reconcileInstance`.
- Requires a SQLite schema migration.

## Follow-up

- Per-claim, idempotent `returnClaims` with persisted progress to enable `RETURN_CLAIM_FAILED` reconciliation.
- Persisted definition snapshot/version for `MISSING_DEFINITION` and `RUNNING` resume.
- Optional auto-trigger on `registerEffectHandler` once the core path is proven safe.
