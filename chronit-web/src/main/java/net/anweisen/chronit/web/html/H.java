package net.anweisen.chronit.web.html;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Element and attribute factories.
 *
 * <p>Static-imported at the call site so a view reads close to the markup it produces:
 *
 * <pre>{@code
 * div(cls("card"),
 *     h2(text("Jobs")),
 *     p(cls("muted"), text(job.id())))
 * }</pre>
 */
public final class H {

    private H() {
    }

    // ------------------------------------------------------------------ elements

    public static Element el(String tag, Node... nodes) {
        return Element.of(tag, nodes);
    }

    public static Element html(Node... n) {
        return el("html", n);
    }

    public static Element head(Node... n) {
        return el("head", n);
    }

    public static Element body(Node... n) {
        return el("body", n);
    }

    public static Element title(Node... n) {
        return el("title", n);
    }

    public static Element meta(Node... n) {
        return el("meta", n);
    }

    public static Element link(Node... n) {
        return el("link", n);
    }

    public static Element script(Node... n) {
        return el("script", n);
    }

    public static Element header(Node... n) {
        return el("header", n);
    }

    public static Element main(Node... n) {
        return el("main", n);
    }

    public static Element section(Node... n) {
        return el("section", n);
    }

    public static Element article(Node... n) {
        return el("article", n);
    }

    public static Element footer(Node... n) {
        return el("footer", n);
    }

    public static Element nav(Node... n) {
        return el("nav", n);
    }

    public static Element div(Node... n) {
        return el("div", n);
    }

    public static Element span(Node... n) {
        return el("span", n);
    }

    public static Element p(Node... n) {
        return el("p", n);
    }

    public static Element a(Node... n) {
        return el("a", n);
    }

    public static Element button(Node... n) {
        return el("button", n);
    }

    public static Element form(Node... n) {
        return el("form", n);
    }

    public static Element input(Node... n) {
        return el("input", n);
    }

    public static Element label(Node... n) {
        return el("label", n);
    }

    public static Element h1(Node... n) {
        return el("h1", n);
    }

    public static Element h2(Node... n) {
        return el("h2", n);
    }

    public static Element h3(Node... n) {
        return el("h3", n);
    }

    public static Element ul(Node... n) {
        return el("ul", n);
    }

    public static Element ol(Node... n) {
        return el("ol", n);
    }

    public static Element li(Node... n) {
        return el("li", n);
    }

    public static Element table(Node... n) {
        return el("table", n);
    }

    public static Element thead(Node... n) {
        return el("thead", n);
    }

    public static Element tbody(Node... n) {
        return el("tbody", n);
    }

    public static Element tr(Node... n) {
        return el("tr", n);
    }

    public static Element th(Node... n) {
        return el("th", n);
    }

    public static Element td(Node... n) {
        return el("td", n);
    }

    public static Element code(Node... n) {
        return el("code", n);
    }

    public static Element strong(Node... n) {
        return el("strong", n);
    }

    public static Element time(Node... n) {
        return el("time", n);
    }

    public static Element details(Node... n) {
        return el("details", n);
    }

    public static Element summary(Node... n) {
        return el("summary", n);
    }

    public static Element dl(Node... n) {
        return el("dl", n);
    }

    public static Element dt(Node... n) {
        return el("dt", n);
    }

    public static Element dd(Node... n) {
        return el("dd", n);
    }

    // ------------------------------------------------------------------ attributes

    public static Attr attr(String name, Object value) {
        return new Attr(name, value == null ? "" : value.toString());
    }

    public static Attr cls(String value) {
        return attr("class", value);
    }

    public static Attr id(String value) {
        return attr("id", value);
    }

    public static Attr href(String value) {
        return attr("href", value);
    }

    public static Attr type(String value) {
        return attr("type", value);
    }

    public static Attr name(String value) {
        return attr("name", value);
    }

    public static Attr value(String value) {
        return attr("value", value);
    }

    public static Attr data(String suffix, Object value) {
        return attr("data-" + suffix, value);
    }

    public static Attr aria(String suffix, Object value) {
        return attr("aria-" + suffix, value);
    }

    public static Attr flag(String name) {
        return Attr.flag(name);
    }

    // ------------------------------------------------------------------ content

    public static Node text(Object value) {
        return Node.text(value);
    }

    public static <T> Node each(java.util.Collection<T> items,
                                java.util.function.Function<T, Node> renderer) {
        return Node.each(items, renderer);
    }

    public static Node when(boolean condition, java.util.function.Supplier<Node> node) {
        return Node.when(condition, node);
    }

    public static Node nothing() {
        return Node.empty();
    }

    /** Percent-encodes a value destined for a URL path segment. */
    public static String urlSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
