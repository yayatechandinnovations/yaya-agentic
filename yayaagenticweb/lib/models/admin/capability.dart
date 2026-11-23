import 'package:freezed_annotation/freezed_annotation.dart';

part 'capability.freezed.dart';
part 'capability.g.dart';

@freezed
class CapabilityRequest with _$CapabilityRequest {
  const factory CapabilityRequest({
    required String tenant,
    required String id,
    required String label,
    String? description,
    String? llmGuidance,
    @Default(<String>[]) List<String> tools,
    @Default(<String>[]) List<String> followUpHints,
    // No editor yet; carried so a republish keeps the quick-reply menu.
    Map<String, dynamic>? menu,
  }) = _CapabilityRequest;

  factory CapabilityRequest.fromJson(Map<String, dynamic> json) =>
      _$CapabilityRequestFromJson(json);
}

@freezed
class CapabilityResponse with _$CapabilityResponse {
  const factory CapabilityResponse({
    required String id,
    required int version,
    required String tenant,
    required String label,
    String? description,
    String? llmGuidance,
    @Default(<String>[]) List<String> tools,
    @Default(<String>[]) List<String> followUpHints,
    Map<String, dynamic>? menu,
    String? createdAt,
  }) = _CapabilityResponse;

  factory CapabilityResponse.fromJson(Map<String, dynamic> json) =>
      _$CapabilityResponseFromJson(json);
}
