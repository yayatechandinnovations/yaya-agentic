import 'package:freezed_annotation/freezed_annotation.dart';

part 'tool.freezed.dart';
part 'tool.g.dart';

@freezed
class HttpBodyDto with _$HttpBodyDto {
  const factory HttpBodyDto({String? contentType, String? template}) =
      _HttpBodyDto;
  factory HttpBodyDto.fromJson(Map<String, dynamic> json) =>
      _$HttpBodyDtoFromJson(json);
}

@freezed
class HttpResponseDto with _$HttpResponseDto {
  const factory HttpResponseDto({
    String? jsonPath,
    @Default(<String, String>{}) Map<String, String> headerOutputs,
    // Carried through unedited so a republish from the console keeps them.
    Map<String, String>? mediaOutputs,
    Map<String, String>? tableOutputs,
    List<Map<String, String>>? cardFields,
  }) = _HttpResponseDto;
  factory HttpResponseDto.fromJson(Map<String, dynamic> json) =>
      _$HttpResponseDtoFromJson(json);
}

@freezed
class HttpHandlerDto with _$HttpHandlerDto {
  const factory HttpHandlerDto({
    required String method,
    required String urlTemplate,
    @Default(<String, String>{}) Map<String, String> headerTemplates,
    HttpBodyDto? body,
    HttpResponseDto? response,
    @Default('NONE') String authForwarding,
  }) = _HttpHandlerDto;
  factory HttpHandlerDto.fromJson(Map<String, dynamic> json) =>
      _$HttpHandlerDtoFromJson(json);
}

@freezed
class ToolHandlerDto with _$ToolHandlerDto {
  const factory ToolHandlerDto({
    required String kind,
    String? beanName,
    HttpHandlerDto? httpSpec,
  }) = _ToolHandlerDto;
  factory ToolHandlerDto.fromJson(Map<String, dynamic> json) =>
      _$ToolHandlerDtoFromJson(json);
}

@freezed
class ToolRequest with _$ToolRequest {
  const factory ToolRequest({
    required String tenant,
    required String id,
    required String inputSchemaJson,
    required String outputSchemaJson,
    @Default(<String, dynamic>{}) Map<String, dynamic> requires,
    required ToolHandlerDto handler,
    @Default(<String, dynamic>{}) Map<String, dynamic> policy,
    // Entity-memory hooks and suggestion rules: no editor here yet, but every
    // save replaces the whole tool, so they must round-trip.
    @Default([]) List<Map<String, dynamic>> captures,
    @Default([]) List<Map<String, dynamic>> bindings,
    @Default([]) List<Map<String, dynamic>> suggestions,
  }) = _ToolRequest;
  factory ToolRequest.fromJson(Map<String, dynamic> json) =>
      _$ToolRequestFromJson(json);
}

@freezed
class ToolResponse with _$ToolResponse {
  const factory ToolResponse({
    required String id,
    required int version,
    required String tenant,
    required String inputSchemaJson,
    required String outputSchemaJson,
    @Default(<String, dynamic>{}) Map<String, dynamic> requires,
    required ToolHandlerDto handler,
    @Default(<String, dynamic>{}) Map<String, dynamic> policy,
    @Default([]) List<Map<String, dynamic>> captures,
    @Default([]) List<Map<String, dynamic>> bindings,
    @Default([]) List<Map<String, dynamic>> suggestions,
    String? status,
    String? createdAt,
  }) = _ToolResponse;
  factory ToolResponse.fromJson(Map<String, dynamic> json) =>
      _$ToolResponseFromJson(json);
}
