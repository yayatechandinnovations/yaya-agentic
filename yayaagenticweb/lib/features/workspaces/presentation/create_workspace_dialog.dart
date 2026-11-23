import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../ui/data/y_sheet.dart';
import '../../../ui/feedback/api_error.dart';
import '../../../ui/form/unsaved_guard.dart';
import '../../../ui/form/y_fields.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/tokens.dart';
import '../../admin/tenants/tenant_validators.dart';
import '../application/workspaces_controller.dart';
import '../data/workspaces_api.dart';

/// Backend `WorkspaceSlugger` rules for an explicit code.
abstract final class WorkspaceCodeRules {
  static final pattern = RegExp(r'^[a-z0-9]+(?:-[a-z0-9]+)*$');
  static const reserved = {
    'admin', 'api', 'auth', 'health', 'system', 'static', 'public',
    'www', 'signup', 'login', 'logout', 'verify', 'reset',
    'accept-invitation', 'me', 'workspaces', 'platform-settings',
    'audit', 'metrics', 'status', 'version', 'root', 'support', 'billing',
  };

  static String? validate(String raw) {
    final v = raw.trim();
    if (v.isEmpty) return 'Enter a code, or switch back to an automatic one.';
    if (v.length < 2 || v.length > 32) return 'Use 2–32 characters.';
    if (!pattern.hasMatch(v)) return 'Lowercase letters and digits, with single dashes between them.';
    if (reserved.contains(v)) return '“$v” is reserved. Pick another code.';
    return null;
  }
}

const _labels = {
  'displayName': 'Workspace name',
  'code': 'Workspace code',
  'hostBaseUrl': 'Host base URL',
  'hostBaseUrlAllowlist': 'Per-call override hosts',
  'inboundOriginAllowlist': 'Allowed browser origins',
};

/// Create a workspace; pops with the new workspace's code. Renders as a
/// dialog by default (the shell's "New workspace…") or as side-sheet
/// content with [asSheet] (the workspaces page).
class CreateWorkspaceDialog extends ConsumerStatefulWidget {
  const CreateWorkspaceDialog({super.key, this.asSheet = false});
  final bool asSheet;

  @override
  ConsumerState<CreateWorkspaceDialog> createState() => _CreateWorkspaceDialogState();
}

class _CreateWorkspaceDialogState extends ConsumerState<CreateWorkspaceDialog> {
  final _displayName = TextEditingController();
  final _code = TextEditingController();
  final _hostBase = TextEditingController();
  final _hostAllowlist = TextEditingController();
  final _inboundOrigins = TextEditingController();
  bool _useCustomCode = false;
  bool _showUrlConfig = false;
  late final YFormController _form;

  @override
  void initState() {
    super.initState();
    _form = YFormController(
      snapshot: () => {
        'displayName': _displayName.text.trim(),
        'useCustomCode': _useCustomCode,
        'code': _code.text.trim(),
        'hostBaseUrl': _hostBase.text.trim(),
        'hostBaseUrlAllowlist': _hostAllowlist.text.trim(),
        'inboundOriginAllowlist': _inboundOrigins.text.trim(),
      },
      validate: _validate,
      labels: _labels,
      draftKey: 'yaya.draft.workspace.mine.new',
    );
    _form.addListener(_rebuild);
    _form.loadDraft().then((d) {
      if (d == null || !mounted) return;
      final v = d.values;
      _displayName.text = (v['displayName'] as String?) ?? '';
      _useCustomCode = v['useCustomCode'] == true;
      _code.text = (v['code'] as String?) ?? '';
      _hostBase.text = (v['hostBaseUrl'] as String?) ?? '';
      _hostAllowlist.text = (v['hostBaseUrlAllowlist'] as String?) ?? '';
      _inboundOrigins.text = (v['inboundOriginAllowlist'] as String?) ?? '';
      _showUrlConfig = [_hostBase, _hostAllowlist, _inboundOrigins].any((c) => c.text.isNotEmpty);
      _form.refresh();
    });
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  // A new workspace starts with "Require HTTPS" on, so its URLs must be https.
  Map<String, String?> _validate() => {
        'displayName': Validators.required(_displayName.text, 'a workspace name'),
        'code': _useCustomCode ? WorkspaceCodeRules.validate(_code.text) : null,
        'hostBaseUrl': _hostBase.text.trim().isEmpty
            ? null
            : TenantValidators.hostBaseUrl(_hostBase.text, requireHttps: true),
        'hostBaseUrlAllowlist': TenantValidators.lines(
            _hostAllowlist.text, (l) => TenantValidators.urlPattern(l, requireHttps: true)),
        'inboundOriginAllowlist': TenantValidators.lines(
            _inboundOrigins.text, (l) => TenantValidators.originPattern(l, requireHttps: true)),
      };

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _form.dispose();
    for (final c in [_displayName, _code, _hostBase, _hostAllowlist, _inboundOrigins]) {
      c.dispose();
    }
    super.dispose();
  }

  static List<String>? _list(String raw) {
    final l = TenantValidators.splitLines(raw);
    return l.isEmpty ? null : l;
  }

  Future<void> _submit() async {
    if (!_form.submit()) return;
    _form.saving = true;
    try {
      final api = await ref.read(workspacesApiProvider.future);
      final created = await api.create(
        _displayName.text.trim(),
        code: _useCustomCode ? _code.text.trim() : null,
        hostBaseUrl: _hostBase.text.trim().isEmpty ? null : _hostBase.text.trim(),
        hostBaseUrlAllowlist: _list(_hostAllowlist.text),
        inboundOriginAllowlist: _list(_inboundOrigins.text),
      );
      ref.invalidate(myWorkspacesProvider);
      _form.saving = false;
      _form.markClean();
      if (!mounted) return;
      Navigator.of(context).pop<String>(created.code);
    } on WorkspaceApiException catch (e) {
      _form.saving = false;
      _form.applyServerError(ApiErrorInfo(
        message: e.message,
        code: e.errorCode,
        status: e.statusCode,
        fieldErrors: e.isCodeTaken ? {'code': 'This code is already taken. Pick another.'} : const {},
      ));
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(e);
    }
  }

  List<Widget> _fields(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return [
      if (_form.summary.isNotEmpty || _form.formError != null) ...[
        YErrorSummary(form: _form, action: 'creating the workspace'),
        const SizedBox(height: Gap.lg),
      ],
      YTextField(
        label: _labels['displayName']!,
        wireKey: 'displayName',
        controller: _displayName,
        form: _form,
        name: 'displayName',
        required: true,
        autofocus: true,
        hint: 'e.g. Acme Co',
        helper: _useCustomCode ? 'What teammates see in the workspace switcher.' : 'A URL code is generated from this. You become the owner.',
        onSubmitted: (_) => _submit(),
      ),
      const SizedBox(height: Gap.fieldGap),
      YSwitchField(
        title: 'Choose the workspace code myself',
        description: 'Useful when the code must match an account ID in another system. It can’t change later.',
        value: _useCustomCode,
        onChanged: (v) {
          setState(() => _useCustomCode = v);
          _form.refresh();
        },
      ),
      if (_useCustomCode) ...[
        const SizedBox(height: Gap.md),
        YTextField(
          label: _labels['code']!,
          wireKey: 'code',
          controller: _code,
          form: _form,
          name: 'code',
          required: true,
          mono: true,
          hint: 'e.g. acme-co',
          helper: 'Used in URLs and audit logs. 2–32 lowercase letters, digits and single dashes.',
          inputFormatters: [
            FilteringTextInputFormatter.allow(RegExp(r'[a-z0-9-]')),
            LengthLimitingTextInputFormatter(32),
          ],
          onSubmitted: (_) => _submit(),
        ),
      ],
      const SizedBox(height: Gap.lg),
      YSwitchField(
        title: 'Set up host URLs now',
        description: 'Needed before HTTP tools or an HTTP auth resolver can run. You can also do it later in the workspace’s settings.',
        value: _showUrlConfig,
        onChanged: (v) => setState(() => _showUrlConfig = v),
      ),
      if (_showUrlConfig) ...[
        const SizedBox(height: Gap.md),
        YTextField(
          label: _labels['hostBaseUrl']!,
          wireKey: 'hostBaseUrl',
          controller: _hostBase,
          form: _form,
          name: 'hostBaseUrl',
          optional: true,
          mono: true,
          keyboardType: TextInputType.url,
          hint: 'e.g. https://api.acme.com',
          helper: 'Outbound: HTTP tools declare paths only; they’re joined onto this URL. Must be https://.',
        ),
        const SizedBox(height: Gap.fieldGap),
        YTextField(
          label: _labels['hostBaseUrlAllowlist']!,
          wireKey: 'hostBaseUrlAllowlist',
          controller: _hostAllowlist,
          form: _form,
          name: 'hostBaseUrlAllowlist',
          optional: true,
          mono: true,
          maxLines: 4,
          minLines: 2,
          hint: 'https://*.acme.com',
          helper: 'One pattern per line, * allowed. Lets the X-Yaya-Host-Base-Url header switch hosts for a single call.',
        ),
        const SizedBox(height: Gap.fieldGap),
        YTextField(
          label: _labels['inboundOriginAllowlist']!,
          wireKey: 'inboundOriginAllowlist',
          controller: _inboundOrigins,
          form: _form,
          name: 'inboundOriginAllowlist',
          optional: true,
          mono: true,
          maxLines: 4,
          minLines: 2,
          hint: 'https://shop.acme.com',
          helper: 'Inbound: browser origins (scheme://host[:port]) allowed to start chat sessions.',
        ),
      ],
      if (!_showUrlConfig && [_hostBase, _hostAllowlist, _inboundOrigins].any((c) => c.text.trim().isNotEmpty))
        Padding(
          padding: const EdgeInsets.only(top: 6),
          child: Text('Host URLs you entered are kept and will be saved.',
              style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant)),
        ),
    ];
  }

  @override
  Widget build(BuildContext context) {
    final bar = YActionBar(
      form: _form,
      primaryLabel: 'Create workspace',
      onPrimary: _submit,
      onCancel: () => closeYSheet(context),
    );
    final body = ListView(
      shrinkWrap: !widget.asSheet,
      padding: const EdgeInsets.fromLTRB(28, 20, 28, 28),
      children: _fields(context),
    );
    final content = widget.asSheet
        ? YSheetScaffold(
            title: 'New workspace',
            subtitle: 'A workspace is a team’s own space: its profiles, tools, knowledge, members and keys. Fields marked * are required.',
            body: body,
            footer: bar,
          )
        : Dialog(
            clipBehavior: Clip.antiAlias,
            child: ConstrainedBox(
              constraints: BoxConstraints(maxWidth: 560, maxHeight: MediaQuery.of(context).size.height * 0.85),
              child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(28, 22, 28, 0),
                  child: Text('New workspace', style: Theme.of(context).textTheme.titleLarge),
                ),
                Flexible(child: body),
                bar,
              ]),
            ),
          );
    return YUnsavedGuard(form: _form, child: YSaveShortcut(onSave: _submit, child: content));
  }
}
