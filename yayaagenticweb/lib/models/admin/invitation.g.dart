// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'invitation.dart';

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

_$InvitationImpl _$$InvitationImplFromJson(Map<String, dynamic> json) =>
    _$InvitationImpl(
      id: json['id'] as String,
      workspaceId: json['workspaceId'] as String,
      email: json['email'] as String,
      role: json['role'] as String,
      invitedBy: json['invitedBy'] as String?,
      expiresAt: json['expiresAt'] as String?,
      createdAt: json['createdAt'] as String?,
      consumedAt: json['consumedAt'] as String?,
    );

Map<String, dynamic> _$$InvitationImplToJson(_$InvitationImpl instance) =>
    <String, dynamic>{
      'id': instance.id,
      'workspaceId': instance.workspaceId,
      'email': instance.email,
      'role': instance.role,
      'invitedBy': instance.invitedBy,
      'expiresAt': instance.expiresAt,
      'createdAt': instance.createdAt,
      'consumedAt': instance.consumedAt,
    };

_$InvitationCreateRequestImpl _$$InvitationCreateRequestImplFromJson(
  Map<String, dynamic> json,
) => _$InvitationCreateRequestImpl(
  email: json['email'] as String,
  role: json['role'] as String,
);

Map<String, dynamic> _$$InvitationCreateRequestImplToJson(
  _$InvitationCreateRequestImpl instance,
) => <String, dynamic>{'email': instance.email, 'role': instance.role};
