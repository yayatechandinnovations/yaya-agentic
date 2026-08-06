import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';

import '../config.dart';
import '../models/chat_message.dart';
import '../models/commerce/commerce_config.dart';
import '../models/forum.dart';
import '../models/receipts.dart';
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

  /// Non-null when the profile carries receipt templates
  /// (chat-experience-design.md §7.3, trait `chat.receipts`).
  final ReceiptsConfig? receipts;

  const StartResult({
    required this.sessionId,
    required this.greeting,
    required this.quickReplies,
    required this.entryPoint,
    this.commerce,
    this.receipts,
  });
}

/// The signed-in end-user account (chat-account-self-service-design.md §6).
class Account {
  final String phoneE164;
  final String name;
  final String? createdAt;
  final bool pinSet;
  const Account(
      {required this.phoneE164, required this.name, this.createdAt, this.pinSet = false});

  factory Account.fromJson(Map<String, dynamic> j) => Account(
        phoneE164: (j['phoneE164'] ?? '') as String,
        name: (j['name'] ?? '') as String,
        createdAt: j['createdAt'] as String?,
        pinSet: (j['pinSet'] ?? false) as bool,
      );
}

/// One activated device of the account (chat-experience-design.md §5.5).
class AccountDevice {
  final String deviceId;
  final String? label;
  final bool pinRequired;
  final bool current;
  final String? lastSeenAt;

  const AccountDevice({
    required this.deviceId,
    this.label,
    required this.pinRequired,
    required this.current,
    this.lastSeenAt,
  });

  factory AccountDevice.fromJson(Map<String, dynamic> j) => AccountDevice(
        deviceId: (j['deviceId'] ?? '') as String,
        label: j['label'] as String?,
        pinRequired: (j['pinRequired'] ?? true) as bool,
        current: (j['current'] ?? false) as bool,
        lastSeenAt: j['lastSeenAt'] as String?,
      );
}

/// The resume candidate for a chat (§7.2). `status` classifies the most-recent
/// session for this account+entry point: LIVE (offer Continue), ENDED (show a
/// read-only transcript, then start fresh), or NONE (first-time greeting).
class LatestSession {
  final String status; // LIVE | ENDED | NONE
  final String? sessionId;
  final String? lastActivityAt;
  final int turnCount;
  final String? preview;

  const LatestSession({
    required this.status,
    this.sessionId,
    this.lastActivityAt,
    this.turnCount = 0,
    this.preview,
  });

  bool get isLive => status == 'LIVE';
  bool get isEnded => status == 'ENDED';

  factory LatestSession.fromJson(Map<String, dynamic> j) => LatestSession(
        status: (j['status'] ?? 'NONE') as String,
        sessionId: j['sessionId'] as String?,
        lastActivityAt: j['lastActivityAt'] as String?,
        turnCount: (j['turnCount'] as num?)?.toInt() ?? 0,
        preview: j['preview'] as String?,
      );
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
  /// name (for a returning phone this is the stored name, even if none was typed),
  /// the once-only device secret for PIN unlock, and whether a PIN already
  /// guards the account (chat-experience-design.md §5.4).
  Future<({String devicePass, String? name, String? deviceSecret, bool pinSet})> verifyCheck(
      String phoneE164, String code, String deviceId, String? name) async {
    try {
      final r = await _dio.post('/v1/chat/verify/check',
          data: {'phoneE164': phoneE164, 'code': code, 'deviceId': deviceId, 'name': name});
      final j = Map<String, dynamic>.from(r.data as Map);
      return (
        devicePass: (j['devicePass'] ?? '') as String,
        name: j['name'] as String?,
        deviceSecret: j['deviceSecret'] as String?,
        pinSet: (j['pinSet'] ?? false) as bool,
      );
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  /// The everyday login (chat-experience-design.md §5.4): device secret + PIN
  /// (omitted when this device's PIN gate is off) → a fresh session pass.
  /// Every failure mode is a uniform 401; `otp_required` is the one exception,
  /// meaning the lockout ladder is exhausted and only SMS re-verify recovers.
  Future<({String devicePass, String? phone, String? name, bool pinRequired})> pinUnlock(
      String deviceId, String deviceSecret, String? pin) async {
    try {
      final r = await _dio.post('/v1/chat/verify/pin-unlock',
          data: {'deviceId': deviceId, 'deviceSecret': deviceSecret, 'pin': pin});
      final j = Map<String, dynamic>.from(r.data as Map);
      return (
        devicePass: (j['devicePass'] ?? '') as String,
        phone: j['phoneE164'] as String?,
        name: j['name'] as String?,
        pinRequired: (j['pinRequired'] ?? false) as bool,
      );
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  /// Set or change the account PIN. `currentPin` is required only for a change.
  /// Returns the re-minted (short-TTL) pass to adopt.
  Future<String> setPin({String? currentPin, required String pin}) async {
    try {
      final r = await _dio.post('/v1/chat/account/pin',
          data: {'currentPin': currentPin, 'pin': pin},
          options: Options(headers: _deviceHeader));
      return (Map<String, dynamic>.from(r.data as Map)['devicePass'] ?? '') as String;
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  /// Remove the PIN (requires knowing it). Returns the re-minted long-TTL pass.
  Future<String> removePin(String currentPin) async {
    try {
      final r = await _dio.delete('/v1/chat/account/pin',
          data: {'currentPin': currentPin}, options: Options(headers: _deviceHeader));
      return (Map<String, dynamic>.from(r.data as Map)['devicePass'] ?? '') as String;
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  Future<List<AccountDevice>> listDevices() async {
    try {
      final r = await _dio.get('/v1/chat/account/devices',
          options: Options(headers: _deviceHeader));
      final j = Map<String, dynamic>.from(r.data as Map);
      return [
        for (final d in (j['devices'] as List? ?? const []))
          AccountDevice.fromJson(Map<String, dynamic>.from(d as Map)),
      ];
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  /// Lazy migration for installs predating device enrollment: trade the valid
  /// pass for this device's secret — no SMS spent.
  Future<({String deviceSecret, bool pinRequired, bool pinSet})> enrollDevice() async {
    try {
      final r = await _dio.post('/v1/chat/account/devices/enroll',
          options: Options(headers: _deviceHeader));
      final j = Map<String, dynamic>.from(r.data as Map);
      return (
        deviceSecret: (j['deviceSecret'] ?? '') as String,
        pinRequired: (j['pinRequired'] ?? true) as bool,
        pinSet: (j['pinSet'] ?? false) as bool,
      );
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  /// Toggle the per-device PIN gate. Turning it OFF is a step-up: the current
  /// PIN must ride along when the account has one.
  Future<void> updateDevice(String deviceId,
      {required bool pinRequired, String? currentPin}) async {
    try {
      await _dio.put('/v1/chat/account/devices/$deviceId',
          data: {'pinRequired': pinRequired, 'currentPin': currentPin},
          options: Options(headers: _deviceHeader));
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  /// Single-device sign-out; revoking the current device is plain "log out".
  Future<void> revokeDevice(String deviceId) async {
    try {
      await _dio.post('/v1/chat/account/devices/$deviceId/revoke',
          options: Options(headers: _deviceHeader));
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
      // The store trait now arrives inside the generic `traits` map keyed by the
      // yaya-sales-owned `sales.store` kind (framework is domain-neutral, S7).
      // Fall back to the pre-S7 top-level `commerce` field for older servers.
      final traits = j['traits'];
      final store = (traits is Map ? traits['sales.store'] : null) ?? j['commerce'];
      final receipts = traits is Map ? traits['chat.receipts'] : null;
      return StartResult(
        sessionId: j['sessionId'] as String,
        greeting: (j['greeting'] as String?) ?? '',
        quickReplies:
            (j['quickReplies'] as List?)?.map((e) => e.toString()).toList() ?? const [],
        entryPoint: EntryPointCard.fromJson(Map<String, dynamic>.from(j['entryPoint'] as Map)),
        commerce: store is Map
            ? CommerceConfig.fromJson(Map<String, dynamic>.from(store))
            : null,
        receipts: receipts is Map
            ? ReceiptsConfig.fromJson(Map<String, dynamic>.from(receipts))
            : null,
      );
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  /// The account's receipt wallet (chat-experience-design.md §7.5): every
  /// ticket/receipt issued to this verified account, template snapshotted at
  /// issuance. Keyset-paged, newest first.
  Future<({List<ReceiptInstance> receipts, String? nextCursor})> accountReceipts(
      {String? cursor}) async {
    try {
      final r = await _dio.get('/v1/chat/account/receipts',
          queryParameters: {'cursor': ?cursor},
          options: Options(headers: _deviceHeader));
      final j = Map<String, dynamic>.from(r.data as Map);
      return (
        receipts: [
          for (final e in (j['receipts'] as List? ?? const []))
            ReceiptInstance.fromWallet(Map<String, dynamic>.from(e as Map)),
        ],
        nextCursor: j['nextCursor'] as String?,
      );
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  /// Stream the assistant's turn. Each yielded [SseEvent] is one `event:/data:`
  /// frame from the SSE response. [attachmentIds] reference prior uploads to
  /// this session (chat-experience-design.md §6.1 — upload first, then send).
  Stream<SseEvent> sendMessage(String sessionId, String text,
          {String? bearer, List<String> attachmentIds = const []}) =>
      _streamSse(
          '/v1/chat/sessions/$sessionId/messages',
          {
            'text': text,
            if (attachmentIds.isNotEmpty) 'attachmentIds': attachmentIds,
          },
          bearer: bearer);

  /// Upload one attachment into a session (chat-experience-design.md §6.1): the
  /// bytes run the server gauntlet (type sniff → malware scan → store) and the
  /// returned attachment carries a short-lived signed URL — plus, for voice
  /// notes, the transcript when transcription succeeded.
  Future<ChatAttachment> uploadAttachment(
      String sessionId, Uint8List bytes, String filename) async {
    try {
      final form = FormData.fromMap({
        'file': MultipartFile.fromBytes(bytes, filename: filename),
      });
      final r = await _dio.post('/v1/chat/sessions/$sessionId/attachments',
          data: form, options: Options(headers: _deviceHeader));
      return ChatAttachment.fromJson(Map<String, dynamic>.from(r.data as Map));
    } on DioException catch (e) {
      throw _map(e);
    }
  }

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

  /* --------------------- account self-service (§6, §7) --------------------- */

  /// View the signed-in account. Device-pass scoped (§6).
  Future<Account> getAccount() async {
    try {
      final r = await _dio.get('/v1/chat/account', options: Options(headers: _deviceHeader));
      return Account.fromJson(Map<String, dynamic>.from(r.data as Map));
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  /// Rename (§6.1). The server re-mints the device pass so the new name reaches
  /// the profile on the next session; the caller MUST store the returned pass.
  Future<({String devicePass, String? name})> renameAccount(String name) async {
    try {
      final r = await _dio.patch('/v1/chat/account',
          data: {'name': name}, options: Options(headers: _deviceHeader));
      final j = Map<String, dynamic>.from(r.data as Map);
      return (devicePass: (j['devicePass'] ?? '') as String, name: j['name'] as String?);
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  /// Phone-change step 1 (§6.2): OTP → the CURRENT number. 409 phone_unavailable
  /// if the new number is already an active account.
  Future<({String changeId, String state})> phoneChangeStart(String newPhoneE164) async {
    try {
      final r = await _dio.post('/v1/chat/account/phone-change/start',
          data: {'newPhoneE164': newPhoneE164}, options: Options(headers: _deviceHeader));
      final j = Map<String, dynamic>.from(r.data as Map);
      return (changeId: (j['changeId'] ?? '') as String, state: (j['state'] ?? '') as String);
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  /// Phone-change step 2 (§6.2): verify the OLD-number code; on success the
  /// server sends an OTP to the NEW number. Returns the new state (NEW_PENDING).
  Future<String> phoneChangeVerifyOld(String changeId, String code) async {
    try {
      final r = await _dio.post('/v1/chat/account/phone-change/verify-old',
          data: {'changeId': changeId, 'code': code}, options: Options(headers: _deviceHeader));
      final j = Map<String, dynamic>.from(r.data as Map);
      return (j['state'] ?? '') as String;
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  /// Phone-change step 3 (§6.2): verify the NEW-number code and migrate. Returns
  /// the fresh device pass + new phone the caller MUST store.
  Future<({String devicePass, String phoneE164})> phoneChangeVerifyNew(
      String changeId, String code) async {
    try {
      final r = await _dio.post('/v1/chat/account/phone-change/verify-new',
          data: {'changeId': changeId, 'code': code}, options: Options(headers: _deviceHeader));
      final j = Map<String, dynamic>.from(r.data as Map);
      return (
        devicePass: (j['devicePass'] ?? '') as String,
        phoneE164: (j['phoneE164'] ?? '') as String,
      );
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  Future<void> phoneChangeResend(String changeId) async {
    try {
      await _dio.post('/v1/chat/account/phone-change/resend',
          data: {'changeId': changeId}, options: Options(headers: _deviceHeader));
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  /// Cancel an in-flight ceremony. Best-effort — a stale change expires anyway.
  Future<void> phoneChangeAbort(String changeId) async {
    try {
      await _dio.post('/v1/chat/account/phone-change/abort',
          data: {'changeId': changeId}, options: Options(headers: _deviceHeader));
    } on DioException catch (_) {
      // best-effort
    }
  }

  /// Delete step 1 (§6.3): OTP → the account number to confirm intent.
  Future<String> deleteStart() async {
    try {
      final r = await _dio.post('/v1/chat/account/delete/start',
          options: Options(headers: _deviceHeader));
      final j = Map<String, dynamic>.from(r.data as Map);
      return (j['state'] ?? '') as String;
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  /// Delete step 2 (§6.3): verify the code; the account is scrubbed and every
  /// device pass revoked. This pass no longer authenticates after the 204.
  Future<void> deleteConfirm(String code) async {
    try {
      await _dio.post('/v1/chat/account/delete/confirm',
          data: {'code': code}, options: Options(headers: _deviceHeader));
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  /// Revoke ALL device passes (including this one) without deleting the account.
  Future<void> signOutEverywhere() async {
    try {
      await _dio.post('/v1/chat/account/sign-out-everywhere',
          options: Options(headers: _deviceHeader));
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  /// The account's server-side directory of added chats (§7.1), re-resolved to
  /// current branding — the cross-device recovery source.
  Future<List<EntryPointCard>> accountTiles() async {
    try {
      final r = await _dio.get('/v1/chat/account/tiles', options: Options(headers: _deviceHeader));
      final j = Map<String, dynamic>.from(r.data as Map);
      return (j['tiles'] as List? ?? const [])
          .map((e) => EntryPointCard.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  /// Mirror an added chat server-side (§7.1), keyed by the public handle.
  /// Best-effort — the on-device store is the fast path; a sync failure never
  /// blocks or fails the UI.
  Future<void> putAccountTile(String handle) async {
    try {
      await _dio.put('/v1/chat/account/tiles/$handle', options: Options(headers: _deviceHeader));
    } on DioException catch (_) {
      // fire-and-forget
    }
  }

  Future<void> deleteAccountTile(String handle) async {
    try {
      await _dio.delete('/v1/chat/account/tiles/$handle', options: Options(headers: _deviceHeader));
    } on DioException catch (_) {
      // fire-and-forget
    }
  }

  /// The resume candidate for this chat (§7.2).
  Future<LatestSession> latestSession(String handle) async {
    try {
      final r = await _dio.get('/v1/chat/e/$handle/sessions/latest',
          options: Options(headers: _deviceHeader));
      return LatestSession.fromJson(Map<String, dynamic>.from(r.data as Map));
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  /// Hydrate a prior session's turns for resume (§7.2). Ownership is enforced
  /// server-side against the account; a mismatch is a 404 (no existence leak).
  Future<List<ChatMessage>> sessionHistory(String sessionId) async {
    try {
      final r = await _dio.get('/v1/chat/sessions/$sessionId',
          queryParameters: {'history': true}, options: Options(headers: _deviceHeader));
      final j = Map<String, dynamic>.from(r.data as Map);
      final turns = j['turns'] as List? ?? const [];
      return turns.map((e) {
        final m = Map<String, dynamic>.from(e as Map);
        return ChatMessage(
          role: (m['role'] ?? 'assistant') as String,
          text: (m['text'] ?? '') as String,
          attachments: [
            for (final a in (m['attachments'] as List? ?? const []))
              ChatAttachment.fromJson(Map<String, dynamic>.from(a as Map)),
          ],
        );
      }).toList();
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  /* ---- Community forum (chat-experience-design.md §8) ---- */

  /// The forum feed for a handle. `enabled=false` means the tenant hasn't
  /// switched the forum on — render the zero state, not an error.
  Future<({bool enabled, List<ForumPost> posts, String? nextCursor})> forumFeed(
      String handle, {String? cursor}) async {
    try {
      final r = await _dio.get('/v1/chat/e/$handle/forum/posts',
          queryParameters: {'cursor': ?cursor},
          options: Options(headers: _deviceHeader));
      final j = Map<String, dynamic>.from(r.data as Map);
      return (
        enabled: (j['enabled'] ?? false) as bool,
        posts: [
          for (final p in (j['posts'] as List? ?? const []))
            ForumPost.fromJson(Map<String, dynamic>.from(p as Map)),
        ],
        nextCursor: j['nextCursor'] as String?,
      );
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  Future<List<ForumPost>> forumReplies(String handle, String postId) async {
    try {
      final r = await _dio.get('/v1/chat/e/$handle/forum/posts/$postId/replies',
          options: Options(headers: _deviceHeader));
      final j = Map<String, dynamic>.from(r.data as Map);
      return [
        for (final p in (j['replies'] as List? ?? const []))
          ForumPost.fromJson(Map<String, dynamic>.from(p as Map)),
      ];
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  Future<ForumPost> forumCreate(String handle, String body, {String? replyTo}) async {
    try {
      final r = await _dio.post('/v1/chat/e/$handle/forum/posts',
          data: {'body': body, 'replyTo': replyTo},
          options: Options(headers: _deviceHeader));
      return ForumPost.fromJson(Map<String, dynamic>.from(r.data as Map));
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  Future<ForumPost> forumEdit(String handle, String postId, String body) async {
    try {
      final r = await _dio.patch('/v1/chat/e/$handle/forum/posts/$postId',
          data: {'body': body}, options: Options(headers: _deviceHeader));
      return ForumPost.fromJson(Map<String, dynamic>.from(r.data as Map));
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  Future<void> forumDelete(String handle, String postId) async {
    try {
      await _dio.delete('/v1/chat/e/$handle/forum/posts/$postId',
          options: Options(headers: _deviceHeader));
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  /// Toggle a reaction; returns the refreshed post.
  Future<ForumPost> forumReact(String handle, String postId, String emoji,
      {required bool add}) async {
    try {
      final path =
          '/v1/chat/e/$handle/forum/posts/$postId/reactions/${Uri.encodeComponent(emoji)}';
      final r = add
          ? await _dio.put(path, options: Options(headers: _deviceHeader))
          : await _dio.delete(path, options: Options(headers: _deviceHeader));
      return ForumPost.fromJson(Map<String, dynamic>.from(r.data as Map));
    } on DioException catch (e) {
      throw _map(e);
    }
  }

  Future<void> forumReport(String handle, String postId, String? reason) async {
    try {
      await _dio.post('/v1/chat/e/$handle/forum/posts/$postId/report',
          data: {'reason': reason}, options: Options(headers: _deviceHeader));
    } on DioException catch (e) {
      throw _map(e);
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
