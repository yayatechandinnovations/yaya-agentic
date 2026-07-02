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
    this.authResolverId,
    required this.onUnresolved,
    this.allowedOverrideProfiles = const [],
    this.onOverrideDenied = 'IGNORE',
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
  final int? authResolverId;
  final String onUnresolved; // 'REJECT' | 'ANONYMOUS'
  // Profile keys the resolver may override into; empty = any published.
  final List<String> allowedOverrideProfiles;
  final String onOverrideDenied; // 'IGNORE' | 'REJECT'

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
        authResolverId: (json['authResolverId'] as num?)?.toInt(),
        onUnresolved: (json['onUnresolved'] ?? 'REJECT') as String,
        allowedOverrideProfiles:
            (json['allowedOverrideProfiles'] as List?)?.cast<String>() ?? const [],
        onOverrideDenied: (json['onOverrideDenied'] ?? 'IGNORE') as String,
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

class SessionPolicyWire {
  SessionPolicyWire({
    required this.idleTimeout,
    required this.newSessionKeywords,
    required this.endSessionKeywords,
    required this.greetOnReengagement,
    this.reengagementTemplate,
    required this.exposeEndUserPhoneToProfile,
  });

  /// Stored as ISO-8601 duration string, e.g. "PT24H".
  final String idleTimeout;
  final List<String> newSessionKeywords;
  final List<String> endSessionKeywords;
  final bool greetOnReengagement;
  final String? reengagementTemplate;
  final bool exposeEndUserPhoneToProfile;

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
      );

  Map<String, dynamic> toJson() => {
        'idleTimeout': idleTimeout,
        'newSessionKeywords': newSessionKeywords,
        'endSessionKeywords': endSessionKeywords,
        'greetOnReengagement': greetOnReengagement,
        if (reengagementTemplate != null && reengagementTemplate!.isNotEmpty)
          'reengagementTemplate': reengagementTemplate,
        'exposeEndUserPhoneToProfile': exposeEndUserPhoneToProfile,
      };

  static SessionPolicyWire defaults() => SessionPolicyWire(
        idleTimeout: 'PT24H',
        newSessionKeywords: const ['#new', 'restart', 'start over'],
        endSessionKeywords: const ['bye', 'done', 'thanks bye'],
        greetOnReengagement: false,
        exposeEndUserPhoneToProfile: true,
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
    this.authResolverId,
    this.onUnresolved = 'REJECT',
    this.allowedOverrideProfiles = const [],
    this.onOverrideDenied = 'IGNORE',
  });

  final ChannelEndpointWire endpoint;
  final String profileId;
  final int? profileVersion;
  final RoutingRuleWire routing;
  final SessionPolicyWire sessionPolicy;
  final int priority;
  final String status;
  final int? authResolverId;
  final String onUnresolved;
  final List<String> allowedOverrideProfiles;
  final String onOverrideDenied;

  Map<String, dynamic> toJson() => {
        'endpoint': endpoint.toJson(),
        'profileId': profileId,
        if (profileVersion != null) 'profileVersion': profileVersion,
        'routing': routing.toJson(),
        'sessionPolicy': sessionPolicy.toJson(),
        'priority': priority,
        'status': status,
        if (authResolverId != null) 'authResolverId': authResolverId,
        'onUnresolved': onUnresolved,
        'allowedOverrideProfiles': allowedOverrideProfiles,
        'onOverrideDenied': onOverrideDenied,
      };
}

class ChannelBindingUpdate {
  ChannelBindingUpdate({
    required this.profileId,
    this.profileVersion,
    required this.routing,
    required this.sessionPolicy,
    required this.priority,
    required this.status,
    this.authResolverId,
    this.onUnresolved = 'REJECT',
    this.allowedOverrideProfiles = const [],
    this.onOverrideDenied = 'IGNORE',
  });

  final String profileId;
  final int? profileVersion;
  final RoutingRuleWire routing;
  final SessionPolicyWire sessionPolicy;
  final int priority;
  final String status;
  final int? authResolverId;
  final String onUnresolved;
  final List<String> allowedOverrideProfiles;
  final String onOverrideDenied;

  Map<String, dynamic> toJson() => {
        'profileId': profileId,
        if (profileVersion != null) 'profileVersion': profileVersion,
        'routing': routing.toJson(),
        'sessionPolicy': sessionPolicy.toJson(),
        'priority': priority,
        'status': status,
        if (authResolverId != null) 'authResolverId': authResolverId,
        'onUnresolved': onUnresolved,
        'allowedOverrideProfiles': allowedOverrideProfiles,
        'onOverrideDenied': onOverrideDenied,
      };
}
