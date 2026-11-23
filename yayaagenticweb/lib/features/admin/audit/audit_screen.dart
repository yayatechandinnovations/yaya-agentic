import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/live/live_resource.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/audit.dart';
import '../../../ui/data/live_async.dart';
import '../../../ui/data/relative_time.dart';
import '../../../ui/data/y_badge.dart';
import '../../../ui/data/y_page.dart';
import '../../../ui/data/y_sheet.dart';
import '../../../ui/data/y_states.dart';
import '../../../ui/data/y_table.dart';
import '../../../ui/feedback/toast.dart';
import '../../../ui/tokens.dart';
import '../shared/admin_shared.dart';

class AuditFilter {
  const AuditFilter({this.decision, this.principal, this.toolId, this.page = 0, this.pageSize = 30});
  final String? decision;
  final String? principal;
  final String? toolId;
  final int page;
  final int pageSize;

  bool get isFiltered => decision != null || principal != null || toolId != null;

  /// Null clears a filter; omit an argument to keep it.
  AuditFilter copyWith({
    Object? decision = _keep,
    Object? principal = _keep,
    Object? toolId = _keep,
    int? page,
    int? pageSize,
  }) =>
      AuditFilter(
        decision: identical(decision, _keep) ? this.decision : decision as String?,
        principal: identical(principal, _keep) ? this.principal : principal as String?,
        toolId: identical(toolId, _keep) ? this.toolId : toolId as String?,
        page: page ?? this.page,
        pageSize: pageSize ?? this.pageSize,
      );
}

const _keep = Object();

final auditFilterProvider = StateProvider<AuditFilter>((_) => const AuditFilter());

final auditPageProvider = FutureProvider<AuthzAuditPage>((ref) async {
  liveRefresh(ref);
  final tenant = ref.watch(currentTenantOrNull);
  if (tenant == null) {
    return const AuthzAuditPage(items: [], page: 0, pageSize: 0, total: 0);
  }
  final api = await ref.watch(adminApiProvider.future);
  final filter = ref.watch(auditFilterProvider);
  return api.searchAuthzAudit(
    tenant: tenant,
    decision: filter.decision,
    principal: filter.principal,
    toolId: filter.toolId,
    page: filter.page,
    pageSize: filter.pageSize,
  );
});

/// Read-only: every Allow and Deny the Authorizer chain produced, with the
/// policy trace. Decision / principal / tool filter on the server (so paging
/// stays correct); the table's search narrows the current page.
class AuditScreen extends ConsumerStatefulWidget {
  const AuditScreen({super.key});

  @override
  ConsumerState<AuditScreen> createState() => _AuditScreenState();
}

class _AuditScreenState extends ConsumerState<AuditScreen> {
  final _principal = TextEditingController();
  final _tool = TextEditingController();

  @override
  void initState() {
    super.initState();
    final f = ref.read(auditFilterProvider);
    _principal.text = f.principal ?? '';
    _tool.text = f.toolId ?? '';
  }

  @override
  void dispose() {
    _principal.dispose();
    _tool.dispose();
    super.dispose();
  }

  void _set(AuditFilter f) => ref.read(auditFilterProvider.notifier).state = f;

  void _applyText() {
    final f = ref.read(auditFilterProvider);
    final p = _principal.text.trim();
    final t = _tool.text.trim();
    _set(f.copyWith(principal: p.isEmpty ? null : p, toolId: t.isEmpty ? null : t, page: 0));
  }

  void _clear() {
    _principal.clear();
    _tool.clear();
    _set(const AuditFilter());
  }

  void _open(AuthzAuditEntry e) {
    showYSheet<void>(context, label: 'Audit entry', builder: (_) => _AuditEntrySheet(entry: e));
  }

  @override
  Widget build(BuildContext context) {
    if (ref.watch(currentTenantOrNull) == null) {
      return const TenantScopedEmptyState(resourceLabel: 'AuthZ audit entries');
    }
    final filter = ref.watch(auditFilterProvider);
    final page = ref.watch(auditPageProvider);

    return YPage(
      title: 'AuthZ audit',
      subtitle: 'Every Allow and Deny from the authorization chain, with the reason shown to the user, the audit reason, and the policy trace.',
      actions: [
        OutlinedButton.icon(
          onPressed: () => ref.invalidate(auditPageProvider),
          icon: const Icon(Icons.refresh, size: 18),
          label: const Text('Refresh'),
        ),
      ],
      toolbar: _filters(context, filter),
      child: LiveAsyncView<AuthzAuditPage>(
        value: page,
        what: 'audit entries',
        onRetry: () => ref.invalidate(auditPageProvider),
        data: (context, data) => Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Expanded(
            child: YTable<AuthzAuditEntry>(
              rows: data.items,
              noun: 'entries',
              idOf: (e) => '${e.id}',
              searchHint: 'Search this page',
              searchTextOf: (e) =>
                  '${e.decision} ${e.toolId ?? ''} ${e.principal ?? ''} ${e.sessionId ?? ''} ${e.auditReason ?? ''} ${e.userReason ?? ''}',
              onRowTap: _open,
              empty: YEmptyState(
                icon: Icons.policy_outlined,
                title: filter.isFiltered ? 'No entries match these filters' : 'No authorization decisions yet',
                message: filter.isFiltered
                    ? 'Try a different decision, principal or tool.'
                    : 'Each tool call and knowledge read is checked by the Authorizer chain; its decisions appear here as conversations run.',
                action: filter.isFiltered ? OutlinedButton(onPressed: _clear, child: const Text('Clear filters')) : null,
              ),
              columns: [
                YColumn(
                  label: 'Decision',
                  width: 130,
                  sortKey: (e) => e.decision,
                  cell: (context, e) => e.decision == 'ALLOW'
                      ? const YBadge('Allow', tone: YTone.success, icon: Icons.check)
                      : e.decision == 'DENY'
                          ? const YBadge('Deny', tone: YTone.danger, icon: Icons.block)
                          : YBadge(e.decision),
                ),
                YColumn(
                  label: 'Tool',
                  flex: 2,
                  sortKey: (e) => e.toolId ?? '',
                  cell: (context, e) => Text(e.toolId ?? '—', overflow: TextOverflow.ellipsis, style: monoStyle(context)),
                ),
                YColumn(
                  label: 'Principal',
                  flex: 2,
                  sortKey: (e) => e.principal ?? '',
                  cell: (context, e) => Text(e.principal ?? 'Anonymous', overflow: TextOverflow.ellipsis, style: monoStyle(context)),
                ),
                YColumn(
                  label: 'Audit reason',
                  flex: 4,
                  cell: (context, e) => Text(
                    e.auditReason ?? 'No reason recorded',
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(color: e.auditReason == null ? Theme.of(context).colorScheme.onSurfaceVariant : null),
                  ),
                ),
                YColumn(
                  label: 'When',
                  width: 120,
                  sortKey: (e) => e.createdAt ?? '',
                  cell: (context, e) => Tooltip(message: e.createdAt ?? '', child: Text(formatRelativeIso(e.createdAt))),
                ),
              ],
            ),
          ),
          _pager(context, data, filter),
        ]),
      ),
    );
  }

  Widget _filters(BuildContext context, AuditFilter filter) {
    Widget decisionChip(String? value, String label) => ChoiceChip(
          label: Text(label),
          selected: filter.decision == value,
          onSelected: (_) => _set(filter.copyWith(decision: value, page: 0)),
        );
    Widget field(TextEditingController c, String hint, IconData icon) => ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 260, minWidth: 200),
          child: TextField(
            controller: c,
            style: monoStyle(context),
            textInputAction: TextInputAction.search,
            onSubmitted: (_) => _applyText(),
            decoration: InputDecoration(isDense: true, hintText: hint, prefixIcon: Icon(icon, size: 18)),
          ),
        );
    return Wrap(spacing: 8, runSpacing: 8, crossAxisAlignment: WrapCrossAlignment.center, children: [
      decisionChip(null, 'Any decision'),
      decisionChip('ALLOW', 'Allow'),
      decisionChip('DENY', 'Deny'),
      const SizedBox(width: 8),
      Semantics(label: 'Filter by principal subject', child: field(_principal, 'Principal subject, e.g. user-123', Icons.person_outline)),
      Semantics(label: 'Filter by tool ID', child: field(_tool, 'Tool ID, e.g. orders_lookup', Icons.build_outlined)),
      FilledButton.tonal(onPressed: _applyText, child: const Text('Apply')),
      if (filter.isFiltered) TextButton(onPressed: _clear, child: const Text('Clear filters')),
    ]);
  }

  Widget _pager(BuildContext context, AuthzAuditPage data, AuditFilter filter) {
    final scheme = Theme.of(context).colorScheme;
    final size = data.pageSize == 0 ? filter.pageSize : data.pageSize;
    final pages = data.total == 0 ? 1 : ((data.total + size - 1) ~/ size);
    final hasNext = (data.page + 1) * size < data.total;
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Row(children: [
        Text('${data.total} matching · page ${data.page + 1} of $pages',
            style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant)),
        const Spacer(),
        IconButton(
          tooltip: 'Previous page',
          onPressed: data.page == 0 ? null : () => _set(filter.copyWith(page: data.page - 1)),
          icon: const Icon(Icons.chevron_left),
        ),
        IconButton(
          tooltip: 'Next page',
          onPressed: hasNext ? () => _set(filter.copyWith(page: data.page + 1)) : null,
          icon: const Icon(Icons.chevron_right),
        ),
      ]),
    );
  }
}

class _AuditEntrySheet extends StatelessWidget {
  const _AuditEntrySheet({required this.entry});
  final AuthzAuditEntry entry;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final trace = const JsonEncoder.withIndent('  ').convert(entry.policyTrace);
    Widget row(String label, String? value, {bool mono = false}) => Padding(
          padding: const EdgeInsets.only(bottom: Gap.md),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(label, style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: scheme.onSurfaceVariant)),
            const SizedBox(height: 2),
            SelectableText(
              value ?? '—',
              style: mono ? monoStyle(context) : TextStyle(fontSize: 14, height: 1.45, color: scheme.onSurface),
            ),
          ]),
        );
    return YSheetScaffold(
      title: '${entry.decision == 'ALLOW' ? 'Allowed' : entry.decision == 'DENY' ? 'Denied' : entry.decision} · ${entry.toolId ?? 'no tool'}',
      subtitle: 'Entry #${entry.id}${entry.createdAt == null ? '' : ' · ${entry.createdAt}'}',
      body: ListView(
        padding: const EdgeInsets.fromLTRB(28, 20, 28, 28),
        children: [
          row('Principal', entry.principal, mono: true),
          row('Tool', entry.toolId, mono: true),
          row('Session', entry.sessionId, mono: true),
          row('Turn', entry.turnId, mono: true),
          row('Reason shown to the user', entry.userReason),
          row('Audit reason (logged only)', entry.auditReason),
          const SizedBox(height: Gap.sm),
          Row(children: [
            Expanded(
              child: Text('Policy trace',
                  style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: scheme.onSurface)),
            ),
            TextButton.icon(
              onPressed: () {
                Clipboard.setData(ClipboardData(text: trace));
                showToast(context, 'Policy trace copied');
              },
              icon: const Icon(Icons.copy, size: 16),
              label: const Text('Copy'),
            ),
          ]),
          const SizedBox(height: 6),
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: scheme.surfaceContainerLow,
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: scheme.outlineVariant),
            ),
            child: SelectableText(trace, style: monoStyle(context, size: 12.5)),
          ),
        ],
      ),
    );
  }
}
