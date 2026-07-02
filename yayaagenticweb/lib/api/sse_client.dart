import 'dart:async';
import 'dart:convert';

import 'package:dio/dio.dart';

import '../models/turn_event.dart';

/// Plain SSE client over Dio's streamed response. Parses the line-oriented
/// SSE wire format (`event:`, `data:`, blank-line dispatch) and emits typed
/// [TurnEventDto]s. Works on web, mobile, and desktop targets.
class SseClient {
  SseClient(this._dio);
  final Dio _dio;

  /// A chat turn: `POST /v1/sessions/{id}/messages`.
  Stream<TurnEventDto> stream({
    required String sessionId,
    required String userText,
  }) =>
      _post('/v1/sessions/$sessionId/messages', {'text': userText});

  /// A client-initiated action (two doors, one lock): `POST /v1/sessions/{id}/actions`.
  /// Runs one whitelisted tool (cart edit, checkout, load-more) through the same
  /// executor + Authorizer as a chat message, streaming back the same events.
  Stream<TurnEventDto> actions({
    required String sessionId,
    required String tool,
    Map<String, dynamic> args = const {},
  }) =>
      _post('/v1/sessions/$sessionId/actions', {'tool': tool, 'args': args});

  Stream<TurnEventDto> _post(String path, Map<String, dynamic> body) async* {
    final response = await _dio.request<ResponseBody>(
      path,
      data: body,
      options: Options(
        method: 'POST',
        responseType: ResponseType.stream,
        headers: {
          'Accept': 'text/event-stream',
          'Content-Type': 'application/json',
        },
      ),
    );

    String? currentEvent;
    final buffer = StringBuffer();

    final lineStream = response.data!.stream
        .map((bytes) => utf8.decode(bytes))
        .transform(const LineSplitter());

    await for (final line in lineStream) {
      if (line.isEmpty) {
        // Dispatch
        if (currentEvent != null && buffer.isNotEmpty) {
          final raw = buffer.toString();
          buffer.clear();
          final event = currentEvent;
          currentEvent = null;
          try {
            final parsed = TurnEventDto.fromSse(event, jsonDecode(raw));
            if (parsed != null) yield parsed;
          } catch (_) {
            // Malformed payload — surface as raw text token for visibility.
            yield TurnEventDto.token(raw);
          }
        } else {
          currentEvent = null;
          buffer.clear();
        }
        continue;
      }
      if (line.startsWith('event:')) {
        currentEvent = line.substring(6).trim();
      } else if (line.startsWith('data:')) {
        if (buffer.isNotEmpty) buffer.write('\n');
        buffer.write(line.substring(5).trimLeft());
      }
      // ignore `id:`, `retry:`, comments
    }
  }
}
