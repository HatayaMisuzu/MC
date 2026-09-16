package com.mccompanion.core.body.menu;

/** Server-thread facts and vanilla menu operations. No native menu or stack crosses this boundary. */
public interface InventoryMenuPort {
    Validation validate(String handle);
    View observe();
    Mutation apply(MenuActionController.Request request);
    void stopInput();

    record Validation(boolean valid, String code, int slotCount) { }
    record View(boolean open, String identity, long revision) { }
    /** accepted only means the native call ran; effectObserved comes from native postconditions. */
    record Mutation(boolean accepted, boolean effectObserved, String code) { }
}
