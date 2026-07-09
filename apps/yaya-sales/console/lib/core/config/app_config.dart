/// Runtime configuration for the console. Values come from `--dart-define`s so
/// the same build points at any environment.
class AppConfig {
  const AppConfig({
    required this.identityBaseUrl,
    required this.salesBaseUrl,
    required this.clientId,
    required this.redirectUri,
  });

  /// yaya-identity base URL (the OIDC issuer that mints operator JWTs).
  final String identityBaseUrl;

  /// yaya-sales base URL (the admin API this console drives).
  final String salesBaseUrl;

  /// The public console client registered in yaya-identity (auth-code + PKCE).
  final String clientId;

  /// This app's OAuth redirect URI (must match the registered client).
  final String redirectUri;

  /// Origin only (scheme://host[:port]) — the Dio base URL. A subpath deploy
  /// (e.g. `…/sales`) is applied per-request by the base-path interceptor so a
  /// leading-slash path can't accidentally drop it.
  static String originOf(String baseUrl) {
    final u = Uri.parse(baseUrl);
    final port = u.hasPort ? ':${u.port}' : '';
    return '${u.scheme}://${u.host}$port';
  }

  static String basePathOf(String baseUrl) {
    final p = Uri.parse(baseUrl).path;
    return p.endsWith('/') ? p.substring(0, p.length - 1) : p;
  }

  String get salesOrigin => originOf(salesBaseUrl);
  String get salesBasePath => basePathOf(salesBaseUrl);
  String get identityOrigin => originOf(identityBaseUrl);
  String get identityBasePath => basePathOf(identityBaseUrl);

  static const AppConfig fromEnv = AppConfig(
    identityBaseUrl: String.fromEnvironment(
      'IDENTITY_BASE_URL',
      defaultValue: 'http://localhost:8090',
    ),
    salesBaseUrl: String.fromEnvironment(
      'SALES_API_BASE_URL',
      defaultValue: 'http://localhost:8092',
    ),
    clientId: String.fromEnvironment('CONSOLE_CLIENT_ID', defaultValue: 'yaya-console'),
    redirectUri: String.fromEnvironment(
      'CONSOLE_REDIRECT_URI',
      defaultValue: 'http://localhost:8093/callback',
    ),
  );
}
