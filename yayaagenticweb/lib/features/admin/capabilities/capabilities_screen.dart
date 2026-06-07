import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../models/admin/capability.dart';
import '../../../models/admin/tool.dart';
import '../profiles/profiles_screen.dart' show capabilitiesProvider;
import '../shared/admin_shared.dart';

final toolsProvider = FutureProvider<List<ToolResponse>>((ref) async {
  final api = await ref.watch(adminApiProvider.future);
  return api.listTools();
});

class CapabilitiesScreen extends ConsumerStatefulWidget {
  const CapabilitiesScreen({super.key});
  @override
  ConsumerState<CapabilitiesScreen> createState() => _CapabilitiesScreenState();
}

class _CapabilitiesScreenState extends ConsumerState<CapabilitiesScreen> {
  final _idCtrl = TextEditingController();
  final _labelCtrl = TextEditingController();
  final _descCtrl = TextEditingController();
  final _guidanceCtrl = TextEditingController();
  final _hintsCtrl = TextEditingController();
  final _toolsSelected = <String>{};
  bool _saving = false;

  @override
  void dispose() {
    _idCtrl.dispose();
    _labelCtrl.dispose();
    _descCtrl.dispose();
    _guidanceCtrl.dispose();
    _hintsCtrl.dispose();
    super.dispose();
  }

  List<String> _parseHints(String raw) => raw
      .split('\n')
      .map((s) => s.trim())
      .where((s) => s.isNotEmpty)
      .toList();

  Future<void> _save() async {
    if (_idCtrl.text.trim().isEmpty || _labelCtrl.text.trim().isEmpty) {
      showSnack(context, 'id and label are required', error: true);
      return;
    }
    setState(() => _saving = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.createCapability(CapabilityRequest(
        tenant: 'default',
        id: _idCtrl.text.trim(),
        label: _labelCtrl.text.trim(),
        description: _descCtrl.text.trim().isEmpty ? null : _descCtrl.text.trim(),
        llmGuidance: _guidanceCtrl.text.trim().isEmpty ? null : _guidanceCtrl.text.trim(),
        tools: _toolsSelected.toList(),
        followUpHints: _parseHints(_hintsCtrl.text),
      ));
      ref.invalidate(capabilitiesProvider);
      if (!mounted) return;
      _idCtrl.clear();
      _labelCtrl.clear();
      _descCtrl.clear();
      _guidanceCtrl.clear();
      _hintsCtrl.clear();
      setState(_toolsSelected.clear);
      showSnack(context, 'capability saved');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final caps = ref.watch(capabilitiesProvider);
    final tools = ref.watch(toolsProvider);

    return AdminTwoPane(
      title: 'Capabilities',
      subtitle: 'User-facing actions. Each backs N tools and surfaces as a quick-reply.',
      list: caps.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Text(formatError(e), style: TextStyle(color: Theme.of(context).colorScheme.error)),
        data: (list) => list.isEmpty
            ? const Center(child: Text('No capabilities yet.'))
            : ListView.separated(
                itemBuilder: (_, i) => _Tile(c: list[i]),
                separatorBuilder: (_, __) => const SizedBox(height: 8),
                itemCount: list.length,
              ),
      ),
      form: SingleChildScrollView(
        child: FormCard(
          title: 'New capability',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextField(controller: _idCtrl, decoration: const InputDecoration(labelText: 'id')),
              const SizedBox(height: 8),
              TextField(controller: _labelCtrl, decoration: const InputDecoration(labelText: 'user-facing label')),
              const SizedBox(height: 8),
              TextField(
                controller: _descCtrl,
                maxLines: 2,
                decoration: const InputDecoration(labelText: 'user-facing description'),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _guidanceCtrl,
                maxLines: 3,
                decoration: const InputDecoration(labelText: 'LLM guidance'),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _hintsCtrl,
                maxLines: 4,
                decoration: const InputDecoration(
                  labelText: 'Follow-up hints',
                  helperText: 'one per line — emitted as quick-reply chips after dispatch',
                ),
              ),
              const SizedBox(height: 12),
              Text('Tools', style: Theme.of(context).textTheme.labelLarge),
              tools.when(
                loading: () => const LinearProgressIndicator(),
                error: (e, _) => Text(formatError(e),
                    style: TextStyle(color: Theme.of(context).colorScheme.error)),
                data: (list) => Wrap(
                  spacing: 6,
                  runSpacing: 4,
                  children: list
                      .map((t) => FilterChip(
                            label: Text('${t.id}@${t.version}'),
                            selected: _toolsSelected.contains(t.id),
                            onSelected: (sel) => setState(() {
                              if (sel) {
                                _toolsSelected.add(t.id);
                              } else {
                                _toolsSelected.remove(t.id);
                              }
                            }),
                          ))
                      .toList(),
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

class _Tile extends StatelessWidget {
  const _Tile({required this.c});
  final CapabilityResponse c;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(child: Text('${c.id}@${c.version}',
                    style: Theme.of(context).textTheme.titleMedium)),
                Text('${c.tools.length} tools',
                    style: Theme.of(context).textTheme.bodySmall),
              ],
            ),
            const SizedBox(height: 4),
            Text(c.label, style: Theme.of(context).textTheme.bodyMedium),
            if (c.description != null)
              Text(c.description!,
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Theme.of(context).colorScheme.outline)),
            if (c.followUpHints.isNotEmpty) ...[
              const SizedBox(height: 6),
              Wrap(
                spacing: 4,
                runSpacing: 4,
                children: c.followUpHints
                    .map((h) => Chip(
                          label: Text(h, style: const TextStyle(fontSize: 12)),
                          visualDensity: VisualDensity.compact,
                          materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                        ))
                    .toList(),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
