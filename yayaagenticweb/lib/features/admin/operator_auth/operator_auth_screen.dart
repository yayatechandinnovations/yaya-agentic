import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../models/admin/operator_auth_strategies.dart';
import '../../../ui/data/live_async.dart';
import '../../../ui/data/relative_time.dart';
import '../../../ui/data/y_badge.dart';
import '../../../ui/data/y_sheet.dart';
import '../../../ui/feedback/api_error.dart';
import '../../../ui/feedback/confirm.dart';
import '../../../ui/feedback/toast.dart';
import '../../../ui/form/unsaved_guard.dart';
import '../../../ui/form/y_editor_page.dart';
import '../../../ui/form/y_fields.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/form/y_key_value_field.dart';
import '../../../ui/tokens.dart';
import 'operator_auth_controller.dart';

const _labels = {
  'enabled': 'Use the HTTP delegate',
  'url': 'Login endpoint URL',
  'sharedSecret': 'Shared secret',
  'timeoutMs': 'Timeout',
  'requireHttps': 'Require HTTPS',
  'method': 'Method',
  'bodyFormat': 'How credentials are sent',
  'bodyTemplate': 'Body template',
  'headers': 'Extra headers',
  'statusIn': 'Success status codes',
  'jsonPathExists': 'JSONPath that must exist',
  'jsonPathEquals': 'JSONPath equality checks',
  'subjectPath': 'Subject path',
  'displayNamePath': 'Display name path',
  'attributesPath': 'Attributes path',
  'reasonPath': 'Failure reason path',
};

/// Backend `jsonPath` keys → this form's field names, for server errors
/// that name the path ("identity.subjectPath: invalid JSONPath …").
const _serverFieldPrefixes = {
  'success.jsonPathExists': 'jsonPathExists',
  'success.jsonPathEquals': 'jsonPathEquals',
  'success.statusIn': 'statusIn',
  'identity.subjectPath': 'subjectPath',
  'identity.displayNamePath': 'displayNamePath',
  'identity.attributesPath': 'attributesPath',
  'failure.reasonPath': 'reasonPath',
  'shared-secret': 'sharedSecret',
  'url ': 'url',
};

/// Platform → Operator authentication: how people sign in to this console.
/// The bootstrap (break-glass) account saves immediately; the HTTP delegate
/// is an editor with its own Save bar.
class OperatorAuthScreen extends ConsumerWidget {
  const OperatorAuthScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final value = ref.watch(operatorAuthStrategiesProvider);
    return LiveAsyncView<StrategiesResponse>(
      value: value,
      what: 'operator login settings',
      onRetry: () => ref.invalidate(operatorAuthStrategiesProvider),
      data: (context, data) => _OperatorAuthEditor(data: data),
    );
  }
}

class _OperatorAuthEditor extends ConsumerStatefulWidget {
  const _OperatorAuthEditor({required this.data});
  final StrategiesResponse data;

  @override
  ConsumerState<_OperatorAuthEditor> createState() => _OperatorAuthEditorState();
}

class _OperatorAuthEditorState extends ConsumerState<_OperatorAuthEditor> {
  final _url = TextEditingController();
  final _secret = TextEditingController();
  final _timeout = TextEditingController();
  final _bodyTemplate = TextEditingController();
  final _statusIn = TextEditingController();
  final _jsonPathExists = TextEditingController();
  final _subjectPath = TextEditingController();
  final _displayNamePath = TextEditingController();
  final _attributesPath = TextEditingController();
  final _reasonPath = TextEditingController();
  bool _enabled = false;
  bool _requireHttps = true;
  String _method = 'POST';
  String _bodyFormat = 'JSON';
  Map<String, String> _headers = {};
  Map<String, String> _equals = {};
  bool _bootstrapBusy = false;

  late final YFormController _form;
  late DelegateView _base;

  final _kConnection = GlobalKey(debugLabel: 'section-connection');
  final _kRequest = GlobalKey(debugLabel: 'section-request');
  final _kSuccess = GlobalKey(debugLabel: 'section-success');
  final _kIdentity = GlobalKey(debugLabel: 'section-identity');
  final _kBootstrap = GlobalKey(debugLabel: 'section-bootstrap');

  @override
  void initState() {
    super.initState();
    _load(widget.data.delegate);
    _form = YFormController(
      snapshot: _snapshot,
      validate: _validate,
      labels: _labels,
      draftKey: 'yaya.draft.operatorAuth.platform.delegate',
      excludeFromDraft: const {'sharedSecret'},
    );
    _form.addListener(_rebuild);
    _form.loadDraft().then((d) {
      if (d == null || !mounted) return;
      _applySnapshot(d.values);
      _form.refresh();
    });
  }

  @override
  void didUpdateWidget(_OperatorAuthEditor old) {
    super.didUpdateWidget(old);
    // Someone else saved (or we did): take the new baseline unless there's
    // unsaved work here, which the operator decides about.
    if (old.data.delegate != widget.data.delegate && !_form.isDirty) {
      _load(widget.data.delegate);
      _form.markClean();
    }
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  void _load(DelegateView d) {
    _base = d;
    _enabled = d.enabled;
    _requireHttps = d.requireHttps;
    _url.text = d.url ?? '';
    _secret.text = '';
    _timeout.text = '${d.timeoutMs}';
    _method = d.request.method;
    _bodyFormat = d.request.body.format;
    _bodyTemplate.text = d.request.body.template ?? '';
    _headers = Map.of(d.request.headers);
    _statusIn.text = d.success.statusIn.join(', ');
    _jsonPathExists.text = d.success.jsonPathExists ?? '';
    _equals = {for (final e in d.success.jsonPathEquals) e.path: _encodeExpected(e.value)};
    _subjectPath.text = d.identity.subjectPath ?? '';
    _displayNamePath.text = d.identity.displayNamePath ?? '';
    _attributesPath.text = d.identity.attributesPath ?? '';
    _reasonPath.text = d.failure.reasonPath ?? '';
  }

  Map<String, Object?> _snapshot() => {
        'enabled': _enabled,
        'url': _url.text.trim(),
        'sharedSecret': _secret.text,
        'timeoutMs': _timeout.text.trim(),
        'requireHttps': _requireHttps,
        'method': _method,
        'bodyFormat': _bodyFormat,
        'bodyTemplate': _bodyTemplate.text,
        'headers': _headers,
        'statusIn': _statusIn.text.trim(),
        'jsonPathExists': _jsonPathExists.text.trim(),
        'jsonPathEquals': _equals,
        'subjectPath': _subjectPath.text.trim(),
        'displayNamePath': _displayNamePath.text.trim(),
        'attributesPath': _attributesPath.text.trim(),
        'reasonPath': _reasonPath.text.trim(),
      };

  void _applySnapshot(Map<String, Object?> v) {
    String s(String k, String cur) => v[k] is String ? v[k] as String : cur;
    if (v['enabled'] is bool) _enabled = v['enabled'] as bool;
    if (v['requireHttps'] is bool) _requireHttps = v['requireHttps'] as bool;
    _url.text = s('url', _url.text);
    _timeout.text = s('timeoutMs', _timeout.text);
    _method = s('method', _method);
    _bodyFormat = s('bodyFormat', _bodyFormat);
    _bodyTemplate.text = s('bodyTemplate', _bodyTemplate.text);
    _statusIn.text = s('statusIn', _statusIn.text);
    _jsonPathExists.text = s('jsonPathExists', _jsonPathExists.text);
    _subjectPath.text = s('subjectPath', _subjectPath.text);
    _displayNamePath.text = s('displayNamePath', _displayNamePath.text);
    _attributesPath.text = s('attributesPath', _attributesPath.text);
    _reasonPath.text = s('reasonPath', _reasonPath.text);
    final h = v['headers'];
    if (h is Map) _headers = h.map((k, val) => MapEntry('$k', '$val'));
    final eq = v['jsonPathEquals'];
    if (eq is Map) _equals = eq.map((k, val) => MapEntry('$k', '$val'));
  }

  // ---- validation ------------------------------------------------------

  List<int>? _parseStatuses() {
    final parts = _statusIn.text.split(',').map((s) => s.trim()).where((s) => s.isNotEmpty).toList();
    final out = <int>[];
    for (final p in parts) {
      final n = int.tryParse(p);
      if (n == null) return null;
      out.add(n);
    }
    return out;
  }

  List<int> get _nonOkStatuses => (_parseStatuses() ?? const []).where((s) => s < 200 || s >= 300).toList();

  bool get _templateApplies => _bodyFormat == 'JSON' || _bodyFormat == 'FORM';

  Map<String, String?> _validate() {
    String? urlErr;
    final url = _url.text.trim();
    if (_enabled && url.isEmpty) {
      urlErr = 'Enter the login endpoint URL — it’s required while the delegate is on.';
    } else if (url.isNotEmpty) {
      urlErr = Validators.httpUrl(url);
      if (urlErr == null && _requireHttps && !url.toLowerCase().startsWith('https://')) {
        urlErr = 'Must start with https:// while “Require HTTPS” is on.';
      }
    }
    final statuses = _parseStatuses();
    String? statusErr;
    if (statuses == null) {
      statusErr = 'Use whole numbers separated by commas, e.g. 200, 204.';
    } else if (statuses.any((s) => s < 100 || s > 599)) {
      statusErr = 'HTTP status codes run from 100 to 599.';
    }
    String? equalsErr = YKeyValueField.validate(_equals.entries);
    if (equalsErr == null) {
      for (final p in _equals.keys) {
        final e = _jsonPath(p);
        if (e != null) {
          equalsErr = '“$p”: $e';
          break;
        }
      }
    }
    return {
      'url': urlErr,
      'sharedSecret': _enabled && !_base.sharedSecretPresent && _secret.text.trim().isEmpty
          ? 'Enter a shared secret — the first save with the delegate on needs one.'
          : null,
      'timeoutMs': Validators.intInRange(_timeout.text, 1, 300000, optional: false),
      'statusIn': statusErr,
      'headers': YKeyValueField.validate(_headers.entries, caseInsensitive: true),
      'jsonPathExists': _jsonPath(_jsonPathExists.text),
      'jsonPathEquals': equalsErr,
      'subjectPath': _jsonPath(_subjectPath.text),
      'displayNamePath': _jsonPath(_displayNamePath.text),
      'attributesPath': _jsonPath(_attributesPath.text),
      'reasonPath': _jsonPath(_reasonPath.text),
    };
  }

  /// A light syntax check; the server compiles the path for real.
  static String? _jsonPath(String raw) {
    final p = raw.trim();
    if (p.isEmpty) return null;
    if (p.endsWith('.') || p.contains('..[')) return 'Path can’t end with a dot.';
    var depth = 0;
    for (final ch in p.split('')) {
      if (ch == '[') depth++;
      if (ch == ']') depth--;
      if (depth < 0) break;
    }
    if (depth != 0) return 'Brackets [ ] don’t balance.';
    if (RegExp(r'\s').hasMatch(p.replaceAll(RegExp(r"'[^']*'|" r'"[^"]*"'), ''))) {
      return 'Remove spaces (quote keys that contain them, e.g. \$[\'full name\']).';
    }
    return null;
  }

  // ---- expected values: typed JSON scalars, shown as text ---------------

  /// Strings that would read back as a number/bool/null are shown quoted so
  /// a save doesn't silently change their type.
  static String _encodeExpected(dynamic v) {
    if (v == null) return 'null';
    if (v is String) return _decodeExpected(v) is String ? v : jsonEncode(v);
    return '$v';
  }

  static dynamic _decodeExpected(String raw) {
    if (raw == 'null') return null;
    if (raw == 'true') return true;
    if (raw == 'false') return false;
    final i = int.tryParse(raw);
    if (i != null) return i;
    final d = double.tryParse(raw);
    if (d != null) return d;
    if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) {
      try {
        final s = jsonDecode(raw);
        if (s is String) return s;
      } catch (_) {}
    }
    return raw;
  }

  DelegateView _buildView() {
    String? orNull(String s) => s.trim().isEmpty ? null : s.trim();
    final statuses = _parseStatuses() ?? const [];
    return _base.copyWith(
      enabled: _enabled,
      url: orNull(_url.text),
      timeoutMs: int.tryParse(_timeout.text.trim()) ?? _base.timeoutMs,
      requireHttps: _requireHttps,
      request: _base.request.copyWith(
        method: _method,
        headers: _headers,
        body: _base.request.body.copyWith(
          format: _bodyFormat,
          template: _templateApplies ? (_bodyTemplate.text.isEmpty ? null : _bodyTemplate.text) : _base.request.body.template,
        ),
      ),
      success: _base.success.copyWith(
        statusIn: statuses.isEmpty ? const [200, 204] : statuses,
        jsonPathExists: orNull(_jsonPathExists.text),
        jsonPathEquals: [
          for (final e in _equals.entries) JsonPathEquals(path: e.key.trim(), value: _decodeExpected(e.value)),
        ],
      ),
      identity: _base.identity.copyWith(
        subjectPath: orNull(_subjectPath.text),
        displayNamePath: orNull(_displayNamePath.text),
        attributesPath: orNull(_attributesPath.text),
      ),
      failure: _base.failure.copyWith(reasonPath: orNull(_reasonPath.text)),
    );
  }

  Future<void> _save() async {
    if (!_form.submit()) return;
    final nonOk = _nonOkStatuses;
    var permissive = false;
    if (nonOk.isNotEmpty) {
      permissive = await confirmAction(
        context,
        title: 'Count ${nonOk.join(', ')} as a successful login?',
        message: 'Your success codes include non-2xx statuses. Any response with ${nonOk.length == 1 ? 'that status' : 'those statuses'} — '
            'often an error page — would sign the operator in, unless another check below fails.',
        confirmLabel: 'Yes, allow ${nonOk.length == 1 ? 'it' : 'them'}',
        destructive: true,
      );
      if (!permissive) return;
    }
    _form.saving = true;
    try {
      final secret = _secret.text.trim();
      final updated = await ref.read(operatorAuthActionsProvider).saveDelegate(
            _buildView(),
            sharedSecret: secret.isEmpty ? null : secret,
            confirmPermissive: permissive,
          );
      _load(updated.delegate);
      _form.saving = false;
      _form.markClean();
      if (mounted) showToast(context, 'HTTP delegate settings saved');
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(_mapServerError(e));
    }
  }

  /// The delegate endpoint answers with plain messages; pin the ones that
  /// name a field under that field.
  Object _mapServerError(Object e) {
    final info = ApiErrorInfo.from(e);
    if (info.fieldErrors.isNotEmpty) return info;
    for (final entry in _serverFieldPrefixes.entries) {
      if (info.message.startsWith(entry.key)) {
        return ApiErrorInfo(
          message: info.message,
          code: info.code,
          status: info.status,
          path: info.path,
          fieldErrors: {entry.value: info.message},
        );
      }
    }
    return info;
  }

  void _discard() {
    _load(widget.data.delegate);
    _form.markClean();
    setState(() {});
  }

  // ---- bootstrap (saves immediately) -----------------------------------

  Future<void> _toggleBootstrap(bool on) async {
    if (!on) {
      final ok = await confirmAction(
        context,
        title: 'Turn off the bootstrap login?',
        message: 'The “${widget.data.bootstrap.username}” break-glass account stops working right away. '
            'If the HTTP delegate later fails, nobody can sign in until the bootstrap account is re-enabled from the server configuration.',
        confirmLabel: 'Turn off bootstrap login',
        destructive: true,
      );
      if (!ok) return;
    }
    setState(() => _bootstrapBusy = true);
    try {
      await ref.read(operatorAuthActionsProvider).saveBootstrap(enabled: on);
      if (mounted) showToast(context, on ? 'Bootstrap login turned on' : 'Bootstrap login turned off');
    } catch (e) {
      if (mounted) showToast(context, ApiErrorInfo.from(e).message, error: true);
    } finally {
      if (mounted) setState(() => _bootstrapBusy = false);
    }
  }

  Future<void> _changePassword() async {
    await showYSheet<void>(
      context,
      width: 560,
      label: 'Change bootstrap password',
      builder: (_) => _BootstrapPasswordSheet(username: widget.data.bootstrap.username),
    );
  }

  Future<void> _openTest() async {
    await showYSheet<void>(context, width: 680, label: 'Test HTTP delegate', builder: (_) => const _DelegateTestSheet());
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _form.dispose();
    for (final c in [
      _url, _secret, _timeout, _bodyTemplate, _statusIn, _jsonPathExists,
      _subjectPath, _displayNamePath, _attributesPath, _reasonPath,
    ]) {
      c.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final data = widget.data;
    final scheme = Theme.of(context).colorScheme;
    final savedEnabled = data.delegate.enabled && (data.delegate.url ?? '').isNotEmpty;
    final testBlocker = !savedEnabled
        ? 'Turn the delegate on and save before testing.'
        : _form.isDirty
            ? 'Testing uses the saved settings — save your changes first.'
            : null;
    final nonOk = _nonOkStatuses;

    return YEditorPage(
      form: _form,
      title: 'Operator authentication',
      subtitle: 'How people sign in to this console: a break-glass bootstrap account, and an HTTP delegate that checks credentials against your existing login service.',
      badges: [
        data.delegate.enabled
            ? const YBadge('Delegate on', tone: YTone.success, icon: Icons.cloud_done_outlined)
            : const YBadge('Delegate off', icon: Icons.cloud_off_outlined),
        data.bootstrap.enabled
            ? const YBadge('Bootstrap on', tone: YTone.warning, icon: Icons.key_outlined)
            : const YBadge('Bootstrap off', icon: Icons.key_off_outlined),
      ],
      headerActions: [
        Tooltip(
          message: testBlocker ?? 'Send test credentials to the saved delegate',
          child: OutlinedButton.icon(
            onPressed: testBlocker == null ? _openTest : null,
            icon: const Icon(Icons.science_outlined, size: 18),
            label: const Text('Test delegate…'),
          ),
        ),
      ],
      banners: [
        if (data.updatedAt != null)
          Text(
            'Last changed ${formatRelativeIso(data.updatedAt)}${data.updatedBy == null ? '' : ' by ${data.updatedBy}'}.',
            style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant),
          ),
      ],
      sections: [
        YSectionLink('Bootstrap account', _kBootstrap),
        YSectionLink('Delegate connection', _kConnection, fields: const ['enabled', 'url', 'sharedSecret', 'timeoutMs', 'requireHttps']),
        YSectionLink('Request', _kRequest, fields: const ['method', 'bodyFormat', 'bodyTemplate', 'headers']),
        YSectionLink('Success criteria', _kSuccess, fields: const ['statusIn', 'jsonPathExists', 'jsonPathEquals']),
        YSectionLink('Identity & failure', _kIdentity, fields: const ['subjectPath', 'displayNamePath', 'attributesPath', 'reasonPath']),
      ],
      primaryLabel: 'Save delegate settings',
      actionVerb: 'saving the delegate settings',
      onPrimary: _save,
      onCancel: _discard,
      children: [
        YFormSection(
          sectionKey: _kBootstrap,
          title: 'Bootstrap account',
          description: 'A break-glass login configured on the server. Changes here save immediately — they don’t use the Save bar.',
          children: [
            Row(children: [
              Icon(Icons.person_outline, size: 20, color: scheme.onSurfaceVariant),
              const SizedBox(width: 8),
              SelectableText(data.bootstrap.username, style: monoStyle(context, size: 14, weight: FontWeight.w600)),
              const SizedBox(width: 10),
              Flexible(
                child: Text('Username, set by YAYA_BOOTSTRAP_USERNAME on the server.',
                    style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant)),
              ),
            ]),
            YSwitchField(
              title: 'Accept bootstrap login',
              wireKey: 'bootstrap.enabled',
              value: data.bootstrap.enabled,
              description: data.bootstrap.enabled
                  ? 'The bootstrap account can sign in as a fallback.'
                  : 'Bootstrap sign-in is refused.',
              disabledReason: _bootstrapBusy
                  ? 'Saving…'
                  : (!data.canDisableBootstrap && data.bootstrap.enabled)
                      ? 'Can’t turn off yet: at least one operator must first sign in through the HTTP delegate, so you’re never locked out.'
                      : null,
              onChanged: _toggleBootstrap,
            ),
            Align(
              alignment: Alignment.centerLeft,
              child: OutlinedButton.icon(
                onPressed: _changePassword,
                icon: const Icon(Icons.password, size: 18),
                label: const Text('Change bootstrap password…'),
              ),
            ),
          ],
        ),
        YFormSection(
          sectionKey: _kConnection,
          title: 'Delegate connection',
          description: 'yaya-agentic forwards the username and password an operator types to your login endpoint and reads its answer.',
          children: [
            YSwitchField(
              title: _labels['enabled']!,
              wireKey: 'enabled',
              value: _enabled,
              description: 'When on, the console sign-in form checks credentials against the endpoint below.',
              onChanged: (v) {
                setState(() => _enabled = v);
                _form.changed('enabled');
              },
            ),
            YTextField(
              label: _labels['url']!,
              wireKey: 'url',
              controller: _url,
              form: _form,
              name: 'url',
              required: _enabled,
              optional: !_enabled,
              mono: true,
              keyboardType: TextInputType.url,
              hint: 'e.g. https://login.acme.com/internal/yaya-login',
              helper: 'Receives the operator’s credentials on every sign-in. Only point it at a service you trust with them.',
            ),
            YSecretField(
              label: _labels['sharedSecret']!,
              wireKey: 'sharedSecret',
              controller: _secret,
              form: _form,
              name: 'sharedSecret',
              stored: data.delegate.sharedSecretPresent,
              required: _enabled,
              helper: data.delegate.sharedSecretPresent
                  ? 'Stored (${data.delegate.sharedSecretMask}). Leave blank to keep it; type a new one to rotate — the endpoint must accept the new value immediately.'
                  : 'Sent so your endpoint can tell the request comes from yaya-agentic. Stored encrypted; never shown again.',
            ),
            YTextField(
              label: _labels['timeoutMs']!,
              wireKey: 'timeoutMs',
              controller: _timeout,
              form: _form,
              name: 'timeoutMs',
              required: true,
              mono: true,
              keyboardType: TextInputType.number,
              hint: 'e.g. 5000',
              helper: 'Milliseconds to wait for the endpoint before refusing the sign-in.',
              suffix: Padding(
                padding: const EdgeInsets.only(right: 12),
                child: Text('ms', style: TextStyle(fontSize: 14, color: scheme.onSurfaceVariant)),
              ),
            ),
            YSwitchField(
              title: _labels['requireHttps']!,
              wireKey: 'requireHttps',
              value: _requireHttps,
              description: 'Refuse an http:// endpoint — passwords would otherwise travel unencrypted. Turn off only for local development.',
              onChanged: (v) {
                setState(() => _requireHttps = v);
                _form.changed('requireHttps');
                if (_url.text.trim().isNotEmpty) _form.touch('url');
              },
            ),
          ],
        ),
        YFormSection(
          sectionKey: _kRequest,
          title: 'Request',
          description: 'How the credentials are sent. X-Yaya-* headers are added automatically.',
          collapsible: true,
          initiallyExpanded: false,
          children: [
            YSelectField<String>(
              label: _labels['method']!,
              wireKey: 'request.method',
              form: _form,
              name: 'method',
              value: _method,
              options: const [YOption('POST', 'POST'), YOption('PUT', 'PUT'), YOption('GET', 'GET')],
              helper: 'GET sends no body; use Basic Auth with it.',
              onChanged: (v) => setState(() => _method = v ?? _method),
            ),
            YEnumCards<String>(
              label: _labels['bodyFormat']!,
              wireKey: 'request.body.format',
              form: _form,
              name: 'bodyFormat',
              value: _bodyFormat,
              onChanged: (v) => setState(() => _bodyFormat = v),
              options: const [
                YEnumOption('JSON', 'JSON body', wire: 'JSON', description: 'Credentials go in a JSON body built from the template below.'),
                YEnumOption('FORM', 'Form-encoded body', wire: 'FORM', description: 'Like an HTML login form: username=…&password=…, from the template.'),
                YEnumOption('BASIC_AUTH', 'HTTP Basic Auth', wire: 'BASIC_AUTH', description: 'Credentials go in the Authorization header; no body is sent.'),
                YEnumOption('NONE', 'No body', wire: 'NONE', caution: true, description: 'Nothing carries the credentials unless your headers use {{username}} / {{password}}.'),
              ],
            ),
            YTextField(
              label: _labels['bodyTemplate']!,
              wireKey: 'request.body.template',
              controller: _bodyTemplate,
              form: _form,
              name: 'bodyTemplate',
              optional: true,
              enabled: _templateApplies,
              mono: true,
              maxLines: 5,
              minLines: 3,
              hint: _bodyFormat == 'FORM'
                  ? 'username={{username}}&password={{password}}'
                  : '{"username":"{{username}}","password":"{{password}}"}',
              helper: _templateApplies
                  ? 'Substitutions: {{username}}, {{password}}, {{basic}}. Empty uses the default shape for this format.'
                  : 'Not used — ${_bodyFormat == 'BASIC_AUTH' ? 'Basic Auth' : 'this format'} sends no body. The saved template is kept.',
            ),
            YKeyValueField(
              label: _labels['headers']!,
              wireKey: 'request.headers',
              form: _form,
              name: 'headers',
              value: _headers,
              caseInsensitiveKeys: true,
              keyHint: 'Header',
              valueHint: 'Value, may use {{username}}',
              addLabel: 'Add header',
              helper: 'Sent with every sign-in request.',
              onChanged: (m) {
                setState(() => _headers = m);
                _form.refresh();
              },
            ),
          ],
        ),
        YFormSection(
          sectionKey: _kSuccess,
          title: 'Success criteria',
          description: 'Every check you configure must pass for the sign-in to be allowed.',
          collapsible: true,
          initiallyExpanded: false,
          children: [
            YTextField(
              label: _labels['statusIn']!,
              wireKey: 'success.statusIn',
              controller: _statusIn,
              form: _form,
              name: 'statusIn',
              required: true,
              mono: true,
              hint: 'e.g. 200, 204',
              helper: 'Comma-separated. Empty means 200, 204.',
            ),
            if (nonOk.isNotEmpty)
              YBanner(
                tone: YBannerTone.warning,
                title: 'Non-2xx counts as success.',
                message: '${nonOk.join(', ')} would sign the operator in. You’ll be asked to confirm when saving.',
              ),
            YTextField(
              label: _labels['jsonPathExists']!,
              wireKey: 'success.jsonPathExists',
              controller: _jsonPathExists,
              form: _form,
              name: 'jsonPathExists',
              optional: true,
              mono: true,
              hint: r'e.g. $.data.user.id',
              helper: 'The response body must contain this path.',
            ),
            YKeyValueField(
              label: _labels['jsonPathEquals']!,
              wireKey: 'success.jsonPathEquals',
              form: _form,
              name: 'jsonPathEquals',
              value: _equals,
              keyHint: r'$.status',
              valueHint: 'Expected, e.g. "ok", 1, true',
              addLabel: 'Add equality check',
              helper: 'The value at each path must equal the expected value. Numbers, true/false and null are typed; quote text that looks like one ("200").',
              onChanged: (m) {
                setState(() => _equals = m);
                _form.refresh();
              },
            ),
          ],
        ),
        YFormSection(
          sectionKey: _kIdentity,
          title: 'Identity & failure',
          description: 'Who the operator is, read from a successful response.',
          collapsible: true,
          initiallyExpanded: false,
          children: [
            YTextField(
              label: _labels['subjectPath']!,
              wireKey: 'identity.subjectPath',
              controller: _subjectPath,
              form: _form,
              name: 'subjectPath',
              optional: true,
              mono: true,
              hint: r'e.g. $.user.email',
              helper: _subjectPath.text.trim().isEmpty
                  ? 'Empty: the typed username becomes the operator’s identity.'
                  : 'Set: if this path is missing from a successful response, the sign-in is refused.',
            ),
            YTextField(
              label: _labels['displayNamePath']!,
              wireKey: 'identity.displayNamePath',
              controller: _displayNamePath,
              form: _form,
              name: 'displayNamePath',
              optional: true,
              mono: true,
              hint: r'e.g. $.user.full_name',
              helper: 'Shown in the console and in audit rows.',
            ),
            YTextField(
              label: _labels['attributesPath']!,
              wireKey: 'identity.attributesPath',
              controller: _attributesPath,
              form: _form,
              name: 'attributesPath',
              optional: true,
              mono: true,
              hint: r'e.g. $.user',
              helper: 'A subtree copied onto the operator verbatim.',
            ),
            YTextField(
              label: _labels['reasonPath']!,
              wireKey: 'failure.reasonPath',
              controller: _reasonPath,
              form: _form,
              name: 'reasonPath',
              optional: true,
              mono: true,
              hint: r'e.g. $.error.code',
              helper: 'Why a sign-in failed — recorded in the audit log only, never shown to the person signing in.',
            ),
          ],
        ),
      ],
    );
  }
}

/// New bootstrap password + confirmation. Saves on its own endpoint.
class _BootstrapPasswordSheet extends ConsumerStatefulWidget {
  const _BootstrapPasswordSheet({required this.username});
  final String username;

  @override
  ConsumerState<_BootstrapPasswordSheet> createState() => _BootstrapPasswordSheetState();
}

class _BootstrapPasswordSheetState extends ConsumerState<_BootstrapPasswordSheet> {
  final _pw = TextEditingController();
  final _confirm = TextEditingController();
  late final YFormController _form;

  @override
  void initState() {
    super.initState();
    _form = YFormController(
      snapshot: () => {'newPassword': _pw.text, 'confirmPassword': _confirm.text},
      validate: () => {
        'newPassword': _pw.text.isEmpty ? 'Enter a new password.' : null,
        'confirmPassword': _confirm.text.isEmpty
            ? 'Type the password again.'
            : _confirm.text != _pw.text
                ? 'Doesn’t match the new password.'
                : null,
      },
      labels: const {'newPassword': 'New password', 'confirmPassword': 'Confirm new password'},
    );
    _form.addListener(_rebuild);
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _form.dispose();
    _pw.dispose();
    _confirm.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!_form.submit()) return;
    final ok = await confirmAction(
      context,
      title: 'Change the bootstrap password?',
      message: 'The old password for “${widget.username}” stops working immediately. Store the new one somewhere safe — it can’t be shown again.',
      confirmLabel: 'Change password',
    );
    if (!ok) return;
    _form.saving = true;
    try {
      await ref.read(operatorAuthActionsProvider).saveBootstrap(newPassword: _pw.text);
      _form.saving = false;
      _form.markClean();
      if (!mounted) return;
      showToast(context, 'Bootstrap password changed');
      Navigator.of(context).pop();
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(e);
    }
  }

  @override
  Widget build(BuildContext context) {
    return YUnsavedGuard(
      form: _form,
      child: YSaveShortcut(
        onSave: _save,
        child: YSheetScaffold(
          title: 'Change bootstrap password',
          subtitle: 'For the break-glass account “${widget.username}”.',
          body: ListView(
            padding: const EdgeInsets.fromLTRB(28, 20, 28, 28),
            children: [
              if (_form.summary.isNotEmpty || _form.formError != null) ...[
                YErrorSummary(form: _form, action: 'changing the password'),
                const SizedBox(height: Gap.lg),
              ],
              YSecretField(
                label: 'New password',
                wireKey: 'newPassword',
                controller: _pw,
                form: _form,
                name: 'newPassword',
                required: true,
                helper: 'Use a long, unique passphrase — this account bypasses the HTTP delegate.',
              ),
              const SizedBox(height: Gap.fieldGap),
              YSecretField(
                label: 'Confirm new password',
                controller: _confirm,
                form: _form,
                name: 'confirmPassword',
                required: true,
                helper: 'Type it again to catch typos.',
              ),
            ],
          ),
          footer: YActionBar(
            form: _form,
            primaryLabel: 'Change password',
            onPrimary: _save,
            onCancel: () => closeYSheet(context),
          ),
        ),
      ),
    );
  }
}

/// Sends test credentials to the SAVED delegate and shows how the rules read the answer.
class _DelegateTestSheet extends ConsumerStatefulWidget {
  const _DelegateTestSheet();

  @override
  ConsumerState<_DelegateTestSheet> createState() => _DelegateTestSheetState();
}

class _DelegateTestSheetState extends ConsumerState<_DelegateTestSheet> {
  final _user = TextEditingController();
  final _pass = TextEditingController();
  late final YFormController _form;
  ProbeResult? _probe;

  @override
  void initState() {
    super.initState();
    _form = YFormController(
      // Nothing here is worth guarding: test credentials are throwaway.
      snapshot: () => const {},
      validate: () => {'username': Validators.required(_user.text, 'a test username')},
      labels: const {'username': 'Test username'},
    );
    _form.addListener(_rebuild);
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _form.dispose();
    _user.dispose();
    _pass.dispose();
    super.dispose();
  }

  Future<void> _run() async {
    if (!_form.submit()) return;
    _form.saving = true;
    try {
      final p = await ref.read(operatorAuthActionsProvider).test(username: _user.text.trim(), password: _pass.text);
      _form.saving = false;
      _form.markClean();
      setState(() => _probe = p);
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(e);
    }
  }

  @override
  Widget build(BuildContext context) {
    return YSaveShortcut(
      onSave: _run,
      child: YSheetScaffold(
        title: 'Test HTTP delegate',
        subtitle: 'Sends these credentials to the saved endpoint and shows how your rules read the answer. Nothing is signed in.',
        body: ListView(
          padding: const EdgeInsets.fromLTRB(28, 20, 28, 28),
          children: [
            if (_form.summary.isNotEmpty || _form.formError != null) ...[
              YErrorSummary(form: _form, action: 'running the test'),
              const SizedBox(height: Gap.lg),
            ],
            YTextField(
              label: 'Test username',
              controller: _user,
              form: _form,
              name: 'username',
              required: true,
              autofocus: true,
              hint: 'e.g. ana@acme.com',
            ),
            const SizedBox(height: Gap.fieldGap),
            YSecretField(
              label: 'Test password',
              controller: _pass,
              form: _form,
              name: 'password',
              helper: 'Sent to your endpoint once for this test; never stored.',
            ),
            if (_probe != null) ...[
              const SizedBox(height: Gap.xl),
              _ProbeResultView(probe: _probe!),
            ],
          ],
        ),
        footer: YActionBar(
          form: _form,
          primaryLabel: _probe == null ? 'Run test' : 'Run again',
          onPrimary: _run,
          onCancel: () => Navigator.of(context).pop(),
          cancelLabel: 'Close',
        ),
      ),
    );
  }
}

class _ProbeResultView extends StatelessWidget {
  const _ProbeResultView({required this.probe});
  final ProbeResult probe;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final status = StatusColors.of(context);
    final allowed = probe.allowed;
    final code = probe.response.status;
    final id = probe.evaluation.identity;
    Widget heading(String s) => Padding(
          padding: const EdgeInsets.only(top: Gap.lg, bottom: 6),
          child: Text(s, style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: scheme.onSurface)),
        );
    Widget mono(String s) => SelectableText(s, style: monoStyle(context, size: 12.5));
    return Container(
      padding: const EdgeInsets.all(Gap.lg),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: scheme.outlineVariant),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Wrap(spacing: 8, runSpacing: 8, crossAxisAlignment: WrapCrossAlignment.center, children: [
          allowed
              ? const YBadge('Would allow', tone: YTone.success, icon: Icons.check_circle_outline)
              : const YBadge('Would deny', tone: YTone.danger, icon: Icons.block),
          if (code != null)
            YBadge('HTTP $code',
                mono: true,
                tone: code >= 200 && code < 300
                    ? YTone.success
                    : code >= 400 && code < 500
                        ? YTone.warning
                        : YTone.danger),
          YBadge('${probe.response.durationMs} ms', mono: true),
          Text('${probe.request.method} ${probe.request.url}', style: monoStyle(context, size: 12.5, color: scheme.onSurfaceVariant)),
        ]),
        if (probe.response.error != null) ...[
          const SizedBox(height: Gap.md),
          YBanner(tone: YBannerTone.error, title: 'Request failed.', message: probe.response.error!),
        ],
        heading('Success criteria'),
        if (probe.evaluation.successChecks.isEmpty)
          Text('No checks ran.', style: TextStyle(fontSize: 14, color: scheme.onSurfaceVariant)),
        for (final c in probe.evaluation.successChecks)
          Padding(
            padding: const EdgeInsets.only(bottom: 4),
            child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Icon(c.matched ? Icons.check_circle : Icons.cancel, size: 18, color: c.matched ? status.success : scheme.error),
              const SizedBox(width: 8),
              Expanded(child: mono('${c.criterion}${c.detail == null ? '' : ': ${c.detail}'}')),
            ]),
          ),
        if (id != null) ...[
          heading('Identity extracted'),
          mono('subject: ${id.subject}'),
          mono('display: ${id.displayName}'),
          if (id.attributes.isNotEmpty) mono('attributes: ${const JsonEncoder.withIndent('  ').convert(id.attributes)}'),
        ],
        if ((probe.evaluation.auditReason ?? probe.auditReason) != null) ...[
          heading('Audit reason (never shown to the person signing in)'),
          mono((probe.evaluation.auditReason ?? probe.auditReason)!),
        ],
        if (probe.response.body != null && probe.response.body!.isNotEmpty) ...[
          heading('Raw response body'),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(color: scheme.surfaceContainerLow, borderRadius: BorderRadius.circular(8)),
            child: mono(probe.response.body!),
          ),
        ],
      ]),
    );
  }
}
