import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../app/theme.dart';
import '../../config.dart';
import '../../l10n/app_localizations.dart';
import '../../models/chat_message.dart';
import '../../models/commerce/catalog_payload.dart';
import '../../models/commerce/product.dart';
import '../../widgets/brand_logo.dart';
import 'chat_controller.dart';
import 'commerce/cart_header_button.dart';
import 'commerce/storefront_card.dart';
import 'commerce/cart_sheet.dart';
import 'commerce/checkout_handler.dart';
import 'commerce/checkout_result_card.dart';
import 'commerce/confirm_card.dart';
import 'commerce/mini_cart_bar.dart';
import 'commerce/product_card_expanded.dart';
import 'commerce/product_carousel.dart';
import 'commerce/product_compact_card.dart';
import 'commerce/storefront_browse_sheet.dart';

class ChatScreen extends ConsumerStatefulWidget {
  const ChatScreen({super.key, required this.handle});
  final String handle;

  @override
  ConsumerState<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends ConsumerState<ChatScreen> {
  final TextEditingController _input = TextEditingController();
  final ScrollController _scroll = ScrollController();

  @override
  void dispose() {
    _input.dispose();
    _scroll.dispose();
    super.dispose();
  }

  void _send(String text) {
    if (text.trim().isEmpty) return;
    _input.clear();
    ref.read(chatControllerProvider(widget.handle).notifier).send(text);
    _scrollToBottom();
  }

  Future<void> _signInDialog(BuildContext context) async {
    final t = AppL10n.of(context);
    final c = TextEditingController();
    final token = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(t.signIn),
        content: TextField(
          controller: c,
          autofocus: true,
          decoration: InputDecoration(hintText: t.pasteToken),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: Text(t.cancel)),
          FilledButton(onPressed: () => Navigator.pop(ctx, c.text), child: Text(t.continueLabel)),
        ],
      ),
    );
    if (token != null && token.trim().isNotEmpty) {
      ref.read(chatControllerProvider(widget.handle).notifier).upgrade(token.trim());
    }
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scroll.hasClients) {
        _scroll.animateTo(_scroll.position.maxScrollExtent,
            duration: const Duration(milliseconds: 200), curve: Curves.easeOut);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(chatControllerProvider(widget.handle));
    final controller = ref.read(chatControllerProvider(widget.handle).notifier);
    final accent = colorFromHex(state.card?.accentColorHex);
    final card = state.card;
    final title = card?.displayName ?? 'Chat';
    ref.listen(chatControllerProvider(widget.handle), (_, _) => _scrollToBottom());

    // Fire the checkout hand-off exactly once when a fresh result arrives (open
    // the hosted page / mount the PSP sheet / confirm the order), then clear.
    ref.listen(
      chatControllerProvider(widget.handle).select((s) => s.pendingCheckout),
      (prev, next) {
        if (next == null) return;
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (!mounted) return;
          controller.checkoutConsumed();
          handleCheckout(context, ref, widget.handle, next);
        });
      },
    );

    return Scaffold(
      appBar: AppBar(
        backgroundColor: accent,
        foregroundColor: Colors.white,
        // Chat is usually reached via a stack-replacing go(), so there's no
        // implicit back arrow — give an explicit one that pops if it can, else
        // heads home to the directory.
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          tooltip: MaterialLocalizations.of(context).backButtonTooltip,
          onPressed: () => context.canPop() ? context.pop() : context.go('/'),
        ),
        title: card == null
            ? Text(title)
            : Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  BrandLogo(
                    url: AppConfig.resolveUrl(card.logoUrl),
                    name: card.displayName,
                    accent: accent,
                    radius: 16,
                  ),
                  const SizedBox(width: 10),
                  Flexible(child: Text(title, overflow: TextOverflow.ellipsis)),
                ],
              ),
        actions: [
          if (state.isStore) CartHeaderButton(handle: widget.handle, accent: accent),
          if (state.canUpgrade)
            TextButton.icon(
              onPressed: () => _signInDialog(context),
              icon: const Icon(Icons.login, color: Colors.white, size: 18),
              label: Text(AppL10n.of(context).signIn, style: const TextStyle(color: Colors.white)),
            ),
          if (state.authenticated)
            const Padding(
              padding: EdgeInsets.only(right: 12),
              child: Icon(Icons.verified_user, size: 18, color: Colors.white),
            ),
        ],
      ),
      body: Column(
        children: [
          if (state.error != null)
            MaterialBanner(
              backgroundColor: Theme.of(context).colorScheme.errorContainer,
              content: Text(state.error!),
              actions: [
                TextButton(
                  onPressed: () => ref.invalidate(chatControllerProvider(widget.handle)),
                  child: Text(AppL10n.of(context).retry),
                ),
              ],
            ),
          // A LIVE prior session (§7.2): let the user resume it or start fresh —
          // the app never auto-picks, and the model never invents this.
          if (state.resumeAvailable)
            MaterialBanner(
              content: Text(AppL10n.of(context).resumePrompt),
              actions: [
                TextButton(
                  onPressed: controller.startNewSession,
                  child: Text(AppL10n.of(context).startNewChat),
                ),
                FilledButton(
                  onPressed: controller.continueSession,
                  child: Text(AppL10n.of(context).continueChat),
                ),
              ],
            ),
          // Catalog-first entry: a tappable storefront card that opens the full
          // browse view (Pillar B §B.2). Shown for a store once the shopper is in.
          if (state.isStore && !state.needsSignIn)
            StorefrontCard(
              accent: accent,
              onBrowse: () => _openStorefront(accent),
            ),
          Expanded(child: _body(state, accent)),
          if (!state.needsSignIn) ...[
            if (state.pendingConfirm != null)
              ConfirmCard(
                prompt: state.pendingConfirm!,
                accent: accent,
                busy: state.sending,
                onConfirm: controller.confirmPending,
                onCancel: controller.cancelPending,
              ),
            if (state.isStore) MiniCartBar(handle: widget.handle, accent: accent),
            _composer(state, accent),
          ],
        ],
      ),
    );
  }

  Widget _body(ChatState state, Color accent) {
    if (state.loading) return const Center(child: CircularProgressIndicator());
    if (state.needsSignIn) return _SignInPrompt(handle: widget.handle);
    return ListView.builder(
      controller: _scroll,
      padding: const EdgeInsets.all(12),
      itemCount: state.messages.length,
      itemBuilder: (context, i) => _Bubble(
        message: state.messages[i],
        accent: accent,
        handle: widget.handle,
        onQuickReply: _send,
      ),
    );
  }

  /// Open the full storefront browse view over the live chat. Product taps and
  /// add-to-cart route through the same controller the chat uses. Pillar B §B.2.
  void _openStorefront(Color accent) {
    final controller = ref.read(chatControllerProvider(widget.handle).notifier);
    final messenger = ScaffoldMessenger.of(context);
    final t = AppL10n.of(context);
    Future<void> add(Product p, {String? variantId, int qty = 1}) async {
      final ok = await controller.addToCart(p.id, variantId: variantId, qty: qty);
      if (ok) messenger.showSnackBar(SnackBar(content: Text(t.addedToCart)));
    }
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (_) => StorefrontBrowseSheet(
        accent: accent,
        onSearch: (query, page) => controller.searchCatalog(query: query, page: page),
        onOpenProduct: (p) => showProductSheet(
          context, product: p, accent: accent,
          onAdd: (variantId, qty) => add(p, variantId: variantId, qty: qty),
        ),
        onAddProduct: (p) => add(p),
      ),
    );
  }

  Widget _composer(ChatState state, Color accent) {
    final enabled = state.sessionId != null && !state.sending;
    return SafeArea(
      top: false,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(8, 4, 8, 8),
        child: Row(
          children: [
            Expanded(
              child: TextField(
                controller: _input,
                enabled: enabled,
                minLines: 1,
                maxLines: 4,
                textInputAction: TextInputAction.send,
                onSubmitted: enabled ? _send : null,
                decoration: InputDecoration(
                  hintText: AppL10n.of(context).messageHint,
                  border: OutlineInputBorder(borderRadius: BorderRadius.circular(24)),
                  contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                ),
              ),
            ),
            const SizedBox(width: 8),
            IconButton.filled(
              style: IconButton.styleFrom(backgroundColor: accent),
              onPressed: enabled ? () => _send(_input.text) : null,
              icon: state.sending
                  ? const SizedBox(
                      width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.send),
            ),
          ],
        ),
      ),
    );
  }
}

/// Detect a `product_catalog` descriptor that leaked into assistant text and
/// parse it back into a [CatalogPayload]. Tolerates both the bare descriptor
/// ({items, facets, …}) and a wrapped {kind, payload} frame. Returns null for
/// ordinary prose. See Pillar B §B.1.3.
CatalogPayload? _salvageCatalogFromText(String text) {
  final trimmed = text.trim();
  if (!trimmed.startsWith('{') || !trimmed.endsWith('}')) return null;
  try {
    final decoded = jsonDecode(trimmed);
    if (decoded is! Map) return null;
    var map = decoded.cast<String, dynamic>();
    // Unwrap a {kind, payload} envelope if that's what leaked.
    if (map['kind'] == 'product_catalog' && map['payload'] is Map) {
      map = (map['payload'] as Map).cast<String, dynamic>();
    }
    final looksLikeCatalog = map['items'] is List &&
        (map.containsKey('facets') || map.containsKey('page') || map.containsKey('total'));
    if (!looksLikeCatalog) return null;
    return CatalogPayload.fromJson(map);
  } catch (_) {
    return null;   // not JSON, or not a catalog — leave it as ordinary text
  }
}

class _Bubble extends ConsumerWidget {
  const _Bubble({
    required this.message,
    required this.accent,
    required this.handle,
    required this.onQuickReply,
  });
  final ChatMessage message;
  final Color accent;
  final String handle;
  final void Function(String) onQuickReply;

  Future<void> _addProduct(BuildContext context, WidgetRef ref, Product p) async {
    final controller = ref.read(chatControllerProvider(handle).notifier);
    final t = AppL10n.of(context);
    final messenger = ScaffoldMessenger.of(context);
    // Products with options must be opened so the shopper picks a variant.
    if (p.hasVariants) {
      await showProductSheet(
        context,
        product: p,
        accent: accent,
        onAdd: (variantId, qty) async {
          final ok = await controller.addToCart(p.id, variantId: variantId, qty: qty);
          if (ok) messenger.showSnackBar(SnackBar(content: Text(t.addedToCart)));
        },
      );
      return;
    }
    final ok = await controller.addToCart(p.id);
    if (ok) messenger.showSnackBar(SnackBar(content: Text(t.addedToCart)));
  }

  void _openProduct(BuildContext context, WidgetRef ref, Product p) {
    final controller = ref.read(chatControllerProvider(handle).notifier);
    final messenger = ScaffoldMessenger.of(context);
    final t = AppL10n.of(context);
    showProductSheet(
      context,
      product: p,
      accent: accent,
      onAdd: (variantId, qty) async {
        final ok = await controller.addToCart(p.id, variantId: variantId, qty: qty);
        if (ok) messenger.showSnackBar(SnackBar(content: Text(t.addedToCart)));
      },
    );
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isUser = message.isUser;
    final bg = isUser ? accent : Theme.of(context).colorScheme.surfaceContainerHighest;
    final fg = isUser ? Colors.white : Theme.of(context).colorScheme.onSurface;

    // Defence in depth (Pillar B §B.1.3): if a commerce descriptor ever arrives
    // as assistant prose instead of a ui_hint frame, render it as a card rather
    // than dumping raw JSON. The real fix is transport, but this makes a
    // regression degrade to a rendered widget, never a wall of JSON.
    final salvagedCatalog =
        message.catalog == null && !isUser ? _salvageCatalogFromText(message.text) : null;
    final effectiveCatalog = message.catalog ?? salvagedCatalog;
    final hasText = salvagedCatalog == null &&
        (message.text.isNotEmpty || (message.streaming && !message.hasCommerce));

    return Column(
      crossAxisAlignment: isUser ? CrossAxisAlignment.end : CrossAxisAlignment.start,
      children: [
        // Attributed header for a system-participant message (§14).
        if (message.isSystem)
          Padding(
            padding: const EdgeInsets.only(left: 6, top: 4),
            child: Row(mainAxisSize: MainAxisSize.min, children: [
              Icon(Icons.hub_outlined, size: 14, color: Theme.of(context).colorScheme.primary),
              const SizedBox(width: 6),
              Text(message.senderDisplayName!,
                  style: Theme.of(context).textTheme.labelSmall?.copyWith(
                      color: Theme.of(context).colorScheme.primary, fontWeight: FontWeight.w600)),
            ]),
          ),
        if (hasText)
          Container(
            margin: const EdgeInsets.symmetric(vertical: 4),
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
            constraints: BoxConstraints(maxWidth: MediaQuery.of(context).size.width * 0.78),
            decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(16)),
            child: Text(
              message.text.isEmpty && message.streaming ? '…' : message.text,
              style: TextStyle(color: fg),
            ),
          ),

        // ---- media attachments on a system message (§10) ----
        if (message.hasAttachments)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Wrap(
              spacing: 8,
              runSpacing: 8,
              children: message.attachments.map((a) => _AttachmentView(attachment: a)).toList(),
            ),
          ),

        // ---- commerce attachments (engine-emitted chrome, never prose) ----
        if (effectiveCatalog != null)
          ProductCarousel(
            catalog: effectiveCatalog,
            accent: accent,
            onOpenProduct: (p) => _openProduct(context, ref, p),
            onAddProduct: (p) => _addProduct(context, ref, p),
            onLoadMore: (q, page) =>
                ref.read(chatControllerProvider(handle).notifier).loadMore(q, page),
          ),
        if (message.productCard != null)
          Align(
            alignment: Alignment.centerLeft,
            child: ProductCompactCard(
              product: message.productCard!,
              accent: accent,
              width: 200,
              onOpen: () => _openProduct(context, ref, message.productCard!),
              onAdd: () => _addProduct(context, ref, message.productCard!),
            ),
          ),
        if (message.checkoutResult != null)
          CheckoutResultCard(
            result: message.checkoutResult!,
            handle: handle,
            accent: accent,
          ),
        if (message.cartSnapshot != null && message.checkoutResult == null)
          _CartHintChip(handle: handle, accent: accent, itemCount: message.cartSnapshot!.itemCount),

        if (message.citations.isNotEmpty)
          Wrap(
            spacing: 6,
            children: message.citations
                .map((c) => Chip(
                      visualDensity: VisualDensity.compact,
                      avatar: const Icon(Icons.menu_book, size: 16),
                      label: Text(c.title, style: const TextStyle(fontSize: 12)),
                    ))
                .toList(),
          ),
        if (!isUser && message.quickReplies.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(top: 4, bottom: 8),
            child: Wrap(
              spacing: 8,
              children: message.quickReplies
                  .map((q) => ActionChip(label: Text(q), onPressed: () => onQuickReply(q)))
                  .toList(),
            ),
          ),
      ],
    );
  }
}

/// The inline "what just changed" cart confirmation after a chat-driven mutation
/// (§10.3). Ambient totals live on the mini-bar; this is the contextual hint.
class _CartHintChip extends StatelessWidget {
  const _CartHintChip({required this.handle, required this.accent, required this.itemCount});
  final String handle;
  final Color accent;
  final int itemCount;

  @override
  Widget build(BuildContext context) {
    final t = AppL10n.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: ActionChip(
        avatar: Icon(Icons.check_circle, size: 16, color: accent),
        label: Text('${t.cartUpdated} · ${t.itemsCount(itemCount)}'),
        onPressed: () => showCartSheet(context, handle, accent),
      ),
    );
  }
}

/// AUTH_REQUIRED entry points: interactive OIDC (PKCE) is the production path
/// (chat-channel-design.md §8.2); this v1 shell accepts a pasted token so the
/// flow is exercisable end-to-end before the OIDC plugin lands.
class _SignInPrompt extends ConsumerWidget {
  const _SignInPrompt({required this.handle});
  final String handle;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = AppL10n.of(context);
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.lock_outline, size: 56),
            const SizedBox(height: 16),
            Text(t.signInRequired, textAlign: TextAlign.center),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: () => _pasteToken(context, ref),
              child: Text(t.signIn),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _pasteToken(BuildContext context, WidgetRef ref) async {
    final t = AppL10n.of(context);
    final c = TextEditingController();
    final token = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(t.signIn),
        content: TextField(
          controller: c,
          autofocus: true,
          decoration: InputDecoration(hintText: t.pasteToken),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: Text(t.cancel)),
          FilledButton(onPressed: () => Navigator.pop(ctx, c.text), child: Text(t.continueLabel)),
        ],
      ),
    );
    if (token != null && token.trim().isNotEmpty) {
      ref.read(chatControllerProvider(handle).notifier).signInWithToken(token.trim());
    }
  }
}

/// Renders a system-message attachment (§10): images inline, everything else as
/// a tappable document chip that opens the short-lived signed URL.
class _AttachmentView extends StatelessWidget {
  const _AttachmentView({required this.attachment});

  final ChatAttachment attachment;

  Future<void> _open() async {
    final uri = Uri.parse(AppConfig.resolveUrl(attachment.url));
    if (await canLaunchUrl(uri)) {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (attachment.isImage) {
      return GestureDetector(
        onTap: _open,
        child: ClipRRect(
          borderRadius: BorderRadius.circular(12),
          child: Image.network(
            AppConfig.resolveUrl(attachment.url),
            width: 180,
            height: 180,
            fit: BoxFit.cover,
            errorBuilder: (ctx, err, stack) => _chip(context),
          ),
        ),
      );
    }
    return _chip(context);
  }

  Widget _chip(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return InkWell(
      onTap: _open,
      borderRadius: BorderRadius.circular(12),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        decoration: BoxDecoration(
          color: scheme.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: scheme.outlineVariant),
        ),
        child: Row(mainAxisSize: MainAxisSize.min, children: [
          Icon(Icons.description_outlined, size: 20, color: scheme.primary),
          const SizedBox(width: 8),
          ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 180),
            child: Text(
              attachment.filename ?? 'Document',
              overflow: TextOverflow.ellipsis,
              style: Theme.of(context).textTheme.bodyMedium,
            ),
          ),
        ]),
      ),
    );
  }
}
