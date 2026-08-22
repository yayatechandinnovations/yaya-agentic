import 'package:flutter/material.dart';

import '../../l10n/app_localizations.dart';
import '../../models/chat_menu.dart';

/// The docked owner-menu button (owner-ai-experience v2 §3): lives beside the
/// composer (thumb-reachable), carries an aggregate badge when any item has
/// pending work, and opens a bottom sheet of sections. Tapping an item closes
/// the sheet and sends the item's `send` text — the same path as a quick reply.
class OwnerMenuButton extends StatelessWidget {
  const OwnerMenuButton({
    super.key,
    required this.menu,
    required this.accent,
    required this.enabled,
    required this.onSelect,
  });

  final ChatMenu menu;
  final Color accent;
  final bool enabled;
  final void Function(String send) onSelect;

  @override
  Widget build(BuildContext context) {
    final total = menu.totalBadge;
    final button = IconButton(
      icon: Icon(Icons.apps, color: accent),
      tooltip: AppL10n.of(context).ownerMenu,
      onPressed: enabled ? () => _openSheet(context) : null,
    );
    if (total <= 0) return button;
    return Badge.count(
      count: total,
      offset: const Offset(-6, 4),
      child: button,
    );
  }

  void _openSheet(BuildContext context) {
    showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (ctx) => SafeArea(
        child: ConstrainedBox(
          constraints:
              BoxConstraints(maxHeight: MediaQuery.of(ctx).size.height * 0.7),
          child: ListView(
            shrinkWrap: true,
            padding: const EdgeInsets.only(bottom: 8),
            children: [
              for (final section in menu.sections) ...[
                if (section.title.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.fromLTRB(20, 12, 20, 4),
                    child: Text(
                      section.title,
                      style: Theme.of(ctx).textTheme.labelLarge?.copyWith(
                            color: Theme.of(ctx).colorScheme.onSurfaceVariant,
                            fontWeight: FontWeight.w600,
                          ),
                    ),
                  ),
                for (final item in section.items)
                  ListTile(
                    title: Text(item.label),
                    trailing: (item.badge ?? 0) > 0
                        ? Badge.count(
                            count: item.badge!,
                            backgroundColor: accent,
                            textColor: Colors.white,
                          )
                        : null,
                    onTap: () {
                      Navigator.pop(ctx);
                      if (item.send.trim().isNotEmpty) onSelect(item.send);
                    },
                  ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
