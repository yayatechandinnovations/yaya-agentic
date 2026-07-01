import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/chat_api.dart';
import '../../data/providers.dart';
import '../../models/chat_message.dart';
import '../../models/entry_point_card.dart';

class ChatState {
  final EntryPointCard? card;
  final List<ChatMessage> messages;
  final String? sessionId;
  final bool loading;
  final bool sending;
  final String? error;
  final String? bearer;

  const ChatState({
    this.card,
    this.messages = const [],
    this.sessionId,
    this.loading = true,
    this.sending = false,
    this.error,
    this.bearer,
  });

  bool get authenticated => bearer != null && bearer!.isNotEmpty;

  /// Only OIDC entry points need a client-supplied token via the sign-in
  /// prompt: RESOLVER authenticates server-side from the verified phone, NONE
  /// needs no token, and NONE+AUTH_REQUIRED is a misconfiguration we don't
  /// surface a phantom paste box for.
  bool get needsSignIn =>
      card != null && card!.requiresAuth && card!.usesOidc && !authenticated;
  bool get canUpgrade => card != null && card!.optionalAuth && !card!.usesResolver
      && !authenticated && sessionId != null;

  ChatState copyWith({
    EntryPointCard? card,
    List<ChatMessage>? messages,
    String? sessionId,
    bool? loading,
    bool? sending,
    String? error,
    bool clearError = false,
    String? bearer,
  }) =>
      ChatState(
        card: card ?? this.card,
        messages: messages ?? this.messages,
        sessionId: sessionId ?? this.sessionId,
        loading: loading ?? this.loading,
        sending: sending ?? this.sending,
        error: clearError ? null : (error ?? this.error),
        bearer: bearer ?? this.bearer,
      );
}

class ChatController extends StateNotifier<ChatState> {
  ChatController(this._ref, this._handle) : super(const ChatState()) {
    _init();
  }

  final Ref _ref;
  final String _handle;

  ChatApi get _api => _ref.read(chatApiProvider);

  Future<void> _init() async {
    try {
      final card = await _api.resolve(_handle);
      state = state.copyWith(card: card, clearError: true);
      if (card.paused) {
        state = state.copyWith(loading: false, error: 'This chat is temporarily unavailable.');
        return;
      }
      if (card.requiresAuth && card.usesOidc && state.bearer == null) {
        // Defer session start until interactive sign-in (OIDC paste shell).
        // RESOLVER cards resolve server-side from the verified phone; a
        // misconfigured NONE+AUTH_REQUIRED card falls through to start() and
        // surfaces the backend's honest auth_required error.
        state = state.copyWith(loading: false);
        return;
      }
      await _startSession();
    } on ChatApiException catch (e) {
      state = state.copyWith(loading: false, error: _friendly(e));
    }
  }

  Future<void> _startSession() async {
    final deviceId = _ref.read(deviceIdProvider);
    final result = await _api.start(
      _handle,
      deviceId: deviceId,
      locale: state.card?.defaultLocale,
      bearer: state.bearer,
    );
    _ref.read(tilesProvider.notifier).setSession(_handle, result.sessionId);
    final greeting = <ChatMessage>[
      if ((state.card?.welcomeText ?? '').isNotEmpty)
        ChatMessage(role: 'assistant', text: state.card!.welcomeText!),
      if (result.greeting.isNotEmpty)
        ChatMessage(role: 'assistant', text: result.greeting, quickReplies: result.quickReplies),
    ];
    state = state.copyWith(
      sessionId: result.sessionId,
      messages: greeting,
      loading: false,
      clearError: true,
    );
  }

  /// Provide a verified bearer (interactive OIDC result, or dev paste) and start.
  Future<void> signInWithToken(String token) async {
    state = state.copyWith(bearer: token, loading: true, clearError: true);
    try {
      await _startSession();
    } on ChatApiException catch (e) {
      state = state.copyWith(loading: false, error: _friendly(e));
    }
  }

  /// AUTH_OPTIONAL: upgrade the live session to a verified identity without
  /// losing history (chat-channel-design.md §8.4).
  Future<void> upgrade(String token) async {
    final trimmed = token.trim();
    if (trimmed.isEmpty) return;
    if (state.sessionId == null) {
      await signInWithToken(trimmed);
      return;
    }
    try {
      await _api.authenticate(state.sessionId!, trimmed);
      state = state.copyWith(
        bearer: trimmed,
        clearError: true,
        messages: [
          ...state.messages,
          const ChatMessage(role: 'assistant', text: "You're signed in now."),
        ],
      );
    } on ChatApiException catch (e) {
      state = state.copyWith(error: _friendly(e));
    }
  }

  Future<void> send(String text) async {
    final trimmed = text.trim();
    if (trimmed.isEmpty || state.sending || state.sessionId == null) return;

    final withUser = [...state.messages, ChatMessage(role: 'user', text: trimmed)];
    final assistant = const ChatMessage(role: 'assistant', text: '', streaming: true);
    state = state.copyWith(messages: [...withUser, assistant], sending: true, clearError: true);

    final buffer = StringBuffer();
    final quickReplies = <String>[];
    final citations = <Citation>[];
    try {
      await for (final evt in _api.sendMessage(state.sessionId!, trimmed, bearer: state.bearer)) {
        switch (evt.event) {
          case 'token':
            buffer.write((evt.data['text'] ?? '').toString());
            _replaceLast(state.messages.last.copyWith(text: buffer.toString()));
            break;
          case 'ui_hint':
            final qr = evt.data['payload']?['quick_replies'] ?? evt.data['quick_replies'];
            if (qr is List) {
              quickReplies
                ..clear()
                ..addAll(qr.map((e) => e.toString()));
              _replaceLast(state.messages.last.copyWith(quickReplies: List.of(quickReplies)));
            }
            break;
          case 'citation':
            citations.add(Citation(
              title: (evt.data['title'] ?? evt.data['source'] ?? 'source').toString(),
              url: evt.data['url']?.toString(),
            ));
            _replaceLast(state.messages.last.copyWith(citations: List.of(citations)));
            break;
          case 'end':
            _replaceLast(state.messages.last.copyWith(streaming: false));
            break;
        }
      }
      _replaceLast(state.messages.last.copyWith(streaming: false));
      state = state.copyWith(sending: false);
    } on ChatApiException catch (e) {
      _replaceLast(state.messages.last.copyWith(streaming: false));
      state = state.copyWith(sending: false, error: _friendly(e));
    }
  }

  void _replaceLast(ChatMessage m) {
    final msgs = [...state.messages];
    if (msgs.isEmpty) return;
    msgs[msgs.length - 1] = m;
    state = state.copyWith(messages: msgs);
  }

  String _friendly(ChatApiException e) {
    switch (e.code) {
      case 'rate_limited':
        return 'Too many requests — please try again shortly.';
      case 'auth_required':
        return 'This chat requires sign-in.';
      case 'verification_required':
        return 'Verify your phone number to start this chat.';
      case 'resolver_unresolved':
        return "We couldn't find an account for this number.";
      case 'session_not_found':
        return 'This chat session expired. Reopen to start a new one.';
      case 'not_found':
        return 'This chat is no longer available.';
      default:
        return e.message;
    }
  }
}

final chatControllerProvider =
    StateNotifierProvider.family<ChatController, ChatState, String>(
  (ref, handle) => ChatController(ref, handle),
);
