import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';

import '../config.dart';
import '../models/directory_card.dart';
import '../models/entry_point_card.dart';

/// A parsed Server-Sent Event from the message stream.
class SseEvent {
  final String event; // token | tool_call | tool_result | citation | ui_hint | end
  final Map<String, dynamic> data;
  const SseEvent(this.event, this.data);
}

class StartResult {
  final String sessionId;
  final String greeting;
  final List<String> quickReplies;
  final EntryPointCard entryPoint;
  const StartResult({
    required this.sessionId,
    required this.greeting,
    required this.quickReplies,
    required this.entryPoint,
  });
}

class ChatApiException implements Exception {
  final int? status;
  final String code;
  final String message;
  ChatApiException(this.status, this.code, this.message);
  @override
  String toString() => 'ChatApiException($status, $code): $message';
}

/// Thin client over the public `/v1/chat/*` surface.
class ChatApi {
  ChatApi({Dio? dio, this.devicePass})
      : _dio = dio ??
            Dio(BaseOptions(
              baseUrl: AppConfig.apiBase,
              connectTimeout: const Duration(seconds: 15),
              receiveTimeout: const Duration(minutes: 5),
            ));

  final Dio _dio;

  /// The verified-device pass (§20), presented as `X-Device-Pass` so the chat
  /// front door identifies a phone-verified human. Null until the user verifies.
  final String? devicePass;

  Map<String, String> get _deviceHeader =>
      (devicePass == null || devicePass!.isEmpty) ? const {} : {'X-Device-Pass': devicePass!};

  /// Start phone verification — sends an OTP via the backend (Twilio Verify).
  Future<void> verifyStart(String phoneE164, String? name) async {
    try {
      await _dio.post('/v1/chat/verify/start', data: {'phoneE164': phoneE164, 'name': name});
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  /// Check the OTP; on success returns the device pass to store.
  Future<String> verifyCheck(String phoneE164, String code, String deviceId, String? name) async {
    try {
      final r = await _dio.post('/v1/chat/verify/check',
          data: {'phoneE164': phoneE164, 'code': code, 'deviceId': deviceId, 'name': name});
      final j = Map<String, dynamic>.from(r.data as Map);
      return (j['devicePass'] ?? '') as String;
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  /// Search/browse the public directory of published chats
  /// (chat-public-directory-design.md §4). [cursor] is the opaque `nextCursor`
  /// from a prior page; null starts at the first page.
  Future<DirectoryPage> directory({String? q, String? cursor, int limit = 20}) async {
    try {
      final r = await _dio.get('/v1/chat/directory', queryParameters: {
        if (q != null && q.trim().isNotEmpty) 'q': q.trim(),
        if (cursor != null && cursor.isNotEmpty) 'cursor': cursor,
        'limit': limit,
      });
      return DirectoryPage.fromJson(Map<String, dynamic>.from(r.data as Map));
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  Future<EntryPointCard> resolve(String handle) async {
    try {
      final r = await _dio.get('/v1/chat/e/$handle');
      return EntryPointCard.fromJson(Map<String, dynamic>.from(r.data as Map));
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  Future<StartResult> start(
    String handle, {
    String? deviceId,
    String? locale,
    String? bearer,
  }) async {
    try {
      final r = await _dio.post(
        '/v1/chat/e/$handle/sessions',
        data: {'deviceId': deviceId, 'locale': locale},
        options: Options(headers: {..._authHeader(bearer), ..._deviceHeader}),
      );
      final j = Map<String, dynamic>.from(r.data as Map);
      return StartResult(
        sessionId: j['sessionId'] as String,
        greeting: (j['greeting'] as String?) ?? '',
        quickReplies:
            (j['quickReplies'] as List?)?.map((e) => e.toString()).toList() ?? const [],
        entryPoint: EntryPointCard.fromJson(Map<String, dynamic>.from(j['entryPoint'] as Map)),
      );
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  /// Stream the assistant's turn. Each yielded [SseEvent] is one `event:/data:`
  /// frame from the SSE response.
  Stream<SseEvent> sendMessage(String sessionId, String text, {String? bearer}) async* {
    final response = await _dio.post<ResponseBody>(
      '/v1/chat/sessions/$sessionId/messages',
      data: {'text': text},
      options: Options(
        responseType: ResponseType.stream,
        headers: {'Accept': 'text/event-stream', ..._authHeader(bearer), ..._deviceHeader},
      ),
    );
    final body = response.data;
    if (body == null) return;

    final buffer = StringBuffer();
    await for (final Uint8List chunk in body.stream) {
      buffer.write(utf8.decode(chunk, allowMalformed: true));
      var content = buffer.toString();
      int sep;
      while ((sep = content.indexOf('\n\n')) >= 0) {
        final frame = content.substring(0, sep);
        content = content.substring(sep + 2);
        final evt = _parseFrame(frame);
        if (evt != null) yield evt;
      }
      buffer
        ..clear()
        ..write(content);
    }
  }

  /// Mid-session AUTH_OPTIONAL upgrade — verify the bearer server-side and
  /// re-bind the session to the verified identity (chat-channel-design.md §8.4).
  Future<String> authenticate(String sessionId, String bearer) async {
    try {
      final r = await _dio.post(
        '/v1/chat/sessions/$sessionId/authenticate',
        options: Options(headers: _authHeader(bearer)),
      );
      final j = Map<String, dynamic>.from(r.data as Map);
      return (j['subject'] ?? '') as String;
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  Future<void> end(String sessionId) async {
    try {
      await _dio.post('/v1/chat/sessions/$sessionId/end');
    } on DioException catch (_) {
      // best-effort
    }
  }

  SseEvent? _parseFrame(String frame) {
    String? event;
    final dataLines = <String>[];
    for (final line in const LineSplitter().convert(frame)) {
      if (line.startsWith('event:')) {
        event = line.substring(6).trim();
      } else if (line.startsWith('data:')) {
        dataLines.add(line.substring(5).trim());
      }
    }
    if (event == null || dataLines.isEmpty) return null;
    try {
      final decoded = jsonDecode(dataLines.join('\n'));
      return SseEvent(event, decoded is Map<String, dynamic> ? decoded : {'value': decoded});
    } catch (_) {
      return SseEvent(event, {'raw': dataLines.join('\n')});
    }
  }

  Map<String, String> _authHeader(String? bearer) =>
      (bearer == null || bearer.isEmpty) ? const {} : {'Authorization': 'Bearer $bearer'};

  ChatApiException _map(DioException e) {
    final status = e.response?.statusCode;
    String code = 'error';
    String message = e.message ?? 'request failed';
    final data = e.response?.data;
    if (data is Map) {
      code = (data['code'] ?? data['error'] ?? code).toString();
      message = (data['message'] ?? data['reason'] ?? message).toString();
    }
    return ChatApiException(status, code, message);
  }
}
