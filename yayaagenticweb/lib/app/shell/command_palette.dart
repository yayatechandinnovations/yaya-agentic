import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../features/admin/capabilities/capabilities_screen.dart' show toolsProvider;
import '../../features/admin/knowledge_sources/knowledge_sources_screen.dart' show knowledgeSourcesProvider;
import '../../features/admin/profiles/profiles_screen.dart' show profilesProvider, capabilitiesProvider, authBindingsProvider;
import '../../features/auth/application/auth_controller.dart';
import '../../ui/form/unsaved_guard.dart';
import '../../ui/theme.dart';
import '../../ui/tokens.dart';
import '../live/admin_feed.dart';
import 'nav_model.dart';

enum _Group { goTo, records, actions }

class _Item {
  _Item(this.group, this.label, this.run, {this.hint, this.keywords = const [], this.icon, this.mono = false});
  final _Group group;
  final String label;
  final String? hint;
  final List<String> keywords;
  final IconData? icon;
  final bool mono;
  final Future<void> Function(BuildContext context) run;

  int score(String q) {
    if (q.isEmpty) return 1;
    final l = label.toLowerCase();
    if (l == q) return 100;
    if (l.startsWith(q)) return 80;
    if (l.split(RegExp(r'[\s._-]')).any((w) => w.startsWith(q))) return 60;
    if (l.contains(q)) return 40;
    if ((hint ?? '').toLowerCase().contains(q)) return 20;
    if (keywords.any((k) => k.contains(q))) return 15;
    return 0;
  }
}

/// ⌘K / Ctrl+K: jump to any page, record or action from the keyboard.
Future<void> showCommandPalette(BuildContext context, {required bool platformAdmin}) {
  return showDialog<void>(
    context: context,
    barrierColor: Colors.black.withValues(alpha: 0.32),
    builder: (_) => _Palette(platformAdmin: platformAdmin),
  );
}

class _Palette extends ConsumerStatefulWidget {
  const _Palette({required this.platformAdmin});
  final bool platformAdmin;

  @override
  ConsumerState<_Palette> createState() => _PaletteState();
}

class _PaletteState extends ConsumerState<_Palette> {
  final _query = TextEditingController();
  final _scroll = ScrollController();
  int _active = 0;

  @override
  void dispose() {
    _query.dispose();
    _scroll.dispose();
    super.dispose();
  }

  Future<void> Function(BuildContext) _go(String location) => (ctx) async {
        final router = GoRouter.of(ctx);
        Navigator.of(ctx).pop();
        if (await UnsavedChanges.instance.confirmLeave(ctx)) router.go(location);
      };

  List<_Item> _items() {
    final items = <_Item>[];
    for (final m in flattenNav(visibleNavEntries(platformAdmin: widget.platformAdmin))) {
      items.add(_Item(_Group.goTo, m.item.label, _go(m.item.location),
          hint: m.group?.label, icon: m.item.icon, keywords: m.item.keywords));
    }
    // Lists hold every version; the palette offers each record once, at its latest.
    List<T> latest<T>(List<T> all, String Function(T) id, int Function(T) v) {
      final m = <String, T>{};
      for (final x in all) {
        final cur = m[id(x)];
        if (cur == null || v(x) > v(cur)) m[id(x)] = x;
      }
      return m.values.toList();
    }

    final profiles = latest(ref.watch(profilesProvider).valueOrNull ?? const [], (p) => p.id, (p) => p.version);
    for (final p in profiles) {
      items.add(_Item(_Group.records, p.id, _go('/admin/profiles/${Uri.encodeComponent(p.id)}'),
          hint: 'Profile · v${p.version} · ${p.displayName}', mono: true, icon: Icons.person_outline, keywords: [p.displayName.toLowerCase()]));
    }
    for (final t in latest(ref.watch(toolsProvider).valueOrNull ?? const [], (t) => t.id, (t) => t.version)) {
      items.add(_Item(_Group.records, t.id, _go('/admin/tools/${Uri.encodeComponent(t.id)}'),
          hint: 'Tool · v${t.version} · ${t.handler.kind}${t.handler.httpSpec != null ? ' ${t.handler.httpSpec!.method}' : ''}',
          mono: true, icon: Icons.build_outlined));
    }
    for (final c in latest(ref.watch(capabilitiesProvider).valueOrNull ?? const [], (c) => c.id, (c) => c.version)) {
      items.add(_Item(_Group.records, c.id, _go('/admin/capabilities?id=${Uri.encodeComponent(c.id)}'),
          hint: 'Capability · v${c.version} · ${c.label}', mono: true, icon: Icons.checklist_outlined, keywords: [c.label.toLowerCase()]));
    }
    for (final k in latest(ref.watch(knowledgeSourcesProvider).valueOrNull ?? const [], (k) => k.id, (k) => k.version)) {
      items.add(_Item(_Group.records, k.id, _go('/admin/knowledge-sources?id=${Uri.encodeComponent(k.id)}'),
          hint: 'Knowledge · ${k.name}', mono: true, icon: Icons.menu_book_outlined, keywords: [k.name.toLowerCase()]));
    }
    for (final b in ref.watch(authBindingsProvider).valueOrNull ?? const []) {
      items.add(_Item(_Group.records, b.id, _go('/admin/auth-bindings?id=${Uri.encodeComponent(b.id)}'),
          hint: 'Auth binding · ${b.authenticatorRef}', mono: true, icon: Icons.lock_outlined));
    }
    final mode = ref.watch(themeModeProvider);
    items.addAll([
      _Item(_Group.actions, 'Create tool', _go('/admin/tools/new'), icon: Icons.add, keywords: ['new tool']),
      _Item(_Group.actions, 'Create profile', _go('/admin/profiles/new'), icon: Icons.add, keywords: ['new profile']),
      _Item(_Group.actions, 'Create capability', _go('/admin/capabilities?new=1'), icon: Icons.add),
      _Item(_Group.actions, 'Add knowledge source', _go('/admin/knowledge-sources?new=1'), icon: Icons.add, keywords: ['upload', 'documents']),
      _Item(_Group.actions, 'Open Playground', _go('/playground'), icon: Icons.science_outlined, keywords: ['test', 'chat']),
      _Item(_Group.actions, 'Refresh all data', (ctx) async {
        Navigator.of(ctx).pop();
        ref.read(adminFeedProvider.notifier).refreshNow();
      }, icon: Icons.refresh, keywords: ['reload', 'sync']),
      _Item(_Group.actions, mode == ThemeMode.dark ? 'Switch to light theme' : 'Switch to dark theme', (ctx) async {
        Navigator.of(ctx).pop();
        await ref.read(themeModeProvider.notifier).set(mode == ThemeMode.dark ? ThemeMode.light : ThemeMode.dark);
      }, icon: Icons.contrast, keywords: ['theme', 'dark', 'light', 'mode']),
      _Item(_Group.actions, 'Switch workspace…', _go('/workspaces'), icon: Icons.workspaces_outline, keywords: ['tenant']),
      _Item(_Group.actions, 'Sign out', (ctx) async {
        Navigator.of(ctx).pop();
        if (await UnsavedChanges.instance.confirmLeave(ctx)) await ref.read(authProvider.notifier).logout();
      }, icon: Icons.logout, keywords: ['logout']),
    ]);
    return items;
  }

  List<(_Group, List<_Item>)> _results() {
    var q = _query.text.trim().toLowerCase();
    var only = <_Group>{};
    if (q.startsWith('>')) {
      only = {_Group.actions};
      q = q.substring(1).trim();
    }
    final scored = _items()
        .where((i) => only.isEmpty || only.contains(i.group))
        .map((i) => (i, i.score(q)))
        .where((p) => p.$2 > 0)
        .toList()
      ..sort((a, b) => b.$2.compareTo(a.$2));
    final out = <(_Group, List<_Item>)>[];
    for (final g in _Group.values) {
      var list = scored.where((p) => p.$1.group == g).map((p) => p.$1).toList();
      if (q.isEmpty && g == _Group.records) list = list.take(5).toList();
      if (list.length > 8) list = list.take(8).toList();
      if (list.isNotEmpty) out.add((g, list));
    }
    return out;
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final groups = _results();
    final flat = [for (final g in groups) ...g.$2];
    if (_active >= flat.length) _active = flat.isEmpty ? 0 : flat.length - 1;
    void run(_Item i) => i.run(context);
    var index = 0;
    return Align(
      alignment: const Alignment(0, -0.6),
      child: ConstrainedBox(
        constraints: BoxConstraints(maxWidth: 640, maxHeight: MediaQuery.of(context).size.height * 0.7),
        child: Material(
          color: scheme.surface,
          elevation: 16,
          borderRadius: BorderRadius.circular(14),
          clipBehavior: Clip.antiAlias,
          child: CallbackShortcuts(
            bindings: {
              const SingleActivator(LogicalKeyboardKey.arrowDown): () => setState(() => _active = (_active + 1).clamp(0, flat.isEmpty ? 0 : flat.length - 1)),
              const SingleActivator(LogicalKeyboardKey.arrowUp): () => setState(() => _active = (_active - 1).clamp(0, flat.isEmpty ? 0 : flat.length - 1)),
              const SingleActivator(LogicalKeyboardKey.enter): () {
                if (flat.isNotEmpty) run(flat[_active]);
              },
            },
            child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
                child: TextField(
                  controller: _query,
                  autofocus: true,
                  onChanged: (_) => setState(() => _active = 0),
                  style: const TextStyle(fontSize: 16),
                  decoration: InputDecoration(
                    hintText: 'Search pages, records and actions',
                    prefixIcon: const Icon(Icons.search),
                    border: InputBorder.none,
                    enabledBorder: InputBorder.none,
                    focusedBorder: InputBorder.none,
                    filled: false,
                    suffixIcon: Padding(
                      padding: const EdgeInsets.all(12),
                      child: Text('esc', style: monoStyle(context, size: 12, color: scheme.onSurfaceVariant)),
                    ),
                  ),
                ),
              ),
              const Divider(height: 1),
              Flexible(
                child: flat.isEmpty
                    ? Padding(
                        padding: const EdgeInsets.all(28),
                        child: Text('Nothing matches “${_query.text}”.',
                            textAlign: TextAlign.center, style: TextStyle(color: scheme.onSurfaceVariant)),
                      )
                    : ListView(
                        controller: _scroll,
                        shrinkWrap: true,
                        padding: const EdgeInsets.all(8),
                        children: [
                          for (final g in groups) ...[
                            Padding(
                              padding: const EdgeInsets.fromLTRB(10, 10, 10, 4),
                              child: Text(
                                switch (g.$1) { _Group.goTo => 'GO TO', _Group.records => 'RECORDS', _Group.actions => 'ACTIONS' },
                                style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, letterSpacing: 0.4, color: scheme.onSurfaceVariant),
                              ),
                            ),
                            for (final item in g.$2) _row(context, item, (index++) == _active, () => run(item)),
                          ],
                        ],
                      ),
              ),
              Container(
                color: scheme.surfaceContainerLow,
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                child: Text('↑↓ to move · ↵ to open · type “>” for actions only',
                    style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant)),
              ),
            ]),
          ),
        ),
      ),
    );
  }

  Widget _row(BuildContext context, _Item item, bool active, VoidCallback onTap) {
    final scheme = Theme.of(context).colorScheme;
    final status = StatusColors.of(context);
    return Material(
      color: active ? status.highlight : Colors.transparent,
      borderRadius: BorderRadius.circular(8),
      child: InkWell(
        borderRadius: BorderRadius.circular(8),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 9),
          child: Row(children: [
            Icon(item.icon ?? Icons.arrow_forward, size: 18, color: scheme.onSurfaceVariant),
            const SizedBox(width: 12),
            Flexible(
              child: Text(item.label,
                  overflow: TextOverflow.ellipsis,
                  style: item.mono
                      ? monoStyle(context, size: 13, weight: FontWeight.w500)
                      : TextStyle(fontSize: 14, fontWeight: FontWeight.w500, color: scheme.onSurface)),
            ),
            if (item.hint != null) ...[
              const SizedBox(width: 10),
              Expanded(
                child: Text(item.hint!, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant)),
              ),
            ] else
              const Spacer(),
          ]),
        ),
      ),
    );
  }
}
