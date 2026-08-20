package dev.craftingmanager.api;

import dev.craftingmanager.api.RecipeApi.PatternDefinition;
import dev.craftingmanager.api.RecipeApi.RecipeDefinition;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import static dev.craftingmanager.api.Domain.*;

public interface CraftingManagerApi {
    RegistrationHandle registerProcess(ProcessDefinition definition);
    RegistrationHandle registerFunctionalBlock(FunctionalBlockDefinition definition);
    RegistrationHandle registerLockableBlock(String material);
    boolean isLockableBlock(String material);
    java.util.Set<String> lockableBlocks();
    RegistrationHandle registerRecipe(RecipeDefinition definition);
    RegistrationHandle registerProcessTrigger(ProcessTrigger trigger);
    RegistrationHandle registerInventoryAdapter(InventoryAdapter adapter);
    RegistrationHandle registerProcessEventSink(ProcessEventSink sink);
    default <E extends CompletionEffect> RegistrationHandle registerEffectHandler(EffectHandler<E> handler) {
        return registerEffectHandler(handler, UnregisterPolicy.REJECT_WHILE_IN_USE);
    }
    <E extends CompletionEffect> RegistrationHandle registerEffectHandler(EffectHandler<E> handler, UnregisterPolicy policy);
    Optional<ProcessDefinition> process(String id);
    Optional<RecipeDefinition> recipe(String id);
    List<RecipeDefinition> match(List<ItemSnapshot> inputs);
    List<RecipeDefinition> matchPattern(PatternDefinition pattern, List<ItemSnapshot> inputs);
    Optional<FunctionalBlockDefinition> functionalBlockDefinition(String id);
    Optional<String> placedFunctionalBlock(BlockKey block);
    void placeFunctionalBlock(BlockKey block, String definitionId);
    void invalidateBlock(BlockKey block);
    boolean insertAt(BlockKey block, ProcessFace face, ItemSnapshot item);
    Optional<ItemSnapshot> extractAt(BlockKey block, ProcessFace face, int amount);
    Optional<ItemSnapshot> slot(BlockKey block, String slotId);
    ProcessStartResult trigger(BlockKey block, UUID owner);
    ProcessStartResult start(BlockKey block, String processId, UUID owner);
    CompletionStage<ProcessState> advance(UUID instanceId);
    Optional<ProcessInstanceSnapshot> activeInstance(BlockKey block);
    Optional<ProcessInstanceSnapshot> activeInstance(UUID instanceId);
    ProcessCancelResult cancelInstance(UUID instanceId);
    ProcessDismissResult dismissInstance(UUID instanceId);


    record ProcessStartResult(boolean started, UUID instanceId, String reason) {
        public static ProcessStartResult rejected(String reason) { return new ProcessStartResult(false, null, reason); }
    }
}
