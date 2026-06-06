import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../models/admin/tool.dart';
import '../capabilities/capabilities_screen.dart' show toolsProvider;
import '../shared/admin_shared.dart';

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

  String _httpMethod = 'GET';
  final _urlCtrl = TextEditingController();
  final _headersCtrl = TextEditingController(); // k=v lines
  final _bodyContentTypeCtrl = TextEditingController(text: 'application/json');
  final _bodyTemplateCtrl = TextEditingController();
  final _jsonPathCtrl = TextEditingController(text: r'$');
  String _authForwarding = 'NONE';

  bool _saving = false;

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
      ));
      ref.invalidate(toolsProvider);
      if (!mounted) return;
      _idCtrl.clear();
      _beanNameCtrl.clear();
      _urlCtrl.clear();
      _headersCtrl.clear();
      _bodyTemplateCtrl.clear();
      _jsonPathCtrl.text = r'$';
      showSnack(context, 'tool saved');
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
                itemBuilder: (_, i) => _Tile(t: list[i]),
                separatorBuilder: (_, __) => const SizedBox(height: 8),
                itemCount: list.length,
              ),
      ),
      form: SingleChildScrollView(child: _form(context)),
    );
  }

  Widget _form(BuildContext context) {
    return FormCard(
      title: 'New / new-version tool',
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
        ],
      ),
      actions: [
        FilledButton(
          onPressed: _saving ? null : _save,
          child: _saving
              ? const SizedBox.square(dimension: 16, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Save'),
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
        TextField(
          controller: _urlCtrl,
          decoration: const InputDecoration(
            labelText: 'urlTemplate',
            helperText: 'placeholders: {field} or {a.b.c}',
          ),
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

class _Tile extends StatelessWidget {
  const _Tile({required this.t});
  final ToolResponse t;

  @override
  Widget build(BuildContext context) {
    final isHttp = t.handler.kind == 'HTTP';
    return Card(
      child: ListTile(
        leading: Icon(isHttp ? Icons.cloud_outlined : Icons.memory_outlined),
        title: Text('${t.id}@${t.version}'),
        subtitle: Text(isHttp
            ? '${t.handler.httpSpec?.method} ${t.handler.httpSpec?.urlTemplate}'
            : 'bean: ${t.handler.beanName}'),
        trailing: Chip(
          label: Text(t.handler.kind),
          visualDensity: VisualDensity.compact,
        ),
      ),
    );
  }
}
