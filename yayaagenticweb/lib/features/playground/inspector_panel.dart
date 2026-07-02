import 'package:flutter/material.dart';

import '../../models/inspector_snapshot.dart';

/// Shared right-side diagnostics panel for the polled playgrounds (WhatsApp and
/// chat). Surfaces the AUTH FLOW (binding → resolver call(s) → the actual
/// resolver request/response exchange → JWT verification → session token) with
/// the "where did it fail?" hints, plus the tool calls a turn made, denial,
/// intent, retrieval, working memory, and the last prompt halves. Sourced from
/// the polled inspector endpoint (no live SSE on these paths).
class InspectorPanel extends StatelessWidget {
  const InspectorPanel({super.key, required this.snapshot, this.emptyHint});
  final InspectorSnapshot? snapshot;

  /// Shown when there's no snapshot yet. Defaults to a message-oriented hint.
  final String? emptyHint;

  @override
  Widget build(BuildContext context) {
    final s = snapshot;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.insights, size: 18),
                const SizedBox(width: 6),
                Text('Inspector',
                    style: Theme.of(context).textTheme.titleSmall),
              ],
            ),
            const Divider(),
            Expanded(
              child: s == null
                  ? Center(
                      child: Text(
                          emptyHint ?? 'Send a message to inspect the turn.',
                          style: Theme.of(context).textTheme.bodySmall,
                          textAlign: TextAlign.center))
                  : ListView(
                      children: [
                        _authFlowSection(context, s),
                        _toolActivitySection(context, s),
                        _denialSection(context, s),
                        _intentSection(context, s),
                        _retrievalSection(context, s),
                        _workingMemorySection(context, s),
                        _promptSection(context, s),
                      ],
                    ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _section(BuildContext context, String title, List<Widget> children) {
    if (children.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(bottom: 14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title.toUpperCase(),
              style: Theme.of(context).textTheme.labelSmall?.copyWith(
                  fontWeight: FontWeight.bold, letterSpacing: 0.5)),
          const SizedBox(height: 6),
          ...children,
        ],
      ),
    );
  }

  Color _severityColor(BuildContext context, String severity) {
    final scheme = Theme.of(context).colorScheme;
    return switch (severity) {
      'fail' => scheme.error,
      'warn' => Colors.orange.shade800,
      _ => scheme.outline,
    };
  }

  IconData _stageIcon(String? status) => switch (status) {
        'ok' => Icons.check_circle,
        'fail' => Icons.error,
        'warn' => Icons.warning_amber,
        'skipped' => Icons.remove_circle_outline,
        _ => Icons.radio_button_unchecked,
      };

  Widget _stageRow(BuildContext context, String status, String headline,
      {String? detail}) {
    final color = _severityColor(context, status == 'ok' ? 'info' : status);
    final dot = Icon(_stageIcon(status), size: 14,
        color: status == 'ok' ? Colors.green : color);
    final head = Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        dot,
        const SizedBox(width: 6),
        Expanded(child: Text(headline, style: const TextStyle(fontSize: 12))),
      ],
    );
    if (detail == null || detail.isEmpty) {
      return Padding(padding: const EdgeInsets.only(bottom: 6), child: head);
    }
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          head,
          Padding(
            padding: const EdgeInsets.only(left: 20, top: 2),
            child: Text(detail,
                style: Theme.of(context).textTheme.bodySmall),
          ),
        ],
      ),
    );
  }

  Widget _kvBlock(BuildContext context, String label, String body) {
    return Padding(
      padding: const EdgeInsets.only(top: 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label,
              style: Theme.of(context).textTheme.labelSmall?.copyWith(
                  fontWeight: FontWeight.bold)),
          Text(body,
              style: const TextStyle(fontFamily: 'monospace', fontSize: 10)),
        ],
      ),
    );
  }

  String _prettyMap(Map<String, dynamic> m) {
    if (m.isEmpty) return '(empty)';
    return m.entries.map((e) => '${e.key}: ${e.value}').join('\n');
  }

  Widget _exchangeTile(BuildContext context, ResolverExchangeSnapshot ex) {
    final scheme = Theme.of(context).colorScheme;
    final ok = ex.responseStatus != null &&
        ex.responseStatus! >= 200 &&
        ex.responseStatus! < 300;
    final statusLabel = ex.error != null
        ? 'error'
        : (ex.responseStatus?.toString() ?? '—');
    return Container(
      margin: const EdgeInsets.only(bottom: 6),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Theme(
        data: Theme.of(context).copyWith(dividerColor: Colors.transparent),
        child: ExpansionTile(
          tilePadding: const EdgeInsets.symmetric(horizontal: 8),
          childrenPadding: const EdgeInsets.fromLTRB(8, 0, 8, 8),
          dense: true,
          leading: Icon(Icons.swap_vert,
              size: 16, color: ok ? Colors.green : scheme.error),
          title: Text('Resolver exchange · $statusLabel',
              style: const TextStyle(fontSize: 12)),
          subtitle: Text('${ex.method ?? 'POST'} ${ex.url ?? ''}',
              style: const TextStyle(fontSize: 10),
              overflow: TextOverflow.ellipsis),
          children: [
            _kvBlock(context, 'REQUEST HEADERS',
                _prettyMap(ex.requestHeaders)),
            _kvBlock(context, 'REQUEST BODY', _prettyMap(ex.requestBody)),
            if (ex.responseStatus != null)
              _kvBlock(context, 'RESPONSE (${ex.responseStatus})',
                  ex.responseBody == null || ex.responseBody!.isEmpty
                      ? '(empty body)'
                      : ex.responseBody!),
            if (ex.error != null)
              _kvBlock(context, 'ERROR', ex.error!),
          ],
        ),
      ),
    );
  }

  Widget _authFlowSection(BuildContext context, InspectorSnapshot s) {
    final af = s.authFlow;
    if (af == null) return const SizedBox.shrink();
    final scheme = Theme.of(context).colorScheme;
    final children = <Widget>[];

    // Hints banner — the "where it failed" conclusion, most-severe first.
    for (final h in af.hints) {
      final c = _severityColor(context, h.severity);
      children.add(Container(
        margin: const EdgeInsets.only(bottom: 6),
        padding: const EdgeInsets.all(8),
        decoration: BoxDecoration(
          color: c.withValues(alpha: 0.12),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: c.withValues(alpha: 0.5)),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(h.severity == 'fail' ? Icons.error : Icons.warning_amber,
                size: 14, color: c),
            const SizedBox(width: 6),
            Expanded(
                child: Text(h.message,
                    style: TextStyle(fontSize: 11, color: c))),
          ],
        ),
      ));
    }

    // Stage 1 — Binding.
    final b = af.binding;
    if (b != null) {
      children.add(_stageRow(context, 'ok',
          b.hasResolver
              ? 'Binding ${b.bindingId} → resolver #${b.resolverId}'
              : 'Binding ${b.bindingId} → FAQ mode (no resolver)',
          detail: 'profile ${b.profileId ?? '—'}'
              '${b.onUnresolved != null ? ' · on_unresolved=${b.onUnresolved}' : ''}'));

      // Stage 1b — profile resolution: show when the resolver overrode the
      // bound profile, or when it tried to and was denied by a guardrail.
      if (b.overrodeProfile) {
        children.add(_stageRow(context, 'ok', 'Profile override → ${b.effectiveProfile}',
            detail: 'resolver selected ${b.effectiveProfile} '
                '(bound was ${b.profileId ?? '—'})'));
      } else if (b.overrideDeniedReason != null) {
        children.add(_stageRow(context, 'warn', 'Profile override denied',
            detail: '${b.overrideDeniedReason} · ran under bound profile '
                '${b.profileId ?? '—'}'));
      }
    }

    // Stage 2 — Resolver call(s).
    for (final c in af.resolverCalls) {
      final status = c.isOk ? 'ok' : (c.status == 'not_found' ? 'warn' : 'fail');
      final parts = <String>[
        if (c.cacheState != null) c.cacheState!,
        if (c.reason != null) c.reason!,
        if (c.elapsedMs != null) '${c.elapsedMs}ms',
        if (c.httpStatus != null) 'HTTP ${c.httpStatus}',
      ];
      children.add(_stageRow(context, status,
          '${c.resolver ?? 'resolver'} · ${c.status ?? '?'}'
          '${parts.isEmpty ? '' : '  (${parts.join(' · ')})'}',
          detail: c.errorSummary));
    }

    // Resolver exchange — the actual request yaya sent and the response it
    // got back (credential + JWT redacted). The point of the debug view.
    for (final ex in af.resolverExchanges) {
      children.add(_exchangeTile(context, ex));
    }

    // Stage 3 — JWT verification.
    final v = af.verification;
    if (v != null && v.decision != null) {
      final status = v.isVerified
          ? 'ok'
          : (v.decision == 'REJECT' ? 'fail' : 'warn');
      children.add(_stageRow(context, status, 'Verify: ${v.decision}',
          detail: v.detail));
    }

    // Stage 4 — Session token.
    if (af.sessionToken != null) {
      final st = af.sessionToken;
      final status = st == 'STORED' ? 'ok' : 'warn';
      final headline = switch (st) {
        'STORED' => 'JWT stored on session',
        'ABSENT' => 'No token on session',
        'CIPHER_DISABLED' => 'Token not persisted — cipher key not configured',
        _ => 'Session token: $st',
      };
      children.add(_stageRow(context, status, headline));
    }

    return Container(
      margin: const EdgeInsets.only(bottom: 14),
      padding: const EdgeInsets.all(8),
      decoration: BoxDecoration(
        border: Border.all(color: scheme.outlineVariant),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('AUTH FLOW',
              style: Theme.of(context).textTheme.labelSmall?.copyWith(
                  fontWeight: FontWeight.bold, letterSpacing: 0.5)),
          const SizedBox(height: 6),
          ...children,
        ],
      ),
    );
  }

  String _toolAuthLine(ToolActivitySnapshot t) {
    final mode = t.authForwarding ?? 'NONE';
    final header = mode == 'NONE'
        ? 'n/a'
        : (t.authHeaderPresent == true ? 'sent' : 'ABSENT');
    final http = t.httpStatus != null ? ' · HTTP ${t.httpStatus}' : '';
    return 'auth: $mode · token $header$http';
  }

  Widget _toolActivitySection(BuildContext context, InspectorSnapshot s) {
    final scheme = Theme.of(context).colorScheme;
    if (s.toolActivity.isEmpty) {
      return _section(context, 'Tool activity', [
        Text('No tools called this turn.',
            style: Theme.of(context).textTheme.bodySmall),
      ]);
    }
    return _section(context, 'Tool activity', [
      for (final t in s.toolActivity)
        Container(
          margin: const EdgeInsets.only(bottom: 6),
          padding: const EdgeInsets.all(8),
          decoration: BoxDecoration(
            color: scheme.surfaceContainerHighest,
            borderRadius: BorderRadius.circular(8),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(
                    t.isPending
                        ? Icons.hourglass_empty
                        : t.isOk
                            ? Icons.check_circle
                            : Icons.error,
                    size: 14,
                    color: t.isPending
                        ? scheme.outline
                        : t.isOk
                            ? Colors.green
                            : scheme.error,
                  ),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Text(t.toolId,
                        style: const TextStyle(
                            fontFamily: 'monospace',
                            fontWeight: FontWeight.bold,
                            fontSize: 12)),
                  ),
                  Text(t.status,
                      style: TextStyle(
                          fontSize: 11,
                          color: t.isOk ? Colors.green : scheme.error)),
                ],
              ),
              if (t.authForwarding != null)
                Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: Text(_toolAuthLine(t),
                      style: TextStyle(
                          fontFamily: 'monospace',
                          fontSize: 11,
                          color: t.httpStatus == 401
                              ? scheme.error
                              : scheme.onSurfaceVariant)),
                ),
              if (t.args.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: Text('args: ${t.args}',
                      style: const TextStyle(
                          fontFamily: 'monospace', fontSize: 11)),
                ),
              if (t.error != null && t.error!.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: Text('error: ${t.error}',
                      style: TextStyle(
                          fontFamily: 'monospace',
                          fontSize: 11,
                          color: scheme.error)),
                )
              else if (!t.isPending && t.result != null)
                Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: Text('→ ${t.result}',
                      style: const TextStyle(
                          fontFamily: 'monospace', fontSize: 11)),
                ),
            ],
          ),
        ),
    ]);
  }

  Widget _denialSection(BuildContext context, InspectorSnapshot s) {
    final d = s.lastDenial;
    if (d == null) return const SizedBox.shrink();
    final scheme = Theme.of(context).colorScheme;
    return _section(context, 'Last denial', [
      Container(
        padding: const EdgeInsets.all(8),
        decoration: BoxDecoration(
          color: scheme.errorContainer,
          borderRadius: BorderRadius.circular(8),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (d.toolId != null)
              Text(d.toolId!,
                  style: TextStyle(
                      fontFamily: 'monospace',
                      fontWeight: FontWeight.bold,
                      fontSize: 12,
                      color: scheme.onErrorContainer)),
            if (d.userReason != null)
              Text('user: ${d.userReason}',
                  style: TextStyle(
                      fontSize: 11, color: scheme.onErrorContainer)),
            if (d.auditReason != null)
              Text('audit: ${d.auditReason}',
                  style: TextStyle(
                      fontSize: 11, color: scheme.onErrorContainer)),
          ],
        ),
      ),
    ]);
  }

  Widget _intentSection(BuildContext context, InspectorSnapshot s) {
    final i = s.intent;
    if (i == null || (i.label == null && i.slots.isEmpty)) {
      return const SizedBox.shrink();
    }
    return _section(context, 'Active intent', [
      if (i.label != null)
        Text(i.label!, style: const TextStyle(fontSize: 12)),
      if (i.slots.isNotEmpty)
        Text('${i.slots}',
            style: const TextStyle(fontFamily: 'monospace', fontSize: 11)),
      for (final p in i.parkedStack)
        Text('parked: ${p.label} (${p.reason ?? '—'})',
            style: const TextStyle(fontSize: 11)),
    ]);
  }

  Widget _retrievalSection(BuildContext context, InspectorSnapshot s) {
    final r = s.lastRetrieval;
    if (r == null) return const SizedBox.shrink();
    return _section(context, 'Retrieval (${r.latencyMs}ms)', [
      if (r.sourcesConsidered.isNotEmpty)
        Text('considered: ${r.sourcesConsidered.join(', ')}',
            style: const TextStyle(fontSize: 11)),
      if (r.sourcesDenied.isNotEmpty)
        Text('denied: ${r.sourcesDenied.join(', ')}',
            style: TextStyle(
                fontSize: 11, color: Theme.of(context).colorScheme.error)),
      for (final c in r.chunks)
        Padding(
          padding: const EdgeInsets.only(top: 4),
          child: Text(
              '[${c.score.toStringAsFixed(2)}] ${c.source}: ${c.snippet}',
              style: const TextStyle(fontSize: 11)),
        ),
    ]);
  }

  Widget _workingMemorySection(BuildContext context, InspectorSnapshot s) {
    if (s.workingMemory.isEmpty) return const SizedBox.shrink();
    return _section(context, 'Working memory', [
      for (final e in s.workingMemory.entries)
        Text('${e.key}: ${e.value}',
            style: const TextStyle(fontFamily: 'monospace', fontSize: 11)),
    ]);
  }

  Widget _promptSection(BuildContext context, InspectorSnapshot s) {
    final p = s.lastPrompt;
    if (p == null) return const SizedBox.shrink();
    return _section(context, 'Last prompt', [
      ExpansionTile(
        tilePadding: EdgeInsets.zero,
        childrenPadding: EdgeInsets.zero,
        dense: true,
        title: const Text('Cacheable prefix', style: TextStyle(fontSize: 12)),
        children: [
          Text(p.cacheablePrefix,
              style: const TextStyle(fontFamily: 'monospace', fontSize: 10)),
        ],
      ),
      ExpansionTile(
        tilePadding: EdgeInsets.zero,
        childrenPadding: EdgeInsets.zero,
        dense: true,
        title: const Text('Variable suffix', style: TextStyle(fontSize: 12)),
        children: [
          Text(p.variableSuffix,
              style: const TextStyle(fontFamily: 'monospace', fontSize: 10)),
        ],
      ),
    ]);
  }
}
