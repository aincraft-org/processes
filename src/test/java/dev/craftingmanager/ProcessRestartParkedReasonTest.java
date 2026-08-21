package dev.craftingmanager;

import dev.craftingmanager.api.Domain.CompletionEffect;
import dev.craftingmanager.api.Domain.ProcessDefinition;
import dev.craftingmanager.api.EffectHandler;
import dev.craftingmanager.persistence.SqliteProcessStore;
import dev.craftingmanager.runtime.RuntimeEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProcessRestartParkedReasonTest {
    @Test void parkedReasonSurvivesRestart(@TempDir Path temp) throws Exception {
        Path db = temp.resolve("craftingmanager.db");
        var block = new dev.craftingmanager.api.Domain.BlockKey(UUID.randomUUID(), 0, 64, 0);
        UUID instanceId;
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            RuntimeEngine engine = new RuntimeEngine(store);
            engine.registerEffectHandler(new EffectHandler<CompletionEffect>() {
                public String type() { return "first"; }
                public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
                public void execute(CompletionEffect effect, String effectId) { throw new IllegalStateException("boom"); }
            });
            engine.registerProcess(new ProcessDefinition("job", List.of(), List.of(), List.of((CompletionEffect) () -> "first")));
            instanceId = engine.start(block, "job", UUID.randomUUID()).instanceId();
            engine.advance(instanceId).toCompletableFuture().join();
            store.flush();
            assertEquals("EFFECT_HANDLER_EXCEPTION", store.loadAll().getFirst().parkedReason());
        }
        try (SqliteProcessStore store = SqliteProcessStore.open(db)) {
            RuntimeEngine engine = new RuntimeEngine(store);
            engine.registerProcess(new ProcessDefinition("job", List.of(), List.of(), List.of((CompletionEffect) () -> "first")));
            engine.hydrate();
            assertEquals(1, store.loadAll().size());
            assertEquals("EFFECT_HANDLER_EXCEPTION", store.loadAll().getFirst().parkedReason());
        }
    }
}
