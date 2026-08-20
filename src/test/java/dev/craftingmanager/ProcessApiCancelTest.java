package dev.craftingmanager;

import dev.craftingmanager.api.Domain.*;
import dev.craftingmanager.api.EffectHandler;
import dev.craftingmanager.api.InventoryAdapter;
import dev.craftingmanager.api.ItemSnapshot;
import dev.craftingmanager.api.Reservation;
import dev.craftingmanager.runtime.RuntimeEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ProcessApiCancelTest {
    @Test void cancelRunningInstanceReturnsInputsAndFreesBlock() {
        TrackingInventory inventory = new TrackingInventory();
        RuntimeEngine engine = new RuntimeEngine();
        engine.registerEffectHandler(new Handler());
        engine.registerInventoryAdapter(inventory);
        engine.registerProcess(new ProcessDefinition("smelt", List.of(input()), List.of(), List.of((CompletionEffect) () -> "output")));
        BlockKey block = new BlockKey(UUID.randomUUID(), 0, 64, 0);
        var started = engine.start(block, "smelt", UUID.randomUUID());
        assertTrue(started.started());

        var result = engine.cancelInstance(started.instanceId());

        assertTrue(result.cancelled());
        assertEquals(1, inventory.returned);
        assertEquals(ProcessState.CANCELLED, engine.state(started.instanceId()).orElseThrow());
        assertTrue(engine.start(block, "smelt", UUID.randomUUID()).started());
    }

    @Test void cancelParkedInstanceIsRejected() {
        RuntimeEngine engine = parkedEngine();
        var started = engine.start(new BlockKey(UUID.randomUUID(), 0, 64, 0), "job", UUID.randomUUID());
        assertEquals(ProcessState.NEEDS_PROVIDER_ACTION, engine.advance(started.instanceId()).toCompletableFuture().join());

        var result = engine.cancelInstance(started.instanceId());

        assertFalse(result.cancelled());
        assertEquals("parked instance requires dismiss", result.reason());
    }

    @Test void dismissParkedInstanceFreesBlockWithoutReturningInputs() {
        TrackingInventory inventory = new TrackingInventory();
        RuntimeEngine engine = parkedEngine(inventory);
        BlockKey block = new BlockKey(UUID.randomUUID(), 0, 64, 0);
        var started = engine.start(block, "job", UUID.randomUUID());
        assertEquals(ProcessState.NEEDS_PROVIDER_ACTION, engine.advance(started.instanceId()).toCompletableFuture().join());

        var result = engine.dismissInstance(started.instanceId());

        assertTrue(result.dismissed());
        assertEquals(0, inventory.returned);
        assertTrue(engine.start(block, "job", UUID.randomUUID()).started());
    }

    @Test void dismissRunningInstanceIsRejected() {
        RuntimeEngine engine = new RuntimeEngine();
        engine.registerEffectHandler(new Handler());
        engine.registerProcess(new ProcessDefinition("smelt", List.of(), List.of(new ProcessStep("one", "One", 1)), List.of((CompletionEffect) () -> "output")));
        var started = engine.start(new BlockKey(UUID.randomUUID(), 0, 64, 0), "smelt", UUID.randomUUID());

        var result = engine.dismissInstance(started.instanceId());

        assertFalse(result.dismissed());
        assertEquals("only parked instances can be dismissed", result.reason());
    }

    private static ProcessInput input() {
        return new ProcessInput("fuel", InputRole.FUEL, "COAL", 1, ConsumptionPolicy.RETURN_ALWAYS, InputTiming.ON_START, false, null);
    }

    private static RuntimeEngine parkedEngine() {
        return parkedEngine(new TrackingInventory());
    }

    private static RuntimeEngine parkedEngine(TrackingInventory inventory) {
        RuntimeEngine engine = new RuntimeEngine();
        AtomicInteger calls = new AtomicInteger();
        engine.registerEffectHandler(handler("first", calls, false));
        engine.registerEffectHandler(handler("second", calls, true));
        engine.registerInventoryAdapter(inventory);
        engine.registerProcess(new ProcessDefinition("job", List.of(input()), List.of(), List.of(() -> "first", () -> "second")));
        return engine;
    }

    private static EffectHandler<CompletionEffect> handler(String type, AtomicInteger calls, boolean fail) {
        return new EffectHandler<>() {
            @Override public String type() { return type; }
            @Override public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
            @Override public void execute(CompletionEffect effect, String effectId) {
                calls.incrementAndGet();
                if (fail) throw new IllegalStateException("provider failure");
            }
        };
    }

    private static final class Handler implements EffectHandler<CompletionEffect> {
        public String type() { return "output"; }
        public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
        public void execute(CompletionEffect effect, String effectId) {}
    }

    private static final class TrackingInventory implements InventoryAdapter {
        int removed;
        int returned;

        public List<Reservation.Claim> captureClaims(List<ProcessInput> inputs) {
            ProcessInput input = inputs.getFirst();
            return List.of(new Reservation.Claim(Reservation.Source.PLAYER_INVENTORY, 0,
                    new ItemSnapshot("COAL", input.amount(), null), input.amount(), input.id(), input.consumption()));
        }

        public boolean claimsStillMatch(List<Reservation.Claim> claims) { return true; }

        public void remove(List<Reservation.Claim> claims) { removed++; }

        public void returnItems(List<Reservation.Claim> claims) { returned++; }
    }
}
