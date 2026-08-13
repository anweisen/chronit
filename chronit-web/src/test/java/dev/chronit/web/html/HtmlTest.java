package dev.chronit.web.html;

import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.chronit.web.html.H.attr;
import static dev.chronit.web.html.H.cls;
import static dev.chronit.web.html.H.div;
import static dev.chronit.web.html.H.input;
import static dev.chronit.web.html.H.span;
import static dev.chronit.web.html.H.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reason this builder exists is that the dashboard displays strings chosen by other people —
 * server names, kick reasons, menu titles, chat. These check that none of them can become markup.
 */
class HtmlTest {

    private static final String ATTACK = "<script>alert('x')</script>";

    @Test
    void textContentIsEscaped() {
        assertEquals("<div>&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;</div>",
                div(text(ATTACK)).toHtml());
    }

    @Test
    void attributeValuesAreEscaped() {
        String html = div(attr("title", "\" onmouseover=\"alert(1)")).toHtml();
        assertEquals("<div title=\"&quot; onmouseover=&quot;alert(1)\"></div>", html);
        assertFalse(html.contains("onmouseover=\"alert"), html);
    }

    @Test
    void ampersandsAreEscapedExactlyOnce() {
        assertEquals("<span>a &amp;amp; b</span>", span(text("a &amp; b")).toHtml());
    }

    @Test
    void nullTextIsEmptyRatherThanTheWordNull() {
        assertEquals("<span></span>", span(text(null)).toHtml());
    }

    @Test
    void attributesAndChildrenSeparateFromOneList() {
        assertEquals("<div class=\"card\" id=\"x\"><span>hi</span></div>",
                div(cls("card"), attr("id", "x"), span(text("hi"))).toHtml());
    }

    @Test
    void voidElementsGetNoClosingTag() {
        assertEquals("<input type=\"text\">", input(attr("type", "text")).toHtml());
    }

    @Test
    void booleanAttributesRenderWithoutAValue() {
        assertEquals("<input disabled>", input(Attr.flag("disabled")).toHtml());
    }

    @Test
    void eachMapsACollectionIntoSiblings() {
        assertEquals("<div><span>a</span><span>b</span></div>",
                div(Node.each(List.of("a", "b"), value -> span(text(value)))).toHtml());
    }

    @Test
    void conditionalsRenderNothingWhenFalse() {
        assertEquals("<div></div>", div(Node.when(false, () -> span(text("no")))).toHtml());
    }

    /**
     * Regression: attributes gathered into a list and handed over as one fragment used to be
     * classified as children, and rendered as visible text inside the element.
     */
    @Test
    void attributesInsideAFragmentStillBecomeAttributes() {
        Node grouped = Node.fragment(attr("data-dashboard", "true"), attr("data-runs", "7"));
        String html = div(grouped, span(text("body"))).toHtml();

        assertEquals("<div data-dashboard=\"true\" data-runs=\"7\"><span>body</span></div>", html);
        assertFalse(html.contains(">data-dashboard"), "attributes must not leak into the text: " + html);
    }

    @Test
    void nestedFragmentsAreFlattenedToo() {
        Node inner = Node.fragment(attr("id", "x"), text("hi"));
        assertEquals("<div id=\"x\">hi</div>", div(Node.fragment(inner)).toHtml());
    }

    @Test
    void rawIsTheOnlyWayToEmitMarkupAndIsNotUsedForInput() {
        // Deliberate escape hatch, used only for the hand-written icons in this package.
        assertEquals("<div><svg/></div>", div(Node.raw("<svg/>")).toHtml());
    }
}
