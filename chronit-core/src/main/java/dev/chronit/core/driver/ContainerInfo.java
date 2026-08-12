package dev.chronit.core.driver;

/**
 * A container the server has opened — a chest, or more usually a plugin's menu.
 *
 * @param containerId      the server's handle for this window; 0 is the player's own inventory and
 *                         is never reported here
 * @param type             wire container type, e.g. {@code GENERIC_9X6}
 * @param title            the window title as a player would see it, used for matching
 * @param containerSlots   how many slots belong to the container itself, before the player's own
 *                         inventory begins. Derived from the contents, so -1 until they arrive
 * @param contentsReceived whether the server has sent the slot contents yet. A window is opened
 *                         first and filled a moment later, and clicking in between does nothing
 */
public record ContainerInfo(
        int containerId,
        String type,
        String title,
        int containerSlots,
        boolean contentsReceived) {

    /** Slots belonging to the player's own inventory within this window: 27 main plus 9 hotbar. */
    public static final int PLAYER_INVENTORY_SLOTS = 36;

    public boolean knowsLayout() {
        return containerSlots >= 0;
    }

    public String describe() {
        return "'" + title + "' (" + type + ", id " + containerId
                + (knowsLayout() ? ", " + containerSlots + " slots" : ", contents pending") + ")";
    }
}
