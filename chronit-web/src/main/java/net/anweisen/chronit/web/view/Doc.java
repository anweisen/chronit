package net.anweisen.chronit.web.view;

import net.anweisen.chronit.web.html.Attr;
import net.anweisen.chronit.web.html.Node;

import java.util.List;

import static net.anweisen.chronit.web.html.H.a;
import static net.anweisen.chronit.web.html.H.attr;
import static net.anweisen.chronit.web.html.H.body;
import static net.anweisen.chronit.web.html.H.button;
import static net.anweisen.chronit.web.html.H.cls;
import static net.anweisen.chronit.web.html.H.div;
import static net.anweisen.chronit.web.html.H.head;
import static net.anweisen.chronit.web.html.H.header;
import static net.anweisen.chronit.web.html.H.href;
import static net.anweisen.chronit.web.html.H.html;
import static net.anweisen.chronit.web.html.H.link;
import static net.anweisen.chronit.web.html.H.meta;
import static net.anweisen.chronit.web.html.H.script;
import static net.anweisen.chronit.web.html.H.span;
import static net.anweisen.chronit.web.html.H.text;
import static net.anweisen.chronit.web.html.H.title;

/** The page shell: head, the bar across the top, and the toast host. */
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
        return page(pageTitle, subtitle, assetVersion, rootPath, bodyAttrs,
                Node.fragment(content), Node.empty());
    }

    /**
     * @param overlays nodes placed after the main content — dialogs and the like, which must sit
     *                 outside the page flow rather than inside a section of it
     */
    public static String page(String pageTitle,
                              String subtitle,
                              String assetVersion,
                              String rootPath,
                              List<Attr> bodyAttrs,
                              Node content,
                              Node overlays) {
        Node document = html(attr("lang", "en"),
                head(
                        meta(attr("charset", "utf-8")),
                        meta(attr("name", "viewport"),
                                attr("content", "width=device-width, initial-scale=1, viewport-fit=cover")),
                        meta(attr("name", "color-scheme"), attr("content", "dark light")),
                        meta(attr("name", "robots"), attr("content", "noindex, nofollow")),
                        title(text(pageTitle)),
                        link(attr("rel", "stylesheet"), href(rootPath + "assets/app.css?v=" + assetVersion)),
                        // Applied before first paint so a reader never sees the wrong theme flash.
                        script(Node.raw("(()=>{try{const t=localStorage.getItem('chronit-theme');"
                                + "if(t)document.documentElement.setAttribute('data-theme',t);}catch(e){}})();"))),
                body(Node.fragment(bodyAttrs.toArray(Node[]::new)),
                        header(cls("topbar"),
                                div(cls("topbar__inner"),
                                        a(cls("brand"), href(rootPath),
                                                span(cls("brand__mark"), attr("aria-hidden", "true")),
                                                span(cls("brand__name"), text("chronit"))),
                                        span(cls("brand__meta"), text(subtitle)),
                                        div(cls("topbar__spacer")),
                                        // The connection light. It says whether what is on screen
                                        // is current, which on a page that no longer polls is the
                                        // one thing a reader cannot otherwise tell.
                                        span(cls("link-state"), attr("data-live", ""),
                                                attr("data-state", "connecting"),
                                                attr("title", "Live connection to the daemon"),
                                                span(cls("link-state__beacon"), attr("aria-hidden", "true")),
                                                span(cls("link-state__word"), attr("data-live-word", ""),
                                                        text("connecting"))),
                                        button(cls("icon-btn"), attr("type", "button"),
                                                attr("data-theme-toggle", ""),
                                                attr("aria-label", "Switch between light and dark"),
                                                Ui.icon("theme")))),
                        content,
                        overlays,
                        div(cls("toasts"), attr("data-toasts", ""), attr("aria-live", "polite")),
                        script(attr("src", rootPath + "assets/app.js?v=" + assetVersion),
                                attr("defer", null))));

        return "<!doctype html>" + document.toHtml();
    }
}
