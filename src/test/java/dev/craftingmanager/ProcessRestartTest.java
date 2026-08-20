package dev.craftingmanager;

import dev.craftingmanager.api.CraftingManagerApi;
import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.Domain.CompletionEffect;
import dev.craftingmanager.api.Domain.ProcessDefinition;
import dev.craftingmanager.api.Domain.ProcessState;
import dev.craftingmanager.api.Domain.ProcessStep;
import dev.craftingmanager.api.EffectHandler;
import dev.craftingmanager.persistence.ProcessInstanceRecord;
import dev.craftingmanager.persistence.SqliteProcessStore;
import dev.craftingmanager.runtime.RuntimeEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProcessRestartTest {
    @TempDir Path temp;

    @Test void restoresRunningInstanceAfterNewEngineOpensSameDatabase() throws Exception {
        Path db = temp.resolve("craftingmanager.db");
        BlockKey block = new BlockKey(UUID.randomUUID(), 3, 70, 9);
        UUID owner = UUID.randomUUID();
        UUID instanceId;
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            RuntimeEngine engine = new RuntimeEngine(store);
            engine.registerEffectHandler(handler());
            engine.registerProcess(definition());
            CraftingManagerApi.ProcessStartResult started = engine.start(block, "forge", owner);
            assertTrue(started.started(), started.reason());
            instanceId = started.instanceId();
            assertEquals(ProcessState.RUNNING, engine.state(instanceId).orElseThrow());
        }
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            RuntimeEngine engine = new RuntimeEngine(store);
            engine.registerEffectHandler(handler());
            engine.registerProcess(definition());
            engine.hydrate();
            assertEquals(ProcessState.RUNNING, engine.state(instanceId).orElseThrow());
            assertEquals(block, engine.instanceBlock(instanceId).orElseThrow());
        }
    }

    @Test void resumesMidStepProgressAfterRestartWithoutCatchUp() throws Exception {
        Path db = temp.resolve("craftingmanager.db");
        BlockKey block = new BlockKey(UUID.randomUUID(), 4, 70, 4);
        UUID instanceId;
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            RuntimeEngine engine = engine(store, new ProcessStep("heat", "Heat", 3));
            instanceId = start(engine, block);
            assertEquals(ProcessState.RUNNING, engine.advance(instanceId).toCompletableFuture().join());
            engine.shutdown();
        }
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            RuntimeEngine engine = engine(store, new ProcessStep("heat", "Heat", 3));
            engine.hydrate();
            assertEquals(ProcessState.RUNNING, engine.state(instanceId).orElseThrow());
            assertEquals(ProcessState.RUNNING, engine.advance(instanceId).toCompletableFuture().join());
            load(engine, block);
            assertEquals(ProcessState.RUNNING, engine.advance(instanceId).toCompletableFuture().join());
            assertEquals(ProcessState.COMPLETED, engine.advance(instanceId).toCompletableFuture().join());
        }
    }

    @Test void hydratedInstanceDoesNotTickUntilItsChunkLoads() throws Exception {
        Path db = temp.resolve("craftingmanager.db");
        BlockKey block = new BlockKey(UUID.randomUUID(), 48, 70, 16);
        UUID instanceId;
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            RuntimeEngine engine = engine(store, new ProcessStep("heat", "Heat", 2));
            instanceId = start(engine, block);
            assertEquals(ProcessState.RUNNING, engine.advance(instanceId).toCompletableFuture().join());
            engine.shutdown();
        }
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            RuntimeEngine engine = engine(store, new ProcessStep("heat", "Heat", 2));
            engine.hydrate();
            assertEquals(ProcessState.RUNNING, engine.advance(instanceId).toCompletableFuture().join());
            assertEquals(ProcessState.RUNNING, engine.advance(instanceId).toCompletableFuture().join());
            assertEquals(ProcessState.RUNNING, engine.state(instanceId).orElseThrow());
            load(engine, block);
            assertEquals(ProcessState.COMPLETED, engine.advance(instanceId).toCompletableFuture().join());
        }
    }

    @Test void unloadThenLoadResumesRemainingTicksWithoutCatchUp() throws Exception {
        Path db = temp.resolve("craftingmanager.db");
        BlockKey block = new BlockKey(UUID.randomUUID(), 64, 70, 64);
        UUID instanceId;
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            RuntimeEngine engine = engine(store, new ProcessStep("heat", "Heat", 3));
            instanceId = start(engine, block);
            assertEquals(ProcessState.RUNNING, engine.advance(instanceId).toCompletableFuture().join());
            engine.unloadChunk(block.worldId(), Math.floorDiv(block.x(), 16), Math.floorDiv(block.z(), 16));
            assertEquals(ProcessState.RUNNING, engine.advance(instanceId).toCompletableFuture().join());
        }
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            RuntimeEngine engine = engine(store, new ProcessStep("heat", "Heat", 3));
            engine.hydrate();
            engine.tick();
            assertEquals(ProcessState.RUNNING, engine.state(instanceId).orElseThrow());
            load(engine, block);
            assertEquals(ProcessState.RUNNING, engine.advance(instanceId).toCompletableFuture().join());
            assertEquals(ProcessState.COMPLETED, engine.advance(instanceId).toCompletableFuture().join());
        }
    }

    @Test void persistChunkFlushesMidStepProgress() throws Exception {
        Path db = temp.resolve("craftingmanager.db");
        BlockKey block = new BlockKey(UUID.randomUUID(), 16, 70, 32);
        UUID instanceId;
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            RuntimeEngine engine = engine(store, new ProcessStep("heat", "Heat", 3));
            instanceId = start(engine, block);
            assertEquals(ProcessState.RUNNING, engine.advance(instanceId).toCompletableFuture().join());
            engine.persistChunk(block.worldId(), Math.floorDiv(block.x(), 16), Math.floorDiv(block.z(), 16));
        }
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            RuntimeEngine engine = engine(store, new ProcessStep("heat", "Heat", 3));
            engine.hydrate();
            load(engine, block);
            assertEquals(ProcessState.RUNNING, engine.advance(instanceId).toCompletableFuture().join());
            assertEquals(ProcessState.COMPLETED, engine.advance(instanceId).toCompletableFuture().join());
        }
    }

    @Test void persistsStepTicksEveryTwentyProgressTicks() throws Exception {
        Path db = temp.resolve("craftingmanager.db");
        BlockKey block = new BlockKey(UUID.randomUUID(), 8, 70, 8);
        UUID instanceId;
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            RuntimeEngine engine = engine(store, new ProcessStep("heat", "Heat", 40));
            instanceId = start(engine, block);
            for (int i = 0; i < 19; i++) {
                assertEquals(ProcessState.RUNNING, engine.advance(instanceId).toCompletableFuture().join());
            }
            assertEquals(0, store.loadAll().getFirst().stepTicks());
            assertEquals(ProcessState.RUNNING, engine.advance(instanceId).toCompletableFuture().join());
            ProcessInstanceRecord saved = store.loadAll().getFirst();
            assertEquals(instanceId, saved.instanceId());
            assertEquals(20, saved.stepTicks());
        }
    }

    @Test void parksInstanceWhenDefinitionMissingAfterRestart() throws Exception {
        Path db = temp.resolve("craftingmanager.db");
        BlockKey block = new BlockKey(UUID.randomUUID(), 0, 1, 0);
        UUID instanceId;
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            RuntimeEngine engine = new RuntimeEngine(store);
            engine.registerEffectHandler(handler());
            engine.registerProcess(definition());
            instanceId = engine.start(block, "forge", UUID.randomUUID()).instanceId();
        }
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            RuntimeEngine engine = new RuntimeEngine(store);
            engine.hydrate();
            assertEquals(ProcessState.NEEDS_PROVIDER_ACTION, engine.state(instanceId).orElseThrow());
        }
    }

    private static RuntimeEngine engine(SqliteProcessStore store, ProcessStep... steps) {
        RuntimeEngine engine = new RuntimeEngine(store);
        engine.registerEffectHandler(handler());
        engine.registerProcess(new ProcessDefinition("forge", List.of(), List.of(steps),
                List.of((CompletionEffect) () -> "item-output")));
        return engine;
    }

    private static UUID start(RuntimeEngine engine, BlockKey block) {
        CraftingManagerApi.ProcessStartResult started = engine.start(block, "forge", UUID.randomUUID());
        assertTrue(started.started(), started.reason());
        return started.instanceId();
    }

    private static void load(RuntimeEngine engine, BlockKey block) {
        engine.loadChunk(block.worldId(), Math.floorDiv(block.x(), 16), Math.floorDiv(block.z(), 16));
    }

    private static ProcessDefinition definition() {
        return new ProcessDefinition("forge", List.of(), List.of(), List.of((CompletionEffect) () -> "item-output"));
    }

    private static EffectHandler<CompletionEffect> handler() {
        return new EffectHandler<>() {
            public String type() { return "item-output"; }
            public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
            public void execute(CompletionEffect effect, String effectId) {}
        };
    }
}
