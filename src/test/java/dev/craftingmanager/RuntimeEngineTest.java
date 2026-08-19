package dev.craftingmanager;

import dev.craftingmanager.api.CraftingManagerApi;
import dev.craftingmanager.api.EffectHandler;
import dev.craftingmanager.api.Domain.*;
import dev.craftingmanager.runtime.RuntimeEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeEngineTest {
    private static final CompletionEffect OUTPUT = () -> "item-output";
    @Test void rejectsDuplicateBlockProcesses() {
        RuntimeEngine engine = new RuntimeEngine();
        ProcessDefinition definition = definition("forge");
        engine.registerEffectHandler(new EffectHandler<CompletionEffect>() {
            public String type() { return "item-output"; }
            public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
            public void execute(CompletionEffect effect, String effectId) {}
        });
        engine.registerProcess(definition);
        BlockKey block = new BlockKey(UUID.randomUUID(), 1, -2, 3);
        assertTrue(engine.start(block, "forge", UUID.randomUUID()).started());
        assertFalse(engine.start(block, "forge", UUID.randomUUID()).started());
    }

    @Test void progressesStepsThenCompletesEffects() {
        RuntimeEngine engine = new RuntimeEngine();
        engine.registerEffectHandler(new EffectHandler<CompletionEffect>() {
            public String type() { return "item-output"; }
            public Class<CompletionEffect> effectType() { return CompletionEffect.class; }
            public void execute(CompletionEffect effect, String effectId) {}
        });
        engine.registerProcess(definition("forge"));
        CraftingManagerApi.ProcessStartResult result = engine.start(new BlockKey(UUID.randomUUID(), 0, 0, 0), "forge", UUID.randomUUID());
        assertTrue(result.started());
        assertEquals(ProcessState.RUNNING, engine.state(result.instanceId()).orElseThrow());
        assertEquals(ProcessState.COMPLETED, engine.advance(result.instanceId()).toCompletableFuture().join());
        assertEquals(ProcessState.COMPLETED, engine.state(result.instanceId()).orElseThrow());
    }

    @Test void validatesPositiveInputs() {
        assertThrows(IllegalArgumentException.class, () -> new ProcessInput("x", InputRole.FUEL, "COAL", 0, ConsumptionPolicy.CONSUME, InputTiming.ON_START, false, null));
    }

    private static ProcessDefinition definition(String id) {
        return new ProcessDefinition(id, List.of(), List.of(new ProcessStep("one", "One", 1)), List.of(OUTPUT));
    }
}
