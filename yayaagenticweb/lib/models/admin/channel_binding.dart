/// Wire models for `/v1/admin/tenants/{tenantId}/channel-bindings`. Mirrors
/// the backend's `ChannelBindingDtos` — RoutingRule is a tagged union; the
/// API sends/receives a `kind` discriminator.
library;

class ChannelBindingResponse {
  ChannelBindingResponse({
    required this.id,
    required this.tenantId,
    required this.endpoint,
    required this.profileId,
    this.profileVersion,
    required this.routing,
    required this.sessionPolicy,
    required this.priority,
    required this.status,
    this.createdAt,
    this.deactivatedAt,
  });

  final int id;
  final String tenantId;
  final ChannelEndpointWire endpoint;
  final String profileId;
  final int? profileVersion;
  final RoutingRuleWire routing;
  final SessionPolicyWire sessionPolicy;
  final int priority;
  final String status;
  final String? createdAt;
  final String? deactivatedAt;

  factory ChannelBindingResponse.fromJson(Map<String, dynamic> json) =>
      ChannelBindingResponse(
        id: (json['id'] as num).toInt(),
        tenantId: (json['tenantId'] ?? '') as String,
        endpoint: ChannelEndpointWire.fromJson(
            Map<String, dynamic>.from(json['endpoint'] as Map)),
        profileId: (json['profileId'] ?? '') as String,
        profileVersion: (json['profileVersion'] as num?)?.toInt(),
        routing: RoutingRuleWire.fromJson(
            Map<String, dynamic>.from(json['routing'] as Map)),
        sessionPolicy: SessionPolicyWire.fromJson(
            Map<String, dynamic>.from(json['sessionPolicy'] as Map)),
        priority: (json['priority'] as num).toInt(),
        status: (json['status'] ?? 'ACTIVE') as String,
        createdAt: json['createdAt']?.toString(),
        deactivatedAt: json['deactivatedAt']?.toString(),
      );
}

class ChannelEndpointWire {
  ChannelEndpointWire({required this.kind, required this.id});
  final String kind;
  final String id;

  factory ChannelEndpointWire.fromJson(Map<String, dynamic> json) =>
      ChannelEndpointWire(
          kind: (json['kind'] ?? '') as String,
          id: (json['id'] ?? '') as String);

  Map<String, dynamic> toJson() => {'kind': kind, 'id': id};
}

class RoutingRuleWire {
  RoutingRuleWire({required this.kind, this.json = const {}});
  final String kind;
  final Map<String, dynamic> json;

  factory RoutingRuleWire.fromJson(Map<String, dynamic> json) {
    final kind = (json['kind'] ?? 'MatchAny') as String;
    return RoutingRuleWire(kind: kind, json: json);
  }

  Map<String, dynamic> toJson() => {...json, 'kind': kind};

  static RoutingRuleWire matchAny() =>
      RoutingRuleWire(kind: 'MatchAny', json: {'kind': 'MatchAny'});

  static RoutingRuleWire keywordPrefix(List<String> keywords,
          {bool caseInsensitive = true}) =>
      RoutingRuleWire(kind: 'KeywordPrefix', json: {
        'kind': 'KeywordPrefix',
        'keywords': keywords,
        'caseInsensitive': caseInsensitive,
      });

  static RoutingRuleWire locale(List<String> locales) =>
      RoutingRuleWire(kind: 'Locale', json: {
        'kind': 'Locale',
        'locales': locales,
      });

  static RoutingRuleWire endUserClaim(String claim, String equals) =>
      RoutingRuleWire(kind: 'EndUserClaim', json: {
        'kind': 'EndUserClaim',
        'claim': claim,
        'equals': equals,
      });

  String humanLabel() {
    switch (kind) {
      case 'MatchAny':
        return 'Match all messages';
      case 'KeywordPrefix':
        final words = (json['keywords'] as List?)?.cast<String>() ?? const [];
        return 'Starts with: ${words.join(", ")}';
      case 'Locale':
        final locs = (json['locales'] as List?)?.cast<String>() ?? const [];
        return 'Locale: ${locs.join(", ")}';
      case 'EndUserClaim':
        return 'Claim ${json['claim']} = ${json['equals']}';
      default:
        return kind;
    }
  }
}

/// How the inbound WhatsApp authenticator mints the end-user Principal. The
/// admin form exposes three kinds; LookupPrincipal is declared for forward-
/// compatibility but ships disabled in W-A1 (the resolver registry arrives in
/// W-A2). Reserved channel claim keys are rejected client-side and server-side.
///
/// See `whatsapp-channel-auth-design.md` §5.
class DefaultPrincipalPolicyWire {
  DefaultPrincipalPolicyWire({required this.kind, this.json = const {}});
  final String kind; // 'Anonymous' | 'PhoneAsPrincipal' | 'LookupPrincipal'
  final Map<String, dynamic> json;

  static const Set<String> reservedClaimKeys = {
    'phone',
    'channel.sender',
    'channel.kind',
    'locale',
    'twilioContactSid',
  };

  factory DefaultPrincipalPolicyWire.fromJson(Map<String, dynamic> json) {
    final kind = (json['kind'] ?? 'Anonymous') as String;
    return DefaultPrincipalPolicyWire(kind: kind, json: json);
  }

  Map<String, dynamic> toJson() => {...json, 'kind': kind};

  static DefaultPrincipalPolicyWire anonymous() =>
      DefaultPrincipalPolicyWire(kind: 'Anonymous', json: {'kind': 'Anonymous'});

  static DefaultPrincipalPolicyWire phoneAsPrincipal({
    List<String> additionalScopes = const [],
    Map<String, dynamic> additionalClaims = const {},
  }) =>
      DefaultPrincipalPolicyWire(kind: 'PhoneAsPrincipal', json: {
        'kind': 'PhoneAsPrincipal',
        'additionalScopes': additionalScopes,
        'additionalClaims': additionalClaims,
      });

  static DefaultPrincipalPolicyWire lookupPrincipal({
    required String resolverId,
    bool denyOnUnknown = true,
  }) =>
      DefaultPrincipalPolicyWire(kind: 'LookupPrincipal', json: {
        'kind': 'LookupPrincipal',
        'resolverId': resolverId,
        'denyOnUnknown': denyOnUnknown,
      });

  List<String> get additionalScopes => ((json['additionalScopes'] as List?) ??
          const [])
      .map((e) => e.toString())
      .toList();

  Map<String, dynamic> get additionalClaims =>
      Map<String, dynamic>.from((json['additionalClaims'] as Map?) ?? const {});

  String? get resolverId => json['resolverId'] as String?;

  String humanLabel() {
    switch (kind) {
      case 'Anonymous':
        return 'Anonymous (phone only)';
      case 'PhoneAsPrincipal':
        final count = additionalScopes.length + additionalClaims.length;
        return count == 0
            ? 'Phone as principal'
            : 'Phone as principal · +$count';
      case 'LookupPrincipal':
        return 'Lookup principal · ${resolverId ?? "?"}';
      default:
        return kind;
    }
  }
}

class SessionPolicyWire {
  SessionPolicyWire({
    required this.idleTimeout,
    required this.newSessionKeywords,
    required this.endSessionKeywords,
    required this.greetOnReengagement,
    this.reengagementTemplate,
    required this.exposeEndUserPhoneToProfile,
    required this.defaultPrincipalPolicy,
  });

  /// Stored as ISO-8601 duration string, e.g. "PT24H".
  final String idleTimeout;
  final List<String> newSessionKeywords;
  final List<String> endSessionKeywords;
  final bool greetOnReengagement;
  final String? reengagementTemplate;
  final bool exposeEndUserPhoneToProfile;
  final DefaultPrincipalPolicyWire defaultPrincipalPolicy;

  factory SessionPolicyWire.fromJson(Map<String, dynamic> json) =>
      SessionPolicyWire(
        idleTimeout: (json['idleTimeout'] ?? 'PT24H') as String,
        newSessionKeywords: ((json['newSessionKeywords'] as List?) ?? const [])
            .map((e) => e.toString())
            .toList(),
        endSessionKeywords: ((json['endSessionKeywords'] as List?) ?? const [])
            .map((e) => e.toString())
            .toList(),
        greetOnReengagement: json['greetOnReengagement'] == true,
        reengagementTemplate: json['reengagementTemplate'] as String?,
        exposeEndUserPhoneToProfile:
            json['exposeEndUserPhoneToProfile'] != false,
        defaultPrincipalPolicy: json['defaultPrincipalPolicy'] == null
            ? DefaultPrincipalPolicyWire.anonymous()
            : DefaultPrincipalPolicyWire.fromJson(
                Map<String, dynamic>.from(json['defaultPrincipalPolicy'] as Map)),
      );

  Map<String, dynamic> toJson() => {
        'idleTimeout': idleTimeout,
        'newSessionKeywords': newSessionKeywords,
        'endSessionKeywords': endSessionKeywords,
        'greetOnReengagement': greetOnReengagement,
        if (reengagementTemplate != null && reengagementTemplate!.isNotEmpty)
          'reengagementTemplate': reengagementTemplate,
        'exposeEndUserPhoneToProfile': exposeEndUserPhoneToProfile,
        'defaultPrincipalPolicy': defaultPrincipalPolicy.toJson(),
      };

  SessionPolicyWire copyWith({DefaultPrincipalPolicyWire? defaultPrincipalPolicy}) =>
      SessionPolicyWire(
        idleTimeout: idleTimeout,
        newSessionKeywords: newSessionKeywords,
        endSessionKeywords: endSessionKeywords,
        greetOnReengagement: greetOnReengagement,
        reengagementTemplate: reengagementTemplate,
        exposeEndUserPhoneToProfile: exposeEndUserPhoneToProfile,
        defaultPrincipalPolicy:
            defaultPrincipalPolicy ?? this.defaultPrincipalPolicy,
      );

  static SessionPolicyWire defaults() => SessionPolicyWire(
        idleTimeout: 'PT24H',
        newSessionKeywords: const ['#new', 'restart', 'start over'],
        endSessionKeywords: const ['bye', 'done', 'thanks bye'],
        greetOnReengagement: false,
        exposeEndUserPhoneToProfile: true,
        defaultPrincipalPolicy: DefaultPrincipalPolicyWire.anonymous(),
      );

  String humanIdle() {
    // Very small ISO-8601 parser — handles "PT{n}H", "PT{n}M".
    final m = RegExp(r'^PT(\d+)([HM])$').firstMatch(idleTimeout);
    if (m == null) return idleTimeout;
    final n = int.parse(m.group(1)!);
    final unit = m.group(2)! == 'H' ? 'hour' : 'minute';
    return '$n $unit${n == 1 ? '' : 's'}';
  }
}

class ChannelBindingRequest {
  ChannelBindingRequest({
    required this.endpoint,
    required this.profileId,
    this.profileVersion,
    required this.routing,
    required this.sessionPolicy,
    required this.priority,
    required this.status,
  });

  final ChannelEndpointWire endpoint;
  final String profileId;
  final int? profileVersion;
  final RoutingRuleWire routing;
  final SessionPolicyWire sessionPolicy;
  final int priority;
  final String status;

  Map<String, dynamic> toJson() => {
        'endpoint': endpoint.toJson(),
        'profileId': profileId,
        if (profileVersion != null) 'profileVersion': profileVersion,
        'routing': routing.toJson(),
        'sessionPolicy': sessionPolicy.toJson(),
        'priority': priority,
        'status': status,
      };
}

/// One entry returned by GET /v1/admin/tenants/{t}/phone-principal-resolvers.
/// Used to populate the resolver dropdown when the operator picks
/// LookupPrincipal as the binding's identity policy.
class PhonePrincipalResolverSummary {
  PhonePrincipalResolverSummary({required this.id, required this.description});
  final String id;
  final String description;

  factory PhonePrincipalResolverSummary.fromJson(Map<String, dynamic> json) =>
      PhonePrincipalResolverSummary(
        id: (json['id'] ?? '') as String,
        description: (json['description'] ?? '') as String,
      );
}

class ChannelBindingUpdate {
  ChannelBindingUpdate({
    required this.profileId,
    this.profileVersion,
    required this.routing,
    required this.sessionPolicy,
    required this.priority,
    required this.status,
  });

  final String profileId;
  final int? profileVersion;
  final RoutingRuleWire routing;
  final SessionPolicyWire sessionPolicy;
  final int priority;
  final String status;

  Map<String, dynamic> toJson() => {
        'profileId': profileId,
        if (profileVersion != null) 'profileVersion': profileVersion,
        'routing': routing.toJson(),
        'sessionPolicy': sessionPolicy.toJson(),
        'priority': priority,
        'status': status,
      };
}
