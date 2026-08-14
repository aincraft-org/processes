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

class RuntimeReservationFailureTest {
    private static final CompletionEffect OUTPUT = () -> "output";

    @Test void returnFailureMarksFailedAndReleasesBlock() {
        RuntimeEngine engine = new RuntimeEngine();
        engine.registerEffectHandler(new Handler());
        engine.registerInventoryAdapter(new FailingReturnInventory());
        ProcessInput input = new ProcessInput("fuel", InputRole.FUEL, "COAL", 1,
                ConsumptionPolicy.RETURN_ALWAYS, InputTiming.ON_START, false, null);
        engine.registerProcess(new ProcessDefinition("smelt", List.of(input), List.of(), List.of(OUTPUT)));

        BlockKey block = new BlockKey(UUID.randomUUID(), 0, 64, 0);
        var result = engine.start(block, "smelt", UUID.randomUUID());
        assertTrue(result.started());
        engine.invalidateBlock(block);
        assertEquals(ProcessState.FAILED, engine.state(result.instanceId()).orElseThrow());
        assertTrue(engine.start(block, "smelt", UUID.randomUUID()).started());
    }

    private static final class Handler implements dev.craftingmanager.api.EffectHandler<CompletionEffect> {
        public String type() { return "output"; }
        public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
        public void execute(CompletionEffect effect, String effectId) {}
    }

    private static final class FailingReturnInventory implements InventoryAdapter {
        public List<Reservation.Claim> captureClaims(List<ProcessInput> inputs) {
            ProcessInput input = inputs.getFirst();
            return List.of(new Reservation.Claim(Reservation.Source.PLAYER_INVENTORY, 0,
                    new ItemSnapshot("COAL", input.amount(), null), input.amount(), input.id(), input.consumption()));
        }
        public boolean claimsStillMatch(List<Reservation.Claim> claims) { return true; }
        public void remove(List<Reservation.Claim> claims) {}
        public void returnItems(List<Reservation.Claim> claims) { throw new IllegalStateException("return failed"); }
    }
}
