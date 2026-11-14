// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'profile.dart';

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

_$ProfileRequestImpl _$$ProfileRequestImplFromJson(Map<String, dynamic> json) =>
    _$ProfileRequestImpl(
      tenant: json['tenant'] as String,
      id: json['id'] as String,
      displayName: json['displayName'] as String,
      introOneLiner: json['introOneLiner'] as String,
      systemPromptFragment: json['systemPromptFragment'] as String,
      capabilities:
          (json['capabilities'] as List<dynamic>?)
              ?.map((e) => e as String)
              .toList() ??
          const <String>[],
      authBindingId: json['authBindingId'] as String?,
      language: json['language'] as String? ?? 'en',
      metadata:
          json['metadata'] as Map<String, dynamic>? ??
          const <String, dynamic>{},
      extensions: json['extensions'] as Map<String, dynamic>?,
      defaultStartFlowId: json['defaultStartFlowId'] as String?,
      model: json['model'] as String?,
      maxToolRounds: (json['maxToolRounds'] as num?)?.toInt(),
      metered: json['metered'] as bool?,
    );

Map<String, dynamic> _$$ProfileRequestImplToJson(
  _$ProfileRequestImpl instance,
) => <String, dynamic>{
  'tenant': instance.tenant,
  'id': instance.id,
  'displayName': instance.displayName,
  'introOneLiner': instance.introOneLiner,
  'systemPromptFragment': instance.systemPromptFragment,
  'capabilities': instance.capabilities,
  'authBindingId': instance.authBindingId,
  'language': instance.language,
  'metadata': instance.metadata,
  'extensions': instance.extensions,
  'defaultStartFlowId': instance.defaultStartFlowId,
  'model': instance.model,
  'maxToolRounds': instance.maxToolRounds,
  'metered': instance.metered,
};

_$ProfileResponseImpl _$$ProfileResponseImplFromJson(
  Map<String, dynamic> json,
) => _$ProfileResponseImpl(
  id: json['id'] as String,
  version: (json['version'] as num).toInt(),
  tenant: json['tenant'] as String,
  displayName: json['displayName'] as String,
  introOneLiner: json['introOneLiner'] as String,
  systemPromptFragment: json['systemPromptFragment'] as String,
  capabilities:
      (json['capabilities'] as List<dynamic>?)
          ?.map((e) => e as String)
          .toList() ??
      const <String>[],
  authBindingId: json['authBindingId'] as String?,
  language: json['language'] as String? ?? 'en',
  metadata:
      json['metadata'] as Map<String, dynamic>? ?? const <String, dynamic>{},
  status: json['status'] as String?,
  createdAt: json['createdAt'] as String?,
  extensions: json['extensions'] as Map<String, dynamic>?,
  defaultStartFlowId: json['defaultStartFlowId'] as String?,
  model: json['model'] as String?,
  maxToolRounds: (json['maxToolRounds'] as num?)?.toInt(),
  metered: json['metered'] as bool? ?? false,
);

Map<String, dynamic> _$$ProfileResponseImplToJson(
  _$ProfileResponseImpl instance,
) => <String, dynamic>{
  'id': instance.id,
  'version': instance.version,
  'tenant': instance.tenant,
  'displayName': instance.displayName,
  'introOneLiner': instance.introOneLiner,
  'systemPromptFragment': instance.systemPromptFragment,
  'capabilities': instance.capabilities,
  'authBindingId': instance.authBindingId,
  'language': instance.language,
  'metadata': instance.metadata,
  'status': instance.status,
  'createdAt': instance.createdAt,
  'extensions': instance.extensions,
  'defaultStartFlowId': instance.defaultStartFlowId,
  'model': instance.model,
  'maxToolRounds': instance.maxToolRounds,
  'metered': instance.metered,
};
