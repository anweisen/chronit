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
import static dev.chronit.web.html.H.text;
import static dev.chronit.web.html.H.type;

/** The device code sign-in page, and the token gate. */
public final class LoginView {

    private LoginView() {
    }

    /**
     * The account sign-in page.
     *
     * <p>Only a frame: the script fills in the code, the link and the progress, polling until
     * Microsoft confirms. That replaces the old meta-refresh, which reloaded the whole page every
     * few seconds and threw away scroll position and focus with it.
     */
    public static String render(String accountId, String subtitle, String assetVersion) {
        return Doc.page("Sign in — chronit", subtitle, assetVersion, "../../",
                List.of(attr("data-login-account", accountId)),
                main(cls("login"),
                        div(cls("login__card"),
                                h1(cls("login__title"), text("Sign in as " + accountId)),
                                p(cls("login__lead"),
                                        text("Microsoft will show a short code to enter on any device "
                                                + "with a browser. Nothing is typed here.")),
                                div(attr("data-login-state", ""),
                                        button(cls("btn btn--primary"), type("button"),
                                                attr("data-start-login", ""),
                                                text("Start sign-in")))),
                        p(cls("login__lead"),
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
                main(cls("login"),
                        form(cls("login__card"), attr("method", "post"), attr("action", "session"),
                                h1(cls("login__title"), text("chronit")),
                                p(cls("login__lead"),
                                        text(rejected
                                                ? "That token was not accepted. Try again."
                                                : "Enter the access token from your configuration.")),
                                div(cls("field"),
                                        label(attr("for", "token"), text("Access token")),
                                        input(attr("id", "token"), name("token"), type("password"),
                                                attr("autocomplete", "current-password"),
                                                attr("autofocus", null))),
                                button(cls("btn btn--primary"), type("submit"), text("Continue")))));
    }
}
