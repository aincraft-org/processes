package dev.craftingmanager.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class Domain {
    private Domain() {}

    public record BlockKey(UUID worldId, int x, int y, int z) {
        public BlockKey {
            if (worldId == null) throw new IllegalArgumentException("worldId is required");
        }
    }

    public enum InputRole { PRIMARY_MATERIAL, SECONDARY_MATERIAL, CATALYST, FUEL, TOOL, FLUID, CONTAINER, ADDITIVE }
    public enum InputTiming { ON_START, BEFORE_STAGE, DURING_STAGE, ON_COMPLETION }
    public enum ConsumptionPolicy { CONSUME, RETURN_ON_SUCCESS, RETURN_ALWAYS, DAMAGE, RETAIN_IN_STATION }
    public enum ProcessState { CREATED, CLAIM_CAPTURED, PENDING_RESERVATION, RESERVED, RUNNING, PAUSED, OUTPUT_PENDING, COMPLETED, CANCELLED, FAILED, NEEDS_PROVIDER_ACTION }
    public enum EffectExecutionState { PENDING, RUNNING, APPLIED, FAILED, UNKNOWN }
    public enum IdempotencyMode { IDEMPOTENT, PROVIDER_DEDUPLICATES, NON_RETRYABLE }
    public enum UnregisterPolicy { REJECT_WHILE_IN_USE, FAIL_ACTIVE_PROCESSES, CANCEL_ACTIVE_PROCESSES }
    public enum ProcessFace { UP, DOWN, NORTH, SOUTH, EAST, WEST;

        public ProcessFace opposite() {
            return switch (this) {
                case UP -> DOWN;
                case DOWN -> UP;
                case NORTH -> SOUTH;
                case SOUTH -> NORTH;
                case EAST -> WEST;
                case WEST -> EAST;
            };
        }
    }

    public record ProcessInput(String id, InputRole role, String matcher, int amount, ConsumptionPolicy consumption, InputTiming timing, boolean optional, String stageId, Set<ProcessFace> insertFaces) {
        public ProcessInput(String id, InputRole role, String matcher, int amount, ConsumptionPolicy consumption, InputTiming timing, boolean optional, String stageId) {
            this(id, role, matcher, amount, consumption, timing, optional, stageId, Set.of());
        }
        public ProcessInput {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("input id is required");
            if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
            if (matcher == null || matcher.isBlank()) throw new IllegalArgumentException("matcher is required");
            if (timing == InputTiming.BEFORE_STAGE && (stageId == null || stageId.isBlank())) throw new IllegalArgumentException("stageId is required");
            insertFaces = Set.copyOf(insertFaces == null ? Set.of() : insertFaces);
        }
    }

    public record ProcessStep(String id, String name, long durationTicks) {
        public ProcessStep {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("step id is required");
            if (durationTicks < 0) throw new IllegalArgumentException("duration cannot be negative");
        }
    }

    public interface CompletionEffect { String type(); }

    public record ItemOutput(String id, ItemSnapshot item, Set<ProcessFace> extractFaces) implements CompletionEffect {
        public static final String TYPE = "item-output";
        public ItemOutput(ItemSnapshot item) { this("output", item, Set.of()); }
        public ItemOutput {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("item output id is required");
            if (item == null) throw new IllegalArgumentException("item output snapshot is required");
            extractFaces = Set.copyOf(extractFaces == null ? Set.of() : extractFaces);
        }
        @Override public String type() { return TYPE; }
    }
    public record EffectExecution(String effectId, String effectType, EffectExecutionState state) {}
    public record ProcessInstanceSnapshot(
            UUID instanceId,
            String processId,
            ProcessState state,
            int stepIndex,
            long stepTicks,
            long stepDurationTicks,
            UUID owner,
            List<EffectExecution> ledger
    ) {
        public ProcessInstanceSnapshot {
            if (instanceId == null) throw new IllegalArgumentException("instanceId is required");
            if (processId == null || processId.isBlank()) throw new IllegalArgumentException("processId is required");
            if (state == null) throw new IllegalArgumentException("state is required");
            if (stepIndex < 0) throw new IllegalArgumentException("stepIndex cannot be negative");
            if (stepTicks < 0) throw new IllegalArgumentException("stepTicks cannot be negative");
            if (stepDurationTicks < 0) throw new IllegalArgumentException("stepDurationTicks cannot be negative");
            if (owner == null) throw new IllegalArgumentException("owner is required");
            ledger = List.copyOf(ledger == null ? List.of() : ledger);
        }
    }
    public record ProcessCancelResult(boolean cancelled, String reason) {
        public static ProcessCancelResult rejected(String reason) { return new ProcessCancelResult(false, reason); }
    }
    public record ProcessDismissResult(boolean dismissed, String reason) {
        public static ProcessDismissResult rejected(String reason) { return new ProcessDismissResult(false, reason); }
    }

    public record ProcessDefinition(String id, List<ProcessInput> inputs, List<ProcessStep> steps, List<CompletionEffect> effects) {
        public ProcessDefinition {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("process id is required");
            inputs = List.copyOf(inputs == null ? List.of() : inputs);
            steps = List.copyOf(steps == null ? List.of() : steps);
            effects = List.copyOf(effects == null ? List.of() : effects);
            if (effects.isEmpty()) throw new IllegalArgumentException("at least one completion effect is required");
        }
    }

    public record FunctionalBlockDefinition(String id, String material, List<String> processIds) {
        public FunctionalBlockDefinition {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("block id is required");
            if (material == null || material.isBlank()) throw new IllegalArgumentException("material is required");
            processIds = List.copyOf(processIds == null ? List.of() : processIds);
        }
    }

    public interface RegistrationHandle extends AutoCloseable {
        @Override void close();
    }
}
