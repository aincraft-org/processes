package dev.craftingmanager;

import dev.craftingmanager.api.CraftingManagerApi;
import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.Domain.CompletionEffect;
import dev.craftingmanager.api.Domain.ConsumptionPolicy;
import dev.craftingmanager.api.Domain.InputRole;
import dev.craftingmanager.api.Domain.InputTiming;
import dev.craftingmanager.api.Domain.ProcessDefinition;
import dev.craftingmanager.api.Domain.ProcessInput;
import dev.craftingmanager.api.Domain.ProcessState;
import dev.craftingmanager.api.EffectHandler;
import dev.craftingmanager.api.InventoryAdapter;
import dev.craftingmanager.api.ItemSnapshot;
import dev.craftingmanager.api.Reservation;
import dev.craftingmanager.persistence.SqliteProcessStore;
import dev.craftingmanager.runtime.RuntimeEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ProcessParkedInvalidationTest {
    private static final CompletionEffect FIRST = () -> "first";
    private static final CompletionEffect SECOND = () -> "second";

    @Test void invalidateBlockOnParkedInstanceDoesNotReturnInputsOrReRunEffects() {
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        TrackingInventory inventory = new TrackingInventory();
        RuntimeEngine engine = new RuntimeEngine();
        engine.registerEffectHandler(handler("first", firstCalls, false));
        engine.registerEffectHandler(handler("second", secondCalls, true));
        engine.registerInventoryAdapter(inventory);
        engine.registerProcess(new ProcessDefinition(
                "job",
                List.of(new ProcessInput("fuel", InputRole.FUEL, "COAL", 1,
                        ConsumptionPolicy.RETURN_ALWAYS, InputTiming.ON_START, false, null)),
                List.of(),
                List.of(FIRST, SECOND)));

        BlockKey block = new BlockKey(UUID.randomUUID(), 0, 64, 0);
        UUID owner = UUID.randomUUID();
        CraftingManagerApi.ProcessStartResult started = engine.start(block, "job", owner);
        assertTrue(started.started(), started.reason());
        assertEquals(ProcessState.NEEDS_PROVIDER_ACTION,
                engine.advance(started.instanceId()).toCompletableFuture().join());
        assertEquals(1, firstCalls.get());
        assertEquals(1, secondCalls.get());
        assertEquals(1, inventory.removed);
        assertEquals(0, inventory.returned);

        engine.invalidateBlock(block);

        assertTrue(engine.state(started.instanceId()).isEmpty());
        CraftingManagerApi.ProcessStartResult again = engine.start(block, "job", owner);
        assertTrue(again.started(), again.reason());
        assertEquals(2, inventory.removed);
        assertEquals(0, inventory.returned);
        assertEquals(1, firstCalls.get());
        assertEquals(1, secondCalls.get());
    }

    @Test void invalidateBlockOnRunningInstanceStillReturnsInputs() {
        TrackingInventory inventory = new TrackingInventory();
        RuntimeEngine engine = new RuntimeEngine();
        engine.registerEffectHandler(new Handler());
        engine.registerInventoryAdapter(inventory);
        engine.registerProcess(new ProcessDefinition(
                "smelt",
                List.of(new ProcessInput("fuel", InputRole.FUEL, "COAL", 1,
                        ConsumptionPolicy.RETURN_ALWAYS, InputTiming.ON_START, false, null)),
                List.of(),
                List.of((CompletionEffect) () -> "output")));

        BlockKey block = new BlockKey(UUID.randomUUID(), 2, 64, 3);
        CraftingManagerApi.ProcessStartResult started = engine.start(block, "smelt", UUID.randomUUID());
        assertTrue(started.started(), started.reason());
        assertEquals(1, inventory.removed);

        engine.invalidateBlock(block);

        assertEquals(1, inventory.returned);
        assertEquals(ProcessState.CANCELLED, engine.state(started.instanceId()).orElseThrow());
        assertTrue(engine.start(block, "smelt", UUID.randomUUID()).started());
    }

    @TempDir Path temp;

    @Test void invalidateBlockOnParkedInstanceRemovesDurableRow() throws Exception {
        Path db = temp.resolve("craftingmanager.db");
        BlockKey block = new BlockKey(UUID.randomUUID(), 5, 70, 5);
        UUID instanceId;
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            RuntimeEngine engine = parkedEngine(store);
            CraftingManagerApi.ProcessStartResult started = engine.start(block, "job", UUID.randomUUID());
            instanceId = started.instanceId();
            assertEquals(ProcessState.NEEDS_PROVIDER_ACTION,
                    engine.advance(instanceId).toCompletableFuture().join());
            engine.invalidateBlock(block);
        }
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            assertTrue(store.loadAll().stream().noneMatch(row -> row.instanceId().equals(instanceId)));
        }
    }

    private static RuntimeEngine parkedEngine(SqliteProcessStore store) {
        RuntimeEngine engine = new RuntimeEngine(store);
        engine.registerEffectHandler(handler("first", new AtomicInteger(), false));
        engine.registerEffectHandler(handler("second", new AtomicInteger(), true));
        engine.registerProcess(new ProcessDefinition("job", List.of(), List.of(), List.of(FIRST, SECOND)));
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
        @Override public String type() { return "output"; }
        @Override public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
        @Override public void execute(CompletionEffect effect, String effectId) {}
    }

    private static final class TrackingInventory implements InventoryAdapter {
        int removed;
        int returned;

        @Override public List<Reservation.Claim> captureClaims(List<ProcessInput> inputs) {
            ProcessInput input = inputs.getFirst();
            return List.of(new Reservation.Claim(Reservation.Source.PLAYER_INVENTORY, 0,
                    new ItemSnapshot("COAL", input.amount(), null), input.amount(), input.id(), input.consumption()));
        }

        @Override public boolean claimsStillMatch(List<Reservation.Claim> claims) { return true; }

        @Override public void remove(List<Reservation.Claim> claims) { removed++; }

        @Override public void returnItems(List<Reservation.Claim> claims) { returned++; }
    }
}
