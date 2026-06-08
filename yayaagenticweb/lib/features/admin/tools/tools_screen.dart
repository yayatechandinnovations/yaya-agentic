import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../models/admin/tenant.dart';
import '../../../models/admin/tool.dart';
import '../capabilities/capabilities_screen.dart' show toolsProvider;
import '../shared/admin_shared.dart';
import '../tenants/tenants_screen.dart' show tenantsProvider;

class ToolsScreen extends ConsumerStatefulWidget {
  const ToolsScreen({super.key});
  @override
  ConsumerState<ToolsScreen> createState() => _ToolsScreenState();
}

class _ToolsScreenState extends ConsumerState<ToolsScreen> {
  final _idCtrl = TextEditingController();
  final _inputSchemaCtrl = TextEditingController(text: '{"type":"object"}');
  final _outputSchemaCtrl = TextEditingController(text: '{"type":"object"}');

  String _kind = 'BEAN';
  final _beanNameCtrl = TextEditingController();
  bool _confirmable = false;

  String _httpMethod = 'GET';
  final _urlCtrl = TextEditingController();
  final _headersCtrl = TextEditingController(); // k=v lines
  final _bodyContentTypeCtrl = TextEditingController(text: 'application/json');
  final _bodyTemplateCtrl = TextEditingController();
  final _jsonPathCtrl = TextEditingController(text: r'$');
  String _authForwarding = 'NONE';

  bool _saving = false;

  /// Non-null when the form was populated from a "Clone & edit" action.
  /// Persists from form prefill until the next successful save, then resets.
  int? _cloningFromVersion;

  void _cloneEditFrom(ToolResponse t) {
    setState(() {
      _idCtrl.text = t.id;
      _inputSchemaCtrl.text = t.inputSchemaJson;
      _outputSchemaCtrl.text = t.outputSchemaJson;
      _kind = t.handler.kind;
      _beanNameCtrl.text = t.handler.beanName ?? '';
      final http = t.handler.httpSpec;
      if (http != null) {
        _httpMethod = http.method;
        _urlCtrl.text = http.urlTemplate;
        _headersCtrl.text = http.headerTemplates.entries
            .map((e) => '${e.key}=${e.value}')
            .join('\n');
        _bodyContentTypeCtrl.text =
            http.body?.contentType ?? 'application/json';
        _bodyTemplateCtrl.text = http.body?.template ?? '';
        _jsonPathCtrl.text = http.response?.jsonPath ?? r'$';
        _authForwarding = http.authForwarding;
      }
      _confirmable = t.policy['confirmable'] == true;
      _cloningFromVersion = t.version;
    });
    showSnack(context,
        'Cloned ${t.id}@${t.version} → will save as v${t.version + 1}');
  }

  void _cancelClone() {
    setState(() {
      _cloningFromVersion = null;
      _idCtrl.clear();
      _beanNameCtrl.clear();
      _urlCtrl.clear();
      _headersCtrl.clear();
      _bodyTemplateCtrl.clear();
      _inputSchemaCtrl.text = '{"type":"object"}';
      _outputSchemaCtrl.text = '{"type":"object"}';
      _jsonPathCtrl.text = r'$';
      _kind = 'BEAN';
      _confirmable = false;
      _authForwarding = 'NONE';
    });
  }

  @override
  void dispose() {
    _idCtrl.dispose();
    _inputSchemaCtrl.dispose();
    _outputSchemaCtrl.dispose();
    _beanNameCtrl.dispose();
    _urlCtrl.dispose();
    _headersCtrl.dispose();
    _bodyContentTypeCtrl.dispose();
    _bodyTemplateCtrl.dispose();
    _jsonPathCtrl.dispose();
    super.dispose();
  }

  Map<String, String> _parseHeaders(String raw) {
    final out = <String, String>{};
    for (final line in raw.split('\n')) {
      final trimmed = line.trim();
      if (trimmed.isEmpty) continue;
      final ix = trimmed.indexOf('=');
      if (ix <= 0) continue;
      out[trimmed.substring(0, ix).trim()] = trimmed.substring(ix + 1).trim();
    }
    return out;
  }

  Future<void> _save() async {
    if (_idCtrl.text.trim().isEmpty) {
      showSnack(context, 'id is required', error: true);
      return;
    }
    ToolHandlerDto handler;
    if (_kind == 'BEAN') {
      if (_beanNameCtrl.text.trim().isEmpty) {
        showSnack(context, 'BEAN tools need a beanName', error: true);
        return;
      }
      handler = ToolHandlerDto(kind: 'BEAN', beanName: _beanNameCtrl.text.trim());
    } else {
      if (_urlCtrl.text.trim().isEmpty) {
        showSnack(context, 'HTTP tools need a urlTemplate', error: true);
        return;
      }
      handler = ToolHandlerDto(
        kind: 'HTTP',
        httpSpec: HttpHandlerDto(
          method: _httpMethod,
          urlTemplate: _urlCtrl.text.trim(),
          headerTemplates: _parseHeaders(_headersCtrl.text),
          body: _bodyTemplateCtrl.text.trim().isEmpty
              ? null
              : HttpBodyDto(
                  contentType: _bodyContentTypeCtrl.text.trim(),
                  template: _bodyTemplateCtrl.text,
                ),
          response: HttpResponseDto(
            jsonPath: _jsonPathCtrl.text.trim().isEmpty ? r'$' : _jsonPathCtrl.text.trim(),
            headerOutputs: const {},
          ),
          authForwarding: _authForwarding,
        ),
      );
    }

    setState(() => _saving = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.createTool(ToolRequest(
        tenant: 'default',
        id: _idCtrl.text.trim(),
        inputSchemaJson: _inputSchemaCtrl.text,
        outputSchemaJson: _outputSchemaCtrl.text,
        handler: handler,
        policy: {'confirmable': _confirmable},
      ));
      ref.invalidate(toolsProvider);
      if (!mounted) return;
      final wasCloning = _cloningFromVersion;
      _idCtrl.clear();
      _beanNameCtrl.clear();
      _urlCtrl.clear();
      _headersCtrl.clear();
      _bodyTemplateCtrl.clear();
      _jsonPathCtrl.text = r'$';
      setState(() => _cloningFromVersion = null);
      showSnack(context,
          wasCloning == null
              ? 'tool saved'
              : 'saved as v${wasCloning + 1}');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final tools = ref.watch(toolsProvider);
    return AdminTwoPane(
      title: 'Tools',
      subtitle: 'BEAN runs in-process; HTTP is a declarative remote call with explicit auth forwarding.',
      list: tools.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Text(formatError(e), style: TextStyle(color: Theme.of(context).colorScheme.error)),
        data: (list) => list.isEmpty
            ? const Center(child: Text('No tools yet.'))
            : ListView.separated(
                itemBuilder: (_, i) => _Tile(
                  t: list[i],
                  onCloneEdit: () => _cloneEditFrom(list[i]),
                ),
                separatorBuilder: (_, __) => const SizedBox(height: 8),
                itemCount: list.length,
              ),
      ),
      form: SingleChildScrollView(child: _form(context)),
    );
  }

  Widget _form(BuildContext context) {
    final cloning = _cloningFromVersion;
    return FormCard(
      title: cloning == null
          ? 'New tool'
          : 'Editing clone of ${_idCtrl.text}@$cloning → saves as v${cloning + 1}',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          TextField(controller: _idCtrl, decoration: const InputDecoration(labelText: 'id')),
          const SizedBox(height: 8),
          TextField(
            controller: _inputSchemaCtrl,
            maxLines: 4,
            style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
            decoration: const InputDecoration(labelText: 'inputSchemaJson'),
          ),
          const SizedBox(height: 8),
          TextField(
            controller: _outputSchemaCtrl,
            maxLines: 4,
            style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
            decoration: const InputDecoration(labelText: 'outputSchemaJson'),
          ),
          const SizedBox(height: 12),
          SegmentedButton<String>(
            segments: const [
              ButtonSegment(value: 'BEAN', label: Text('BEAN')),
              ButtonSegment(value: 'HTTP', label: Text('HTTP')),
            ],
            selected: {_kind},
            onSelectionChanged: (s) => setState(() => _kind = s.first),
          ),
          const SizedBox(height: 12),
          if (_kind == 'BEAN')
            TextField(
              controller: _beanNameCtrl,
              decoration: const InputDecoration(
                labelText: 'bean name',
                helperText: 'must resolve to a @Component ToolHandler in the backend',
              ),
            )
          else
            _httpForm(context),
          const SizedBox(height: 12),
          SwitchListTile(
            value: _confirmable,
            onChanged: (v) => setState(() => _confirmable = v),
            title: const Text('Confirmable'),
            subtitle: const Text(
                'Pause for explicit yes/no before dispatch. Use for destructive actions.',
                style: TextStyle(fontSize: 12)),
            contentPadding: EdgeInsets.zero,
          ),
        ],
      ),
      actions: [
        if (_cloningFromVersion != null)
          TextButton(
            onPressed: _saving ? null : _cancelClone,
            child: const Text('Cancel'),
          ),
        FilledButton(
          onPressed: _saving ? null : _save,
          child: _saving
              ? const SizedBox.square(dimension: 16, child: CircularProgressIndicator(strokeWidth: 2))
              : Text(_cloningFromVersion == null
                  ? 'Save'
                  : 'Save as v${_cloningFromVersion! + 1}'),
        ),
      ],
    );
  }

  Widget _httpForm(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        DropdownButtonFormField<String>(
          initialValue: _httpMethod,
          decoration: const InputDecoration(labelText: 'method'),
          items: const ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']
              .map((m) => DropdownMenuItem(value: m, child: Text(m)))
              .toList(),
          onChanged: (v) => setState(() => _httpMethod = v ?? 'GET'),
        ),
        const SizedBox(height: 8),
        _PathOnlyUrlField(
          controller: _urlCtrl,
          onChanged: () => setState(() {}),
        ),
        const SizedBox(height: 8),
        TextField(
          controller: _headersCtrl,
          maxLines: 3,
          style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
          decoration: const InputDecoration(
            labelText: 'header templates',
            helperText: 'one per line: Key=Value (placeholders work too)',
          ),
        ),
        const SizedBox(height: 8),
        TextField(
          controller: _bodyContentTypeCtrl,
          decoration: const InputDecoration(labelText: 'body content-type'),
        ),
        const SizedBox(height: 8),
        TextField(
          controller: _bodyTemplateCtrl,
          maxLines: 5,
          style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
          decoration: const InputDecoration(labelText: 'body template (leave empty for GET/DELETE)'),
        ),
        const SizedBox(height: 8),
        TextField(
          controller: _jsonPathCtrl,
          decoration: const InputDecoration(
            labelText: 'response.jsonPath',
            helperText: r'use $ for the whole body',
          ),
        ),
        const SizedBox(height: 12),
        Text('Auth forwarding', style: Theme.of(context).textTheme.labelLarge),
        ...['NONE', 'PRINCIPAL_TOKEN', 'SERVICE_TOKEN'].map(
          (mode) => RadioListTile<String>(
            value: mode,
            groupValue: _authForwarding,
            onChanged: (v) => setState(() => _authForwarding = v ?? 'NONE'),
            title: Text(mode),
            subtitle: mode == 'PRINCIPAL_TOKEN'
                ? const Text('Forwards the inbound Authorization header. Only use for trusted downstreams.',
                    style: TextStyle(fontSize: 12))
                : null,
          ),
        ),
      ],
    );
  }
}

/// urlTemplate input that enforces the path-only rule (§6 of
/// tenant-registry-design.md) in-place. Absolute URLs surface an inline
/// warning + a single-click "Extract path" affordance when the origin
/// matches the tenant's host_base_url. Anything else just gets a "fix
/// manually" hint — same code path the backend rejects with
/// absolute_url_not_permitted at save time.
class _PathOnlyUrlField extends ConsumerWidget {
  const _PathOnlyUrlField({required this.controller, required this.onChanged});
  final TextEditingController controller;
  final VoidCallback onChanged;

  static const _kTenant = 'default';            // mirrors the form's hardcoded tenant

  bool _isAbsolute(String s) =>
      s.startsWith('//') || s.indexOf('://') > 0;

  String? _originOf(String url) {
    if (url.startsWith('//')) return null;
    final schemeEnd = url.indexOf('://');
    if (schemeEnd <= 0) return null;
    final pathStart = url.indexOf('/', schemeEnd + 3);
    return pathStart < 0
        ? url.toLowerCase()
        : url.substring(0, pathStart).toLowerCase();
  }

  String _extractPath(String url) {
    final origin = _originOf(url);
    if (origin == null) return url;
    final tail = url.substring(origin.length);
    return tail.isEmpty || !tail.startsWith('/') ? '/$tail' : tail;
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final value = controller.text;
    final absolute = _isAbsolute(value);
    final tenants = ref.watch(tenantsProvider);

    final tenantOrigin = tenants.maybeWhen(
      data: (list) {
        for (final TenantResponse t in list) {
          if (t.id == _kTenant) {
            return _originOf(t.hostBaseUrl ?? '');
          }
        }
        return null;
      },
      orElse: () => null,
    );

    final urlOrigin = absolute ? _originOf(value) : null;
    final canExtract = absolute
        && urlOrigin != null
        && tenantOrigin != null
        && urlOrigin == tenantOrigin;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        TextField(
          controller: controller,
          onChanged: (_) => onChanged(),
          decoration: InputDecoration(
            labelText: 'urlTemplate',
            helperText: 'Path only (/...). The host resolves from this tool\'s tenant.',
            helperMaxLines: 2,
            errorText: absolute
                ? 'Absolute URLs are no longer accepted (absolute_url_not_permitted).'
                : null,
          ),
          style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
        ),
        if (absolute) ...[
          const SizedBox(height: 6),
          Row(children: [
            Icon(Icons.warning_amber_rounded,
                size: 16, color: theme.colorScheme.error),
            const SizedBox(width: 6),
            Expanded(
              child: Text(
                canExtract
                    ? 'Origin matches tenant host — click to extract the path.'
                    : 'Origin doesn\'t match this tenant\'s host_base_url; '
                        'fix manually or route via tenant migration.',
                style: TextStyle(
                  fontSize: 12,
                  color: theme.colorScheme.error,
                ),
              ),
            ),
            if (canExtract)
              TextButton.icon(
                onPressed: () {
                  controller.text = _extractPath(value);
                  onChanged();
                },
                icon: const Icon(Icons.content_cut, size: 16),
                label: const Text('Extract path'),
              ),
          ]),
        ],
      ],
    );
  }
}

class _Tile extends StatelessWidget {
  const _Tile({required this.t, required this.onCloneEdit});
  final ToolResponse t;
  final VoidCallback onCloneEdit;

  @override
  Widget build(BuildContext context) {
    final isHttp = t.handler.kind == 'HTTP';
    final confirmable = t.policy['confirmable'] == true;
    final http = t.handler.httpSpec;
    return EditableRecordCard(
      onCloneEdit: onCloneEdit,
      leading: Icon(isHttp ? Icons.cloud_outlined : Icons.memory_outlined),
      header: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            Text('${t.id}@${t.version}',
                style: Theme.of(context).textTheme.titleSmall),
            if (confirmable) ...[
              const SizedBox(width: 8),
              Tooltip(
                message: 'Confirmable: preview → yes/no before dispatch',
                child: Icon(Icons.priority_high,
                    size: 16, color: Theme.of(context).colorScheme.error),
              ),
            ],
          ]),
          Text(isHttp
              ? '${http?.method} ${http?.urlTemplate}'
              : 'bean: ${t.handler.beanName}'),
        ],
      ),
      trailing: Chip(
        label: Text(t.handler.kind),
        visualDensity: VisualDensity.compact,
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          DetailRow(label: 'inputSchemaJson', value: t.inputSchemaJson, mono: true),
          DetailRow(label: 'outputSchemaJson', value: t.outputSchemaJson, mono: true),
          DetailRow(label: 'handler.kind', value: t.handler.kind),
          if (t.handler.beanName != null)
            DetailRow(label: 'handler.beanName', value: t.handler.beanName!),
          if (http != null) ...[
            DetailRow(label: 'http.method', value: http.method),
            DetailRow(label: 'http.urlTemplate', value: http.urlTemplate),
            DetailRow(
              label: 'http.headers',
              value: http.headerTemplates.isEmpty
                  ? '(none)'
                  : http.headerTemplates.entries
                      .map((e) => '${e.key}=${e.value}')
                      .join('\n'),
              mono: true,
            ),
            if (http.body != null)
              DetailRow(
                label: 'http.body',
                value: '${http.body!.contentType}\n${http.body!.template}',
                mono: true,
              ),
            DetailRow(label: 'http.response.jsonPath', value: http.response?.jsonPath ?? r'$'),
            DetailRow(label: 'http.authForwarding', value: http.authForwarding),
          ],
          DetailRow(
            label: 'policy',
            value: t.policy.entries.map((e) => '${e.key}=${e.value}').join('  '),
          ),
        ],
      ),
    );
  }
}
