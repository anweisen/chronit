package dev.chronit.core.driver;

/**
 * A click on one slot of the open container.
 *
 * @param part   which half of the window {@code slot} counts within
 * @param slot   zero-based index within {@code part}
 * @param button which mouse button
 * @param mode   what kind of click
 */
public record SlotClick(
        InventoryPart part,
        int slot,
        ClickButton button,
        ClickMode mode) {

    /**
     * A container window addresses its own slots first and the player's inventory after them, as
     * one continuous range. Naming the half explicitly means a configuration does not have to know
     * how big the menu is to click a menu slot.
     */
    public enum InventoryPart {
        /** The opened window itself — the "top" inventory. Slot 0 is its first slot. */
        CONTAINER,
        /**
         * The player's own inventory, shown beneath. Slots 0-26 are the three main rows and 27-35
         * the hotbar. Needs the container's size, so it only works once contents have arrived.
         */
        PLAYER
    }

    public enum ClickButton { LEFT, RIGHT }

    public enum ClickMode {
        /** An ordinary click: picks the stack up, or activates the button in a menu. */
        PICKUP,
        /** Shift-click, which moves the stack to the other half of the window. */
        SHIFT,
        /** Drops the item without picking it up. */
        DROP
    }

    public static SlotClick container(int slot) {
        return new SlotClick(InventoryPart.CONTAINER, slot, ClickButton.LEFT, ClickMode.PICKUP);
    }

    public String describe() {
        return mode + " " + button + " click on " + part + " slot " + slot;
    }
}
