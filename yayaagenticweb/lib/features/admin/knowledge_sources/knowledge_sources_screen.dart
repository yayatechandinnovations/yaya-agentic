import 'dart:async';

import 'package:dio/dio.dart' show MultipartFile;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/knowledge_source.dart';
import '../shared/admin_shared.dart';
import 'web_file_picker.dart';

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
  // Upload is the default — operators pick files from their machine and never
  // deal with a server path. Inline is kept for quick text/markdown snippets.
  // (There is no local-path option: reading server paths is a file-read risk.)
  String _locationKind = 'UPLOADED';
  final _inlineDocsCtrl =
      TextEditingController(text: '# Sample\n\nReplace this with your content.');
  final List<UploadedFileRef> _uploadedFiles = [];
  bool _saving = false;
  bool _uploading = false;
  int? _cloningFromVersion;

  // Non-terminal states we poll through so the badge reflects background
  // ingestion without the operator hitting refresh.
  static const _busyStates = {'INDEXING', 'QUEUED', 'RUNNING'};
  Timer? _pollTimer;

  @override
  void initState() {
    super.initState();
    _pollTimer = Timer.periodic(const Duration(seconds: 3), (_) => _maybePoll());
  }

  void _maybePoll() {
    final async = ref.read(knowledgeSourcesProvider);
    final list = async.valueOrNull;
    if (list == null) return;
    final anyBusy = list.any((s) => _busyStates.contains(s.status));
    if (anyBusy || _awaitingIndex) {
      ref.invalidate(knowledgeSourcesProvider);
    }
  }

  // True right after saving an uploaded source, until it leaves UNINDEXED —
  // covers the gap before the worker flips it to INDEXING.
  bool _awaitingIndex = false;

  void _cloneEditFrom(KnowledgeSourceResponse s) {
    setState(() {
      _idCtrl.text = s.id;
      _nameCtrl.text = s.name;
      _uploadedFiles.clear();
      switch (s.locationKind) {
        case 'UPLOADED':
          _locationKind = 'UPLOADED';
          final files = (s.location['files'] as List?) ?? const [];
          for (final f in files) {
            if (f is Map) {
              _uploadedFiles
                  .add(UploadedFileRef.fromJson(Map<String, dynamic>.from(f)));
            }
          }
          break;
        default:
          _locationKind = 'INLINE';
          final docs = (s.location['docs'] as List?) ?? const [];
          if (docs.isNotEmpty && docs.first is Map) {
            _inlineDocsCtrl.text =
                ((docs.first as Map)['text'] ?? '').toString();
          }
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
      _uploadedFiles.clear();
      _locationKind = 'UPLOADED';
    });
  }

  @override
  void dispose() {
    _pollTimer?.cancel();
    _idCtrl.dispose();
    _nameCtrl.dispose();
    _inlineDocsCtrl.dispose();
    super.dispose();
  }

  Map<String, dynamic> _buildLocation() {
    switch (_locationKind) {
      case 'UPLOADED':
        return {'files': _uploadedFiles.map((f) => f.toJson()).toList()};
      default:
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
  }

  Future<void> _pickAndUpload() async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) {
      showSnack(context, 'select a tenant first', error: true);
      return;
    }
    final picked = await pickFilesFromBrowser();
    if (picked.isEmpty) return;
    setState(() => _uploading = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      final files = picked
          .map((p) => MultipartFile.fromBytes(p.bytes, filename: p.name))
          .toList();
      final refs = await api.uploadKnowledgeFiles(tenant: tenant, files: files);
      if (!mounted) return;
      setState(() => _uploadedFiles.addAll(refs));
      showSnack(context, 'uploaded ${refs.length} file(s)');
    } catch (e) {
      if (!mounted) return;
      showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _uploading = false);
    }
  }

  Future<void> _removeUploaded(UploadedFileRef ref0) async {
    final tenant = ref.read(currentTenantOrNull);
    setState(() => _uploadedFiles.remove(ref0));
    if (tenant == null) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.deleteKnowledgeBlob(tenant: tenant, blobId: ref0.blobId);
    } catch (_) {
      // Best-effort: the orphan sweep reclaims it even if this delete fails.
    }
  }

  Future<void> _save() async {
    if (_idCtrl.text.trim().isEmpty || _nameCtrl.text.trim().isEmpty) {
      showSnack(context, 'id and name are required', error: true);
      return;
    }
    if (_locationKind == 'UPLOADED' && _uploadedFiles.isEmpty) {
      showSnack(context, 'upload at least one file', error: true);
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
      final uploaded = _locationKind == 'UPLOADED';
      showSnack(
          context,
          uploaded
              ? 'saved — indexing in the background…'
              : (wasCloning == null
                  ? 'created (status UNINDEXED — reindex to populate)'
                  : 'saved as v${wasCloning + 1} (reindex to populate)'));
      ref.invalidate(knowledgeSourcesProvider);
      setState(() {
        _awaitingIndex = uploaded;
        _cloningFromVersion = null;
        _idCtrl.clear();
        _nameCtrl.clear();
        _uploadedFiles.clear();
      });
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
    // Stop the awaiting-index window once the saved source starts/finishes.
    if (_awaitingIndex) {
      final list = sources.valueOrNull;
      if (list != null && list.any((s) => s.status != 'UNINDEXED')) {
        _awaitingIndex = list.any((s) => _busyStates.contains(s.status));
      }
    }
    return AdminTwoPane(
      title: 'Knowledge sources',
      subtitle:
          'Upload the documents the bot grounds answers in. Uploaded sources '
          'index automatically in the background; watch the status badge.',
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
                ButtonSegment(value: 'UPLOADED', label: Text('Upload')),
                ButtonSegment(value: 'INLINE', label: Text('Inline')),
              ],
              selected: {_locationKind},
              onSelectionChanged: (s) =>
                  setState(() => _locationKind = s.first),
            ),
            const SizedBox(height: 12),
            if (_locationKind == 'UPLOADED')
              _UploadPane(
                files: _uploadedFiles,
                uploading: _uploading,
                onPick: _pickAndUpload,
                onRemove: _removeUploaded,
              )
            else
              TextField(
                controller: _inlineDocsCtrl,
                maxLines: 10,
                decoration: const InputDecoration(
                  labelText: 'document text (markdown)',
                  alignLabelWithHint: true,
                ),
              ),
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

/// Upload picker + the list of already-uploaded files as removable chips.
class _UploadPane extends StatelessWidget {
  const _UploadPane({
    required this.files,
    required this.uploading,
    required this.onPick,
    required this.onRemove,
  });
  final List<UploadedFileRef> files;
  final bool uploading;
  final VoidCallback onPick;
  final ValueChanged<UploadedFileRef> onRemove;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        DottedZone(
          child: Column(
            children: [
              Icon(Icons.upload_file_outlined, color: scheme.outline, size: 32),
              const SizedBox(height: 8),
              Text('PDF, Word, PowerPoint, Excel, HTML, Markdown, or text',
                  style: TextStyle(color: scheme.outline, fontSize: 12)),
              const SizedBox(height: 12),
              FilledButton.tonalIcon(
                onPressed: uploading ? null : onPick,
                icon: uploading
                    ? const SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.attach_file, size: 18),
                label: Text(uploading ? 'Uploading…' : 'Choose files…'),
              ),
            ],
          ),
        ),
        if (files.isNotEmpty) ...[
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final f in files)
                Chip(
                  avatar: const Icon(Icons.description_outlined, size: 18),
                  label: Text('${f.filename} · ${f.prettySize}'),
                  onDeleted: () => onRemove(f),
                ),
            ],
          ),
        ],
      ],
    );
  }
}

/// Simple dashed-border drop zone container (visual only — the picker button
/// inside does the work; the browser file dialog is the upload path).
class DottedZone extends StatelessWidget {
  const DottedZone({super.key, required this.child});
  final Widget child;
  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(vertical: 24, horizontal: 16),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerHighest.withValues(alpha: 0.3),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: scheme.outlineVariant),
      ),
      child: child,
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

  String _locationSummary() {
    if (source.locationKind == 'UPLOADED') {
      final files = (source.location['files'] as List?) ?? const [];
      if (files.isEmpty) return 'no files';
      final names = files
          .whereType<Map>()
          .map((f) => (f['filename'] ?? '').toString())
          .where((s) => s.isNotEmpty)
          .toList();
      return '${files.length} file(s): ${names.join(', ')}';
    }
    return source.location.toString();
  }

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
    final busy = source.status == 'INDEXING';
    return EditableRecordCard(
      onCloneEdit: onCloneEdit,
      header: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(source.name, style: Theme.of(context).textTheme.titleSmall),
          Text(
              '${source.id}@${source.version}  ·  ${source.locationKind}',
              style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
        ],
      ),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (busy)
            const Padding(
              padding: EdgeInsets.only(right: 6),
              child: SizedBox(
                  width: 12,
                  height: 12,
                  child: CircularProgressIndicator(strokeWidth: 2)),
            ),
          Container(
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
        ],
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          DetailRow(label: 'name', value: source.name),
          DetailRow(label: 'locationKind', value: source.locationKind),
          DetailRow(label: 'location', value: _locationSummary(), mono: true),
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
