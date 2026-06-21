// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'member.dart';

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

_$MemberImpl _$$MemberImplFromJson(Map<String, dynamic> json) => _$MemberImpl(
  userId: json['userId'] as String,
  email: json['email'] as String?,
  displayName: json['displayName'] as String?,
  role: json['role'] as String,
  addedAt: json['addedAt'] as String?,
);

Map<String, dynamic> _$$MemberImplToJson(_$MemberImpl instance) =>
    <String, dynamic>{
      'userId': instance.userId,
      'email': instance.email,
      'displayName': instance.displayName,
      'role': instance.role,
      'addedAt': instance.addedAt,
    };

_$MemberRoleChangeRequestImpl _$$MemberRoleChangeRequestImplFromJson(
  Map<String, dynamic> json,
) => _$MemberRoleChangeRequestImpl(role: json['role'] as String);

Map<String, dynamic> _$$MemberRoleChangeRequestImplToJson(
  _$MemberRoleChangeRequestImpl instance,
) => <String, dynamic>{'role': instance.role};
