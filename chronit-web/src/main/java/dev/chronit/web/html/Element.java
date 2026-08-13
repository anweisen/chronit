package dev.chronit.web.html;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** An HTML element. Attributes and children arrive in one list and are separated here. */
public record Element(String tag, List<Attr> attributes, List<Node> children) implements Node {

    /** Elements that must not be given a closing tag. */
    private static final Set<String> VOID_TAGS =
            Set.of("area", "base", "br", "col", "embed", "hr", "img", "input",
                    "link", "meta", "param", "source", "track", "wbr");

    public static Element of(String tag, Node... nodes) {
        List<Attr> attributes = new ArrayList<>(4);
        List<Node> children = new ArrayList<>(nodes.length);
        collect(nodes, attributes, children);
        return new Element(tag, attributes, children);
    }

    /**
     * Sorts attributes from content.
     *
     * <p>Fragments are looked inside, because a fragment is a transparent grouping — the caller
     * meant "these nodes here". Without this, attributes assembled into a list and passed as one
     * fragment land in the child list instead, and {@code Fragment.render} then prints them as
     * visible text inside the element. That is not a hypothetical: it shipped, and put
     * {@code data-login-account="main"} on screen.
     */
    private static void collect(Node[] nodes, List<Attr> attributes, List<Node> children) {
        for (Node node : nodes) {
            switch (node) {
                case Attr attribute -> attributes.add(attribute);
                case Node.Fragment fragment ->
                        collect(fragment.children().toArray(Node[]::new), attributes, children);
                default -> children.add(node);
            }
        }
    }

    @Override
    public void render(StringBuilder out) {
        out.append('<').append(tag);
        attributes.forEach(attribute -> attribute.render(out));
        out.append('>');

        if (VOID_TAGS.contains(tag)) {
            return;
        }
        children.forEach(child -> child.render(out));
        out.append("</").append(tag).append('>');
    }
}
