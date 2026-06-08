// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'operator_auth_strategies.dart';

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

_$StrategiesResponseImpl _$$StrategiesResponseImplFromJson(
  Map<String, dynamic> json,
) => _$StrategiesResponseImpl(
  bootstrap: BootstrapView.fromJson(json['bootstrap'] as Map<String, dynamic>),
  delegate: DelegateView.fromJson(json['delegate'] as Map<String, dynamic>),
  updatedAt: json['updatedAt'] as String?,
  updatedBy: json['updatedBy'] as String?,
  canDisableBootstrap: json['canDisableBootstrap'] as bool? ?? false,
);

Map<String, dynamic> _$$StrategiesResponseImplToJson(
  _$StrategiesResponseImpl instance,
) => <String, dynamic>{
  'bootstrap': instance.bootstrap,
  'delegate': instance.delegate,
  'updatedAt': instance.updatedAt,
  'updatedBy': instance.updatedBy,
  'canDisableBootstrap': instance.canDisableBootstrap,
};

_$BootstrapViewImpl _$$BootstrapViewImplFromJson(Map<String, dynamic> json) =>
    _$BootstrapViewImpl(
      enabled: json['enabled'] as bool,
      username: json['username'] as String,
    );

Map<String, dynamic> _$$BootstrapViewImplToJson(_$BootstrapViewImpl instance) =>
    <String, dynamic>{
      'enabled': instance.enabled,
      'username': instance.username,
    };

_$DelegateViewImpl _$$DelegateViewImplFromJson(
  Map<String, dynamic> json,
) => _$DelegateViewImpl(
  enabled: json['enabled'] as bool,
  url: json['url'] as String?,
  sharedSecretMask: json['sharedSecretMask'] as String? ?? '',
  sharedSecretPresent: json['sharedSecretPresent'] as bool? ?? false,
  timeoutMs: (json['timeoutMs'] as num?)?.toInt() ?? 5000,
  requireHttps: json['requireHttps'] as bool? ?? true,
  request: RequestShape.fromJson(json['request'] as Map<String, dynamic>),
  success: SuccessCriteria.fromJson(json['success'] as Map<String, dynamic>),
  identity: IdentityMapping.fromJson(json['identity'] as Map<String, dynamic>),
  failure: FailureMapping.fromJson(json['failure'] as Map<String, dynamic>),
);

Map<String, dynamic> _$$DelegateViewImplToJson(_$DelegateViewImpl instance) =>
    <String, dynamic>{
      'enabled': instance.enabled,
      'url': instance.url,
      'sharedSecretMask': instance.sharedSecretMask,
      'sharedSecretPresent': instance.sharedSecretPresent,
      'timeoutMs': instance.timeoutMs,
      'requireHttps': instance.requireHttps,
      'request': instance.request,
      'success': instance.success,
      'identity': instance.identity,
      'failure': instance.failure,
    };

_$RequestShapeImpl _$$RequestShapeImplFromJson(Map<String, dynamic> json) =>
    _$RequestShapeImpl(
      method: json['method'] as String? ?? 'POST',
      headers:
          (json['headers'] as Map<String, dynamic>?)?.map(
            (k, e) => MapEntry(k, e as String),
          ) ??
          const <String, String>{},
      body: RequestBody.fromJson(json['body'] as Map<String, dynamic>),
    );

Map<String, dynamic> _$$RequestShapeImplToJson(_$RequestShapeImpl instance) =>
    <String, dynamic>{
      'method': instance.method,
      'headers': instance.headers,
      'body': instance.body,
    };

_$RequestBodyImpl _$$RequestBodyImplFromJson(Map<String, dynamic> json) =>
    _$RequestBodyImpl(
      format: json['format'] as String? ?? 'JSON',
      template: json['template'] as String?,
    );

Map<String, dynamic> _$$RequestBodyImplToJson(_$RequestBodyImpl instance) =>
    <String, dynamic>{'format': instance.format, 'template': instance.template};

_$SuccessCriteriaImpl _$$SuccessCriteriaImplFromJson(
  Map<String, dynamic> json,
) => _$SuccessCriteriaImpl(
  statusIn:
      (json['statusIn'] as List<dynamic>?)
          ?.map((e) => (e as num).toInt())
          .toList() ??
      const <int>[200, 204],
  jsonPathExists: json['jsonPathExists'] as String?,
  jsonPathEquals:
      (json['jsonPathEquals'] as List<dynamic>?)
          ?.map((e) => JsonPathEquals.fromJson(e as Map<String, dynamic>))
          .toList() ??
      const <JsonPathEquals>[],
);

Map<String, dynamic> _$$SuccessCriteriaImplToJson(
  _$SuccessCriteriaImpl instance,
) => <String, dynamic>{
  'statusIn': instance.statusIn,
  'jsonPathExists': instance.jsonPathExists,
  'jsonPathEquals': instance.jsonPathEquals,
};

_$JsonPathEqualsImpl _$$JsonPathEqualsImplFromJson(Map<String, dynamic> json) =>
    _$JsonPathEqualsImpl(path: json['path'] as String, value: json['value']);

Map<String, dynamic> _$$JsonPathEqualsImplToJson(
  _$JsonPathEqualsImpl instance,
) => <String, dynamic>{'path': instance.path, 'value': instance.value};

_$IdentityMappingImpl _$$IdentityMappingImplFromJson(
  Map<String, dynamic> json,
) => _$IdentityMappingImpl(
  subjectPath: json['subjectPath'] as String?,
  displayNamePath: json['displayNamePath'] as String?,
  attributesPath: json['attributesPath'] as String?,
);

Map<String, dynamic> _$$IdentityMappingImplToJson(
  _$IdentityMappingImpl instance,
) => <String, dynamic>{
  'subjectPath': instance.subjectPath,
  'displayNamePath': instance.displayNamePath,
  'attributesPath': instance.attributesPath,
};

_$FailureMappingImpl _$$FailureMappingImplFromJson(Map<String, dynamic> json) =>
    _$FailureMappingImpl(reasonPath: json['reasonPath'] as String?);

Map<String, dynamic> _$$FailureMappingImplToJson(
  _$FailureMappingImpl instance,
) => <String, dynamic>{'reasonPath': instance.reasonPath};

_$ProbeResultImpl _$$ProbeResultImplFromJson(
  Map<String, dynamic> json,
) => _$ProbeResultImpl(
  request: SentRequest.fromJson(json['request'] as Map<String, dynamic>),
  response: ReceivedResponse.fromJson(json['response'] as Map<String, dynamic>),
  evaluation: Evaluation.fromJson(json['evaluation'] as Map<String, dynamic>),
  allowed: json['allowed'] as bool? ?? false,
  auditReason: json['auditReason'] as String?,
);

Map<String, dynamic> _$$ProbeResultImplToJson(_$ProbeResultImpl instance) =>
    <String, dynamic>{
      'request': instance.request,
      'response': instance.response,
      'evaluation': instance.evaluation,
      'allowed': instance.allowed,
      'auditReason': instance.auditReason,
    };

_$SentRequestImpl _$$SentRequestImplFromJson(Map<String, dynamic> json) =>
    _$SentRequestImpl(
      method: json['method'] as String,
      url: json['url'] as String,
      headers:
          (json['headers'] as Map<String, dynamic>?)?.map(
            (k, e) => MapEntry(k, e as String),
          ) ??
          const <String, String>{},
      body: json['body'] as String?,
    );

Map<String, dynamic> _$$SentRequestImplToJson(_$SentRequestImpl instance) =>
    <String, dynamic>{
      'method': instance.method,
      'url': instance.url,
      'headers': instance.headers,
      'body': instance.body,
    };

_$ReceivedResponseImpl _$$ReceivedResponseImplFromJson(
  Map<String, dynamic> json,
) => _$ReceivedResponseImpl(
  status: (json['status'] as num?)?.toInt(),
  durationMs: (json['durationMs'] as num?)?.toInt() ?? 0,
  headers:
      (json['headers'] as Map<String, dynamic>?)?.map(
        (k, e) => MapEntry(k, e as String),
      ) ??
      const <String, String>{},
  body: json['body'] as String?,
  error: json['error'] as String?,
);

Map<String, dynamic> _$$ReceivedResponseImplToJson(
  _$ReceivedResponseImpl instance,
) => <String, dynamic>{
  'status': instance.status,
  'durationMs': instance.durationMs,
  'headers': instance.headers,
  'body': instance.body,
  'error': instance.error,
};

_$EvaluationImpl _$$EvaluationImplFromJson(Map<String, dynamic> json) =>
    _$EvaluationImpl(
      successChecks:
          (json['successChecks'] as List<dynamic>?)
              ?.map((e) => CriterionCheck.fromJson(e as Map<String, dynamic>))
              .toList() ??
          const <CriterionCheck>[],
      identity: json['identity'] == null
          ? null
          : ExtractedIdentity.fromJson(
              json['identity'] as Map<String, dynamic>,
            ),
      decision: json['decision'] as String? ?? 'DENY',
      auditReason: json['auditReason'] as String?,
    );

Map<String, dynamic> _$$EvaluationImplToJson(_$EvaluationImpl instance) =>
    <String, dynamic>{
      'successChecks': instance.successChecks,
      'identity': instance.identity,
      'decision': instance.decision,
      'auditReason': instance.auditReason,
    };

_$CriterionCheckImpl _$$CriterionCheckImplFromJson(Map<String, dynamic> json) =>
    _$CriterionCheckImpl(
      criterion: json['criterion'] as String,
      matched: json['matched'] as bool,
      detail: json['detail'] as String?,
    );

Map<String, dynamic> _$$CriterionCheckImplToJson(
  _$CriterionCheckImpl instance,
) => <String, dynamic>{
  'criterion': instance.criterion,
  'matched': instance.matched,
  'detail': instance.detail,
};

_$ExtractedIdentityImpl _$$ExtractedIdentityImplFromJson(
  Map<String, dynamic> json,
) => _$ExtractedIdentityImpl(
  subject: json['subject'] as String,
  displayName: json['displayName'] as String,
  attributes:
      json['attributes'] as Map<String, dynamic>? ?? const <String, dynamic>{},
);

Map<String, dynamic> _$$ExtractedIdentityImplToJson(
  _$ExtractedIdentityImpl instance,
) => <String, dynamic>{
  'subject': instance.subject,
  'displayName': instance.displayName,
  'attributes': instance.attributes,
};
