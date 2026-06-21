// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'operator.dart';

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

_$OperatorImpl _$$OperatorImplFromJson(
  Map<String, dynamic> json,
) => _$OperatorImpl(
  subject: json['subject'] as String,
  displayName: json['displayName'] as String,
  source: json['source'] as String,
  attributes:
      json['attributes'] as Map<String, dynamic>? ?? const <String, dynamic>{},
  verifiedAt: json['verifiedAt'] == null
      ? null
      : DateTime.parse(json['verifiedAt'] as String),
  memberships:
      (json['memberships'] as List<dynamic>?)
          ?.map((e) => WorkspaceMembership.fromJson(e as Map<String, dynamic>))
          .toList() ??
      const <WorkspaceMembership>[],
  platformAdmin: json['platformAdmin'] as bool? ?? false,
);

Map<String, dynamic> _$$OperatorImplToJson(_$OperatorImpl instance) =>
    <String, dynamic>{
      'subject': instance.subject,
      'displayName': instance.displayName,
      'source': instance.source,
      'attributes': instance.attributes,
      'verifiedAt': instance.verifiedAt?.toIso8601String(),
      'memberships': instance.memberships,
      'platformAdmin': instance.platformAdmin,
    };

_$WorkspaceMembershipImpl _$$WorkspaceMembershipImplFromJson(
  Map<String, dynamic> json,
) => _$WorkspaceMembershipImpl(
  workspaceId: json['workspaceId'] as String,
  role: json['role'] as String,
);

Map<String, dynamic> _$$WorkspaceMembershipImplToJson(
  _$WorkspaceMembershipImpl instance,
) => <String, dynamic>{
  'workspaceId': instance.workspaceId,
  'role': instance.role,
};
