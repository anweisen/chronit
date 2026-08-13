package net.anweisen.chronit.driver.mcpl;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Flattens chat components.
 *
 * <p>Chat arrives as a tree of styled parts, sometimes with translation keys resolved client-side.
 * Patterns in the configuration are written against what a player sees, so matching happens on the
 * plain rendering; the JSON is kept alongside for the log, where colour and hover text occasionally
 * matter for working out which plugin sent something.
 */
final class Components {

    private Components() {
    }

    static String plain(Component component) {
        if (component == null) {
            return "";
        }
        try {
            return PlainTextComponentSerializer.plainText().serialize(component);
        } catch (RuntimeException e) {
            // A malformed component from a server must never take a session down.
            return component.toString();
        }
    }

    static String json(Component component) {
        if (component == null) {
            return null;
        }
        try {
            return GsonComponentSerializer.gson().serialize(component);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
