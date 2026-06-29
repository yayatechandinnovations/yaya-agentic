import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../l10n/app_localizations.dart';
import '../../models/chat_message.dart';
import 'chat_controller.dart';

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
    final accent = colorFromHex(state.card?.accentColorHex);
    final title = state.card?.displayName ?? 'Chat';
    ref.listen(chatControllerProvider(widget.handle), (_, _) => _scrollToBottom());

    return Scaffold(
      appBar: AppBar(
        backgroundColor: accent,
        foregroundColor: Colors.white,
        title: Text(title),
        actions: [
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
          Expanded(child: _body(state, accent)),
          if (!state.needsSignIn) _composer(state, accent),
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
        onQuickReply: _send,
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

class _Bubble extends StatelessWidget {
  const _Bubble({required this.message, required this.accent, required this.onQuickReply});
  final ChatMessage message;
  final Color accent;
  final void Function(String) onQuickReply;

  @override
  Widget build(BuildContext context) {
    final isUser = message.isUser;
    final bg = isUser ? accent : Theme.of(context).colorScheme.surfaceContainerHighest;
    final fg = isUser ? Colors.white : Theme.of(context).colorScheme.onSurface;
    return Column(
      crossAxisAlignment: isUser ? CrossAxisAlignment.end : CrossAxisAlignment.start,
      children: [
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
