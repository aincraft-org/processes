package dev.craftingmanager.api;

import dev.craftingmanager.api.Domain.CompletionEffect;

public interface EffectHandler<E extends CompletionEffect> {
    String type();
    Class<E> effectType();
    default Domain.IdempotencyMode idempotency() { return Domain.IdempotencyMode.IDEMPOTENT; }
    void execute(E effect, String effectId);
}
