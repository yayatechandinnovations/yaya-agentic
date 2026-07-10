package com.yayatechandinnovations.yayaidentity.tokens;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * The human credential form backing the auth-code flow — Spring's auto-generated
 * page replaced with a branded one. Deliberately dependency-free (no template
 * engine for one page): static HTML with the CSRF token and the error/logout
 * banners injected. POST /login is still processed by the form-login filter.
 *
 * <p>The palette matches assets/yaya-identity-logo.png (deep navy, magenta→cyan
 * gradient). The logo itself is loaded from the console SPA's asset bundle via a
 * relative URL (same origin, context-path safe); if the SPA isn't co-deployed
 * the {@code onerror} fallback swaps in a gradient monogram.
 */
@RestController
public class LoginPageController {

    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    public String loginPage(HttpServletRequest request) {
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        String banner = "";
        if (request.getParameter("error") != null) {
            banner = "<div class=\"banner error\">Wrong email or password — or the account is locked "
                    + "after too many attempts. Try again in a moment.</div>";
        } else if (request.getParameter("logout") != null) {
            banner = "<div class=\"banner ok\">You have been signed out.</div>";
        }
        String csrfInput = csrf == null ? ""
                : "<input type=\"hidden\" name=\"" + HtmlUtils.htmlEscape(csrf.getParameterName())
                        + "\" value=\"" + HtmlUtils.htmlEscape(csrf.getToken()) + "\">";
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Sign in — Yaya Identity</title>
                <style>
                  :root { color-scheme: dark; }
                  * { box-sizing: border-box; margin: 0; }
                  body {
                    font-family: -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
                    min-height: 100vh; display: flex; align-items: center; justify-content: center;
                    background: radial-gradient(1100px 600px at 20%% -10%%, #17204d 0%%, #0a0e27 55%%) #0a0e27;
                    padding: 24px;
                  }
                  .card {
                    background: #10163a; border: 1px solid rgba(255,255,255,.08);
                    border-radius: 16px; width: 100%%; max-width: 400px;
                    padding: 36px; box-shadow: 0 24px 60px rgba(0,0,0,.5);
                  }
                  .logo { display: block; width: 200px; margin: 0 auto 4px; }
                  .brand-fallback {
                    display: none; width: 46px; height: 46px; border-radius: 12px; margin: 0 auto 10px;
                    background: linear-gradient(135deg, #c026d3, #7c3aed, #06b6d4);
                    align-items: center; justify-content: center;
                    color: #fff; font-weight: 700; font-size: 20px;
                  }
                  .sub { color: #8b93b8; font-size: 13.5px; margin: 6px 0 26px; text-align: center; }
                  label { display: block; font-size: 13px; font-weight: 600; color: #c0c6e4; margin: 14px 0 6px; }
                  input[type=text], input[type=password] {
                    width: 100%%; padding: 11px 12px; font-size: 15px; color: #eef0fb;
                    border: 1.5px solid #2a325e; border-radius: 9px; outline: none;
                    transition: border-color .15s, box-shadow .15s; background: #0c1130;
                  }
                  input:focus { border-color: #7c3aed; box-shadow: 0 0 0 3px rgba(124,58,237,.25); }
                  button {
                    width: 100%%; margin-top: 24px; padding: 12px; font-size: 15px; font-weight: 600;
                    color: #fff; background: linear-gradient(90deg, #c026d3, #7c3aed, #06b6d4);
                    border: 0; border-radius: 9px; cursor: pointer; transition: filter .15s;
                  }
                  button:hover { filter: brightness(1.12); }
                  .banner { padding: 11px 13px; border-radius: 9px; font-size: 13.5px; margin-bottom: 18px; }
                  .banner.error { background: rgba(244,63,94,.12); color: #fda4af; border: 1px solid rgba(244,63,94,.35); }
                  .banner.ok { background: rgba(6,182,212,.12); color: #67e8f9; border: 1px solid rgba(6,182,212,.35); }
                  .foot { margin-top: 22px; text-align: center; color: #5c6490; font-size: 12px; }
                </style>
                </head>
                <body>
                  <main class="card">
                    <img class="logo" src="assets/assets/yaya-identity-logo.png" alt="Yaya Identity"
                         onerror="this.style.display='none';document.getElementById('bf').style.display='flex';">
                    <div id="bf" class="brand-fallback">yi</div>
                    <p class="sub">Digital you, secure always — sign in to continue.</p>
                    %s
                    <form method="post" action="login">
                      <label for="username">Email</label>
                      <input type="text" id="username" name="username" autocomplete="username"
                             autofocus required placeholder="you@example.com">
                      <label for="password">Password</label>
                      <input type="password" id="password" name="password"
                             autocomplete="current-password" required placeholder="••••••••••••">
                      %s
                      <button type="submit">Sign in</button>
                    </form>
                    <p class="foot">Protected by Yaya Identity — accounts lock after repeated failures.</p>
                  </main>
                </body>
                </html>
                """.formatted(banner, csrfInput);
    }
}
