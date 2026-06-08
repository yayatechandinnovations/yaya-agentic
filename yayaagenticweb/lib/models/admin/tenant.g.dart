// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'tenant.dart';

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

_$TenantRequestImpl _$$TenantRequestImplFromJson(Map<String, dynamic> json) =>
    _$TenantRequestImpl(
      id: json['id'] as String?,
      displayName: json['displayName'] as String?,
      hostBaseUrl: json['hostBaseUrl'] as String?,
      hostBaseUrlAllowlist:
          (json['hostBaseUrlAllowlist'] as List<dynamic>?)
              ?.map((e) => e as String)
              .toList() ??
          const <String>[],
      inboundOriginAllowlist:
          (json['inboundOriginAllowlist'] as List<dynamic>?)
              ?.map((e) => e as String)
              .toList() ??
          const <String>[],
      requireHttps: json['requireHttps'] as bool? ?? true,
      defaultAuthenticatorBindingId:
          json['defaultAuthenticatorBindingId'] as String?,
      defaultRecordingStrategyId: (json['defaultRecordingStrategyId'] as num?)
          ?.toInt(),
      settings:
          json['settings'] as Map<String, dynamic>? ??
          const <String, dynamic>{},
    );

Map<String, dynamic> _$$TenantRequestImplToJson(_$TenantRequestImpl instance) =>
    <String, dynamic>{
      'id': instance.id,
      'displayName': instance.displayName,
      'hostBaseUrl': instance.hostBaseUrl,
      'hostBaseUrlAllowlist': instance.hostBaseUrlAllowlist,
      'inboundOriginAllowlist': instance.inboundOriginAllowlist,
      'requireHttps': instance.requireHttps,
      'defaultAuthenticatorBindingId': instance.defaultAuthenticatorBindingId,
      'defaultRecordingStrategyId': instance.defaultRecordingStrategyId,
      'settings': instance.settings,
    };

_$TenantResponseImpl _$$TenantResponseImplFromJson(Map<String, dynamic> json) =>
    _$TenantResponseImpl(
      id: json['id'] as String,
      displayName: json['displayName'] as String,
      status: json['status'] as String,
      hostBaseUrl: json['hostBaseUrl'] as String?,
      hostBaseUrlAllowlist:
          (json['hostBaseUrlAllowlist'] as List<dynamic>?)
              ?.map((e) => e as String)
              .toList() ??
          const <String>[],
      inboundOriginAllowlist:
          (json['inboundOriginAllowlist'] as List<dynamic>?)
              ?.map((e) => e as String)
              .toList() ??
          const <String>[],
      requireHttps: json['requireHttps'] as bool? ?? true,
      defaultAuthenticatorBindingId:
          json['defaultAuthenticatorBindingId'] as String?,
      defaultRecordingStrategyId: (json['defaultRecordingStrategyId'] as num?)
          ?.toInt(),
      settings:
          json['settings'] as Map<String, dynamic>? ??
          const <String, dynamic>{},
      createdAt: json['createdAt'] as String?,
      updatedAt: json['updatedAt'] as String?,
      archivedAt: json['archivedAt'] as String?,
      createdBy: json['createdBy'] as String?,
    );

Map<String, dynamic> _$$TenantResponseImplToJson(
  _$TenantResponseImpl instance,
) => <String, dynamic>{
  'id': instance.id,
  'displayName': instance.displayName,
  'status': instance.status,
  'hostBaseUrl': instance.hostBaseUrl,
  'hostBaseUrlAllowlist': instance.hostBaseUrlAllowlist,
  'inboundOriginAllowlist': instance.inboundOriginAllowlist,
  'requireHttps': instance.requireHttps,
  'defaultAuthenticatorBindingId': instance.defaultAuthenticatorBindingId,
  'defaultRecordingStrategyId': instance.defaultRecordingStrategyId,
  'settings': instance.settings,
  'createdAt': instance.createdAt,
  'updatedAt': instance.updatedAt,
  'archivedAt': instance.archivedAt,
  'createdBy': instance.createdBy,
};

_$TenantHealthResponseImpl _$$TenantHealthResponseImplFromJson(
  Map<String, dynamic> json,
) => _$TenantHealthResponseImpl(
  tenantId: json['tenantId'] as String,
  status: json['status'] as String,
  hostBaseUrlSet: json['hostBaseUrlSet'] as bool,
  authBindingResolves: json['authBindingResolves'] as bool,
  recordingStrategyResolves: json['recordingStrategyResolves'] as bool,
  dependencyCount: (json['dependencyCount'] as num).toInt(),
  warnings:
      (json['warnings'] as List<dynamic>?)?.map((e) => e as String).toList() ??
      const <String>[],
);

Map<String, dynamic> _$$TenantHealthResponseImplToJson(
  _$TenantHealthResponseImpl instance,
) => <String, dynamic>{
  'tenantId': instance.tenantId,
  'status': instance.status,
  'hostBaseUrlSet': instance.hostBaseUrlSet,
  'authBindingResolves': instance.authBindingResolves,
  'recordingStrategyResolves': instance.recordingStrategyResolves,
  'dependencyCount': instance.dependencyCount,
  'warnings': instance.warnings,
};
