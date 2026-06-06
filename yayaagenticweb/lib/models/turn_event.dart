import 'package:freezed_annotation/freezed_annotation.dart';

part 'turn_event.freezed.dart';

/// Mirrors the backend's sealed [TurnEvent]. Built by [SseClient.fromSse]
/// based on the SSE `event:` line — we don't rely on a discriminator inside
/// the payload because the backend signals the variant out-of-band.
@freezed
sealed class TurnEventDto with _$TurnEventDto {
  const factory TurnEventDto.token(String text) = TokenEvent;

  const factory TurnEventDto.toolCall({
    required String callId,
    required String tool,
    required Map<String, dynamic> args,
  }) = ToolCallEvent;

  const factory TurnEventDto.toolResult({
    required String callId,
    required String status,
    Object? value,
    String? error,
  }) = ToolResultEvent;

  const factory TurnEventDto.citation({
    required String chunkId,
    required String source,
    String? title,
    String? url,
  }) = CitationEvent;

  const factory TurnEventDto.uiHint({
    required String kind,
    required Map<String, dynamic> payload,
  }) = UiHintEvent;

  const factory TurnEventDto.end({
    String? turnId,
    int? tokensIn,
    int? tokensOut,
  }) = EndEvent;

  static TurnEventDto? fromSse(String event, dynamic data) {
    final map = data is Map<String, dynamic> ? data : <String, dynamic>{};
    switch (event) {
      case 'token':
        return TurnEventDto.token(map['text']?.toString() ?? '');
      case 'tool_call':
        return TurnEventDto.toolCall(
          callId: map['callId']?.toString() ?? '',
          tool: (map['tool'] is Map ? (map['tool']['value'] ?? '') : map['tool'] ?? '').toString(),
          args: (map['args'] as Map?)?.cast<String, dynamic>() ?? {},
        );
      case 'tool_result':
        return TurnEventDto.toolResult(
          callId: map['callId']?.toString() ?? '',
          status: map['status']?.toString() ?? 'OK',
          value: map['value'],
          error: map['error']?.toString(),
        );
      case 'citation':
        return TurnEventDto.citation(
          chunkId: map['chunkId']?.toString() ?? '',
          source: (map['source'] is Map ? (map['source']['value'] ?? '') : map['source'] ?? '').toString(),
          title: map['title']?.toString(),
          url: map['url']?.toString(),
        );
      case 'ui_hint':
        return TurnEventDto.uiHint(
          kind: map['kind']?.toString() ?? '',
          payload: (map['payload'] as Map?)?.cast<String, dynamic>() ?? {},
        );
      case 'end':
        final turnIdRaw = map['turnId'];
        final turnId = turnIdRaw is Map ? turnIdRaw['value']?.toString() : turnIdRaw?.toString();
        return TurnEventDto.end(
          turnId: turnId,
          tokensIn: (map['tokensIn'] as num?)?.toInt(),
          tokensOut: (map['tokensOut'] as num?)?.toInt(),
        );
      default:
        return null;
    }
  }
}
