package dev.craftingmanager;

import dev.craftingmanager.api.Domain.*;
import dev.craftingmanager.api.InventoryAdapter;
import dev.craftingmanager.api.ItemSnapshot;
import dev.craftingmanager.api.Reservation;
import dev.craftingmanager.runtime.RuntimeEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeReservationTest {
    private static final CompletionEffect OUTPUT = () -> "output";

    @Test void removesClaimsAtStartAndReturnsAlwaysClaimsOnInvalidation() {
        RuntimeEngine engine = new RuntimeEngine();
        engine.registerEffectHandler(new Handler());
        Inventory inventory = new Inventory();
        engine.registerInventoryAdapter(inventory);
        ProcessInput input = new ProcessInput("fuel", InputRole.FUEL, "COAL", 1,
                ConsumptionPolicy.RETURN_ALWAYS, InputTiming.ON_START, false, null);
        engine.registerProcess(new ProcessDefinition("smelt", List.of(input), List.of(), List.of(OUTPUT)));

        BlockKey block = new BlockKey(UUID.randomUUID(), 2, 64, 3);
        var result = engine.start(block, "smelt", UUID.randomUUID());
        assertTrue(result.started());
        assertEquals(1, inventory.removed);
        engine.invalidateBlock(block);
        assertEquals(1, inventory.returned);
        assertEquals(ProcessState.CANCELLED, engine.state(result.instanceId()).orElseThrow());
    }

    @Test void rejectsClaimsThatChangedBeforeRemoval() {
        RuntimeEngine engine = new RuntimeEngine();
        engine.registerEffectHandler(new Handler());
        Inventory inventory = new Inventory();
        inventory.matches = false;
        engine.registerInventoryAdapter(inventory);
        ProcessInput input = new ProcessInput("fuel", InputRole.FUEL, "COAL", 1,
                ConsumptionPolicy.CONSUME, InputTiming.ON_START, false, null);
        engine.registerProcess(new ProcessDefinition("smelt", List.of(input), List.of(), List.of(OUTPUT)));

        assertFalse(engine.start(new BlockKey(UUID.randomUUID(), 0, 0, 0), "smelt", UUID.randomUUID()).started());
        assertEquals(0, inventory.removed);
    }

    private static final class Handler implements dev.craftingmanager.api.EffectHandler<CompletionEffect> {
        public String type() { return "output"; }
        public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
        public void execute(CompletionEffect effect, String effectId) {}
    }

    private static final class Inventory implements InventoryAdapter {
        boolean matches = true;
        int removed;
        int returned;

        public List<Reservation.Claim> captureClaims(List<ProcessInput> inputs) {
            ProcessInput input = inputs.getFirst();
            return List.of(new Reservation.Claim(Reservation.Source.PLAYER_INVENTORY, 4,
                    new ItemSnapshot("COAL", input.amount(), null), input.amount(), input.id(), input.consumption()));
        }
        public boolean claimsStillMatch(List<Reservation.Claim> claims) { return matches; }
        public void remove(List<Reservation.Claim> claims) { removed++; }
        public void returnItems(List<Reservation.Claim> claims) { returned++; }
    }
}
