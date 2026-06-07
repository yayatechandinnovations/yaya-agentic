import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../api/sessions_api.dart';
import '../../models/inspector_snapshot.dart';
import '../../models/start_session.dart';
import '../../models/turn_event.dart';

class ChatMessage {
  ChatMessage({required this.role, required this.text, this.toolCalls = const [], this.toolResults = const []});
  final Role role;
  final String text;
  final List<ToolCallEvent> toolCalls;
  final List<ToolResultEvent> toolResults;

  ChatMessage copyWith({String? text, List<ToolCallEvent>? toolCalls, List<ToolResultEvent>? toolResults}) =>
      ChatMessage(
        role: role,
        text: text ?? this.text,
        toolCalls: toolCalls ?? this.toolCalls,
        toolResults: toolResults ?? this.toolResults,
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
  });

  final StartSessionResponse? session;
  final List<ChatMessage> messages;
  final bool streaming;
  final String? error;

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
      );
}

class PlaygroundController extends Notifier<PlaygroundState> {
  StreamSubscription<TurnEventDto>? _sub;

  @override
  PlaygroundState build() => PlaygroundState();

  Future<void> startSession({
    String profileId = 'hello-world',
    int profileVersion = 1,
  }) async {
    final api = await ref.read(sessionsApiProvider.future);
    try {
      final res = await api.start(StartSessionRequest(
        profileId: profileId,
        profileVersion: profileVersion,
      ));
      state = PlaygroundState(
        session: res,
        messages: const [],
        currentQuickReplies: List.of(res.quickReplies),
      );
    } catch (e) {
      state = state.copyWith(error: 'Failed to start session: $e');
    }
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
      if (event.kind == 'confirm') {
        state = state.copyWith(pendingConfirm: event);
      } else if (event.kind == 'quick_replies') {
        final items = event.payload['items'];
        if (items is List) {
          state = state.copyWith(
              currentQuickReplies: items.map((e) => e.toString()).toList());
        }
      }
      return;
    }

    final msgs = [...state.messages];
    final last = msgs.removeLast();
    final updated = switch (event) {
      TokenEvent(:final text) => last.copyWith(text: last.text + text),
      ToolCallEvent _ => last.copyWith(toolCalls: [...last.toolCalls, event]),
      ToolResultEvent _ => last.copyWith(toolResults: [...last.toolResults, event]),
      CitationEvent _ => last,
      UiHintEvent _ => last,   // already handled above
      EndEvent _ => last,
    };
    msgs.add(updated);
    state = state.copyWith(messages: msgs);
  }
}

final playgroundProvider =
    NotifierProvider<PlaygroundController, PlaygroundState>(PlaygroundController.new);
