package dev.craftingmanager.example;

import dev.craftingmanager.api.CraftingManagerApi;
import dev.craftingmanager.api.Domain.BlockKey;
import dev.craftingmanager.api.Domain.CompletionEffect;
import dev.craftingmanager.api.Domain.ConsumptionPolicy;
import dev.craftingmanager.api.Domain.InputRole;
import dev.craftingmanager.api.Domain.InputTiming;
import dev.craftingmanager.api.Domain.ProcessDefinition;
import dev.craftingmanager.api.Domain.ProcessInput;
import dev.craftingmanager.api.Domain.ProcessStep;
import dev.craftingmanager.api.Domain.RegistrationHandle;
import dev.craftingmanager.api.EffectHandler;
import dev.craftingmanager.api.InventoryAdapter;
import dev.craftingmanager.api.ItemSnapshot;
import dev.craftingmanager.api.Reservation;
import org.bukkit.entity.Player;

import java.util.List;

/** Small provider-style example that registers one GUI-backed process. */
public final class ExampleProcessProvider {
    public static final String PROCESS_ID = "example:alloy-smelt";

    private final CraftingManagerApi api;
    private final ExampleGuiListener guiListener;
    private RegistrationHandle processRegistration;
    private RegistrationHandle adapterRegistration;
    private RegistrationHandle effectRegistration;

    public ExampleProcessProvider(CraftingManagerApi api, ExampleGuiListener guiListener) {
        this.api = api;
        this.guiListener = guiListener;
    }

    public void enable() {
        adapterRegistration = api.registerInventoryAdapter(new ExampleInventoryAdapter());
        effectRegistration = api.registerEffectHandler(new ExampleOutputHandler());
        processRegistration = api.registerProcess(new ProcessDefinition(
                PROCESS_ID,
                List.of(
                        new ProcessInput("iron", InputRole.PRIMARY_MATERIAL, "IRON_INGOT", 1,
                                ConsumptionPolicy.CONSUME, InputTiming.ON_START, false, null),
                        new ProcessInput("fuel", InputRole.FUEL, "COAL", 1,
                                ConsumptionPolicy.CONSUME, InputTiming.ON_START, false, null)),
                List.of(
                        new ProcessStep("heat", "Heat", 40),
                        new ProcessStep("smelt", "Smelt", 60)),
                List.of(new ExampleOutputEffect())));
    }

    public void disable() {
        if (processRegistration != null) processRegistration.close();
        if (effectRegistration != null) effectRegistration.close();
        if (adapterRegistration != null) adapterRegistration.close();
        processRegistration = null;
        effectRegistration = null;
        adapterRegistration = null;
    }

    public ExampleProcessGui openGui(Player player, BlockKey block) {
        ExampleProcessGui gui = new ExampleProcessGui(api, block, player.getUniqueId(), PROCESS_ID);
        guiListener.track(player, gui);
        gui.open(player);
        return gui;
    }

    public record ExampleOutputEffect() implements CompletionEffect {
        @Override public String type() { return "example:item-output"; }
    }

    private static final class ExampleOutputHandler implements EffectHandler<ExampleOutputEffect> {
        @Override public String type() { return "example:item-output"; }
        @Override public Class<ExampleOutputEffect> effectType() { return ExampleOutputEffect.class; }
        @Override public void execute(ExampleOutputEffect effect, String effectId) { }
    }

    private static final class ExampleInventoryAdapter implements InventoryAdapter {
        @Override public List<Reservation.Claim> captureClaims(List<ProcessInput> inputs) {
            return inputs.stream().map(input -> new Reservation.Claim(
                    Reservation.Source.PLAYER_INVENTORY, 0,
                    new ItemSnapshot(input.matcher(), input.amount(), null), input.amount(), input.id(), input.consumption())).toList();
        }
        @Override public boolean claimsStillMatch(List<Reservation.Claim> claims) { return true; }
        @Override public void remove(List<Reservation.Claim> claims) { }
        @Override public void returnItems(List<Reservation.Claim> claims) { }
    }
}
