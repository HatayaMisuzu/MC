package com.mccompanion.minecraft.v120;

import com.mccompanion.core.body.menu.MenuHandleStore;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.item.ItemStack;

/** Native menu identity and synchronized observation binding; handle policy is shared Java. */
public final class MenuSessionTracker {
    private static final MenuHandleStore HANDLES = new MenuHandleStore();
    private static final Map<UUID, NativeMenu> MENUS = new HashMap<>();
    private MenuSessionTracker() { }

    public static Snapshot inspect(CompanionPlayer body) {
        return inspectAt(body, System.nanoTime(), System.currentTimeMillis());
    }
    static Snapshot inspectAt(CompanionPlayer body, long nanos, long millis) {
        NativeMenu current = current(body);
        if (current == null) return null;
        var handle = HANDLES.inspect(body.getUUID(), current.identity, current.revision, nanos, millis);
        return new Snapshot(handle.token(), current.menu.containerId, handle.expiresAtEpochMillis(),
                current.menu, handle.revision());
    }
    public static Validation validate(CompanionPlayer body, String token) {
        return validateAt(body, token, System.nanoTime());
    }
    static Validation validateAt(CompanionPlayer body, String token, long nanos) {
        NativeMenu current = current(body);
        String failure = HANDLES.validate(body.getUUID(), current == null ? "" : current.identity,
                current == null ? -1 : current.revision, token, nanos);
        if (failure == null && current == null) failure = "MENU_SESSION_CHANGED";
        return new Validation(failure == null, failure == null ? "OK" : failure,
                failure == null ? current.menu : null);
    }
    static com.mccompanion.core.body.menu.InventoryMenuPort.View view(CompanionPlayer body) {
        NativeMenu current = current(body);
        return current == null ? new com.mccompanion.core.body.menu.InventoryMenuPort.View(false, "", 0)
                : new com.mccompanion.core.body.menu.InventoryMenuPort.View(true, current.identity, current.revision);
    }
    private static NativeMenu current(CompanionPlayer body) {
        NativeMenu previous = MENUS.get(body.getUUID());
        AbstractContainerMenu menu = body.containerMenu;
        if (menu == body.inventoryMenu) {
            if (previous != null) previous.menu.removeSlotListener(previous);
            MENUS.remove(body.getUUID());
            return null;
        }
        if (previous == null || previous.menu != menu) {
            if (previous != null) previous.menu.removeSlotListener(previous);
            previous = new NativeMenu(menu);
            MENUS.put(body.getUUID(), previous);
            menu.addSlotListener(previous);
        }
        menu.broadcastChanges();
        if (previous.stateId != menu.getStateId()) {
            previous.stateId = menu.getStateId();
            previous.revision++;
        }
        return previous;
    }
    public static void invalidate(UUID companion) {
        if (companion == null) return;
        HANDLES.invalidate(companion);
        NativeMenu menu = MENUS.remove(companion);
        if (menu != null) menu.menu.removeSlotListener(menu);
    }
    static boolean isExpired(long issuedNanos, long nanos) { return MenuHandleStore.expired(issuedNanos, nanos); }
    private static final class NativeMenu implements ContainerListener {
        private final AbstractContainerMenu menu;
        private final String identity = UUID.randomUUID().toString();
        private long revision;
        private int stateId;
        private NativeMenu(AbstractContainerMenu menu) { this.menu = menu; this.stateId = menu.getStateId(); }
        @Override public void slotChanged(AbstractContainerMenu menu, int slot, ItemStack stack) { revision++; }
        @Override public void dataChanged(AbstractContainerMenu menu, int property, int value) { revision++; }
    }
    public record Snapshot(String token, int containerId, long expiresAtEpochMillis,
                           AbstractContainerMenu menu, long revision) { }
    public record Validation(boolean valid, String code, AbstractContainerMenu menu) { }
}
