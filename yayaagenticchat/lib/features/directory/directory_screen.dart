import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/theme.dart';
import '../../config.dart';
import '../../data/providers.dart';
import '../../l10n/app_localizations.dart';
import '../../models/tile.dart';
import '../../widgets/brand_logo.dart';

/// The home screen — the user's directory of `LOGO | NAME` tiles
/// (chat-channel-design.md §11).
class DirectoryScreen extends ConsumerWidget {
  const DirectoryScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tiles = ref.watch(tilesProvider);
    final verified = ref.watch(verificationProvider).verified;
    return Scaffold(
      appBar: AppBar(
        title: Brand.gradientText('Yaya Chat', fontSize: 22),
        actions: [
          IconButton(
            icon: const Icon(Icons.search),
            tooltip: AppL10n.of(context).discoverTitle,
            onPressed: () => context.push('/discover'),
          ),
          if (verified)
            IconButton(
              icon: const Icon(Icons.person_outline),
              tooltip: AppL10n.of(context).account,
              onPressed: () => context.push('/account'),
            ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => context.push('/scan'),
        icon: const Icon(Icons.qr_code_scanner),
        label: Text(AppL10n.of(context).add),
      ),
      body: tiles.isEmpty
          ? const _EmptyState()
          : ListView.separated(
              padding: const EdgeInsets.all(12),
              itemCount: tiles.length,
              separatorBuilder: (_, _) => const SizedBox(height: 8),
              itemBuilder: (context, i) => _TileCard(tile: tiles[i]),
            ),
    );
  }
}

class _EmptyState extends StatelessWidget {
  const _EmptyState();

  @override
  Widget build(BuildContext context) {
    final t = AppL10n.of(context);
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Brand.wordmark(height: 200),
            const SizedBox(height: 8),
            Text(t.noChatsYet, style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            Text(t.noChatsBlurb, textAlign: TextAlign.center),
          ],
        ),
      ),
    );
  }
}

class _TileCard extends ConsumerWidget {
  const _TileCard({required this.tile});
  final Tile tile;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final accent = colorFromHex(tile.accentColorHex);
    return Card(
      clipBehavior: Clip.antiAlias,
      child: ListTile(
        leading: BrandLogo(url: AppConfig.resolveUrl(tile.logoUrl), name: tile.displayName, accent: accent),
        title: Text(tile.displayName, maxLines: 1, overflow: TextOverflow.ellipsis),
        subtitle: Text(tile.authMode == 'AUTH_REQUIRED'
            ? AppL10n.of(context).signInRequired
            : AppL10n.of(context).guest),
        trailing: const Icon(Icons.chevron_right),
        onTap: () => context.push('/chat/${tile.handle}'),
        onLongPress: () => _confirmRemove(context, ref),
      ),
    );
  }

  void _confirmRemove(BuildContext context, WidgetRef ref) {
    showModalBottomSheet(
      context: context,
      builder: (ctx) => SafeArea(
        child: Wrap(children: [
          ListTile(
            leading: const Icon(Icons.delete_outline),
            title: Text(AppL10n.of(context).remove(tile.displayName)),
            onTap: () {
              ref.read(tilesProvider.notifier).remove(tile.handle);
              Navigator.pop(ctx);
            },
          ),
        ]),
      ),
    );
  }
}

