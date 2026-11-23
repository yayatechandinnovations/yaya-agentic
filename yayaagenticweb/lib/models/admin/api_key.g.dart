// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'api_key.dart';

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

_$ApiKeyImpl _$$ApiKeyImplFromJson(Map<String, dynamic> json) => _$ApiKeyImpl(
  id: json['id'] as String,
  name: json['name'] as String,
  keyPrefix: json['keyPrefix'] as String,
  role: json['role'] as String,
  createdBy: json['createdBy'] as String?,
  createdAt: json['createdAt'] as String?,
  expiresAt: json['expiresAt'] as String?,
  lastUsedAt: json['lastUsedAt'] as String?,
  lastUsedIp: json['lastUsedIp'] as String?,
  status: json['status'] as String,
  platformScope: json['platformScope'] as bool? ?? false,
);

Map<String, dynamic> _$$ApiKeyImplToJson(_$ApiKeyImpl instance) =>
    <String, dynamic>{
      'id': instance.id,
      'name': instance.name,
      'keyPrefix': instance.keyPrefix,
      'role': instance.role,
      'createdBy': instance.createdBy,
      'createdAt': instance.createdAt,
      'expiresAt': instance.expiresAt,
      'lastUsedAt': instance.lastUsedAt,
      'lastUsedIp': instance.lastUsedIp,
      'status': instance.status,
      'platformScope': instance.platformScope,
    };

_$ApiKeyCreateRequestImpl _$$ApiKeyCreateRequestImplFromJson(
  Map<String, dynamic> json,
) => _$ApiKeyCreateRequestImpl(
  name: json['name'] as String,
  role: json['role'] as String,
  expiresAt: json['expiresAt'] as String?,
  platformScope: json['platformScope'] as bool?,
);

Map<String, dynamic> _$$ApiKeyCreateRequestImplToJson(
  _$ApiKeyCreateRequestImpl instance,
) => <String, dynamic>{
  'name': instance.name,
  'role': instance.role,
  'expiresAt': instance.expiresAt,
  'platformScope': instance.platformScope,
};

_$ApiKeyCreatedImpl _$$ApiKeyCreatedImplFromJson(Map<String, dynamic> json) =>
    _$ApiKeyCreatedImpl(
      id: json['id'] as String,
      name: json['name'] as String,
      keyPrefix: json['keyPrefix'] as String,
      role: json['role'] as String,
      createdAt: json['createdAt'] as String?,
      expiresAt: json['expiresAt'] as String?,
      platformScope: json['platformScope'] as bool? ?? false,
      secret: json['secret'] as String,
    );

Map<String, dynamic> _$$ApiKeyCreatedImplToJson(_$ApiKeyCreatedImpl instance) =>
    <String, dynamic>{
      'id': instance.id,
      'name': instance.name,
      'keyPrefix': instance.keyPrefix,
      'role': instance.role,
      'createdAt': instance.createdAt,
      'expiresAt': instance.expiresAt,
      'platformScope': instance.platformScope,
      'secret': instance.secret,
    };

_$ApiKeyAuditEventImpl _$$ApiKeyAuditEventImplFromJson(
  Map<String, dynamic> json,
) => _$ApiKeyAuditEventImpl(
  kind: json['kind'] as String,
  detail: json['detail'] as String?,
  clientIp: json['clientIp'] as String?,
  occurredAt: json['occurredAt'] as String?,
);

Map<String, dynamic> _$$ApiKeyAuditEventImplToJson(
  _$ApiKeyAuditEventImpl instance,
) => <String, dynamic>{
  'kind': instance.kind,
  'detail': instance.detail,
  'clientIp': instance.clientIp,
  'occurredAt': instance.occurredAt,
};
