import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../api/admin_api.dart';
import '../../../app/live/conflict_banner.dart';
import '../../../app/live/resource_kind.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/profile.dart';
import '../../../ui/data/relative_time.dart';
import '../../../ui/data/y_badge.dart';
import '../../../ui/data/y_states.dart';
import '../../../ui/feedback/api_error.dart';
import '../../../ui/feedback/toast.dart';
import '../../../ui/form/y_editor_page.dart';
import '../../../ui/form/y_fields.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/form/y_resource_picker.dart';
import '../../../ui/tokens.dart';
import '../capabilities/capabilities_screen.dart' show toolsProvider;
import '../channel_bindings/sender_routes_section.dart' show channelBindingsProvider;
import '../chats/chats_admin_screen.dart' show chatEntryPointsProvider;
import '../flows/flows_admin_screen.dart' show startFlowsProvider;
import '../identity_routing/identity_routing_screen.dart' show identityProfileMappingsProvider;
import '../shared/admin_shared.dart';
import 'clone_profile_wizard.dart';
import 'profile_form.dart';
import 'profiles_screen.dart';

/// Full-page editor for one profile: `/admin/profiles/new` (optionally
/// `?from=<id>` to duplicate) or `/admin/profiles/:id`. Editing publishes a
/// new version; running sessions keep the one they started on.
class ProfileEditorScreen extends ConsumerWidget {
  const ProfileEditorScreen({super.key, this.profileId, this.duplicateOf});

  /// Null for a new profile.
  final String? profileId;

  /// New profile prefilled from this one (Duplicate).
  final String? duplicateOf;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) return const TenantScopedEmptyState(resourceLabel: 'Profiles');
    final lookup = profileId ?? duplicateOf;
    if (lookup == null) return _ProfileEditor(key: ValueKey('new:$tenant'), tenant: tenant);
    return ref.watch(profileSummariesProvider).when(
          skipLoadingOnRefresh: true,
          skipLoadingOnReload: true,
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (e, _) => YErrorState(error: e, what: 'this profile', onRetry: () => ref.invalidate(profilesProvider)),
          data: (list) {
            final match = list.where((s) => s.latest.id == lookup).firstOrNull;
            if (match == null) {
              return YEmptyState(
                icon: Icons.search_off,
                title: 'No profile “$lookup” on this tenant',
                message: 'It may have been removed, or you may be looking at a different tenant.',
                action: OutlinedButton(onPressed: () => context.go('/admin/profiles'), child: const Text('Back to profiles')),
              );
            }
            return _ProfileEditor(
              key: ValueKey('$tenant:${profileId ?? 'dup'}:$lookup'),
              tenant: tenant,
              summary: profileId != null ? match : null,
              prefill: duplicateOf != null ? match.latest : null,
            );
          },
        );
  }
}

class _ProfileEditor extends ConsumerStatefulWidget {
  const _ProfileEditor({super.key, required this.tenant, this.summary, this.prefill});
  final String tenant;
  final ProfileSummary? summary;
  final ProfileResponse? prefill;

  @override
  ConsumerState<_ProfileEditor> createState() => _ProfileEditorState();
}

class _ProfileEditorState extends ConsumerState<_ProfileEditor> {
  late ProfileDraft _draft;
  late final YFormController _form;

  /// The version this edit started from (null for a new profile) — the
  /// baseline for "what changed".
  ProfileResponse? _base;

  /// Where everything the form doesn't edit is saved from: [_base], the
  /// newer version the operator chose to publish on top of, or the profile
  /// being duplicated. Null for a brand-new profile, which carries nothing.
  ProfileResponse? _source;
  ({DateTime savedAt, Map<String, Object?> values})? _pendingDraft;

  final _id = TextEditingController();
  final _name = TextEditingController();
  final _intro = TextEditingController();
  final _prompt = TextEditingController();
  final _storeId = TextEditingController();
  final _model = TextEditingController();
  final _rounds = TextEditingController();

  final _identityKey = GlobalKey();
  final _instructionsKey = GlobalKey();
  final _capsKey = GlobalKey();
  final _signInKey = GlobalKey();
  final _startKey = GlobalKey();
  final _storeKey = GlobalKey();
  final _advancedKey = GlobalKey();

  static const _advancedFields = ['model', 'maxToolRounds', 'metered'];

  /// Advanced starts collapsed; once one of its fields has had an error it
  /// stays open, so fixing the error doesn't fold it away mid-typing.
  bool _advancedForcedOpen = false;

  bool get _editing => _base != null;

  @override
  void initState() {
    super.initState();
    _base = widget.summary?.latest;
    _source = _base ?? widget.prefill;
    _draft = _base != null
        ? ProfileDraft.fromProfile(_base!)
        : widget.prefill != null
            ? (ProfileDraft.fromProfile(widget.prefill!)
              ..id = ''
              ..displayName = '${widget.prefill!.displayName} (copy)')
            : ProfileDraft();
    _syncControllers();
    _form = YFormController(
      snapshot: () => _draft.toSnapshot(),
      validate: _validate,
      labels: profileFieldLabels,
      draftKey: 'yaya.draft.profile.${widget.tenant}.${_base?.id ?? 'new'}',
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
    _name.text = _draft.displayName;
    _intro.text = _draft.introOneLiner;
    _prompt.text = _draft.systemPrompt;
    _storeId.text = _draft.storeId;
    _model.text = _draft.model;
    _rounds.text = _draft.maxToolRounds;
  }

  @override
  void dispose() {
    _form.removeListener(_onForm);
    _form.dispose();
    for (final c in [_id, _name, _intro, _prompt, _storeId, _model, _rounds]) {
      c.dispose();
    }
    super.dispose();
  }

  Map<String, String?> _validate() {
    final d = _draft;
    final existing = ref.read(profileSummariesProvider).valueOrNull ?? const [];
    String? idErr = _editing ? null : Validators.slugId(d.id, 'a profile ID');
    if (idErr == null && !_editing && existing.any((s) => s.latest.id == d.id.trim())) {
      idErr = 'A profile with this ID already exists. Open it to publish a new version, or pick another ID.';
    }
    // Only judged once the lists have loaded — never "missing" while loading.
    final caps = ref.read(capabilitiesProvider).valueOrNull;
    final missingCaps = caps == null ? const <String>[] : d.capabilities.where((c) => !caps.any((x) => x.id == c)).toList();
    final bindings = ref.read(authBindingsProvider).valueOrNull;
    final binding = d.authBindingId;
    return {
      'id': idErr,
      'displayName': Validators.required(d.displayName, 'a display name'),
      'introOneLiner': Validators.required(d.introOneLiner, 'an intro line'),
      'systemPrompt': Validators.required(d.systemPrompt, 'the instructions for this profile'),
      'capabilities': missingCaps.isEmpty
          ? null
          : '${missingCaps.join(', ')} ${missingCaps.length == 1 ? 'isn’t a capability' : 'aren’t capabilities'} on this tenant — remove ${missingCaps.length == 1 ? 'it' : 'them'} to save.',
      'authBindingId': binding != null && bindings != null && !bindings.any((b) => b.id == binding)
          ? '“$binding” doesn’t exist on this tenant. Pick another binding or None.'
          : null,
      'maxToolRounds': Validators.intInRange(d.maxToolRounds, minToolRounds, maxToolRounds),
    };
  }

  void _set(void Function(ProfileDraft d) change, [String? field]) {
    setState(() => change(_draft));
    if (field != null) {
      _form.changed(field);
    } else {
      _form.refresh();
    }
  }

  /// The admin API names a field only in its message for these; put them
  /// under the field anyway.
  Object _withFieldErrors(Object e) {
    final info = ApiErrorInfo.from(e);
    if (info.fieldErrors.isNotEmpty || info.isVersionConflict) return info;
    final m = info.message;
    final field = switch (info.code) {
      'model_not_allowed' => 'model',
      'max_tool_rounds_out_of_range' => 'maxToolRounds',
      _ => m.contains('unknown capability')
          ? 'capabilities'
          : m.contains('unknown auth binding')
              ? 'authBindingId'
              : m.contains('profile.displayName')
                  ? 'displayName'
                  : m.contains('profile.introOneLiner')
                      ? 'introOneLiner'
                      : m.contains('profile.systemPromptFragment')
                          ? 'systemPrompt'
                          : null,
    };
    if (field == null) return info;
    return ApiErrorInfo(
      message: m,
      code: info.code,
      status: info.status,
      fieldErrors: {field: field == 'model' ? 'This model isn’t on the deployment’s allow-list. Leave it empty to use the deployment’s model.' : m},
      path: info.path,
    );
  }

  Future<void> _save() async {
    if (!_form.submit()) return;
    final api = await ref.read(adminApiProvider.future);
    _form.saving = true;
    try {
      final saved = await api.createProfile(
        _draft.toRequest(tenant: widget.tenant, source: _carrySource()),
        baseVersion: _form.baseVersion,
      );
      ref.invalidate(profilesProvider);
      _form.saving = false;
      if (_editing) {
        setState(() {
          _base = saved;
          _source = saved;
        });
      }
      _form.markClean(baseVersion: saved.version);
      if (!mounted) return;
      showToast(context, _editing ? 'Published ${saved.id} v${saved.version}' : 'Created ${saved.id}');
      if (!_editing) context.go('/admin/profiles/${Uri.encodeComponent(saved.id)}');
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(_withFieldErrors(e));
    }
  }

  void _cancel() => context.go('/admin/profiles');

  /// [_source], or — when the operator chose to publish on top of a version
  /// the list hadn't loaded yet at the time — that version, once it has.
  ProfileResponse? _carrySource() {
    final src = _source;
    final target = _form.baseVersion;
    if (!_editing || src == null || target == null || src.version >= target) return src;
    final summary = ref.read(profileSummariesProvider).valueOrNull?.where((s) => s.latest.id == _base!.id).firstOrNull;
    return summary?.versions.where((v) => v.version == target).firstOrNull ?? src;
  }

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

  void _reloadLatest(ProfileResponse latest) {
    setState(() {
      _base = latest;
      _source = latest;
      _draft = ProfileDraft.fromProfile(latest);
      _syncControllers();
    });
    _form.markClean(baseVersion: latest.version);
  }

  /// Publish on top of [version]: what the form doesn't edit now comes from
  /// that version, so the save doesn't revert someone else's changes there.
  void _keepMine(int version) {
    final summary = ref.read(profileSummariesProvider).valueOrNull?.where((s) => s.latest.id == _base?.id).firstOrNull;
    final newer = summary?.versions.where((v) => v.version == version).firstOrNull ?? summary?.latest;
    setState(() {
      if (newer != null && newer.version >= version) _source = newer;
      _form.baseVersion = version;
    });
  }

  Future<void> _review(ProfileResponse latest) async {
    final mine = _draft.toSnapshot();
    final base = _base == null ? <String, Object?>{} : ProfileDraft.fromProfile(_base!).toSnapshot();
    final theirs = ProfileDraft.fromProfile(latest).toSnapshot();
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
                      Text(profileFieldLabels[k] ?? k, style: const TextStyle(fontWeight: FontWeight.w600)),
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
              _keepMine(latest.version);
            },
            child: const Text('Keep my changes'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final summary = ref.watch(profileSummariesProvider).valueOrNull?.where((s) => s.latest.id == _base?.id).firstOrNull;
    final latest = summary?.latest;
    // Someone published past the version this edit started from and the
    // operator hasn't chosen yet: saving would be a 409, so the primary
    // action is the review instead.
    final unresolved = latest != null && _form.baseVersion != null && latest.version > _form.baseVersion!;
    final nextVersion = (_form.baseVersion ?? 0) + 1;
    final primary = !_editing
        ? 'Create profile'
        : unresolved
            ? (_form.isDirty ? 'Review v${latest.version} first' : 'Load v${latest.version}')
            : 'Publish v$nextVersion';
    if (_form.summary.any((e) => _advancedFields.contains(e.field))) _advancedForcedOpen = true;

    return YEditorPage(
      form: _form,
      title: _editing ? (_draft.displayName.trim().isEmpty ? _base!.id : _draft.displayName.trim()) : 'New profile',
      subtitle: _editing
          ? 'Editing publishes a new version. Conversations already running keep the version they started with.'
          : widget.prefill != null
              ? 'A copy of ${widget.prefill!.id}, saved under a new ID. Fields marked * are required.'
              : 'A profile is a role users talk to. Fields marked * are required.',
      badges: [
        if (_editing) YBadge('v${latest?.version ?? _base!.version} is live', tone: YTone.success, icon: Icons.circle),
        if (_editing) YBadge(_base!.id, mono: true),
      ],
      headerActions: [
        if (_editing) ...[
          OutlinedButton.icon(
            onPressed: () => context.go('/admin/profiles/new?from=${Uri.encodeComponent(_base!.id)}'),
            icon: const Icon(Icons.copy, size: 16),
            label: const Text('Duplicate'),
          ),
          OutlinedButton.icon(
            onPressed: () => CloneProfileWizard.show(context, sourceTenant: widget.tenant, profile: latest ?? _base!),
            icon: const Icon(Icons.move_to_inbox_outlined, size: 16),
            label: const Text('Clone to another tenant…'),
          ),
        ],
      ],
      onBack: _cancel,
      backLabel: 'Profiles',
      primaryLabel: primary,
      onPrimary: unresolved && _form.isDirty ? () => _review(latest) : (unresolved ? () => _reloadLatest(latest) : _save),
      onCancel: _cancel,
      actionVerb: _editing ? 'publishing' : 'creating this profile',
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
            kind: AdminResourceKind.profile,
            id: _base!.id,
            baseVersion: _form.baseVersion,
            dirty: _form.isDirty,
            noun: 'This profile',
            onReview: () => _form.isDirty ? _review(latest) : _reloadLatest(latest),
            onKeepMine: _keepMine,
          ),
      ],
      sections: [
        YSectionLink('Identity', _identityKey, fields: const ['displayName', 'id', 'introOneLiner', 'language']),
        YSectionLink('Instructions', _instructionsKey, fields: const ['systemPrompt']),
        YSectionLink('Capabilities', _capsKey, fields: const ['capabilities']),
        YSectionLink('Sign-in', _signInKey, fields: const ['authBindingId']),
        YSectionLink('Start flow', _startKey, fields: const ['defaultStartFlowId']),
        YSectionLink('Store', _storeKey, fields: const ['storeEnabled', 'storeId', 'checkoutTool', 'cartEnabled']),
        YSectionLink('Advanced', _advancedKey, fields: _advancedFields),
      ],
      aside: _editing ? _aside(context, summary, nextVersion) : null,
      children: [
        _identitySection(),
        _instructionsSection(),
        _capabilitiesSection(),
        _signInSection(),
        _startFlowSection(),
        _storeSection(),
        _advancedSection(),
      ],
    );
  }

  Widget _identitySection() {
    final d = _draft;
    return YFormSection(
      sectionKey: _identityKey,
      title: 'Identity',
      description: 'How this profile introduces itself.',
      children: [
        YTextField(
          label: 'Display name',
          wireKey: 'displayName',
          controller: _name,
          form: _form,
          name: 'displayName',
          required: true,
          autofocus: !_editing,
          hint: 'e.g. Retail assistant',
          helper: 'Shown to users and in this console.',
          onChanged: (v) => _draft.displayName = v,
        ),
        YTextField(
          label: 'Profile ID',
          wireKey: 'id',
          controller: _id,
          form: _form,
          name: 'id',
          required: !_editing,
          mono: true,
          readOnly: _editing,
          hint: 'e.g. retail-customer',
          helper: _editing
              ? 'Fixed — identity routes, senders and chat entry points refer to the profile by this ID.'
              : 'Letters, digits, dots, underscores and dashes. Routes and entry points refer to it, so it can’t change later.',
          onChanged: (v) => _draft.id = v,
        ),
        YTextField(
          label: 'Intro line',
          wireKey: 'introOneLiner',
          controller: _intro,
          form: _form,
          name: 'introOneLiner',
          required: true,
          hint: 'e.g. I can help you find products and track your orders.',
          helper: 'One sentence the agent uses to say what it can help with.',
          onChanged: (v) => _draft.introOneLiner = v,
        ),
        YSelectField<String>(
          label: 'Response language',
          wireKey: 'language',
          form: _form,
          name: 'language',
          required: true,
          value: d.language,
          options: [
            for (final e in profileLanguages.entries) YOption(e.key, e.value, meta: e.key),
            // A profile saved with another tag keeps it.
            if (!profileLanguages.containsKey(d.language)) YOption(d.language, d.language, meta: 'current'),
          ],
          helper: 'The agent answers in this language whatever language the user writes in.',
          onChanged: (v) => _set((d) => d.language = v ?? 'en'),
        ),
      ],
    );
  }

  Widget _instructionsSection() => YFormSection(
        sectionKey: _instructionsKey,
        title: 'Instructions',
        description: 'Added to the system prompt after the shared personality (tone, safety rules), which every profile gets.',
        children: [
          YTextField(
            label: 'Instructions for this role',
            wireKey: 'systemPromptFragment',
            controller: _prompt,
            form: _form,
            name: 'systemPrompt',
            required: true,
            maxLines: 24,
            minLines: 10,
            hint: 'e.g. You help shoppers of Acme find products, check stock and place orders. Ask before adding to the cart.',
            helper: 'Who the user is, what this role does and doesn’t do. Tool and knowledge rules come from capabilities — no need to repeat them.',
            onChanged: (v) => _draft.systemPrompt = v,
          ),
        ],
      );

  Widget _capabilitiesSection() {
    final caps = ref.watch(capabilitiesProvider);
    final latest = <String, ({int version, String label, int tools})>{};
    for (final c in caps.valueOrNull ?? const []) {
      final cur = latest[c.id];
      if (cur == null || c.version > cur.version) latest[c.id] = (version: c.version, label: c.label, tools: c.tools.length);
    }
    final ids = latest.keys.toList()..sort();
    return YFormSection(
      sectionKey: _capsKey,
      title: 'Capabilities',
      description: 'What the agent may do. Only the tools of these capabilities are ever offered to the model.',
      children: [
        YChipsField(
          label: 'Capabilities',
          wireKey: 'capabilities',
          form: _form,
          name: 'capabilities',
          optional: true,
          selected: _draft.capabilities,
          resources: [
            for (final id in ids)
              YResource(
                id: id,
                name: latest[id]!.label,
                meta: 'v${latest[id]!.version} · ${latest[id]!.tools} tool${latest[id]!.tools == 1 ? '' : 's'}',
              ),
          ],
          hint: caps.isLoading && caps.valueOrNull == null ? 'Loading capabilities…' : 'Type to add a capability…',
          helper: 'Each uses its latest version when a conversation starts.',
          onChanged: (v) => _set((d) => d.capabilities = v),
        ),
        if (caps.hasError)
          YBanner(tone: YBannerTone.error, message: 'Couldn’t load this tenant’s capabilities: ${ApiErrorInfo.from(caps.error!).message}'),
      ],
    );
  }

  Widget _signInSection() {
    final bindings = ref.watch(authBindingsProvider);
    return YFormSection(
      sectionKey: _signInKey,
      title: 'Sign-in',
      children: [
        YResourcePicker(
          label: 'Sign-in binding',
          wireKey: 'authBindingId',
          form: _form,
          name: 'authBindingId',
          optional: true,
          loading: bindings.isLoading && bindings.valueOrNull == null,
          value: _draft.authBindingId,
          noneLabel: 'None',
          resources: [
            for (final b in bindings.valueOrNull ?? const [])
              YResource(
                id: b.id,
                meta: [b.authenticatorRef, if (b.authorizerChain.isNotEmpty) '${b.authorizerChain.length} authorizer${b.authorizerChain.length == 1 ? '' : 's'}'].join(' · '),
              ),
          ],
          helper: 'Which authenticator verifies users of this profile and which authorizers check every tool call.',
          onChanged: (v) => _set((d) => d.authBindingId = v),
        ),
      ],
    );
  }

  Widget _startFlowSection() {
    final flows = ref.watch(startFlowsProvider(widget.tenant));
    final byId = <String, ({int version, String name, String status})>{};
    for (final f in flows.valueOrNull ?? const []) {
      final cur = byId[f.id];
      if (cur == null || f.version > cur.version) byId[f.id] = (version: f.version, name: f.name, status: f.status);
    }
    final ids = byId.keys.toList()..sort();
    return YFormSection(
      sectionKey: _startKey,
      title: 'Start flow',
      children: [
        YResourcePicker(
          label: 'Default start flow',
          wireKey: 'defaultStartFlowId',
          form: _form,
          name: 'defaultStartFlowId',
          optional: true,
          loading: flows.isLoading && flows.valueOrNull == null,
          value: _draft.defaultStartFlowId,
          noneLabel: 'None',
          resources: [
            for (final id in ids)
              YResource(
                id: id,
                name: byId[id]!.name.isEmpty ? null : byId[id]!.name,
                meta: switch (byId[id]!.status) {
                  'ACTIVE' => 'Active',
                  'DRAFT' => 'Draft',
                  'ARCHIVED' => 'Archived',
                  final s => s,
                },
                disabledReason: byId[id]!.status == 'ARCHIVED' && _draft.defaultStartFlowId != id ? 'Archived' : null,
              ),
          ],
          helper: 'Runs deterministically when a conversation starts on this profile, unless the channel sets its own.',
          onChanged: (v) => _set((d) => d.defaultStartFlowId = v),
        ),
      ],
    );
  }

  Widget _storeSection() {
    final d = _draft;
    final tools = ref.watch(toolsProvider);
    final latestTools = <String, ({int version, bool clientInvocable})>{};
    for (final t in tools.valueOrNull ?? const []) {
      final cur = latestTools[t.id];
      if (cur == null || t.version > cur.version) {
        latestTools[t.id] = (version: t.version, clientInvocable: t.policy['clientInvocable'] == true);
      }
    }
    final toolIds = latestTools.keys.toList()..sort();
    return YFormSection(
      sectionKey: _storeKey,
      title: 'Store',
      description: 'Catalogs, pricing and payment providers are managed in the yaya-sales console, not here.',
      children: [
        YSwitchField(
          title: 'Sell from a yaya-sales store',
          wireKey: 'extensions.sales.store',
          description: 'Shows the storefront (products, cart, checkout) to users of this profile.',
          value: d.storeEnabled,
          onChanged: (v) => _set((d) => d.storeEnabled = v, 'storeEnabled'),
        ),
        if (d.storeEnabled) ...[
          YTextField(
            label: 'Store ID',
            wireKey: 'storeId',
            controller: _storeId,
            form: _form,
            name: 'storeId',
            optional: true,
            mono: true,
            hint: 'e.g. 3f2a9c…',
            helper: 'The yaya-sales store this profile sells from.',
            onChanged: (v) => _draft.storeId = v,
          ),
          YResourcePicker(
            label: 'Checkout tool',
            wireKey: 'checkoutTool',
            form: _form,
            name: 'checkoutTool',
            loading: tools.isLoading && tools.valueOrNull == null,
            value: d.checkoutTool,
            resources: [
              for (final id in toolIds)
                YResource(
                  id: id,
                  meta: 'v${latestTools[id]!.version}${latestTools[id]!.clientInvocable ? ' · runnable from the chat UI' : ''}',
                ),
            ],
            helper: 'The tool the checkout button runs. It needs “Runnable from the chat UI” on.',
            onChanged: (v) => _set((d) => d.checkoutTool = (v == null || v.isEmpty) ? 'checkout' : v),
          ),
          YSwitchField(
            title: 'Cart',
            wireKey: 'cartEnabled',
            description: 'Shows the cart to shoppers. Off = buy one item at a time.',
            value: d.cartEnabled,
            onChanged: (v) => _set((d) => d.cartEnabled = v, 'cartEnabled'),
          ),
        ],
      ],
    );
  }

  Widget _advancedSection() {
    final d = _draft;
    final rounds = int.tryParse(d.maxToolRounds.trim());
    final summary = [
      d.model.trim().isEmpty ? 'Deployment’s model' : d.model.trim(),
      '${rounds ?? defaultToolRounds} tool rounds',
      if (d.metered) 'Metered' else 'Not metered',
    ].join(' · ');
    return YFormSection(
      // Remounts open the first time one of its fields has an error to show.
      key: ValueKey('advanced:$_advancedForcedOpen'),
      sectionKey: _advancedKey,
      title: 'Advanced',
      description: summary,
      collapsible: true,
      initiallyExpanded: _advancedForcedOpen,
      children: [
        YTextField(
          label: 'Model',
          wireKey: 'model',
          controller: _model,
          form: _form,
          name: 'model',
          optional: true,
          mono: true,
          hint: 'e.g. claude-sonnet-4-5',
          helper: 'Leave empty to use the deployment’s model. Must be on the deployment’s allowed-models list.',
          onChanged: (v) => _set((d) => d.model = v, 'model'),
        ),
        YTextField(
          label: 'Max tool rounds per turn',
          wireKey: 'maxToolRounds',
          controller: _rounds,
          form: _form,
          name: 'maxToolRounds',
          optional: true,
          mono: true,
          keyboardType: TextInputType.number,
          hint: 'e.g. $defaultToolRounds',
          helper: 'How many times the model may call tools before it must answer: $minToolRounds–$maxToolRounds. Empty = $defaultToolRounds.',
          onChanged: (v) => _set((d) => d.maxToolRounds = v, 'maxToolRounds'),
        ),
        YSwitchField(
          title: 'Metered',
          wireKey: 'metered',
          description: 'Turns count against the tenant’s monthly message allowance and are refused once it is used up.',
          value: d.metered,
          onChanged: (v) => _set((d) => d.metered = v, 'metered'),
        ),
      ],
    );
  }

  Widget _aside(BuildContext context, ProfileSummary? summary, int nextVersion) {
    final scheme = Theme.of(context).colorScheme;
    final changed = _form.changedFields;
    final current = _form.baseVersion ?? _base!.version;
    final id = _base!.id;

    final routes = (ref.watch(identityProfileMappingsProvider(widget.tenant)).valueOrNull ?? const [])
        .where((m) => m.profileId == id)
        .toList();
    final senders = (ref.watch(channelBindingsProvider(widget.tenant)).valueOrNull ?? const [])
        .where((b) => b.profileId == id && b.deactivatedAt == null)
        .toList();
    final entryPoints = (ref.watch(chatEntryPointsProvider(widget.tenant)).valueOrNull ?? const [])
        .where((e) => e.profileId == id)
        .toList();
    final pinned = senders.where((b) => b.profileVersion != null).toList();

    Widget link(String text, String route, {String? meta}) => InkWell(
          onTap: () => context.go(route),
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Row(children: [
              Flexible(child: Text(text, overflow: TextOverflow.ellipsis, style: monoStyle(context, size: 13, color: scheme.primary))),
              if (meta != null) ...[
                const SizedBox(width: 8),
                Text(meta, style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant)),
              ],
            ]),
          ),
        );
    Widget group(String title) => Padding(
          padding: const EdgeInsets.only(top: 10, bottom: 2),
          child: Text(title.toUpperCase(),
              style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, letterSpacing: 0.4, color: scheme.onSurfaceVariant)),
        );

    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Card(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('Publishing creates v$nextVersion', style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
            const SizedBox(height: 8),
            Text('New conversations use v$nextVersion. Conversations already running stay on v$current until they end.',
                style: TextStyle(fontSize: 14, height: 1.5, color: scheme.onSurface)),
            if (pinned.isNotEmpty) ...[
              const SizedBox(height: 8),
              Text(
                '${pinned.length == 1 ? '1 sender route is' : '${pinned.length} sender routes are'} pinned to a specific version and won’t pick v$nextVersion up.',
                style: TextStyle(fontSize: 14, height: 1.5, color: StatusColors.of(context).onWarningContainer),
              ),
            ],
            const Divider(height: 24),
            Text('YOUR CHANGES', style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, letterSpacing: 0.4, color: scheme.onSurfaceVariant)),
            const SizedBox(height: 6),
            if (changed.isEmpty)
              Text('Nothing yet.', style: TextStyle(fontSize: 14, color: scheme.onSurfaceVariant))
            else
              for (final f in changed)
                Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: Text('~ ${profileFieldLabels[f] ?? f}', style: const TextStyle(fontSize: 14)),
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
            const SizedBox(height: 4),
            if (routes.isEmpty && senders.isEmpty && entryPoints.isEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Text('No identity route, sender or chat entry point points here yet. Users can still reach it by ID.',
                    style: TextStyle(fontSize: 14, height: 1.5, color: scheme.onSurfaceVariant)),
              ),
            if (routes.isNotEmpty) ...[
              group('Identity routes'),
              for (final r in routes) link('${r.claimName} = ${r.claimValue}', '/admin/identity-routing'),
            ],
            if (senders.isNotEmpty) ...[
              group('Sender routes'),
              for (final b in senders)
                link(
                  '${b.endpoint.kind} ${b.endpoint.id}',
                  b.endpoint.kind.toUpperCase().contains('SMS') ? '/admin/sms' : '/admin/whatsapp-senders',
                  meta: b.profileVersion != null ? 'pinned v${b.profileVersion}' : null,
                ),
            ],
            if (entryPoints.isNotEmpty) ...[
              group('Chat entry points'),
              for (final e in entryPoints) link(e.handle, '/admin/chats', meta: e.isActive ? null : e.status.toLowerCase()),
            ],
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
