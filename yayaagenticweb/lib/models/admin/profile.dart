import 'package:freezed_annotation/freezed_annotation.dart';

part 'profile.freezed.dart';
part 'profile.g.dart';

@freezed
class ProfileRequest with _$ProfileRequest {
  const factory ProfileRequest({
    required String tenant,
    required String id,
    required String displayName,
    required String introOneLiner,
    required String systemPromptFragment,
    @Default(<String>[]) List<String> capabilities,
    String? authBindingId,
    @Default('en') String language,
    @Default(<String, dynamic>{}) Map<String, dynamic> metadata,
    // App-specific traits keyed by namespaced kind -> opaque payload. The store
    // binding rides under the yaya-sales-owned `sales.store` kind; the framework
    // never interprets it. Catalogs/pricing/PSP live in yaya-sales.
    Map<String, dynamic>? extensions,
    // Default Session Start Flow id (null = none).
    String? defaultStartFlowId,
  }) = _ProfileRequest;

  factory ProfileRequest.fromJson(Map<String, dynamic> json) =>
      _$ProfileRequestFromJson(json);
}

@freezed
class ProfileResponse with _$ProfileResponse {
  const factory ProfileResponse({
    required String id,
    required int version,
    required String tenant,
    required String displayName,
    required String introOneLiner,
    required String systemPromptFragment,
    @Default(<String>[]) List<String> capabilities,
    String? authBindingId,
    @Default('en') String language,
    @Default(<String, dynamic>{}) Map<String, dynamic> metadata,
    String? status,
    String? createdAt,
    Map<String, dynamic>? extensions,
    String? defaultStartFlowId,
  }) = _ProfileResponse;

  factory ProfileResponse.fromJson(Map<String, dynamic> json) =>
      _$ProfileResponseFromJson(json);
}
