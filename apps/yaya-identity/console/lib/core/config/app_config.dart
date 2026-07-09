/// Runtime configuration for the console. Values come from `--dart-define`s so
/// the same build points at any environment.
class AppConfig {
  const AppConfig({
    required this.issuerBaseUrl,
    required this.clientId,
    required this.redirectUri,
  });

  /// yaya-identity base URL, e.g. `https://identity.example.com`.
  final String issuerBaseUrl;

  /// The public console client registered in yaya-identity (auth-code + PKCE).
  final String clientId;

  /// This app's OAuth redirect URI (must match the registered client).
  final String redirectUri;

  /// Origin only (scheme://host[:port]) — the Dio base URL. When the service is
  /// hosted under a path (e.g. `…/identity`), that prefix is applied per-request
  /// by the base-path interceptor rather than baked into the Dio base URL, so a
  /// leading-slash path can't accidentally drop it.
  String get apiOrigin {
    final u = Uri.parse(issuerBaseUrl);
    final port = u.hasPort ? ':${u.port}' : '';
    return '${u.scheme}://${u.host}$port';
  }

  /// The path prefix the service lives under — `/identity` for a subpath deploy,
  /// or `''` at the root of its origin.
  String get basePath {
    final p = Uri.parse(issuerBaseUrl).path;
    return p.endsWith('/') ? p.substring(0, p.length - 1) : p;
  }

  static const AppConfig fromEnv = AppConfig(
    issuerBaseUrl: String.fromEnvironment(
      'IDENTITY_BASE_URL',
      defaultValue: 'http://localhost:8090',
    ),
    clientId: String.fromEnvironment('CONSOLE_CLIENT_ID', defaultValue: 'yaya-console'),
    redirectUri: String.fromEnvironment(
      'CONSOLE_REDIRECT_URI',
      defaultValue: 'http://localhost:5000/callback',
    ),
  );
}
