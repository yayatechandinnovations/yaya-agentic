import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../api/sessions_api.dart';
import '../../app/selected_tenant.dart';
import '../../models/act_as.dart';
import '../../models/inspector_snapshot.dart';
import '../../models/start_session.dart';
import '../../models/turn_event.dart';
import '../admin/profiles/profiles_screen.dart' show profilesProvider;

class ChatMessage {
  ChatMessage({
    required this.role,
    required this.text,
    this.toolCalls = const [],
    this.toolResults = const [],
    this.citations = const [],
    this.commerceHints = const [],
  });
  final Role role;
  final String text;
  final List<ToolCallEvent> toolCalls;
  final List<ToolResultEvent> toolResults;

  /// Citation footnotes attached to this turn. Order matches arrival on the
  /// SSE stream; the playground renders them as numbered footnote markers.
  final List<CitationEvent> citations;

  /// Commerce UiHints emitted during this turn that render as cards in the
  /// stream — `product_catalog`, `product_card`, `checkout_result`. The `cart`
  /// hint is NOT here; it drives the persistent cart bar instead.
  final List<UiHintEvent> commerceHints;

  ChatMessage copyWith({
    String? text,
    List<ToolCallEvent>? toolCalls,
    List<ToolResultEvent>? toolResults,
    List<CitationEvent>? citations,
    List<UiHintEvent>? commerceHints,
  }) =>
      ChatMessage(
        role: role,
        text: text ?? this.text,
        toolCalls: toolCalls ?? this.toolCalls,
        toolResults: toolResults ?? this.toolResults,
        citations: citations ?? this.citations,
        commerceHints: commerceHints ?? this.commerceHints,
      );
}

enum Role { user, assistant }

class PlaygroundState {
  PlaygroundState({
    this.session,
    this.messages = const [],
    this.streaming = false,
    this.error,
    this.pendingConfirm,
    this.currentQuickReplies = const [],
    this.inspector,
    this.cart,
  });

  final StartSessionResponse? session;
  final List<ChatMessage> messages;
  final bool streaming;
  final String? error;

  /// Latest `cart` UiHint payload — drives the persistent cart bar + sheet.
  /// Null until the first cart mutation this session.
  final Map<String, dynamic>? cart;

  /// When non-null, the engine has emitted a UiHint("confirm", …); the
  /// playground renders a card with Confirm/Cancel buttons.
  final UiHintEvent? pendingConfirm;

  /// The chips below the input. Initialised from session.quickReplies and
  /// replaced when the engine emits a UiHint("quick_replies", …).
  final List<String> currentQuickReplies;

  /// Latest inspector snapshot fetched after a turn — populates the right
  /// panel (intent, working memory, last prompt, last denial).
  final InspectorSnapshot? inspector;

  PlaygroundState copyWith({
    StartSessionResponse? session,
    List<ChatMessage>? messages,
    bool? streaming,
    String? error,
    bool clearError = false,
    UiHintEvent? pendingConfirm,
    bool clearPendingConfirm = false,
    List<String>? currentQuickReplies,
    InspectorSnapshot? inspector,
    Map<String, dynamic>? cart,
  }) =>
      PlaygroundState(
        session: session ?? this.session,
        messages: messages ?? this.messages,
        streaming: streaming ?? this.streaming,
        error: clearError ? null : (error ?? this.error),
        pendingConfirm:
            clearPendingConfirm ? null : (pendingConfirm ?? this.pendingConfirm),
        currentQuickReplies: currentQuickReplies ?? this.currentQuickReplies,
        inspector: inspector ?? this.inspector,
        cart: cart ?? this.cart,
      );
}

class PlaygroundController extends Notifier<PlaygroundState> {
  StreamSubscription<TurnEventDto>? _sub;

  @override
  PlaygroundState build() {
    // End any active session when the operator switches tenants. A session
    // is server-side bound to the tenant it was started under, so silently
    // keeping it alive after a top-bar switch would be misleading.
    ref.listen<String?>(currentTenantOrNull, (prev, next) {
      if (prev != null && next != prev && state.session != null) {
        endSession();
      }
    });
    return PlaygroundState();
  }

  Future<void> startSession({
    String profileId = 'hello-world',
    int profileVersion = 1,
    ActAs? actAs,
  }) async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) {
      state = state.copyWith(
          error: 'Pick a tenant from the top bar before starting a session.');
      return;
    }
    final api = await ref.read(sessionsApiProvider.future);
    try {
      final res = await api.start(StartSessionRequest(
        tenant: tenant,
        profileId: profileId,
        profileVersion: profileVersion,
        actAs: actAs,
      ));
      state = PlaygroundState(
        session: res,
        messages: const [],
        currentQuickReplies: List.of(res.quickReplies),
      );
    } catch (e) {
      state = state.copyWith(error: 'Failed to start session: ${_describe(e)}');
    }
  }

  /// Pulls the actual server message out of a DioException — by default Dio
  /// only stringifies the status code, hiding the body that explains *why*
  /// (e.g. an OriginEnforcer denial with the offending origin). Falls back to
  /// the exception toString for non-Dio failures.
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

  /// Ends the current session server-side and resets local state so the
  /// playground returns to the profile picker. Operators use this to switch
  /// from hello-world to retail-customer without reloading the page.
  Future<void> endSession() async {
    final sid = state.session?.sessionId;
    await _sub?.cancel();
    _sub = null;
    if (sid != null) {
      try {
        final api = await ref.read(sessionsApiProvider.future);
        await api.end(sid);
      } catch (_) {
        // Best-effort — server-side TTL eventually reaps the session even
        // if the explicit POST /end couldn't reach the backend.
      }
    }
    // Re-fetch the profile list so the picker shows anything the operator
    // added (or any bootstraps that ran on restart) while a session was open.
    ref.invalidate(profilesProvider);
    state = PlaygroundState();
  }

  Future<void> sendMessage(String text) async {
    final session = state.session;
    if (session == null) return;
    await _sub?.cancel();

    final api = await ref.read(sessionsApiProvider.future);
    final updated = [
      ...state.messages,
      ChatMessage(role: Role.user, text: text),
      ChatMessage(role: Role.assistant, text: ''),
    ];
    state = state.copyWith(
      messages: updated,
      streaming: true,
      clearError: true,
      clearPendingConfirm: true,
      currentQuickReplies: const [],
    );

    _sub = api.sse().stream(sessionId: session.sessionId, userText: text).listen(
      _onEvent,
      onError: (e) {
        state = state.copyWith(streaming: false, error: 'Stream error: $e');
      },
      onDone: () {
        state = state.copyWith(streaming: false);
        _refreshInspector();
      },
    );
  }

  Future<void> _refreshInspector() async {
    final sid = state.session?.sessionId;
    if (sid == null) return;
    try {
      final api = await ref.read(sessionsApiProvider.future);
      final snap = await api.inspect(sid);
      state = state.copyWith(inspector: snap);
    } catch (_) {
      // Non-fatal — the inspector panel just keeps the previous snapshot.
    }
  }

  void _onEvent(TurnEventDto event) {
    // Side-channel state (confirm card, chips) is updated separately from
    // the per-message body. We mutate messages only for token/tool events.
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
          // Drives the persistent cart bar + sheet, not an inline bubble.
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
    final last = msgs.removeLast();
    final updated = switch (event) {
      TokenEvent(:final text) => last.copyWith(text: last.text + text),
      ToolCallEvent _ => last.copyWith(toolCalls: [...last.toolCalls, event]),
      ToolResultEvent _ => last.copyWith(toolResults: [...last.toolResults, event]),
      CitationEvent _ => last.copyWith(citations: [...last.citations, event]),
      UiHintEvent _ => last,   // already handled above
      EndEvent _ => last,
    };
    msgs.add(updated);
    state = state.copyWith(messages: msgs);
  }

  /// Renders a commerce card (catalog / product / checkout result) under the
  /// current assistant turn.
  void _attachCommerceHint(UiHintEvent event) {
    final msgs = [...state.messages];
    if (msgs.isEmpty) return;
    final last = msgs.removeLast();
    msgs.add(last.copyWith(commerceHints: [...last.commerceHints, event]));
    state = state.copyWith(messages: msgs);
  }

  /// Two doors, one lock: a tap (add/remove/checkout/load-more) runs a
  /// whitelisted tool through `POST /sessions/{id}/actions`, streaming back the
  /// same events as a chat turn. Cart edits refresh the cart bar; a confirmable
  /// checkout surfaces the confirm card (completed via the yes/no chips).
  Future<void> dispatchAction(String tool, [Map<String, dynamic> args = const {}]) async {
    final session = state.session;
    if (session == null || state.streaming) return;
    await _sub?.cancel();

    final api = await ref.read(sessionsApiProvider.future);
    // A placeholder assistant turn holds any inline hints (e.g. checkout_result)
    // or tool activity; a pure cart edit leaves it empty (rendered as nothing).
    state = state.copyWith(
      messages: [...state.messages, ChatMessage(role: Role.assistant, text: '')],
      streaming: true,
      clearError: true,
      clearPendingConfirm: true,
    );

    _sub = api.sse().actions(sessionId: session.sessionId, tool: tool, args: args).listen(
      _onEvent,
      onError: (e) {
        state = state.copyWith(streaming: false, error: 'Action failed: ${_describe(e)}');
      },
      onDone: () {
        state = state.copyWith(streaming: false);
        _refreshInspector();
      },
    );
  }
}

final playgroundProvider =
    NotifierProvider<PlaygroundController, PlaygroundState>(PlaygroundController.new);
