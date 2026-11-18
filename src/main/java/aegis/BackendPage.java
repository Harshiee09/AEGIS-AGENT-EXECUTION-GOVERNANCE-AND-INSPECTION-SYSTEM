package aegis;

/** Public entry page for the local API; no credentials or request data. */
final class BackendPage {
    private BackendPage() { }
    static final String HTML = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>AEGIS | Backend running</title>
              <style>
                :root { color-scheme: dark; font-family: system-ui, sans-serif; }
                * { box-sizing: border-box; }
                body { margin: 0; background: #101827; color: #ecf1f8; }
                main { max-width: 760px; margin: 8vh auto; padding: 32px; }
                .brand { color: #b5c3da; letter-spacing: .18em; font-size: 13px; }
                h1 { font-size: clamp(32px, 6vw, 48px); margin: 20px 0 16px; letter-spacing: -.03em; }
                p { color: #b5c3da; line-height: 1.65; }
                .status { color: #9ee4c0; }
                section { margin-top: 32px; padding-top: 24px; border-top: 1px solid #344055; }
                h2 { font-size: 19px; margin: 0 0 12px; }
                a { color: #a4c4ff; text-underline-offset: 4px; }
                code { display: block; background: #192338; padding: 16px; border-radius: 6px; overflow-wrap: anywhere; font-size: 13px; }
              </style>
            </head>
            <body>
              <main>
                <div class="brand">AEGIS</div>
                <h1>Your Java backend is running.</h1>
                <div class="status">● Service online</div>
                <p>The file governance API is available on this local server. File requests, approvals,
                   and audit events are stored in MySQL. Ollama models are available through the API.</p>
                <p><a href="/health">Check service health</a></p>
                <section>
                  <h2>Open the desktop dashboard</h2>
                  <p>AEGIS currently uses a Java Swing interface. Run this command from the Aegis project folder:</p>
                  <code>powershell -ExecutionPolicy Bypass -File .\\scripts\\run-dashboard.ps1</code>
                </section>
                <section>
                  <h2>API access</h2>
                  <p>API endpoints require the local agent or admin token. Use the project API helper to connect:</p>
                  <code>powershell -ExecutionPolicy Bypass -File .\\scripts\\api.ps1 -Path /api/operations</code>
                </section>
              </main>
            </body>
            </html>
            """;
}
