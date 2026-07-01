/// Public branding card for an entry point — mirrors the backend
/// `PublicChatDtos.EntryPointCard` (chat-channel-design.md §9). This is what the
/// QR scan resolves to and what themes the tile + chat header.
class EntryPointCard {
  final String handle;
  final String displayName;
  final String logoUrl;
  final String? accentColorHex;
  final String? welcomeText;
  final String defaultLocale;
  final String authMode; // ANONYMOUS_ONLY | AUTH_REQUIRED | AUTH_OPTIONAL
  final String authMechanism; // NONE | OIDC | RESOLVER — how a token is obtained
  final bool paused;
  final OidcLogin? oidc;

  const EntryPointCard({
    required this.handle,
    required this.displayName,
    required this.logoUrl,
    required this.authMode,
    required this.defaultLocale,
    this.authMechanism = 'NONE',
    this.accentColorHex,
    this.welcomeText,
    this.paused = false,
    this.oidc,
  });

  bool get requiresAuth => authMode == 'AUTH_REQUIRED';
  bool get optionalAuth => authMode == 'AUTH_OPTIONAL';

  /// Server-side transparent sign-in: the backend mints the JWT from the
  /// verified device phone. The client never handles a token and must NOT
  /// prompt for one — it just starts the session (chat-channel-auth-resolution-design.md §6).
  bool get usesResolver => authMechanism == 'RESOLVER';

  /// Interactive front-channel login (PKCE) at the tenant IdP — the only
  /// mechanism where the client must obtain and present a token itself. Until
  /// the PKCE launcher lands, this is the sole trigger for the `_SignInPrompt`
  /// paste-token shell (chat-channel-design.md §8.2).
  bool get usesOidc => authMechanism == 'OIDC';

  factory EntryPointCard.fromJson(Map<String, dynamic> j) => EntryPointCard(
        handle: j['handle'] as String,
        displayName: j['displayName'] as String,
        logoUrl: j['logoUrl'] as String,
        accentColorHex: j['accentColorHex'] as String?,
        welcomeText: j['welcomeText'] as String?,
        defaultLocale: (j['defaultLocale'] as String?) ?? 'en-US',
        authMode: (j['authMode'] as String?) ?? 'ANONYMOUS_ONLY',
        authMechanism: (j['authMechanism'] as String?) ?? 'NONE',
        paused: (j['paused'] as bool?) ?? false,
        oidc: j['oidc'] == null
            ? null
            : OidcLogin.fromJson(j['oidc'] as Map<String, dynamic>),
      );
}

class OidcLogin {
  final String issuer;
  final String? audience;
  final List<String> scopes;

  const OidcLogin({required this.issuer, this.audience, this.scopes = const []});

  factory OidcLogin.fromJson(Map<String, dynamic> j) => OidcLogin(
        issuer: j['issuer'] as String,
        audience: j['audience'] as String?,
        scopes: (j['scopes'] as List?)?.map((e) => e.toString()).toList() ?? const [],
      );
}
