import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../models/admin/knowledge_source.dart';
import '../shared/admin_shared.dart';

final knowledgeSourcesProvider =
    FutureProvider.autoDispose<List<KnowledgeSourceResponse>>((ref) async {
  final api = await ref.watch(adminApiProvider.future);
  return api.listKnowledgeSources();
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
    setState(() => _saving = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.createKnowledgeSource(CreateKnowledgeSourceRequest(
        id: _idCtrl.text.trim(),
        name: _nameCtrl.text.trim(),
        locationKind: _locationKind,
        location: _buildLocation(),
        ingestion: const {},
        retrieval: const {},
      ));
      if (!mounted) return;
      showSnack(context, 'created (status UNINDEXED — reindex to populate)');
      ref.invalidate(knowledgeSourcesProvider);
      _idCtrl.clear();
      _nameCtrl.clear();
    } catch (e) {
      if (!mounted) return;
      showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _reindex(String id) async {
    try {
      final api = await ref.read(adminApiProvider.future);
      final result = await api.reindexKnowledgeSource(id: id);
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
          ),
        ),
      ),
      form: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('Create knowledge source',
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
            FilledButton.icon(
              onPressed: _saving ? null : _save,
              icon: const Icon(Icons.add),
              label: const Text('Create source'),
            ),
          ],
        ),
      ),
    );
  }
}

class _SourceCard extends StatelessWidget {
  const _SourceCard({required this.source, required this.onReindex});
  final KnowledgeSourceResponse source;
  final VoidCallback onReindex;

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
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(source.name,
                          style: Theme.of(context).textTheme.titleSmall),
                      Text(
                          '${source.id}@${source.version}  ·  ${source.locationKind}',
                          style: const TextStyle(
                              fontFamily: 'monospace', fontSize: 12)),
                    ],
                  ),
                ),
                Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
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
              ],
            ),
            const SizedBox(height: 6),
            Text(
              '${source.docCount} docs · ${source.chunkCount} chunks'
              '${source.lastIndexedAt == null ? "" : "  ·  indexed " + source.lastIndexedAt!.substring(0, 19)}',
              style: const TextStyle(fontSize: 12),
            ),
            if (source.lastError != null) ...[
              const SizedBox(height: 4),
              Text(source.lastError!,
                  style: TextStyle(color: scheme.error, fontSize: 12)),
            ],
            const SizedBox(height: 8),
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                FilledButton.tonalIcon(
                  onPressed: onReindex,
                  icon: const Icon(Icons.refresh),
                  label: const Text('Reindex'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
