import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/theme.dart';
import '../../config.dart';
import '../../data/chat_api.dart';
import '../../data/providers.dart';
import '../../l10n/app_localizations.dart';
import '../../models/directory_card.dart';
import '../../models/entry_point_card.dart';
import '../entry/chat_confirm_card.dart';

/// Discover — search/browse the public directory of published chats
/// (chat-public-directory-design.md §8). A third on-ramp alongside the QR
/// scanner and deep link: tapping a result flows through the same confirm/add
/// path, so nothing about session start changes here.
class DiscoverScreen extends ConsumerStatefulWidget {
  const DiscoverScreen({super.key});

  @override
  ConsumerState<DiscoverScreen> createState() => _DiscoverScreenState();
}

class _DiscoverScreenState extends ConsumerState<DiscoverScreen> {
  final _scroll = ScrollController();
  final _searchCtl = TextEditingController();
  Timer? _debounce;
  bool _resolving = false;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_onScroll);
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _scroll.dispose();
    _searchCtl.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (_scroll.position.pixels >= _scroll.position.maxScrollExtent - 320) {
      ref.read(discoverProvider.notifier).loadMore();
    }
  }

  void _onQueryChanged(String value) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 250),
        () => ref.read(discoverProvider.notifier).setQuery(value));
  }

  Future<void> _open(DirectoryCard card) async {
    if (_resolving) return;
    setState(() => _resolving = true);
    try {
      // Resolve the full public card (auth mechanism, OIDC, paused flag) before the
      // confirm step — the directory row is only a trimmed projection.
      final full = await ref.read(chatApiProvider).resolve(card.handle);
      if (!mounted) return;
      await _confirm(full);
    } on ChatApiException {
      if (mounted) _toast(AppL10n.of(context).chatNotFound);
    } finally {
      if (mounted) setState(() => _resolving = false);
    }
  }

  Future<void> _confirm(EntryPointCard card) async {
    final add = await showModalBottomSheet<bool>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: ChatConfirmCard(card: card, onAdd: () => Navigator.pop(ctx, true)),
        ),
      ),
    );
    if (add == true) {
      await ref.read(tilesProvider.notifier).addFromCard(card);
      if (mounted) context.go('/chat/${card.handle}');
    }
  }

  void _toast(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  Widget build(BuildContext context) {
    final t = AppL10n.of(context);
    final state = ref.watch(discoverProvider);

    return Scaffold(
      appBar: AppBar(title: Text(t.discoverTitle)),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 12, 12, 4),
            child: TextField(
              controller: _searchCtl,
              autofocus: true,
              textInputAction: TextInputAction.search,
              onChanged: _onQueryChanged,
              decoration: InputDecoration(
                prefixIcon: const Icon(Icons.search),
                hintText: t.discoverSearchHint,
                border: const OutlineInputBorder(),
                suffixIcon: _searchCtl.text.isEmpty
                    ? null
                    : IconButton(
                        icon: const Icon(Icons.clear),
                        onPressed: () {
                          _searchCtl.clear();
                          _onQueryChanged('');
                          setState(() {});
                        },
                      ),
              ),
            ),
          ),
          if (_resolving) const LinearProgressIndicator(),
          Expanded(child: _body(context, state)),
        ],
      ),
    );
  }

  Widget _body(BuildContext context, DiscoverState state) {
    final t = AppL10n.of(context);
    if (state.loading) return const Center(child: CircularProgressIndicator());
    if (state.error != null) {
      return _Message(
        icon: Icons.cloud_off,
        text: state.error!,
        action: t.retry,
        onAction: () => ref.read(discoverProvider.notifier).refresh(),
      );
    }
    if (state.items.isEmpty) {
      return _Message(
        icon: Icons.search_off,
        text: state.query.isEmpty ? t.discoverEmpty : t.discoverNoResults,
      );
    }
    return RefreshIndicator(
      onRefresh: () => ref.read(discoverProvider.notifier).refresh(),
      child: ListView.separated(
        controller: _scroll,
        padding: const EdgeInsets.all(12),
        itemCount: state.items.length + (state.hasMore ? 1 : 0),
        separatorBuilder: (_, _) => const SizedBox(height: 8),
        itemBuilder: (context, i) {
          if (i >= state.items.length) {
            return const Padding(
              padding: EdgeInsets.all(16),
              child: Center(child: CircularProgressIndicator()),
            );
          }
          return _ResultCard(card: state.items[i], onTap: () => _open(state.items[i]));
        },
      ),
    );
  }
}

class _ResultCard extends StatelessWidget {
  const _ResultCard({required this.card, required this.onTap});
  final DirectoryCard card;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final t = AppL10n.of(context);
    final accent = colorFromHex(card.accentColorHex);
    final subtitle = (card.tagline != null && card.tagline!.trim().isNotEmpty)
        ? card.tagline!
        : (card.requiresAuth ? t.signInRequired : t.guest);
    return Card(
      clipBehavior: Clip.antiAlias,
      child: ListTile(
        leading: _Logo(url: AppConfig.resolveUrl(card.logoUrl), name: card.displayName, accent: accent),
        title: Text(card.displayName, maxLines: 1, overflow: TextOverflow.ellipsis),
        subtitle: Text(subtitle, maxLines: 2, overflow: TextOverflow.ellipsis),
        trailing: const Icon(Icons.add_circle_outline),
        onTap: onTap,
      ),
    );
  }
}

class _Logo extends StatelessWidget {
  const _Logo({required this.url, required this.name, required this.accent});
  final String url;
  final String name;
  final Color accent;

  @override
  Widget build(BuildContext context) {
    final initials = name.isEmpty ? '?' : name.trim()[0].toUpperCase();
    return CircleAvatar(
      backgroundColor: accent,
      foregroundColor: Colors.white,
      child: ClipOval(
        child: Image.network(
          url,
          width: 40,
          height: 40,
          fit: BoxFit.cover,
          errorBuilder: (_, _, _) => Text(initials),
        ),
      ),
    );
  }
}

class _Message extends StatelessWidget {
  const _Message({required this.icon, required this.text, this.action, this.onAction});
  final IconData icon;
  final String text;
  final String? action;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 48),
            const SizedBox(height: 12),
            Text(text, textAlign: TextAlign.center),
            if (action != null && onAction != null) ...[
              const SizedBox(height: 20),
              FilledButton(onPressed: onAction, child: Text(action!)),
            ],
          ],
        ),
      ),
    );
  }
}
