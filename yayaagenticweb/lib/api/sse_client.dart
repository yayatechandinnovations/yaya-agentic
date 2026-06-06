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

  Stream<TurnEventDto> stream({
    required String sessionId,
    required String userText,
  }) async* {
    final response = await _dio.request<ResponseBody>(
      '/v1/sessions/$sessionId/messages',
      data: {'text': userText},
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
