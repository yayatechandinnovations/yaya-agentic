import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../api/admin_api.dart';
import '../../../app/live/live_resource.dart';
import '../../../app/live/resource_kind.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/auth_binding.dart';
import '../../../ui/data/live_async.dart';
import '../../../ui/data/relative_time.dart';
import '../../../ui/data/y_badge.dart';
import '../../../ui/data/y_page.dart';
import '../../../ui/data/y_sheet.dart';
import '../../../ui/data/y_states.dart';
import '../../../ui/data/y_table.dart';
import '../../../ui/feedback/api_error.dart';
import '../../../ui/feedback/toast.dart';
import '../../../ui/form/unsaved_guard.dart';
import '../../../ui/form/y_fields.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/tokens.dart';
import '../profiles/profiles_screen.dart' show authBindingsProvider, profilesProvider;
import '../shared/admin_shared.dart';

final authAvailabilityProvider = FutureProvider<AuthAvailability>((ref) async {
  final api = await ref.watch(adminApiProvider.future);
  return api.availableAuth();
});

/// What each registered authenticator does, in the operator's words. Names
/// the backend registers but this map doesn't know still show, with a
/// generic line, so a new authenticator is pickable before the copy lands.
const _authenticators = <String, ({String title, String description, bool caution})>{
  'oidc': (
    title: 'OIDC bearer token',
    description: 'Verifies RS256/ES256 JWTs against this tenant’s trusted OIDC issuers (their JWKS).',
    caution: false,
  ),
  'shared-secret-jwt': (
    title: 'Shared-secret JWT',
    description: 'Verifies HS256/384/512 JWTs signed with one of this tenant’s JWT secrets.',
    caution: false,
  ),
  'service-token': (
    title: 'Service token',
    description: 'Machine-to-machine calls carrying an X-Yaya-Service-Token HMAC token.',
    caution: false,
  ),
  'delegated-host': (
    title: 'Delegated host',
    description: 'Trusts the identity your host app signs with Ed25519 and sends in X-Yaya-Identity headers.',
    caution: false,
  ),
  'pre-verified': (
    title: 'Pre-verified principal',
    description: 'Accepts a user an upstream step already verified — e.g. a channel’s auth resolver.',
    caution: false,
  ),
  'channel-whatsapp-phone': (
    title: 'WhatsApp phone',
    description: 'The sender’s WhatsApp number, as stamped by the WhatsApp webhook.',
    caution: false,
  ),
  'channel-sms-phone': (
    title: 'SMS phone',
    description: 'The sender’s phone number from an inbound SMS.',
    caution: false,
  ),
  'noop': (
    title: 'Anonymous (development only)',
    description: 'Lets anyone in as a fresh anonymous user. Never use it for a real tenant.',
    caution: true,
  ),
};

const _authorizers = <String, ({String title, String description})>{
  'ScopeAuthorizer': (
    title: 'Scopes',
    description: 'Denies a tool call when the user’s token lacks a scope the tool requires.',
  ),
  'OwnershipAuthorizer': (
    title: 'Ownership',
    description: 'Denies when a resource id in the tool’s arguments doesn’t belong to the signed-in user.',
  ),
  'StepUpScopeAuthorizer': (
    title: 'Step-up verification',
    description: 'Asks the user to verify again (e.g. a one-time code) before guarded actions.',
  ),
  'OpaAuthorizer': (
    title: 'OPA policy (stub)',
    description: 'Placeholder for an Open Policy Agent check — currently always allows.',
  ),
};

String authenticatorTitle(String ref) => _authenticators[ref]?.title ?? ref;
String _authorizerTitle(String name) => _authorizers[name]?.title ?? name;

/// Binding id → ids of the profiles (latest versions) that use it.
final authBindingUsageProvider = Provider<Map<String, List<String>>>((ref) {
  final profiles = ref.watch(profilesProvider).valueOrNull ?? const [];
  final latest = <String, ({int version, String? binding})>{};
  for (final p in profiles) {
    final cur = latest[p.id];
    if (cur == null || p.version > cur.version) latest[p.id] = (version: p.version, binding: p.authBindingId);
  }
  final out = <String, List<String>>{};
  latest.forEach((profileId, v) {
    if (v.binding != null) out.putIfAbsent(v.binding!, () => []).add(profileId);
  });
  return out;
});

const _labels = {
  'id': 'Binding ID',
  'authenticatorRef': 'Authenticator',
  'authorizerChain': 'Authorizer chain',
};

class AuthBindingsScreen extends ConsumerStatefulWidget {
  const AuthBindingsScreen({super.key});
  @override
  ConsumerState<AuthBindingsScreen> createState() => _AuthBindingsScreenState();
}

class _AuthBindingsScreenState extends ConsumerState<AuthBindingsScreen> {
  String? _handledLink;

  Future<void> _open(AuthBindingResponse? existing) async {
    await showYSheet<void>(
      context,
      label: existing == null ? 'New auth binding' : 'Edit auth binding',
      builder: (_) => AuthBindingEditorSheet(existing: existing),
    );
  }

  /// `?id=` / `?new=1` (⌘K, cross-links) open the editor once the list is in.
  void _handleDeepLink(List<AuthBindingResponse> list) {
    final params = GoRouterState.of(context).uri.queryParameters;
    final link = params['id'] ?? (params['new'] != null ? '' : null);
    if (link == null || link == _handledLink) return;
    _handledLink = link;
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      final match = link.isEmpty ? null : list.where((b) => b.id == link).firstOrNull;
      if (link.isNotEmpty && match == null) {
        showToast(context, 'No auth binding “$link” on this tenant', error: true);
      } else {
        await _open(match);
      }
      if (mounted) context.go('/admin/auth-bindings');
      _handledLink = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    if (ref.watch(currentTenantOrNull) == null) {
      return const TenantScopedEmptyState(resourceLabel: 'Auth bindings');
    }
    final bindings = ref.watch(authBindingsProvider);
    final usage = ref.watch(authBindingUsageProvider);
    final fresh = ref.watch(freshIdsProvider(AdminResourceKind.authBinding));
    bindings.whenData(_handleDeepLink);

    return YPage(
      title: 'Auth bindings',
      subtitle: 'How a profile’s users prove who they are, and which policy checks run before every tool call.',
      actions: [
        FilledButton.icon(onPressed: () => _open(null), icon: const Icon(Icons.add, size: 18), label: const Text('New binding')),
      ],
      child: LiveAsyncView<List<AuthBindingResponse>>(
        value: bindings,
        what: 'auth bindings',
        onRetry: () => ref.invalidate(authBindingsProvider),
        data: (context, list) => YTable<AuthBindingResponse>(
          rows: [...list]..sort((a, b) => a.id.compareTo(b.id)),
          noun: 'bindings',
          idOf: (b) => b.id,
          searchHint: 'Search by ID, authenticator or authorizer',
          searchTextOf: (b) => '${b.id} ${b.authenticatorRef} ${authenticatorTitle(b.authenticatorRef)} ${b.authorizerChain.join(' ')}',
          highlightIds: fresh,
          onRowTap: _open,
          filters: [
            YFilter('No authorizers', (b) => b.authorizerChain.isEmpty),
            YFilter('Unused', (b) => (usage[b.id] ?? const []).isEmpty),
          ],
          empty: YEmptyState(
            icon: Icons.lock_outlined,
            title: 'No auth bindings yet',
            message: 'A binding pairs an authenticator (how users sign in) with an ordered chain of authorizers (what they may do). Profiles point at one.',
            action: FilledButton.icon(onPressed: () => _open(null), icon: const Icon(Icons.add, size: 18), label: const Text('Create your first binding')),
          ),
          columns: [
            YColumn(
              label: 'Binding',
              flex: 2,
              sortKey: (b) => b.id,
              cell: (context, b) => Text(b.id, overflow: TextOverflow.ellipsis, style: monoStyle(context, weight: FontWeight.w500)),
            ),
            YColumn(
              label: 'Authenticator',
              flex: 3,
              sortKey: (b) => b.authenticatorRef,
              cell: (context, b) => YCellStack(primary: authenticatorTitle(b.authenticatorRef), secondary: b.authenticatorRef),
            ),
            YColumn(
              label: 'Authorizers',
              flex: 3,
              sortKey: (b) => b.authorizerChain.length,
              cell: (context, b) => b.authorizerChain.isEmpty
                  ? const YBadge('None', tone: YTone.warning, icon: Icons.warning_amber_rounded)
                  : Text(b.authorizerChain.map(_authorizerTitle).join(' → '), overflow: TextOverflow.ellipsis),
            ),
            YColumn(
              label: 'Used by',
              flex: 2,
              sortKey: (b) => (usage[b.id] ?? const []).length,
              cell: (context, b) {
                final p = usage[b.id] ?? const [];
                return Text(
                  p.isEmpty ? 'No profile yet' : p.length == 1 ? p.first : '${p.length} profiles',
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(color: p.isEmpty ? Theme.of(context).colorScheme.onSurfaceVariant : null),
                );
              },
            ),
            YColumn(
              label: 'Created',
              width: 120,
              sortKey: (b) => b.createdAt ?? '',
              cell: (context, b) => Text(fresh.contains(b.id) ? 'Just now' : formatRelativeIso(b.createdAt)),
            ),
          ],
        ),
      ),
    );
  }
}

/// Create or overwrite one auth binding (upserted by id), in a side sheet.
class AuthBindingEditorSheet extends ConsumerStatefulWidget {
  const AuthBindingEditorSheet({super.key, this.existing});
  final AuthBindingResponse? existing;

  @override
  ConsumerState<AuthBindingEditorSheet> createState() => _AuthBindingEditorSheetState();
}

class _AuthBindingEditorSheetState extends ConsumerState<AuthBindingEditorSheet> {
  final _id = TextEditingController();
  String? _authenticator;
  List<String> _chain = [];
  late final YFormController _form;

  AuthBindingResponse? get _base => widget.existing;
  bool get _editing => _base != null;

  @override
  void initState() {
    super.initState();
    _id.text = _base?.id ?? '';
    _authenticator = _base?.authenticatorRef;
    _chain = List.of(_base?.authorizerChain ?? const []);
    final tenant = ref.read(currentTenantOrNull);
    _form = YFormController(
      snapshot: () => {
        'id': _id.text.trim(),
        'authenticatorRef': _authenticator,
        'authorizerChain': _chain,
      },
      validate: _validate,
      labels: _labels,
      draftKey: 'yaya.draft.authBinding.$tenant.${_base?.id ?? 'new'}',
    );
    _form.addListener(_rebuild);
    _form.loadDraft().then((d) {
      if (d == null || !mounted) return;
      final v = d.values;
      if (!_editing) _id.text = (v['id'] as String?) ?? _id.text;
      _authenticator = (v['authenticatorRef'] as String?) ?? _authenticator;
      final chain = v['authorizerChain'];
      if (chain is List) _chain = chain.map((e) => '$e').toList();
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
    _id.dispose();
    super.dispose();
  }

  Map<String, String?> _validate() {
    final avail = ref.read(authAvailabilityProvider).valueOrNull;
    String? idErr;
    if (!_editing) {
      final id = _id.text.trim();
      idErr = Validators.required(id, 'a binding ID');
      if (idErr == null && RegExp(r'\s').hasMatch(id)) idErr = 'Use no spaces — profiles refer to the binding by this ID.';
      if (idErr == null && id.length > 128) idErr = 'Keep it to 128 characters or fewer.';
      final existing = ref.read(authBindingsProvider).valueOrNull ?? const [];
      if (idErr == null && existing.any((b) => b.id == id)) {
        idErr = 'A binding with this ID already exists. Open it to change it — saving here would overwrite it.';
      }
    }
    String? authErr;
    if (_authenticator == null) {
      authErr = 'Pick how users of this binding sign in.';
    } else if (avail != null && !avail.authenticators.contains(_authenticator)) {
      authErr = '“$_authenticator” isn’t registered on this deployment. Pick another.';
    }
    final unknown = avail == null ? const <String>[] : _chain.where((a) => !avail.authorizers.contains(a)).toList();
    return {
      'id': idErr,
      'authenticatorRef': authErr,
      'authorizerChain': unknown.isEmpty
          ? null
          : '${unknown.join(', ')} ${unknown.length == 1 ? 'isn’t' : 'aren’t'} registered on this deployment. Remove ${unknown.length == 1 ? 'it' : 'them'}.',
    };
  }

  Future<void> _save() async {
    if (!_form.submit()) return;
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    _form.saving = true;
    try {
      final api = await ref.read(adminApiProvider.future);
      final saved = await api.createAuthBinding(AuthBindingRequest(
        tenant: tenant,
        id: _id.text.trim(),
        authenticatorRef: _authenticator!,
        authorizerChain: _chain,
      ));
      ref.invalidate(authBindingsProvider);
      _form.saving = false;
      _form.markClean();
      if (!mounted) return;
      showToast(context, _editing ? 'Saved ${saved.id}' : 'Created ${saved.id}');
      Navigator.of(context).pop();
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(e);
    }
  }

  void _setChain(List<String> next) {
    _form.touch('authorizerChain');
    _form.changed('authorizerChain');
    setState(() => _chain = next);
  }

  @override
  Widget build(BuildContext context) {
    final avail = ref.watch(authAvailabilityProvider);
    final usage = _base == null ? const <String>[] : (ref.watch(authBindingUsageProvider)[_base!.id] ?? const []);

    return YUnsavedGuard(
      form: _form,
      child: YSaveShortcut(
        onSave: _save,
        child: YSheetScaffold(
          title: _editing ? _base!.id : 'New auth binding',
          subtitle: _editing
              ? 'Saving replaces this binding in place${usage.isEmpty ? '' : ' for ${usage.length == 1 ? '1 profile' : '${usage.length} profiles'}'}. Fields marked * are required.'
              : 'Fields marked * are required.',
          body: ListView(
            padding: const EdgeInsets.fromLTRB(28, 20, 28, 28),
            children: [
              if (_form.summary.isNotEmpty || _form.formError != null) ...[
                YErrorSummary(form: _form, action: _editing ? 'saving' : 'creating this binding'),
                const SizedBox(height: Gap.lg),
              ],
              YTextField(
                label: 'Binding ID',
                wireKey: 'id',
                controller: _id,
                form: _form,
                name: 'id',
                required: !_editing,
                readOnly: _editing,
                autofocus: !_editing,
                mono: true,
                hint: 'e.g. retail-oidc',
                helper: _editing ? 'Fixed — profiles refer to the binding by this ID.' : 'Profiles refer to the binding by this ID; it can’t change later.',
              ),
              const SizedBox(height: Gap.fieldGap),
              ...avail.when(
                skipLoadingOnReload: true,
                loading: () => [const LinearProgressIndicator(minHeight: 2)],
                error: (e, _) => [
                  YBanner(
                    tone: YBannerTone.error,
                    title: 'Couldn’t load the registered authenticators.',
                    message: ApiErrorInfo.from(e).message,
                    actions: [TextButton(onPressed: () => ref.invalidate(authAvailabilityProvider), child: const Text('Retry'))],
                  ),
                ],
                data: (a) => [
                  _authenticatorField(a),
                  const SizedBox(height: Gap.fieldGap),
                  _AuthorizerChainField(
                    form: _form,
                    available: a.authorizers,
                    chain: _chain,
                    onChanged: _setChain,
                  ),
                ],
              ),
              if (_editing) ...[
                const SizedBox(height: Gap.xl),
                _UsedBy(profiles: usage, form: _form),
              ],
            ],
          ),
          footer: YActionBar(
            form: _form,
            primaryLabel: _editing ? 'Save binding' : 'Create binding',
            onPrimary: _save,
            onCancel: () => closeYSheet(context),
          ),
        ),
      ),
    );
  }

  Widget _authenticatorField(AuthAvailability a) {
    final names = [...a.authenticators];
    // A binding saved against an authenticator this deployment no longer
    // registers still shows what it points at, flagged, rather than blank.
    if (_authenticator != null && !names.contains(_authenticator)) names.add(_authenticator!);
    if (names.isEmpty) {
      return const YBanner(
        tone: YBannerTone.warning,
        message: 'This deployment registers no authenticators, so a binding can’t be created yet.',
      );
    }
    return YEnumCards<String?>(
      label: 'Authenticator',
      wireKey: 'authenticatorRef',
      form: _form,
      name: 'authenticatorRef',
      required: true,
      helper: 'How users of profiles on this binding prove who they are.',
      value: _authenticator,
      onChanged: (v) {
        _form.touch('authenticatorRef');
        setState(() => _authenticator = v);
      },
      options: [
        for (final n in names)
          YEnumOption<String?>(
            n,
            _authenticators[n]?.title ?? n,
            wire: n,
            caution: (_authenticators[n]?.caution ?? false) || !a.authenticators.contains(n),
            description: !a.authenticators.contains(n)
                ? 'Not registered on this deployment — saving will be refused.'
                : _authenticators[n]?.description ?? 'Registered on this deployment.',
          ),
      ],
    );
  }
}

/// Ordered authorizer picker: the chain runs top to bottom and stops at the
/// first denial, so order is part of the value — chips can't show that.
class _AuthorizerChainField extends StatelessWidget {
  const _AuthorizerChainField({required this.form, required this.available, required this.chain, required this.onChanged});
  final YFormController form;
  final List<String> available;
  final List<String> chain;
  final ValueChanged<List<String>> onChanged;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final status = StatusColors.of(context);
    final remaining = available.where((a) => !chain.contains(a)).toList();
    return YFieldShell(
      label: 'Authorizer chain',
      wireKey: 'authorizerChain',
      form: form,
      name: 'authorizerChain',
      optional: true,
      helper: 'Checked in this order before every tool call; the first denial stops the call.',
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        if (chain.isEmpty)
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(color: status.warningContainer, borderRadius: BorderRadius.circular(8)),
            child: Text(
              'No authorizers — tool calls go through without a policy check beyond what the profile lists.',
              style: TextStyle(fontSize: 13, height: 1.45, color: status.onWarningContainer),
            ),
          )
        else
          DecoratedBox(
            decoration: BoxDecoration(border: Border.all(color: scheme.outlineVariant), borderRadius: BorderRadius.circular(8)),
            child: Column(children: [
              for (var i = 0; i < chain.length; i++) ...[
                if (i > 0) Divider(height: 1, color: scheme.outlineVariant),
                Padding(
                  padding: const EdgeInsets.fromLTRB(12, 8, 4, 8),
                  child: Row(children: [
                    SizedBox(
                      width: 24,
                      child: Text('${i + 1}', style: monoStyle(context, weight: FontWeight.w600, color: scheme.onSurfaceVariant)),
                    ),
                    Expanded(
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Wrap(spacing: 8, crossAxisAlignment: WrapCrossAlignment.center, children: [
                          Text(_authorizerTitle(chain[i]),
                              style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: scheme.onSurface)),
                          Text(chain[i], style: monoStyle(context, size: 12, color: scheme.onSurfaceVariant)),
                        ]),
                        if (_authorizers[chain[i]] != null)
                          Text(_authorizers[chain[i]]!.description,
                              style: TextStyle(fontSize: 13, height: 1.4, color: scheme.onSurfaceVariant)),
                      ]),
                    ),
                    IconButton(
                      tooltip: 'Move up',
                      icon: const Icon(Icons.arrow_upward, size: 18),
                      onPressed: i == 0 ? null : () => onChanged([...chain]..insert(i - 1, chain[i])..removeAt(i + 1)),
                    ),
                    IconButton(
                      tooltip: 'Move down',
                      icon: const Icon(Icons.arrow_downward, size: 18),
                      onPressed: i == chain.length - 1 ? null : () => onChanged([...chain]..insert(i + 2, chain[i])..removeAt(i)),
                    ),
                    IconButton(
                      tooltip: 'Remove ${_authorizerTitle(chain[i])}',
                      icon: const Icon(Icons.close, size: 18),
                      onPressed: () => onChanged([...chain]..removeAt(i)),
                    ),
                  ]),
                ),
              ],
            ]),
          ),
        if (remaining.isNotEmpty) ...[
          const SizedBox(height: 10),
          Wrap(spacing: 8, runSpacing: 8, children: [
            for (final a in remaining)
              Tooltip(
                message: _authorizers[a]?.description ?? a,
                child: ActionChip(
                  avatar: Icon(Icons.add, size: 16, color: scheme.primary),
                  label: Text(_authorizerTitle(a)),
                  onPressed: () => onChanged([...chain, a]),
                ),
              ),
          ]),
        ],
      ]),
    );
  }
}

class _UsedBy extends StatelessWidget {
  const _UsedBy({required this.profiles, required this.form});
  final List<String> profiles;
  final YFormController form;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text('Used by', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: scheme.onSurface)),
      const SizedBox(height: 6),
      if (profiles.isEmpty)
        Text('No profile uses this binding yet.', style: TextStyle(fontSize: 14, color: scheme.onSurfaceVariant))
      else
        Wrap(spacing: 8, runSpacing: 8, children: [
          for (final p in profiles)
            ActionChip(
              label: Text(p, style: monoStyle(context, size: 12.5)),
              onPressed: () async {
                final router = GoRouter.of(context);
                if (await UnsavedChanges.instance.confirmLeave(context)) {
                  form.markClean();
                  if (context.mounted) Navigator.of(context).pop();
                  router.go('/admin/profiles/${Uri.encodeComponent(p)}');
                }
              },
            ),
        ]),
    ]);
  }
}
