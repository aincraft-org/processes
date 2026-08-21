package dev.craftingmanager;

import dev.craftingmanager.api.CraftingManagerApi;
import dev.craftingmanager.api.Domain;
import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.Domain.CompletionEffect;
import dev.craftingmanager.api.Domain.ProcessDefinition;
import dev.craftingmanager.api.Domain.ProcessState;
import dev.craftingmanager.api.EffectHandler;
import dev.craftingmanager.api.InventoryAdapter;
import dev.craftingmanager.api.ItemSnapshot;
import dev.craftingmanager.api.Reservation;
import dev.craftingmanager.runtime.RuntimeEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ProcessApiReconcileTest {
    @Test void reconcileInstanceRejectsUnknownInstance() {
        RuntimeEngine engine = new RuntimeEngine();
        var result = engine.reconcileInstance(UUID.randomUUID());
        assertFalse(result.reconciled());
        assertNull(result.state());
        assertEquals("unknown or terminal instance", result.reason());
    }

    @Test void missingEffectHandlerReconcilesWhenHandlerRegistered() {
        RuntimeEngine engine = new RuntimeEngine();
        var second = engine.registerEffectHandler(handler("second", false), Domain.UnregisterPolicy.FAIL_ACTIVE_PROCESSES);
        engine.registerEffectHandler(handler("first", false));
        engine.registerProcess(new ProcessDefinition("job", List.of(), List.of(),
                List.of((CompletionEffect) () -> "first", (CompletionEffect) () -> "second")));
        BlockKey block = new BlockKey(UUID.randomUUID(), 0, 64, 0);
        var started = engine.start(block, "job", UUID.randomUUID());
        assertTrue(started.started());

        second.close();
        assertEquals(ProcessState.NEEDS_PROVIDER_ACTION, engine.state(started.instanceId()).orElseThrow());

        engine.registerEffectHandler(handler("second", false));
        var result = engine.reconcileInstance(started.instanceId());

        assertTrue(result.reconciled());
        assertEquals(ProcessState.COMPLETED, result.state());
    }

    @Test void effectHandlerExceptionReconcilesForIdempotentHandler() {
        RuntimeEngine engine = new RuntimeEngine();
        AtomicInteger failFirst = new AtomicInteger(1);
        var first = engine.registerEffectHandler(new EffectHandler<CompletionEffect>() {
            public String type() { return "first"; }
            public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
            public void execute(CompletionEffect effect, String effectId) {
                if (failFirst.getAndDecrement() > 0) throw new IllegalStateException("provider failure");
            }
        }, Domain.UnregisterPolicy.FAIL_ACTIVE_PROCESSES);
        engine.registerEffectHandler(handler("second", false));
        engine.registerProcess(new ProcessDefinition("job", List.of(), List.of(),
                List.of((CompletionEffect) () -> "first", (CompletionEffect) () -> "second")));
        BlockKey block = new BlockKey(UUID.randomUUID(), 0, 64, 0);
        var started = engine.start(block, "job", UUID.randomUUID());
        assertTrue(started.started());
        assertEquals(ProcessState.NEEDS_PROVIDER_ACTION, engine.advance(started.instanceId()).toCompletableFuture().join());

        first.close();
        engine.registerEffectHandler(handler("first", false));
        var result = engine.reconcileInstance(started.instanceId());

        assertTrue(result.reconciled());
        assertEquals(ProcessState.COMPLETED, result.state());
    }

    @Test void idempotentExceptionDoesNotRerunAppliedEffects() {
        RuntimeEngine engine = new RuntimeEngine();
        AtomicInteger firstCount = new AtomicInteger();
        AtomicInteger secondCount = new AtomicInteger();
        engine.registerEffectHandler(countingHandler("first", firstCount));
        var second = engine.registerEffectHandler(new EffectHandler<CompletionEffect>() {
            public String type() { return "second"; }
            public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
            public void execute(CompletionEffect effect, String effectId) {
                secondCount.incrementAndGet();
                throw new IllegalStateException("provider failure");
            }
        }, Domain.UnregisterPolicy.FAIL_ACTIVE_PROCESSES);
        engine.registerProcess(new ProcessDefinition("job", List.of(), List.of(),
                List.of((CompletionEffect) () -> "first", (CompletionEffect) () -> "second")));
        BlockKey block = new BlockKey(UUID.randomUUID(), 0, 64, 0);
        var started = engine.start(block, "job", UUID.randomUUID());
        assertTrue(started.started());
        assertEquals(ProcessState.NEEDS_PROVIDER_ACTION, engine.advance(started.instanceId()).toCompletableFuture().join());

        assertEquals(1, firstCount.get(), "first effect should have run and be APPLIED");
        assertEquals(1, secondCount.get(), "second effect should have run once and failed");

        second.close();
        engine.registerEffectHandler(countingHandler("second", secondCount));
        var result = engine.reconcileInstance(started.instanceId());

        assertTrue(result.reconciled());
        assertEquals(1, firstCount.get(), "first effect should not re-run during reconciliation");
        assertEquals(2, secondCount.get(), "second effect should run exactly once more during reconciliation");
    }

    @Test void nonRetryableEffectExceptionIsRejected() {
        RuntimeEngine engine = new RuntimeEngine();
        engine.registerEffectHandler(new EffectHandler<CompletionEffect>() {
            public String type() { return "first"; }
            public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
            public Domain.IdempotencyMode idempotency() { return Domain.IdempotencyMode.NON_RETRYABLE; }
            public void execute(CompletionEffect effect, String effectId) { throw new IllegalStateException("boom"); }
        });
        engine.registerProcess(new ProcessDefinition("job", List.of(), List.of(),
                List.of((CompletionEffect) () -> "first")));
        BlockKey block = new BlockKey(UUID.randomUUID(), 0, 64, 0);
        var started = engine.start(block, "job", UUID.randomUUID());
        assertTrue(started.started());
        assertEquals(ProcessState.NEEDS_PROVIDER_ACTION, engine.advance(started.instanceId()).toCompletableFuture().join());

        var result = engine.reconcileInstance(started.instanceId());

        assertFalse(result.reconciled());
        assertEquals(ProcessState.NEEDS_PROVIDER_ACTION, result.state());
        assertTrue(result.reason().contains("non-retryable"));
    }

    @Test void returnClaimFailedIsNotReconciled() {
        RuntimeEngine engine = new RuntimeEngine();
        InventoryAdapter failing = new InventoryAdapter() {
            public List<Reservation.Claim> captureClaims(List<Domain.ProcessInput> inputs) {
                Domain.ProcessInput input = inputs.getFirst();
                return List.of(new Reservation.Claim(Reservation.Source.PLAYER_INVENTORY, 0,
                        new ItemSnapshot(input.matcher(), input.amount(), null), input.amount(), input.id(), input.consumption()));
            }
            public boolean claimsStillMatch(List<Reservation.Claim> claims) { return true; }
            public void remove(List<Reservation.Claim> claims) {}
            public void returnItems(List<Reservation.Claim> claims) { throw new IllegalStateException("no space"); }
        };
        engine.registerInventoryAdapter(failing);
        engine.registerEffectHandler(handler("first", false));
        engine.registerProcess(new ProcessDefinition("job",
                List.of(new Domain.ProcessInput("x", Domain.InputRole.FUEL, "COAL", 1,
                        Domain.ConsumptionPolicy.RETURN_ALWAYS, Domain.InputTiming.ON_START, false, null, Set.of())),
                List.of(), List.of((CompletionEffect) () -> "first")));
        BlockKey block = new BlockKey(UUID.randomUUID(), 0, 64, 0);
        var started = engine.start(block, "job", UUID.randomUUID());
        assertTrue(started.started());
        assertEquals(ProcessState.NEEDS_PROVIDER_ACTION, engine.advance(started.instanceId()).toCompletableFuture().join());

        var result = engine.reconcileInstance(started.instanceId());

        assertFalse(result.reconciled());
        assertEquals(ProcessState.NEEDS_PROVIDER_ACTION, result.state());
        assertTrue(result.reason().contains("RETURN_CLAIM_FAILED"));
    }

    private static EffectHandler<CompletionEffect> handler(String type, boolean fail) {
        return new EffectHandler<>() {
            public String type() { return type; }
            public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
            public void execute(CompletionEffect effect, String effectId) {
                if (fail) throw new IllegalStateException("provider failure");
            }
        };
    }

    private static EffectHandler<CompletionEffect> countingHandler(String type, AtomicInteger counter) {
        return new EffectHandler<>() {
            public String type() { return type; }
            public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
            public void execute(CompletionEffect effect, String effectId) { counter.incrementAndGet(); }
        };
    }
}
