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
  int? _cloningFromVersion;

  void _cloneEditFrom(CapabilityResponse c) {
    setState(() {
      _idCtrl.text = c.id;
      _labelCtrl.text = c.label;
      _descCtrl.text = c.description ?? '';
      _guidanceCtrl.text = c.llmGuidance ?? '';
      _hintsCtrl.text = c.followUpHints.join('\n');
      _toolsSelected
        ..clear()
        ..addAll(c.tools);
      _cloningFromVersion = c.version;
    });
    showSnack(context,
        'Cloned ${c.id}@${c.version} → will save as v${c.version + 1}');
  }

  void _cancelClone() {
    setState(() {
      _cloningFromVersion = null;
      _idCtrl.clear();
      _labelCtrl.clear();
      _descCtrl.clear();
      _guidanceCtrl.clear();
      _hintsCtrl.clear();
      _toolsSelected.clear();
    });
  }

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
      final wasCloning = _cloningFromVersion;
      _idCtrl.clear();
      _labelCtrl.clear();
      _descCtrl.clear();
      _guidanceCtrl.clear();
      _hintsCtrl.clear();
      setState(() {
        _toolsSelected.clear();
        _cloningFromVersion = null;
      });
      showSnack(context,
          wasCloning == null
              ? 'capability saved'
              : 'saved as v${wasCloning + 1}');
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
                itemBuilder: (_, i) => _Tile(
                  c: list[i],
                  onCloneEdit: () => _cloneEditFrom(list[i]),
                ),
                separatorBuilder: (_, __) => const SizedBox(height: 8),
                itemCount: list.length,
              ),
      ),
      form: SingleChildScrollView(
        child: FormCard(
          title: _cloningFromVersion == null
              ? 'New capability'
              : 'Editing clone of ${_idCtrl.text}@$_cloningFromVersion → '
                  'saves as v${_cloningFromVersion! + 1}',
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
            if (_cloningFromVersion != null)
              TextButton(
                onPressed: _saving ? null : _cancelClone,
                child: const Text('Cancel'),
              ),
            FilledButton(
              onPressed: _saving ? null : _save,
              child: _saving
                  ? const SizedBox.square(dimension: 16, child: CircularProgressIndicator(strokeWidth: 2))
                  : Text(_cloningFromVersion == null
                      ? 'Save'
                      : 'Save as v${_cloningFromVersion! + 1}'),
            ),
          ],
        ),
      ),
    );
  }
}

class _Tile extends StatelessWidget {
  const _Tile({required this.c, required this.onCloneEdit});
  final CapabilityResponse c;
  final VoidCallback onCloneEdit;

  @override
  Widget build(BuildContext context) {
    return EditableRecordCard(
      onCloneEdit: onCloneEdit,
      header: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('${c.id}@${c.version}',
              style: Theme.of(context).textTheme.titleSmall),
          Text(c.label, style: Theme.of(context).textTheme.bodyMedium),
        ],
      ),
      trailing: Chip(
        label: Text('${c.tools.length} tools'),
        visualDensity: VisualDensity.compact,
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          DetailRow(label: 'label', value: c.label),
          if (c.description != null)
            DetailRow(label: 'description', value: c.description!),
          if (c.llmGuidance != null)
            DetailRow(label: 'llmGuidance', value: c.llmGuidance!),
          DetailRow(
            label: 'tools',
            value: c.tools.isEmpty ? '(none)' : c.tools.join(', '),
          ),
          DetailRow(
            label: 'followUpHints',
            value: c.followUpHints.isEmpty
                ? '(none)'
                : c.followUpHints.map((h) => '• $h').join('\n'),
            mono: c.followUpHints.length > 1,
          ),
        ],
      ),
    );
  }
}
