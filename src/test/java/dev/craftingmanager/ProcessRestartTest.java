package dev.craftingmanager;

import dev.craftingmanager.api.CraftingManagerApi;
import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.Domain.CompletionEffect;
import dev.craftingmanager.api.Domain.ProcessDefinition;
import dev.craftingmanager.api.Domain.ProcessState;
import dev.craftingmanager.api.EffectHandler;
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
