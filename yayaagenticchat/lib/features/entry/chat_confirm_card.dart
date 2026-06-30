import 'package:flutter/material.dart';

import '../../app/theme.dart';
import '../../config.dart';
import '../../l10n/app_localizations.dart';
import '../../models/entry_point_card.dart';

/// The "Chat with …" confirm card: logo, name, auth requirement, and an
/// "Add to my chats" action. Shared by the QR scanner (in a bottom sheet) and
/// the deep-link landing screen (chat-channel-design.md §11), so both on-ramps
/// present an entry point identically before a tile is persisted.
class ChatConfirmCard extends StatelessWidget {
  const ChatConfirmCard({super.key, required this.card, required this.onAdd});

  final EntryPointCard card;
  final VoidCallback onAdd;

  @override
  Widget build(BuildContext context) {
    final t = AppL10n.of(context);
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        CircleAvatar(
          radius: 28,
          backgroundColor: colorFromHex(card.accentColorHex),
          foregroundColor: Colors.white,
          child: ClipOval(
            child: Image.network(
              AppConfig.resolveUrl(card.logoUrl),
              width: 56,
              height: 56,
              fit: BoxFit.cover,
              errorBuilder: (_, _, _) =>
                  Text(card.displayName.isEmpty ? '?' : card.displayName[0]),
            ),
          ),
        ),
        const SizedBox(height: 12),
        Text(card.displayName, style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 4),
        Text(card.requiresAuth ? t.signInRequired : t.chatAsGuest),
        const SizedBox(height: 20),
        FilledButton(onPressed: onAdd, child: Text(t.addToMyChats)),
      ],
    );
  }
}
