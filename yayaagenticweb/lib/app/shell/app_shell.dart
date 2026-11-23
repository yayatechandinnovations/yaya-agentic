import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../features/admin/tenants/tenants_screen.dart' show tenantsProvider;
import '../../features/auth/application/auth_controller.dart';
import '../../features/auth/models/auth_state.dart';
import '../../features/auth/models/operator.dart';
import '../../features/workspaces/application/workspaces_controller.dart';
import '../../features/workspaces/presentation/create_workspace_dialog.dart';
import '../../ui/data/y_sheet.dart';
import '../../ui/feedback/global_shortcut.dart';
import '../../ui/form/unsaved_guard.dart';
import '../../ui/theme.dart';
import '../../ui/tokens.dart';
import '../live/admin_feed.dart';
import '../selected_tenant.dart';
import 'command_palette.dart';
import 'nav_model.dart';
import 'sync_indicator.dart';

/// Every authenticated route: side nav (brand, workspace/tenant context,
/// search, groups) + top bar (breadcrumbs, sync state, operator menu).
class AppShell extends ConsumerStatefulWidget {
  const AppShell({super.key, required this.child});
  final Widget child;

  @override
  ConsumerState<AppShell> createState() => _AppShellState();
}

class _AppShellState extends ConsumerState<AppShell> {
  bool _paletteOpen = false;

  Future<void> _openPalette() async {
    if (_paletteOpen || !mounted) return;
    final auth = ref.read(authProvider);
    _paletteOpen = true;
    await showCommandPalette(
      context,
      platformAdmin: auth is AuthAuthenticated && auth.operator.platformAdmin,
    );
    _paletteOpen = false;
  }

  @override
  Widget build(BuildContext context) {
    final location = GoRouterState.of(context).uri.path;
    final width = MediaQuery.of(context).size.width;
    final wide = width > 1100;
    final auth = ref.watch(authProvider);

    // Mount the workspace→tenant bridge and the change feed once, here.
    ref.watch(workspaceToTenantBridgeProvider);
    ref.watch(adminFeedProvider);

    final platformAdmin =
        auth is AuthAuthenticated && auth.operator.platformAdmin;
    final entries = visibleNavEntries(platformAdmin: platformAdmin);

    // Global, not a Shortcuts widget: ⌘K has to work even when nothing in
    // the shell has focus (after clicking empty space, after a sheet closes).
    return GlobalShortcut(
      keyK: LogicalKeyboardKey.keyK,
      onTrigger: _openPalette,
      child: Scaffold(
        body: Row(
          children: [
            _SideNav(
              entries: entries,
              currentLocation: location,
              wide: wide,
              platformAdmin: platformAdmin,
              onSelect: (loc) {
                if (loc != location) GoRouter.of(context).go(loc);
              },
              onSearch: _openPalette,
            ),
            VerticalDivider(
              width: 1,
              color: Theme.of(context).colorScheme.outlineVariant,
            ),
            Expanded(
              child: Column(
                children: [
                  _TopBar(
                    entries: entries,
                    location: location,
                    operator: auth is AuthAuthenticated ? auth.operator : null,
                  ),
                  Expanded(child: widget.child),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _TopBar extends StatelessWidget {
  const _TopBar({
    required this.entries,
    required this.location,
    required this.operator,
  });
  final List<NavEntry> entries;
  final String location;
  final Operator? operator;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      height: 56,
      padding: const EdgeInsets.only(left: Gap.pagePadding, right: 8),
      decoration: BoxDecoration(
        color: scheme.surface,
        border: Border(bottom: BorderSide(color: scheme.outlineVariant)),
      ),
      child: Row(
        children: [
          Expanded(
            child: Breadcrumbs(entries: entries, location: location),
          ),
          const SyncIndicator(),
          if (operator != null) _OperatorMenu(operator: operator!),
        ],
      ),
    );
  }
}

/// "Agent design / Tools / sales_checkout" — derived from the nav and the path tail.
class Breadcrumbs extends StatelessWidget {
  const Breadcrumbs({super.key, required this.entries, required this.location});
  final List<NavEntry> entries;
  final String location;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final match = navMatch(entries, location);
    final crumbs = <(String, String?, bool)>[];
    if (match != null) {
      if (match.group != null) crumbs.add((match.group!.label, null, false));
      final tail = location
          .substring(match.item.location.length)
          .split('/')
          .where((s) => s.isNotEmpty)
          .toList();
      crumbs.add((
        match.item.label,
        tail.isEmpty ? null : match.item.location,
        false,
      ));
      for (var i = 0; i < tail.length; i++) {
        final seg = Uri.decodeComponent(tail[i]);
        crumbs.add((seg == 'new' ? 'New' : seg, null, seg != 'new'));
      }
    }
    if (crumbs.isEmpty) return const SizedBox.shrink();
    final children = <Widget>[];
    for (var i = 0; i < crumbs.length; i++) {
      final (label, link, mono) = crumbs[i];
      final last = i == crumbs.length - 1;
      final style = mono
          ? monoStyle(
              context,
              size: 13,
              weight: last ? FontWeight.w600 : FontWeight.w500,
              color: last ? scheme.onSurface : scheme.onSurfaceVariant,
            )
          : TextStyle(
              fontSize: 14,
              fontWeight: last ? FontWeight.w600 : FontWeight.w400,
              color: last ? scheme.onSurface : scheme.onSurfaceVariant,
            );
      if (i > 0) {
        children.add(
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 8),
            child: Text('/', style: TextStyle(color: scheme.onSurfaceVariant)),
          ),
        );
      }
      children.add(
        link == null
            ? Flexible(
                child: Text(
                  label,
                  overflow: TextOverflow.ellipsis,
                  style: style,
                ),
              )
            : InkWell(
                onTap: () => GoRouter.of(context).go(link),
                child: Text(
                  label,
                  style: style.copyWith(color: scheme.primary),
                ),
              ),
      );
    }
    return Semantics(
      label: 'Breadcrumb',
      child: Row(children: children),
    );
  }
}

class _SideNav extends StatefulWidget {
  const _SideNav({
    required this.entries,
    required this.currentLocation,
    required this.wide,
    required this.platformAdmin,
    required this.onSelect,
    required this.onSearch,
  });

  final List<NavEntry> entries;
  final String currentLocation;
  final bool wide;
  final bool platformAdmin;
  final void Function(String location) onSelect;
  final VoidCallback onSearch;

  @override
  State<_SideNav> createState() => _SideNavState();
}

class _SideNavState extends State<_SideNav> {
  final Set<String> _expanded = {};

  bool _selected(String loc) {
    final m = navMatch(widget.entries, widget.currentLocation);
    return m?.item.location == loc;
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final m = navMatch(widget.entries, widget.currentLocation);
    return SizedBox(
      width: widget.wide ? 264 : 76,
      child: Material(
        color: scheme.surfaceContainerLow,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(10, 14, 10, 14),
          children: [
            _brand(context),
            const SizedBox(height: 12),
            if (widget.wide) ...[
              const _ContextCard(),
              const SizedBox(height: 10),
              _searchButton(context),
              const SizedBox(height: 10),
            ] else ...[
              IconButton(
                tooltip: 'Search or jump to… (⌘K)',
                icon: const Icon(Icons.search),
                onPressed: widget.onSearch,
              ),
              const SizedBox(height: 6),
            ],
            for (final e in widget.entries)
              ..._entry(context, e, m?.group?.label),
          ],
        ),
      ),
    );
  }

  Widget _brand(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Row(
      mainAxisAlignment: widget.wide
          ? MainAxisAlignment.start
          : MainAxisAlignment.center,
      children: [
        Container(
          width: 30,
          height: 30,
          margin: EdgeInsets.only(left: widget.wide ? 8 : 0),
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: scheme.primary,
            borderRadius: BorderRadius.circular(8),
          ),
          child: Text(
            'Y',
            style: TextStyle(
              color: scheme.onPrimary,
              fontWeight: FontWeight.w700,
              fontSize: 15,
            ),
          ),
        ),
        if (widget.wide) ...[
          const SizedBox(width: 10),
          Text(
            'Yaya Agentic',
            style: TextStyle(
              fontWeight: FontWeight.w600,
              fontSize: 15,
              color: scheme.onSurface,
            ),
          ),
        ],
      ],
    );
  }

  Widget _searchButton(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final status = StatusColors.of(context);
    return OutlinedButton(
      style: OutlinedButton.styleFrom(
        alignment: Alignment.centerLeft,
        padding: const EdgeInsets.symmetric(horizontal: 12),
        backgroundColor: scheme.surface,
        side: BorderSide(color: status.inputBorder),
      ),
      onPressed: widget.onSearch,
      child: Row(
        children: [
          Icon(Icons.search, size: 18, color: scheme.onSurfaceVariant),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              'Search or jump to…',
              style: TextStyle(
                fontWeight: FontWeight.w400,
                color: scheme.onSurfaceVariant,
              ),
            ),
          ),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
            decoration: BoxDecoration(
              border: Border.all(color: scheme.outlineVariant),
              borderRadius: BorderRadius.circular(4),
            ),
            child: Text(
              '⌘K',
              style: monoStyle(
                context,
                size: 11,
                color: scheme.onSurfaceVariant,
              ),
            ),
          ),
        ],
      ),
    );
  }

  List<Widget> _entry(BuildContext context, NavEntry e, String? activeGroup) {
    if (e is NavItem) {
      return [
        _tile(
          context,
          e.label,
          e.icon,
          e.selectedIcon,
          _selected(e.location),
          0,
          () => widget.onSelect(e.location),
        ),
      ];
    }
    final g = e as NavGroup;
    final hasSelected = activeGroup == g.label;
    final open = _expanded.contains(g.label) || hasSelected;
    if (!widget.wide) {
      return [
        if (g != widget.entries.first)
          Divider(
            height: 12,
            indent: 12,
            endIndent: 12,
            color: Theme.of(context).colorScheme.outlineVariant,
          ),
        for (final c in g.children)
          _tile(
            context,
            c.label,
            c.icon,
            c.selectedIcon,
            _selected(c.location),
            0,
            () => widget.onSelect(c.location),
          ),
      ];
    }
    return [
      _tile(
        context,
        g.label,
        g.icon,
        g.selectedIcon,
        hasSelected && !open,
        0,
        () => setState(
          () => _expanded.contains(g.label)
              ? _expanded.remove(g.label)
              : _expanded.add(g.label),
        ),
        trailing: Icon(open ? Icons.expand_less : Icons.expand_more, size: 20),
        bold: true,
        expanded: open,
      ),
      if (open)
        for (final c in g.children)
          _tile(
            context,
            c.label,
            c.icon,
            c.selectedIcon,
            _selected(c.location),
            1,
            () => widget.onSelect(c.location),
          ),
    ];
  }

  Widget _tile(
    BuildContext context,
    String label,
    IconData icon,
    IconData selectedIcon,
    bool selected,
    int indent,
    VoidCallback onTap, {
    Widget? trailing,
    bool bold = false,
    bool? expanded,
  }) {
    final scheme = Theme.of(context).colorScheme;
    final bg = selected ? scheme.secondaryContainer : Colors.transparent;
    final fg = selected
        ? scheme.onSecondaryContainer
        : (bold ? scheme.onSurface : scheme.onSurfaceVariant);
    final iconW = Icon(selected ? selectedIcon : icon, color: fg, size: 20);
    if (!widget.wide) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 2),
        child: Tooltip(
          message: label,
          child: Semantics(
            selected: selected,
            button: true,
            label: label,
            child: InkWell(
              borderRadius: BorderRadius.circular(12),
              onTap: onTap,
              child: Container(
                height: 44,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: bg,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: iconW,
              ),
            ),
          ),
        ),
      );
    }
    return Padding(
      padding: EdgeInsets.only(left: indent * 14.0, top: 1, bottom: 1),
      child: Semantics(
        selected: selected,
        expanded: expanded,
        button: true,
        child: InkWell(
          borderRadius: BorderRadius.circular(8),
          onTap: onTap,
          child: Container(
            constraints: const BoxConstraints(minHeight: 40),
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            decoration: BoxDecoration(
              color: bg,
              borderRadius: BorderRadius.circular(8),
            ),
            child: Row(
              children: [
                iconW,
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    label,
                    style: TextStyle(
                      fontSize: 14,
                      color: fg,
                      fontWeight: selected || bold
                          ? FontWeight.w600
                          : FontWeight.w500,
                    ),
                  ),
                ),
                if (trailing != null)
                  IconTheme(
                    data: IconThemeData(color: scheme.onSurfaceVariant),
                    child: trailing,
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Workspace + tenant the console is acting on, visible to every operator.
/// Workspaces switch here; platform admins can also point at any tenant.
class _ContextCard extends ConsumerWidget {
  const _ContextCard();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scheme = Theme.of(context).colorScheme;
    final auth = ref.watch(authProvider);
    final platformAdmin =
        auth is AuthAuthenticated && auth.operator.platformAdmin;
    final workspaces = ref.watch(myWorkspacesProvider).valueOrNull ?? const [];
    final current = ref.watch(currentMembershipProvider);
    final tenantId = ref.watch(currentTenantOrNull);
    final tenant = ref.watch(currentTenantProvider);
    final tenants = platformAdmin
        ? (ref.watch(tenantsProvider).valueOrNull ?? const [])
        : const [];
    final active = workspaces
        .where((m) => m.workspace.status != 'ARCHIVED')
        .toList();

    return PopupMenuButton<Future<void> Function()>(
      tooltip: 'Switch workspace or tenant',
      position: PopupMenuPosition.under,
      constraints: const BoxConstraints(minWidth: 280, maxWidth: 360),
      onSelected: (action) => action(),
      itemBuilder: (ctx) => [
        if (active.isNotEmpty)
          const PopupMenuItem(
            enabled: false,
            height: 32,
            child: Text(
              'WORKSPACES',
              style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
            ),
          ),
        for (final m in active)
          PopupMenuItem(
            value: () async {
              if (await UnsavedChanges.instance.confirmLeave(context)) {
                await ref
                    .read(currentWorkspaceProvider.notifier)
                    .switchTo(m.workspace.code);
              }
            },
            child: Row(
              children: [
                Icon(
                  m.workspace.code == current?.workspace.code
                      ? Icons.check
                      : Icons.workspaces_outline,
                  size: 18,
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    m.workspace.displayName,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                Text(
                  m.role.toLowerCase(),
                  style: TextStyle(
                    fontSize: 12,
                    color: scheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
        if (platformAdmin && tenants.isNotEmpty) ...[
          const PopupMenuDivider(),
          const PopupMenuItem(
            enabled: false,
            height: 32,
            child: Text(
              'TENANT (PLATFORM)',
              style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
            ),
          ),
          for (final t in tenants.where((t) => t.status != 'ARCHIVED'))
            PopupMenuItem(
              value: () async {
                if (await UnsavedChanges.instance.confirmLeave(context)) {
                  await ref.read(selectedTenantProvider.notifier).select(t.id);
                }
              },
              child: Row(
                children: [
                  Icon(
                    t.id == tenantId ? Icons.check : Icons.apartment_outlined,
                    size: 18,
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(t.displayName, overflow: TextOverflow.ellipsis),
                  ),
                  Text(
                    t.id,
                    style: monoStyle(
                      context,
                      size: 12,
                      color: scheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ),
        ],
        const PopupMenuDivider(),
        PopupMenuItem(
          value: () async => GoRouter.of(context).go('/workspaces'),
          child: const Text('All workspaces'),
        ),
        PopupMenuItem(
          value: () async {
            final code = await showYSheet<String>(
              context,
              label: 'New workspace',
              builder: (_) => const CreateWorkspaceDialog(asSheet: true),
            );
            if (code != null)
              await ref.read(currentWorkspaceProvider.notifier).switchTo(code);
          },
          child: const Text('New workspace…'),
        ),
      ],
      child: Container(
        padding: const EdgeInsets.fromLTRB(12, 10, 8, 10),
        decoration: BoxDecoration(
          color: scheme.surface,
          border: Border.all(color: scheme.outlineVariant),
          borderRadius: BorderRadius.circular(10),
        ),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Workspace · Tenant',
                    style: TextStyle(
                      fontSize: 12,
                      color: scheme.onSurfaceVariant,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    current?.workspace.displayName ??
                        tenant?.displayName ??
                        (tenantId ?? 'Pick a workspace'),
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w600,
                      color: scheme.onSurface,
                    ),
                  ),
                  if (tenantId != null)
                    Text(
                      tenantId,
                      overflow: TextOverflow.ellipsis,
                      style: monoStyle(
                        context,
                        size: 12,
                        color: scheme.onSurfaceVariant,
                      ),
                    ),
                ],
              ),
            ),
            Icon(Icons.unfold_more, size: 18, color: scheme.onSurfaceVariant),
          ],
        ),
      ),
    );
  }
}

class _OperatorMenu extends ConsumerWidget {
  const _OperatorMenu({required this.operator});
  final Operator operator;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scheme = Theme.of(context).colorScheme;
    final mode = ref.watch(themeModeProvider);
    final initials = operator.displayName.trim().isEmpty
        ? '?'
        : operator.displayName
              .trim()
              .split(RegExp(r'\s+'))
              .take(2)
              .map((w) => w[0].toUpperCase())
              .join();
    return PopupMenuButton<String>(
      tooltip: 'Account and theme',
      position: PopupMenuPosition.under,
      onSelected: (v) async {
        switch (v) {
          case 'signout':
            if (await UnsavedChanges.instance.confirmLeave(context))
              await ref.read(authProvider.notifier).logout();
          case 'light':
            await ref.read(themeModeProvider.notifier).set(ThemeMode.light);
          case 'dark':
            await ref.read(themeModeProvider.notifier).set(ThemeMode.dark);
          case 'system':
            await ref.read(themeModeProvider.notifier).set(ThemeMode.system);
        }
      },
      itemBuilder: (_) => [
        PopupMenuItem(
          enabled: false,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                operator.displayName,
                style: TextStyle(
                  fontWeight: FontWeight.w600,
                  color: scheme.onSurface,
                ),
              ),
              if (operator.source.isNotEmpty)
                Text(
                  'via ${operator.source}',
                  style: TextStyle(
                    fontSize: 12,
                    color: scheme.onSurfaceVariant,
                  ),
                ),
            ],
          ),
        ),
        const PopupMenuDivider(),
        for (final (value, label, m) in [
          ('system', 'Match system theme', ThemeMode.system),
          ('light', 'Light theme', ThemeMode.light),
          ('dark', 'Dark theme', ThemeMode.dark),
        ])
          CheckedPopupMenuItem(
            value: value,
            checked: mode == m,
            child: Text(label),
          ),
        const PopupMenuDivider(),
        const PopupMenuItem(
          value: 'signout',
          child: Row(
            children: [
              Icon(Icons.logout, size: 18),
              SizedBox(width: 10),
              Text('Sign out'),
            ],
          ),
        ),
      ],
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            CircleAvatar(
              radius: 16,
              backgroundColor: scheme.primaryContainer,
              child: Text(
                initials,
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                  color: scheme.onPrimaryContainer,
                ),
              ),
            ),
            const SizedBox(width: 8),
            ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 160),
              child: Text(
                operator.displayName,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(fontSize: 14, color: scheme.onSurface),
              ),
            ),
            Icon(Icons.arrow_drop_down, color: scheme.onSurfaceVariant),
          ],
        ),
      ),
    );
  }
}
