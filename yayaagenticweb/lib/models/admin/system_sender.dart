/// Wire models for the System Inbound feature — trusted integration senders
/// (`/v1/admin/tenants/{tenantId}/system-senders`). Hand-rolled to match the
/// existing admin-model pattern (see `chat_entry_point.dart`). See
/// `system-messaging-design.md` §3, §14.
library;

class SenderTrust {
  SenderTrust({
    this.authenticatorName = '',
    this.requiredScopes = const [],
    this.credentialRef = '',
  });

  final String authenticatorName; // Authenticator.name() to route to
  final List<String> requiredScopes;
  final String credentialRef; // which jwt-secret iss / api key / issuer to accept

  factory SenderTrust.fromJson(Map<String, dynamic> json) => SenderTrust(
        authenticatorName: (json['authenticatorName'] ?? '') as String,
        requiredScopes:
            (json['requiredScopes'] as List? ?? const []).map((e) => e.toString()).toList(),
        credentialRef: (json['credentialRef'] ?? '') as String,
      );

  Map<String, dynamic> toJson() => {
        'authenticatorName': authenticatorName,
        'requiredScopes': requiredScopes,
        'credentialRef': credentialRef,
      };
}

class AddressingPolicy {
  AddressingPolicy({
    this.allowedKinds = const ['PRINCIPAL'],
    this.allowedSubjects = const [],
    this.allowedRoles = const [],
  });

  final List<String> allowedKinds; // PRINCIPAL | ROLE | SESSION
  final List<String> allowedSubjects;
  final List<String> allowedRoles;

  factory AddressingPolicy.fromJson(Map<String, dynamic> json) => AddressingPolicy(
        allowedKinds:
            (json['allowedKinds'] as List? ?? const ['PRINCIPAL']).map((e) => e.toString()).toList(),
        allowedSubjects:
            (json['allowedSubjects'] as List? ?? const []).map((e) => e.toString()).toList(),
        allowedRoles:
            (json['allowedRoles'] as List? ?? const []).map((e) => e.toString()).toList(),
      );

  Map<String, dynamic> toJson() => {
        'allowedKinds': allowedKinds,
        'allowedSubjects': allowedSubjects,
        'allowedRoles': allowedRoles,
      };
}

class SystemSenderResponse {
  SystemSenderResponse({
    required this.id,
    required this.tenantId,
    required this.entryPoint,
    required this.displayName,
    required this.enabled,
    required this.trust,
    required this.mediation,
    required this.addressing,
    this.responseToolId,
    this.whatsappSenderId,
  });

  final String id;
  final String tenantId;
  final String entryPoint;
  final String displayName;
  final bool enabled;
  final SenderTrust trust;
  final String mediation; // VERBATIM | LLM_MEDIATED
  final AddressingPolicy addressing;
  final String? responseToolId;
  final String? whatsappSenderId;

  factory SystemSenderResponse.fromJson(Map<String, dynamic> json) => SystemSenderResponse(
        id: json['id'] as String,
        tenantId: (json['tenantId'] ?? '') as String,
        entryPoint: (json['entryPoint'] ?? '') as String,
        displayName: (json['displayName'] ?? '') as String,
        enabled: (json['enabled'] ?? false) as bool,
        trust: SenderTrust.fromJson((json['trust'] as Map?)?.cast<String, dynamic>() ?? const {}),
        mediation: (json['mediation'] ?? 'VERBATIM') as String,
        addressing: AddressingPolicy.fromJson(
            (json['addressing'] as Map?)?.cast<String, dynamic>() ?? const {}),
        responseToolId: json['responseToolId'] as String?,
        whatsappSenderId: json['whatsappSenderId'] as String?,
      );
}

class SystemSenderRequest {
  SystemSenderRequest({
    required this.entryPoint,
    required this.displayName,
    required this.enabled,
    required this.trust,
    required this.mediation,
    required this.addressing,
    this.responseToolId,
    this.whatsappSenderId,
  });

  final String entryPoint;
  final String displayName;
  final bool enabled;
  final SenderTrust trust;
  final String mediation;
  final AddressingPolicy addressing;
  final String? responseToolId;

  /// WhatsApp number the sender's proactive messages go out on. `null` leaves
  /// the server's current link untouched; `''` unlinks.
  final String? whatsappSenderId;

  Map<String, dynamic> toJson() => {
        'entryPoint': entryPoint,
        'displayName': displayName,
        'enabled': enabled,
        'trust': trust.toJson(),
        'mediation': mediation,
        'addressing': addressing.toJson(),
        if (responseToolId != null && responseToolId!.isNotEmpty) 'responseToolId': responseToolId,
        if (whatsappSenderId != null) 'whatsappSenderId': whatsappSenderId,
      };
}

/// One row of the integration log (`GET .../system-senders/{id}/messages`).
class SystemInboundLogEntry {
  SystemInboundLogEntry({
    required this.id,
    required this.status,
    this.body,
    this.receivedAt,
    this.sessionId,
  });

  final String id;
  final String status; // RECEIVED | DELIVERED | DROPPED | FAILED
  final String? body;
  final String? receivedAt;
  final String? sessionId;

  factory SystemInboundLogEntry.fromJson(Map<String, dynamic> json) => SystemInboundLogEntry(
        id: json['id'] as String,
        status: (json['status'] ?? '') as String,
        body: json['body'] as String?,
        receivedAt: json['receivedAt']?.toString(),
        sessionId: json['sessionId'] as String?,
      );
}
