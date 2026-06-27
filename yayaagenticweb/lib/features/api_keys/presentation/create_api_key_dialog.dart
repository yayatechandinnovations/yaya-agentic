import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../models/admin/api_key.dart';
import '../application/api_keys_controller.dart';
import '../data/api_keys_api.dart';

/// Create-key modal (design §3.1): name, role (bounded by the creator's tier),
/// and an optional expiry. On success pops with the [ApiKeyCreated] payload so
/// the caller can render the show-once secret panel.
class CreateApiKeyDialog extends ConsumerStatefulWidget {
  const CreateApiKeyDialog({
    super.key,
    required this.workspaceCode,
    required this.creatorRole,
  });

  final String workspaceCode;
  final String creatorRole;

  @override
  ConsumerState<CreateApiKeyDialog> createState() => _CreateApiKeyDialogState();
}

enum _Expiry { never, d30, d90, y1, custom }

class _CreateApiKeyDialogState extends ConsumerState<CreateApiKeyDialog> {
  final _formKey = GlobalKey<FormState>();
  final _name = TextEditingController();
  late String _role;
  _Expiry _expiry = _Expiry.never;
  DateTime? _customDate;
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    final options = mintableRoles(widget.creatorRole);
    // Default to MEMBER when allowed (writes resources, can't manage secrets);
    // otherwise the lowest tier the creator can mint.
    _role = options.contains('MEMBER') ? 'MEMBER' : options.last;
  }

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  String? _resolveExpiresAt() {
    final now = DateTime.now().toUtc();
    switch (_expiry) {
      case _Expiry.never:
        return null;
      case _Expiry.d30:
        return now.add(const Duration(days: 30)).toIso8601String();
      case _Expiry.d90:
        return now.add(const Duration(days: 90)).toIso8601String();
      case _Expiry.y1:
        return now.add(const Duration(days: 365)).toIso8601String();
      case _Expiry.custom:
        return _customDate?.toUtc().toIso8601String();
    }
  }

  Future<void> _submit() async {
    if (_saving) return;
    if (!(_formKey.currentState?.validate() ?? false)) return;
    if (_expiry == _Expiry.custom && _customDate == null) {
      setState(() => _error = 'Pick a custom expiry date.');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final created = await ref.read(apiKeysControllerProvider).create(
            workspaceCode: widget.workspaceCode,
            name: _name.text.trim(),
            role: _role,
            expiresAt: _resolveExpiresAt(),
          );
      if (!mounted) return;
      Navigator.of(context).pop<ApiKeyCreated>(created);
    } on ApiKeyApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        _error = e.isCapReached
            ? 'This workspace has hit its active-key cap. Revoke an old key first.'
            : e.message;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        _error = 'Create failed: $e';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final roles = mintableRoles(widget.creatorRole);

    return AlertDialog(
      title: const Text('Create API key'),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 420),
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextFormField(
                controller: _name,
                autofocus: true,
                maxLength: 64,
                decoration: const InputDecoration(
                  labelText: 'Name',
                  hintText: 'CI deploy bot',
                  helperText: 'For your own bookkeeping; shown in audit rows.',
                ),
                validator: (v) {
                  final t = (v ?? '').trim();
                  if (t.length < 3) return 'At least 3 characters';
                  if (t.length > 64) return 'At most 64 characters';
                  return null;
                },
              ),
              const SizedBox(height: 8),
              DropdownButtonFormField<String>(
                initialValue: _role,
                decoration: const InputDecoration(labelText: 'Role'),
                items: roles
                    .map((r) => DropdownMenuItem(value: r, child: Text(r)))
                    .toList(),
                onChanged: (v) => setState(() => _role = v ?? _role),
              ),
              Text(
                "Bounded by your own role. The key can't do more than this tier.",
                style: theme.textTheme.bodySmall
                    ?.copyWith(color: theme.colorScheme.outline),
              ),
              const SizedBox(height: 16),
              Text('Expires', style: theme.textTheme.labelLarge),
              _expiryRadio(_Expiry.never, 'Never'),
              _expiryRadio(_Expiry.d30, '30 days'),
              _expiryRadio(_Expiry.d90, '90 days'),
              _expiryRadio(_Expiry.y1, '1 year'),
              _expiryRadio(_Expiry.custom, 'Custom date'),
              if (_expiry == _Expiry.custom)
                Align(
                  alignment: Alignment.centerLeft,
                  child: TextButton.icon(
                    icon: const Icon(Icons.calendar_today, size: 16),
                    label: Text(_customDate == null
                        ? 'Pick a date'
                        : _customDate!.toLocal().toString().split(' ').first),
                    onPressed: () async {
                      final now = DateTime.now();
                      final picked = await showDatePicker(
                        context: context,
                        firstDate: now.add(const Duration(days: 1)),
                        lastDate: now.add(const Duration(days: 3650)),
                        initialDate: now.add(const Duration(days: 30)),
                      );
                      if (picked != null) setState(() => _customDate = picked);
                    },
                  ),
                ),
              if (_error != null) ...[
                const SizedBox(height: 12),
                Text(_error!, style: TextStyle(color: theme.colorScheme.error)),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _saving ? null : _submit,
          child: _saving
              ? const SizedBox(
                  height: 16, width: 16, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Create'),
        ),
      ],
    );
  }

  Widget _expiryRadio(_Expiry value, String label) {
    return RadioListTile<_Expiry>(
      value: value,
      groupValue: _expiry,
      onChanged: (v) => setState(() => _expiry = v ?? _expiry),
      title: Text(label),
      dense: true,
      contentPadding: EdgeInsets.zero,
      controlAffinity: ListTileControlAffinity.leading,
    );
  }
}
