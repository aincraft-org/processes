package dev.craftingmanager.persistence;

import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.Domain.EffectExecution;
import dev.craftingmanager.api.Domain.ProcessState;
import dev.craftingmanager.api.Reservation;

import java.util.List;
import java.util.UUID;

public record ProcessInstanceRecord(
        UUID instanceId,
        BlockKey block,
        String processId,
        UUID owner,
        long revision,
        int step,
        int stepTicks,
        ProcessState state,
        Reservation.State reservationState,
        List<Reservation.Claim> claims,
        List<EffectExecution> ledger) {
    public ProcessInstanceRecord {
        if (instanceId == null || block == null || processId == null || processId.isBlank() || owner == null || state == null) {
            throw new IllegalArgumentException("process instance identity is required");
        }
        if (stepTicks < 0) throw new IllegalArgumentException("stepTicks cannot be negative");
        claims = List.copyOf(claims == null ? List.of() : claims);
        ledger = List.copyOf(ledger == null ? List.of() : ledger);
    }
}
