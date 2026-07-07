// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'workspace.dart';

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

_$WorkspaceImpl _$$WorkspaceImplFromJson(Map<String, dynamic> json) =>
    _$WorkspaceImpl(
      id: json['id'] as String,
      code: json['code'] as String,
      displayName: json['displayName'] as String,
      status: json['status'] as String?,
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
      archivedAt: json['archivedAt'] as String?,
      createdAt: json['createdAt'] as String?,
    );

Map<String, dynamic> _$$WorkspaceImplToJson(_$WorkspaceImpl instance) =>
    <String, dynamic>{
      'id': instance.id,
      'code': instance.code,
      'displayName': instance.displayName,
      'status': instance.status,
      'hostBaseUrl': instance.hostBaseUrl,
      'hostBaseUrlAllowlist': instance.hostBaseUrlAllowlist,
      'inboundOriginAllowlist': instance.inboundOriginAllowlist,
      'archivedAt': instance.archivedAt,
      'createdAt': instance.createdAt,
    };

_$WorkspaceListItemImpl _$$WorkspaceListItemImplFromJson(
  Map<String, dynamic> json,
) => _$WorkspaceListItemImpl(
  workspace: Workspace.fromJson(json['workspace'] as Map<String, dynamic>),
  role: json['role'] as String,
);

Map<String, dynamic> _$$WorkspaceListItemImplToJson(
  _$WorkspaceListItemImpl instance,
) => <String, dynamic>{'workspace': instance.workspace, 'role': instance.role};

_$WorkspaceCreateRequestImpl _$$WorkspaceCreateRequestImplFromJson(
  Map<String, dynamic> json,
) => _$WorkspaceCreateRequestImpl(
  displayName: json['displayName'] as String,
  code: json['code'] as String?,
  hostBaseUrl: json['hostBaseUrl'] as String?,
  hostBaseUrlAllowlist: (json['hostBaseUrlAllowlist'] as List<dynamic>?)
      ?.map((e) => e as String)
      .toList(),
  inboundOriginAllowlist: (json['inboundOriginAllowlist'] as List<dynamic>?)
      ?.map((e) => e as String)
      .toList(),
);

Map<String, dynamic> _$$WorkspaceCreateRequestImplToJson(
  _$WorkspaceCreateRequestImpl instance,
) => <String, dynamic>{
  'displayName': instance.displayName,
  'code': instance.code,
  'hostBaseUrl': instance.hostBaseUrl,
  'hostBaseUrlAllowlist': instance.hostBaseUrlAllowlist,
  'inboundOriginAllowlist': instance.inboundOriginAllowlist,
};

_$WorkspacePatchRequestImpl _$$WorkspacePatchRequestImplFromJson(
  Map<String, dynamic> json,
) => _$WorkspacePatchRequestImpl(
  displayName: json['displayName'] as String?,
  hostBaseUrl: json['hostBaseUrl'] as String?,
  hostBaseUrlAllowlist: (json['hostBaseUrlAllowlist'] as List<dynamic>?)
      ?.map((e) => e as String)
      .toList(),
  inboundOriginAllowlist: (json['inboundOriginAllowlist'] as List<dynamic>?)
      ?.map((e) => e as String)
      .toList(),
);

Map<String, dynamic> _$$WorkspacePatchRequestImplToJson(
  _$WorkspacePatchRequestImpl instance,
) => <String, dynamic>{
  'displayName': instance.displayName,
  'hostBaseUrl': instance.hostBaseUrl,
  'hostBaseUrlAllowlist': instance.hostBaseUrlAllowlist,
  'inboundOriginAllowlist': instance.inboundOriginAllowlist,
};

_$WorkspaceArchiveRequestImpl _$$WorkspaceArchiveRequestImplFromJson(
  Map<String, dynamic> json,
) => _$WorkspaceArchiveRequestImpl(confirmCode: json['confirmCode'] as String);

Map<String, dynamic> _$$WorkspaceArchiveRequestImplToJson(
  _$WorkspaceArchiveRequestImpl instance,
) => <String, dynamic>{'confirmCode': instance.confirmCode};
