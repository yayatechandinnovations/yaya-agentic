import 'package:freezed_annotation/freezed_annotation.dart';

part 'recording_strategy.freezed.dart';
part 'recording_strategy.g.dart';

@freezed
class RecordingStrategyRequest with _$RecordingStrategyRequest {
  const factory RecordingStrategyRequest({
    required String tenant,
    required String scopeKind,    // TENANT | PROFILE
    required String scopeId,
    required Map<String, dynamic> strategy,
  }) = _RecordingStrategyRequest;
  factory RecordingStrategyRequest.fromJson(Map<String, dynamic> json) =>
      _$RecordingStrategyRequestFromJson(json);
}

@freezed
class RecordingStrategyResponse with _$RecordingStrategyResponse {
  const factory RecordingStrategyResponse({
    required String tenant,
    required String scopeKind,
    required String scopeId,
    required Map<String, dynamic> strategy,
    required int version,
    String? createdAt,
  }) = _RecordingStrategyResponse;
  factory RecordingStrategyResponse.fromJson(Map<String, dynamic> json) =>
      _$RecordingStrategyResponseFromJson(json);
}
