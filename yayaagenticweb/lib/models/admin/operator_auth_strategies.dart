import 'package:freezed_annotation/freezed_annotation.dart';

part 'operator_auth_strategies.freezed.dart';
part 'operator_auth_strategies.g.dart';

/// Top-level GET /v1/admin/auth/strategies response.
@freezed
class StrategiesResponse with _$StrategiesResponse {
  const factory StrategiesResponse({
    required BootstrapView bootstrap,
    required DelegateView delegate,
    String? updatedAt,
    String? updatedBy,
    @Default(false) bool canDisableBootstrap,
  }) = _StrategiesResponse;

  factory StrategiesResponse.fromJson(Map<String, dynamic> json) =>
      _$StrategiesResponseFromJson(json);
}

@freezed
class BootstrapView with _$BootstrapView {
  const factory BootstrapView({
    required bool enabled,
    required String username,
  }) = _BootstrapView;

  factory BootstrapView.fromJson(Map<String, dynamic> json) =>
      _$BootstrapViewFromJson(json);
}

@freezed
class DelegateView with _$DelegateView {
  const factory DelegateView({
    required bool enabled,
    String? url,
    @Default('') String sharedSecretMask,
    @Default(false) bool sharedSecretPresent,
    @Default(5000) int timeoutMs,
    @Default(true) bool requireHttps,
    required RequestShape request,
    required SuccessCriteria success,
    required IdentityMapping identity,
    required FailureMapping failure,
  }) = _DelegateView;

  factory DelegateView.fromJson(Map<String, dynamic> json) =>
      _$DelegateViewFromJson(json);
}

@freezed
class RequestShape with _$RequestShape {
  const factory RequestShape({
    @Default('POST') String method,
    @Default(<String, String>{}) Map<String, String> headers,
    required RequestBody body,
  }) = _RequestShape;

  factory RequestShape.fromJson(Map<String, dynamic> json) =>
      _$RequestShapeFromJson(json);
}

@freezed
class RequestBody with _$RequestBody {
  const factory RequestBody({
    /// Matches Java BodyFormat enum: JSON | FORM | BASIC_AUTH | NONE.
    @Default('JSON') String format,
    String? template,
  }) = _RequestBody;

  factory RequestBody.fromJson(Map<String, dynamic> json) =>
      _$RequestBodyFromJson(json);
}

@freezed
class SuccessCriteria with _$SuccessCriteria {
  const factory SuccessCriteria({
    @Default(<int>[200, 204]) List<int> statusIn,
    String? jsonPathExists,
    @Default(<JsonPathEquals>[]) List<JsonPathEquals> jsonPathEquals,
  }) = _SuccessCriteria;

  factory SuccessCriteria.fromJson(Map<String, dynamic> json) =>
      _$SuccessCriteriaFromJson(json);
}

@freezed
class JsonPathEquals with _$JsonPathEquals {
  const factory JsonPathEquals({
    required String path,
    /// dynamic — operator can configure null/string/number/boolean.
    dynamic value,
  }) = _JsonPathEquals;

  factory JsonPathEquals.fromJson(Map<String, dynamic> json) =>
      _$JsonPathEqualsFromJson(json);
}

@freezed
class IdentityMapping with _$IdentityMapping {
  const factory IdentityMapping({
    String? subjectPath,
    String? displayNamePath,
    String? attributesPath,
  }) = _IdentityMapping;

  factory IdentityMapping.fromJson(Map<String, dynamic> json) =>
      _$IdentityMappingFromJson(json);
}

@freezed
class FailureMapping with _$FailureMapping {
  const factory FailureMapping({
    String? reasonPath,
  }) = _FailureMapping;

  factory FailureMapping.fromJson(Map<String, dynamic> json) =>
      _$FailureMappingFromJson(json);
}

// -----------------------------------------------------------------------
// Test (probe) result — POST /v1/admin/auth/strategies/http-delegate/test
// -----------------------------------------------------------------------

@freezed
class ProbeResult with _$ProbeResult {
  const factory ProbeResult({
    required SentRequest request,
    required ReceivedResponse response,
    required Evaluation evaluation,
    @Default(false) bool allowed,
    String? auditReason,
  }) = _ProbeResult;

  factory ProbeResult.fromJson(Map<String, dynamic> json) =>
      _$ProbeResultFromJson(json);
}

@freezed
class SentRequest with _$SentRequest {
  const factory SentRequest({
    required String method,
    required String url,
    @Default(<String, String>{}) Map<String, String> headers,
    String? body,
  }) = _SentRequest;

  factory SentRequest.fromJson(Map<String, dynamic> json) =>
      _$SentRequestFromJson(json);
}

@freezed
class ReceivedResponse with _$ReceivedResponse {
  const factory ReceivedResponse({
    int? status,
    @Default(0) int durationMs,
    @Default(<String, String>{}) Map<String, String> headers,
    String? body,
    String? error,
  }) = _ReceivedResponse;

  factory ReceivedResponse.fromJson(Map<String, dynamic> json) =>
      _$ReceivedResponseFromJson(json);
}

@freezed
class Evaluation with _$Evaluation {
  const factory Evaluation({
    @Default(<CriterionCheck>[]) List<CriterionCheck> successChecks,
    ExtractedIdentity? identity,
    @Default('DENY') String decision,
    String? auditReason,
  }) = _Evaluation;

  factory Evaluation.fromJson(Map<String, dynamic> json) =>
      _$EvaluationFromJson(json);
}

@freezed
class CriterionCheck with _$CriterionCheck {
  const factory CriterionCheck({
    required String criterion,
    required bool matched,
    String? detail,
  }) = _CriterionCheck;

  factory CriterionCheck.fromJson(Map<String, dynamic> json) =>
      _$CriterionCheckFromJson(json);
}

@freezed
class ExtractedIdentity with _$ExtractedIdentity {
  const factory ExtractedIdentity({
    required String subject,
    required String displayName,
    @Default(<String, dynamic>{}) Map<String, dynamic> attributes,
  }) = _ExtractedIdentity;

  factory ExtractedIdentity.fromJson(Map<String, dynamic> json) =>
      _$ExtractedIdentityFromJson(json);
}
