// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'tool.dart';

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

_$HttpBodyDtoImpl _$$HttpBodyDtoImplFromJson(Map<String, dynamic> json) =>
    _$HttpBodyDtoImpl(
      contentType: json['contentType'] as String?,
      template: json['template'] as String?,
    );

Map<String, dynamic> _$$HttpBodyDtoImplToJson(_$HttpBodyDtoImpl instance) =>
    <String, dynamic>{
      'contentType': instance.contentType,
      'template': instance.template,
    };

_$HttpResponseDtoImpl _$$HttpResponseDtoImplFromJson(
  Map<String, dynamic> json,
) => _$HttpResponseDtoImpl(
  jsonPath: json['jsonPath'] as String?,
  headerOutputs:
      (json['headerOutputs'] as Map<String, dynamic>?)?.map(
        (k, e) => MapEntry(k, e as String),
      ) ??
      const <String, String>{},
);

Map<String, dynamic> _$$HttpResponseDtoImplToJson(
  _$HttpResponseDtoImpl instance,
) => <String, dynamic>{
  'jsonPath': instance.jsonPath,
  'headerOutputs': instance.headerOutputs,
};

_$HttpHandlerDtoImpl _$$HttpHandlerDtoImplFromJson(Map<String, dynamic> json) =>
    _$HttpHandlerDtoImpl(
      method: json['method'] as String,
      urlTemplate: json['urlTemplate'] as String,
      headerTemplates:
          (json['headerTemplates'] as Map<String, dynamic>?)?.map(
            (k, e) => MapEntry(k, e as String),
          ) ??
          const <String, String>{},
      body: json['body'] == null
          ? null
          : HttpBodyDto.fromJson(json['body'] as Map<String, dynamic>),
      response: json['response'] == null
          ? null
          : HttpResponseDto.fromJson(json['response'] as Map<String, dynamic>),
      authForwarding: json['authForwarding'] as String? ?? 'NONE',
    );

Map<String, dynamic> _$$HttpHandlerDtoImplToJson(
  _$HttpHandlerDtoImpl instance,
) => <String, dynamic>{
  'method': instance.method,
  'urlTemplate': instance.urlTemplate,
  'headerTemplates': instance.headerTemplates,
  'body': instance.body,
  'response': instance.response,
  'authForwarding': instance.authForwarding,
};

_$ToolHandlerDtoImpl _$$ToolHandlerDtoImplFromJson(Map<String, dynamic> json) =>
    _$ToolHandlerDtoImpl(
      kind: json['kind'] as String,
      beanName: json['beanName'] as String?,
      httpSpec: json['httpSpec'] == null
          ? null
          : HttpHandlerDto.fromJson(json['httpSpec'] as Map<String, dynamic>),
    );

Map<String, dynamic> _$$ToolHandlerDtoImplToJson(
  _$ToolHandlerDtoImpl instance,
) => <String, dynamic>{
  'kind': instance.kind,
  'beanName': instance.beanName,
  'httpSpec': instance.httpSpec,
};

_$ToolRequestImpl _$$ToolRequestImplFromJson(
  Map<String, dynamic> json,
) => _$ToolRequestImpl(
  tenant: json['tenant'] as String,
  id: json['id'] as String,
  inputSchemaJson: json['inputSchemaJson'] as String,
  outputSchemaJson: json['outputSchemaJson'] as String,
  requires:
      json['requires'] as Map<String, dynamic>? ?? const <String, dynamic>{},
  handler: ToolHandlerDto.fromJson(json['handler'] as Map<String, dynamic>),
  policy: json['policy'] as Map<String, dynamic>? ?? const <String, dynamic>{},
);

Map<String, dynamic> _$$ToolRequestImplToJson(_$ToolRequestImpl instance) =>
    <String, dynamic>{
      'tenant': instance.tenant,
      'id': instance.id,
      'inputSchemaJson': instance.inputSchemaJson,
      'outputSchemaJson': instance.outputSchemaJson,
      'requires': instance.requires,
      'handler': instance.handler,
      'policy': instance.policy,
    };

_$ToolResponseImpl _$$ToolResponseImplFromJson(
  Map<String, dynamic> json,
) => _$ToolResponseImpl(
  id: json['id'] as String,
  version: (json['version'] as num).toInt(),
  tenant: json['tenant'] as String,
  inputSchemaJson: json['inputSchemaJson'] as String,
  outputSchemaJson: json['outputSchemaJson'] as String,
  requires:
      json['requires'] as Map<String, dynamic>? ?? const <String, dynamic>{},
  handler: ToolHandlerDto.fromJson(json['handler'] as Map<String, dynamic>),
  policy: json['policy'] as Map<String, dynamic>? ?? const <String, dynamic>{},
  status: json['status'] as String?,
  createdAt: json['createdAt'] as String?,
);

Map<String, dynamic> _$$ToolResponseImplToJson(_$ToolResponseImpl instance) =>
    <String, dynamic>{
      'id': instance.id,
      'version': instance.version,
      'tenant': instance.tenant,
      'inputSchemaJson': instance.inputSchemaJson,
      'outputSchemaJson': instance.outputSchemaJson,
      'requires': instance.requires,
      'handler': instance.handler,
      'policy': instance.policy,
      'status': instance.status,
      'createdAt': instance.createdAt,
    };
