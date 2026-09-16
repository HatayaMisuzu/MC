package com.mccompanion.minecraft.v121;

import com.mccompanion.core.body.menu.InventoryMenuPort;
import com.mccompanion.core.body.menu.MenuActionController;
import net.minecraft.world.inventory.ClickType;

/** A short-lived binding, constructed and used only on the server thread. */
final class MinecraftInventoryMenuPort implements InventoryMenuPort {
    private final CompanionPlayer body;
    private final PlayerActionGateway gateway;
    MinecraftInventoryMenuPort(CompanionPlayer body, PlayerActionGateway gateway) {
        this.body = body; this.gateway = gateway;
    }
    @Override public Validation validate(String handle) {
        var result = MenuSessionTracker.validate(body, handle);
        return new Validation(result.valid(), result.code(), result.valid() ? result.menu().slots.size() : 0);
    }
    @Override public View observe() { return MenuSessionTracker.view(body); }
    @Override public void stopInput() { gateway.stopInput(body); }
    @Override public Mutation apply(MenuActionController.Request request) {
        var valid = MenuSessionTracker.validate(body, request.handle());
        if (!valid.valid()) return new Mutation(false, false, valid.code());
        View before = observe();
        try {
            if (request.action().equals("CLOSE")) {
                body.closeContainer();
            } else {
                valid.menu().clicked(request.slot(), request.action().equals("CLICK") ? request.button() : 0,
                        request.action().equals("QUICK_MOVE") ? ClickType.QUICK_MOVE : ClickType.PICKUP, body);
                valid.menu().broadcastChanges();
            }
            gateway.markVanillaMenuAction(body);
            View after = observe();
            return new Mutation(true, before.open() != after.open() || !before.identity().equals(after.identity())
                    || before.revision() != after.revision(), "OK");
        } catch (RuntimeException failure) {
            // Native code may have mutated before throwing. Never invoke it again to guess the outcome.
            return new Mutation(true, false, "UNCERTAIN_EFFECT");
        }
    }
}
