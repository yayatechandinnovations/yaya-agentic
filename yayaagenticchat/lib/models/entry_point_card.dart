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
  final bool paused;
  final OidcLogin? oidc;

  const EntryPointCard({
    required this.handle,
    required this.displayName,
    required this.logoUrl,
    required this.authMode,
    required this.defaultLocale,
    this.accentColorHex,
    this.welcomeText,
    this.paused = false,
    this.oidc,
  });

  bool get requiresAuth => authMode == 'AUTH_REQUIRED';
  bool get optionalAuth => authMode == 'AUTH_OPTIONAL';

  factory EntryPointCard.fromJson(Map<String, dynamic> j) => EntryPointCard(
        handle: j['handle'] as String,
        displayName: j['displayName'] as String,
        logoUrl: j['logoUrl'] as String,
        accentColorHex: j['accentColorHex'] as String?,
        welcomeText: j['welcomeText'] as String?,
        defaultLocale: (j['defaultLocale'] as String?) ?? 'en-US',
        authMode: (j['authMode'] as String?) ?? 'ANONYMOUS_ONLY',
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
