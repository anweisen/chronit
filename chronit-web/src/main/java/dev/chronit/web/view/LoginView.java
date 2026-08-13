package dev.chronit.web.view;

import dev.chronit.web.html.Node;

import java.util.List;

import static dev.chronit.web.html.H.a;
import static dev.chronit.web.html.H.attr;
import static dev.chronit.web.html.H.button;
import static dev.chronit.web.html.H.cls;
import static dev.chronit.web.html.H.div;
import static dev.chronit.web.html.H.form;
import static dev.chronit.web.html.H.h1;
import static dev.chronit.web.html.H.href;
import static dev.chronit.web.html.H.input;
import static dev.chronit.web.html.H.label;
import static dev.chronit.web.html.H.main;
import static dev.chronit.web.html.H.name;
import static dev.chronit.web.html.H.p;
import static dev.chronit.web.html.H.span;
import static dev.chronit.web.html.H.text;
import static dev.chronit.web.html.H.type;

/**
 * The device code sign-in page and the token gate.
 *
 * <p>Both are built from the same pieces as the dashboard — the same card, the same eyebrow-above-
 * title pairing, the same buttons — so arriving here does not feel like arriving somewhere else.
 */
public final class LoginView {

    private LoginView() {
    }

    /**
     * The account sign-in page.
     *
     * <p>Only a frame: the script fills in the code, the link and the progress, polling until
     * Microsoft confirms. That replaces a meta-refresh, which reloaded the whole page every few
     * seconds and threw away scroll position and focus with it.
     */
    public static String render(String accountId, String subtitle, String assetVersion) {
        return Doc.page("Sign in — chronit", subtitle, assetVersion, "../../",
                List.of(attr("data-login-account", accountId)),
                main(cls("centred"),
                        div(cls("panel-card"),
                                div(cls("panel-card__head"),
                                        span(cls("panel-card__icon"), Ui.icon("key")),
                                        p(cls("panel-card__eyebrow"), text("Microsoft account")),
                                        h1(cls("panel-card__title"), text(accountId))),
                                // Replaced wholesale by the script as the flow progresses. The
                                // button carries the account id because the handler is delegated,
                                // so it keeps working however often this is re-rendered.
                                div(cls("panel-card__body"), attr("data-login-state", ""),
                                        p(cls("panel-card__lead"),
                                                text("Microsoft will show a short code to enter on "
                                                        + "any device with a browser.")),
                                        button(cls("btn btn--primary btn--lg"), type("button"),
                                                attr("data-start-login", accountId),
                                                text("Start sign-in")))),
                        p(cls("centred__back"),
                                a(href("../../"), text("Back to dashboard")))));
    }

    /**
     * The token gate.
     *
     * <p>Submitting posts the token so it is exchanged for a cookie, rather than being pasted into
     * a URL where it would end up in browser history and any referrer header the page emits.
     */
    public static String tokenGate(String assetVersion, boolean rejected) {
        return Doc.page("chronit", "authentication required", assetVersion, "./",
                List.of(),
                main(cls("centred"),
                        form(cls("panel-card"), attr("method", "post"), attr("action", "session"),
                                div(cls("panel-card__head"),
                                        span(cls("panel-card__icon"), Ui.icon("key")),
                                        p(cls("panel-card__eyebrow"), text("chronit")),
                                        h1(cls("panel-card__title"), text("Sign in"))),
                                div(cls("panel-card__body"),
                                        rejected
                                                ? p(cls("panel-card__lead panel-card__lead--bad"),
                                                text("That token was not accepted."))
                                                : p(cls("panel-card__lead"),
                                                text("Enter the access token from your configuration.")),
                                        div(cls("field"),
                                                label(attr("for", "token"), text("Access token")),
                                                input(attr("id", "token"), name("token"), type("password"),
                                                        attr("autocomplete", "current-password"),
                                                        attr("autofocus", null))),
                                        button(cls("btn btn--primary btn--lg"), type("submit"),
                                                text("Continue"))))));
    }
}
