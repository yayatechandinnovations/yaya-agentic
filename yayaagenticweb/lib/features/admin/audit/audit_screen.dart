import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../models/admin/audit.dart';
import '../shared/admin_shared.dart';

class AuditFilter {
  const AuditFilter({this.decision, this.principal, this.toolId, this.page = 0, this.pageSize = 30});
  final String? decision;
  final String? principal;
  final String? toolId;
  final int page;
  final int pageSize;

  AuditFilter copyWith({String? decision, String? principal, String? toolId, int? page, int? pageSize}) =>
      AuditFilter(
        decision: decision ?? this.decision,
        principal: principal ?? this.principal,
        toolId: toolId ?? this.toolId,
        page: page ?? this.page,
        pageSize: pageSize ?? this.pageSize,
      );
}

final auditFilterProvider = StateProvider<AuditFilter>((_) => const AuditFilter());

final auditPageProvider = FutureProvider<AuthzAuditPage>((ref) async {
  final api = await ref.watch(adminApiProvider.future);
  final filter = ref.watch(auditFilterProvider);
  return api.searchAuthzAudit(
    decision: filter.decision,
    principal: filter.principal,
    toolId: filter.toolId,
    page: filter.page,
    pageSize: filter.pageSize,
  );
});

class AuditScreen extends ConsumerWidget {
  const AuditScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final filter = ref.watch(auditFilterProvider);
    final page = ref.watch(auditPageProvider);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 24, 24, 0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('AuthZ audit', style: Theme.of(context).textTheme.headlineSmall),
              Text('Every Allow + Deny lands here, with the policy trace inline.',
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      color: Theme.of(context).colorScheme.outline)),
              const SizedBox(height: 12),
              Row(
                children: [
                  SizedBox(
                    width: 160,
                    child: DropdownButtonFormField<String?>(
                      initialValue: filter.decision,
                      decoration: const InputDecoration(labelText: 'decision'),
                      items: const [
                        DropdownMenuItem(value: null, child: Text('— any —')),
                        DropdownMenuItem(value: 'ALLOW', child: Text('ALLOW')),
                        DropdownMenuItem(value: 'DENY', child: Text('DENY')),
                      ],
                      onChanged: (v) {
                        ref.read(auditFilterProvider.notifier).state =
                            filter.copyWith(decision: v, page: 0);
                      },
                    ),
                  ),
                  const SizedBox(width: 12),
                  SizedBox(
                    width: 240,
                    child: TextField(
                      decoration: const InputDecoration(labelText: 'principal subject'),
                      onSubmitted: (v) {
                        ref.read(auditFilterProvider.notifier).state =
                            filter.copyWith(principal: v.isEmpty ? null : v, page: 0);
                      },
                    ),
                  ),
                  const SizedBox(width: 12),
                  SizedBox(
                    width: 240,
                    child: TextField(
                      decoration: const InputDecoration(labelText: 'tool id'),
                      onSubmitted: (v) {
                        ref.read(auditFilterProvider.notifier).state =
                            filter.copyWith(toolId: v.isEmpty ? null : v, page: 0);
                      },
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        Expanded(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 24),
            child: page.when(
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (e, _) => Text(formatError(e),
                  style: TextStyle(color: Theme.of(context).colorScheme.error)),
              data: (data) {
                if (data.items.isEmpty) {
                  return const Center(child: Text('No audit entries match these filters.'));
                }
                return Column(
                  children: [
                    Expanded(
                      child: ListView.separated(
                        itemCount: data.items.length,
                        separatorBuilder: (_, __) => const SizedBox(height: 6),
                        itemBuilder: (_, i) => _AuditTile(entry: data.items[i]),
                      ),
                    ),
                    const Divider(),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text('${data.total} matching · page ${data.page + 1}',
                            style: Theme.of(context).textTheme.bodySmall),
                        Row(children: [
                          IconButton(
                            onPressed: data.page == 0
                                ? null
                                : () {
                                    ref.read(auditFilterProvider.notifier).state =
                                        filter.copyWith(page: data.page - 1);
                                  },
                            icon: const Icon(Icons.chevron_left),
                          ),
                          IconButton(
                            onPressed: (data.page + 1) * data.pageSize >= data.total
                                ? null
                                : () {
                                    ref.read(auditFilterProvider.notifier).state =
                                        filter.copyWith(page: data.page + 1);
                                  },
                            icon: const Icon(Icons.chevron_right),
                          ),
                        ]),
                      ],
                    ),
                  ],
                );
              },
            ),
          ),
        ),
      ],
    );
  }
}

class _AuditTile extends StatelessWidget {
  const _AuditTile({required this.entry});
  final AuthzAuditEntry entry;

  @override
  Widget build(BuildContext context) {
    final isAllow = entry.decision == 'ALLOW';
    final color = isAllow
        ? Theme.of(context).colorScheme.primaryContainer
        : Theme.of(context).colorScheme.errorContainer;
    return Card(
      child: ExpansionTile(
        leading: CircleAvatar(
          backgroundColor: color,
          child: Text(isAllow ? 'A' : 'D'),
        ),
        title: Text(
          '${entry.decision}  ·  ${entry.toolId ?? "—"}  ·  ${entry.principal ?? "—"}',
          style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
        ),
        subtitle: Text(
          entry.auditReason ?? '(no reason)',
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (entry.userReason != null) _row(context, 'user-safe', entry.userReason!),
                if (entry.auditReason != null) _row(context, 'audit', entry.auditReason!),
                if (entry.sessionId != null) _row(context, 'session', entry.sessionId!),
                const SizedBox(height: 8),
                Text('policy trace',
                    style: Theme.of(context).textTheme.labelMedium),
                const SizedBox(height: 4),
                SelectableText(
                  const JsonEncoder.withIndent('  ').convert(entry.policyTrace),
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _row(BuildContext context, String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(width: 88, child: Text(label, style: Theme.of(context).textTheme.labelMedium)),
          Expanded(child: SelectableText(value, style: const TextStyle(fontFamily: 'monospace', fontSize: 12))),
        ],
      ),
    );
  }
}
