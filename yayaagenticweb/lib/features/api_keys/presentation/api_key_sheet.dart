import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../models/admin/api_key.dart';
import '../../../ui/data/relative_time.dart';
import '../../../ui/data/y_badge.dart';
import '../../../ui/data/y_sheet.dart';
import '../../../ui/data/y_states.dart';
import '../../../ui/feedback/api_error.dart';
import '../../../ui/feedback/confirm.dart';
import '../../../ui/feedback/toast.dart';
import '../../../ui/form/unsaved_guard.dart';
import '../../../ui/form/y_fields.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/tokens.dart';
import '../../workspaces/presentation/role_chip.dart';
import '../application/api_keys_controller.dart';
import '../data/api_keys_api.dart';
import 'create_api_key_dialog.dart' show validateKeyName;

YBadge keyStatusBadge(String status) => switch (status) {
      'active' => const YBadge('Active', tone: YTone.success, icon: Icons.check_circle_outline),
      'revoked' => const YBadge('Revoked', icon: Icons.block),
      'expired' => const YBadge('Expired', tone: YTone.warning, icon: Icons.schedule),
      _ => YBadge(status),
    };

/// One key: details, rename, recent audit events, revoke.
class ApiKeySheet extends ConsumerStatefulWidget {
  const ApiKeySheet({super.key, required this.workspaceCode, required this.apiKey, required this.canManage});
  final String workspaceCode;
  final ApiKey apiKey;
  final bool canManage;

  @override
  ConsumerState<ApiKeySheet> createState() => _ApiKeySheetState();
}

class _ApiKeySheetState extends ConsumerState<ApiKeySheet> {
  final _name = TextEditingController();
  late final YFormController _form;
  bool _revoking = false;

  ApiKey get k => widget.apiKey;
  bool get _active => k.status == 'active';
  bool get _editable => widget.canManage && _active;

  @override
  void initState() {
    super.initState();
    _name.text = k.name;
    _form = YFormController(
      snapshot: () => {'name': _name.text.trim()},
      validate: () => {'name': validateKeyName(_name.text)},
      labels: const {'name': 'Name'},
      draftKey: _editable ? 'yaya.draft.apiKey.${widget.workspaceCode}.${k.id}' : null,
    );
    _form.addListener(_rebuild);
    if (_editable) {
      _form.loadDraft().then((d) {
        if (d == null || !mounted) return;
        _name.text = (d.values['name'] as String?) ?? _name.text;
        _form.refresh();
      });
    }
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _form.dispose();
    _name.dispose();
    super.dispose();
  }

  Future<void> _rename() async {
    if (!_form.submit()) return;
    if (_name.text.trim() == k.name) {
      Navigator.of(context).pop();
      return;
    }
    _form.saving = true;
    try {
      await ref.read(apiKeysControllerProvider).rename(workspaceCode: widget.workspaceCode, keyId: k.id, name: _name.text.trim());
      _form.saving = false;
      _form.markClean();
      if (!mounted) return;
      showToast(context, 'Renamed to ${_name.text.trim()}');
      Navigator.of(context).pop();
    } on ApiKeyApiException catch (e) {
      _form.saving = false;
      _form.applyServerError(ApiErrorInfo(message: e.message, code: e.errorCode, status: e.statusCode));
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(e);
    }
  }

  Future<void> _revoke() async {
    final ok = await confirmTyped(
      context,
      title: 'Revoke “${k.name}”?',
      message: 'Revoking ${k.keyPrefix}… is immediate and can’t be undone. Anything still using this key starts getting 401 Unauthorized.',
      expected: k.name,
      confirmLabel: 'Revoke key',
    );
    if (!ok) return;
    setState(() => _revoking = true);
    try {
      await ref.read(apiKeysControllerProvider).revoke(workspaceCode: widget.workspaceCode, keyId: k.id);
      _form.markClean();
      if (!mounted) return;
      showToast(context, 'Revoked ${k.name}');
      Navigator.of(context).pop();
    } catch (e) {
      if (!mounted) return;
      setState(() => _revoking = false);
      showToast(context, e is ApiKeyApiException ? e.message : ApiErrorInfo.from(e).message, error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    Widget row(String label, String value, {bool mono = false}) => Padding(
          padding: const EdgeInsets.only(bottom: 10),
          child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            SizedBox(width: 140, child: Text(label, style: TextStyle(fontSize: 14, color: scheme.onSurfaceVariant))),
            Expanded(
              child: SelectableText(value,
                  style: mono ? monoStyle(context) : TextStyle(fontSize: 14, color: scheme.onSurface)),
            ),
          ]),
        );
    return YUnsavedGuard(
      form: _form,
      child: YSaveShortcut(
        onSave: _rename,
        child: YSheetScaffold(
          title: k.name,
          subtitle: '${k.keyPrefix}… · created ${formatRelativeIso(k.createdAt)}${k.createdBy == null ? '' : ' by ${k.createdBy}'}',
          headerActions: [Padding(padding: const EdgeInsets.only(top: 6, right: 8), child: keyStatusBadge(k.status))],
          body: ListView(
            padding: const EdgeInsets.fromLTRB(28, 20, 28, 28),
            children: [
              if (_form.summary.isNotEmpty || _form.formError != null) ...[
                YErrorSummary(form: _form, action: 'renaming'),
                const SizedBox(height: Gap.lg),
              ],
              if (k.platformScope) ...[
                const YBanner(
                  tone: YBannerTone.warning,
                  title: 'Platform-wide key.',
                  message: 'This key acts with platform-admin reach across every workspace, not just this one.',
                ),
                const SizedBox(height: Gap.lg),
              ],
              YTextField(
                label: 'Name',
                wireKey: 'name',
                controller: _name,
                form: _form,
                name: 'name',
                required: _editable,
                readOnly: !_editable,
                helper: _editable
                    ? 'Renaming doesn’t change the secret; callers keep working.'
                    : !_active
                        ? 'Inactive keys can’t be renamed.'
                        : 'Only admins and owners can rename keys.',
                onSubmitted: (_) => _rename(),
              ),
              const SizedBox(height: Gap.xl),
              Row(children: [
                Text('Role', style: TextStyle(fontSize: 14, color: scheme.onSurfaceVariant)),
                const SizedBox(width: 12),
                RoleChip(k.role),
              ]),
              Padding(
                padding: const EdgeInsets.only(top: 4, bottom: Gap.md),
                child: Text(roleSummaries[k.role] ?? '', style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant)),
              ),
              row('Key prefix', '${k.keyPrefix}…', mono: true),
              row('Last used', k.lastUsedAt == null ? 'Never' : '${formatRelativeIso(k.lastUsedAt)}${k.lastUsedIp == null ? '' : ' from ${k.lastUsedIp}'}'),
              row('Expires', k.expiresAt == null ? 'Never' : '${formatRelativeIso(k.expiresAt)} (${k.expiresAt})'),
              row('Key ID', k.id, mono: true),
              if (widget.canManage) ...[
                const SizedBox(height: Gap.lg),
                Text('Recent activity', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: scheme.onSurface)),
                const SizedBox(height: Gap.sm),
                _AuditList(workspaceCode: widget.workspaceCode, keyId: k.id),
              ],
              if (_editable) ...[
                const SizedBox(height: Gap.xl),
                OutlinedButton.icon(
                  style: OutlinedButton.styleFrom(foregroundColor: scheme.error),
                  onPressed: _revoking ? null : _revoke,
                  icon: const Icon(Icons.block, size: 18),
                  label: const Text('Revoke key…'),
                ),
              ],
            ],
          ),
          footer: _editable
              ? YActionBar(form: _form, primaryLabel: 'Rename key', onPrimary: _rename, onCancel: () => closeYSheet(context))
              : null,
        ),
      ),
    );
  }
}

class _AuditList extends ConsumerWidget {
  const _AuditList({required this.workspaceCode, required this.keyId});
  final String workspaceCode;
  final String keyId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scheme = Theme.of(context).colorScheme;
    final events = ref.watch(apiKeyAuditProvider((workspaceCode, keyId)));
    return events.when(
      skipLoadingOnReload: true,
      loading: () => const YSkeletonRows(count: 3),
      error: (e, _) => YBanner(
        tone: YBannerTone.error,
        title: 'Couldn’t load activity.',
        message: e is ApiKeyApiException ? e.message : ApiErrorInfo.from(e).message,
      ),
      data: (rows) {
        if (rows.isEmpty) {
          return Text('Nothing recorded yet.', style: TextStyle(fontSize: 14, color: scheme.onSurfaceVariant));
        }
        return Column(children: [
          for (final e in rows)
            Container(
              padding: const EdgeInsets.symmetric(vertical: 8),
              decoration: BoxDecoration(border: Border(bottom: BorderSide(color: scheme.outlineVariant.withValues(alpha: 0.6)))),
              child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                SizedBox(
                  width: 120,
                  child: Align(
                    alignment: Alignment.centerLeft,
                    child: YBadge(
                      switch (e.kind) {
                        'CREATE' => 'Created',
                        'REVOKE' => 'Revoked',
                        'RENAME' => 'Renamed',
                        'USE_DENIED' => 'Use denied',
                        _ => e.kind,
                      },
                      tone: switch (e.kind) {
                        'CREATE' => YTone.success,
                        'REVOKE' => YTone.danger,
                        'USE_DENIED' => YTone.warning,
                        _ => YTone.neutral,
                      },
                    ),
                  ),
                ),
                Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Tooltip(
                      message: e.occurredAt ?? '',
                      child: Text(formatRelativeIso(e.occurredAt), style: TextStyle(fontSize: 14, color: scheme.onSurface)),
                    ),
                    if ((e.detail ?? '').isNotEmpty || (e.clientIp ?? '').isNotEmpty)
                      Text(
                        [if ((e.detail ?? '').isNotEmpty) e.detail!, if ((e.clientIp ?? '').isNotEmpty) 'from ${e.clientIp}'].join(' · '),
                        style: monoStyle(context, size: 12.5, color: scheme.onSurfaceVariant),
                      ),
                  ]),
                ),
              ]),
            ),
        ]);
      },
    );
  }
}
