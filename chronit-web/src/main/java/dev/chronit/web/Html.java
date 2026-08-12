package dev.chronit.web;

/**
 * Minimal HTML assembly.
 *
 * <p>Everything user-visible on these pages originates outside the process — server MOTDs, kick
 * reasons, chat lines, profile names — so {@link #escape} is applied to every interpolated value
 * without exception. A server operator choosing a name containing a script tag should not be able
 * to reach into the dashboard.
 */
final class Html {

    private Html() {
    }

    static String escape(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        StringBuilder out = new StringBuilder(text.length() + 16);
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
        return out.toString();
    }

    static String page(String title, String body) {
        return page(title, body, null);
    }

    /**
     * @param refreshSeconds when set, the page reloads itself on this interval — used while polling
     *                       a device code login, which completes out of band
     */
    static String page(String title, String body, Integer refreshSeconds) {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  %s<title>%s</title>
                  <style>%s</style>
                </head>
                <body>
                  <header><h1><a href="./">chronit</a></h1></header>
                  <main>%s</main>
                </body>
                </html>
                """.formatted(
                refreshSeconds != null
                        ? "<meta http-equiv=\"refresh\" content=\"" + refreshSeconds + "\">"
                        : "",
                escape(title), STYLE, body);
    }

    static String badge(boolean good, String text) {
        return "<span class=\"badge " + (good ? "ok" : "bad") + "\">" + escape(text) + "</span>";
    }

    private static final String STYLE = """
            :root { color-scheme: light dark; --fg: #1a1a1a; --bg: #fdfdfc; --muted: #6b6b6b;
                    --line: #e0e0dc; --ok: #1f7a3d; --bad: #a52a2a; --accent: #35507a; }
            @media (prefers-color-scheme: dark) {
              :root { --fg: #e8e8e6; --bg: #16171a; --muted: #9a9a97; --line: #2c2e33;
                      --ok: #5fbf7f; --bad: #e07a7a; --accent: #8ab0e8; }
            }
            * { box-sizing: border-box; }
            body { margin: 0; font: 15px/1.5 ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif;
                   color: var(--fg); background: var(--bg); }
            header { padding: 1rem 1.5rem; border-bottom: 1px solid var(--line); }
            h1 { margin: 0; font-size: 1.1rem; letter-spacing: .02em; }
            h1 a { color: var(--fg); text-decoration: none; }
            h2 { font-size: .8rem; text-transform: uppercase; letter-spacing: .08em;
                 color: var(--muted); margin: 2rem 0 .5rem; }
            main { padding: 0 1.5rem 3rem; max-width: 60rem; }
            table { width: 100%; border-collapse: collapse; font-size: .9rem; }
            th { text-align: left; font-weight: 600; color: var(--muted); font-size: .75rem;
                 text-transform: uppercase; letter-spacing: .05em; padding: .4rem .6rem .4rem 0;
                 border-bottom: 1px solid var(--line); }
            td { padding: .5rem .6rem .5rem 0; border-bottom: 1px solid var(--line);
                 vertical-align: top; }
            td.wrap { word-break: break-word; }
            .badge { display: inline-block; padding: .05rem .45rem; border-radius: 999px;
                     font-size: .75rem; font-weight: 600; }
            .badge.ok { background: color-mix(in srgb, var(--ok) 18%, transparent); color: var(--ok); }
            .badge.bad { background: color-mix(in srgb, var(--bad) 18%, transparent); color: var(--bad); }
            .muted { color: var(--muted); }
            code, .mono { font-family: ui-monospace, "SF Mono", Menlo, Consolas, monospace; font-size: .85em; }
            button { font: inherit; padding: .25rem .7rem; border: 1px solid var(--line);
                     border-radius: .3rem; background: transparent; color: var(--accent); cursor: pointer; }
            button:hover { border-color: var(--accent); }
            .card { border: 1px solid var(--line); border-radius: .4rem; padding: 1rem 1.2rem; margin: 1rem 0; }
            .code { font-family: ui-monospace, monospace; font-size: 1.6rem; letter-spacing: .12em;
                    padding: .4rem 0; }
            a { color: var(--accent); }
            """;
}
