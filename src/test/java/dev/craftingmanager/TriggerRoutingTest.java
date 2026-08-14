package dev.craftingmanager;

import dev.craftingmanager.api.CraftingManagerApi;
import dev.craftingmanager.api.Domain.*;
import dev.craftingmanager.runtime.RuntimeEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TriggerRoutingTest {
    private static final CompletionEffect EFFECT = () -> "test";
    private static ProcessDefinition process() { return new ProcessDefinition("forge", List.of(), List.of(), List.of(EFFECT)); }

    @Test void rejectsWhenNoProviderSelectsProcess() {
        RuntimeEngine engine = new RuntimeEngine();
        assertFalse(engine.trigger(new BlockKey(UUID.randomUUID(), 1, 2, 3), UUID.randomUUID()).started());
    }

    @Test void startsProviderSelectedProcess() {
        RuntimeEngine engine = new RuntimeEngine();
        engine.registerEffectHandler(new dev.craftingmanager.api.EffectHandler<CompletionEffect>() {
            public String type() { return "test"; }
            public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
            public void execute(CompletionEffect effect, String effectId) { }
        });
        engine.registerProcess(process());
        engine.registerProcessTrigger((block, player) -> Optional.of("forge"));
        CraftingManagerApi.ProcessStartResult result = engine.trigger(new BlockKey(UUID.randomUUID(), 1, 2, 3), UUID.randomUUID());
        assertTrue(result.started());
        assertNotNull(result.instanceId());
    }
}
