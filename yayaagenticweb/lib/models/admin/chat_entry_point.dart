/// Wire models for the Chat inbound channel — branded entry points
/// (`/v1/admin/tenants/{tenantId}/chat-entry-points`). Hand-rolled to match the
/// existing admin-model pattern (see `sms_channel.dart`). See
/// `chat-channel-design.md` §6, §9.
library;

class ChatAuthConfig {
  ChatAuthConfig({
    this.kind = 'None',
    this.issuer,
    this.audience,
    this.scopes = const [],
    this.authResolverId,
    this.senderKey,
  });

  final String kind; // None | Oidc | ResolverExchange
  final String? issuer;
  final String? audience;
  final List<String> scopes;
  final int? authResolverId;
  // ResolverExchange only: the tenant-issued chat-sender code the resolver
  // gates on (chat has no sender phone). e.g. "F0DX-12FF".
  final String? senderKey;

  factory ChatAuthConfig.fromJson(Map<String, dynamic> json) => ChatAuthConfig(
        kind: (json['kind'] ?? 'None') as String,
        issuer: json['issuer'] as String?,
        audience: json['audience'] as String?,
        scopes: (json['scopes'] as List? ?? const []).map((e) => e.toString()).toList(),
        authResolverId: (json['authResolverId'] as num?)?.toInt(),
        senderKey: json['senderKey'] as String?,
      );

  Map<String, dynamic> toJson() => {
        'kind': kind,
        if (issuer != null) 'issuer': issuer,
        if (audience != null) 'audience': audience,
        if (scopes.isNotEmpty) 'scopes': scopes,
        if (authResolverId != null) 'authResolverId': authResolverId,
        if (senderKey != null && senderKey!.isNotEmpty) 'senderKey': senderKey,
      };
}

class ChatAbusePolicy {
  ChatAbusePolicy({
    this.startsPerDevicePerHour,
    this.startsPerIpPerHour,
    this.messagesPerSessionPerHour,
    this.anonymousDailyMessageCap,
    this.challenge,
  });

  final int? startsPerDevicePerHour;
  final int? startsPerIpPerHour;
  final int? messagesPerSessionPerHour;
  final int? anonymousDailyMessageCap;
  final String? challenge; // NONE | TURNSTILE | PROOF_OF_WORK

  factory ChatAbusePolicy.fromJson(Map<String, dynamic> json) => ChatAbusePolicy(
        startsPerDevicePerHour: (json['startsPerDevicePerHour'] as num?)?.toInt(),
        startsPerIpPerHour: (json['startsPerIpPerHour'] as num?)?.toInt(),
        messagesPerSessionPerHour: (json['messagesPerSessionPerHour'] as num?)?.toInt(),
        anonymousDailyMessageCap: (json['anonymousDailyMessageCap'] as num?)?.toInt(),
        challenge: json['challenge'] as String?,
      );

  Map<String, dynamic> toJson() => {
        if (startsPerDevicePerHour != null) 'startsPerDevicePerHour': startsPerDevicePerHour,
        if (startsPerIpPerHour != null) 'startsPerIpPerHour': startsPerIpPerHour,
        if (messagesPerSessionPerHour != null) 'messagesPerSessionPerHour': messagesPerSessionPerHour,
        if (anonymousDailyMessageCap != null) 'anonymousDailyMessageCap': anonymousDailyMessageCap,
        if (challenge != null) 'challenge': challenge,
      };
}

class ChatEntryPointResponse {
  ChatEntryPointResponse({
    required this.id,
    required this.tenantId,
    required this.handle,
    required this.deepLink,
    required this.profileId,
    required this.profileVersion,
    required this.displayName,
    required this.logoUrl,
    this.accentColorHex,
    this.welcomeText,
    required this.defaultLocale,
    this.tagline,
    required this.discoverable,
    required this.authMode,
    required this.authConfig,
    required this.abuse,
    required this.requiresVerifiedPhone,
    required this.status,
    this.createdAt,
    this.updatedAt,
    this.allowedOverrideProfiles = const [],
    this.onOverrideDenied = 'IGNORE',
  });

  final String id;
  final String tenantId;
  final String handle;
  final String deepLink;
  final String profileId;
  final int profileVersion;
  final String displayName;
  final String logoUrl;
  final String? accentColorHex;
  final String? welcomeText;
  final String defaultLocale;
  final String? tagline;
  final bool discoverable; // listed in the public directory (chat-public-directory-design.md §2)
  final String authMode;
  final ChatAuthConfig authConfig;
  final ChatAbusePolicy abuse;
  final bool requiresVerifiedPhone;
  final String status; // DRAFT | ACTIVE | PAUSED | DEACTIVATED
  final String? createdAt;
  final String? updatedAt;
  // Profile keys the resolver may override into; empty = any published.
  final List<String> allowedOverrideProfiles;
  final String onOverrideDenied; // 'IGNORE' | 'REJECT'

  bool get isActive => status == 'ACTIVE';
  bool get isPaused => status == 'PAUSED';
  bool get isDraft => status == 'DRAFT';
  bool get isDeactivated => status == 'DEACTIVATED';

  factory ChatEntryPointResponse.fromJson(Map<String, dynamic> json) => ChatEntryPointResponse(
        id: (json['id'] ?? '') as String,
        tenantId: (json['tenantId'] ?? '') as String,
        handle: (json['handle'] ?? '') as String,
        deepLink: (json['deepLink'] ?? '') as String,
        profileId: (json['profileId'] ?? '') as String,
        profileVersion: (json['profileVersion'] as num?)?.toInt() ?? 1,
        displayName: (json['displayName'] ?? '') as String,
        logoUrl: (json['logoUrl'] ?? '') as String,
        accentColorHex: json['accentColorHex'] as String?,
        welcomeText: json['welcomeText'] as String?,
        defaultLocale: (json['defaultLocale'] ?? 'en-US') as String,
        tagline: json['tagline'] as String?,
        discoverable: json['discoverable'] == true,
        authMode: (json['authMode'] ?? 'ANONYMOUS_ONLY') as String,
        authConfig: ChatAuthConfig.fromJson(json['authConfig'] is Map
            ? Map<String, dynamic>.from(json['authConfig'] as Map)
            : const {'kind': 'None'}),
        abuse: ChatAbusePolicy.fromJson(json['abuse'] is Map
            ? Map<String, dynamic>.from(json['abuse'] as Map)
            : const <String, dynamic>{}),
        requiresVerifiedPhone: json['requiresVerifiedPhone'] == true,
        status: (json['status'] ?? 'DRAFT') as String,
        createdAt: json['createdAt']?.toString(),
        updatedAt: json['updatedAt']?.toString(),
        allowedOverrideProfiles:
            (json['allowedOverrideProfiles'] as List?)?.cast<String>() ?? const [],
        onOverrideDenied: (json['onOverrideDenied'] ?? 'IGNORE') as String,
      );
}

class ChatEntryPointRequest {
  ChatEntryPointRequest({
    required this.profileId,
    this.profileVersion,
    required this.displayName,
    required this.logoUrl,
    this.accentColorHex,
    this.welcomeText,
    this.defaultLocale,
    this.tagline,
    this.discoverable,
    this.authMode,
    this.authConfig,
    this.abuse,
    this.requiresVerifiedPhone,
    this.allowedOverrideProfiles,
    this.onOverrideDenied,
  });

  final String profileId;
  final int? profileVersion;
  final String displayName;
  final String logoUrl;
  final String? accentColorHex;
  final String? welcomeText;
  final String? defaultLocale;
  final String? tagline;
  final bool? discoverable;
  final String? authMode;
  final ChatAuthConfig? authConfig;
  final ChatAbusePolicy? abuse;
  final bool? requiresVerifiedPhone;
  final List<String>? allowedOverrideProfiles;
  final String? onOverrideDenied;

  Map<String, dynamic> toJson() => {
        'profileId': profileId,
        if (profileVersion != null) 'profileVersion': profileVersion,
        'displayName': displayName,
        'logoUrl': logoUrl,
        if (accentColorHex != null && accentColorHex!.isNotEmpty) 'accentColorHex': accentColorHex,
        if (welcomeText != null && welcomeText!.isNotEmpty) 'welcomeText': welcomeText,
        if (defaultLocale != null && defaultLocale!.isNotEmpty) 'defaultLocale': defaultLocale,
        if (tagline != null) 'tagline': tagline,
        if (discoverable != null) 'discoverable': discoverable,
        if (authMode != null) 'authMode': authMode,
        if (authConfig != null) 'authConfig': authConfig!.toJson(),
        if (abuse != null) 'abuse': abuse!.toJson(),
        if (requiresVerifiedPhone != null) 'requiresVerifiedPhone': requiresVerifiedPhone,
        if (allowedOverrideProfiles != null) 'allowedOverrideProfiles': allowedOverrideProfiles,
        if (onOverrideDenied != null) 'onOverrideDenied': onOverrideDenied,
      };

  /// Update body. Rebinding the profile is allowed — the backend moves both the
  /// entry-point row and its MatchAny binding; new sessions pick up the change.
  Map<String, dynamic> toUpdateJson() => {
        'profileId': profileId,
        if (profileVersion != null) 'profileVersion': profileVersion,
        'displayName': displayName,
        'logoUrl': logoUrl,
        if (accentColorHex != null) 'accentColorHex': accentColorHex,
        if (welcomeText != null) 'welcomeText': welcomeText,
        if (defaultLocale != null && defaultLocale!.isNotEmpty) 'defaultLocale': defaultLocale,
        if (tagline != null) 'tagline': tagline,
        if (discoverable != null) 'discoverable': discoverable,
        if (authMode != null) 'authMode': authMode,
        if (authConfig != null) 'authConfig': authConfig!.toJson(),
        if (abuse != null) 'abuse': abuse!.toJson(),
        if (requiresVerifiedPhone != null) 'requiresVerifiedPhone': requiresVerifiedPhone,
        if (allowedOverrideProfiles != null) 'allowedOverrideProfiles': allowedOverrideProfiles,
        if (onOverrideDenied != null) 'onOverrideDenied': onOverrideDenied,
      };
}
