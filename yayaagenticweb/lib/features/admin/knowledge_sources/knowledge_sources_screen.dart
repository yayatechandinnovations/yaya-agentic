import 'package:dio/dio.dart' show MultipartFile;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../api/admin_api.dart';
import '../../../app/live/conflict_banner.dart';
import '../../../app/live/live_resource.dart';
import '../../../app/live/resource_kind.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/knowledge_source.dart';
import '../../../ui/data/live_async.dart';
import '../../../ui/data/relative_time.dart';
import '../../../ui/data/y_badge.dart';
import '../../../ui/data/y_page.dart';
import '../../../ui/data/y_sheet.dart';
import '../../../ui/data/y_states.dart';
import '../../../ui/data/y_table.dart';
import '../../../ui/feedback/confirm.dart';
import '../../../ui/feedback/toast.dart';
import '../../../ui/form/unsaved_guard.dart';
import '../../../ui/form/y_fields.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/tokens.dart';
import '../shared/admin_shared.dart';
import 'web_file_picker.dart';

/// Every version of every source on the tenant. Uploaded sources that are
/// still on their way to Ready carry their ingestion job (Queued / retrying /
/// failed), and the list polls itself while any of them is in flight.
final knowledgeSourcesProvider = FutureProvider.autoDispose<List<KnowledgeSourceResponse>>((ref) async {
  liveResource(ref, AdminResourceKind.knowledgeSource);
  final tenant = ref.watch(currentTenantOrNull);
  if (tenant == null) return const [];
  final api = await ref.watch(adminApiProvider.future);
  final all = await api.listKnowledgeSources(tenant: tenant);
  final out = await _withIngestionJobs(api, tenant, all);
  pollWhile(ref, summarizeKnowledgeSources(out).any((s) => knowledgeStatusOf(s.latest).inFlight));
  return out;
});

/// The list endpoint has no job state, so ask `/status` — only for the latest
/// version of uploaded sources that aren't Ready yet, which keeps it to a few calls.
Future<List<KnowledgeSourceResponse>> _withIngestionJobs(
    AdminApi api, String tenant, List<KnowledgeSourceResponse> all) async {
  final latest = {for (final s in summarizeKnowledgeSources(all)) s.latest.id: s.latest.version};
  return Future.wait(all.map((s) async {
    final wanted = latest[s.id] == s.version && s.locationKind == 'UPLOADED' && s.status != 'READY';
    if (!wanted) return s;
    try {
      return s.withJob(KnowledgeIngestionJob.fromStatus(await api.knowledgeSourceStatus(tenant: tenant, id: s.id)));
    } catch (_) {
      return s;
    }
  }));
}

class KnowledgeSummary {
  KnowledgeSummary(this.latest, this.versions);
  final KnowledgeSourceResponse latest;
  final List<KnowledgeSourceResponse> versions;
}

List<KnowledgeSummary> summarizeKnowledgeSources(List<KnowledgeSourceResponse> all) {
  final byId = <String, List<KnowledgeSourceResponse>>{};
  for (final s in all) {
    byId.putIfAbsent(s.id, () => []).add(s);
  }
  return [
    for (final e in byId.entries)
      KnowledgeSummary((e.value..sort((a, b) => b.version.compareTo(a.version))).first, e.value),
  ]..sort((a, b) => a.latest.name.toLowerCase().compareTo(b.latest.name.toLowerCase()));
}

final knowledgeSummariesProvider = Provider.autoDispose<AsyncValue<List<KnowledgeSummary>>>(
  (ref) => ref.watch(knowledgeSourcesProvider).whenData(summarizeKnowledgeSources),
);

enum KnowledgePhase { ready, ingesting, queued, failed, unindexed }

/// What the status column says about one source version.
class KnowledgeStatus {
  const KnowledgeStatus(this.phase, this.label, this.tone, this.icon, [this.detail]);
  final KnowledgePhase phase;
  final String label;
  final YTone tone;
  final IconData icon;

  /// Why it failed, which attempt it's on, or what to do next.
  final String? detail;

  bool get inFlight => phase == KnowledgePhase.ingesting || phase == KnowledgePhase.queued;
}

const _supportedKinds = {'UPLOADED', 'INLINE'};

KnowledgeStatus knowledgeStatusOf(KnowledgeSourceResponse s) {
  final job = s.job;
  String? attempt(KnowledgeIngestionJob j) =>
      j.maxAttempts > 1 && j.attempts > 0 ? 'Attempt ${j.attempts} of ${j.maxAttempts}' : null;
  if (s.status == 'INDEXING' || job?.state == 'RUNNING') {
    return KnowledgeStatus(KnowledgePhase.ingesting, 'Ingesting', YTone.info, Icons.sync, job == null ? null : attempt(job));
  }
  if (job?.state == 'QUEUED') {
    final retrying = job!.attempts > 0;
    return KnowledgeStatus(
      KnowledgePhase.queued,
      retrying ? 'Retrying' : 'Queued',
      retrying ? YTone.warning : YTone.neutral,
      Icons.schedule,
      retrying
          ? 'Try ${job.attempts + 1} of ${job.maxAttempts}${job.lastError == null ? '' : ' · last error: ${job.lastError}'}'
          : 'Waiting for an ingestion worker',
    );
  }
  if (s.status == 'ERROR') {
    return KnowledgeStatus(KnowledgePhase.failed, 'Failed', YTone.danger, Icons.error_outline, s.lastError ?? job?.lastError ?? 'No reason was recorded.');
  }
  if (job?.state == 'FAILED') {
    return KnowledgeStatus(KnowledgePhase.failed, 'Failed', YTone.danger, Icons.error_outline,
        '${job!.lastError ?? 'Ingestion gave up'}${job.attempts > 0 ? ' (after ${job.attempts} attempts)' : ''}');
  }
  if (s.status == 'READY') return const KnowledgeStatus(KnowledgePhase.ready, 'Ready', YTone.success, Icons.check_circle_outline);
  if (!_supportedKinds.contains(s.locationKind)) {
    return const KnowledgeStatus(KnowledgePhase.unindexed, 'Unindexed', YTone.warning, Icons.block,
        'Reads a server path, which is no longer supported. Edit it to upload the files instead.');
  }
  return KnowledgeStatus(KnowledgePhase.unindexed, 'Unindexed', YTone.neutral, Icons.radio_button_unchecked,
      s.locationKind == 'INLINE' ? 'Re-index to make it searchable.' : null);
}

List<Object?> _listAt(Map<String, dynamic> m, String key) {
  final v = m[key];
  return v is List ? v : const [];
}

/// Content defined in backend code (the demo seed) rather than stored rows:
/// the console can show it but must not rewrite it.
bool _definedInCode(KnowledgeSourceResponse s) =>
    (s.location.containsKey('docs') && s.location['docs'] is! List) ||
    (s.location.containsKey('files') && s.location['files'] is! List);

List<UploadedFileRef> _filesOf(KnowledgeSourceResponse s) => [
      // Seeded sources hold a placeholder string here, not a list.
      for (final f in _listAt(s.location, 'files'))
        if (f is Map) UploadedFileRef.fromJson(Map<String, dynamic>.from(f)),
    ];

List<Map<String, dynamic>> _docsOf(KnowledgeSourceResponse s) => [
      for (final d in _listAt(s.location, 'docs'))
        if (d is Map) Map<String, dynamic>.from(d),
    ];

String _contentSummary(KnowledgeSourceResponse s) {
  switch (s.locationKind) {
    case 'UPLOADED':
      final files = _filesOf(s);
      if (files.isEmpty) return 'No files';
      return files.length == 1 ? files.first.filename : '${files.length} files';
    case 'INLINE':
      final docs = _docsOf(s);
      return docs.length <= 1 ? 'Pasted text' : '${docs.length} pasted documents';
    case 'LOCAL_PATH':
      return 'Server path (removed)';
    default:
      return s.locationKind;
  }
}

const _labels = {
  'id': 'Source ID',
  'name': 'Name',
  'locationKind': 'Content',
  'files': 'Files',
  'inlineText': 'Text',
};

class KnowledgeSourcesScreen extends ConsumerStatefulWidget {
  const KnowledgeSourcesScreen({super.key});
  @override
  ConsumerState<KnowledgeSourcesScreen> createState() => _KnowledgeSourcesScreenState();
}

class _KnowledgeSourcesScreenState extends ConsumerState<KnowledgeSourcesScreen> {
  String? _handledLink;

  Future<void> _open(KnowledgeSummary? existing) async {
    await showYSheet<void>(
      context,
      width: 720,
      label: existing == null ? 'New knowledge source' : 'Edit knowledge source',
      builder: (_) => KnowledgeSourceEditorSheet(existing: existing),
    );
  }

  /// `?id=` / `?new=1` (⌘K, cross-links) open the editor once the list is in.
  void _handleDeepLink(List<KnowledgeSummary> list) {
    final params = GoRouterState.of(context).uri.queryParameters;
    final link = params['id'] ?? (params['new'] != null ? '' : null);
    if (link == null || link == _handledLink) return;
    _handledLink = link;
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      final match = link.isEmpty ? null : list.where((s) => s.latest.id == link).firstOrNull;
      if (link.isNotEmpty && match == null) {
        showToast(context, 'No knowledge source “$link” on this tenant', error: true);
      } else {
        await _open(match);
      }
      if (mounted) context.go('/admin/knowledge-sources');
      _handledLink = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    if (ref.watch(currentTenantOrNull) == null) {
      return const TenantScopedEmptyState(resourceLabel: 'Knowledge sources');
    }
    final summaries = ref.watch(knowledgeSummariesProvider);
    final fresh = ref.watch(freshIdsProvider(AdminResourceKind.knowledgeSource));
    final scheme = Theme.of(context).colorScheme;
    summaries.whenData(_handleDeepLink);

    return YPage(
      title: 'Knowledge sources',
      subtitle: 'The documents a profile grounds its answers in. Uploads index in the background; the status updates by itself.',
      actions: [
        FilledButton.icon(onPressed: () => _open(null), icon: const Icon(Icons.add, size: 18), label: const Text('New source')),
      ],
      child: LiveAsyncView<List<KnowledgeSummary>>(
        value: summaries,
        what: 'knowledge sources',
        onRetry: () => ref.invalidate(knowledgeSourcesProvider),
        data: (context, list) => YTable<KnowledgeSummary>(
          rows: list,
          noun: 'sources',
          idOf: (s) => s.latest.id,
          searchHint: 'Search by name, ID or file name',
          searchTextOf: (s) => '${s.latest.id} ${s.latest.name} ${_filesOf(s.latest).map((f) => f.filename).join(' ')}',
          highlightIds: fresh,
          onRowTap: _open,
          filters: [
            YFilter('Ready', (s) => knowledgeStatusOf(s.latest).phase == KnowledgePhase.ready),
            YFilter('In progress', (s) => knowledgeStatusOf(s.latest).inFlight),
            YFilter('Failed', (s) => knowledgeStatusOf(s.latest).phase == KnowledgePhase.failed),
            YFilter('Unindexed', (s) => knowledgeStatusOf(s.latest).phase == KnowledgePhase.unindexed),
            YFilter('Uploads', (s) => s.latest.locationKind == 'UPLOADED'),
            YFilter('Pasted text', (s) => s.latest.locationKind == 'INLINE'),
          ],
          empty: YEmptyState(
            icon: Icons.menu_book_outlined,
            title: 'No knowledge sources yet',
            message: 'A knowledge source is a set of documents — a price list, a policy PDF — that a profile can cite when it answers.',
            action: FilledButton.icon(onPressed: () => _open(null), icon: const Icon(Icons.upload_file, size: 18), label: const Text('Upload your first documents')),
          ),
          columns: [
            YColumn(
              label: 'Source',
              flex: 3,
              sortKey: (s) => s.latest.name.toLowerCase(),
              cell: (context, s) => YCellStack(primary: s.latest.name.isEmpty ? s.latest.id : s.latest.name, secondary: s.latest.id),
            ),
            YColumn(
              label: 'Status',
              flex: 3,
              sortKey: (s) => knowledgeStatusOf(s.latest).phase.index,
              cell: (context, s) {
                final st = knowledgeStatusOf(s.latest);
                return Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
                  YBadge(st.label, tone: st.tone, icon: st.icon),
                  if (st.detail != null)
                    Padding(
                      padding: const EdgeInsets.only(top: 4),
                      child: Text(st.detail!,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                              fontSize: 12.5,
                              color: st.phase == KnowledgePhase.failed ? scheme.error : scheme.onSurfaceVariant)),
                    ),
                ]);
              },
            ),
            YColumn(
              label: 'Content',
              flex: 2,
              cell: (context, s) => Text(_contentSummary(s.latest), overflow: TextOverflow.ellipsis),
            ),
            YColumn(
              label: 'Indexed',
              flex: 2,
              sortKey: (s) => s.latest.chunkCount,
              cell: (context, s) => YCellStack(
                primary: '${s.latest.docCount} ${s.latest.docCount == 1 ? 'doc' : 'docs'}',
                secondary: '${s.latest.chunkCount} ${s.latest.chunkCount == 1 ? 'chunk' : 'chunks'}',
              ),
            ),
            YColumn(
              label: 'Version',
              width: 120,
              sortKey: (s) => s.latest.version,
              cell: (context, s) => YCellStack(
                primary: 'v${s.latest.version}',
                mono: true,
                secondary: s.versions.length == 1 ? null : '${s.versions.length} versions',
              ),
            ),
            YColumn(
              label: 'Last indexed',
              flex: 2,
              sortKey: (s) => s.latest.lastIndexedAt ?? '',
              cell: (context, s) => Text(
                s.latest.lastIndexedAt == null ? 'Never' : formatRelativeIso(s.latest.lastIndexedAt),
                style: TextStyle(color: s.latest.lastIndexedAt == null ? scheme.onSurfaceVariant : null),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

const _defaultInline = '# Sample\n\nReplace this with your content.';

/// Create a source, or publish a new version of one; re-index from the header.
class KnowledgeSourceEditorSheet extends ConsumerStatefulWidget {
  const KnowledgeSourceEditorSheet({super.key, this.existing});
  final KnowledgeSummary? existing;

  @override
  ConsumerState<KnowledgeSourceEditorSheet> createState() => _KnowledgeSourceEditorSheetState();
}

class _KnowledgeSourceEditorSheetState extends ConsumerState<KnowledgeSourceEditorSheet> {
  final _id = TextEditingController();
  final _name = TextEditingController();
  final _inline = TextEditingController(text: _defaultInline);

  // Upload is the default — operators pick files from their machine and never
  // deal with a server path (reading server paths was a file-read risk).
  String _kind = 'UPLOADED';
  List<UploadedFileRef> _files = [];

  /// Blobs uploaded in this editor. Only these are deleted when removed: the
  /// saved version still points at the others.
  final Set<String> _uploadedHere = {};
  bool _uploading = false;
  String? _uploadError;
  bool _reindexing = false;
  late final YFormController _form;
  KnowledgeSourceResponse? _base;

  bool get _editing => _base != null;
  bool get _legacyKind => _base != null && !_supportedKinds.contains(_base!.locationKind);

  @override
  void initState() {
    super.initState();
    _base = widget.existing?.latest;
    _load(_base);
    final tenant = ref.read(currentTenantOrNull);
    _form = YFormController(
      snapshot: () => {
        'id': _id.text.trim(),
        'name': _name.text.trim(),
        'locationKind': _kind,
        if (_kind == 'UPLOADED') 'files': [for (final f in _files) f.toJson()],
        if (_kind == 'INLINE') 'inlineText': _inline.text,
      },
      validate: _validate,
      labels: _labels,
      draftKey: 'yaya.draft.knowledgeSource.$tenant.${_base?.id ?? 'new'}',
      baseVersion: _base?.version,
    );
    _form.addListener(_rebuild);
    _form.loadDraft().then((d) {
      if (d == null || !mounted) return;
      setState(() => _applySnapshot(d.values));
      _form.refresh();
    });
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  void _load(KnowledgeSourceResponse? s) {
    _id.text = s?.id ?? '';
    _name.text = s?.name ?? '';
    _kind = s == null || !_supportedKinds.contains(s.locationKind) ? 'UPLOADED' : s.locationKind;
    _files = s == null ? [] : _filesOf(s);
    final docs = s == null ? const <Map<String, dynamic>>[] : _docsOf(s);
    _inline.text = docs.isEmpty ? _defaultInline : '${docs.first['text'] ?? ''}';
  }

  void _applySnapshot(Map<String, Object?> v) {
    if (!_editing) _id.text = (v['id'] as String?) ?? _id.text;
    _name.text = (v['name'] as String?) ?? _name.text;
    final kind = v['locationKind'];
    if (kind is String && _supportedKinds.contains(kind)) _kind = kind;
    final files = v['files'];
    if (files is List) {
      _files = [for (final f in files) if (f is Map) UploadedFileRef.fromJson(Map<String, dynamic>.from(f))];
    }
    final text = v['inlineText'];
    if (text is String) _inline.text = text;
  }

  Map<String, String?> _validate() {
    final existing = ref.read(knowledgeSummariesProvider).valueOrNull ?? const [];
    var idErr = _editing ? null : Validators.slugId(_id.text, 'a source ID');
    if (idErr == null && !_editing && existing.any((s) => s.latest.id == _id.text.trim())) {
      idErr = 'A source with this ID already exists. Open it to publish a new version.';
    }
    return {
      'id': idErr,
      'name': Validators.required(_name.text, 'a name'),
      'files': _kind == 'UPLOADED' && _files.isEmpty ? 'Upload at least one file.' : null,
      'inlineText': _kind == 'INLINE' ? Validators.required(_inline.text, 'the text to index') : null,
    };
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _form.dispose();
    for (final c in [_id, _name, _inline]) {
      c.dispose();
    }
    super.dispose();
  }

  Map<String, dynamic> _location() {
    final carried = _base != null && _base!.locationKind == _kind
        ? (Map<String, dynamic>.from(_base!.location)..removeWhere((k, _) => k == 'files' || k == 'docs'))
        : <String, dynamic>{};
    if (_kind == 'UPLOADED') return {...carried, 'files': [for (final f in _files) f.toJson()]};
    // Only the first pasted document is editable here; any others ride along.
    final docs = _base != null && _base!.locationKind == 'INLINE' ? _docsOf(_base!) : const <Map<String, dynamic>>[];
    final first = docs.isEmpty ? <String, dynamic>{'id': 'doc-1', 'contentType': 'text/markdown'} : docs.first;
    return {
      ...carried,
      'docs': [
        {...first, 'text': _inline.text},
        ...docs.skip(1),
      ],
    };
  }

  Future<void> _pickAndUpload() async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    final picked = await pickFilesFromBrowser();
    if (picked.isEmpty) return;
    setState(() {
      _uploading = true;
      _uploadError = null;
    });
    try {
      final api = await ref.read(adminApiProvider.future);
      final refs = await api.uploadKnowledgeFiles(
        tenant: tenant,
        files: [for (final p in picked) MultipartFile.fromBytes(p.bytes, filename: p.name)],
      );
      if (!mounted) return;
      setState(() {
        _files = [..._files, ...refs];
        _uploadedHere.addAll(refs.map((r) => r.blobId));
      });
      _form.changed('files');
    } catch (e) {
      if (mounted) setState(() => _uploadError = 'Upload failed: ${formatError(e)}');
    } finally {
      if (mounted) setState(() => _uploading = false);
    }
  }

  Future<void> _removeFile(UploadedFileRef f) async {
    setState(() => _files = [..._files]..remove(f));
    _form.changed('files');
    if (!_uploadedHere.remove(f.blobId)) return;
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.deleteKnowledgeBlob(tenant: tenant, blobId: f.blobId);
    } catch (_) {
      // Best-effort: the orphan sweep reclaims it even if this delete fails.
    }
  }

  Future<void> _save() async {
    if (!_form.submit()) return;
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    _form.saving = true;
    try {
      final api = await ref.read(adminApiProvider.future);
      final saved = await api.createKnowledgeSource(CreateKnowledgeSourceRequest(
        tenant: tenant,
        id: _editing ? _base!.id : _id.text.trim(),
        name: _name.text.trim(),
        locationKind: _kind,
        location: _location(),
        ingestion: _base?.ingestion ?? const {},
        retrieval: _base?.retrieval ?? const {},
        access: _base?.access ?? const {},
      ));
      ref.invalidate(knowledgeSourcesProvider);
      _form.saving = false;
      _form.markClean(baseVersion: saved.version);
      _uploadedHere.clear();
      if (!mounted) return;
      final what = _editing ? 'Published ${saved.id} v${saved.version}' : 'Created ${saved.id}';
      showToast(context, _kind == 'UPLOADED' ? '$what · indexing in the background' : '$what · re-index it to make it searchable');
      Navigator.of(context).pop();
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(e);
    }
  }

  Future<void> _reindex() async {
    final base = _base!;
    final ok = await confirmAction(
      context,
      title: 'Re-index “${base.name}”?',
      message: 'This re-reads ${_contentSummary(base).toLowerCase()} of v${base.version} and rebuilds its search index now. '
          '${_form.isDirty ? 'It uses the saved version, not your unsaved changes. ' : ''}'
          'Answers keep citing the current index until it finishes.',
      confirmLabel: 'Re-index',
    );
    if (!ok || !mounted) return;
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    setState(() => _reindexing = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      final r = await api.reindexKnowledgeSource(tenant: tenant, id: base.id);
      ref.invalidate(knowledgeSourcesProvider);
      if (!mounted) return;
      if (r.error != null) {
        showToast(context, 'Re-index of ${base.id} failed: ${r.error}', error: true);
      } else {
        showToast(context, 'Re-indexed ${base.id}: ${r.totalDocs} docs, ${r.totalChunks} chunks (+${r.chunksAdded} new)');
      }
    } catch (e) {
      if (mounted) showToast(context, 'Re-index of ${base.id} failed: ${formatError(e)}', error: true);
    } finally {
      if (mounted) setState(() => _reindexing = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final summary = _base == null
        ? null
        : ref.watch(knowledgeSummariesProvider).valueOrNull?.where((s) => s.latest.id == _base!.id).firstOrNull;
    final live = summary?.latest ?? _base;
    final next = (_form.baseVersion ?? 0) + 1;
    final status = live == null ? null : knowledgeStatusOf(live);

    return YUnsavedGuard(
      form: _form,
      child: YSaveShortcut(
        onSave: _save,
        child: YSheetScaffold(
          title: _editing ? (_base!.name.isEmpty ? _base!.id : _base!.name) : 'New knowledge source',
          subtitle: _editing
              ? '${_base!.id} · v${live!.version} is current. Saving publishes v$next and indexes it; profiles bound to this source pick it up.'
              : 'Fields marked * are required.',
          headerActions: [
            if (_editing)
              Padding(
                padding: const EdgeInsets.only(right: 8),
                child: OutlinedButton.icon(
                  onPressed: _reindexing || (status?.inFlight ?? false) ? null : _reindex,
                  icon: _reindexing
                      ? const SizedBox.square(dimension: 16, child: CircularProgressIndicator(strokeWidth: 2))
                      : const Icon(Icons.refresh, size: 18),
                  label: Text(_reindexing ? 'Re-indexing…' : 'Re-index'),
                ),
              ),
          ],
          body: ListView(
            padding: const EdgeInsets.fromLTRB(28, 20, 28, 28),
            children: [
              if (_editing)
                LiveConflictBanner(
                  kind: AdminResourceKind.knowledgeSource,
                  id: _base!.id,
                  baseVersion: _form.baseVersion,
                  dirty: _form.isDirty,
                  noun: 'This source',
                  onKeepMine: (v) => setState(() => _form.baseVersion = v),
                ),
              if (_form.summary.isNotEmpty || _form.formError != null) ...[
                YErrorSummary(form: _form, action: _editing ? 'publishing' : 'creating this source'),
                const SizedBox(height: Gap.lg),
              ],
              if (live != null && status != null) ...[
                _StatusPanel(source: live, status: status),
                const SizedBox(height: Gap.xl),
              ],
              if (_base != null && _definedInCode(_base!)) ...[
                const YBanner(
                  tone: YBannerTone.info,
                  title: 'This source’s content is built into the backend.',
                  message: 'It can’t be shown here. Publishing a new version replaces it with the content below.',
                ),
                const SizedBox(height: Gap.lg),
              ],
              if (_legacyKind) ...[
                const YBanner(
                  tone: YBannerTone.warning,
                  title: 'This source reads a server path.',
                  message: 'That is no longer supported, so it can’t be indexed. Upload the files (or paste the text) and publish a working version.',
                ),
                const SizedBox(height: Gap.lg),
              ],
              YTextField(
                label: 'Source ID',
                wireKey: 'id',
                controller: _id,
                form: _form,
                name: 'id',
                required: !_editing,
                readOnly: _editing,
                autofocus: !_editing,
                mono: true,
                hint: 'e.g. return-policy',
                helper: _editing ? 'Fixed — profiles refer to the source by this ID.' : 'Profiles refer to the source by this ID; it can’t change later.',
              ),
              const SizedBox(height: Gap.fieldGap),
              YTextField(
                label: 'Name',
                wireKey: 'name',
                controller: _name,
                form: _form,
                name: 'name',
                required: true,
                autofocus: _editing,
                hint: 'e.g. Return policy 2026',
                helper: 'How operators recognise it in lists and pickers.',
              ),
              const SizedBox(height: Gap.fieldGap),
              YEnumCards<String>(
                label: 'Content',
                wireKey: 'locationKind',
                required: true,
                horizontal: true,
                form: _form,
                name: 'locationKind',
                value: _kind,
                options: const [
                  YEnumOption('UPLOADED', 'Upload files', wire: 'UPLOADED',
                      description: 'PDF, Word, PowerPoint, Excel, HTML, Markdown or text. Indexes automatically in the background.'),
                  YEnumOption('INLINE', 'Paste text', wire: 'INLINE',
                      description: 'Markdown or plain text typed here. Re-index after saving to make it searchable.'),
                ],
                onChanged: (v) {
                  setState(() => _kind = v);
                  _form.refresh();
                },
              ),
              const SizedBox(height: Gap.fieldGap),
              if (_kind == 'UPLOADED')
                YFieldShell(
                  label: 'Files',
                  wireKey: 'location.files',
                  form: _form,
                  name: 'files',
                  required: true,
                  error: _uploadError ?? _form.errorFor('files'),
                  helper: 'Files are scanned and stored as soon as you pick them; they become part of the source when you save.',
                  child: _UploadZone(files: _files, uploading: _uploading, onPick: _pickAndUpload, onRemove: _removeFile),
                )
              else
                YTextField(
                  label: 'Text',
                  wireKey: 'location.docs[0].text',
                  controller: _inline,
                  form: _form,
                  name: 'inlineText',
                  required: true,
                  mono: true,
                  maxLines: 16,
                  minLines: 8,
                  helper: 'Markdown headings help the index split it into sections the model can cite.',
                ),
              if (_editing && (live!.ingestion.isNotEmpty || live.retrieval.isNotEmpty)) ...[
                const SizedBox(height: Gap.lg),
                Text('Chunking and retrieval settings carry over to the new version unchanged.',
                    style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant)),
              ],
            ],
          ),
          footer: YActionBar(
            form: _form,
            primaryLabel: _editing ? 'Publish v$next' : 'Create source',
            onPrimary: _save,
            onCancel: () => closeYSheet(context),
          ),
        ),
      ),
    );
  }
}

class _StatusPanel extends StatelessWidget {
  const _StatusPanel({required this.source, required this.status});
  final KnowledgeSourceResponse source;
  final KnowledgeStatus status;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final facts = [
      '${source.docCount} ${source.docCount == 1 ? 'doc' : 'docs'} · ${source.chunkCount} ${source.chunkCount == 1 ? 'chunk' : 'chunks'}',
      source.lastIndexedAt == null ? 'Never indexed' : 'Indexed ${formatRelativeIso(source.lastIndexedAt).toLowerCase()}',
    ];
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: scheme.outlineVariant),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Wrap(spacing: 12, runSpacing: 6, crossAxisAlignment: WrapCrossAlignment.center, children: [
          YBadge(status.label, tone: status.tone, icon: status.icon),
          Text('v${source.version}', style: monoStyle(context, size: 13, weight: FontWeight.w500)),
          for (final f in facts) Text(f, style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant)),
        ]),
        if (status.detail != null)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: SelectableText(status.detail!,
                style: TextStyle(
                    fontSize: 13,
                    height: 1.45,
                    color: status.phase == KnowledgePhase.failed ? scheme.error : scheme.onSurfaceVariant)),
          ),
        if (status.inFlight)
          const Padding(padding: EdgeInsets.only(top: 10), child: LinearProgressIndicator(minHeight: 3)),
      ]),
    );
  }
}

/// Picker button + the files already uploaded, each removable.
class _UploadZone extends StatelessWidget {
  const _UploadZone({required this.files, required this.uploading, required this.onPick, required this.onRemove});
  final List<UploadedFileRef> files;
  final bool uploading;
  final VoidCallback onPick;
  final ValueChanged<UploadedFileRef> onRemove;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final status = StatusColors.of(context);
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: status.inputBorder),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        for (final f in files)
          Padding(
            padding: const EdgeInsets.only(bottom: 6),
            child: Row(children: [
              Icon(Icons.description_outlined, size: 18, color: scheme.onSurfaceVariant),
              const SizedBox(width: 8),
              Expanded(child: Text(f.filename, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 14))),
              Text(f.prettySize, style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant)),
              IconButton(
                tooltip: 'Remove ${f.filename}',
                icon: const Icon(Icons.close, size: 18),
                onPressed: () => onRemove(f),
              ),
            ]),
          ),
        Padding(
          padding: EdgeInsets.only(top: files.isEmpty ? 12 : 4, bottom: files.isEmpty ? 12 : 0),
          child: Column(children: [
            if (files.isEmpty) ...[
              Icon(Icons.upload_file_outlined, color: scheme.onSurfaceVariant, size: 32),
              const SizedBox(height: 6),
              Text('PDF, Word, PowerPoint, Excel, HTML, Markdown or text',
                  style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13)),
              const SizedBox(height: 10),
            ],
            FilledButton.tonalIcon(
              onPressed: uploading ? null : onPick,
              icon: uploading
                  ? const SizedBox.square(dimension: 16, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.attach_file, size: 18),
              label: Text(uploading ? 'Uploading…' : (files.isEmpty ? 'Choose files…' : 'Add more files…')),
            ),
          ]),
        ),
      ]),
    );
  }
}
