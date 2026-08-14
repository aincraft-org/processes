package dev.craftingmanager.api;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import static dev.craftingmanager.api.Domain.*;

public interface CraftingManagerApi {
    RegistrationHandle registerProcess(ProcessDefinition definition);
    RegistrationHandle registerFunctionalBlock(FunctionalBlockDefinition definition);
    RegistrationHandle registerProcessTrigger(ProcessTrigger trigger);
    RegistrationHandle registerInventoryAdapter(InventoryAdapter adapter);
    default <E extends CompletionEffect> RegistrationHandle registerEffectHandler(EffectHandler<E> handler) {
        return registerEffectHandler(handler, UnregisterPolicy.REJECT_WHILE_IN_USE);
    }
    <E extends CompletionEffect> RegistrationHandle registerEffectHandler(EffectHandler<E> handler, UnregisterPolicy policy);
    Optional<ProcessDefinition> process(String id);
    ProcessStartResult trigger(BlockKey block, UUID owner);
    ProcessStartResult start(BlockKey block, String processId, UUID owner);
    CompletionStage<ProcessState> advance(UUID instanceId);

    record ProcessStartResult(boolean started, UUID instanceId, String reason) {
        public static ProcessStartResult rejected(String reason) { return new ProcessStartResult(false, null, reason); }
    }
}
