import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/knowledge_source.dart';
import '../shared/admin_shared.dart';

final knowledgeSourcesProvider =
    FutureProvider.autoDispose<List<KnowledgeSourceResponse>>((ref) async {
  final tenant = ref.watch(currentTenantOrNull);
  if (tenant == null) return const [];
  final api = await ref.watch(adminApiProvider.future);
  return api.listKnowledgeSources(tenant: tenant);
});

class KnowledgeSourcesScreen extends ConsumerStatefulWidget {
  const KnowledgeSourcesScreen({super.key});
  @override
  ConsumerState<KnowledgeSourcesScreen> createState() => _State();
}

class _State extends ConsumerState<KnowledgeSourcesScreen> {
  final _idCtrl = TextEditingController();
  final _nameCtrl = TextEditingController();
  String _locationKind = 'INLINE';
  final _inlineDocsCtrl =
      TextEditingController(text: '# Sample\n\nReplace this with your content.');
  final _localPathCtrl = TextEditingController(text: '/var/data/docs');
  final _includeGlobCtrl = TextEditingController(text: 'glob:**/*.md');
  bool _saving = false;
  int? _cloningFromVersion;

  void _cloneEditFrom(KnowledgeSourceResponse s) {
    setState(() {
      _idCtrl.text = s.id;
      _nameCtrl.text = s.name;
      _locationKind = s.locationKind == 'LOCAL_PATH' ? 'LOCAL_PATH' : 'INLINE';
      if (_locationKind == 'INLINE') {
        final docs = (s.location['docs'] as List?) ?? const [];
        if (docs.isNotEmpty && docs.first is Map) {
          _inlineDocsCtrl.text =
              ((docs.first as Map)['text'] ?? '').toString();
        }
      } else {
        _localPathCtrl.text = (s.location['root'] ?? '').toString();
        _includeGlobCtrl.text =
            (s.location['includeGlob'] ?? 'glob:**/*.md').toString();
      }
      _cloningFromVersion = s.version;
    });
    showSnack(context,
        'Cloned ${s.id}@${s.version} → will save as v${s.version + 1}');
  }

  void _cancelClone() {
    setState(() {
      _cloningFromVersion = null;
      _idCtrl.clear();
      _nameCtrl.clear();
      _inlineDocsCtrl.text = '# Sample\n\nReplace this with your content.';
      _localPathCtrl.text = '/var/data/docs';
      _includeGlobCtrl.text = 'glob:**/*.md';
      _locationKind = 'INLINE';
    });
  }

  @override
  void dispose() {
    _idCtrl.dispose();
    _nameCtrl.dispose();
    _inlineDocsCtrl.dispose();
    _localPathCtrl.dispose();
    _includeGlobCtrl.dispose();
    super.dispose();
  }

  Map<String, dynamic> _buildLocation() {
    if (_locationKind == 'INLINE') {
      return {
        'docs': [
          {
            'id': 'doc-1',
            'contentType': 'text/markdown',
            'text': _inlineDocsCtrl.text,
          }
        ]
      };
    }
    return {
      'root': _localPathCtrl.text.trim(),
      'includeGlob': _includeGlobCtrl.text.trim(),
    };
  }

  Future<void> _save() async {
    if (_idCtrl.text.trim().isEmpty || _nameCtrl.text.trim().isEmpty) {
      showSnack(context, 'id and name are required', error: true);
      return;
    }
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) {
      showSnack(context, 'select a tenant first', error: true);
      return;
    }
    setState(() => _saving = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.createKnowledgeSource(CreateKnowledgeSourceRequest(
        tenant: tenant,
        id: _idCtrl.text.trim(),
        name: _nameCtrl.text.trim(),
        locationKind: _locationKind,
        location: _buildLocation(),
        ingestion: const {},
        retrieval: const {},
      ));
      if (!mounted) return;
      final wasCloning = _cloningFromVersion;
      showSnack(context,
          wasCloning == null
              ? 'created (status UNINDEXED — reindex to populate)'
              : 'saved as v${wasCloning + 1} (reindex to populate)');
      ref.invalidate(knowledgeSourcesProvider);
      _idCtrl.clear();
      _nameCtrl.clear();
      setState(() => _cloningFromVersion = null);
    } catch (e) {
      if (!mounted) return;
      showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _reindex(String id) async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) {
      showSnack(context, 'select a tenant first', error: true);
      return;
    }
    try {
      final api = await ref.read(adminApiProvider.future);
      final result = await api.reindexKnowledgeSource(tenant: tenant, id: id);
      if (!mounted) return;
      final summary = result.error == null
          ? 'reindexed: +${result.docsAdded} docs, +${result.chunksAdded} chunks '
              '(total ${result.totalDocs}/${result.totalChunks})'
          : 'reindex error: ${result.error}';
      showSnack(context, summary, error: result.error != null);
      ref.invalidate(knowledgeSourcesProvider);
    } catch (e) {
      if (!mounted) return;
      showSnack(context, formatError(e), error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (ref.watch(currentTenantOrNull) == null) {
      return const TenantScopedEmptyState(resourceLabel: 'Knowledge sources');
    }
    final sources = ref.watch(knowledgeSourcesProvider);
    return AdminTwoPane(
      title: 'Knowledge sources',
      subtitle:
          'Attach documents the bot grounds answers in. Reindex after edits; '
          'sources stay UNINDEXED until first reindex.',
      list: sources.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Padding(
            padding: const EdgeInsets.all(16),
            child: Text(formatError(e),
                style: TextStyle(color: Theme.of(context).colorScheme.error))),
        data: (list) => ListView.separated(
          padding: const EdgeInsets.all(16),
          itemCount: list.length,
          separatorBuilder: (_, __) => const SizedBox(height: 8),
          itemBuilder: (_, i) => _SourceCard(
            source: list[i],
            onReindex: () => _reindex(list[i].id),
            onCloneEdit: () => _cloneEditFrom(list[i]),
          ),
        ),
      ),
      form: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
                _cloningFromVersion == null
                    ? 'Create knowledge source'
                    : 'Editing clone of ${_idCtrl.text}@$_cloningFromVersion → '
                        'saves as v${_cloningFromVersion! + 1}',
                style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 12),
            TextField(
                controller: _idCtrl,
                decoration: const InputDecoration(labelText: 'id (kebab)')),
            const SizedBox(height: 8),
            TextField(
                controller: _nameCtrl,
                decoration: const InputDecoration(labelText: 'display name')),
            const SizedBox(height: 16),
            SegmentedButton<String>(
              segments: const [
                ButtonSegment(value: 'INLINE', label: Text('Inline')),
                ButtonSegment(value: 'LOCAL_PATH', label: Text('Local path')),
              ],
              selected: {_locationKind},
              onSelectionChanged: (s) =>
                  setState(() => _locationKind = s.first),
            ),
            const SizedBox(height: 12),
            if (_locationKind == 'INLINE')
              TextField(
                controller: _inlineDocsCtrl,
                maxLines: 10,
                decoration: const InputDecoration(
                  labelText: 'document text (markdown)',
                  alignLabelWithHint: true,
                ),
              )
            else ...[
              TextField(
                  controller: _localPathCtrl,
                  decoration: const InputDecoration(
                      labelText: 'root path (server-local)')),
              const SizedBox(height: 8),
              TextField(
                  controller: _includeGlobCtrl,
                  decoration: const InputDecoration(
                      labelText: 'include glob',
                      hintText: 'glob:**/*.md')),
            ],
            const SizedBox(height: 16),
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                if (_cloningFromVersion != null) ...[
                  TextButton(
                    onPressed: _saving ? null : _cancelClone,
                    child: const Text('Cancel'),
                  ),
                  const SizedBox(width: 8),
                ],
                FilledButton.icon(
                  onPressed: _saving ? null : _save,
                  icon: Icon(_cloningFromVersion == null
                      ? Icons.add
                      : Icons.save_outlined),
                  label: Text(_cloningFromVersion == null
                      ? 'Create source'
                      : 'Save as v${_cloningFromVersion! + 1}'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _SourceCard extends StatelessWidget {
  const _SourceCard({
    required this.source,
    required this.onReindex,
    required this.onCloneEdit,
  });
  final KnowledgeSourceResponse source;
  final VoidCallback onReindex;
  final VoidCallback onCloneEdit;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    Color statusColor;
    switch (source.status) {
      case 'READY':
        statusColor = Colors.green.shade700;
        break;
      case 'INDEXING':
        statusColor = Colors.blue.shade700;
        break;
      case 'ERROR':
        statusColor = scheme.error;
        break;
      default:
        statusColor = scheme.outline;
    }
    return EditableRecordCard(
      onCloneEdit: onCloneEdit,
      header: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(source.name,
              style: Theme.of(context).textTheme.titleSmall),
          Text(
              '${source.id}@${source.version}  ·  ${source.locationKind}',
              style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
        ],
      ),
      trailing: Container(
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
        decoration: BoxDecoration(
          color: statusColor.withValues(alpha: 0.15),
          borderRadius: BorderRadius.circular(4),
          border: Border.all(color: statusColor),
        ),
        child: Text(source.status,
            style: TextStyle(
                color: statusColor,
                fontSize: 10,
                fontWeight: FontWeight.w700)),
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          DetailRow(label: 'name', value: source.name),
          DetailRow(label: 'locationKind', value: source.locationKind),
          DetailRow(
            label: 'location',
            value: source.location.toString(),
            mono: true,
          ),
          DetailRow(label: 'status', value: source.status),
          DetailRow(
            label: 'counts',
            value: '${source.docCount} docs · ${source.chunkCount} chunks',
          ),
          if (source.lastIndexedAt != null)
            DetailRow(label: 'lastIndexedAt', value: source.lastIndexedAt!),
          if (source.lastError != null)
            DetailRow(label: 'lastError', value: source.lastError!),
          const SizedBox(height: 4),
          Align(
            alignment: Alignment.centerLeft,
            child: FilledButton.tonalIcon(
              onPressed: onReindex,
              icon: const Icon(Icons.refresh, size: 18),
              label: const Text('Reindex now'),
            ),
          ),
        ],
      ),
    );
  }
}
