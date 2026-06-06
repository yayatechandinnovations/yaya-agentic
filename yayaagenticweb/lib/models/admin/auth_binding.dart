import 'package:freezed_annotation/freezed_annotation.dart';

part 'auth_binding.freezed.dart';
part 'auth_binding.g.dart';

@freezed
class AuthBindingRequest with _$AuthBindingRequest {
  const factory AuthBindingRequest({
    required String tenant,
    required String id,
    required String authenticatorRef,
    @Default(<String>[]) List<String> authorizerChain,
  }) = _AuthBindingRequest;
  factory AuthBindingRequest.fromJson(Map<String, dynamic> json) =>
      _$AuthBindingRequestFromJson(json);
}

@freezed
class AuthBindingResponse with _$AuthBindingResponse {
  const factory AuthBindingResponse({
    required String id,
    required String tenant,
    required String authenticatorRef,
    @Default(<String>[]) List<String> authorizerChain,
    String? createdAt,
  }) = _AuthBindingResponse;
  factory AuthBindingResponse.fromJson(Map<String, dynamic> json) =>
      _$AuthBindingResponseFromJson(json);
}

@freezed
class AuthAvailability with _$AuthAvailability {
  const factory AuthAvailability({
    @Default(<String>[]) List<String> authenticators,
    @Default(<String>[]) List<String> authorizers,
  }) = _AuthAvailability;
  factory AuthAvailability.fromJson(Map<String, dynamic> json) =>
      _$AuthAvailabilityFromJson(json);
}
