package net.anweisen.chronit.core.config;

import net.anweisen.chronit.core.driver.SlotClick;

/**
 * Clicks a slot in the container the server has opened.
 *
 * <p>Written for the common case — a plugin menu opened by a command, where one slot is the button
 * you want — so {@code slot} alone is usually enough:
 *
 * <pre>{@code
 * - command: "shop"
 *   waitFor: { screen: "(?i)shop", timeout: 5s }
 * - click: { slot: 13 }
 * }</pre>
 *
 * @param slot      zero-based index within {@code inventory}
 * @param inventory which half of the window the index counts within. Defaults to the opened
 *                  container, so menu slots are numbered from 0 regardless of the menu's size
 */
public record ClickConfig(
        Integer slot,
        SlotClick.InventoryPart inventory,
        SlotClick.ClickButton button,
        SlotClick.ClickMode mode) {

    public SlotClick toSlotClick() {
        return new SlotClick(
                inventory != null ? inventory : SlotClick.InventoryPart.CONTAINER,
                slot != null ? slot : 0,
                button != null ? button : SlotClick.ClickButton.LEFT,
                mode != null ? mode : SlotClick.ClickMode.PICKUP);
    }
}
