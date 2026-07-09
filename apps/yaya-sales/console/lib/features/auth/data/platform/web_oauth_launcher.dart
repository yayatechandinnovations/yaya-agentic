import 'package:web/web.dart' as web;

import 'oauth_launcher.dart';

/// Web implementation — a full-page navigation to the authorization endpoint.
/// Isolated here so nothing else (and no test) pulls in web-only libraries.
class WebOAuthLauncher implements OAuthLauncher {
  @override
  void redirect(String url) => web.window.location.assign(url);
}
