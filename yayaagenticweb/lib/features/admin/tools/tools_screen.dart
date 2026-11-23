import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../app/live/live_resource.dart';
import '../../../app/live/resource_kind.dart';
import '../../../app/selected_tenant.dart';
import '../../../ui/data/live_async.dart';
import '../../../ui/data/relative_time.dart';
import '../../../ui/data/y_badge.dart';
import '../../../ui/data/y_page.dart';
import '../../../ui/data/y_states.dart';
import '../../../ui/data/y_table.dart';
import '../capabilities/capabilities_screen.dart' show toolsProvider;
import '../profiles/profiles_screen.dart' show capabilitiesProvider;
import '../shared/admin_shared.dart';
import 'tool_form.dart';

/// Latest version of every tool, with its version history.
final toolSummariesProvider = Provider<AsyncValue<List<ToolSummary>>>(
  (ref) => ref.watch(toolsProvider).whenData(summarizeTools),
);

/// Tool id → ids of the capabilities (latest versions) that list it.
final toolUsageProvider = Provider<Map<String, List<String>>>((ref) {
  final caps = ref.watch(capabilitiesProvider).valueOrNull ?? const [];
  final latest = <String, ({int version, List<String> tools})>{};
  for (final c in caps) {
    final cur = latest[c.id];
    if (cur == null || c.version > cur.version) latest[c.id] = (version: c.version, tools: c.tools);
  }
  final out = <String, List<String>>{};
  latest.forEach((capId, v) {
    for (final t in v.tools) {
      out.putIfAbsent(t, () => []).add(capId);
    }
  });
  return out;
});

class ToolsScreen extends ConsumerWidget {
  const ToolsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final params = GoRouterState.of(context).uri.queryParameters;
    // Old links and the palette's generic form: ?id= / ?new=1.
    if (params['id'] != null || params['new'] != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!context.mounted) return;
        context.go(params['id'] != null ? '/admin/tools/${Uri.encodeComponent(params['id']!)}' : '/admin/tools/new');
      });
    }
    if (ref.watch(currentTenantOrNull) == null) {
      return const TenantScopedEmptyState(resourceLabel: 'Tools');
    }
    final summaries = ref.watch(toolSummariesProvider);
    final usage = ref.watch(toolUsageProvider);
    final fresh = ref.watch(freshIdsProvider(AdminResourceKind.tool));

    void openNew() => context.go('/admin/tools/new');

    return YPage(
      title: 'Tools',
      subtitle: 'Actions a capability can take. HTTP tools call a service with explicit credential forwarding; Bean tools run inside the backend.',
      actions: [FilledButton.icon(onPressed: openNew, icon: const Icon(Icons.add, size: 18), label: const Text('New tool'))],
      child: LiveAsyncView<List<ToolSummary>>(
        value: summaries,
        what: 'tools',
        onRetry: () => ref.invalidate(toolsProvider),
        data: (context, list) => YTable<ToolSummary>(
          rows: list,
          noun: 'tools',
          idOf: (s) => s.latest.id,
          searchHint: 'Search by ID, method, path or bean',
          searchTextOf: (s) {
            final h = s.latest.handler;
            return '${s.latest.id} ${h.kind} ${h.beanName ?? ''} ${h.httpSpec?.method ?? ''} ${h.httpSpec?.urlTemplate ?? ''}';
          },
          highlightIds: fresh,
          onRowTap: (s) => context.go('/admin/tools/${Uri.encodeComponent(s.latest.id)}'),
          filters: [
            YFilter('HTTP', (s) => s.latest.handler.kind == 'HTTP'),
            YFilter('Bean', (s) => s.latest.handler.kind == 'BEAN'),
            YFilter('Forwards user token', (s) => s.latest.handler.httpSpec?.authForwarding == 'PRINCIPAL_TOKEN'),
            YFilter('Asks before running', (s) => s.latest.policy['confirmable'] == true),
            YFilter('Unused', (s) => (usage[s.latest.id] ?? const []).isEmpty),
          ],
          empty: YEmptyState(
            icon: Icons.build_outlined,
            title: 'No tools yet',
            message: 'Tools are the actions a capability can take — an HTTP call to your service, or a Java handler in the backend.',
            action: FilledButton.icon(onPressed: openNew, icon: const Icon(Icons.add, size: 18), label: const Text('Create your first tool')),
          ),
          columns: [
            YColumn(
              label: 'Tool',
              flex: 3,
              sortKey: (s) => s.latest.id,
              cell: (context, s) => YCellStack(
                primary: s.latest.id,
                mono: true,
                secondary: s.latest.handler.kind == 'HTTP'
                    ? s.latest.handler.httpSpec?.urlTemplate
                    : s.latest.handler.beanName,
              ),
            ),
            YColumn(
              label: 'Transport',
              flex: 2,
              sortKey: (s) => s.latest.handler.kind,
              cell: (context, s) => Wrap(spacing: 6, crossAxisAlignment: WrapCrossAlignment.center, children: [
                YBadge(s.latest.handler.kind == 'HTTP' ? 'HTTP' : 'Bean', tone: s.latest.handler.kind == 'HTTP' ? YTone.brand : YTone.success),
                if (s.latest.handler.httpSpec != null) YBadge(s.latest.handler.httpSpec!.method, mono: true),
              ]),
            ),
            YColumn(
              label: 'Credentials',
              flex: 2,
              cell: (context, s) => switch (s.latest.handler.httpSpec?.authForwarding) {
                'PRINCIPAL_TOKEN' => const YBadge('User’s token', tone: YTone.warning, icon: Icons.person_outline),
                'SERVICE_TOKEN' => const YBadge('Service token', tone: YTone.info, icon: Icons.vpn_key_outlined),
                null => const YBadge('In-process'),
                _ => const YBadge('None'),
              },
            ),
            YColumn(
              label: 'Behaviour',
              flex: 2,
              cell: (context, s) => Wrap(spacing: 4, runSpacing: 4, children: [
                if (s.latest.policy['confirmable'] == true) const YBadge('Confirms', tone: YTone.warning),
                if (s.latest.policy['clientInvocable'] == true) const YBadge('Client'),
                if (s.latest.policy['headless'] == true) const YBadge('Headless', tone: YTone.info),
                if (s.latest.policy['secretInput'] == true) const YBadge('Secret'),
              ]),
            ),
            YColumn(
              label: 'Version',
              width: 110,
              sortKey: (s) => s.latest.version,
              cell: (context, s) => YCellStack(
                primary: 'v${s.latest.version}',
                mono: true,
                secondary: s.versions.length == 1 ? null : '${s.versions.length} versions',
              ),
            ),
            YColumn(
              label: 'Used by',
              flex: 2,
              sortKey: (s) => (usage[s.latest.id] ?? const []).length,
              cell: (context, s) {
                final caps = usage[s.latest.id] ?? const [];
                return Text(
                  caps.isEmpty ? 'Not used yet' : caps.length == 1 ? caps.first : '${caps.length} capabilities',
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(color: caps.isEmpty ? Theme.of(context).colorScheme.onSurfaceVariant : null),
                );
              },
            ),
            YColumn(
              label: 'Updated',
              width: 120,
              sortKey: (s) => s.latest.createdAt ?? '',
              cell: (context, s) {
                final isFresh = fresh.contains(s.latest.id);
                return Text(
                  isFresh ? 'Just now' : formatRelativeIso(s.latest.createdAt),
                  style: TextStyle(
                    fontWeight: isFresh ? FontWeight.w600 : null,
                    color: isFresh ? Theme.of(context).colorScheme.primary : null,
                  ),
                );
              },
            ),
          ],
        ),
      ),
    );
  }
}
