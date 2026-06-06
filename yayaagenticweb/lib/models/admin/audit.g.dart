// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'audit.dart';

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

_$AuthzAuditEntryImpl _$$AuthzAuditEntryImplFromJson(
  Map<String, dynamic> json,
) => _$AuthzAuditEntryImpl(
  id: (json['id'] as num).toInt(),
  tenant: json['tenant'] as String,
  sessionId: json['sessionId'] as String?,
  turnId: json['turnId'] as String?,
  principal: json['principal'] as String?,
  toolId: json['toolId'] as String?,
  decision: json['decision'] as String,
  userReason: json['userReason'] as String?,
  auditReason: json['auditReason'] as String?,
  policyTrace:
      json['policyTrace'] as Map<String, dynamic>? ?? const <String, dynamic>{},
  createdAt: json['createdAt'] as String?,
);

Map<String, dynamic> _$$AuthzAuditEntryImplToJson(
  _$AuthzAuditEntryImpl instance,
) => <String, dynamic>{
  'id': instance.id,
  'tenant': instance.tenant,
  'sessionId': instance.sessionId,
  'turnId': instance.turnId,
  'principal': instance.principal,
  'toolId': instance.toolId,
  'decision': instance.decision,
  'userReason': instance.userReason,
  'auditReason': instance.auditReason,
  'policyTrace': instance.policyTrace,
  'createdAt': instance.createdAt,
};

_$AuthzAuditPageImpl _$$AuthzAuditPageImplFromJson(Map<String, dynamic> json) =>
    _$AuthzAuditPageImpl(
      items:
          (json['items'] as List<dynamic>?)
              ?.map((e) => AuthzAuditEntry.fromJson(e as Map<String, dynamic>))
              .toList() ??
          const <AuthzAuditEntry>[],
      page: (json['page'] as num).toInt(),
      pageSize: (json['pageSize'] as num).toInt(),
      total: (json['total'] as num).toInt(),
    );

Map<String, dynamic> _$$AuthzAuditPageImplToJson(
  _$AuthzAuditPageImpl instance,
) => <String, dynamic>{
  'items': instance.items,
  'page': instance.page,
  'pageSize': instance.pageSize,
  'total': instance.total,
};
