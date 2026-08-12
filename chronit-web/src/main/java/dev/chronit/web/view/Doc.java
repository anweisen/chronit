package dev.chronit.web.view;

import dev.chronit.web.html.Attr;
import dev.chronit.web.html.Node;

import java.util.List;

import static dev.chronit.web.html.H.a;
import static dev.chronit.web.html.H.attr;
import static dev.chronit.web.html.H.body;
import static dev.chronit.web.html.H.button;
import static dev.chronit.web.html.H.cls;
import static dev.chronit.web.html.H.div;
import static dev.chronit.web.html.H.head;
import static dev.chronit.web.html.H.header;
import static dev.chronit.web.html.H.href;
import static dev.chronit.web.html.H.html;
import static dev.chronit.web.html.H.link;
import static dev.chronit.web.html.H.meta;
import static dev.chronit.web.html.H.script;
import static dev.chronit.web.html.H.span;
import static dev.chronit.web.html.H.text;
import static dev.chronit.web.html.H.title;

/** The page shell: head, header bar, and the toast host. */
public final class Doc {

    private Doc() {
    }

    /**
     * @param assetVersion cache-busting token, so the stylesheet and script can be served with a
     *                     long max-age and still update the moment the jar changes
     * @param bodyAttrs    flags the script reads to decide what this page is
     */
    public static String page(String pageTitle,
                              String subtitle,
                              String assetVersion,
                              String rootPath,
                              List<Attr> bodyAttrs,
                              Node... content) {
        Node document = html(attr("lang", "en"),
                head(
                        meta(attr("charset", "utf-8")),
                        meta(attr("name", "viewport"),
                                attr("content", "width=device-width, initial-scale=1, viewport-fit=cover")),
                        meta(attr("name", "color-scheme"), attr("content", "light dark")),
                        meta(attr("name", "robots"), attr("content", "noindex, nofollow")),
                        title(text(pageTitle)),
                        link(attr("rel", "stylesheet"), href(rootPath + "assets/app.css?v=" + assetVersion)),
                        // Applied before first paint so a dark-mode reader never sees a light flash.
                        script(Node.raw("(()=>{try{const t=localStorage.getItem('chronit-theme');"
                                + "if(t)document.documentElement.setAttribute('data-theme',t);}catch(e){}})();"))),
                body(Node.fragment(bodyAttrs.toArray(Node[]::new)),
                        header(cls("topbar"),
                                div(cls("topbar__inner"),
                                        div(cls("brand"),
                                                a(cls("brand__name"), href(rootPath), text("chronit")),
                                                span(cls("brand__meta"), text(subtitle))),
                                        div(cls("topbar__spacer")),
                                        span(cls("live"), attr("data-live", ""), attr("data-state", "live"),
                                                span(cls("live__dot")),
                                                span(text("live"))),
                                        button(cls("icon-btn"), attr("type", "button"),
                                                attr("data-theme-toggle", ""),
                                                attr("aria-label", "Switch between light and dark"),
                                                Ui.icon("theme")))),
                        Node.fragment(content),
                        div(cls("toasts"), attr("data-toasts", ""), attr("aria-live", "polite")),
                        script(attr("src", rootPath + "assets/app.js?v=" + assetVersion),
                                attr("defer", null))));

        return "<!doctype html>" + document.toHtml();
    }
}
