package com.mccompanion.core.body;

import com.mccompanion.core.body.menu.InventoryMenuPort;
import com.mccompanion.core.body.menu.MenuActionController;
import com.mccompanion.core.body.menu.MenuHandleStore;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MenuActionControllerTest {
    @Test void acceptanceWaitsForObservationAndNeverReplaysTheClick() {
        var port = new Port();
        var session = session("QUICK_MOVE", 0, null);
        assertEquals(MenuActionController.State.WAITING_OBSERVATION, session.tick(port, 10).state());
        assertFalse(session.tick(port, 10).terminal());
        assertTrue(session.tick(port, 11).success());
        assertTrue(session.tick(port, 12).success());
        assertEquals(1, port.clicks);
    }
    @Test void acceptedNativeCallWithoutObservedEffectIsUnknown() {
        var port = new Port(); port.effect = false;
        var session = session("CLICK", 1, 0);
        session.tick(port, 10);
        assertEquals("UNCERTAIN_EFFECT", session.tick(port, 11).code());
        assertEquals(1, port.clicks);
    }
    @Test void invalidObservationAndSlotsNeverReachNativeCode() {
        var port = new Port(); port.valid = false;
        assertEquals("MENU_OBSERVATION_STALE", session("CLICK", 0, 0).tick(port, 10).code());
        port.valid = true;
        assertEquals("MENU_SLOT_INVALID", session("CLICK", 128, 0).tick(port, 10).code());
        assertEquals("MENU_BUTTON_INVALID", session("CLICK", 0, 4).tick(port, 10).code());
        assertEquals(0, port.clicks);
    }
    @Test void cancellationAfterDispatchRequiresReconciliationAndDoesNotClickAgain() {
        var port = new Port(); var session = session("CLICK", 0, 0);
        session.tick(port, 10);
        assertEquals("CANCELLED_EFFECT_REQUIRES_RECONCILIATION", session.cancel().code());
        assertEquals(MenuActionController.State.CANCELLED, session.tick(port, 11).state());
        assertEquals(1, port.clicks);
    }
    @Test void replacementAfterDispatchCannotConfirmAnEffectInAnotherMenu() {
        var port = new Port(); var session = session("CLICK", 0, 0);
        session.tick(port, 10); port.identity = "replacement";
        assertEquals(MenuActionController.State.UNKNOWN, session.tick(port, 11).state());
        assertEquals(1, port.clicks);
    }
    @Test void closeSucceedsOnlyAfterClosedObservation() {
        var port = new Port(); var session = session("CLOSE", null, null);
        session.tick(port, 10);
        assertEquals("MENU_CLOSE_FAILED", session.tick(port, 11).code());
        var closing = session("CLOSE", null, null);
        closing.tick(port, 10); port.open = false;
        assertTrue(closing.tick(port, 11).success());
    }
    @Test void opaqueHandlesBindCompanionMenuRevisionAndExpiry() {
        var store = new MenuHandleStore(); var companion = java.util.UUID.randomUUID();
        var handle = store.inspect(companion, "menu", 2, 0, 1000);
        assertNull(store.validate(companion, "menu", 2, handle.token(), 1));
        assertEquals("MENU_SESSION_INVALID", store.validate(java.util.UUID.randomUUID(), "menu", 2, handle.token(), 1));
        assertEquals("MENU_OBSERVATION_STALE", store.validate(companion, "menu", 3, handle.token(), 1));
        var renewed = store.inspect(companion, "menu", 3, 1, 1001);
        assertNotEquals(handle.token(), renewed.token());
        assertEquals("MENU_SESSION_EXPIRED", store.validate(companion, "menu", 3, renewed.token(), 60_000_000_002L));
    }
    private static MenuActionController.Session session(String action, Integer slot, Integer button) {
        return new MenuActionController.Session(new MenuActionController.Request("handle", action, slot, button), 10);
    }
    private static final class Port implements InventoryMenuPort {
        boolean valid = true, effect = true, open = true;
        String identity = "menu";
        int clicks;
        @Override public Validation validate(String handle) { return new Validation(valid, "MENU_OBSERVATION_STALE", 128); }
        @Override public View observe() { return new View(open, identity, clicks); }
        @Override public Mutation apply(MenuActionController.Request request) { clicks++; return new Mutation(true, effect, "OK"); }
        @Override public void stopInput() { }
    }
}
