package dev.craftingmanager.runtime;

import dev.craftingmanager.api.CraftingManagerApi;
import dev.craftingmanager.api.Domain.*;
import dev.craftingmanager.api.EffectContext;
import dev.craftingmanager.api.EffectHandler;
import dev.craftingmanager.api.InventoryAdapter;
import dev.craftingmanager.api.ItemSnapshot;
import dev.craftingmanager.api.ProcessEventSink;
import dev.craftingmanager.api.ProcessTrigger;
import dev.craftingmanager.api.ProcessUsage;
import dev.craftingmanager.api.RecipeApi.Ingredient;
import dev.craftingmanager.api.RecipeApi.Mode;
import dev.craftingmanager.api.RecipeApi.PatternDefinition;
import dev.craftingmanager.api.RecipeApi.RecipeDefinition;
import dev.craftingmanager.api.Reservation;
import dev.craftingmanager.persistence.FunctionalBlockRecord;
import dev.craftingmanager.persistence.ProcessInstanceRecord;
import dev.craftingmanager.persistence.ProcessStore;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class RuntimeEngine implements CraftingManagerApi, StationPorts {
    private final ProcessStore store;
    private final ProcessEventSink events;
    private Consumer<String> lockableRegistered = material -> {};
    private final Map<String, ProcessDefinition> processes = new HashMap<>();
    private final Map<String, FunctionalBlockDefinition> blocks = new HashMap<>();
    private final Map<String, Integer> lockableRefCounts = new HashMap<>();
    private final Map<String, RecipeDefinition> recipes = new HashMap<>();
    private final Map<BlockKey, String> registeredBlocks = new HashMap<>();
    private final List<ProcessTrigger> triggers = new ArrayList<>();
    private final List<InventoryAdapter> inventoryAdapters = new ArrayList<>();
    private final Map<String, HandlerRegistration<?>> handlers = new HashMap<>();
    private final Map<UUID, Instance> instances = new HashMap<>();
    private final Map<BlockKey, UUID> activeByBlock = new HashMap<>();
    private final Map<BlockKey, Map<String, ItemSnapshot>> stationPorts = new HashMap<>();
    private Predicate<BlockKey> chunkLoaded = key -> true;

    public RuntimeEngine() {
        this(ProcessStore.none());
    }

    public RuntimeEngine(ProcessStore store) {
        this(store, ProcessEventSink.noop());
    }

    public RuntimeEngine(ProcessStore store, ProcessEventSink events) {
        this.store = Objects.requireNonNull(store, "store");
        this.events = events == null ? ProcessEventSink.noop() : events;
    }

    public void onLockableRegistered(Consumer<String> listener) {
        this.lockableRegistered = listener == null ? material -> {} : listener;
    }

    public synchronized void setChunkLoaded(Predicate<BlockKey> chunkLoaded) {
        this.chunkLoaded = chunkLoaded == null ? key -> true : chunkLoaded;
    }

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
        RegistrationHandle lockable = addLockable(definition.material());
        return handle(() -> { synchronized (this) {
            lockable.close();
            blocks.remove(definition.id(), definition);
            registeredBlocks.values().removeIf(id -> id.equals(definition.id()));
        } });
    }

    @Override public synchronized RegistrationHandle registerLockableBlock(String material) {
        if (material == null || material.isBlank()) throw new IllegalArgumentException("material is required");
        return addLockable(material.toUpperCase(Locale.ROOT));
    }

    @Override public synchronized boolean isLockableBlock(String material) {
        if (material == null || material.isBlank()) return false;
        return lockableRefCounts.getOrDefault(material.toUpperCase(Locale.ROOT), 0) > 0;
    }

    @Override public synchronized Set<String> lockableBlocks() {
        Set<String> ids = new HashSet<>();
        for (Map.Entry<String, Integer> entry : lockableRefCounts.entrySet()) {
            if (entry.getValue() > 0) ids.add(entry.getKey());
        }
        return Set.copyOf(ids);
    }

    @Override public synchronized RegistrationHandle registerRecipe(RecipeDefinition definition) {
        Objects.requireNonNull(definition);
        if (recipes.putIfAbsent(definition.id(), definition) != null) {
            throw new IllegalArgumentException("recipe already registered: " + definition.id());
        }
        return handle(() -> { synchronized (this) { recipes.remove(definition.id(), definition); } });
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
        placeFunctionalBlock(key, definitionId);
    }

    @Override public synchronized void placeFunctionalBlock(BlockKey key, String definitionId) {
        Objects.requireNonNull(key);
        if (!blocks.containsKey(definitionId)) throw new IllegalArgumentException("unknown block definition: " + definitionId);
        registeredBlocks.put(key, definitionId);
        store.saveBlock(key, definitionId);
    }

    @Override public synchronized void invalidateBlock(BlockKey key) {
        registeredBlocks.remove(key);
        store.removeBlock(key);
        stationPorts.remove(key);
        UUID instanceId = activeByBlock.get(key);
        if (instanceId != null) cancel(instances.get(instanceId));
    }

    @Override public synchronized boolean insertAt(BlockKey block, ProcessFace face, ItemSnapshot item) {
        Objects.requireNonNull(block);
        Objects.requireNonNull(face);
        Objects.requireNonNull(item);
        ProcessDefinition definition = processAt(block);
        if (definition == null) return false;
        for (ProcessInput input : definition.inputs()) {
            if (!input.insertFaces().contains(face) || !input.matcher().equals(item.material())) continue;
            return mergePort(block, input.id(), item);
        }
        return false;
    }

    @Override public synchronized Optional<ItemSnapshot> extractAt(BlockKey block, ProcessFace face, int amount) {
        Objects.requireNonNull(block);
        Objects.requireNonNull(face);
        if (amount <= 0) return Optional.empty();
        ProcessDefinition definition = processAt(block);
        if (definition == null) return Optional.empty();
        for (CompletionEffect effect : definition.effects()) {
            if (!(effect instanceof ItemOutput output) || !output.extractFaces().contains(face)) continue;
            Optional<ItemSnapshot> taken = takePort(block, output.id(), amount);
            if (taken.isPresent()) return taken;
        }
        return Optional.empty();
    }

    @Override public synchronized boolean offerOutput(BlockKey block, ItemOutput output) {
        Objects.requireNonNull(block);
        Objects.requireNonNull(output);
        return mergePort(block, output.id(), output.item());
    }

    @Override public synchronized Optional<ProcessDefinition> process(String id) {
        return Optional.ofNullable(processes.get(id));
    }

    @Override public synchronized Optional<RecipeDefinition> recipe(String id) {
        return Optional.ofNullable(recipes.get(id));
    }

    @Override public synchronized Optional<FunctionalBlockDefinition> functionalBlockDefinition(String id) {
        return Optional.ofNullable(blocks.get(id));
    }

    @Override public synchronized Optional<String> placedFunctionalBlock(BlockKey block) {
        return Optional.ofNullable(registeredBlocks.get(block));
    }

    @Override public synchronized List<RecipeDefinition> match(List<ItemSnapshot> inputs) {
        List<ItemSnapshot> offered = List.copyOf(inputs == null ? List.of() : inputs);
        List<RecipeDefinition> matches = new ArrayList<>();
        for (RecipeDefinition recipe : recipes.values()) {
            if (recipe.mode() == Mode.PATTERN) continue;
            if (ingredientsMatch(recipe.ingredients(), offered)) matches.add(recipe);
        }
        return List.copyOf(matches);
    }

    @Override public synchronized List<RecipeDefinition> matchPattern(PatternDefinition pattern, List<ItemSnapshot> inputs) {
        Objects.requireNonNull(pattern);
        List<ItemSnapshot> offered = List.copyOf(inputs == null ? List.of() : inputs);
        List<RecipeDefinition> matches = new ArrayList<>();
        for (RecipeDefinition recipe : recipes.values()) {
            if (recipe.mode() != Mode.PATTERN) continue;
            if (recipe.pattern().isPresent() && recipe.pattern().orElseThrow().equals(pattern)
                    && ingredientsMatch(recipe.ingredients(), offered)) {
                matches.add(recipe);
            }
        }
        return List.copyOf(matches);
    }

    @Override public synchronized ProcessStartResult trigger(BlockKey block, UUID owner) {
        Objects.requireNonNull(block);
        Objects.requireNonNull(owner);
        for (ProcessTrigger trigger : List.copyOf(triggers)) {
            Optional<String> processId = trigger.selectProcess(block, owner);
            if (processId.isPresent()) return start(block, processId.get(), owner);
        }
        String placed = registeredBlocks.get(block);
        if (placed != null) {
            FunctionalBlockDefinition definition = blocks.get(placed);
            if (definition != null && !definition.processIds().isEmpty()) {
                return start(block, definition.processIds().getFirst(), owner);
            }
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
        if (!events.emitStarting(new ProcessUsage(null, block, processId, owner))) {
            return ProcessStartResult.rejected("cancelled");
        }
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
            claims = List.copyOf(Objects.requireNonNull(
                    adapter.captureClaims(block, owner, definition.inputs()), "claims"));
            if (!coversRequired(definition.inputs(), claims)) return ProcessStartResult.rejected("missing inputs");
            if (!adapter.claimsStillMatch(owner, claims)) return ProcessStartResult.rejected("input claims changed");
        }

        UUID id = UUID.randomUUID();
        Instance instance = new Instance(id, block, definition, owner, adapter, claims);
        instances.put(id, instance);
        activeByBlock.put(block, id);
        try {
            if (adapter != null) {
                instance.state = ProcessState.CLAIM_CAPTURED;
                instance.reservationState = Reservation.State.CLAIMED;
                adapter.remove(owner, claims);
                instance.reservationState = Reservation.State.RESERVED;
            }
            instance.state = ProcessState.RUNNING;
            persist(instance);
            events.emitStarted(new ProcessUsage(id, block, processId, owner));
            return new ProcessStartResult(true, id, "started");
        } catch (RuntimeException error) {
            instance.state = ProcessState.FAILED;
            activeByBlock.remove(block, id);
            instances.remove(id);
            store.delete(id);
            return ProcessStartResult.rejected("reservation failed: " + error.getMessage());
        }
    }

    @Override public synchronized CompletionStage<ProcessState> advance(UUID instanceId) {
        Instance instance = instances.get(instanceId);
        if (instance == null) return CompletableFuture.failedStage(new IllegalArgumentException("unknown instance"));
        if (instance.state != ProcessState.RUNNING) return CompletableFuture.completedFuture(instance.state);
        if (!chunkLoaded.test(instance.block)) return CompletableFuture.completedFuture(instance.state);
        return CompletableFuture.completedFuture(tickRunning(instance));
    }

    public synchronized void tick() {
        for (Instance instance : List.copyOf(instances.values())) {
            if (instance.state != ProcessState.RUNNING) continue;
            if (!chunkLoaded.test(instance.block)) continue;
            tickRunning(instance);
        }
    }

    public synchronized void persistChunk(UUID worldId, int chunkX, int chunkZ) {
        Objects.requireNonNull(worldId);
        for (Instance instance : instances.values()) {
            if (!instance.block.worldId().equals(worldId)) continue;
            if (Math.floorDiv(instance.block.x(), 16) != chunkX) continue;
            if (Math.floorDiv(instance.block.z(), 16) != chunkZ) continue;
            persist(instance);
        }
    }

    private ProcessState tickRunning(Instance instance) {
        List<ProcessStep> steps = instance.definition.steps();
        if (instance.step >= steps.size()) return applyEffects(instance);
        ProcessStep current = steps.get(instance.step);
        instance.stepTicks++;
        boolean persistNow = instance.stepTicks % 20 == 0;
        if (instance.stepTicks >= current.durationTicks()) {
            instance.step++;
            instance.stepTicks = 0;
            persistNow = true;
            if (instance.step >= steps.size()) return applyEffects(instance);
        }
        if (persistNow) persist(instance);
        return instance.state;
    }

    private ProcessState applyEffects(Instance instance) {
        instance.state = ProcessState.OUTPUT_PENDING;
        for (int i = 0; i < instance.definition.effects().size(); i++) {
            if (instance.ledger.get(i) == EffectExecutionState.APPLIED) continue;
            CompletionEffect effect = instance.definition.effects().get(i);
            HandlerRegistration<?> registration = handlers.get(effect.type());
            if (registration == null || !registration.handler.effectType().isInstance(effect)) {
                instance.state = ProcessState.NEEDS_PROVIDER_ACTION;
                persist(instance);
                emitFinished(instance);
                return instance.state;
            }
            String effectId = effectId(instance, i);
            try {
                instance.ledger.put(i, EffectExecutionState.RUNNING);
                execute(registration.handler, effect, new EffectContext(
                        instance.id, instance.revision, instance.owner, instance.block, effectId));
                instance.ledger.put(i, EffectExecutionState.APPLIED);
            } catch (RuntimeException error) {
                instance.ledger.put(i, EffectExecutionState.UNKNOWN);
                instance.state = ProcessState.NEEDS_PROVIDER_ACTION;
                persist(instance);
                emitFinished(instance);
                return instance.state;
            }
        }
        if (!returnClaims(instance, ConsumptionPolicy.RETURN_ON_SUCCESS, ConsumptionPolicy.RETURN_ALWAYS)) {
            instance.state = ProcessState.NEEDS_PROVIDER_ACTION;
            persist(instance);
            emitFinished(instance);
            return instance.state;
        }
        instance.state = ProcessState.COMPLETED;
        activeByBlock.remove(instance.block, instance.id);
        if (instance.reservationState != null) instance.reservationState = Reservation.State.CONSUMED;
        persist(instance);
        emitFinished(instance);
        return instance.state;
    }

    public synchronized void hydrate() {
        for (FunctionalBlockRecord record : store.loadBlocks()) {
            registeredBlocks.put(record.key(), record.definitionId());
        }
        for (ProcessInstanceRecord record : store.loadAll()) {
            restore(record);
        }
    }

    public synchronized Optional<BlockKey> instanceBlock(UUID instanceId) {
        return Optional.ofNullable(instances.get(instanceId)).map(instance -> instance.block);
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

    public synchronized void shutdown() {
        for (Instance instance : instances.values()) persist(instance);
    }

    public synchronized void clear() {
        for (Instance instance : List.copyOf(instances.values())) cancel(instance);
        instances.clear();
        activeByBlock.clear();
        registeredBlocks.clear();
        stationPorts.clear();
        processes.clear();
        blocks.clear();
        lockableRefCounts.clear();
        recipes.clear();
        triggers.clear();
        inventoryAdapters.clear();
        handlers.clear();
    }

    private void restore(ProcessInstanceRecord record) {
        ProcessDefinition definition = processes.get(record.processId());
        ProcessState state = record.state();
        if (definition == null || missingHandlers(definition)) state = ProcessState.NEEDS_PROVIDER_ACTION;
        if (definition == null) {
            String effectType = record.ledger().isEmpty() ? "unknown" : record.ledger().getFirst().effectType();
            definition = new ProcessDefinition(record.processId(), List.of(), List.of(),
                    List.of((CompletionEffect) () -> effectType));
        }
        InventoryAdapter adapter = findAdapter(record.block(), record.owner());
        Instance instance = new Instance(record.instanceId(), record.block(), definition, record.owner(), adapter, record.claims());
        instance.revision = record.revision();
        instance.step = record.step();
        instance.stepTicks = record.stepTicks();
        instance.state = state;
        instance.reservationState = record.reservationState();
        for (int i = 0; i < record.ledger().size(); i++) {
            instance.ledger.put(i, record.ledger().get(i).state());
        }
        instances.put(instance.id, instance);
        if (!terminal(instance.state)) activeByBlock.put(instance.block, instance.id);
        persist(instance);
    }

    private boolean missingHandlers(ProcessDefinition definition) {
        for (CompletionEffect effect : definition.effects()) {
            HandlerRegistration<?> registration = handlers.get(effect.type());
            if (registration == null || !registration.handler.effectType().isInstance(effect)) return true;
        }
        return false;
    }

    private InventoryAdapter findAdapter(BlockKey block, UUID owner) {
        for (InventoryAdapter candidate : inventoryAdapters) {
            if (candidate.supports(block, owner)) return candidate;
        }
        return null;
    }

    private void persist(Instance instance) {
        if (terminal(instance.state)) {
            store.delete(instance.id);
            return;
        }
        List<EffectExecution> ledger = new ArrayList<>();
        for (int i = 0; i < instance.definition.effects().size(); i++) {
            CompletionEffect effect = instance.definition.effects().get(i);
            ledger.add(new EffectExecution(effectId(instance, i), effect.type(),
                    instance.ledger.getOrDefault(i, EffectExecutionState.PENDING)));
        }
        store.save(new ProcessInstanceRecord(
                instance.id, instance.block, instance.definition.id(), instance.owner, instance.revision,
                instance.step, instance.stepTicks, instance.state, instance.reservationState, instance.claims, ledger));
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
            active.forEach(i -> {
                i.state = ProcessState.NEEDS_PROVIDER_ACTION;
                persist(i);
            });
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
            persist(instance);
            emitFinished(instance);
            return;
        }
        instance.state = ProcessState.CANCELLED;
        activeByBlock.remove(instance.block, instance.id);
        persist(instance);
        emitFinished(instance);
    }

    private boolean returnClaims(Instance instance, ConsumptionPolicy... policies) {
        if (instance.adapter == null || instance.claims.isEmpty() || instance.reservationState != Reservation.State.RESERVED) return true;
        EnumSet<ConsumptionPolicy> selected = EnumSet.noneOf(ConsumptionPolicy.class);
        Collections.addAll(selected, policies);
        List<Reservation.Claim> returns = instance.claims.stream().filter(c -> selected.contains(c.policy())).toList();
        if (returns.isEmpty()) return true;
        try {
            instance.reservationState = Reservation.State.RETURN_PENDING;
            instance.adapter.returnItems(instance.owner, returns);
            instance.reservationState = Reservation.State.RETURNED;
            return true;
        } catch (RuntimeException error) {
            instance.reservationState = Reservation.State.FAILED;
            return false;
        }
    }

    private RegistrationHandle addLockable(String material) {
        String id = material.toUpperCase(Locale.ROOT);
        lockableRefCounts.merge(id, 1, Integer::sum);
        if (lockableRefCounts.get(id) == 1) lockableRegistered.accept(id);
        return handle(() -> { synchronized (this) {
            Integer remaining = lockableRefCounts.get(id);
            if (remaining == null) return;
            if (remaining <= 1) lockableRefCounts.remove(id);
            else lockableRefCounts.put(id, remaining - 1);
        } });
    }

    private void emitFinished(Instance instance) {
        events.emitFinished(new ProcessUsage(instance.id, instance.block, instance.definition.id(), instance.owner),
                instance.state);
    }

    private static boolean ingredientsMatch(List<Ingredient> ingredients, List<ItemSnapshot> offered) {
        List<ItemSnapshot> remaining = new ArrayList<>(offered);
        for (Ingredient ingredient : ingredients) {
            int needed = ingredient.amount();
            for (int i = 0; i < remaining.size() && needed > 0; i++) {
                ItemSnapshot snapshot = remaining.get(i);
                if (!ingredient.matcher().equals(snapshot.material())) continue;
                int used = Math.min(needed, snapshot.amount());
                needed -= used;
                if (used == snapshot.amount()) remaining.remove(i--);
                else remaining.set(i, new ItemSnapshot(snapshot.material(), snapshot.amount() - used, snapshot.metadata()));
            }
            if (needed > 0) return false;
        }
        return true;
    }

    private ProcessDefinition processAt(BlockKey block) {
        String placed = registeredBlocks.get(block);
        if (placed == null) return null;
        FunctionalBlockDefinition definition = blocks.get(placed);
        if (definition == null || definition.processIds().isEmpty()) return null;
        return processes.get(definition.processIds().getFirst());
    }

    private boolean mergePort(BlockKey block, String portId, ItemSnapshot item) {
        Map<String, ItemSnapshot> ports = stationPorts.computeIfAbsent(block, key -> new HashMap<>());
        ItemSnapshot current = ports.get(portId);
        if (current == null) {
            ports.put(portId, item);
            return true;
        }
        if (!current.material().equals(item.material())) return false;
        ports.put(portId, new ItemSnapshot(current.material(), current.amount() + item.amount(), current.metadata()));
        return true;
    }

    private Optional<ItemSnapshot> takePort(BlockKey block, String portId, int amount) {
        Map<String, ItemSnapshot> ports = stationPorts.get(block);
        if (ports == null) return Optional.empty();
        ItemSnapshot current = ports.get(portId);
        if (current == null) return Optional.empty();
        int taken = Math.min(amount, current.amount());
        if (taken <= 0) return Optional.empty();
        if (taken == current.amount()) ports.remove(portId);
        else ports.put(portId, new ItemSnapshot(current.material(), current.amount() - taken, current.metadata()));
        return Optional.of(new ItemSnapshot(current.material(), taken, current.metadata()));
    }

    private static boolean coversRequired(List<ProcessInput> inputs, List<Reservation.Claim> claims) {
        for (ProcessInput input : inputs) {
            if (input.optional() || input.timing() != InputTiming.ON_START) continue;
            boolean claimed = false;
            for (Reservation.Claim claim : claims) {
                if (claim.inputId().equals(input.id()) && claim.amount() >= input.amount()) {
                    claimed = true;
                    break;
                }
            }
            if (!claimed) return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static <E extends CompletionEffect> void execute(
            EffectHandler<E> handler, CompletionEffect effect, EffectContext context) {
        handler.execute((E) effect, context);
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
        int stepTicks;
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
