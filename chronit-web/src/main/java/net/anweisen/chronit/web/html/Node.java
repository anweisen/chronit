package net.anweisen.chronit.web.html;

import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A piece of HTML.
 *
 * <p>The point of this type existing at all is escaping. Everything shown on these pages comes from
 * outside the process — server names, kick reasons, menu titles, chat, profile names — and the
 * previous approach of concatenating strings meant a single forgotten {@code escape()} call was an
 * injection hole. Here the only way to put content into a node is {@link #text}, which escapes, so
 * the mistake is not available to make. {@link Raw} exists for the handful of icons written by hand
 * in this package and is deliberately awkward to reach for.
 */
public sealed interface Node permits Element, Node.Text, Node.Raw, Node.Fragment, Attr {

    void render(StringBuilder out);

    /** Escaped text content. */
    record Text(String value) implements Node {
        @Override
        public void render(StringBuilder out) {
            escapeInto(out, value);
        }
    }

    /** Markup emitted verbatim. Only for literals written in this package, never for input. */
    record Raw(String markup) implements Node {
        @Override
        public void render(StringBuilder out) {
            out.append(markup);
        }
    }

    /** A list of nodes with no wrapping element. */
    record Fragment(List<Node> children) implements Node {
        @Override
        public void render(StringBuilder out) {
            children.forEach(child -> child.render(out));
        }
    }

    static Node text(Object value) {
        return new Text(value == null ? "" : value.toString());
    }

    static Node raw(String markup) {
        return new Raw(markup);
    }

    static Node empty() {
        return new Fragment(List.of());
    }

    static Node fragment(Node... children) {
        return new Fragment(List.of(children));
    }

    /** Maps a collection into sibling nodes. */
    static <T> Node each(Collection<T> items, Function<T, Node> renderer) {
        return new Fragment(items.stream().map(renderer).toList());
    }

    /** Renders {@code node} only when {@code condition} holds. */
    static Node when(boolean condition, Supplier<Node> node) {
        return condition ? node.get() : empty();
    }

    default String toHtml() {
        StringBuilder out = new StringBuilder(512);
        render(out);
        return out.toString();
    }

    static void escapeInto(StringBuilder out, String text) {
        if (text == null) {
            return;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
    }
}
