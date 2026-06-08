import 'package:freezed_annotation/freezed_annotation.dart';

part 'tenant.freezed.dart';
part 'tenant.g.dart';

/// Mirrors backend AdminDtos.TenantRequest.
@freezed
class TenantRequest with _$TenantRequest {
  const factory TenantRequest({
    String? id,
    String? displayName,
    String? hostBaseUrl,
    @Default(<String>[]) List<String> hostBaseUrlAllowlist,
    @Default(<String>[]) List<String> inboundOriginAllowlist,
    @Default(true) bool? requireHttps,
    String? defaultAuthenticatorBindingId,
    int? defaultRecordingStrategyId,
    @Default(<String, dynamic>{}) Map<String, dynamic> settings,
  }) = _TenantRequest;
  factory TenantRequest.fromJson(Map<String, dynamic> json) =>
      _$TenantRequestFromJson(json);
}

/// Mirrors backend AdminDtos.TenantResponse.
@freezed
class TenantResponse with _$TenantResponse {
  const factory TenantResponse({
    required String id,
    required String displayName,
    required String status,                     // ACTIVE | SUSPENDED | ARCHIVED
    String? hostBaseUrl,
    @Default(<String>[]) List<String> hostBaseUrlAllowlist,
    @Default(<String>[]) List<String> inboundOriginAllowlist,
    @Default(true) bool requireHttps,
    String? defaultAuthenticatorBindingId,
    int? defaultRecordingStrategyId,
    @Default(<String, dynamic>{}) Map<String, dynamic> settings,
    String? createdAt,
    String? updatedAt,
    String? archivedAt,
    String? createdBy,
  }) = _TenantResponse;
  factory TenantResponse.fromJson(Map<String, dynamic> json) =>
      _$TenantResponseFromJson(json);
}

/// Mirrors backend AdminDtos.TenantHealthResponse.
@freezed
class TenantHealthResponse with _$TenantHealthResponse {
  const factory TenantHealthResponse({
    required String tenantId,
    required String status,
    required bool hostBaseUrlSet,
    required bool authBindingResolves,
    required bool recordingStrategyResolves,
    required int dependencyCount,
    @Default(<String>[]) List<String> warnings,
  }) = _TenantHealthResponse;
  factory TenantHealthResponse.fromJson(Map<String, dynamic> json) =>
      _$TenantHealthResponseFromJson(json);
}
