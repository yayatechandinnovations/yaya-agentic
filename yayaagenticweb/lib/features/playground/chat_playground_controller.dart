import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../api/api_client.dart';
import '../../api/sse_client.dart';
import '../../app/selected_tenant.dart';
import '../../models/inspector_snapshot.dart';
import '../../models/turn_event.dart';
import 'playground_controller.dart' show ChatMessage, Role;

/// The operator-chosen simulated caller identity for a chat-playground session.
/// Mirrors the three server-side paths in `ChatAuthService`.
class ChatIdentity {
  const ChatIdentity({
    this.mode = 'ANONYMOUS',
    this.phone = '',
    this.name = '',
    this.bearer = '',
  });

  /// `ANONYMOUS` | `VERIFIED_PHONE` | `BEARER`.
  final String mode;
  final String phone;
  final String name;
  final String bearer;

  ChatIdentity copyWith({String? mode, String? phone, String? name, String? bearer}) =>
      ChatIdentity(
        mode: mode ?? this.mode,
        phone: phone ?? this.phone,
        name: name ?? this.name,
        bearer: bearer ?? this.bearer,
      );

  String get summary => switch (mode) {
        'VERIFIED_PHONE' => phone.isEmpty ? 'Verified phone · (no number)' : 'Verified phone · $phone',
        'BEARER' => bearer.isEmpty ? 'Bearer · (no token)' : 'Bearer token',
        _ => 'Anonymous',
      };
}

class ChatPlaygroundState {
  ChatPlaygroundState({
    this.entryPointId,
    this.identity = const ChatIdentity(),
    this.sessionId,
    this.startFailed = false,
    this.failureMessage,
    this.greeting = '',
    this.messages = const [],
    this.streaming = false,
    this.starting = false,
    this.error,
    this.cart,
    this.pendingConfirm,
    this.currentQuickReplies = const [],
    this.inspector,
  });

  final String? entryPointId;
  final ChatIdentity identity;

  final String? sessionId;

  /// True when the backend resolved the caller to REJECT (or a request-shape
  /// error): there's no live session, but the inspector can still show why.
  final bool startFailed;
  final String? failureMessage;

  final String greeting;
  final List<ChatMessage> messages;
  final bool streaming;
  final bool starting;
  final String? error;
  final Map<String, dynamic>? cart;
  final UiHintEvent? pendingConfirm;
  final List<String> currentQuickReplies;
  final InspectorSnapshot? inspector;

  /// A live session that can take turns (start succeeded).
  bool get live => sessionId != null && !startFailed;

  /// Either a live session or an inspectable failed start — both leave the
  /// picker and show the transcript/inspector split.
  bool get hasSession => sessionId != null;

  ChatPlaygroundState copyWith({
    String? entryPointId,
    ChatIdentity? identity,
    String? sessionId,
    bool? startFailed,
    String? failureMessage,
    String? greeting,
    List<ChatMessage>? messages,
    bool? streaming,
    bool? starting,
    String? error,
    bool clearError = false,
    Map<String, dynamic>? cart,
    UiHintEvent? pendingConfirm,
    bool clearPendingConfirm = false,
    List<String>? currentQuickReplies,
    InspectorSnapshot? inspector,
  }) =>
      ChatPlaygroundState(
        entryPointId: entryPointId ?? this.entryPointId,
        identity: identity ?? this.identity,
        sessionId: sessionId ?? this.sessionId,
        startFailed: startFailed ?? this.startFailed,
        failureMessage: failureMessage ?? this.failureMessage,
        greeting: greeting ?? this.greeting,
        messages: messages ?? this.messages,
        streaming: streaming ?? this.streaming,
        starting: starting ?? this.starting,
        error: clearError ? null : (error ?? this.error),
        cart: cart ?? this.cart,
        pendingConfirm:
            clearPendingConfirm ? null : (pendingConfirm ?? this.pendingConfirm),
        currentQuickReplies: currentQuickReplies ?? this.currentQuickReplies,
        inspector: inspector ?? this.inspector,
      );
}

class ChatPlaygroundController extends Notifier<ChatPlaygroundState> {
  StreamSubscription<TurnEventDto>? _sub;

  @override
  ChatPlaygroundState build() {
    // A session is server-bound to the tenant it started under; drop it on a
    // top-bar tenant switch (mirrors the SSE playground).
    ref.listen<String?>(currentTenantOrNull, (prev, next) {
      if (prev != null && next != prev && state.hasSession) {
        endSession();
      }
    });
    return ChatPlaygroundState();
  }

  void selectEntryPoint(String? id) =>
      state = state.copyWith(entryPointId: id, clearError: true);

  void setIdentity(ChatIdentity identity) =>
      state = state.copyWith(identity: identity);

  String _base(String tenant) => '/v1/admin/tenants/$tenant/chat-playground';

  Future<void> start() async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) {
      state = state.copyWith(error: 'Pick a tenant from the top bar first.');
      return;
    }
    final epId = state.entryPointId;
    if (epId == null) {
      state = state.copyWith(error: 'Pick a chat entry point to test.');
      return;
    }
    final id = state.identity;
    if (id.mode == 'VERIFIED_PHONE' && id.phone.trim().isEmpty) {
      state = state.copyWith(error: 'Enter an E.164 phone for the verified-phone identity.');
      return;
    }
    if (id.mode == 'BEARER' && id.bearer.trim().isEmpty) {
      state = state.copyWith(error: 'Paste a bearer JWT for the bearer identity.');
      return;
    }

    state = state.copyWith(starting: true, clearError: true);
    try {
      final dio = await ref.read(dioProvider.future);
      final res = await dio.post(
        '${_base(tenant)}/sessions',
        data: {
          'entryPointId': epId,
          'identityMode': id.mode,
          if (id.phone.trim().isNotEmpty) 'phone': id.phone.trim(),
          if (id.name.trim().isNotEmpty) 'name': id.name.trim(),
          if (id.bearer.trim().isNotEmpty) 'bearer': id.bearer.trim(),
        },
        options: Options(contentType: 'application/json'),
      );
      final data = Map<String, dynamic>.from(res.data as Map);
      final failed = data['startFailed'] == true;
      state = state.copyWith(
        sessionId: data['sessionId'] as String?,
        startFailed: failed,
        failureMessage: data['failureMessage'] as String?,
        greeting: (data['greeting'] as String?) ?? '',
        currentQuickReplies:
            ((data['quickReplies'] as List?) ?? const []).map((e) => e.toString()).toList(),
        messages: const [],
        starting: false,
      );
      // Pull the auth-flow trace once — this is what surfaces the failing
      // resolver exchange even when the session couldn't start.
      if (state.hasSession) _refreshInspector();
    } catch (e) {
      state = state.copyWith(starting: false, error: 'Failed to start: ${_describe(e)}');
    }
  }

  Future<void> sendMessage(String text) async {
    if (!state.live) return;
    final tenant = ref.read(currentTenantOrNull);
    final sid = state.sessionId;
    if (tenant == null || sid == null) return;
    await _sub?.cancel();

    state = state.copyWith(
      messages: [
        ...state.messages,
        ChatMessage(role: Role.user, text: text),
        ChatMessage(role: Role.assistant, text: ''),
      ],
      streaming: true,
      clearError: true,
      clearPendingConfirm: true,
      currentQuickReplies: const [],
    );

    final sse = await _sse();
    _sub = sse
        .streamPath('${_base(tenant)}/sessions/$sid/messages', {'text': text})
        .listen(_onEvent, onError: (e) {
      state = state.copyWith(streaming: false, error: 'Stream error: $e');
    }, onDone: () {
      state = state.copyWith(streaming: false);
      _refreshInspector();
    });
  }

  /// Two doors, one lock: a tap (add/remove/checkout/load-more) runs a
  /// whitelisted tool through `.../actions`, streaming the same events.
  Future<void> dispatchAction(String tool, [Map<String, dynamic> args = const {}]) async {
    if (!state.live || state.streaming) return;
    final tenant = ref.read(currentTenantOrNull);
    final sid = state.sessionId;
    if (tenant == null || sid == null) return;
    await _sub?.cancel();

    state = state.copyWith(
      messages: [...state.messages, ChatMessage(role: Role.assistant, text: '')],
      streaming: true,
      clearError: true,
      clearPendingConfirm: true,
    );

    final sse = await _sse();
    _sub = sse
        .streamPath('${_base(tenant)}/sessions/$sid/actions', {'tool': tool, 'args': args})
        .listen(_onEvent, onError: (e) {
      state = state.copyWith(streaming: false, error: 'Action failed: ${_describe(e)}');
    }, onDone: () {
      state = state.copyWith(streaming: false);
      _refreshInspector();
    });
  }

  Future<void> endSession() async {
    final tenant = ref.read(currentTenantOrNull);
    final sid = state.sessionId;
    await _sub?.cancel();
    _sub = null;
    if (tenant != null && sid != null) {
      try {
        final dio = await ref.read(dioProvider.future);
        await dio.delete('${_base(tenant)}/sessions/$sid');
      } catch (_) {
        // Best-effort — the trace is bounded/in-memory and reaps itself.
      }
    }
    // Keep the entry-point + identity selection so "New session" is one click.
    state = ChatPlaygroundState(
      entryPointId: state.entryPointId,
      identity: state.identity,
    );
  }

  Future<void> _refreshInspector() async {
    final tenant = ref.read(currentTenantOrNull);
    final sid = state.sessionId;
    if (tenant == null || sid == null) return;
    try {
      final dio = await ref.read(dioProvider.future);
      final res = await dio.get('${_base(tenant)}/sessions/$sid/inspector');
      state = state.copyWith(
          inspector: InspectorSnapshot.fromJson(Map<String, dynamic>.from(res.data as Map)));
    } catch (_) {
      // Non-fatal — keep the previous snapshot.
    }
  }

  Future<SseClient> _sse() async {
    final dio = await ref.read(dioProvider.future);
    return SseClient(dio);
  }

  void _onEvent(TurnEventDto event) {
    if (event is UiHintEvent) {
      switch (event.kind) {
        case 'confirm':
          state = state.copyWith(pendingConfirm: event);
        case 'quick_replies':
          final items = event.payload['items'];
          if (items is List) {
            state = state.copyWith(
                currentQuickReplies: items.map((e) => e.toString()).toList());
          }
        case 'cart':
          state = state.copyWith(cart: event.payload);
        case 'product_catalog':
        case 'product_card':
        case 'checkout_result':
          _attachCommerceHint(event);
        default:
          break;
      }
      return;
    }

    final msgs = [...state.messages];
    if (msgs.isEmpty) return;
    final last = msgs.removeLast();
    final updated = switch (event) {
      TokenEvent(:final text) => last.copyWith(text: last.text + text),
      ToolCallEvent _ => last.copyWith(toolCalls: [...last.toolCalls, event]),
      ToolResultEvent _ => last.copyWith(toolResults: [...last.toolResults, event]),
      CitationEvent _ => last.copyWith(citations: [...last.citations, event]),
      UiHintEvent _ => last,
      EndEvent _ => last,
    };
    msgs.add(updated);
    state = state.copyWith(messages: msgs);
  }

  void _attachCommerceHint(UiHintEvent event) {
    final msgs = [...state.messages];
    if (msgs.isEmpty) return;
    final last = msgs.removeLast();
    msgs.add(last.copyWith(commerceHints: [...last.commerceHints, event]));
    state = state.copyWith(messages: msgs);
  }

  String _describe(Object error) {
    if (error is! DioException) return error.toString();
    final response = error.response;
    if (response == null) return error.message ?? error.toString();
    final data = response.data;
    final status = response.statusCode;
    String body;
    if (data is Map && (data['message'] is String || data['error'] is String)) {
      body = '${data['error'] ?? ''} ${data['message'] ?? ''}'.trim();
    } else if (data is String && data.isNotEmpty) {
      body = data;
    } else {
      body = response.statusMessage ?? 'no body';
    }
    return 'HTTP $status — $body';
  }
}

final chatPlaygroundProvider =
    NotifierProvider<ChatPlaygroundController, ChatPlaygroundState>(
        ChatPlaygroundController.new);
