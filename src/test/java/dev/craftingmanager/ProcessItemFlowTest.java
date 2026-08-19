package dev.craftingmanager;

import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.Domain.ConsumptionPolicy;
import dev.craftingmanager.api.Domain.InputRole;
import dev.craftingmanager.api.Domain.InputTiming;
import dev.craftingmanager.api.Domain.ItemOutput;
import dev.craftingmanager.api.Domain.ProcessDefinition;
import dev.craftingmanager.api.Domain.ProcessInput;
import dev.craftingmanager.api.Domain.ProcessState;
import dev.craftingmanager.api.Domain.ProcessStep;
import dev.craftingmanager.api.ItemSnapshot;
import dev.craftingmanager.runtime.ItemOutputHandler;
import dev.craftingmanager.runtime.MapItemVault;
import dev.craftingmanager.runtime.RuntimeEngine;
import dev.craftingmanager.runtime.SlotInventoryAdapter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProcessItemFlowTest {
    @Test void startRejectsWhenRequiredInputsAreMissing() {
        MapItemVault vault = new MapItemVault();
        UUID owner = UUID.randomUUID();
        vault.open(owner, 9);
        RuntimeEngine engine = engine(vault);
        var result = engine.start(new BlockKey(UUID.randomUUID(), 0, 64, 0), "job", owner);
        assertFalse(result.started());
        assertEquals("missing inputs", result.reason());
    }

    @Test void completingAProcessConsumesInputsAndGrantsItemOutput() {
        MapItemVault vault = new MapItemVault();
        UUID owner = UUID.randomUUID();
        vault.open(owner, 9).set(0, new ItemSnapshot("IRON_INGOT", 1, null));
        vault.open(owner, 9).set(1, new ItemSnapshot("COAL", 1, null));
        RuntimeEngine engine = engine(vault);
        var started = engine.start(new BlockKey(UUID.randomUUID(), 1, 64, 1), "job", owner);
        assertTrue(started.started(), started.reason());
        assertNull(vault.open(owner, 9).get(0));
        assertNull(vault.open(owner, 9).get(1));
        assertEquals(ProcessState.COMPLETED, engine.advance(started.instanceId()).toCompletableFuture().join());
        assertEquals(new ItemSnapshot("IRON_NUGGET", 1, null), vault.open(owner, 9).get(0));
    }

    private static RuntimeEngine engine(MapItemVault vault) {
        RuntimeEngine engine = new RuntimeEngine();
        engine.registerInventoryAdapter(new SlotInventoryAdapter(vault));
        engine.registerEffectHandler(new ItemOutputHandler(vault));
        engine.registerProcess(new ProcessDefinition(
                "job",
                List.of(
                        new ProcessInput("iron", InputRole.PRIMARY_MATERIAL, "IRON_INGOT", 1,
                                ConsumptionPolicy.CONSUME, InputTiming.ON_START, false, null),
                        new ProcessInput("fuel", InputRole.FUEL, "COAL", 1,
                                ConsumptionPolicy.CONSUME, InputTiming.ON_START, false, null)),
                List.of(new ProcessStep("work", "Work", 1)),
                List.of(new ItemOutput(new ItemSnapshot("IRON_NUGGET", 1, null)))));
        return engine;
    }
}
