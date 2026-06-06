import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../api/sessions_api.dart';
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
  });

  final StartSessionResponse? session;
  final List<ChatMessage> messages;
  final bool streaming;
  final String? error;

  PlaygroundState copyWith({
    StartSessionResponse? session,
    List<ChatMessage>? messages,
    bool? streaming,
    String? error,
    bool clearError = false,
  }) =>
      PlaygroundState(
        session: session ?? this.session,
        messages: messages ?? this.messages,
        streaming: streaming ?? this.streaming,
        error: clearError ? null : (error ?? this.error),
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
      state = PlaygroundState(session: res, messages: const []);
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
    state = state.copyWith(messages: updated, streaming: true, clearError: true);

    _sub = api.sse().stream(sessionId: session.sessionId, userText: text).listen(
      _onEvent,
      onError: (e) {
        state = state.copyWith(streaming: false, error: 'Stream error: $e');
      },
      onDone: () {
        state = state.copyWith(streaming: false);
      },
    );
  }

  void _onEvent(TurnEventDto event) {
    final msgs = [...state.messages];
    final last = msgs.removeLast();

    final updated = switch (event) {
      TokenEvent(:final text) => last.copyWith(text: last.text + text),
      ToolCallEvent _ => last.copyWith(toolCalls: [...last.toolCalls, event]),
      ToolResultEvent _ => last.copyWith(toolResults: [...last.toolResults, event]),
      CitationEvent _ => last,
      UiHintEvent _ => last,
      EndEvent _ => last,
    };
    msgs.add(updated);
    state = state.copyWith(messages: msgs);
  }
}

final playgroundProvider =
    NotifierProvider<PlaygroundController, PlaygroundState>(PlaygroundController.new);
