package net.anweisen.chronit.web.view;


import java.util.List;

import static net.anweisen.chronit.web.html.H.a;
import static net.anweisen.chronit.web.html.H.attr;
import static net.anweisen.chronit.web.html.H.button;
import static net.anweisen.chronit.web.html.H.cls;
import static net.anweisen.chronit.web.html.H.div;
import static net.anweisen.chronit.web.html.H.form;
import static net.anweisen.chronit.web.html.H.h1;
import static net.anweisen.chronit.web.html.H.href;
import static net.anweisen.chronit.web.html.H.input;
import static net.anweisen.chronit.web.html.H.label;
import static net.anweisen.chronit.web.html.H.main;
import static net.anweisen.chronit.web.html.H.name;
import static net.anweisen.chronit.web.html.H.p;
import static net.anweisen.chronit.web.html.H.span;
import static net.anweisen.chronit.web.html.H.text;
import static net.anweisen.chronit.web.html.H.type;

/**
 * The device code sign-in page and the token gate.
 *
 * <p>Both are built from the same pieces as the dashboard — the same rail, the same eyebrow above a
 * title, the same buttons — so arriving here does not feel like arriving somewhere else.
 */
public final class LoginView {

    private LoginView() {
    }

    /**
     * The account sign-in page.
     *
     * <p>Only a frame: the code, the link and the progress are filled in as they arrive on the
     * live stream. That replaced a two-second poll, which in turn replaced a meta-refresh that
     * reloaded the whole page and threw away scroll position and focus with it.
     */
    public static String render(String accountId, String subtitle, String assetVersion) {
        return Doc.page("Sign in — chronit", subtitle, assetVersion, "../../",
                List.of(attr("data-login-account", accountId)),
                main(cls("solo"),
                        div(cls("solo__sheet"),
                                Ui.rail(Ui.Tone.NEUTRAL),
                                div(cls("solo__head"),
                                        p(cls("solo__eyebrow"), text("Microsoft account")),
                                        h1(cls("solo__title"), text(accountId))),
                                // Replaced wholesale as the flow progresses. The button carries the
                                // account id because the handler is delegated, so it keeps working
                                // however often this is re-rendered.
                                div(cls("solo__body"), attr("data-login-state", ""),
                                        p(cls("solo__lead"),
                                                text("Microsoft will show a short code to enter on "
                                                        + "any device with a browser.")),
                                        button(cls("btn btn--primary btn--lg"), type("button"),
                                                attr("data-start-login", accountId),
                                                text("Start sign-in")))),
                        p(cls("solo__back"),
                                a(cls("link-action"), href("../../"),
                                        span(text("Back to dashboard")), Ui.icon("arrow")))));
    }

    /**
     * The token gate.
     *
     * <p>Submitting posts the token so it is exchanged for a cookie, rather than being pasted into
     * a URL where it would end up in browser history and any referrer header the page emits. That
     * same cookie is what authenticates the live stream afterwards.
     */
    public static String tokenGate(String assetVersion, boolean rejected) {
        return Doc.page("chronit", "authentication required", assetVersion, "./",
                List.of(),
                main(cls("solo"),
                        form(cls("solo__sheet"), attr("method", "post"), attr("action", "session"),
                                Ui.rail(rejected ? Ui.Tone.BAD : Ui.Tone.NEUTRAL),
                                div(cls("solo__head"),
                                        p(cls("solo__eyebrow"), text("chronit")),
                                        h1(cls("solo__title"), text("Sign in"))),
                                div(cls("solo__body"),
                                        rejected
                                                ? p(cls("solo__lead is-bad"),
                                                text("That token was not accepted."))
                                                : p(cls("solo__lead"),
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
