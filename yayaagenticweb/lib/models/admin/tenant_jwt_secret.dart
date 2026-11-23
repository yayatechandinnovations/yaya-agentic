/// Per-tenant HMAC-signed JWT trust config — one row in `tenant_jwt_secrets`.
///
/// The response deliberately omits the secret: it's only readable by the
/// backend's [SecretCipher] and never round-trips to the operator. Rotation
/// happens via PUT with a fresh secret value; "edit metadata only" is PUT
/// with the secret field blank.
class TenantJwtSecretResponse {
  TenantJwtSecretResponse({
    required this.id,
    required this.tenantId,
    required this.iss,
    this.audiences = const <String>[],
    this.createdAt,
    this.updatedAt,
    this.principalKind,
    this.requiredClaims = const <String, String>{},
  });

  final int id;
  final String tenantId;
  final String iss;
  final List<String> audiences;
  final String? createdAt;
  final String? updatedAt;

  /// The caller kind this issuer vouches for (profiles admit by kind via
  /// `metadata.principalKinds`). Null = no kind.
  final String? principalKind;

  /// Claims every token must carry with these exact values — pins a signing
  /// key that several tenants share to this one.
  final Map<String, String> requiredClaims;

  factory TenantJwtSecretResponse.fromJson(Map<String, dynamic> json) =>
      TenantJwtSecretResponse(
        id: (json['id'] as num).toInt(),
        tenantId: (json['tenantId'] ?? '') as String,
        iss: (json['iss'] ?? '') as String,
        audiences: json['audiences'] == null
            ? const <String>[]
            : (json['audiences'] as List).map((e) => e.toString()).toList(),
        createdAt: json['createdAt']?.toString(),
        updatedAt: json['updatedAt']?.toString(),
        principalKind: json['principalKind'] as String?,
        requiredClaims: json['requiredClaims'] is Map
            ? (json['requiredClaims'] as Map).map((k, v) => MapEntry('$k', '$v'))
            : const <String, String>{},
      );
}

class TenantJwtSecretRequest {
  TenantJwtSecretRequest({
    required this.iss,
    this.secret,
    this.audiences = const <String>[],
    this.principalKind,
    this.requiredClaims,
  });

  final String iss;

  /// Plaintext HMAC key. Required on create; on update an empty/null value
  /// means "leave the existing secret as-is, only update iss/audiences".
  final String? secret;
  final List<String> audiences;

  /// Null = leave the stored kind as-is; empty string clears it.
  final String? principalKind;

  /// Null = leave the stored pins as-is; an empty map clears them.
  final Map<String, String>? requiredClaims;

  Map<String, dynamic> toJson() => <String, dynamic>{
        'iss': iss,
        if (secret != null && secret!.isNotEmpty) 'secret': secret,
        'audiences': audiences,
        'principalKind': ?principalKind,
        'requiredClaims': ?requiredClaims,
      };
}
