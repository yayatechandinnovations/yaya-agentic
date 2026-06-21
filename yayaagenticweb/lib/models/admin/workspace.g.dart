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
      archivedAt: json['archivedAt'] as String?,
      createdAt: json['createdAt'] as String?,
    );

Map<String, dynamic> _$$WorkspaceImplToJson(_$WorkspaceImpl instance) =>
    <String, dynamic>{
      'id': instance.id,
      'code': instance.code,
      'displayName': instance.displayName,
      'status': instance.status,
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
) => _$WorkspaceCreateRequestImpl(displayName: json['displayName'] as String);

Map<String, dynamic> _$$WorkspaceCreateRequestImplToJson(
  _$WorkspaceCreateRequestImpl instance,
) => <String, dynamic>{'displayName': instance.displayName};

_$WorkspacePatchRequestImpl _$$WorkspacePatchRequestImplFromJson(
  Map<String, dynamic> json,
) => _$WorkspacePatchRequestImpl(displayName: json['displayName'] as String?);

Map<String, dynamic> _$$WorkspacePatchRequestImplToJson(
  _$WorkspacePatchRequestImpl instance,
) => <String, dynamic>{'displayName': instance.displayName};

_$WorkspaceArchiveRequestImpl _$$WorkspaceArchiveRequestImplFromJson(
  Map<String, dynamic> json,
) => _$WorkspaceArchiveRequestImpl(confirmCode: json['confirmCode'] as String);

Map<String, dynamic> _$$WorkspaceArchiveRequestImplToJson(
  _$WorkspaceArchiveRequestImpl instance,
) => <String, dynamic>{'confirmCode': instance.confirmCode};
