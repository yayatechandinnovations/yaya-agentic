// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'clone.dart';

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

_$CloneRequestImpl _$$CloneRequestImplFromJson(Map<String, dynamic> json) =>
    _$CloneRequestImpl(
      destinationTenant: json['destinationTenant'] as String,
      destinationProfileId: json['destinationProfileId'] as String?,
      conflictPolicy: json['conflictPolicy'] as String? ?? 'FAIL',
      knowledgeLocationStrategy:
          json['knowledgeLocationStrategy'] as String? ?? 'RETAIN',
      personalityPolicy: json['personalityPolicy'] as String? ?? 'AUTO',
      dryRun: json['dryRun'] as bool? ?? true,
    );

Map<String, dynamic> _$$CloneRequestImplToJson(_$CloneRequestImpl instance) =>
    <String, dynamic>{
      'destinationTenant': instance.destinationTenant,
      'destinationProfileId': instance.destinationProfileId,
      'conflictPolicy': instance.conflictPolicy,
      'knowledgeLocationStrategy': instance.knowledgeLocationStrategy,
      'personalityPolicy': instance.personalityPolicy,
      'dryRun': instance.dryRun,
    };

_$ResourceActionImpl _$$ResourceActionImplFromJson(Map<String, dynamic> json) =>
    _$ResourceActionImpl(
      id: json['id'] as String,
      action: json['action'] as String,
      fromVersion: (json['fromVersion'] as num?)?.toInt(),
      toVersion: (json['toVersion'] as num?)?.toInt(),
      notes:
          (json['notes'] as List<dynamic>?)?.map((e) => e as String).toList() ??
          const <String>[],
    );

Map<String, dynamic> _$$ResourceActionImplToJson(
  _$ResourceActionImpl instance,
) => <String, dynamic>{
  'id': instance.id,
  'action': instance.action,
  'fromVersion': instance.fromVersion,
  'toVersion': instance.toVersion,
  'notes': instance.notes,
};

_$KnowledgeActionImpl _$$KnowledgeActionImplFromJson(
  Map<String, dynamic> json,
) => _$KnowledgeActionImpl(
  id: json['id'] as String,
  action: json['action'] as String,
  fromVersion: (json['fromVersion'] as num?)?.toInt(),
  toVersion: (json['toVersion'] as num?)?.toInt(),
  locationKind: json['locationKind'] as String?,
  location:
      json['location'] as Map<String, dynamic>? ?? const <String, dynamic>{},
  notes:
      (json['notes'] as List<dynamic>?)?.map((e) => e as String).toList() ??
      const <String>[],
);

Map<String, dynamic> _$$KnowledgeActionImplToJson(
  _$KnowledgeActionImpl instance,
) => <String, dynamic>{
  'id': instance.id,
  'action': instance.action,
  'fromVersion': instance.fromVersion,
  'toVersion': instance.toVersion,
  'locationKind': instance.locationKind,
  'location': instance.location,
  'notes': instance.notes,
};

_$PersonalityActionImpl _$$PersonalityActionImplFromJson(
  Map<String, dynamic> json,
) => _$PersonalityActionImpl(
  locale: json['locale'] as String?,
  action: json['action'] as String,
  fromVersion: (json['fromVersion'] as num?)?.toInt(),
  toVersion: (json['toVersion'] as num?)?.toInt(),
  notes:
      (json['notes'] as List<dynamic>?)?.map((e) => e as String).toList() ??
      const <String>[],
);

Map<String, dynamic> _$$PersonalityActionImplToJson(
  _$PersonalityActionImpl instance,
) => <String, dynamic>{
  'locale': instance.locale,
  'action': instance.action,
  'fromVersion': instance.fromVersion,
  'toVersion': instance.toVersion,
  'notes': instance.notes,
};

_$ClonePlanImpl _$$ClonePlanImplFromJson(
  Map<String, dynamic> json,
) => _$ClonePlanImpl(
  sourceTenant: json['sourceTenant'] as String,
  destinationTenant: json['destinationTenant'] as String,
  destinationProfileId: json['destinationProfileId'] as String,
  profile: ResourceAction.fromJson(json['profile'] as Map<String, dynamic>),
  capabilities:
      (json['capabilities'] as List<dynamic>?)
          ?.map((e) => ResourceAction.fromJson(e as Map<String, dynamic>))
          .toList() ??
      const <ResourceAction>[],
  tools:
      (json['tools'] as List<dynamic>?)
          ?.map((e) => ResourceAction.fromJson(e as Map<String, dynamic>))
          .toList() ??
      const <ResourceAction>[],
  knowledgeSources:
      (json['knowledgeSources'] as List<dynamic>?)
          ?.map((e) => KnowledgeAction.fromJson(e as Map<String, dynamic>))
          .toList() ??
      const <KnowledgeAction>[],
  authBindings:
      (json['authBindings'] as List<dynamic>?)
          ?.map((e) => ResourceAction.fromJson(e as Map<String, dynamic>))
          .toList() ??
      const <ResourceAction>[],
  recordingStrategies:
      (json['recordingStrategies'] as List<dynamic>?)
          ?.map((e) => ResourceAction.fromJson(e as Map<String, dynamic>))
          .toList() ??
      const <ResourceAction>[],
  personality:
      (json['personality'] as List<dynamic>?)
          ?.map((e) => PersonalityAction.fromJson(e as Map<String, dynamic>))
          .toList() ??
      const <PersonalityAction>[],
  warnings:
      (json['warnings'] as List<dynamic>?)?.map((e) => e as String).toList() ??
      const <String>[],
);

Map<String, dynamic> _$$ClonePlanImplToJson(_$ClonePlanImpl instance) =>
    <String, dynamic>{
      'sourceTenant': instance.sourceTenant,
      'destinationTenant': instance.destinationTenant,
      'destinationProfileId': instance.destinationProfileId,
      'profile': instance.profile,
      'capabilities': instance.capabilities,
      'tools': instance.tools,
      'knowledgeSources': instance.knowledgeSources,
      'authBindings': instance.authBindings,
      'recordingStrategies': instance.recordingStrategies,
      'personality': instance.personality,
      'warnings': instance.warnings,
    };

_$CloneResultImpl _$$CloneResultImplFromJson(Map<String, dynamic> json) =>
    _$CloneResultImpl(
      jobId: json['jobId'] as String?,
      status: json['status'] as String,
      plan: ClonePlan.fromJson(json['plan'] as Map<String, dynamic>),
      errorCode: json['errorCode'] as String?,
      errorMessage: json['errorMessage'] as String?,
    );

Map<String, dynamic> _$$CloneResultImplToJson(_$CloneResultImpl instance) =>
    <String, dynamic>{
      'jobId': instance.jobId,
      'status': instance.status,
      'plan': instance.plan,
      'errorCode': instance.errorCode,
      'errorMessage': instance.errorMessage,
    };

_$MigrateToPathPlanImpl _$$MigrateToPathPlanImplFromJson(
  Map<String, dynamic> json,
) => _$MigrateToPathPlanImpl(
  candidates:
      (json['candidates'] as List<dynamic>?)
          ?.map((e) => MigrateCandidate.fromJson(e as Map<String, dynamic>))
          .toList() ??
      const <MigrateCandidate>[],
  unsafe:
      (json['unsafe'] as List<dynamic>?)
          ?.map((e) => MigrateUnsafe.fromJson(e as Map<String, dynamic>))
          .toList() ??
      const <MigrateUnsafe>[],
);

Map<String, dynamic> _$$MigrateToPathPlanImplToJson(
  _$MigrateToPathPlanImpl instance,
) => <String, dynamic>{
  'candidates': instance.candidates,
  'unsafe': instance.unsafe,
};

_$MigrateCandidateImpl _$$MigrateCandidateImplFromJson(
  Map<String, dynamic> json,
) => _$MigrateCandidateImpl(
  toolId: json['toolId'] as String,
  version: (json['version'] as num).toInt(),
  current: json['current'] as String,
  rewritten: json['rewritten'] as String,
);

Map<String, dynamic> _$$MigrateCandidateImplToJson(
  _$MigrateCandidateImpl instance,
) => <String, dynamic>{
  'toolId': instance.toolId,
  'version': instance.version,
  'current': instance.current,
  'rewritten': instance.rewritten,
};

_$MigrateUnsafeImpl _$$MigrateUnsafeImplFromJson(Map<String, dynamic> json) =>
    _$MigrateUnsafeImpl(
      toolId: json['toolId'] as String,
      version: (json['version'] as num).toInt(),
      current: json['current'] as String,
      reason: json['reason'] as String,
    );

Map<String, dynamic> _$$MigrateUnsafeImplToJson(_$MigrateUnsafeImpl instance) =>
    <String, dynamic>{
      'toolId': instance.toolId,
      'version': instance.version,
      'current': instance.current,
      'reason': instance.reason,
    };
