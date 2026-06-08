import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../models/admin/operator_auth_strategies.dart';
import 'operator_auth_controller.dart';

/// Settings → Operator authentication.
///
/// Two cards (Bootstrap + HTTP delegate) plus a Test dialog that
/// renders the structured ProbeResult — exactly what the runtime
/// authenticator would compute against the same config.
class OperatorAuthScreen extends ConsumerStatefulWidget {
  const OperatorAuthScreen({super.key});

  @override
  ConsumerState<OperatorAuthScreen> createState() => _OperatorAuthScreenState();
}

class _OperatorAuthScreenState extends ConsumerState<OperatorAuthScreen> {
  // Bootstrap-card local state ----------------------------------------
  final _newPassword = TextEditingController();
  final _confirmPassword = TextEditingController();

  // Delegate-card local state (mirrors DelegateView; tied back on save)
  final _urlCtrl = TextEditingController();
  final _secretCtrl = TextEditingController();
  final _timeoutCtrl = TextEditingController();
  final _bodyTemplateCtrl = TextEditingController();
  final _jsonPathExistsCtrl = TextEditingController();
  final _subjectPathCtrl = TextEditingController();
  final _displayNamePathCtrl = TextEditingController();
  final _attributesPathCtrl = TextEditingController();
  final _reasonPathCtrl = TextEditingController();
  final _statusInCtrl = TextEditingController();   // comma list
  bool _delegateEnabled = false;
  bool _requireHttps = true;
  String _method = 'POST';
  String _bodyFormat = 'JSON';
  // Editable equals list — operator can add/remove rows.
  final List<({TextEditingController path, TextEditingController value})>
      _equalsRows = [];
  // Editable headers list.
  final List<({TextEditingController key, TextEditingController value})>
      _headerRows = [];

  bool _formHydrated = false;

  @override
  void dispose() {
    _newPassword.dispose();
    _confirmPassword.dispose();
    _urlCtrl.dispose();
    _secretCtrl.dispose();
    _timeoutCtrl.dispose();
    _bodyTemplateCtrl.dispose();
    _jsonPathExistsCtrl.dispose();
    _subjectPathCtrl.dispose();
    _displayNamePathCtrl.dispose();
    _attributesPathCtrl.dispose();
    _reasonPathCtrl.dispose();
    _statusInCtrl.dispose();
    for (final r in _equalsRows) { r.path.dispose(); r.value.dispose(); }
    for (final r in _headerRows) { r.key.dispose(); r.value.dispose(); }
    super.dispose();
  }

  void _hydrateFromData(DelegateView d) {
    _delegateEnabled = d.enabled;
    _requireHttps = d.requireHttps;
    _urlCtrl.text = d.url ?? '';
    _secretCtrl.text = '';      // never echo the secret; placeholder shows the mask
    _timeoutCtrl.text = d.timeoutMs.toString();
    _method = d.request.method;
    _bodyFormat = d.request.body.format;
    _bodyTemplateCtrl.text = d.request.body.template ?? '';
    _statusInCtrl.text = d.success.statusIn.join(',');
    _jsonPathExistsCtrl.text = d.success.jsonPathExists ?? '';
    _subjectPathCtrl.text = d.identity.subjectPath ?? '';
    _displayNamePathCtrl.text = d.identity.displayNamePath ?? '';
    _attributesPathCtrl.text = d.identity.attributesPath ?? '';
    _reasonPathCtrl.text = d.failure.reasonPath ?? '';

    for (final r in _equalsRows) { r.path.dispose(); r.value.dispose(); }
    _equalsRows
      ..clear()
      ..addAll(d.success.jsonPathEquals.map((e) => (
            path: TextEditingController(text: e.path),
            value: TextEditingController(text: e.value?.toString() ?? ''),
          )));

    for (final r in _headerRows) { r.key.dispose(); r.value.dispose(); }
    _headerRows
      ..clear()
      ..addAll(d.request.headers.entries.map((e) => (
            key: TextEditingController(text: e.key),
            value: TextEditingController(text: e.value),
          )));
  }

  DelegateView _buildDelegateView(DelegateView base) {
    final equals = _equalsRows
        .where((r) => r.path.text.trim().isNotEmpty)
        .map((r) {
          final raw = r.value.text;
          // Best-effort coerce: int → int, "true"/"false" → bool, "null" → null.
          dynamic v = raw.isEmpty ? '' : raw;
          if (raw == 'null') v = null;
          else if (raw == 'true') v = true;
          else if (raw == 'false') v = false;
          else {
            final asInt = int.tryParse(raw);
            if (asInt != null) v = asInt;
            else { final asD = double.tryParse(raw); if (asD != null) v = asD; }
          }
          return JsonPathEquals(path: r.path.text.trim(), value: v);
        })
        .toList();
    final headers = <String, String>{};
    for (final r in _headerRows) {
      if (r.key.text.trim().isNotEmpty) headers[r.key.text.trim()] = r.value.text;
    }
    final statusIn = _statusInCtrl.text
        .split(',')
        .map((s) => int.tryParse(s.trim()))
        .whereType<int>()
        .toList();

    return base.copyWith(
      enabled: _delegateEnabled,
      url: _urlCtrl.text.trim().isEmpty ? null : _urlCtrl.text.trim(),
      timeoutMs: int.tryParse(_timeoutCtrl.text) ?? 5000,
      requireHttps: _requireHttps,
      request: RequestShape(
        method: _method,
        headers: headers,
        body: RequestBody(
          format: _bodyFormat,
          template: _bodyTemplateCtrl.text.isEmpty ? null : _bodyTemplateCtrl.text,
        ),
      ),
      success: SuccessCriteria(
        statusIn: statusIn.isEmpty ? const [200, 204] : statusIn,
        jsonPathExists: _trimOrNull(_jsonPathExistsCtrl.text),
        jsonPathEquals: equals,
      ),
      identity: IdentityMapping(
        subjectPath: _trimOrNull(_subjectPathCtrl.text),
        displayNamePath: _trimOrNull(_displayNamePathCtrl.text),
        attributesPath: _trimOrNull(_attributesPathCtrl.text),
      ),
      failure: FailureMapping(
        reasonPath: _trimOrNull(_reasonPathCtrl.text),
      ),
    );
  }

  String? _trimOrNull(String s) {
    final t = s.trim();
    return t.isEmpty ? null : t;
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(operatorAuthProvider);
    final ctrl = ref.read(operatorAuthProvider.notifier);

    // Push toasts via listener so they don't replay on every rebuild.
    ref.listen<OperatorAuthState>(operatorAuthProvider, (prev, next) {
      if (next.error != null && next.error != prev?.error) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(next.error!),
          backgroundColor: Theme.of(context).colorScheme.errorContainer,
        ));
        ctrl.dismissError();
      }
      if (next.notice != null && next.notice != prev?.notice) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(next.notice!)));
        ctrl.dismissNotice();
      }
      if (next.lastProbe != null && next.lastProbe != prev?.lastProbe) {
        _showProbeDialog(next.lastProbe!);
      }
    });

    if (state.loading && state.data == null) {
      return const Center(child: CircularProgressIndicator());
    }
    final data = state.data;
    if (data == null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('Failed to load operator-auth strategies.'),
              const SizedBox(height: 12),
              FilledButton(onPressed: ctrl.load, child: const Text('Retry')),
            ],
          ),
        ),
      );
    }

    if (!_formHydrated) {
      _hydrateFromData(data.delegate);
      _formHydrated = true;
    }

    return SingleChildScrollView(
      padding: const EdgeInsets.all(24),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 920),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('Settings → Operator authentication',
                style: Theme.of(context).textTheme.bodySmall),
            const SizedBox(height: 4),
            Text('Operator login',
                style: Theme.of(context).textTheme.headlineSmall),
            const SizedBox(height: 16),
            _BootstrapCard(
              data: data,
              saving: state.savingBootstrap,
              newPassword: _newPassword,
              confirmPassword: _confirmPassword,
              onSavePassword: () {
                final np = _newPassword.text;
                if (np.isEmpty) return;
                if (np != _confirmPassword.text) {
                  ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
                      content: Text('Passwords do not match.')));
                  return;
                }
                ctrl.saveBootstrap(newPassword: np);
                _newPassword.clear();
                _confirmPassword.clear();
              },
              onToggleEnabled: (v) => ctrl.saveBootstrap(enabled: v),
            ),
            const SizedBox(height: 24),
            _DelegateCard(
              data: data,
              saving: state.savingDelegate,
              testing: state.testing,
              enabled: _delegateEnabled,
              onEnabledChanged: (v) => setState(() => _delegateEnabled = v),
              requireHttps: _requireHttps,
              onRequireHttpsChanged: (v) => setState(() => _requireHttps = v),
              method: _method,
              onMethodChanged: (v) => setState(() => _method = v),
              bodyFormat: _bodyFormat,
              onBodyFormatChanged: (v) => setState(() => _bodyFormat = v),
              urlCtrl: _urlCtrl,
              secretCtrl: _secretCtrl,
              timeoutCtrl: _timeoutCtrl,
              bodyTemplateCtrl: _bodyTemplateCtrl,
              statusInCtrl: _statusInCtrl,
              jsonPathExistsCtrl: _jsonPathExistsCtrl,
              subjectPathCtrl: _subjectPathCtrl,
              displayNamePathCtrl: _displayNamePathCtrl,
              attributesPathCtrl: _attributesPathCtrl,
              reasonPathCtrl: _reasonPathCtrl,
              equalsRows: _equalsRows,
              headerRows: _headerRows,
              addEqualsRow: () => setState(() => _equalsRows.add((
                    path: TextEditingController(),
                    value: TextEditingController(),
                  ))),
              removeEqualsRow: (i) => setState(() {
                final r = _equalsRows.removeAt(i);
                r.path.dispose();
                r.value.dispose();
              }),
              addHeaderRow: () => setState(() => _headerRows.add((
                    key: TextEditingController(),
                    value: TextEditingController(),
                  ))),
              removeHeaderRow: (i) => setState(() {
                final r = _headerRows.removeAt(i);
                r.key.dispose();
                r.value.dispose();
              }),
              onSave: ({bool confirmPermissive = false}) {
                final view = _buildDelegateView(data.delegate);
                final secret = _secretCtrl.text.trim();
                ctrl.saveDelegate(view,
                    sharedSecret: secret.isEmpty ? null : secret,
                    confirmPermissive: confirmPermissive);
              },
              onTest: () => _showTestDialog(context, ctrl),
            ),
          ],
        ),
      ),
    );
  }

  void _showTestDialog(BuildContext context, OperatorAuthController ctrl) {
    final user = TextEditingController();
    final pass = TextEditingController();
    showDialog<void>(
      context: context,
      builder: (ctx) => Consumer(builder: (ctx2, ref2, _) {
        final s = ref2.watch(operatorAuthProvider);
        return AlertDialog(
          title: const Text('Test HTTP delegate'),
          content: SizedBox(
            width: 480,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const Text('Posts these credentials to the delegate URL '
                    'and shows how your configured rules interpret the response.'),
                const SizedBox(height: 12),
                TextField(controller: user,
                    decoration: const InputDecoration(labelText: 'Test username')),
                const SizedBox(height: 8),
                TextField(controller: pass, obscureText: true,
                    decoration: const InputDecoration(labelText: 'Test password')),
                const SizedBox(height: 16),
                if (s.testing) const LinearProgressIndicator(),
                if (s.lastProbe != null) _ProbeResultView(probe: s.lastProbe!),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () { ctrl.dismissProbe(); Navigator.of(ctx).pop(); },
              child: const Text('Close'),
            ),
            FilledButton(
              onPressed: s.testing
                  ? null
                  : () => ctrl.runTest(username: user.text, password: pass.text),
              child: const Text('Run test'),
            ),
          ],
        );
      }),
    ).then((_) {
      user.dispose();
      pass.dispose();
    });
  }

  void _showProbeDialog(ProbeResult _) {
    // The dialog is already open with a Consumer that picks up lastProbe.
    // This hook stays in case we ever want a notification when a probe
    // result lands without the dialog being open.
  }
}

// ---------------------------------------------------------------------------
// Bootstrap card
// ---------------------------------------------------------------------------

class _BootstrapCard extends StatelessWidget {
  const _BootstrapCard({
    required this.data,
    required this.saving,
    required this.newPassword,
    required this.confirmPassword,
    required this.onSavePassword,
    required this.onToggleEnabled,
  });

  final StrategiesResponse data;
  final bool saving;
  final TextEditingController newPassword;
  final TextEditingController confirmPassword;
  final VoidCallback onSavePassword;
  final ValueChanged<bool> onToggleEnabled;

  @override
  Widget build(BuildContext context) {
    final canDisable = data.canDisableBootstrap;
    final enabled = data.bootstrap.enabled;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.key_outlined),
                const SizedBox(width: 8),
                Text('Bootstrap operator',
                    style: Theme.of(context).textTheme.titleMedium),
              ],
            ),
            const SizedBox(height: 12),
            ListTile(
              dense: true,
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.person_outline),
              title: Text(data.bootstrap.username,
                  style: const TextStyle(fontWeight: FontWeight.w500)),
              subtitle: const Text('Username (set via YAYA_BOOTSTRAP_USERNAME)'),
            ),
            const SizedBox(height: 8),
            if (!canDisable && enabled)
              Container(
                margin: const EdgeInsets.only(bottom: 12),
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.amber.shade100,
                  borderRadius: BorderRadius.circular(6),
                  border: Border.all(color: Colors.amber.shade700),
                ),
                child: const Text(
                  'Disabling bootstrap removes your break-glass account. '
                  'The toggle unlocks after at least one operator has signed in '
                  'through the HTTP delegate successfully.',
                ),
              ),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Enabled'),
              subtitle: Text(enabled
                  ? 'Bootstrap login is accepted as a fallback.'
                  : 'Bootstrap login is rejected.'),
              value: enabled,
              onChanged: saving || (!canDisable && enabled) ? null : onToggleEnabled,
            ),
            const Divider(height: 24),
            const Text('Change password',
                style: TextStyle(fontWeight: FontWeight.w500)),
            const SizedBox(height: 8),
            TextField(
              controller: newPassword,
              obscureText: true,
              decoration: const InputDecoration(labelText: 'New password'),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: confirmPassword,
              obscureText: true,
              decoration: const InputDecoration(labelText: 'Confirm new password'),
            ),
            const SizedBox(height: 12),
            Align(
              alignment: Alignment.centerLeft,
              child: FilledButton.icon(
                onPressed: saving ? null : onSavePassword,
                icon: const Icon(Icons.save_outlined),
                label: Text(saving ? 'Saving…' : 'Update password'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// HTTP delegate card
// ---------------------------------------------------------------------------

class _DelegateCard extends StatelessWidget {
  const _DelegateCard({
    required this.data,
    required this.saving,
    required this.testing,
    required this.enabled,
    required this.onEnabledChanged,
    required this.requireHttps,
    required this.onRequireHttpsChanged,
    required this.method,
    required this.onMethodChanged,
    required this.bodyFormat,
    required this.onBodyFormatChanged,
    required this.urlCtrl,
    required this.secretCtrl,
    required this.timeoutCtrl,
    required this.bodyTemplateCtrl,
    required this.statusInCtrl,
    required this.jsonPathExistsCtrl,
    required this.subjectPathCtrl,
    required this.displayNamePathCtrl,
    required this.attributesPathCtrl,
    required this.reasonPathCtrl,
    required this.equalsRows,
    required this.headerRows,
    required this.addEqualsRow,
    required this.removeEqualsRow,
    required this.addHeaderRow,
    required this.removeHeaderRow,
    required this.onSave,
    required this.onTest,
  });

  final StrategiesResponse data;
  final bool saving;
  final bool testing;
  final bool enabled;
  final ValueChanged<bool> onEnabledChanged;
  final bool requireHttps;
  final ValueChanged<bool> onRequireHttpsChanged;
  final String method;
  final ValueChanged<String> onMethodChanged;
  final String bodyFormat;
  final ValueChanged<String> onBodyFormatChanged;

  final TextEditingController urlCtrl;
  final TextEditingController secretCtrl;
  final TextEditingController timeoutCtrl;
  final TextEditingController bodyTemplateCtrl;
  final TextEditingController statusInCtrl;
  final TextEditingController jsonPathExistsCtrl;
  final TextEditingController subjectPathCtrl;
  final TextEditingController displayNamePathCtrl;
  final TextEditingController attributesPathCtrl;
  final TextEditingController reasonPathCtrl;

  final List<({TextEditingController path, TextEditingController value})> equalsRows;
  final List<({TextEditingController key, TextEditingController value})> headerRows;
  final VoidCallback addEqualsRow;
  final ValueChanged<int> removeEqualsRow;
  final VoidCallback addHeaderRow;
  final ValueChanged<int> removeHeaderRow;

  final void Function({bool confirmPermissive}) onSave;
  final VoidCallback onTest;

  static const _methods = ['GET', 'POST', 'PUT'];
  static const _formats = ['JSON', 'FORM', 'BASIC_AUTH', 'NONE'];

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.cloud_outlined),
                const SizedBox(width: 8),
                Text('HTTP delegate',
                    style: Theme.of(context).textTheme.titleMedium),
                const Spacer(),
                Switch(value: enabled, onChanged: onEnabledChanged),
              ],
            ),
            const SizedBox(height: 4),
            const Text('Forward operator credentials to your existing login '
                'endpoint; yaya-agentic adapts to whatever shape it already '
                'returns.'),
            const SizedBox(height: 16),
            TextField(
              controller: urlCtrl,
              enabled: enabled,
              decoration: const InputDecoration(
                labelText: 'URL',
                hintText: 'https://host.example.com/internal/yaya-login',
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: secretCtrl,
              enabled: enabled,
              obscureText: true,
              decoration: InputDecoration(
                labelText: 'Shared secret',
                hintText: data.delegate.sharedSecretPresent
                    ? data.delegate.sharedSecretMask + '   (leave blank to keep)'
                    : 'Required',
              ),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: timeoutCtrl,
                    enabled: enabled,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(labelText: 'Timeout (ms)'),
                  ),
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: SwitchListTile(
                    title: const Text('Require HTTPS'),
                    value: requireHttps,
                    onChanged: enabled ? onRequireHttpsChanged : null,
                    contentPadding: EdgeInsets.zero,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            // Three collapsible advanced sections — closed by default to
            // keep the common case (URL + secret + save) one screen tall.
            _AdvancedTile(
              title: 'Request shaping',
              subtitle: 'How yaya-agentic sends the credentials',
              children: [
                Row(
                  children: [
                    Expanded(
                      child: DropdownButtonFormField<String>(
                        initialValue: method,
                        decoration: const InputDecoration(labelText: 'Method'),
                        items: _methods.map((m) =>
                            DropdownMenuItem(value: m, child: Text(m))).toList(),
                        onChanged: enabled ? (v) { if (v != null) onMethodChanged(v); } : null,
                      ),
                    ),
                    const SizedBox(width: 16),
                    Expanded(
                      child: DropdownButtonFormField<String>(
                        initialValue: bodyFormat,
                        decoration: const InputDecoration(labelText: 'Body format'),
                        items: _formats.map((f) => DropdownMenuItem(
                              value: f,
                              child: Text(switch (f) {
                                'JSON' => 'JSON body',
                                'FORM' => 'Form-encoded body',
                                'BASIC_AUTH' => 'HTTP Basic Auth (no body)',
                                'NONE' => 'No body',
                                _ => f,
                              }),
                            )).toList(),
                        onChanged: enabled ? (v) { if (v != null) onBodyFormatChanged(v); } : null,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: bodyTemplateCtrl,
                  enabled: enabled
                      && bodyFormat != 'BASIC_AUTH'
                      && bodyFormat != 'NONE',
                  maxLines: 4,
                  decoration: const InputDecoration(
                    labelText: 'Body template',
                    hintText: '{"username":"{{username}}","password":"{{password}}"}',
                    border: OutlineInputBorder(),
                  ),
                ),
                const SizedBox(height: 8),
                Text('Available substitutions: {{username}}, {{password}}, {{basic}}',
                    style: Theme.of(context).textTheme.bodySmall),
                const SizedBox(height: 16),
                const Text('Extra headers (X-Yaya-* are added automatically)'),
                const SizedBox(height: 4),
                ..._headerEditor(),
                Align(
                  alignment: Alignment.centerLeft,
                  child: TextButton.icon(
                    onPressed: enabled ? addHeaderRow : null,
                    icon: const Icon(Icons.add),
                    label: const Text('Add header'),
                  ),
                ),
              ],
            ),
            _AdvancedTile(
              title: 'Success criteria',
              subtitle: 'All configured criteria must match for ALLOW',
              children: [
                TextField(
                  controller: statusInCtrl,
                  enabled: enabled,
                  decoration: const InputDecoration(
                    labelText: 'Status codes (comma-separated)',
                    hintText: '200, 204',
                  ),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: jsonPathExistsCtrl,
                  enabled: enabled,
                  decoration: const InputDecoration(
                    labelText: 'JSONPath must exist (optional)',
                    hintText: '\$.data.user.id',
                  ),
                ),
                const SizedBox(height: 12),
                const Text('JSONPath equals — path + expected value'),
                const SizedBox(height: 4),
                ..._equalsEditor(),
                Align(
                  alignment: Alignment.centerLeft,
                  child: TextButton.icon(
                    onPressed: enabled ? addEqualsRow : null,
                    icon: const Icon(Icons.add),
                    label: const Text('Add equality check'),
                  ),
                ),
              ],
            ),
            _AdvancedTile(
              title: 'Identity & failure',
              subtitle: 'How to extract the operator from a successful response',
              children: [
                TextField(
                  controller: subjectPathCtrl,
                  enabled: enabled,
                  decoration: const InputDecoration(
                    labelText: 'Subject path (optional — defaults to typed username)',
                    hintText: '\$.user.email',
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  subjectPathCtrl.text.isEmpty
                      ? 'Unset → yaya-agentic trusts the typed username as the operator subject.'
                      : 'Set → if the path does not resolve on success, login is denied.',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: displayNamePathCtrl,
                  enabled: enabled,
                  decoration: const InputDecoration(
                    labelText: 'Display name path (optional)',
                    hintText: '\$.user.full_name',
                  ),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: attributesPathCtrl,
                  enabled: enabled,
                  decoration: const InputDecoration(
                    labelText: 'Attributes path (optional — subtree to copy verbatim)',
                    hintText: '\$.user',
                  ),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: reasonPathCtrl,
                  enabled: enabled,
                  decoration: const InputDecoration(
                    labelText: 'Failure reason path (audit only, never shown to user)',
                    hintText: '\$.error.code',
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                FilledButton.icon(
                  onPressed: saving ? null : () => onSave(),
                  icon: const Icon(Icons.save_outlined),
                  label: Text(saving ? 'Saving…' : 'Save'),
                ),
                const SizedBox(width: 12),
                OutlinedButton.icon(
                  onPressed: enabled && !testing ? onTest : null,
                  icon: const Icon(Icons.science_outlined),
                  label: const Text('Test delegate'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  List<Widget> _equalsEditor() {
    if (equalsRows.isEmpty) {
      return const [
        Padding(
          padding: EdgeInsets.symmetric(vertical: 4),
          child: Text('No equality checks configured.'),
        ),
      ];
    }
    return [
      for (var i = 0; i < equalsRows.length; i++) Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: Row(
          children: [
            Expanded(
              flex: 2,
              child: TextField(
                controller: equalsRows[i].path,
                enabled: enabled,
                decoration: const InputDecoration(labelText: 'Path'),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              flex: 1,
              child: TextField(
                controller: equalsRows[i].value,
                enabled: enabled,
                decoration: const InputDecoration(labelText: 'Value'),
              ),
            ),
            IconButton(
              icon: const Icon(Icons.delete_outline),
              onPressed: enabled ? () => removeEqualsRow(i) : null,
            ),
          ],
        ),
      ),
    ];
  }

  List<Widget> _headerEditor() {
    return [
      for (var i = 0; i < headerRows.length; i++) Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: Row(
          children: [
            Expanded(
              child: TextField(
                controller: headerRows[i].key,
                enabled: enabled,
                decoration: const InputDecoration(labelText: 'Header name'),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              flex: 2,
              child: TextField(
                controller: headerRows[i].value,
                enabled: enabled,
                decoration: const InputDecoration(labelText: 'Value'),
              ),
            ),
            IconButton(
              icon: const Icon(Icons.delete_outline),
              onPressed: enabled ? () => removeHeaderRow(i) : null,
            ),
          ],
        ),
      ),
    ];
  }
}

class _AdvancedTile extends StatelessWidget {
  const _AdvancedTile({required this.title, required this.subtitle, required this.children});
  final String title;
  final String subtitle;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return ExpansionTile(
      tilePadding: EdgeInsets.zero,
      childrenPadding: const EdgeInsets.only(top: 8, bottom: 16),
      title: Text(title),
      subtitle: Text(subtitle),
      children: children.map((c) =>
          Padding(padding: const EdgeInsets.only(bottom: 8), child: c)).toList(),
    );
  }
}

// ---------------------------------------------------------------------------
// Probe result render — the structured-evaluation view from design §8
// ---------------------------------------------------------------------------

class _ProbeResultView extends StatelessWidget {
  const _ProbeResultView({required this.probe});
  final ProbeResult probe;

  @override
  Widget build(BuildContext context) {
    final allowed = probe.allowed;
    final decisionColor = allowed ? Colors.green.shade700 : Colors.red.shade700;
    return Container(
      margin: const EdgeInsets.only(top: 8),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        border: Border.all(color: Theme.of(context).dividerColor),
        borderRadius: BorderRadius.circular(6),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              if (probe.response.status != null) ...[
                _Pill(label: 'HTTP ${probe.response.status}',
                    color: _statusColor(probe.response.status!)),
                const SizedBox(width: 8),
              ],
              _Pill(label: '${probe.response.durationMs} ms', color: Colors.blueGrey),
              const Spacer(),
              _Pill(
                label: probe.evaluation.decision,
                color: decisionColor,
              ),
            ],
          ),
          const SizedBox(height: 12),
          Text('Success criteria',
              style: Theme.of(context).textTheme.labelLarge),
          const SizedBox(height: 4),
          ...probe.evaluation.successChecks.map((c) => Row(
                children: [
                  Icon(c.matched ? Icons.check_circle : Icons.cancel,
                      color: c.matched ? Colors.green : Colors.red, size: 16),
                  const SizedBox(width: 6),
                  Expanded(child: Text('${c.criterion}: ${c.detail ?? ''}',
                      style: const TextStyle(fontFamily: 'monospace', fontSize: 12))),
                ],
              )),
          if (probe.evaluation.identity != null) ...[
            const SizedBox(height: 12),
            Text('Identity extracted',
                style: Theme.of(context).textTheme.labelLarge),
            const SizedBox(height: 4),
            Text('subject: ${probe.evaluation.identity!.subject}',
                style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
            Text('display: ${probe.evaluation.identity!.displayName}',
                style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
            if (probe.evaluation.identity!.attributes.isNotEmpty)
              Text('attributes: ${probe.evaluation.identity!.attributes}',
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
          ],
          if (probe.evaluation.auditReason != null) ...[
            const SizedBox(height: 12),
            Text('Audit reason (never shown to user):',
                style: Theme.of(context).textTheme.labelLarge),
            const SizedBox(height: 4),
            Text(probe.evaluation.auditReason!,
                style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
          ],
          if (probe.response.body != null && probe.response.body!.isNotEmpty) ...[
            const SizedBox(height: 12),
            ExpansionTile(
              tilePadding: EdgeInsets.zero,
              childrenPadding: const EdgeInsets.only(bottom: 8),
              title: const Text('Raw response body'),
              children: [
                SelectableText(probe.response.body!,
                    style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
              ],
            ),
          ],
        ],
      ),
    );
  }

  Color _statusColor(int status) {
    if (status >= 200 && status < 300) return Colors.green.shade700;
    if (status >= 400 && status < 500) return Colors.orange.shade700;
    return Colors.red.shade700;
  }
}

class _Pill extends StatelessWidget {
  const _Pill({required this.label, required this.color});
  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: color),
      ),
      child: Text(label,
          style: TextStyle(color: color, fontWeight: FontWeight.w600, fontSize: 12)),
    );
  }
}
