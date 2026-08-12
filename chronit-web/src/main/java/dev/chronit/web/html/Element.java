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
        for (Node node : nodes) {
            if (node instanceof Attr attribute) {
                attributes.add(attribute);
            } else {
                children.add(node);
            }
        }
        return new Element(tag, attributes, children);
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
