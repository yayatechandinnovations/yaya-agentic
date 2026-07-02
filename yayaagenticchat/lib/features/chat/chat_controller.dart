import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/chat_api.dart';
import '../../data/providers.dart';
import '../../models/chat_message.dart';
import '../../models/commerce/cart.dart';
import '../../models/commerce/catalog_payload.dart';
import '../../models/commerce/checkout_result.dart';
import '../../models/commerce/commerce_config.dart';
import '../../models/commerce/confirm_prompt.dart';
import '../../models/commerce/product.dart';
import '../../models/entry_point_card.dart';

class ChatState {
  final EntryPointCard? card;
  final List<ChatMessage> messages;
  final String? sessionId;
  final bool loading;
  final bool sending;
  final String? error;
  final String? bearer;

  // Commerce state (all null / empty for a non-store profile).
  final CommerceConfig? commerce;
  final Cart? cart;
  final ConfirmPrompt? pendingConfirm;

  /// One-shot side-effect trigger: a fresh checkout hand-off the UI layer must
  /// act on (open the hosted page / mount the PSP sheet / show the receipt),
  /// then clear. Kept off the message list so the effect fires exactly once.
  final CheckoutResult? pendingCheckout;

  const ChatState({
    this.card,
    this.messages = const [],
    this.sessionId,
    this.loading = true,
    this.sending = false,
    this.error,
    this.bearer,
    this.commerce,
    this.cart,
    this.pendingConfirm,
    this.pendingCheckout,
  });

  bool get authenticated => bearer != null && bearer!.isNotEmpty;

  bool get isStore => commerce?.isStore ?? false;

  int get cartCount => cart?.itemCount ?? 0;

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
    CommerceConfig? commerce,
    Cart? cart,
    ConfirmPrompt? pendingConfirm,
    bool clearConfirm = false,
    CheckoutResult? pendingCheckout,
    bool clearCheckout = false,
  }) =>
      ChatState(
        card: card ?? this.card,
        messages: messages ?? this.messages,
        sessionId: sessionId ?? this.sessionId,
        loading: loading ?? this.loading,
        sending: sending ?? this.sending,
        error: clearError ? null : (error ?? this.error),
        bearer: bearer ?? this.bearer,
        commerce: commerce ?? this.commerce,
        cart: cart ?? this.cart,
        pendingConfirm: clearConfirm ? null : (pendingConfirm ?? this.pendingConfirm),
        pendingCheckout: clearCheckout ? null : (pendingCheckout ?? this.pendingCheckout),
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
      commerce: result.commerce,
    );
    // Hydrate the cart badge for a returning shopper (durable cart projection,
    // §14). Best-effort — a fresh session just gets an empty cart.
    if (result.commerce?.cartEnabled ?? false) {
      unawaited(_silentAction(CommerceTools.viewCart, const {}));
    }
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

  /* ------------------------------ chat send ----------------------------- */

  Future<void> send(String text) async {
    final trimmed = text.trim();
    if (trimmed.isEmpty || state.sending || state.sessionId == null) return;

    // Any new user turn resolves or pivots away from a pending confirm — the
    // engine drops its own pending_confirm; mirror that on the client.
    final withUser = [...state.messages, ChatMessage(role: 'user', text: trimmed)];
    final assistant = const ChatMessage(role: 'assistant', text: '', streaming: true);
    state = state.copyWith(
        messages: [...withUser, assistant],
        sending: true,
        clearError: true,
        clearConfirm: true);

    final buf = _TurnBuffers();
    try {
      await for (final evt in _api.sendMessage(state.sessionId!, trimmed, bearer: state.bearer)) {
        _applyEvent(evt, buf, hasAssistantBubble: true);
      }
      _finishAssistant();
      state = state.copyWith(sending: false);
    } on ChatApiException catch (e) {
      _finishAssistant();
      state = state.copyWith(sending: false, error: _friendly(e));
    }
  }

  /* --------------------------- action (tap) ----------------------------- */

  /// Invoke a whitelisted commerce tool by tap through the action channel
  /// (§9). Returns true on success. A refused/failed action surfaces a friendly
  /// error and returns false. Cart/confirm/checkout state updates flow through
  /// the shared event processor exactly like a chat turn.
  Future<bool> invokeAction(String tool, Map<String, Object?> args) async {
    if (state.sessionId == null) return false;
    final buf = _TurnBuffers();
    try {
      await for (final evt in _api.sendAction(state.sessionId!, tool, args, bearer: state.bearer)) {
        _applyEvent(evt, buf, hasAssistantBubble: false);
      }
      return true;
    } on ChatApiException catch (e) {
      state = state.copyWith(error: _friendly(e));
      return false;
    }
  }

  /// A tap action whose cart/confirm side effects matter but whose failures are
  /// silent (e.g. the on-open cart hydration).
  Future<void> _silentAction(String tool, Map<String, Object?> args) async {
    if (state.sessionId == null) return;
    try {
      final buf = _TurnBuffers();
      await for (final evt in _api.sendAction(state.sessionId!, tool, args, bearer: state.bearer)) {
        _applyEvent(evt, buf, hasAssistantBubble: false);
      }
    } on ChatApiException catch (_) {
      // best-effort
    }
  }

  // Cart edits (§8) — every one runs through the same executor + Authorizer.
  Future<bool> addToCart(String productId, {String? variantId, int qty = 1}) =>
      invokeAction(CommerceTools.addToCart, {
        'productId': productId,
        'variantId': ?variantId,
        'qty': qty,
      });

  Future<bool> removeLine(String lineId) =>
      invokeAction(CommerceTools.removeFromCart, {'lineId': lineId});

  Future<bool> setQty(String lineId, int qty) =>
      invokeAction(CommerceTools.setCartQuantity, {'lineId': lineId, 'qty': qty});

  Future<bool> clearCart() => invokeAction(CommerceTools.clearCart, const {});

  Future<bool> viewCart() => invokeAction(CommerceTools.viewCart, const {});

  Future<bool> loadMore(String query, int nextPage) =>
      invokeAction(CommerceTools.searchProducts, {'query': query, 'page': nextPage});

  /// Start checkout by tap. `checkout` is `confirmable`, so this raises the
  /// `confirm` UiHint (→ [state.pendingConfirm]); the shopper still explicitly
  /// approves before any PSP hand-off (§9, §11). The tool id comes from the
  /// profile's commerce block.
  Future<bool> checkout() =>
      invokeAction(state.commerce?.checkoutTool ?? CommerceTools.checkout, const {});

  /* --------------------------- confirm flow ----------------------------- */

  /// Approve a pending confirm. The engine reads the next message text via its
  /// `ConfirmDetector`, so a canonical affirmative resolves the pending tool
  /// (there is no dedicated confirm endpoint).
  Future<void> confirmPending() => send('yes');

  Future<void> cancelPending() => send('no');

  /// The UI layer calls this after it has consumed [state.pendingCheckout].
  void checkoutConsumed() => state = state.copyWith(clearCheckout: true);

  /* --------------------------- event plumbing --------------------------- */

  void _applyEvent(SseEvent evt, _TurnBuffers buf, {required bool hasAssistantBubble}) {
    switch (evt.event) {
      case 'token':
        if (!hasAssistantBubble) break;
        buf.text.write((evt.data['text'] ?? '').toString());
        _replaceLast(state.messages.last.copyWith(text: buf.text.toString()));
        break;
      case 'citation':
        if (!hasAssistantBubble) break;
        buf.citations.add(Citation(
          title: (evt.data['title'] ?? evt.data['source'] ?? 'source').toString(),
          url: evt.data['url']?.toString(),
        ));
        _replaceLast(state.messages.last.copyWith(citations: List.of(buf.citations)));
        break;
      case 'ui_hint':
        _applyUiHint(evt.data, buf, hasAssistantBubble: hasAssistantBubble);
        break;
      case 'end':
        if (hasAssistantBubble) _finishAssistant();
        break;
    }
  }

  void _applyUiHint(Map<String, dynamic> data, _TurnBuffers buf,
      {required bool hasAssistantBubble}) {
    final kind = data['kind']?.toString();
    final payload = data['payload'] is Map
        ? Map<String, dynamic>.from(data['payload'] as Map)
        : <String, dynamic>{};

    switch (kind) {
      case 'quick_replies':
        // Engine emits {"payload":{"items":[…]}}. Tolerate the older shapes too.
        final raw = payload['items'] ?? payload['quick_replies'] ?? data['quick_replies'];
        if (raw is List && hasAssistantBubble) {
          buf.quickReplies
            ..clear()
            ..addAll(raw.map((e) => e.toString()));
          _replaceLast(state.messages.last.copyWith(quickReplies: List.of(buf.quickReplies)));
        }
        break;
      case 'product_catalog':
        if (hasAssistantBubble) {
          _replaceLast(state.messages.last.copyWith(catalog: CatalogPayload.fromJson(payload)));
        } else {
          _appendAssistant(ChatMessage(
              role: 'assistant', text: '', catalog: CatalogPayload.fromJson(payload)));
        }
        break;
      case 'product_card':
        final product = Product.fromJson(payload);
        if (hasAssistantBubble) {
          _replaceLast(state.messages.last.copyWith(productCard: product));
        } else {
          _appendAssistant(ChatMessage(role: 'assistant', text: '', productCard: product));
        }
        break;
      case 'cart':
        final cart = Cart.fromJson(payload);
        // Global cart drives the header badge / mini-bar / sheet; the inline
        // snapshot on the assistant bubble is the "what just changed" hint.
        state = state.copyWith(cart: cart);
        if (hasAssistantBubble) {
          _replaceLast(state.messages.last.copyWith(cartSnapshot: cart));
        }
        break;
      case 'confirm':
        state = state.copyWith(pendingConfirm: ConfirmPrompt.fromJson(payload));
        break;
      case 'checkout_result':
        final result = CheckoutResult.fromJson(payload);
        if (hasAssistantBubble) {
          _replaceLast(state.messages.last.copyWith(checkoutResult: result));
        } else {
          _appendAssistant(ChatMessage(role: 'assistant', text: '', checkoutResult: result));
        }
        _onCheckout(result);
        break;
      default:
        break; // entity_conflict and any future kind: ignore, don't crash.
    }
  }

  void _onCheckout(CheckoutResult result) {
    // A completed order clears the cart optimistically (the PSP webhook is the
    // authoritative clear, but the shopper shouldn't see stale items).
    final cartCleared = result.mode == CheckoutMode.orderPlaced
        ? Cart(currency: result.currency)
        : null;
    state = state.copyWith(pendingCheckout: result, cart: cartCleared);
  }

  void _appendAssistant(ChatMessage m) =>
      state = state.copyWith(messages: [...state.messages, m]);

  void _finishAssistant() {
    if (state.messages.isEmpty) return;
    final last = state.messages.last;
    if (last.streaming) _replaceLast(last.copyWith(streaming: false));
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
      case 'forbidden':
        return "That action isn't available right now.";
      default:
        return e.message;
    }
  }
}

/// Per-turn accumulators for the streamed assistant bubble.
class _TurnBuffers {
  final StringBuffer text = StringBuffer();
  final List<String> quickReplies = <String>[];
  final List<Citation> citations = <Citation>[];
}

/// Canonical commerce tool ids (mirror of the backend `CommerceTools`), used
/// when the client invokes a tool by tap.
class CommerceTools {
  const CommerceTools._();
  static const searchProducts = 'search_products';
  static const getProduct = 'get_product';
  static const addToCart = 'add_to_cart';
  static const removeFromCart = 'remove_from_cart';
  static const setCartQuantity = 'set_cart_quantity';
  static const viewCart = 'view_cart';
  static const clearCart = 'clear_cart';
  static const checkout = 'checkout';
}

final chatControllerProvider =
    StateNotifierProvider.family<ChatController, ChatState, String>(
  (ref, handle) => ChatController(ref, handle),
);
