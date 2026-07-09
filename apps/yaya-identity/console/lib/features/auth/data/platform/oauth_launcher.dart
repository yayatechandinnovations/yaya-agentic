/// Abstracts the browser navigation the OAuth redirect needs, so the repository
/// stays testable (the web implementation lives in `web_oauth_launcher.dart`,
/// which is the only file that imports `dart:html`).
abstract class OAuthLauncher {
  void redirect(String url);
}
