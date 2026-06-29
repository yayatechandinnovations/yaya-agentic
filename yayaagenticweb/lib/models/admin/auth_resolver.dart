/// Wire models for `/v1/admin/tenants/{tenantId}/auth-resolvers`. Mirrors
/// the backend's `TenantAuthResolverController` DTOs.
///
/// The serviceSecret is plaintext on POST/PUT but never appears on GET —
/// the response surfaces `serviceSecretMasked` only.
library;

class AuthResolverResponse {
  AuthResolverResponse({
    required this.id,
    required this.tenantId,
    required this.name,
    this.description,
    required this.kind,
    this.beanName,
    this.httpPath,
    this.serviceAuthKind,
    this.serviceSecretMasked,
    this.serviceJwtKid,
    this.serviceJwtAudience,
    this.timeoutMs,
    this.cacheEnabled,
    this.maxCacheSeconds,
    this.refreshWindowSeconds,
    this.maxTokenAgeSeconds,
    this.onRefreshFailure,
    required this.status,
    this.createdAt,
    this.updatedAt,
  });

  final int id;
  final String tenantId;
  final String name;
  final String? description;
  final String kind;                  // 'BEAN' | 'HTTP'
  final String? beanName;
  final String? httpPath;
  final String? serviceAuthKind;      // 'NONE' | 'BEARER' | 'SIGNED_JWT'
  final String? serviceSecretMasked;
  final String? serviceJwtKid;        // JOSE `kid` — cleartext, not a secret
  final String? serviceJwtAudience;   // overrides `aud`; null = tenant id
  final int? timeoutMs;
  final bool? cacheEnabled;
  final int? maxCacheSeconds;
  final int? refreshWindowSeconds;
  final int? maxTokenAgeSeconds;
  final String? onRefreshFailure;     // 'REJECT' | 'DOWNGRADE_TO_ANONYMOUS'
  final String status;                // 'ACTIVE' | 'DEACTIVATED'
  final String? createdAt;
  final String? updatedAt;

  factory AuthResolverResponse.fromJson(Map<String, dynamic> json) =>
      AuthResolverResponse(
        id: (json['id'] as num).toInt(),
        tenantId: (json['tenantId'] ?? '') as String,
        name: (json['name'] ?? '') as String,
        description: json['description'] as String?,
        kind: (json['kind'] ?? 'HTTP') as String,
        beanName: json['beanName'] as String?,
        httpPath: json['httpPath'] as String?,
        serviceAuthKind: json['serviceAuthKind'] as String?,
        serviceSecretMasked: json['serviceSecretMasked'] as String?,
        serviceJwtKid: json['serviceJwtKid'] as String?,
        serviceJwtAudience: json['serviceJwtAudience'] as String?,
        timeoutMs: (json['timeoutMs'] as num?)?.toInt(),
        cacheEnabled: json['cacheEnabled'] as bool?,
        maxCacheSeconds: (json['maxCacheSeconds'] as num?)?.toInt(),
        refreshWindowSeconds: (json['refreshWindowSeconds'] as num?)?.toInt(),
        maxTokenAgeSeconds: (json['maxTokenAgeSeconds'] as num?)?.toInt(),
        onRefreshFailure: json['onRefreshFailure'] as String?,
        status: (json['status'] ?? 'ACTIVE') as String,
        createdAt: json['createdAt']?.toString(),
        updatedAt: json['updatedAt']?.toString(),
      );
}

/// The list endpoint returns both the resolver rows and the BEAN names
/// the deployment has wired — the form's "kind=BEAN" dropdown reads from
/// `registeredBeanNames`.
class AuthResolverListResponse {
  AuthResolverListResponse({
    required this.resolvers,
    required this.registeredBeanNames,
  });

  final List<AuthResolverResponse> resolvers;
  final List<String> registeredBeanNames;

  factory AuthResolverListResponse.fromJson(Map<String, dynamic> json) =>
      AuthResolverListResponse(
        resolvers: ((json['resolvers'] as List?) ?? const [])
            .map((e) => AuthResolverResponse.fromJson(
                Map<String, dynamic>.from(e as Map)))
            .toList(),
        registeredBeanNames: ((json['registeredBeanNames'] as List?) ?? const [])
            .map((e) => e.toString())
            .toList(),
      );
}

class AuthResolverRequest {
  AuthResolverRequest({
    required this.name,
    this.description,
    required this.kind,
    this.beanName,
    this.httpPath,
    this.serviceAuthKind,
    this.serviceSecret,
    this.serviceJwtKid,
    this.serviceJwtAudience,
    this.timeoutMs,
    this.cacheEnabled,
    this.maxCacheSeconds,
    this.refreshWindowSeconds,
    this.maxTokenAgeSeconds,
    this.onRefreshFailure,
    this.status,
  });

  final String name;
  final String? description;
  final String kind;
  final String? beanName;
  final String? httpPath;
  final String? serviceAuthKind;

  /// Plaintext. Required on create when serviceAuthKind ∈ {BEARER, SIGNED_JWT}.
  /// Empty/null on update means "leave the existing secret as-is."
  final String? serviceSecret;

  /// JOSE `kid` for the outbound SIGNED_JWT header. Not a secret.
  final String? serviceJwtKid;

  /// Overrides the SIGNED_JWT `aud` claim; null = default to tenant id.
  final String? serviceJwtAudience;
  final int? timeoutMs;
  final bool? cacheEnabled;
  final int? maxCacheSeconds;
  final int? refreshWindowSeconds;
  final int? maxTokenAgeSeconds;
  final String? onRefreshFailure;
  final String? status;

  Map<String, dynamic> toJson() => <String, dynamic>{
        'name': name,
        if (description != null && description!.isNotEmpty)
          'description': description,
        'kind': kind,
        if (beanName != null && beanName!.isNotEmpty) 'beanName': beanName,
        if (httpPath != null && httpPath!.isNotEmpty) 'httpPath': httpPath,
        if (serviceAuthKind != null) 'serviceAuthKind': serviceAuthKind,
        if (serviceSecret != null && serviceSecret!.isNotEmpty)
          'serviceSecret': serviceSecret,
        if (serviceJwtKid != null && serviceJwtKid!.isNotEmpty)
          'serviceJwtKid': serviceJwtKid,
        if (serviceJwtAudience != null && serviceJwtAudience!.isNotEmpty)
          'serviceJwtAudience': serviceJwtAudience,
        if (timeoutMs != null) 'timeoutMs': timeoutMs,
        if (cacheEnabled != null) 'cacheEnabled': cacheEnabled,
        if (maxCacheSeconds != null) 'maxCacheSeconds': maxCacheSeconds,
        if (refreshWindowSeconds != null)
          'refreshWindowSeconds': refreshWindowSeconds,
        if (maxTokenAgeSeconds != null)
          'maxTokenAgeSeconds': maxTokenAgeSeconds,
        if (onRefreshFailure != null) 'onRefreshFailure': onRefreshFailure,
        if (status != null) 'status': status,
      };
}

/// Operator-supplied inputs for the test probe.
class AuthResolverProbeInput {
  AuthResolverProbeInput({this.callerPhone, this.senderId, this.senderPhone});
  final String? callerPhone;
  final String? senderId;
  final String? senderPhone;

  Map<String, dynamic> toJson() => <String, dynamic>{
        if (callerPhone != null && callerPhone!.isNotEmpty)
          'callerPhone': callerPhone,
        if (senderId != null && senderId!.isNotEmpty) 'senderId': senderId,
        if (senderPhone != null && senderPhone!.isNotEmpty)
          'senderPhone': senderPhone,
      };
}

/// JWT verification block — what the auth chain saw.
class AuthResolverVerification {
  AuthResolverVerification({
    required this.ok,
    this.issuer,
    this.subject,
    this.audience = const <String>[],
    this.exp,
    this.kid,
    this.profileId,
    this.scopes = const <String>[],
    this.reason,
  });

  final bool ok;
  final String? issuer;
  final String? subject;
  final List<String> audience;
  final String? exp;
  final String? kid;
  final String? profileId;
  final List<String> scopes;
  final String? reason;

  factory AuthResolverVerification.fromJson(Map<String, dynamic> json) =>
      AuthResolverVerification(
        ok: json['ok'] == true,
        issuer: json['issuer'] as String?,
        subject: json['subject'] as String?,
        audience: ((json['audience'] as List?) ?? const [])
            .map((e) => e.toString())
            .toList(),
        exp: json['exp']?.toString(),
        kid: json['kid'] as String?,
        profileId: json['profileId'] as String?,
        scopes: ((json['scopes'] as List?) ?? const [])
            .map((e) => e.toString())
            .toList(),
        reason: json['reason'] as String?,
      );
}

class AuthResolverProbeResult {
  AuthResolverProbeResult({
    required this.ok,
    required this.transport,
    this.url,
    this.elapsedMs,
    required this.tokenReceived,
    this.verification,
    this.errorStatus,
    this.httpStatus,
    this.errorMessage,
  });

  final bool ok;
  final String transport;       // 'BEAN' | 'HTTP'
  final String? url;
  final int? elapsedMs;
  final bool tokenReceived;
  final AuthResolverVerification? verification;
  final String? errorStatus;
  final int? httpStatus;
  final String? errorMessage;

  factory AuthResolverProbeResult.fromJson(Map<String, dynamic> json) =>
      AuthResolverProbeResult(
        ok: json['ok'] == true,
        transport: (json['transport'] ?? '') as String,
        url: json['url'] as String?,
        elapsedMs: (json['elapsedMs'] as num?)?.toInt(),
        tokenReceived: json['tokenReceived'] == true,
        verification: json['verification'] == null
            ? null
            : AuthResolverVerification.fromJson(
                Map<String, dynamic>.from(json['verification'] as Map)),
        errorStatus: json['errorStatus'] as String?,
        httpStatus: (json['httpStatus'] as num?)?.toInt(),
        errorMessage: json['errorMessage'] as String?,
      );
}

class AuthResolverDeleteResponse {
  AuthResolverDeleteResponse({required this.name, required this.cacheEntriesEvicted});
  final String name;
  final int cacheEntriesEvicted;

  factory AuthResolverDeleteResponse.fromJson(Map<String, dynamic> json) =>
      AuthResolverDeleteResponse(
        name: (json['name'] ?? '') as String,
        cacheEntriesEvicted:
            (json['cacheEntriesEvicted'] as num?)?.toInt() ?? 0,
      );
}

class AuthResolverInvalidateRequest {
  AuthResolverInvalidateRequest({this.callerPhone, this.senderId, this.allForTenant});
  final String? callerPhone;
  final String? senderId;
  final bool? allForTenant;

  Map<String, dynamic> toJson() => <String, dynamic>{
        if (callerPhone != null && callerPhone!.isNotEmpty)
          'callerPhone': callerPhone,
        if (senderId != null && senderId!.isNotEmpty) 'senderId': senderId,
        if (allForTenant != null) 'allForTenant': allForTenant,
      };
}

class AuthResolverInvalidateResponse {
  AuthResolverInvalidateResponse({
    required this.tenantScopeEvicted,
    required this.count,
  });
  final bool tenantScopeEvicted;
  final int count;

  factory AuthResolverInvalidateResponse.fromJson(Map<String, dynamic> json) =>
      AuthResolverInvalidateResponse(
        tenantScopeEvicted: json['tenantScopeEvicted'] == true,
        count: (json['count'] as num?)?.toInt() ?? 0,
      );
}
