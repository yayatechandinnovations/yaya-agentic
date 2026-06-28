import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../application/workspaces_controller.dart';
import '../data/workspaces_api.dart';

/// Create-a-new-workspace dialog. Returns the new workspace's code on
/// success so the caller can switch to it.
///
/// `Code` is optional and starts hidden behind a "use a custom code"
/// toggle. When the operator opens it, they can type the slug verbatim —
/// useful when the workspace code must match an external integration's
/// account identifier. Validation mirrors the backend (lowercase ASCII +
/// internal dashes, 2–32 chars, no reserved words).
class CreateWorkspaceDialog extends ConsumerStatefulWidget {
  const CreateWorkspaceDialog({super.key});

  @override
  ConsumerState<CreateWorkspaceDialog> createState() =>
      _CreateWorkspaceDialogState();
}

class _CreateWorkspaceDialogState extends ConsumerState<CreateWorkspaceDialog> {
  // Same shape the backend enforces (WorkspaceSlugger#validateExplicit).
  // Keeping it client-side too gives the operator instant feedback before
  // the round-trip.
  static final RegExp _codePattern =
      RegExp(r'^[a-z0-9]+(?:-[a-z0-9]+)*$');

  final _formKey = GlobalKey<FormState>();
  final _displayName = TextEditingController();
  final _code = TextEditingController();
  bool _useCustomCode = false;
  bool _saving = false;
  String? _error;
  String? _codeError;

  @override
  void dispose() {
    _displayName.dispose();
    _code.dispose();
    super.dispose();
  }

  String? _validateCode(String? raw) {
    if (!_useCustomCode) return null;
    final v = raw?.trim() ?? '';
    if (v.isEmpty) return 'Required when "use a custom code" is on';
    if (v.length < 2 || v.length > 32) return 'Must be 2–32 characters';
    if (!_codePattern.hasMatch(v)) {
      return 'Lowercase letters, digits, single internal dashes only';
    }
    return null;
  }

  Future<void> _submit() async {
    if (_saving) return;
    setState(() => _codeError = null);
    if (!(_formKey.currentState?.validate() ?? false)) return;
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final api = await ref.read(workspacesApiProvider.future);
      final created = await api.create(
        _displayName.text.trim(),
        code: _useCustomCode ? _code.text.trim() : null,
      );
      // Refresh the membership list so the new workspace is visible
      // everywhere it's rendered (switcher, list page).
      ref.invalidate(myWorkspacesProvider);
      if (!mounted) return;
      Navigator.of(context).pop<String>(created.code);
    } on WorkspaceApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        if (e.isCodeTaken) {
          // Bind to the field, not the dialog-wide error band — the
          // operator just needs to pick a different slug.
          _codeError = e.message;
        } else {
          _error = e.message;
        }
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
    return AlertDialog(
      title: const Text('Create a workspace'),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 380),
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextFormField(
                controller: _displayName,
                autofocus: true,
                decoration: const InputDecoration(
                  labelText: 'Display name',
                  hintText: 'Acme Co',
                ),
                onFieldSubmitted: (_) => _submit(),
                validator: (v) =>
                    (v == null || v.trim().isEmpty) ? 'Required' : null,
              ),
              const SizedBox(height: 8),
              if (!_useCustomCode)
                Align(
                  alignment: Alignment.centerLeft,
                  child: TextButton.icon(
                    onPressed: _saving
                        ? null
                        : () => setState(() => _useCustomCode = true),
                    icon: const Icon(Icons.edit_outlined, size: 16),
                    label: const Text('Use a custom code'),
                  ),
                ),
              if (_useCustomCode) ...[
                TextFormField(
                  controller: _code,
                  decoration: InputDecoration(
                    labelText: 'Workspace code',
                    hintText: 'acme-co',
                    errorText: _codeError,
                    helperText:
                        'Used in URLs and audit logs. Lowercase letters, digits, internal dashes. 2–32 chars.',
                    helperMaxLines: 2,
                    suffixIcon: IconButton(
                      tooltip: 'Use auto-generated slug',
                      icon: const Icon(Icons.close, size: 18),
                      onPressed: _saving
                          ? null
                          : () {
                              setState(() {
                                _useCustomCode = false;
                                _code.clear();
                                _codeError = null;
                              });
                            },
                    ),
                  ),
                  inputFormatters: [
                    FilteringTextInputFormatter.allow(RegExp(r'[a-z0-9-]')),
                    LengthLimitingTextInputFormatter(32),
                  ],
                  onChanged: (_) {
                    if (_codeError != null) {
                      setState(() => _codeError = null);
                    }
                  },
                  onFieldSubmitted: (_) => _submit(),
                  validator: _validateCode,
                ),
              ] else
                Text(
                  "A unique URL slug is generated from this — you'll be the OWNER.",
                  style: theme.textTheme.bodySmall
                      ?.copyWith(color: theme.colorScheme.outline),
                ),
              if (_error != null) const SizedBox(height: 12),
              if (_error != null)
                Text(_error!,
                    style: TextStyle(color: theme.colorScheme.error)),
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
}
