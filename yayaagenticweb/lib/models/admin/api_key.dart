import 'package:freezed_annotation/freezed_annotation.dart';

part 'api_key.freezed.dart';
part 'api_key.g.dart';

/// Mirrors backend `ApiKeyController.ApiKeyDto` — one row in the list/get
/// view. The secret is NEVER present here; it is returned exactly once by
/// the create endpoint (see [ApiKeyCreated]).
@freezed
class ApiKey with _$ApiKey {
  const factory ApiKey({
    required String id,
    required String name,
    required String keyPrefix,
    required String role,
    String? createdBy,
    String? createdAt,
    String? expiresAt,
    String? lastUsedAt,
    String? lastUsedIp,
    required String status, // active | revoked | expired
  }) = _ApiKey;

  factory ApiKey.fromJson(Map<String, dynamic> json) => _$ApiKeyFromJson(json);
}

/// Body for `POST .../api-keys`. `expiresAt` is an optional ISO-8601 string;
/// `null`/absent means the key never expires.
@freezed
class ApiKeyCreateRequest with _$ApiKeyCreateRequest {
  const factory ApiKeyCreateRequest({
    required String name,
    required String role,
    String? expiresAt,
  }) = _ApiKeyCreateRequest;

  factory ApiKeyCreateRequest.fromJson(Map<String, dynamic> json) =>
      _$ApiKeyCreateRequestFromJson(json);
}

/// Mirrors `ApiKeyController.CreateResponse` — the ONLY response that carries
/// the raw `secret`. The UI shows it once in the show-once panel and never
/// stores or re-displays it.
@freezed
class ApiKeyCreated with _$ApiKeyCreated {
  const factory ApiKeyCreated({
    required String id,
    required String name,
    required String keyPrefix,
    required String role,
    String? createdAt,
    String? expiresAt,
    required String secret,
  }) = _ApiKeyCreated;

  factory ApiKeyCreated.fromJson(Map<String, dynamic> json) =>
      _$ApiKeyCreatedFromJson(json);
}

/// Mirrors `ApiKeyController.AuditDto`. `clientIp` is populated only for
/// `USE_DENIED` events (state-change events store a salted hash the API
/// never renders).
@freezed
class ApiKeyAuditEvent with _$ApiKeyAuditEvent {
  const factory ApiKeyAuditEvent({
    required String kind,
    String? detail,
    String? clientIp,
    String? occurredAt,
  }) = _ApiKeyAuditEvent;

  factory ApiKeyAuditEvent.fromJson(Map<String, dynamic> json) =>
      _$ApiKeyAuditEventFromJson(json);
}
