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
  });

  final int id;
  final String tenantId;
  final String iss;
  final List<String> audiences;
  final String? createdAt;
  final String? updatedAt;

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
      );
}

class TenantJwtSecretRequest {
  TenantJwtSecretRequest({
    required this.iss,
    this.secret,
    this.audiences = const <String>[],
  });

  final String iss;

  /// Plaintext HMAC key. Required on create; on update an empty/null value
  /// means "leave the existing secret as-is, only update iss/audiences".
  final String? secret;
  final List<String> audiences;

  Map<String, dynamic> toJson() => <String, dynamic>{
        'iss': iss,
        if (secret != null && secret!.isNotEmpty) 'secret': secret,
        'audiences': audiences,
      };
}
