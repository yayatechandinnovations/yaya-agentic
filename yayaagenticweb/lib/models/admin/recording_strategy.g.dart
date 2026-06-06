// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'recording_strategy.dart';

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

_$RecordingStrategyRequestImpl _$$RecordingStrategyRequestImplFromJson(
  Map<String, dynamic> json,
) => _$RecordingStrategyRequestImpl(
  tenant: json['tenant'] as String,
  scopeKind: json['scopeKind'] as String,
  scopeId: json['scopeId'] as String,
  strategy: json['strategy'] as Map<String, dynamic>,
);

Map<String, dynamic> _$$RecordingStrategyRequestImplToJson(
  _$RecordingStrategyRequestImpl instance,
) => <String, dynamic>{
  'tenant': instance.tenant,
  'scopeKind': instance.scopeKind,
  'scopeId': instance.scopeId,
  'strategy': instance.strategy,
};

_$RecordingStrategyResponseImpl _$$RecordingStrategyResponseImplFromJson(
  Map<String, dynamic> json,
) => _$RecordingStrategyResponseImpl(
  tenant: json['tenant'] as String,
  scopeKind: json['scopeKind'] as String,
  scopeId: json['scopeId'] as String,
  strategy: json['strategy'] as Map<String, dynamic>,
  version: (json['version'] as num).toInt(),
  createdAt: json['createdAt'] as String?,
);

Map<String, dynamic> _$$RecordingStrategyResponseImplToJson(
  _$RecordingStrategyResponseImpl instance,
) => <String, dynamic>{
  'tenant': instance.tenant,
  'scopeKind': instance.scopeKind,
  'scopeId': instance.scopeId,
  'strategy': instance.strategy,
  'version': instance.version,
  'createdAt': instance.createdAt,
};
