import 'dart:async';
import 'dart:typed_data';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/chat_api.dart';
import '../../data/credential_store.dart';
import '../../data/providers.dart';
import '../../models/chat_menu.dart';
import '../../models/chat_message.dart';
import '../../models/data_view.dart';
import '../../models/commerce/cart.dart';
import '../../models/commerce/catalog_payload.dart';
import '../../models/commerce/checkout_result.dart';
import '../../models/commerce/commerce_config.dart';
import '../../models/commerce/confirm_prompt.dart';
import '../../models/commerce/product.dart';
import '../../models/entry_point_card.dart';
import '../../models/receipts.dart';

/// One attachment staged in the composer tray (chat-experience-design.md §6):
/// uploading → uploaded (server attachment with signed URL + transcript) or
/// failed (retryable — the bytes are kept until sent or removed).
class PendingAttachment {
  final String localId;
  final String filename;
  final Uint8List bytes;
  final bool isVoice;
  final ChatAttachment? uploaded;
  final String? error;

  const PendingAttachment({
    required this.localId,
    required this.filename,
    required this.bytes,
    this.isVoice = false,
    this.uploaded,
    this.error,
  });

  bool get uploading => uploaded == null && error == null;
  bool get failed => error != null;

  PendingAttachment copyWith({ChatAttachment? uploaded, String? error, bool clearError = false}) =>
      PendingAttachment(
        localId: localId,
        filename: filename,
        bytes: bytes,
        isVoice: isVoice,
        uploaded: uploaded ?? this.uploaded,
        error: clearError ? null : (error ?? this.error),
      );
}

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

  /// Receipt templates delivered at session start (chat-experience §7.3).
  final ReceiptsConfig? receipts;

  /// A ticket/receipt being built play-by-play (§7.8): rendered as a live
  /// pinned preview above the composer, replaced in place each turn, cleared
  /// when the final receipt is issued. Never a message bubble, never persisted.
  final ReceiptInstance? draftReceipt;

  /// One-shot side-effect trigger: a fresh checkout hand-off the UI layer must
  /// act on (open the hosted page / mount the PSP sheet / show the receipt),
  /// then clear. Kept off the message list so the effect fires exactly once.
  final CheckoutResult? pendingCheckout;

  /// A LIVE prior session was found (§7.2): the app offers Continue vs Start new
  /// instead of auto-starting. [resumeSessionId] is the session to hydrate.
  final bool resumeAvailable;
  final String? resumeSessionId;

  /// Attachments staged in the composer tray, not yet sent (§6).
  final List<PendingAttachment> pendingAttachments;

  /// The persistent owner menu (owner-ai-experience v2 §3): session chrome, not
  /// a bubble. Only the LATEST `menu` UiHint is kept — each new one replaces it.
  final ChatMenu? menu;

  /// Pending (undelivered) inbox messages, peeked via `/inbox/count` — drives
  /// the app-bar badge; cleared when the inbox is drained (owner-ai v2 §5).
  final int unreadCount;

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
    this.receipts,
    this.draftReceipt,
    this.pendingCheckout,
    this.resumeAvailable = false,
    this.resumeSessionId,
    this.pendingAttachments = const [],
    this.menu,
    this.unreadCount = 0,
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
    ReceiptsConfig? receipts,
    ReceiptInstance? draftReceipt,
    bool clearDraftReceipt = false,
    bool clearConfirm = false,
    CheckoutResult? pendingCheckout,
    bool clearCheckout = false,
    bool? resumeAvailable,
    String? resumeSessionId,
    bool clearResume = false,
    List<PendingAttachment>? pendingAttachments,
    ChatMenu? menu,
    int? unreadCount,
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
        receipts: receipts ?? this.receipts,
        draftReceipt: clearDraftReceipt ? null : (draftReceipt ?? this.draftReceipt),
        pendingCheckout: clearCheckout ? null : (pendingCheckout ?? this.pendingCheckout),
        resumeAvailable: clearResume ? false : (resumeAvailable ?? this.resumeAvailable),
        resumeSessionId: clearResume ? null : (resumeSessionId ?? this.resumeSessionId),
        pendingAttachments: pendingAttachments ?? this.pendingAttachments,
        menu: menu ?? this.menu,
        unreadCount: unreadCount ?? this.unreadCount,
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
      await _resolveResume();
    } on ChatApiException catch (e) {
      state = state.copyWith(loading: false, error: _friendly(e));
    }
  }

  /// Decide how to open the chat (§7.2). A verified user asks the server for the
  /// latest session: LIVE offers Continue-vs-New; ENDED shows the prior
  /// transcript above a fresh session; NONE/unverified is the normal greeting.
  Future<void> _resolveResume() async {
    if (!_ref.read(verificationProvider).verified) {
      await _startSession();
      return;
    }
    final LatestSession latest;
    try {
      latest = await _api.latestSession(_handle);
    } on ChatApiException {
      // Resume is a nicety — a failed probe just falls back to a fresh session.
      await _startSession();
      return;
    }
    switch (latest.status) {
      case 'LIVE':
        _ref.read(tilesProvider.notifier).setSession(_handle, latest.sessionId);
        state = state.copyWith(
          loading: false,
          resumeAvailable: true,
          resumeSessionId: latest.sessionId,
        );
        break;
      case 'ENDED':
        // Design decision §14.3: render the prior transcript read-only, then the
        // first message silently starts a new session.
        final prior = await _safeHistory(latest.sessionId);
        await _startSession(prior: prior);
        break;
      default:
        await _startSession();
    }
  }

  Future<List<ChatMessage>> _safeHistory(String? sessionId) async {
    if (sessionId == null) return const [];
    try {
      return await _api.sessionHistory(sessionId);
    } on ChatApiException {
      return const [];
    }
  }

  Future<void> _startSession({List<ChatMessage> prior = const []}) async {
    final deviceId = _ref.read(deviceIdProvider);
    final result = await _api.start(
      _handle,
      deviceId: deviceId,
      locale: state.card?.defaultLocale,
      bearer: state.bearer,
    );
    _ref.read(tilesProvider.notifier).setSession(_handle, result.sessionId);
    final greeting = <ChatMessage>[
      ...prior,
      if ((state.card?.welcomeText ?? '').isNotEmpty)
        ChatMessage(role: 'assistant', text: state.card!.welcomeText!),
      if (result.greeting.isNotEmpty)
        ChatMessage(
            role: 'assistant',
            text: result.greeting,
            quickReplies: [for (final q in result.quickReplies) QuickReply.text(q)]),
    ];
    state = state.copyWith(
      sessionId: result.sessionId,
      messages: greeting,
      loading: false,
      clearResume: true,
      clearError: true,
      commerce: result.commerce,
      receipts: result.receipts,
      // Seed the docked owner menu from the profile's `extensions.menu`
      // (owner-ai v2 §3) so it's there before the first turn; any later
      // `menu` ui_hint still replaces it wholesale.
      menu: result.menu,
    );
    // Hydrate the cart badge for a returning shopper (durable cart projection,
    // §14). Best-effort — a fresh session just gets an empty cart.
    if (result.commerce?.cartEnabled ?? false) {
      unawaited(_silentAction(CommerceTools.viewCart, const {}));
    }
    // Drain any system messages delivered while offline (system-messaging §11).
    // Best-effort: an anonymous chat (no verified identity) simply has no inbox.
    unawaited(_drainInbox());
  }

  /// Re-drain the inbox on a foreground push wake for this tile, so a message
  /// that arrives while the chat is open appears without reopening it.
  Future<void> refreshInbox() => _drainInbox();

  /// Peek the pending-inbox count WITHOUT delivering (owner-ai v2 §5) — drives
  /// the app-bar unread badge. Only touches state when the count actually
  /// changed, so the badge never flickers.
  Future<void> refreshUnreadCount() async {
    try {
      final count = await _api.inboxCount(_handle, bearer: state.bearer);
      if (!mounted || count == state.unreadCount) return;
      state = state.copyWith(unreadCount: count);
    } catch (_) {
      // Badge is a nicety — a failed peek changes nothing.
    }
  }

  /// Fetch + append pending system-participant messages, rendered as attributed
  /// bubbles with their attachments (system-messaging-design.md §11, §14).
  /// A drain always clears the unread badge — those messages are now on screen.
  Future<void> _drainInbox() async {
    try {
      final items = await _api.inbox(_handle, bearer: state.bearer);
      if (!mounted) return;
      if (items.isEmpty) {
        if (state.unreadCount != 0) state = state.copyWith(unreadCount: 0);
        return;
      }
      final drained = items
          .map((m) => ChatMessage(
                role: 'assistant',
                text: m.body,
                senderDisplayName: m.senderDisplay ?? ChatMessage.systemSenderFallback,
                attachments: m.attachments,
                actions: m.actions,
              ))
          .toList();
      state = state.copyWith(
          messages: [..._withoutQuickReplies(state.messages), ...drained],
          unreadCount: 0);
    } catch (_) {
      // Never let a drain failure disrupt the live chat.
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

  /* ------------------------------- resume ------------------------------- */

  /// LIVE resume (§7.2): hydrate the prior turns and keep streaming into the
  /// same session — the stored `sessionId` finally gets used.
  Future<void> continueSession() async {
    final sid = state.resumeSessionId;
    if (sid == null) {
      await startNewSession();
      return;
    }
    state = state.copyWith(loading: true, clearResume: true, clearError: true);
    try {
      final history = await _api.sessionHistory(sid);
      _ref.read(tilesProvider.notifier).setSession(_handle, sid);
      state = state.copyWith(sessionId: sid, messages: history, loading: false, clearError: true);
      unawaited(_drainInbox());
    } on ChatApiException catch (e) {
      state = state.copyWith(loading: false, error: _friendly(e));
    }
  }

  /// Decline the LIVE candidate and open a fresh session (§7.2).
  Future<void> startNewSession() async {
    state = state.copyWith(loading: true, clearResume: true, clearError: true, messages: const []);
    try {
      await _startSession();
    } on ChatApiException catch (e) {
      state = state.copyWith(loading: false, error: _friendly(e));
    }
  }

  /// "Limpiar conversación": wipe the visible transcript and start a fresh
  /// session. Ends the old session best-effort so the server stops resuming it,
  /// and clears any pending confirm so nothing carries over into the new thread.
  Future<void> clearConversation() async {
    final old = state.sessionId;
    if (old != null && old.isNotEmpty) {
      unawaited(_api.end(old));
    }
    state = state.copyWith(
      loading: true,
      messages: const [],
      clearResume: true,
      clearError: true,
      clearConfirm: true,
    );
    try {
      await _startSession();
    } on ChatApiException catch (e) {
      state = state.copyWith(loading: false, error: _friendly(e));
    }
  }

  /* --------------------------- attachments (§6) -------------------------- */

  static int _attachSeq = 0;

  /// Stage + upload one attachment into the composer tray. The tray entry
  /// shows progress and becomes sendable once the server accepts it.
  Future<void> attach(Uint8List bytes, String filename, {bool isVoice = false}) async {
    final sid = state.sessionId;
    if (sid == null) return;
    final localId = 'p${_attachSeq++}';
    final pending = PendingAttachment(
        localId: localId, filename: filename, bytes: bytes, isVoice: isVoice);
    state = state.copyWith(pendingAttachments: [...state.pendingAttachments, pending]);
    await _upload(sid, localId);
  }

  Future<void> retryAttachment(String localId) async {
    final sid = state.sessionId;
    if (sid == null) return;
    _patchPending(localId, (p) => p.copyWith(clearError: true));
    await _upload(sid, localId);
  }

  void removeAttachment(String localId) {
    state = state.copyWith(
        pendingAttachments:
            state.pendingAttachments.where((p) => p.localId != localId).toList());
  }

  Future<void> _upload(String sid, String localId) async {
    final p = state.pendingAttachments.where((x) => x.localId == localId).firstOrNull;
    if (p == null) return;
    try {
      final uploaded = await _api.uploadAttachment(sid, p.bytes, p.filename);
      _patchPending(localId, (x) => x.copyWith(uploaded: uploaded));
    } on ChatApiException catch (e) {
      _patchPending(localId, (x) => x.copyWith(error: _friendly(e)));
    }
  }

  void _patchPending(String localId, PendingAttachment Function(PendingAttachment) fn) {
    state = state.copyWith(pendingAttachments: [
      for (final p in state.pendingAttachments) p.localId == localId ? fn(p) : p
    ]);
  }

  /// Resolve the template a receipt renders with: the hint's explicit
  /// templateId wins; otherwise fall back to the first configured template
  /// (selection is declarative, never inferred — chat-experience §7.2).
  ReceiptTemplate? templateFor(ReceiptInstance receipt) {
    final config = state.receipts;
    if (config == null || config.isEmpty) return receipt.snapshot;
    return config.byId(receipt.templateId) ??
        receipt.snapshot ??
        config.templates.first;
  }

  /// A tap on a receipt action button — the same authz-gated /actions door as
  /// every cart tap ("two doors, one lock").
  Future<void> invokeReceiptAction(ReceiptAction action) =>
      invokeAction(action.tool, action.args);

  /* ------------------------------ chat send ----------------------------- */

  /// [selection] optionally rides the message as the structured `selection`
  /// field (network-chat-runtime §3.2) — used by the multi-item confirm
  /// (`{"kind":"confirm_items","itemIds":[…]}`, owner-ai v2 §4).
  Future<void> send(String text, {Map<String, Object?>? selection}) async {
    final trimmed = text.trim();
    final ready = state.pendingAttachments.where((p) => p.uploaded != null).toList();
    if ((trimmed.isEmpty && ready.isEmpty) || state.sending || state.sessionId == null) {
      return;
    }
    if (state.pendingAttachments.any((p) => p.uploading)) return; // wait for uploads

    final attachmentIds = [for (final p in ready) p.uploaded!.assetId];
    final sentAttachments = [for (final p in ready) p.uploaded!];

    // Any new user turn resolves or pivots away from a pending confirm — the
    // engine drops its own pending_confirm; mirror that on the client. Stale
    // quick replies come off older bubbles too (owner-ai v2 §5): chips belong
    // only to the LATEST assistant turn.
    final withUser = [
      ..._withoutQuickReplies(state.messages),
      ChatMessage(role: 'user', text: trimmed, attachments: sentAttachments),
    ];
    final assistant = const ChatMessage(role: 'assistant', text: '', streaming: true);
    state = state.copyWith(
        messages: [...withUser, assistant],
        sending: true,
        clearError: true,
        clearConfirm: true,
        pendingAttachments: const []);

    final buf = _TurnBuffers();
    try {
      await for (final evt in _api.sendMessage(state.sessionId!, trimmed,
          bearer: state.bearer, attachmentIds: attachmentIds, selection: selection)) {
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

  /// A tap on a message-owned action button (owner-ai v3 §D3). A bound action
  /// (WhatsApp ops §14.3) sends its label as the legible transcript plus the
  /// structured `{"kind":"action","actionId":…}` selection: the platform
  /// resolves the action and runs its tool, and the tap IS the one consent
  /// (§5 R1) — no confirm card follows. A legacy action submits its `send`
  /// text, as before.
  Future<void> tapMessageAction(MessageAction action) => action.isBound
      ? send(action.label.isNotEmpty ? action.label : action.send,
          selection: {'kind': 'action', 'actionId': action.actionId})
      : send(action.send);

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

  /// Fetch a page of the catalog for the storefront browse sheet WITHOUT
  /// appending it to the chat — the sheet renders it in a dedicated grid. Runs
  /// `search_products` through the same executor + Authorizer as any action, but
  /// captures the `product_catalog` payload instead of streaming it into the
  /// message list. Pillar B §B.2.
  Future<CatalogPayload?> searchCatalog({String query = '', int page = 0}) async {
    if (state.sessionId == null) return null;
    CatalogPayload? found;
    final args = <String, Object?>{if (query.isNotEmpty) 'query': query, 'page': page};
    try {
      await for (final evt
          in _api.sendAction(state.sessionId!, CommerceTools.searchProducts, args, bearer: state.bearer)) {
        if (evt.event == 'ui_hint' &&
            evt.data['kind'] == 'product_catalog' &&
            evt.data['payload'] is Map) {
          found = CatalogPayload.fromJson(Map<String, dynamic>.from(evt.data['payload'] as Map));
        }
      }
    } on ChatApiException catch (e) {
      state = state.copyWith(error: _friendly(e));
      return null;
    }
    return found;
  }

  /// Start checkout by tap. `checkout` is `confirmable`, so this raises the
  /// `confirm` UiHint (→ [state.pendingConfirm]); the shopper still explicitly
  /// approves before any PSP hand-off (§9, §11). The tool id comes from the
  /// profile's commerce block.
  Future<bool> checkout() =>
      invokeAction(state.commerce?.checkoutTool ?? CommerceTools.checkout, const {});

  /* --------------------------- confirm flow ----------------------------- */

  /// Approve the pending confirm (one-confirmation rule, WhatsApp ops §6.7):
  /// the button's label (the frame's own `confirmLabel`, else [fallbackLabel])
  /// as the legible transcript plus the structured
  /// `{"kind":"confirm","callId":…}` selection, which the engine resolves by
  /// callId before any NL reading. The label alone is still a recognized
  /// affirmative for an engine that predates the selection.
  Future<void> confirmPending({String fallbackLabel = 'Confirmar'}) {
    final prompt = state.pendingConfirm;
    return send(prompt?.confirmText(fallbackLabel) ?? fallbackLabel, selection: {
      'kind': 'confirm',
      'callId': ?prompt?.callId,
    });
  }

  /// Decline the pending confirm — single or multi-item — with
  /// `{"kind":"cancel","callId":…}`; "Cancelar" is also a recognized negative.
  Future<void> cancelPending({String fallbackLabel = 'Cancelar'}) {
    final prompt = state.pendingConfirm;
    return send(prompt?.cancelText(fallbackLabel) ?? fallbackLabel, selection: {
      'kind': 'cancel',
      'callId': ?prompt?.callId,
    });
  }

  /// Approve a MULTI-ITEM confirm (owner-ai v2 §4): the legible label plus a
  /// structured selection naming exactly the checked item ids. The engine
  /// reads `_selection` before NL resolution, so partial approval is
  /// deterministic — never inferred from prose.
  Future<void> confirmPendingItems(List<String> itemIds, {String fallbackLabel = 'Confirmar'}) {
    final prompt = state.pendingConfirm;
    return send(prompt?.confirmText(fallbackLabel) ?? fallbackLabel, selection: {
      'kind': 'confirm_items',
      'callId': ?prompt?.callId,
      'itemIds': itemIds,
    });
  }

  /// The UI layer calls this after it has consumed [state.pendingCheckout].
  void checkoutConsumed() => state = state.copyWith(clearCheckout: true);

  /* --------------------------- event plumbing --------------------------- */

  void _applyEvent(SseEvent evt, _TurnBuffers buf, {required bool hasAssistantBubble}) {
    switch (evt.event) {
      case 'token':
        if (!hasAssistantBubble) break;
        buf.text.write((evt.data['text'] ?? '').toString());
        _replaceLast(state.messages.last.copyWith(text: buf.visibleText()));
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
        // v2 `actions` win when present ({label, send} — owner-ai v2 §5);
        // otherwise the legacy label-only shapes: {"payload":{"items":[…]}}
        // and its older variants.
        final actions = payload['actions'];
        final raw = payload['items'] ?? payload['quick_replies'] ?? data['quick_replies'];
        final replies = actions is List && actions.isNotEmpty
            ? [
                for (final a in actions)
                  if (a is Map) QuickReply.fromJson(Map<String, dynamic>.from(a)),
              ]
            : raw is List
                ? [for (final e in raw) QuickReply.text(e.toString())]
                : null;
        if (replies != null && hasAssistantBubble) {
          buf.quickReplies
            ..clear()
            ..addAll(replies);
          _replaceLast(state.messages.last.copyWith(quickReplies: List.of(buf.quickReplies)));
        }
        break;
      case 'attachment':
        // A document the engine hands over mid-turn (e.g. a cash report PDF):
        // rides the current assistant bubble as a standard attachment tile —
        // same chrome + signed-URL open path as inbox attachments.
        final attachment = ChatAttachment.fromJson(payload);
        if (attachment.url.isEmpty) break;
        if (hasAssistantBubble) {
          final last = state.messages.last;
          _replaceLast(last.copyWith(attachments: [...last.attachments, attachment]));
        } else {
          _appendAssistant(
              ChatMessage(role: 'assistant', text: '', attachments: [attachment]));
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
        final prompt = ConfirmPrompt.fromJson(payload);
        state = state.copyWith(pendingConfirm: prompt);
        // One confirmation, one question (WhatsApp ops §6.7 C1): the engine
        // also streams the summary as a token, but the pinned ConfirmCard
        // already asks it — drop it from the bubble, whichever frame came first.
        if (hasAssistantBubble && prompt.summary.trim().isNotEmpty) {
          buf.confirmSummary = prompt.summary.trim();
          _replaceLast(state.messages.last.copyWith(text: buf.visibleText()));
        }
        break;
      case 'menu':
        // Persistent owner menu (owner-ai v2 §3): session chrome, never a
        // bubble. The latest hint always wins — replace wholesale.
        state = state.copyWith(menu: ChatMenu.fromJson(payload));
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
      case 'receipt':
        // Printed-paper preview (chat-experience-design.md §7.3). The template
        // is looked up at render time from the session's `chat.receipts` trait
        // (hint's templateId, else the per-tool bindings fallback).
        final receipt = ReceiptInstance.fromHint(payload);
        if (receipt.draft) {
          // A ticket being built (§7.8): live pinned preview, updated in place —
          // never a stack of stale bubbles, never a message.
          state = state.copyWith(draftReceipt: receipt);
        } else {
          // The issued document: the draft (if any) resolved into it; drop the
          // pinned preview and drop it into the conversation.
          state = state.copyWith(clearDraftReceipt: true);
          if (hasAssistantBubble) {
            _replaceLast(state.messages.last.copyWith(receipt: receipt));
          } else {
            _appendAssistant(ChatMessage(role: 'assistant', text: '', receipt: receipt));
          }
        }
        break;
      case 'actions':
        // Action buttons that belong to the current bubble (owner-ai v3 §D3).
        // Primary path is inbox-carried actions; this handles a live hint too.
        final parsed = MessageAction.listFrom(payload['actions'] ?? payload['items']);
        if (parsed.isEmpty) break;
        if (hasAssistantBubble) {
          final last = state.messages.last;
          _replaceLast(last.copyWith(actions: [...last.actions, ...parsed]));
        } else {
          _appendAssistant(ChatMessage(role: 'assistant', text: '', actions: parsed));
        }
        break;
      case 'data_table':
        // Styled tabular result (owner-ai v3 §D4): rides the current assistant
        // bubble, or a bare bubble if the hint arrived on a tap channel.
        final table = DataTablePayload.fromJson(payload);
        if (hasAssistantBubble) {
          _replaceLast(state.messages.last.copyWith(dataTable: table));
        } else {
          _appendAssistant(ChatMessage(role: 'assistant', text: '', dataTable: table));
        }
        break;
      case 'data_card':
        final cardData = DataCardPayload.fromJson(payload);
        if (hasAssistantBubble) {
          _replaceLast(state.messages.last.copyWith(dataCard: cardData));
        } else {
          _appendAssistant(ChatMessage(role: 'assistant', text: '', dataCard: cardData));
        }
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
      // A new assistant bubble demotes every older one — stale quick replies
      // come off so chips only ever ride the latest turn (owner-ai v2 §5).
      state = state.copyWith(messages: [..._withoutQuickReplies(state.messages), m]);

  /// Strip quick replies from every message — used whenever a NEW bubble is
  /// about to be appended, so chips never linger on older turns.
  static List<ChatMessage> _withoutQuickReplies(List<ChatMessage> msgs) => [
        for (final m in msgs)
          m.quickReplies.isEmpty ? m : m.copyWith(quickReplies: const []),
      ];

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
    _maybeLock(e);
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

  /// A 401 while we hold an EXPIRED device pass means the short-TTL pass aged
  /// out mid-session (chat-experience-design.md §5.5) — hand off to the lock
  /// screen (or silent unlock). A 401 with a still-valid pass is a tenant
  /// sign-in requirement, not a lock condition.
  void _maybeLock(ChatApiException e) {
    if (e.status != 401) return;
    final v = _ref.read(verificationProvider);
    if (v.verified && isJwtExpired(v.devicePass)) {
      unawaited(_ref.read(verificationProvider.notifier).onAuthRejected());
    }
  }
}

/// Per-turn accumulators for the streamed assistant bubble.
class _TurnBuffers {
  final StringBuffer text = StringBuffer();
  final List<QuickReply> quickReplies = <QuickReply>[];
  final List<Citation> citations = <Citation>[];

  /// The `confirm` frame's question once this turn raised one (§6.7 C1).
  String? confirmSummary;

  /// The bubble text: the streamed prose minus the confirm question the card
  /// shows (and anything after it, e.g. an older engine's "(reply yes or no)"
  /// suffix). Lead-in prose before the question stays.
  String visibleText() => stripConfirmSummary(text.toString(), confirmSummary);
}

/// Removes the LAST occurrence of [summary] — and whatever follows it — from
/// [text]. Unchanged when there is no summary or the text never carried it.
String stripConfirmSummary(String text, String? summary) {
  if (summary == null || summary.isEmpty) return text;
  final at = text.lastIndexOf(summary);
  return at < 0 ? text : text.substring(0, at).trimRight();
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
