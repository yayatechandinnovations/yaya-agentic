import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../models/admin/api_key.dart';
import '../../../ui/data/y_sheet.dart';
import '../../../ui/feedback/api_error.dart';
import '../../../ui/form/unsaved_guard.dart';
import '../../../ui/form/y_fields.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/tokens.dart';
import '../../workspaces/presentation/role_chip.dart';
import '../application/api_keys_controller.dart';
import '../data/api_keys_api.dart';

/// Backend `ApiKeyService` name bounds.
String? validateKeyName(String raw) {
  final t = raw.trim();
  if (t.isEmpty) return 'Enter a name, e.g. what will use the key.';
  if (t.length < 3) return 'Use at least 3 characters.';
  if (t.length > 64) return 'Use at most 64 characters.';
  return null;
}

enum KeyExpiry { never, d30, d90, y1, custom }

/// Mint a key: name, role (bounded by the creator's own tier) and expiry.
/// Pops with the [ApiKeyCreated] so the caller shows the one-time secret.
class CreateApiKeySheet extends ConsumerStatefulWidget {
  const CreateApiKeySheet({super.key, required this.workspaceCode, required this.creatorRole});
  final String workspaceCode;
  final String creatorRole;

  @override
  ConsumerState<CreateApiKeySheet> createState() => _CreateApiKeySheetState();
}

class _CreateApiKeySheetState extends ConsumerState<CreateApiKeySheet> {
  final _name = TextEditingController();
  late String _role;
  KeyExpiry _expiry = KeyExpiry.d90;
  DateTime? _customDate;
  late final YFormController _form;

  List<String> get _roles => mintableRoles(widget.creatorRole);

  @override
  void initState() {
    super.initState();
    // MEMBER writes resources but can't manage secrets — the safe default.
    _role = _roles.contains('MEMBER') ? 'MEMBER' : _roles.last;
    _form = YFormController(
      snapshot: () => {
        'name': _name.text.trim(),
        'role': _role,
        'expiry': _expiry.name,
        'customDate': _customDate?.toIso8601String(),
      },
      validate: () => {
        'name': validateKeyName(_name.text),
        'expiresAt': _expiry == KeyExpiry.custom && _customDate == null ? 'Pick the date the key stops working.' : null,
      },
      labels: const {'name': 'Name', 'role': 'Role', 'expiresAt': 'Expires'},
      draftKey: 'yaya.draft.apiKey.${widget.workspaceCode}.new',
    );
    _form.addListener(_rebuild);
    _form.loadDraft().then((d) {
      if (d == null || !mounted) return;
      final v = d.values;
      _name.text = (v['name'] as String?) ?? '';
      if (v['role'] is String && _roles.contains(v['role'])) _role = v['role'] as String;
      _expiry = KeyExpiry.values.where((e) => e.name == v['expiry']).firstOrNull ?? _expiry;
      _customDate = DateTime.tryParse('${v['customDate']}');
      _form.refresh();
    });
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

  String? _expiresAt() {
    final now = DateTime.now().toUtc();
    return switch (_expiry) {
      KeyExpiry.never => null,
      KeyExpiry.d30 => now.add(const Duration(days: 30)).toIso8601String(),
      KeyExpiry.d90 => now.add(const Duration(days: 90)).toIso8601String(),
      KeyExpiry.y1 => now.add(const Duration(days: 365)).toIso8601String(),
      KeyExpiry.custom => _customDate?.toUtc().toIso8601String(),
    };
  }

  Future<void> _pickDate() async {
    final now = DateTime.now();
    final picked = await showDatePicker(
      context: context,
      firstDate: now.add(const Duration(days: 1)),
      lastDate: now.add(const Duration(days: 3650)),
      initialDate: _customDate ?? now.add(const Duration(days: 30)),
    );
    if (picked == null) return;
    setState(() => _customDate = picked);
    _form.changed('expiresAt');
  }

  Future<void> _submit() async {
    if (!_form.submit()) return;
    _form.saving = true;
    try {
      final created = await ref.read(apiKeysControllerProvider).create(
            workspaceCode: widget.workspaceCode,
            name: _name.text.trim(),
            role: _role,
            expiresAt: _expiresAt(),
          );
      _form.saving = false;
      _form.markClean();
      if (mounted) Navigator.of(context).pop<ApiKeyCreated>(created);
    } on ApiKeyApiException catch (e) {
      _form.saving = false;
      _form.applyServerError(ApiErrorInfo(
        message: e.isCapReached ? 'This workspace has reached its limit of active keys. Revoke one you no longer use, then try again.' : e.message,
        code: e.errorCode,
        status: e.statusCode,
      ));
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(e);
    }
  }

  String _dateLabel(DateTime d) {
    String two(int n) => n.toString().padLeft(2, '0');
    final l = d.toLocal();
    return '${l.year}-${two(l.month)}-${two(l.day)}';
  }

  @override
  Widget build(BuildContext context) {
    final capped = widget.creatorRole != 'OWNER' && widget.creatorRole != 'PLATFORM_ADMIN';
    return YUnsavedGuard(
      form: _form,
      child: YSaveShortcut(
        onSave: _submit,
        child: YSheetScaffold(
          title: 'New API key',
          subtitle: 'For a script, service or CI job to call the admin API as this workspace. The secret is shown once, right after you create it.',
          body: ListView(
            padding: const EdgeInsets.fromLTRB(28, 20, 28, 28),
            children: [
              if (_form.summary.isNotEmpty || _form.formError != null) ...[
                YErrorSummary(form: _form, action: 'creating the key'),
                const SizedBox(height: Gap.lg),
              ],
              YTextField(
                label: 'Name',
                wireKey: 'name',
                controller: _name,
                form: _form,
                name: 'name',
                required: true,
                autofocus: true,
                hint: 'e.g. CI deploy bot',
                helper: 'For your own bookkeeping; shown in the list and in audit rows. 3–64 characters.',
                onSubmitted: (_) => _submit(),
              ),
              const SizedBox(height: Gap.fieldGap),
              YEnumCards<String>(
                label: 'Role',
                wireKey: 'role',
                form: _form,
                name: 'role',
                required: true,
                value: _role,
                options: roleOptions(_roles),
                helper: capped
                    ? 'A key can’t do more than you can, so ${roleWithArticle(widget.creatorRole)} can mint up to ${roleLabel(widget.creatorRole).toLowerCase()}.'
                    : 'Give the key the least it needs — anyone holding it acts with this role.',
                onChanged: (r) => setState(() => _role = r),
              ),
              const SizedBox(height: Gap.fieldGap),
              YEnumCards<KeyExpiry>(
                label: 'Expires',
                wireKey: 'expiresAt',
                form: _form,
                name: 'expiresAt',
                value: _expiry,
                onChanged: (e) {
                  setState(() => _expiry = e);
                  if (e == KeyExpiry.custom && _customDate == null) _pickDate();
                },
                options: [
                  const YEnumOption(KeyExpiry.d30, 'In 30 days', description: 'Good for a one-off migration or a trial integration.'),
                  const YEnumOption(KeyExpiry.d90, 'In 90 days', description: 'A sensible default; rotate it before it lapses.'),
                  const YEnumOption(KeyExpiry.y1, 'In 1 year'),
                  YEnumOption(
                    KeyExpiry.custom,
                    _customDate == null ? 'On a date I choose' : 'On ${_dateLabel(_customDate!)}',
                    description: 'Pick any day up to 10 years out.',
                  ),
                  const YEnumOption(KeyExpiry.never, 'Never', caution: true, description: 'Works until someone revokes it. A leaked key stays usable indefinitely.'),
                ],
              ),
              if (_expiry == KeyExpiry.custom)
                Align(
                  alignment: Alignment.centerLeft,
                  child: Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: OutlinedButton.icon(
                      onPressed: _pickDate,
                      icon: const Icon(Icons.calendar_today, size: 16),
                      label: Text(_customDate == null ? 'Pick a date' : 'Change date (${_dateLabel(_customDate!)})'),
                    ),
                  ),
                ),
            ],
          ),
          footer: YActionBar(
            form: _form,
            primaryLabel: 'Create key',
            onPrimary: _submit,
            onCancel: () => closeYSheet(context),
          ),
        ),
      ),
    );
  }
}
