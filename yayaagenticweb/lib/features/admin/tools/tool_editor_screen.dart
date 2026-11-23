import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../api/admin_api.dart';
import '../../../app/live/conflict_banner.dart';
import '../../../app/live/resource_kind.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/tenant.dart';
import '../../../models/admin/tool.dart';
import '../../../ui/data/relative_time.dart';
import '../../../ui/data/y_badge.dart';
import '../../../ui/data/y_states.dart';
import '../../../ui/feedback/toast.dart';
import '../../../ui/form/y_editor_page.dart';
import '../../../ui/form/y_fields.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/form/y_json_field.dart';
import '../../../ui/form/y_key_value_field.dart';
import '../../../ui/tokens.dart';
import '../capabilities/capabilities_screen.dart' show toolsProvider;
import '../shared/admin_shared.dart';
import '../tenants/tenants_screen.dart' show tenantsProvider;
import 'tool_form.dart';
import 'tools_screen.dart';

/// Full-page editor for one tool: `/admin/tools/new` or `/admin/tools/:id`.
/// Editing publishes a new version; running sessions keep the one they started on.
class ToolEditorScreen extends ConsumerWidget {
  const ToolEditorScreen({super.key, this.toolId, this.duplicateOf});

  /// Null for a new tool.
  final String? toolId;

  /// New tool prefilled from this one (Duplicate).
  final String? duplicateOf;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) return const TenantScopedEmptyState(resourceLabel: 'Tools');
    final summaries = ref.watch(toolSummariesProvider);
    final lookup = toolId ?? duplicateOf;
    if (lookup == null) return _ToolEditor(key: ValueKey('new:$tenant'), tenant: tenant);
    return summaries.when(
      skipLoadingOnRefresh: true,
      skipLoadingOnReload: true,
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => YErrorState(error: e, what: 'this tool', onRetry: () => ref.invalidate(toolsProvider)),
      data: (list) {
        final match = list.where((s) => s.latest.id == lookup).firstOrNull;
        if (match == null) {
          return YEmptyState(
            icon: Icons.search_off,
            title: 'No tool “$lookup” on this tenant',
            message: 'It may have been removed, or you may be looking at a different tenant.',
            action: OutlinedButton(onPressed: () => context.go('/admin/tools'), child: const Text('Back to tools')),
          );
        }
        return _ToolEditor(
          key: ValueKey('$tenant:${toolId ?? 'dup'}:$lookup'),
          tenant: tenant,
          summary: toolId != null ? match : null,
          prefill: duplicateOf != null ? match.latest : null,
        );
      },
    );
  }
}

class _ToolEditor extends ConsumerStatefulWidget {
  const _ToolEditor({super.key, required this.tenant, this.summary, this.prefill});
  final String tenant;
  final ToolSummary? summary;
  final ToolResponse? prefill;

  @override
  ConsumerState<_ToolEditor> createState() => _ToolEditorState();
}

class _ToolEditorState extends ConsumerState<_ToolEditor> {
  late ToolDraft _draft;
  late final YFormController _form;
  ToolResponse? _base;
  ({DateTime savedAt, Map<String, Object?> values})? _pendingDraft;
  List<String> _schemaProps = const [];

  final _id = TextEditingController();
  final _bean = TextEditingController();
  final _url = TextEditingController();
  final _bodyType = TextEditingController();
  final _bodyTemplate = TextEditingController();
  final _jsonPath = TextEditingController();
  final _inputSchema = TextEditingController();
  final _outputSchema = TextEditingController();
  final _confirmPrompt = TextEditingController();

  final _basicsKey = GlobalKey();
  final _runsKey = GlobalKey();
  final _schemaKey = GlobalKey();
  final _behaviourKey = GlobalKey();

  bool get _editing => _base != null;

  @override
  void initState() {
    super.initState();
    _base = widget.summary?.latest;
    _draft = _base != null
        ? ToolDraft.fromTool(_base!)
        : widget.prefill != null
            ? (ToolDraft.fromTool(widget.prefill!)..id = '')
            : ToolDraft();
    _syncControllers();
    _form = YFormController(
      snapshot: () => _draft.toSnapshot(),
      validate: _validate,
      labels: toolFieldLabels,
      draftKey: 'yaya.draft.tool.${widget.tenant}.${_base?.id ?? 'new'}',
      baseVersion: _base?.version,
    );
    _form.addListener(_onForm);
    _form.loadDraft().then((d) {
      if (d != null && mounted) setState(() => _pendingDraft = d);
    });
  }

  void _onForm() {
    if (mounted) setState(() {});
  }

  void _syncControllers() {
    _id.text = _draft.id;
    _bean.text = _draft.beanName;
    _url.text = _draft.urlTemplate;
    _bodyType.text = _draft.bodyContentType;
    _bodyTemplate.text = _draft.bodyTemplate;
    _jsonPath.text = _draft.jsonPath;
    _inputSchema.text = _draft.inputSchema;
    _outputSchema.text = _draft.outputSchema;
    _confirmPrompt.text = _draft.confirmPrompt;
  }

  @override
  void dispose() {
    _form.removeListener(_onForm);
    _form.dispose();
    for (final c in [_id, _bean, _url, _bodyType, _bodyTemplate, _jsonPath, _inputSchema, _outputSchema, _confirmPrompt]) {
      c.dispose();
    }
    super.dispose();
  }

  Map<String, String?> _validate() {
    final d = _draft;
    final existing = ref.read(toolSummariesProvider).valueOrNull ?? const [];
    String? idErr = _editing ? null : Validators.toolId(d.id);
    if (idErr == null && !_editing && existing.any((s) => s.latest.id == d.id.trim())) {
      idErr = 'A tool with this ID already exists. Open it to publish a new version, or pick another ID.';
    }
    String? urlErr;
    if (d.kind == 'HTTP') {
      if (d.urlTemplate.trim().isEmpty) {
        urlErr = 'Enter the path this tool calls, e.g. /v1/orders/{orderId}.';
      } else if (isAbsoluteUrl(d.urlTemplate.trim())) {
        urlErr = 'Use a path that starts with / — the host comes from the tenant’s base URL.';
      }
    }
    return {
      'id': idErr,
      'beanName': d.kind == 'BEAN' ? Validators.required(d.beanName, 'the handler bean name') : null,
      'urlTemplate': urlErr,
      'headers': d.kind == 'HTTP' ? YKeyValueField.validate(d.headers.entries, caseInsensitive: true) : null,
      'inputSchema': Validators.jsonObject(d.inputSchema).$1,
      'outputSchema': Validators.jsonObject(d.outputSchema).$1,
      'confirmPrompt': d.plannable && d.plannableBlocker == null && d.confirmPrompt.trim().isEmpty
          ? 'Grouped confirmations need a prompt to show for this step.'
          : null,
    };
  }

  void _set(void Function(ToolDraft d) change, [String? field]) {
    setState(() => change(_draft));
    if (field != null) {
      _form.changed(field);
    } else {
      _form.refresh();
    }
  }

  Future<void> _save() async {
    if (!_form.submit()) return;
    final api = await ref.read(adminApiProvider.future);
    _form.saving = true;
    try {
      final carried = Map<String, dynamic>.from(_base?.policy ?? widget.prefill?.policy ?? const {})
        ..removeWhere((k, _) => ToolDraft.policyKeys.contains(k));
      final saved = await api.createTool(
        _draft.toRequest(tenant: widget.tenant, source: _base ?? widget.prefill, carriedPolicy: carried),
        baseVersion: _form.baseVersion,
      );
      ref.invalidate(toolsProvider);
      _form.saving = false;
      _form.markClean(baseVersion: saved.version);
      if (!mounted) return;
      showToast(context, _editing ? 'Published ${saved.id} v${saved.version}' : 'Created ${saved.id}');
      if (!_editing) context.go('/admin/tools/${Uri.encodeComponent(saved.id)}');
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(e);
    }
  }

  void _cancel() => context.go('/admin/tools');

  void _restoreDraft() {
    final d = _pendingDraft;
    if (d == null) return;
    setState(() {
      _draft.applySnapshot(d.values);
      if (_editing) _draft.id = _base!.id;
      _syncControllers();
      _pendingDraft = null;
    });
    _form.refresh();
  }

  void _discardDraft() {
    _form.clearDraft();
    setState(() => _pendingDraft = null);
  }

  void _reloadLatest(ToolResponse latest) {
    setState(() {
      _base = latest;
      _draft = ToolDraft.fromTool(latest);
      _syncControllers();
    });
    _form.markClean(baseVersion: latest.version);
  }

  Future<void> _review(ToolResponse latest) async {
    final mine = _draft.toSnapshot();
    final base = _base == null ? <String, Object?>{} : ToolDraft.fromTool(_base!).toSnapshot();
    final theirs = ToolDraft.fromTool(latest).toSnapshot();
    final changed = {...base.keys, ...theirs.keys}.where((k) => '${base[k]}' != '${theirs[k]}').toList();
    final overlap = changed.where((k) => '${mine[k]}' != '${base[k]}').toSet();
    await showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('What changed in v${latest.version}'),
        content: SizedBox(
          width: 560,
          child: changed.isEmpty
              ? const Text('Nothing the editor shows changed — only settings it carries through.')
              : SingleChildScrollView(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    for (final k in changed) ...[
                      Text(toolFieldLabels[k] ?? k, style: const TextStyle(fontWeight: FontWeight.w600)),
                      const SizedBox(height: 4),
                      _DiffLine(sign: '−', text: '${base[k] ?? '—'}', tone: YTone.danger),
                      _DiffLine(sign: '+', text: '${theirs[k] ?? '—'}', tone: YTone.success),
                      if (overlap.contains(k))
                        Padding(
                          padding: const EdgeInsets.only(top: 4),
                          child: Text('You changed this too — keeping yours replaces theirs.',
                              style: TextStyle(fontSize: 13, color: StatusColors.of(ctx).onWarningContainer)),
                        ),
                      const SizedBox(height: 12),
                    ],
                  ]),
                ),
        ),
        actions: [
          OutlinedButton(
            onPressed: () {
              Navigator.of(ctx).pop();
              _reloadLatest(latest);
            },
            child: Text('Discard mine, load v${latest.version}'),
          ),
          FilledButton(
            onPressed: () {
              Navigator.of(ctx).pop();
              setState(() => _form.baseVersion = latest.version);
            },
            child: const Text('Keep my changes'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final summary = ref.watch(toolSummariesProvider).valueOrNull?.where((s) => s.latest.id == _base?.id).firstOrNull;
    final latest = summary?.latest;
    final usage = _base == null ? const <String>[] : (ref.watch(toolUsageProvider)[_base!.id] ?? const []);
    final d = _draft;
    // Someone published past the version this edit started from and the
    // operator hasn't chosen yet: saving would be a 409, so the primary
    // action is the review instead.
    final unresolved = latest != null && _form.baseVersion != null && latest.version > _form.baseVersion!;
    final nextVersion = (_form.baseVersion ?? 0) + 1;
    final primary = !_editing
        ? 'Create tool'
        : unresolved
            ? (_form.isDirty ? 'Review v${latest.version} first' : 'Load v${latest.version}')
            : 'Publish v$nextVersion';

    return YEditorPage(
      form: _form,
      title: _editing ? _base!.id : 'New tool',
      subtitle: _editing
          ? 'Editing publishes a new version. Conversations already running keep the version they started with.'
          : 'Tools are the actions a capability can take. Fields marked * are required.',
      badges: [
        if (_editing) YBadge('v${latest?.version ?? _base!.version} is live', tone: YTone.success, icon: Icons.circle),
      ],
      headerActions: [
        if (_editing)
          OutlinedButton.icon(
            onPressed: () => context.go('/admin/tools/new?from=${Uri.encodeComponent(_base!.id)}'),
            icon: const Icon(Icons.copy, size: 16),
            label: const Text('Duplicate'),
          ),
      ],
      onBack: _cancel,
      backLabel: 'Tools',
      primaryLabel: primary,
      onPrimary: unresolved && _form.isDirty ? () => _review(latest) : (unresolved ? () => _reloadLatest(latest) : _save),
      onCancel: _cancel,
      actionVerb: _editing ? 'publishing' : 'creating this tool',
      banners: [
        if (_pendingDraft != null)
          YBanner(
            tone: YBannerTone.info,
            title: 'You have an unsaved draft',
            message: 'from ${formatRelative(_pendingDraft!.savedAt).toLowerCase()} on this device.',
            actions: [
              FilledButton(onPressed: _restoreDraft, child: const Text('Restore draft')),
              OutlinedButton(onPressed: _discardDraft, child: const Text('Discard it')),
            ],
          ),
        if (_editing && latest != null)
          LiveConflictBanner(
            kind: AdminResourceKind.tool,
            id: _base!.id,
            baseVersion: _form.baseVersion,
            dirty: _form.isDirty,
            noun: 'This tool',
            onReview: () => _form.isDirty ? _review(latest) : _reloadLatest(latest),
            onKeepMine: (v) => setState(() => _form.baseVersion = v),
          ),
      ],
      sections: [
        YSectionLink('Basics', _basicsKey, fields: const ['id']),
        YSectionLink('How it runs', _runsKey, fields: const ['kind', 'beanName', 'method', 'urlTemplate', 'headers', 'bodyContentType', 'bodyTemplate', 'jsonPath', 'authForwarding']),
        YSectionLink('Schemas', _schemaKey, fields: const ['inputSchema', 'outputSchema']),
        YSectionLink('Behaviour', _behaviourKey, fields: const ['confirmable', 'confirmPrompt', 'clientInvocable', 'secretInput', 'plannable', 'headless']),
      ],
      aside: _editing ? _aside(context, summary, usage, nextVersion) : null,
      children: [
        YFormSection(
          sectionKey: _basicsKey,
          title: 'Basics',
          children: [
            YTextField(
              label: 'Tool ID',
              wireKey: 'id',
              controller: _id,
              form: _form,
              name: 'id',
              required: !_editing,
              mono: true,
              readOnly: _editing,
              autofocus: !_editing,
              hint: 'e.g. orders_refund',
              helper: _editing
                  ? 'Fixed — capabilities refer to the tool by this ID.'
                  : 'Letters, digits, underscores and dashes. It is also the name the model sees, and can’t change later.',
              onChanged: (v) => _draft.id = v,
            ),
          ],
        ),
        YFormSection(
          sectionKey: _runsKey,
          title: 'How it runs',
          children: [
            YEnumCards<String>(
              label: 'Transport',
              wireKey: 'handler.kind',
              horizontal: true,
              value: d.kind,
              form: _form,
              name: 'kind',
              options: const [
                YEnumOption('HTTP', 'HTTP request', description: 'Calls a path on the tenant’s service — e.g. yaya-sales.'),
                YEnumOption('BEAN', 'In-process bean', description: 'Runs a Java ToolHandler deployed with the backend.'),
              ],
              onChanged: (v) => _set((d) {
                d.kind = v;
                if (v == 'BEAN') d.headless = false;
              }),
            ),
            if (d.kind == 'BEAN')
              YTextField(
                label: 'Handler bean',
                wireKey: 'handler.beanName',
                controller: _bean,
                form: _form,
                name: 'beanName',
                required: true,
                mono: true,
                hint: 'e.g. orderLookupHandler',
                helper: 'The Spring bean name of a @Component implementing ToolHandler.',
                onChanged: (v) => _draft.beanName = v,
              )
            else ..._httpFields(context),
          ],
        ),
        YFormSection(
          sectionKey: _schemaKey,
          title: 'Schemas',
          description: 'JSON Schema for what the model sends and what the tool returns. Input properties become values you can use in the path, headers and body.',
          children: [
            YJsonField(
              label: 'Input schema',
              wireKey: 'inputSchemaJson',
              controller: _inputSchema,
              form: _form,
              name: 'inputSchema',
              required: true,
              schemaAware: true,
              onChanged: () => _draft.inputSchema = _inputSchema.text,
              onProperties: (p) {
                if (p.join(',') != _schemaProps.join(',') && mounted) setState(() => _schemaProps = p);
              },
            ),
            YJsonField(
              label: 'Output schema',
              wireKey: 'outputSchemaJson',
              controller: _outputSchema,
              form: _form,
              name: 'outputSchema',
              required: true,
              minLines: 3,
              onChanged: () => _draft.outputSchema = _outputSchema.text,
            ),
          ],
        ),
        YFormSection(
          sectionKey: _behaviourKey,
          title: 'Behaviour',
          children: [
            YSwitchField(
              title: 'Ask before running',
              wireKey: 'confirmable',
              description: 'Pauses for an explicit yes/no from the user. Use for anything that changes data or moves money.',
              value: d.confirmable,
              onChanged: (v) => _set((d) {
                d.confirmable = v;
                if (!v) d.plannable = false;
                if (v) d.headless = false;
              }, 'confirmable'),
            ),
            if (d.confirmable)
              YTextField(
                label: 'Confirmation prompt',
                wireKey: 'policy.confirmPrompt',
                controller: _confirmPrompt,
                form: _form,
                name: 'confirmPrompt',
                optional: !d.plannable,
                required: d.plannable,
                hint: 'e.g. Refund {amount} to the original card?',
                helper: 'What the user is asked. Leave blank to let the engine describe the call.',
                onChanged: (v) => _draft.confirmPrompt = v,
              ),
            YSwitchField(
              title: 'Group with other confirmations',
              wireKey: 'plannable',
              description: 'Several confirmable calls in one answer share a single card the user approves once.',
              value: d.plannable,
              disabledReason: d.plannableBlocker,
              onChanged: (v) => _set((d) => d.plannable = v, 'plannable'),
            ),
            YSwitchField(
              title: 'Runnable from the chat UI',
              wireKey: 'clientInvocable',
              description: 'Lets a button tap (cart edit, checkout) run this tool via POST /sessions/{id}/actions. Off keeps it model-only.',
              value: d.clientInvocable,
              onChanged: (v) => _set((d) {
                d.clientInvocable = v;
                if (v) d.headless = false;
              }, 'clientInvocable'),
            ),
            YSwitchField(
              title: 'Collects a secret code',
              wireKey: 'secretInput',
              description: 'The tool may ask the user for a code; the value reaches it as {secret.value} in a header, never as an argument the model sees.',
              value: d.secretInput,
              disabledReason: d.plannable ? 'Not available while Group with other confirmations is on.' : null,
              onChanged: (v) => _set((d) {
                d.secretInput = v;
                if (v) d.headless = false;
              }, 'secretInput'),
            ),
            YSwitchField(
              title: 'Headless runs',
              wireKey: 'headless',
              description: 'A stored question answered with nobody present may call this tool. For unattended reads only.',
              value: d.headless,
              disabledReason: d.headless ? null : d.headlessBlocker,
              onChanged: (v) => _set((d) => d.headless = v, 'headless'),
            ),
          ],
        ),
      ],
    );
  }

  List<Widget> _httpFields(BuildContext context) {
    final d = _draft;
    final scheme = Theme.of(context).colorScheme;
    final tenant = ref.watch(tenantsProvider).valueOrNull?.where((TenantResponse t) => t.id == widget.tenant).firstOrNull;
    final tenantOrigin = originOf(tenant?.hostBaseUrl ?? '');
    final absolute = isAbsoluteUrl(d.urlTemplate.trim());
    final canExtract = absolute && tenantOrigin != null && originOf(d.urlTemplate.trim()) == tenantOrigin;
    final vars = [..._schemaProps, 'principal.sub'];
    final hasBody = d.bodyTemplate.trim().isNotEmpty || !(d.method == 'GET' || d.method == 'DELETE');
    return [
      Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        SizedBox(
          width: 160,
          child: YSelectField<String>(
            label: 'Method',
            wireKey: 'method',
            form: _form,
            name: 'method',
            mono: true,
            value: d.method,
            options: [for (final m in const ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']) YOption(m, m)],
            onChanged: (v) => _set((d) {
              d.method = v ?? 'GET';
              if (d.method != 'GET') d.headless = false;
            }),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: YTextField(
            label: 'Request path',
            wireKey: 'httpSpec.urlTemplate',
            controller: _url,
            form: _form,
            name: 'urlTemplate',
            required: true,
            mono: true,
            hint: '/v1/orders/{orderId}/refund',
            helper: tenant?.hostBaseUrl == null || tenant!.hostBaseUrl!.isEmpty
                ? 'Path only. The host comes from the tenant’s base URL (none is set yet — add one under Tenants).'
                : 'Path only. Calls go to ${tenant.hostBaseUrl}.',
            onChanged: (v) => _set((d) => d.urlTemplate = v),
          ),
        ),
      ]),
      if (canExtract)
        Align(
          alignment: Alignment.centerLeft,
          child: TextButton.icon(
            onPressed: () {
              _url.text = extractPath(_url.text.trim());
              _set((d) => d.urlTemplate = _url.text, 'urlTemplate');
            },
            icon: const Icon(Icons.content_cut, size: 16),
            label: Text('This matches the tenant’s host — keep just “${extractPath(d.urlTemplate.trim())}”'),
          ),
        ),
      Wrap(spacing: 6, runSpacing: 6, crossAxisAlignment: WrapCrossAlignment.center, children: [
        Text('Insert a value:', style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant)),
        for (final v in vars)
          ActionChip(
            label: Text('{$v}', style: monoStyle(context, size: 12)),
            onPressed: () {
              _url.text = '${_url.text}{$v}';
              _set((d) => d.urlTemplate = _url.text, 'urlTemplate');
            },
          ),
        if (_schemaProps.isEmpty)
          Text('Add properties to the input schema to insert them here.', style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant)),
      ]),
      YKeyValueField(
        label: 'Headers',
        wireKey: 'httpSpec.headerTemplates',
        form: _form,
        name: 'headers',
        value: d.headers,
        caseInsensitiveKeys: true,
        keyHint: 'Header',
        valueHint: 'Value or {template}',
        addLabel: 'Add header',
        helper: 'Values can use {templates}. A secret code from “Collects a secret code” arrives as {secret.value}.',
        onChanged: (m) => _set((d) => d.headers = m),
      ),
      if (hasBody) ...[
        YTextField(
          label: 'Body content type',
          wireKey: 'httpSpec.body.contentType',
          controller: _bodyType,
          form: _form,
          name: 'bodyContentType',
          optional: true,
          mono: true,
          onChanged: (v) => _set((d) => d.bodyContentType = v),
        ),
        YTextField(
          label: 'Body template',
          wireKey: 'httpSpec.body.template',
          controller: _bodyTemplate,
          form: _form,
          name: 'bodyTemplate',
          optional: true,
          mono: true,
          maxLines: 8,
          minLines: 3,
          hint: '{ "orderId": "{orderId}" }',
          helper: 'Leave empty to send no body.',
          onChanged: (v) => _set((d) => d.bodyTemplate = v),
        ),
      ],
      YTextField(
        label: 'Response JSON path',
        wireKey: 'httpSpec.response.jsonPath',
        controller: _jsonPath,
        form: _form,
        name: 'jsonPath',
        optional: true,
        mono: true,
        helper: r'Which part of the response the model sees. $ is the whole body.',
        onChanged: (v) => _set((d) => d.jsonPath = v),
      ),
      YEnumCards<String>(
        label: 'Credentials sent to the service',
        wireKey: 'httpSpec.authForwarding',
        required: true,
        form: _form,
        name: 'authForwarding',
        value: d.authForwarding,
        options: const [
          YEnumOption('NONE', 'None', wire: 'NONE', description: 'The service receives no user credentials. The safe default.'),
          YEnumOption('PRINCIPAL_TOKEN', 'The user’s token', wire: 'PRINCIPAL_TOKEN', caution: true,
              description: 'The verified user’s bearer token is forwarded so the service can act as them.'),
          YEnumOption('SERVICE_TOKEN', 'A service token', wire: 'SERVICE_TOKEN',
              description: 'A token for this deployment, not the user. For back-office APIs.'),
        ],
        onChanged: (v) => _set((d) => d.authForwarding = v),
      ),
      if (d.authForwarding == 'PRINCIPAL_TOKEN')
        YBanner(
          tone: YBannerTone.warning,
          message: 'The signed-in user’s token will leave yaya-agentic for ${tenant?.hostBaseUrl?.isNotEmpty == true ? tenant!.hostBaseUrl : 'the tenant’s service'}. Only choose this for services you trust with it.',
        ),
    ];
  }

  Widget _aside(BuildContext context, ToolSummary? summary, List<String> usage, int nextVersion) {
    final scheme = Theme.of(context).colorScheme;
    final changed = _form.changedFields;
    final current = _form.baseVersion ?? _base!.version;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Card(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('Publishing creates v$nextVersion', style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
            const SizedBox(height: 8),
            Text('New conversations use v$nextVersion. Conversations already running stay on v$current until they end.',
                style: TextStyle(fontSize: 14, height: 1.5, color: scheme.onSurface)),
            const Divider(height: 24),
            Text('YOUR CHANGES', style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, letterSpacing: 0.4, color: scheme.onSurfaceVariant)),
            const SizedBox(height: 6),
            if (changed.isEmpty)
              Text('Nothing yet.', style: TextStyle(fontSize: 14, color: scheme.onSurfaceVariant))
            else
              for (final f in changed)
                Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: Text('~ ${toolFieldLabels[f] ?? f}', style: const TextStyle(fontSize: 14)),
                ),
          ]),
        ),
      ),
      const SizedBox(height: 16),
      Card(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('Used by', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
            const SizedBox(height: 8),
            if (usage.isEmpty)
              Text('No capability lists this tool yet, so no profile can call it.',
                  style: TextStyle(fontSize: 14, height: 1.5, color: scheme.onSurfaceVariant))
            else
              for (final c in usage)
                InkWell(
                  onTap: () => context.go('/admin/capabilities?id=${Uri.encodeComponent(c)}'),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 4),
                    child: Text(c, style: monoStyle(context, size: 13, color: scheme.primary)),
                  ),
                ),
          ]),
        ),
      ),
      if (summary != null && summary.versions.length > 1) ...[
        const SizedBox(height: 16),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(20),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('Versions', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
              const SizedBox(height: 8),
              for (final v in summary.versions.take(8))
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 3),
                  child: Row(children: [
                    Text('v${v.version}', style: monoStyle(context, size: 13, weight: FontWeight.w500)),
                    const SizedBox(width: 12),
                    Text(formatRelativeIso(v.createdAt), style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant)),
                    if (v.version == summary.latest.version) ...[const Spacer(), const YBadge('live', tone: YTone.success)],
                  ]),
                ),
            ]),
          ),
        ),
      ],
    ]);
  }
}

class _DiffLine extends StatelessWidget {
  const _DiffLine({required this.sign, required this.text, required this.tone});
  final String sign;
  final String text;
  final YTone tone;

  @override
  Widget build(BuildContext context) {
    final (bg, fg) = toneColors(context, tone);
    return Container(
      width: double.infinity,
      color: bg,
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      child: Text('$sign $text', maxLines: 6, overflow: TextOverflow.ellipsis, style: monoStyle(context, size: 12, color: fg)),
    );
  }
}
