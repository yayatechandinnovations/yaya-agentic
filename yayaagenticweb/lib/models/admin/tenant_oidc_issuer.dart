/// Per-tenant trusted JWT issuer config — one row in `tenant_oidc_issuers`.
/// Plain DTOs (no freezed) to match the rest of the admin models for resources
/// where the shape is small and stable.
class TenantOidcIssuerResponse {
  TenantOidcIssuerResponse({
    required this.id,
    required this.tenantId,
    required this.iss,
    required this.jwksUri,
    this.audiences = const <String>[],
    this.createdAt,
    this.updatedAt,
  });

  final int id;
  final String tenantId;
  final String iss;
  final String jwksUri;
  final List<String> audiences;
  final String? createdAt;
  final String? updatedAt;

  factory TenantOidcIssuerResponse.fromJson(Map<String, dynamic> json) =>
      TenantOidcIssuerResponse(
        id: (json['id'] as num).toInt(),
        tenantId: (json['tenantId'] ?? '') as String,
        iss: (json['iss'] ?? '') as String,
        jwksUri: (json['jwksUri'] ?? '') as String,
        audiences: json['audiences'] == null
            ? const <String>[]
            : (json['audiences'] as List).map((e) => e.toString()).toList(),
        createdAt: json['createdAt']?.toString(),
        updatedAt: json['updatedAt']?.toString(),
      );
}

class TenantOidcIssuerRequest {
  TenantOidcIssuerRequest({
    required this.iss,
    required this.jwksUri,
    this.audiences = const <String>[],
  });

  final String iss;
  final String jwksUri;
  final List<String> audiences;

  Map<String, dynamic> toJson() => <String, dynamic>{
        'iss': iss,
        'jwksUri': jwksUri,
        'audiences': audiences,
      };
}
