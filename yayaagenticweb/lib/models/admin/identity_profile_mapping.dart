/// Per-tenant claim → profile mapping — one row in `identity_profile_mappings`.
/// Drives `IdentityProfileResolver`: at session start the resolver walks the
/// tenant's rows in priority-ASC order and matches the verified principal's
/// claims against them.
class IdentityProfileMappingResponse {
  IdentityProfileMappingResponse({
    required this.id,
    required this.tenantId,
    required this.claimName,
    required this.claimValue,
    required this.profileId,
    this.profileVersion,
    this.priority = 100,
    this.createdAt,
  });

  final int id;
  final String tenantId;
  final String claimName;
  final String claimValue;
  final String profileId;

  /// Null = unpinned: the profile's latest published version at session start.
  final int? profileVersion;
  final int priority;
  final String? createdAt;

  factory IdentityProfileMappingResponse.fromJson(Map<String, dynamic> json) =>
      IdentityProfileMappingResponse(
        id: (json['id'] as num).toInt(),
        tenantId: (json['tenantId'] ?? '') as String,
        claimName: (json['claimName'] ?? '') as String,
        claimValue: (json['claimValue'] ?? '') as String,
        profileId: (json['profileId'] ?? '') as String,
        profileVersion: (json['profileVersion'] as num?)?.toInt(),
        priority: (json['priority'] as num?)?.toInt() ?? 100,
        createdAt: json['createdAt']?.toString(),
      );
}

class IdentityProfileMappingRequest {
  IdentityProfileMappingRequest({
    required this.claimName,
    required this.claimValue,
    required this.profileId,
    this.profileVersion,
    this.priority = 100,
  });

  final String claimName;
  final String claimValue;
  final String profileId;

  /// Null = unpinned (follows new publishes); sent as null, never coerced to 1.
  final int? profileVersion;
  final int priority;

  Map<String, dynamic> toJson() => <String, dynamic>{
        'claimName': claimName,
        'claimValue': claimValue,
        'profileId': profileId,
        'profileVersion': profileVersion,
        'priority': priority,
      };
}
