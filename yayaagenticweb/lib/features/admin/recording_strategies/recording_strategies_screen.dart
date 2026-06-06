import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../models/admin/recording_strategy.dart';
import '../shared/admin_shared.dart';

/// (scope kind, scope id) -> latest strategy. M1 doesn't expose a list-all
/// endpoint, so the user types in a scope and we show the effective row.
final lookupCtrlProvider = StateProvider<({String kind, String id})>((_) =>
    (kind: 'PROFILE', id: 'hello-world'));

final strategyForScopeProvider = FutureProvider<RecordingStrategyResponse?>((ref) async {
  final api = await ref.watch(adminApiProvider.future);
  final scope = ref.watch(lookupCtrlProvider);
  return api.getRecordingStrategy(scopeKind: scope.kind, scopeId: scope.id);
});

class RecordingStrategiesScreen extends ConsumerStatefulWidget {
  const RecordingStrategiesScreen({super.key});
  @override
  ConsumerState<RecordingStrategiesScreen> createState() =>
      _RecordingStrategiesScreenState();
}

class _RecordingStrategiesScreenState
    extends ConsumerState<RecordingStrategiesScreen> {
  String _scopeKind = 'PROFILE';
  final _scopeIdCtrl = TextEditingController(text: 'hello-world');

  String _strategyKind = 'fanout';
  final _extraJsonCtrl = TextEditingController(text: '{\n  "primary": "postgres",\n  "sinks": []\n}');
  bool _saving = false;

  @override
  void dispose() {
    _scopeIdCtrl.dispose();
    _extraJsonCtrl.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_scopeIdCtrl.text.trim().isEmpty) {
      showSnack(context, 'scope id is required', error: true);
      return;
    }
    Map<String, dynamic> extras;
    try {
      final parsed = jsonDecode(_extraJsonCtrl.text);
      extras = parsed is Map<String, dynamic>
          ? parsed
          : Map<String, dynamic>.from(parsed as Map);
    } catch (e) {
      showSnack(context, 'extra JSON is not valid: $e', error: true);
      return;
    }
    setState(() => _saving = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.createRecordingStrategy(RecordingStrategyRequest(
        tenant: 'default',
        scopeKind: _scopeKind,
        scopeId: _scopeIdCtrl.text.trim(),
        strategy: {'kind': _strategyKind, ...extras},
      ));
      ref.read(lookupCtrlProvider.notifier).state =
          (kind: _scopeKind, id: _scopeIdCtrl.text.trim());
      ref.invalidate(strategyForScopeProvider);
      if (!mounted) return;
      showSnack(context, 'recording strategy saved');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final effective = ref.watch(strategyForScopeProvider);
    return AdminTwoPane(
      title: 'Recording strategies',
      subtitle: 'PROFILE wins over TENANT at session-start lookup time (M5 will use this for live routing).',
      list: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('Lookup', style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 8),
          Row(
            children: [
              SizedBox(
                width: 140,
                child: DropdownButtonFormField<String>(
                  initialValue: ref.watch(lookupCtrlProvider).kind,
                  decoration: const InputDecoration(labelText: 'scope kind'),
                  items: const ['TENANT', 'PROFILE']
                      .map((s) => DropdownMenuItem(value: s, child: Text(s)))
                      .toList(),
                  onChanged: (v) {
                    final cur = ref.read(lookupCtrlProvider);
                    ref.read(lookupCtrlProvider.notifier).state =
                        (kind: v ?? 'PROFILE', id: cur.id);
                  },
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: TextFormField(
                  initialValue: ref.watch(lookupCtrlProvider).id,
                  decoration: const InputDecoration(labelText: 'scope id'),
                  onFieldSubmitted: (v) {
                    final cur = ref.read(lookupCtrlProvider);
                    ref.read(lookupCtrlProvider.notifier).state = (kind: cur.kind, id: v);
                  },
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          Text('Effective strategy', style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 8),
          Expanded(
            child: effective.when(
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (e, _) => Text(formatError(e),
                  style: TextStyle(color: Theme.of(context).colorScheme.error)),
              data: (s) => s == null
                  ? const Center(child: Text('No strategy bound at this scope.'))
                  : Card(
                      child: Padding(
                        padding: const EdgeInsets.all(12),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text('${s.scopeKind}/${s.scopeId}  v${s.version}',
                                style: Theme.of(context).textTheme.titleMedium),
                            const SizedBox(height: 8),
                            SelectableText(
                              const JsonEncoder.withIndent('  ').convert(s.strategy),
                              style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                            ),
                          ],
                        ),
                      ),
                    ),
            ),
          ),
        ],
      ),
      form: SingleChildScrollView(
        child: FormCard(
          title: 'New / new-version strategy',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              SegmentedButton<String>(
                segments: const [
                  ButtonSegment(value: 'TENANT', label: Text('TENANT')),
                  ButtonSegment(value: 'PROFILE', label: Text('PROFILE')),
                ],
                selected: {_scopeKind},
                onSelectionChanged: (s) => setState(() => _scopeKind = s.first),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _scopeIdCtrl,
                decoration: const InputDecoration(labelText: 'scope id'),
              ),
              const SizedBox(height: 12),
              DropdownButtonFormField<String>(
                initialValue: _strategyKind,
                decoration: const InputDecoration(labelText: 'strategy kind'),
                items: const ['single', 'fanout', 'tiered', 'classified']
                    .map((k) => DropdownMenuItem(value: k, child: Text(k)))
                    .toList(),
                onChanged: (v) => setState(() => _strategyKind = v ?? 'single'),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _extraJsonCtrl,
                maxLines: 8,
                style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                decoration: const InputDecoration(
                  labelText: 'extra strategy JSON',
                  helperText: 'merged with {kind: …} when sent',
                ),
              ),
            ],
          ),
          actions: [
            FilledButton(
              onPressed: _saving ? null : _save,
              child: _saving
                  ? const SizedBox.square(dimension: 16, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Text('Save'),
            ),
          ],
        ),
      ),
    );
  }
}
