import 'package:freezed_annotation/freezed_annotation.dart';

part 'workspace.freezed.dart';
part 'workspace.g.dart';

/// Mirrors backend `WorkspaceController.WorkspaceDto`. {@code id} is the
/// stable identifier (today the slug; V24 swaps it to UUID); {@code code}
/// is the human-readable slug surfaced in URLs and UI.
///
/// {@code hostBaseUrl} and {@code hostBaseUrlAllowlist} are the resolver
/// inputs for HTTP-tool dispatch — when both are absent, any HTTP tool
/// call inside this workspace fails at the resolver. They surface on the
/// General settings tab so operators can fix that without leaving the
/// workspace plane.
@freezed
class Workspace with _$Workspace {
  const factory Workspace({
    required String id,
    required String code,
    required String displayName,
    String? status,
    String? hostBaseUrl,
    @Default(<String>[]) List<String> hostBaseUrlAllowlist,
    String? archivedAt,
    String? createdAt,
  }) = _Workspace;

  factory Workspace.fromJson(Map<String, dynamic> json) =>
      _$WorkspaceFromJson(json);
}

/// Mirrors `WorkspaceListItemDto`. Used by the workspaces grid + the
/// switcher dropdown so each entry carries the caller's role inline.
@freezed
class WorkspaceListItem with _$WorkspaceListItem {
  const factory WorkspaceListItem({
    required Workspace workspace,
    required String role,
  }) = _WorkspaceListItem;

  factory WorkspaceListItem.fromJson(Map<String, dynamic> json) =>
      _$WorkspaceListItemFromJson(json);
}

/// Body for `POST /v1/admin/workspaces`.
@freezed
class WorkspaceCreateRequest with _$WorkspaceCreateRequest {
  const factory WorkspaceCreateRequest({
    required String displayName,
  }) = _WorkspaceCreateRequest;

  factory WorkspaceCreateRequest.fromJson(Map<String, dynamic> json) =>
      _$WorkspaceCreateRequestFromJson(json);
}

/// Body for `PATCH /v1/admin/workspaces/{idOrCode}`. Every field is
/// optional — backend treats {@code null} as "leave untouched". Empty
/// string in {@code hostBaseUrl} clears the value; an empty list in
/// {@code hostBaseUrlAllowlist} clears the allowlist.
@freezed
class WorkspacePatchRequest with _$WorkspacePatchRequest {
  const factory WorkspacePatchRequest({
    String? displayName,
    String? hostBaseUrl,
    List<String>? hostBaseUrlAllowlist,
  }) = _WorkspacePatchRequest;

  factory WorkspacePatchRequest.fromJson(Map<String, dynamic> json) =>
      _$WorkspacePatchRequestFromJson(json);
}

/// Body for `DELETE /v1/admin/workspaces/{idOrCode}`. The typed-name
/// confirmation matches the backend's archive guard — see
/// docs/specs/user-accounts/UI-UX.md §4.9.
@freezed
class WorkspaceArchiveRequest with _$WorkspaceArchiveRequest {
  const factory WorkspaceArchiveRequest({
    required String confirmCode,
  }) = _WorkspaceArchiveRequest;

  factory WorkspaceArchiveRequest.fromJson(Map<String, dynamic> json) =>
      _$WorkspaceArchiveRequestFromJson(json);
}
