package dev.craftingmanager;

import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.Domain.ConsumptionPolicy;
import dev.craftingmanager.api.Domain.InputRole;
import dev.craftingmanager.api.Domain.InputTiming;
import dev.craftingmanager.api.Domain.ItemOutput;
import dev.craftingmanager.api.Domain.ProcessDefinition;
import dev.craftingmanager.api.Domain.ProcessFace;
import dev.craftingmanager.api.Domain.ProcessInput;
import dev.craftingmanager.api.Domain.ProcessState;
import dev.craftingmanager.api.EffectHandler;
import dev.craftingmanager.api.ItemSnapshot;
import dev.craftingmanager.runtime.HopperGeometry;
import dev.craftingmanager.runtime.ItemOutputHandler;
import dev.craftingmanager.runtime.MapItemVault;
import dev.craftingmanager.runtime.RuntimeEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProcessFaceIoTest {
    @Test void hopperInsertUsesDeclaredInputFaces() {
        RuntimeEngine engine = machine();
        BlockKey block = place(engine);
        ItemSnapshot iron = new ItemSnapshot("IRON_INGOT", 1, null);
        ItemSnapshot coal = new ItemSnapshot("COAL", 1, null);
        assertTrue(engine.insertAt(block, ProcessFace.UP, iron));
        assertFalse(engine.insertAt(block, ProcessFace.UP, coal));
        assertTrue(engine.insertAt(block, ProcessFace.NORTH, coal));
        assertFalse(engine.insertAt(block, ProcessFace.DOWN, iron));
    }

    @Test void hopperExtractUsesDeclaredOutputFacesAfterCompletion() {
        RuntimeEngine engine = machine();
        BlockKey block = place(engine);
        UUID owner = UUID.randomUUID();
        MapItemVault players = new MapItemVault();
        players.open(owner, 9).set(0, new ItemSnapshot("IRON_INGOT", 1, null));
        players.open(owner, 9).set(1, new ItemSnapshot("COAL", 1, null));
        engine.registerInventoryAdapter(new dev.craftingmanager.runtime.SlotInventoryAdapter(players));
        var started = engine.start(block, "job", owner);
        assertTrue(started.started(), started.reason());
        assertEquals(ProcessState.COMPLETED, engine.advance(started.instanceId()).toCompletableFuture().join());
        assertTrue(engine.extractAt(block, ProcessFace.UP, 1).isEmpty());
        assertEquals(new ItemSnapshot("IRON_NUGGET", 1, null), engine.extractAt(block, ProcessFace.DOWN, 1).orElseThrow());
        assertTrue(engine.extractAt(block, ProcessFace.DOWN, 1).isEmpty());
    }

    @Test void hopperInsertionFaceIsOppositeOfHopperFacing() {
        assertEquals(ProcessFace.UP, HopperGeometry.insertionFace(ProcessFace.DOWN));
        assertEquals(ProcessFace.WEST, HopperGeometry.insertionFace(ProcessFace.EAST));
        assertEquals(ProcessFace.DOWN, HopperGeometry.extractionFace());
    }

    private static RuntimeEngine machine() {
        RuntimeEngine engine = new RuntimeEngine();
        engine.registerEffectHandler(new ItemOutputHandler(new MapItemVault(), engine));
        engine.registerProcess(new ProcessDefinition(
                "job",
                List.of(
                        new ProcessInput("iron", InputRole.PRIMARY_MATERIAL, "IRON_INGOT", 1,
                                ConsumptionPolicy.CONSUME, InputTiming.ON_START, false, null, Set.of(ProcessFace.UP)),
                        new ProcessInput("fuel", InputRole.FUEL, "COAL", 1,
                                ConsumptionPolicy.CONSUME, InputTiming.ON_START, false, null,
                                Set.of(ProcessFace.NORTH, ProcessFace.SOUTH, ProcessFace.EAST, ProcessFace.WEST))),
                List.of(),
                List.of(new ItemOutput("alloy", new ItemSnapshot("IRON_NUGGET", 1, null), Set.of(ProcessFace.DOWN)))));
        engine.registerFunctionalBlock(new dev.craftingmanager.api.Domain.FunctionalBlockDefinition(
                "station", "BLAST_FURNACE", List.of("job")));
        return engine;
    }

    private static BlockKey place(RuntimeEngine engine) {
        BlockKey block = new BlockKey(UUID.randomUUID(), 4, 64, 4);
        engine.placeFunctionalBlock(block, "station");
        return block;
    }
}
