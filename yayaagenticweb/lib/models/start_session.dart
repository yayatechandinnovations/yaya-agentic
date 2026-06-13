import 'package:freezed_annotation/freezed_annotation.dart';

import 'act_as.dart';

part 'start_session.freezed.dart';
part 'start_session.g.dart';

@freezed
class StartSessionRequest with _$StartSessionRequest {
  const factory StartSessionRequest({
    @Default('default') String tenant,
    @Default('hello-world') String profileId,
    @Default(1) int profileVersion,
    @Default('web') String channel,
    @Default(<String, dynamic>{}) Map<String, dynamic> hints,
    ActAs? actAs,
  }) = _StartSessionRequest;

  factory StartSessionRequest.fromJson(Map<String, dynamic> json) =>
      _$StartSessionRequestFromJson(json);
}

@freezed
class StartSessionResponse with _$StartSessionResponse {
  const factory StartSessionResponse({
    required String sessionId,
    required String profileId,
    required int profileVersion,
    required String greeting,
    required List<String> quickReplies,
  }) = _StartSessionResponse;

  factory StartSessionResponse.fromJson(Map<String, dynamic> json) =>
      _$StartSessionResponseFromJson(json);
}
