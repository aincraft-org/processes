package dev.craftingmanager.runtime;

import dev.craftingmanager.api.CraftingManagerApi;
import dev.craftingmanager.api.Domain.*;
import dev.craftingmanager.api.EffectHandler;
import dev.craftingmanager.api.InventoryAdapter;
import dev.craftingmanager.api.ProcessTrigger;
import dev.craftingmanager.api.Reservation;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RuntimeEngine implements CraftingManagerApi {
    private final Map<String, ProcessDefinition> processes = new HashMap<>();
    private final Map<String, FunctionalBlockDefinition> blocks = new HashMap<>();
    private final Map<BlockKey, String> registeredBlocks = new HashMap<>();
    private final List<ProcessTrigger> triggers = new ArrayList<>();
    private final List<InventoryAdapter> inventoryAdapters = new ArrayList<>();
    private final Map<String, HandlerRegistration<?>> handlers = new HashMap<>();
    private final Map<UUID, Instance> instances = new HashMap<>();
    private final Map<BlockKey, UUID> activeByBlock = new HashMap<>();

    @Override public synchronized RegistrationHandle registerProcess(ProcessDefinition definition) {
        Objects.requireNonNull(definition);
        if (processes.putIfAbsent(definition.id(), definition) != null) {
            throw new IllegalArgumentException("process already registered: " + definition.id());
        }
        return handle(() -> { synchronized (this) { processes.remove(definition.id(), definition); } });
    }

    @Override public synchronized RegistrationHandle registerFunctionalBlock(FunctionalBlockDefinition definition) {
        Objects.requireNonNull(definition);
        if (blocks.putIfAbsent(definition.id(), definition) != null) {
            throw new IllegalArgumentException("block already registered: " + definition.id());
        }
        return handle(() -> { synchronized (this) {
            blocks.remove(definition.id(), definition);
            registeredBlocks.values().removeIf(id -> id.equals(definition.id()));
        } });
    }

    @Override public synchronized RegistrationHandle registerProcessTrigger(ProcessTrigger trigger) {
        triggers.add(Objects.requireNonNull(trigger));
        return handle(() -> { synchronized (this) { triggers.remove(trigger); } });
    }

    @Override public synchronized RegistrationHandle registerInventoryAdapter(InventoryAdapter adapter) {
        inventoryAdapters.add(Objects.requireNonNull(adapter));
        return handle(() -> { synchronized (this) { inventoryAdapters.remove(adapter); } });
    }

    @Override public synchronized <E extends CompletionEffect> RegistrationHandle registerEffectHandler(
            EffectHandler<E> handler, UnregisterPolicy policy) {
        Objects.requireNonNull(handler);
        Objects.requireNonNull(policy);
        if (handler.type() == null || handler.type().isBlank()) throw new IllegalArgumentException("effect type is required");
        if (handler.effectType() == null) throw new IllegalArgumentException("effect class is required");
        if (handlers.putIfAbsent(handler.type(), new HandlerRegistration<>(handler, policy)) != null) {
            throw new IllegalArgumentException("effect handler already registered: " + handler.type());
        }
        return handle(() -> unregisterHandler(handler.type(), handler));
    }

    public synchronized void registerBlock(BlockKey key, String definitionId) {
        Objects.requireNonNull(key);
        if (!blocks.containsKey(definitionId)) throw new IllegalArgumentException("unknown block definition: " + definitionId);
        registeredBlocks.put(key, definitionId);
    }

    public synchronized void invalidateBlock(BlockKey key) {
        registeredBlocks.remove(key);
        UUID instanceId = activeByBlock.get(key);
        if (instanceId != null) cancel(instances.get(instanceId));
    }

    @Override public synchronized Optional<ProcessDefinition> process(String id) {
        return Optional.ofNullable(processes.get(id));
    }

    @Override public synchronized ProcessStartResult trigger(BlockKey block, UUID owner) {
        Objects.requireNonNull(block);
        Objects.requireNonNull(owner);
        for (ProcessTrigger trigger : List.copyOf(triggers)) {
            Optional<String> processId = trigger.selectProcess(block, owner);
            if (processId.isPresent()) return start(block, processId.get(), owner);
        }
        return ProcessStartResult.rejected("no provider selected a process");
    }

    @Override public synchronized ProcessStartResult start(BlockKey block, String processId, UUID owner) {
        Objects.requireNonNull(block);
        Objects.requireNonNull(processId);
        Objects.requireNonNull(owner);
        if (activeByBlock.containsKey(block)) return ProcessStartResult.rejected("block is busy");
        ProcessDefinition definition = processes.get(processId);
        if (definition == null) return ProcessStartResult.rejected("unknown process");
        for (CompletionEffect effect : definition.effects()) {
            HandlerRegistration<?> registration = handlers.get(effect.type());
            if (registration == null) return ProcessStartResult.rejected("missing effect handler: " + effect.type());
            if (!registration.handler.effectType().isInstance(effect)) {
                return ProcessStartResult.rejected("effect type mismatch: " + effect.type());
            }
        }

        InventoryAdapter adapter = null;
        List<Reservation.Claim> claims = List.of();
        if (!definition.inputs().isEmpty()) {
            for (InventoryAdapter candidate : List.copyOf(inventoryAdapters)) {
                if (candidate.supports(block, owner)) { adapter = candidate; break; }
            }
            if (adapter == null) return ProcessStartResult.rejected("inventory adapter unavailable");
            claims = List.copyOf(Objects.requireNonNull(adapter.captureClaims(definition.inputs()), "claims"));
            if (!adapter.claimsStillMatch(claims)) return ProcessStartResult.rejected("input claims changed");
        }

        UUID id = UUID.randomUUID();
        Instance instance = new Instance(id, block, definition, owner, adapter, claims);
        instances.put(id, instance);
        activeByBlock.put(block, id);
        try {
            if (adapter != null) {
                instance.state = ProcessState.CLAIM_CAPTURED;
                instance.reservationState = Reservation.State.CLAIMED;
                adapter.remove(claims);
                instance.reservationState = Reservation.State.RESERVED;
            }
            instance.state = ProcessState.RUNNING;
            return new ProcessStartResult(true, id, "started");
        } catch (RuntimeException error) {
            instance.state = ProcessState.FAILED;
            activeByBlock.remove(block, id);
            instances.remove(id);
            return ProcessStartResult.rejected("reservation failed: " + error.getMessage());
        }
    }

    @Override public synchronized CompletionStage<ProcessState> advance(UUID instanceId) {
        Instance instance = instances.get(instanceId);
        if (instance == null) return CompletableFuture.failedStage(new IllegalArgumentException("unknown instance"));
        if (instance.state != ProcessState.RUNNING) return CompletableFuture.completedFuture(instance.state);
        if (instance.step < instance.definition.steps().size()) {
            instance.step++;
            return CompletableFuture.completedFuture(instance.state);
        }
        instance.state = ProcessState.OUTPUT_PENDING;
        for (int i = 0; i < instance.definition.effects().size(); i++) {
            if (instance.ledger.get(i) == EffectExecutionState.APPLIED) continue;
            CompletionEffect effect = instance.definition.effects().get(i);
            HandlerRegistration<?> registration = handlers.get(effect.type());
            if (registration == null || !registration.handler.effectType().isInstance(effect)) {
                instance.state = ProcessState.NEEDS_PROVIDER_ACTION;
                return CompletableFuture.completedFuture(instance.state);
            }
            String effectId = effectId(instance, i);
            try {
                instance.ledger.put(i, EffectExecutionState.RUNNING);
                execute(registration.handler, effect, effectId);
                instance.ledger.put(i, EffectExecutionState.APPLIED);
            } catch (RuntimeException error) {
                instance.ledger.put(i, EffectExecutionState.UNKNOWN);
                instance.state = ProcessState.NEEDS_PROVIDER_ACTION;
                return CompletableFuture.completedFuture(instance.state);
            }
        }
        if (!returnClaims(instance, ConsumptionPolicy.RETURN_ON_SUCCESS, ConsumptionPolicy.RETURN_ALWAYS)) {
            instance.state = ProcessState.NEEDS_PROVIDER_ACTION;
            return CompletableFuture.completedFuture(instance.state);
        }
        instance.state = ProcessState.COMPLETED;
        activeByBlock.remove(instance.block, instance.id);
        if (instance.reservationState != null) instance.reservationState = Reservation.State.CONSUMED;
        return CompletableFuture.completedFuture(instance.state);
    }

    public synchronized Optional<List<EffectExecution>> ledger(UUID id) {
        Instance instance = instances.get(id);
        if (instance == null) return Optional.empty();
        List<EffectExecution> result = new ArrayList<>();
        for (int i = 0; i < instance.definition.effects().size(); i++) {
            CompletionEffect effect = instance.definition.effects().get(i);
            result.add(new EffectExecution(effectId(instance, i), effect.type(),
                    instance.ledger.getOrDefault(i, EffectExecutionState.PENDING)));
        }
        return Optional.of(List.copyOf(result));
    }

    public synchronized Optional<ProcessState> state(UUID id) {
        return Optional.ofNullable(instances.get(id)).map(i -> i.state);
    }

    public synchronized void clear() {
        for (Instance instance : List.copyOf(instances.values())) cancel(instance);
        instances.clear();
        activeByBlock.clear();
        registeredBlocks.clear();
        processes.clear();
        blocks.clear();
        triggers.clear();
        inventoryAdapters.clear();
        handlers.clear();
    }

    private void unregisterHandler(String type, EffectHandler<?> handler) {
        HandlerRegistration<?> registration = handlers.get(type);
        if (registration == null || registration.handler != handler) return;
        List<Instance> active = instances.values().stream()
                .filter(i -> !terminal(i.state) && i.definition.effects().stream().anyMatch(e -> e.type().equals(type)))
                .toList();
        if (!active.isEmpty() && registration.policy == UnregisterPolicy.REJECT_WHILE_IN_USE) {
            throw new IllegalStateException("effect handler is in use: " + type);
        }
        if (registration.policy == UnregisterPolicy.FAIL_ACTIVE_PROCESSES) {
            active.forEach(i -> i.state = ProcessState.NEEDS_PROVIDER_ACTION);
        } else if (registration.policy == UnregisterPolicy.CANCEL_ACTIVE_PROCESSES) {
            active.forEach(this::cancel);
        }
        handlers.remove(type, registration);
    }

    private void cancel(Instance instance) {
        if (instance == null || terminal(instance.state)) return;
        if (!returnClaims(instance, ConsumptionPolicy.RETURN_ALWAYS)) {
            instance.state = ProcessState.FAILED;
            activeByBlock.remove(instance.block, instance.id);
            return;
        }
        instance.state = ProcessState.CANCELLED;
        activeByBlock.remove(instance.block, instance.id);
    }

    private boolean returnClaims(Instance instance, ConsumptionPolicy... policies) {
        if (instance.adapter == null || instance.claims.isEmpty() || instance.reservationState != Reservation.State.RESERVED) return true;
        EnumSet<ConsumptionPolicy> selected = EnumSet.noneOf(ConsumptionPolicy.class);
        Collections.addAll(selected, policies);
        List<Reservation.Claim> returns = instance.claims.stream().filter(c -> selected.contains(c.policy())).toList();
        if (returns.isEmpty()) return true;
        try {
            instance.reservationState = Reservation.State.RETURN_PENDING;
            instance.adapter.returnItems(returns);
            instance.reservationState = Reservation.State.RETURNED;
            return true;
        } catch (RuntimeException error) {
            instance.reservationState = Reservation.State.FAILED;
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends CompletionEffect> void execute(EffectHandler<E> handler, CompletionEffect effect, String effectId) {
        handler.execute((E) effect, effectId);
    }

    private static boolean terminal(ProcessState state) {
        return state == ProcessState.COMPLETED || state == ProcessState.CANCELLED || state == ProcessState.FAILED;
    }

    private static String effectId(Instance instance, int index) {
        return instance.id + ":" + instance.revision + ":" + index + ":" + instance.definition.effects().get(index).type();
    }

    private static RegistrationHandle handle(Runnable close) {
        AtomicBoolean closed = new AtomicBoolean();
        return () -> { if (closed.compareAndSet(false, true)) close.run(); };
    }

    private record HandlerRegistration<E extends CompletionEffect>(EffectHandler<E> handler, UnregisterPolicy policy) {}

    private static final class Instance {
        final UUID id;
        final BlockKey block;
        final ProcessDefinition definition;
        final UUID owner;
        final InventoryAdapter adapter;
        final List<Reservation.Claim> claims;
        final Map<Integer, EffectExecutionState> ledger = new HashMap<>();
        int step;
        long revision;
        ProcessState state = ProcessState.CREATED;
        Reservation.State reservationState;

        Instance(UUID id, BlockKey block, ProcessDefinition definition, UUID owner, InventoryAdapter adapter, List<Reservation.Claim> claims) {
            this.id = id;
            this.block = block;
            this.definition = definition;
            this.owner = owner;
            this.adapter = adapter;
            this.claims = claims;
        }
    }
}
