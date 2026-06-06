// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'capability.dart';

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

_$CapabilityRequestImpl _$$CapabilityRequestImplFromJson(
  Map<String, dynamic> json,
) => _$CapabilityRequestImpl(
  tenant: json['tenant'] as String,
  id: json['id'] as String,
  label: json['label'] as String,
  description: json['description'] as String?,
  llmGuidance: json['llmGuidance'] as String?,
  tools:
      (json['tools'] as List<dynamic>?)?.map((e) => e as String).toList() ??
      const <String>[],
);

Map<String, dynamic> _$$CapabilityRequestImplToJson(
  _$CapabilityRequestImpl instance,
) => <String, dynamic>{
  'tenant': instance.tenant,
  'id': instance.id,
  'label': instance.label,
  'description': instance.description,
  'llmGuidance': instance.llmGuidance,
  'tools': instance.tools,
};

_$CapabilityResponseImpl _$$CapabilityResponseImplFromJson(
  Map<String, dynamic> json,
) => _$CapabilityResponseImpl(
  id: json['id'] as String,
  version: (json['version'] as num).toInt(),
  tenant: json['tenant'] as String,
  label: json['label'] as String,
  description: json['description'] as String?,
  llmGuidance: json['llmGuidance'] as String?,
  tools:
      (json['tools'] as List<dynamic>?)?.map((e) => e as String).toList() ??
      const <String>[],
  createdAt: json['createdAt'] as String?,
);

Map<String, dynamic> _$$CapabilityResponseImplToJson(
  _$CapabilityResponseImpl instance,
) => <String, dynamic>{
  'id': instance.id,
  'version': instance.version,
  'tenant': instance.tenant,
  'label': instance.label,
  'description': instance.description,
  'llmGuidance': instance.llmGuidance,
  'tools': instance.tools,
  'createdAt': instance.createdAt,
};
