package dev.craftingmanager;

import dev.craftingmanager.api.CraftingManagerApi;
import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.Domain.ConsumptionPolicy;
import dev.craftingmanager.api.Domain.FunctionalBlockDefinition;
import dev.craftingmanager.api.Domain.InputRole;
import dev.craftingmanager.api.Domain.InputTiming;
import dev.craftingmanager.api.Domain.ItemOutput;
import dev.craftingmanager.api.Domain.ProcessDefinition;
import dev.craftingmanager.api.Domain.ProcessFace;
import dev.craftingmanager.api.Domain.ProcessInput;
import dev.craftingmanager.api.Domain.ProcessState;
import dev.craftingmanager.api.Domain.ProcessStep;
import dev.craftingmanager.api.EffectHandler;
import dev.craftingmanager.api.ItemSnapshot;
import dev.craftingmanager.persistence.SqliteProcessStore;
import dev.craftingmanager.runtime.ItemOutputHandler;
import dev.craftingmanager.runtime.MapItemVault;
import dev.craftingmanager.runtime.RuntimeEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProcessStationInventoryTest {
    @TempDir Path temp;

    @Test void hopperBuffersSurviveRestartAsItemSnapshots() throws Exception {
        Path db = temp.resolve("craftingmanager.db");
        BlockKey block = new BlockKey(UUID.randomUUID(), 8, 64, 8);
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            RuntimeEngine engine = machine(store);
            engine.placeFunctionalBlock(block, "station");
            assertTrue(engine.insertAt(block, ProcessFace.UP, new ItemSnapshot("IRON_INGOT", 2, null)));
            assertTrue(engine.insertAt(block, ProcessFace.NORTH, new ItemSnapshot("COAL", 1, null)));
            engine.shutdown();
        }
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            RuntimeEngine engine = machine(store);
            engine.hydrate();
            assertEquals(new ItemSnapshot("IRON_INGOT", 2, null), engine.slot(block, "iron").orElseThrow());
            assertEquals(new ItemSnapshot("COAL", 1, null), engine.slot(block, "fuel").orElseThrow());
        }
    }

    @Test void startConsumesStationSlotsWithoutPlayerInventory() throws Exception {
        Path db = temp.resolve("craftingmanager.db");
        BlockKey block = new BlockKey(UUID.randomUUID(), 4, 64, 4);
        UUID owner = UUID.randomUUID();
        UUID instanceId;
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            RuntimeEngine engine = machine(store);
            engine.placeFunctionalBlock(block, "station");
            assertTrue(engine.insertAt(block, ProcessFace.UP, new ItemSnapshot("IRON_INGOT", 1, null)));
            assertTrue(engine.insertAt(block, ProcessFace.NORTH, new ItemSnapshot("COAL", 1, null)));
            CraftingManagerApi.ProcessStartResult started = engine.start(block, "job", owner);
            assertTrue(started.started(), started.reason());
            instanceId = started.instanceId();
            assertTrue(engine.slot(block, "iron").isEmpty());
            assertTrue(engine.slot(block, "fuel").isEmpty());
            assertEquals(ProcessState.COMPLETED, engine.advance(instanceId).toCompletableFuture().join());
            assertEquals(new ItemSnapshot("IRON_NUGGET", 1, null), engine.slot(block, "alloy").orElseThrow());
        }
    }

    @Test void leftoverStationAmountStaysAfterStart() {
        RuntimeEngine engine = machine(null);
        BlockKey block = new BlockKey(UUID.randomUUID(), 0, 64, 0);
        engine.placeFunctionalBlock(block, "station");
        assertTrue(engine.insertAt(block, ProcessFace.UP, new ItemSnapshot("IRON_INGOT", 3, null)));
        assertTrue(engine.insertAt(block, ProcessFace.EAST, new ItemSnapshot("COAL", 1, null)));
        assertTrue(engine.start(block, "job", UUID.randomUUID()).started());
        assertEquals(new ItemSnapshot("IRON_INGOT", 2, null), engine.slot(block, "iron").orElseThrow());
        assertTrue(engine.slot(block, "fuel").isEmpty());
    }

    @Test void returnedToolGoesBackToTheStationSlot() {
        RuntimeEngine engine = polisher();
        BlockKey block = new BlockKey(UUID.randomUUID(), 16, 64, 16);
        engine.placeFunctionalBlock(block, "station");
        assertTrue(engine.insertAt(block, ProcessFace.NORTH, new ItemSnapshot("AMETHYST_SHARD", 1, null)));
        assertTrue(engine.insertAt(block, ProcessFace.UP, new ItemSnapshot("IRON_PICKAXE", 1, null)));
        CraftingManagerApi.ProcessStartResult started = engine.start(block, "polish", UUID.randomUUID());
        assertTrue(started.started(), started.reason());
        assertTrue(engine.slot(block, "tool").isEmpty());
        assertEquals(ProcessState.COMPLETED, engine.advance(started.instanceId()).toCompletableFuture().join());
        assertEquals(new ItemSnapshot("IRON_PICKAXE", 1, null), engine.slot(block, "tool").orElseThrow());
        assertEquals(new ItemSnapshot("QUARTZ", 1, null), engine.slot(block, "gem").orElseThrow());
    }

    @Test void invalidateClearsPersistedStationSlots() throws Exception {
        Path db = temp.resolve("invalidate.db");
        BlockKey block = new BlockKey(UUID.randomUUID(), 1, 70, 1);
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            RuntimeEngine engine = machine(store);
            engine.placeFunctionalBlock(block, "station");
            assertTrue(engine.insertAt(block, ProcessFace.UP, new ItemSnapshot("IRON_INGOT", 1, null)));
            engine.invalidateBlock(block);
            engine.shutdown();
        }
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            RuntimeEngine engine = machine(store);
            engine.hydrate();
            assertTrue(engine.slot(block, "iron").isEmpty());
        }
    }

    private static RuntimeEngine machine(SqliteProcessStore store) {
        RuntimeEngine engine = store == null ? new RuntimeEngine() : new RuntimeEngine(store);
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
        engine.registerFunctionalBlock(new FunctionalBlockDefinition("station", "BLAST_FURNACE", List.of("job")));
        return engine;
    }

    private static RuntimeEngine polisher() {
        RuntimeEngine engine = new RuntimeEngine();
        engine.registerEffectHandler(new ItemOutputHandler(new MapItemVault(), engine));
        engine.registerProcess(new ProcessDefinition(
                "polish",
                List.of(
                        new ProcessInput("rough", InputRole.PRIMARY_MATERIAL, "AMETHYST_SHARD", 1,
                                ConsumptionPolicy.CONSUME, InputTiming.ON_START, false, null, Set.of(ProcessFace.NORTH)),
                        new ProcessInput("tool", InputRole.TOOL, "IRON_PICKAXE", 1,
                                ConsumptionPolicy.RETURN_ON_SUCCESS, InputTiming.ON_START, false, null,
                                Set.of(ProcessFace.UP))),
                List.of(),
                List.of(new ItemOutput("gem", new ItemSnapshot("QUARTZ", 1, null), Set.of(ProcessFace.WEST)))));
        engine.registerFunctionalBlock(new FunctionalBlockDefinition("station", "GRINDSTONE", List.of("polish")));
        return engine;
    }
}
