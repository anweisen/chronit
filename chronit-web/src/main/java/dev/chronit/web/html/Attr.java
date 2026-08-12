package dev.chronit.web.html;

/**
 * An attribute.
 *
 * <p>Also a {@link Node} so that attributes and children can share one varargs list, which is what
 * lets an element read like the markup it produces.
 */
public record Attr(String name, String value) implements Node {

    /** A boolean attribute such as {@code disabled}, rendered without a value. */
    public static Attr flag(String name) {
        return new Attr(name, null);
    }

    @Override
    public void render(StringBuilder out) {
        out.append(' ').append(name);
        if (value != null) {
            out.append("=\"");
            Node.escapeInto(out, value);
            out.append('"');
        }
    }
}
