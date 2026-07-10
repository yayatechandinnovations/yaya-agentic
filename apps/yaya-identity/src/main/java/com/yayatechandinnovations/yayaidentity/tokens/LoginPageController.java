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
                <title>Sign in — yaya-identity</title>
                <style>
                  :root { color-scheme: light; }
                  * { box-sizing: border-box; margin: 0; }
                  body {
                    font-family: -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
                    min-height: 100vh; display: flex; align-items: center; justify-content: center;
                    background: linear-gradient(135deg, #0f2027 0%%, #203a43 50%%, #2c5364 100%%);
                    padding: 24px;
                  }
                  .card {
                    background: #fff; border-radius: 16px; width: 100%%; max-width: 400px;
                    padding: 40px 36px; box-shadow: 0 24px 60px rgba(0,0,0,.35);
                  }
                  .brand { display: flex; align-items: center; gap: 12px; margin-bottom: 6px; }
                  .brand .dot {
                    width: 38px; height: 38px; border-radius: 10px; flex: none;
                    background: linear-gradient(135deg, #2c5364, #4ca1af);
                    display: flex; align-items: center; justify-content: center;
                    color: #fff; font-weight: 700; font-size: 18px;
                  }
                  h1 { font-size: 21px; color: #16232c; }
                  .sub { color: #6b7a86; font-size: 13.5px; margin: 6px 0 26px; }
                  label { display: block; font-size: 13px; font-weight: 600; color: #34454f; margin: 14px 0 6px; }
                  input[type=text], input[type=password] {
                    width: 100%%; padding: 11px 12px; font-size: 15px;
                    border: 1.5px solid #d4dde2; border-radius: 9px; outline: none;
                    transition: border-color .15s, box-shadow .15s; background: #fbfdfe;
                  }
                  input:focus { border-color: #4ca1af; box-shadow: 0 0 0 3px rgba(76,161,175,.18); }
                  button {
                    width: 100%%; margin-top: 24px; padding: 12px; font-size: 15px; font-weight: 600;
                    color: #fff; background: linear-gradient(135deg, #2c5364, #4ca1af);
                    border: 0; border-radius: 9px; cursor: pointer; transition: filter .15s;
                  }
                  button:hover { filter: brightness(1.08); }
                  .banner { padding: 11px 13px; border-radius: 9px; font-size: 13.5px; margin-bottom: 18px; }
                  .banner.error { background: #fdecec; color: #9c2b2b; border: 1px solid #f3c4c4; }
                  .banner.ok { background: #e9f7ef; color: #1d6f42; border: 1px solid #bfe5cd; }
                  .foot { margin-top: 22px; text-align: center; color: #93a3ad; font-size: 12px; }
                </style>
                </head>
                <body>
                  <main class="card">
                    <div class="brand"><div class="dot">yi</div><h1>yaya-identity</h1></div>
                    <p class="sub">Sign in to continue to your console.</p>
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
                    <p class="foot">Protected by yaya-identity — accounts lock after repeated failures.</p>
                  </main>
                </body>
                </html>
                """.formatted(banner, csrfInput);
    }
}
