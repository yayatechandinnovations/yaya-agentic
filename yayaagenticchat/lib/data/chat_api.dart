import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';

import '../config.dart';
import '../models/chat_message.dart';
import '../models/commerce/commerce_config.dart';
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

  /// Non-null when the resolved profile is a store — the client renders cart
  /// chrome from session start without a new transport (ecommerce-chat §3).
  final CommerceConfig? commerce;

  const StartResult({
    required this.sessionId,
    required this.greeting,
    required this.quickReplies,
    required this.entryPoint,
    this.commerce,
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

  /// Phone-first probe: is there already an account for this number? Drives
  /// whether we ask for a name before sending the OTP. Sends no SMS.
  Future<bool> verifyLookup(String phoneE164) async {
    try {
      final r = await _dio.post('/v1/chat/verify/lookup', data: {'phoneE164': phoneE164});
      final j = Map<String, dynamic>.from(r.data as Map);
      return (j['registered'] ?? false) as bool;
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  /// Start phone verification — sends an OTP via the backend (Twilio Verify).
  Future<void> verifyStart(String phoneE164, String? name) async {
    try {
      await _dio.post('/v1/chat/verify/start', data: {'phoneE164': phoneE164, 'name': name});
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  /// Check the OTP; on success returns the device pass to store plus the account's
  /// name (for a returning phone this is the stored name, even if none was typed).
  Future<({String devicePass, String? name})> verifyCheck(
      String phoneE164, String code, String deviceId, String? name) async {
    try {
      final r = await _dio.post('/v1/chat/verify/check',
          data: {'phoneE164': phoneE164, 'code': code, 'deviceId': deviceId, 'name': name});
      final j = Map<String, dynamic>.from(r.data as Map);
      return (devicePass: (j['devicePass'] ?? '') as String, name: j['name'] as String?);
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
      final commerce = j['commerce'];
      return StartResult(
        sessionId: j['sessionId'] as String,
        greeting: (j['greeting'] as String?) ?? '',
        quickReplies:
            (j['quickReplies'] as List?)?.map((e) => e.toString()).toList() ?? const [],
        entryPoint: EntryPointCard.fromJson(Map<String, dynamic>.from(j['entryPoint'] as Map)),
        commerce: commerce is Map
            ? CommerceConfig.fromJson(Map<String, dynamic>.from(commerce))
            : null,
      );
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  /// Stream the assistant's turn. Each yielded [SseEvent] is one `event:/data:`
  /// frame from the SSE response.
  Stream<SseEvent> sendMessage(String sessionId, String text, {String? bearer}) =>
      _streamSse('/v1/chat/sessions/$sessionId/messages', {'text': text}, bearer: bearer);

  /// The client-initiated action channel (ecommerce-chat-design.md §9): invoke
  /// one whitelisted, `clientInvocable` commerce tool (a tap on Add / Remove /
  /// Checkout) through the same executor + Authorizer as a message, streaming
  /// back the same SSE shape. A refused tool returns 403 → [ChatApiException].
  Stream<SseEvent> sendAction(String sessionId, String tool, Map<String, Object?> args,
          {String? bearer}) =>
      _streamSse('/v1/chat/sessions/$sessionId/actions',
          {'tool': tool, 'args': args}, bearer: bearer);

  /// Shared SSE reader for both the message and action channels. Splits the
  /// byte stream on blank-line frame boundaries and parses each `event:/data:`
  /// frame. A non-2xx (e.g. 403 from the action guard) throws before the first
  /// frame and is surfaced as a [ChatApiException].
  Stream<SseEvent> _streamSse(String path, Object body, {String? bearer}) async* {
    final ResponseBody? stream;
    try {
      final response = await _dio.post<ResponseBody>(
        path,
        data: body,
        options: Options(
          responseType: ResponseType.stream,
          headers: {'Accept': 'text/event-stream', ..._authHeader(bearer), ..._deviceHeader},
        ),
      );
      stream = response.data;
    } on DioException catch (e) {
      throw _map(e);
    }
    if (stream == null) return;

    final buffer = StringBuffer();
    await for (final Uint8List chunk in stream.stream) {
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

  /// Drain pending system messages delivered while offline (system-messaging §11).
  /// Identity-scoped: the server derives the recipient from the verified bearer,
  /// never from the client. `peek` leaves them undelivered.
  Future<List<InboxMessage>> inbox(String handle, {String? bearer, bool peek = false}) async {
    try {
      final r = await _dio.get(
        '/v1/chat/e/$handle/inbox',
        queryParameters: {'peek': peek},
        options: Options(headers: {..._authHeader(bearer), ..._deviceHeader}),
      );
      final j = Map<String, dynamic>.from(r.data as Map);
      return (j['items'] as List? ?? const [])
          .map((e) => InboxMessage.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();
    } on DioException catch (_) {
      // No inbox / not authenticated → nothing to drain, never fatal to the chat.
      return const [];
    }
  }

  /// Reply into a system-participant thread (system-messaging §8). Same SSE shape
  /// as a normal message, but targets the durable outreach session by id.
  Stream<SseEvent> replyToSystem(String handle, String sessionId, String text, {String? bearer}) =>
      _streamSse('/v1/chat/e/$handle/outreach/$sessionId/messages', {'text': text}, bearer: bearer);

  /// Register a device for content-free wake nudges (system-messaging §11 #3).
  Future<void> registerPushToken(String handle, String token,
      {String platform = 'WEBPUSH', String? bearer}) async {
    try {
      await _dio.post(
        '/v1/chat/e/$handle/push-tokens',
        data: {'platform': platform, 'token': token},
        options: Options(headers: {..._authHeader(bearer), ..._deviceHeader}),
      );
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
