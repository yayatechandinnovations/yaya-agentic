import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/theme.dart';
import '../../data/providers.dart';
import '../../l10n/app_localizations.dart';
import '../../models/entry_point_card.dart';
import 'chat_confirm_card.dart';

/// Landing screen for the `/e/{handle}` deep link the QR/share link encodes
/// (chat-channel-design.md §11). Reached when someone opens the link in a
/// browser, or — once native app/universal links are wired — when the system
/// hands the URL to the installed app. It resolves the handle to its public
/// branding and shows the same confirm card the scanner uses.
///
/// If the user already has this chat, we skip the card and open it directly:
/// re-clicking a link for an added chat should just take you there.
class EntryScreen extends ConsumerStatefulWidget {
  const EntryScreen({super.key, required this.handle});

  final String handle;

  @override
  ConsumerState<EntryScreen> createState() => _EntryScreenState();
}

class _EntryScreenState extends ConsumerState<EntryScreen> {
  Future<EntryPointCard>? _resolve;

  @override
  void initState() {
    super.initState();
    final alreadyAdded =
        ref.read(tilesProvider).any((t) => t.handle == widget.handle);
    if (alreadyAdded) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) context.go('/chat/${widget.handle}');
      });
    } else {
      _resolve = ref.read(chatApiProvider).resolve(widget.handle);
    }
  }

  Future<void> _add(EntryPointCard card) async {
    await ref.read(tilesProvider.notifier).addFromCard(card);
    if (mounted) context.go('/chat/${card.handle}');
  }

  @override
  Widget build(BuildContext context) {
    final t = AppL10n.of(context);
    return Scaffold(
      appBar: AppBar(title: Brand.gradientText('Yaya Chat', fontSize: 22)),
      body: _resolve == null
          ? const Center(child: CircularProgressIndicator())
          : FutureBuilder<EntryPointCard>(
              future: _resolve,
              builder: (context, snap) {
                if (snap.connectionState != ConnectionState.done) {
                  return const Center(child: CircularProgressIndicator());
                }
                if (snap.hasError || !snap.hasData) {
                  return _NotFound(message: t.chatNotFound, action: t.backToChats);
                }
                final card = snap.data!;
                return Center(
                  child: Padding(
                    padding: const EdgeInsets.all(24),
                    child: ChatConfirmCard(card: card, onAdd: () => _add(card)),
                  ),
                );
              },
            ),
    );
  }
}

class _NotFound extends StatelessWidget {
  const _NotFound({required this.message, required this.action});

  final String message;
  final String action;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.link_off, size: 48),
            const SizedBox(height: 12),
            Text(message, textAlign: TextAlign.center),
            const SizedBox(height: 20),
            FilledButton(
              onPressed: () => context.go('/'),
              child: Text(action),
            ),
          ],
        ),
      ),
    );
  }
}
