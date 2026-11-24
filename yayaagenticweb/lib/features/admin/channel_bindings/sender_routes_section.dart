import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/live/live_resource.dart';
import '../../../app/live/resource_kind.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/auth_resolver.dart';
import '../../../models/admin/channel_binding.dart';
import '../../../models/admin/profile.dart';
import '../../../ui/data/y_badge.dart';
import '../../../ui/data/y_sheet.dart';
import '../../../ui/feedback/api_error.dart';
import '../../../ui/feedback/confirm.dart';
import '../../../ui/feedback/toast.dart';
import '../../../ui/form/unsaved_guard.dart';
import '../../../ui/form/y_fields.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/form/y_resource_picker.dart';
import '../../../ui/tokens.dart';
import '../auth_resolvers/auth_resolvers_screen.dart' show authResolversProvider;
import '../profiles/profiles_screen.dart' show profilesProvider;
import 'channel_ui.dart';

final channelBindingsProvider = FutureProvider.family<List<ChannelBindingResponse>, String>((ref, tenantId) async {
  liveResource(ref, AdminResourceKind.channelBinding);
  final api = await ref.watch(adminApiProvider.future);
  return api.listChannelBindings(tenantId);
});

String _profileLabel(List<ProfileResponse> profiles, String id, int? version) {
  final p = profiles.where((p) => p.id == id).firstOrNull;
  final name = p == null || p.displayName.isEmpty ? id : p.displayName;
  return version == null ? name : '$name v$version';
}

/// Puts `platform_sender_requires_resolver` next to the resolver picker.
ApiErrorInfo routeFieldError(Object e) {
  final info = ApiErrorInfo.from(e);
  if (info.fieldErrors.isNotEmpty || info.code != 'platform_sender_requires_resolver') return info;
  return ApiErrorInfo(
    message: info.message,
    code: info.code,
    status: info.status,
    path: info.path,
    fieldErrors: const {
      'authResolverId': 'A shared platform number needs an identity resolver that drops what it can’t resolve.',
    },
  );
}

/// The routing config for one channel endpoint (a WhatsApp/SMS sender),
/// shown inside that sender's detail sheet: *which profile an inbound talks
/// to*, managed right where the number is.
///
/// One endpoint can carry several routes (evaluated by priority, lowest
/// first); the common case is one "match every message" route → a profile.
class SenderRoutesSection extends ConsumerWidget {
  const SenderRoutesSection({
    super.key,
    required this.endpointKind, // 'WHATSAPP_SENDER' | 'SMS_SENDER'
    required this.endpointId,
    this.supportsResolver = true,
    this.platformSender = false,
  });

  final String endpointKind;
  final String endpointId;

  /// WhatsApp runs the auth resolver; SMS is FAQ-mode only, so the identity
  /// and override controls are hidden there.
  final bool supportsResolver;

  /// The endpoint is a shared platform sender: every route needs a resolver
  /// and drops what it can't resolve (shared-sender §7.5).
  final bool platformSender;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) return const SizedBox.shrink();
    final scheme = Theme.of(context).colorScheme;
    final bindings = ref.watch(channelBindingsProvider(tenant));
    final profiles = ref.watch(profilesProvider).valueOrNull ?? const <ProfileResponse>[];

    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      ChannelSheetHeading(
        'Routes',
        trailing: TextButton.icon(
          onPressed: () => _openEditor(context, tenant, null),
          icon: const Icon(Icons.add, size: 18),
          label: const Text('Add route'),
        ),
      ),
      Text(
        'Which assistant answers a message on this number. Routes are tried by priority, lowest first; the first '
        'match wins.',
        style: TextStyle(fontSize: 13, height: 1.45, color: scheme.onSurfaceVariant),
      ),
      const SizedBox(height: Gap.sm),
      if (bindings.hasError && !bindings.hasValue)
        YBanner(
          tone: YBannerTone.error,
          title: 'Couldn’t load routes.',
          message: ApiErrorInfo.from(bindings.error!).message,
          actions: [TextButton(onPressed: () => ref.invalidate(channelBindingsProvider(tenant)), child: const Text('Retry'))],
        )
      else if (!bindings.hasValue)
        const LinearProgressIndicator()
      else
        Builder(builder: (context) {
          final routes = bindings.value!
              .where((b) => b.endpoint.kind == endpointKind && b.endpoint.id == endpointId)
              .toList()
            ..sort((a, b) => a.priority.compareTo(b.priority));
          if (routes.isEmpty) {
            return const YBanner(
              tone: YBannerTone.warning,
              title: 'No routes yet.',
              message: 'Messages on this number aren’t handed to any assistant. Add a route to start replying.',
            );
          }
          return Column(children: [
            for (final r in routes)
              _RouteRow(
                row: r,
                profileLabel: _profileLabel(profiles, r.profileId, r.profileVersion),
                onEdit: () => _openEditor(context, tenant, r),
                onDelete: () => _delete(context, ref, tenant, r, profiles),
              ),
          ]);
        }),
    ]);
  }

  Future<void> _openEditor(BuildContext context, String tenant, ChannelBindingResponse? existing) {
    return showYSheet<void>(
      context,
      width: 600,
      label: existing == null ? 'Add route' : 'Edit route',
      builder: (_) => RouteEditorSheet(
        tenant: tenant,
        endpointKind: endpointKind,
        endpointId: endpointId,
        supportsResolver: supportsResolver,
        platformSender: platformSender,
        existing: existing,
      ),
    );
  }

  Future<void> _delete(BuildContext context, WidgetRef ref, String tenant, ChannelBindingResponse row,
      List<ProfileResponse> profiles) async {
    final profile = _profileLabel(profiles, row.profileId, row.profileVersion);
    final ok = await confirmAction(
      context,
      title: 'Remove the route to $profile?',
      message: 'Messages matching “${row.routing.humanLabel()}” stop reaching $profile right away'
          '${row.routing.kind == 'MatchAny' ? ' — if no other route matches, nobody answers them' : ''}. '
          'The audit trail is kept.',
      confirmLabel: 'Remove route',
      destructive: true,
    );
    if (!ok) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.deleteChannelBinding(tenant, row.id);
      ref.invalidate(channelBindingsProvider(tenant));
      if (context.mounted) showToast(context, 'Removed the route to $profile');
    } catch (e) {
      if (context.mounted) showToast(context, ApiErrorInfo.from(e).message, error: true);
    }
  }
}

class _RouteRow extends StatelessWidget {
  const _RouteRow({required this.row, required this.profileLabel, required this.onEdit, required this.onDelete});
  final ChannelBindingResponse row;
  final String profileLabel;
  final VoidCallback onEdit;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final active = row.status == 'ACTIVE';
    return Container(
      margin: const EdgeInsets.only(bottom: 6),
      padding: const EdgeInsets.fromLTRB(12, 8, 4, 8),
      decoration: BoxDecoration(
        border: Border.all(color: scheme.outlineVariant),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(children: [
        Tooltip(message: 'Priority — lower is tried first', child: YBadge('${row.priority}', mono: true)),
        const SizedBox(width: 10),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('${row.routing.humanLabel()} → $profileLabel',
                style: TextStyle(fontSize: 14, fontWeight: FontWeight.w500, color: scheme.onSurface)),
            const SizedBox(height: 4),
            Wrap(spacing: 6, runSpacing: 4, children: [
              YBadge(active ? 'Active' : 'Paused', tone: active ? YTone.success : YTone.info,
                  icon: active ? Icons.check_circle_outline : Icons.pause_circle_outline),
              YBadge(row.authResolverId == null ? 'FAQ mode' : 'Resolver #${row.authResolverId}',
                  icon: row.authResolverId == null ? Icons.help_outline : Icons.badge_outlined),
            ]),
          ]),
        ),
        IconButton(tooltip: 'Edit route', icon: const Icon(Icons.edit_outlined, size: 18), onPressed: onEdit),
        IconButton(
          tooltip: 'Remove route',
          icon: Icon(Icons.delete_outline, size: 18, color: scheme.error),
          onPressed: onDelete,
        ),
      ]),
    );
  }
}

const _routingOptions = [
  YEnumOption('MatchAny', 'Every message', wire: 'MatchAny', description: 'Everything on this number goes to the profile.'),
  YEnumOption('KeywordPrefix', 'Starts with a keyword',
      wire: 'KeywordPrefix', description: 'Only messages whose text begins with one of the keywords.'),
  YEnumOption('Locale', 'Customer’s language', wire: 'Locale', description: 'Only customers whose locale matches.'),
  YEnumOption('EndUserClaim', 'Identity claim',
      wire: 'EndUserClaim', description: 'Only customers whose resolved identity carries a claim with this value.'),
];

const _routeLabels = {
  'profile': 'Profile',
  'keywords': 'Keywords',
  'locales': 'Locales',
  'claim': 'Claim name',
  'equals': 'Claim value',
  'priority': 'Priority',
  'unresolvedReplyText': 'Reply to unknown callers',
  'authResolverId': 'Identity resolver',
};

/// Add or edit one route for a fixed endpoint, in a side sheet. Everything
/// the form doesn't show (session policy, other rule options) rides along
/// unchanged on save.
class RouteEditorSheet extends ConsumerStatefulWidget {
  const RouteEditorSheet({
    super.key,
    required this.tenant,
    required this.endpointKind,
    required this.endpointId,
    required this.supportsResolver,
    this.platformSender = false,
    this.existing,
  });

  final String tenant;
  final String endpointKind;
  final String endpointId;
  final bool supportsResolver;

  /// A shared platform sender: an identity resolver is required and
  /// `onUnresolved` is always `REJECT` — non-members get the workspace's
  /// non-member reply instead (backend `platform_sender_requires_resolver`).
  final bool platformSender;
  final ChannelBindingResponse? existing;

  @override
  ConsumerState<RouteEditorSheet> createState() => _RouteEditorSheetState();
}

class _RouteEditorSheetState extends ConsumerState<RouteEditorSheet> {
  final _priority = TextEditingController(text: '100');
  final _keywords = TextEditingController();
  final _locales = TextEditingController();
  final _claim = TextEditingController();
  final _claimValue = TextEditingController();
  final _replyText = TextEditingController();

  String? _profileSlug; // "<id>@<version>"
  String _routingKind = 'MatchAny';
  String _status = 'ACTIVE';
  int? _authResolverId;
  String _onUnresolved = 'REJECT';
  String _onOverrideDenied = 'IGNORE';
  List<String> _allowedOverrideProfiles = [];
  late final YFormController _form;

  ChannelBindingResponse? get _existing => widget.existing;

  @override
  void initState() {
    super.initState();
    final row = _existing;
    if (row != null) {
      _profileSlug = '${row.profileId}@${row.profileVersion ?? 1}';
      _routingKind = row.routing.kind;
      _status = row.status;
      _priority.text = row.priority.toString();
      _keywords.text = ((row.routing.json['keywords'] as List?) ?? const []).join(', ');
      _locales.text = ((row.routing.json['locales'] as List?) ?? const []).join(', ');
      _claim.text = (row.routing.json['claim'] ?? '').toString();
      _claimValue.text = (row.routing.json['equals'] ?? '').toString();
      _authResolverId = row.authResolverId;
      _onUnresolved = row.onUnresolved;
      _replyText.text = row.unresolvedReplyText ?? '';
      _onOverrideDenied = row.onOverrideDenied;
      _allowedOverrideProfiles = List.of(row.allowedOverrideProfiles);
    }
    if (_platformSender) _onUnresolved = 'REJECT';
    _form = YFormController(
      snapshot: () => {
        'profile': _profileSlug,
        'routing': _routingKind,
        'keywords': _keywords.text.trim(),
        'locales': _locales.text.trim(),
        'claim': _claim.text.trim(),
        'equals': _claimValue.text.trim(),
        'priority': _priority.text.trim(),
        'status': _status,
        'authResolverId': _authResolverId,
        'onUnresolved': _onUnresolved,
        'unresolvedReplyText': _replyText.text.trim(),
        'allowedOverrideProfiles': _allowedOverrideProfiles,
        'onOverrideDenied': _onOverrideDenied,
      },
      validate: _validate,
      labels: _routeLabels,
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
    for (final c in [_priority, _keywords, _locales, _claim, _claimValue, _replyText]) {
      c.dispose();
    }
    super.dispose();
  }

  bool get _platformSender => widget.platformSender && widget.supportsResolver;

  List<String> _csv(TextEditingController c) => c.text.split(',').map((s) => s.trim()).where((s) => s.isNotEmpty).toList();

  Map<String, String?> _validate() => {
        'profile': _profileSlug == null ? 'Choose the profile that answers.' : null,
        'priority': Validators.intInRange(_priority.text, 0, 1000000, optional: false),
        'keywords': _routingKind == 'KeywordPrefix' && _csv(_keywords).isEmpty ? 'Enter at least one keyword.' : null,
        'locales': _routingKind == 'Locale' && _csv(_locales).isEmpty ? 'Enter at least one locale.' : null,
        'claim': _routingKind == 'EndUserClaim' ? Validators.required(_claim.text, 'the claim name') : null,
        'equals': _routingKind == 'EndUserClaim' ? Validators.required(_claimValue.text, 'the value to match') : null,
        'authResolverId': _platformSender && _authResolverId == null
            ? 'Choose an identity resolver — on a shared platform number only identified staff may reach the assistant.'
            : null,
        'unresolvedReplyText': !_autoReply
            ? null
            : _replyText.text.trim().isEmpty
                ? 'Enter what unknown callers are told.'
                : _replyText.text.trim().length > 1000
                    ? 'Keep it to 1000 characters.'
                    : null,
      };

  bool get _autoReply => widget.supportsResolver && _authResolverId != null && _onUnresolved == 'AUTO_REPLY';

  /// The same rule kind keeps any option the form doesn't edit
  /// (e.g. `caseInsensitive`); a new kind starts clean.
  RoutingRuleWire _routingRule() {
    final base = _existing != null && _existing!.routing.kind == _routingKind
        ? Map<String, dynamic>.from(_existing!.routing.json)
        : <String, dynamic>{};
    final fresh = switch (_routingKind) {
      'KeywordPrefix' => RoutingRuleWire.keywordPrefix(_csv(_keywords)),
      'Locale' => RoutingRuleWire.locale(_csv(_locales)),
      'EndUserClaim' => RoutingRuleWire.endUserClaim(_claim.text.trim(), _claimValue.text.trim()),
      _ => RoutingRuleWire.matchAny(),
    };
    final edited = <String, dynamic>{
      'kind': _routingKind,
      ...switch (_routingKind) {
        'KeywordPrefix' => {'keywords': _csv(_keywords)},
        'Locale' => {'locales': _csv(_locales)},
        'EndUserClaim' => {'claim': _claim.text.trim(), 'equals': _claimValue.text.trim()},
        _ => const <String, dynamic>{},
      },
    };
    // Defaults, then what was stored, then what the operator edited.
    return RoutingRuleWire(kind: _routingKind, json: {...fresh.json, ...base, ...edited});
  }

  Future<void> _save() async {
    if (!_form.submit()) return;
    final parts = _profileSlug!.split('@');
    final profileId = parts[0];
    final profileVersion = int.tryParse(parts.length > 1 ? parts[1] : '1') ?? 1;
    final priority = int.parse(_priority.text.trim());
    // SMS never runs a resolver, so never persist resolver/override on it.
    final resolverId = widget.supportsResolver ? _authResolverId : null;
    if (_platformSender) _onUnresolved = 'REJECT';
    final sessionPolicy = _existing?.sessionPolicy ?? SessionPolicyWire.defaults();
    _form.saving = true;
    try {
      final api = await ref.read(adminApiProvider.future);
      if (_existing == null) {
        await api.createChannelBinding(
            widget.tenant,
            ChannelBindingRequest(
              endpoint: ChannelEndpointWire(kind: widget.endpointKind, id: widget.endpointId),
              profileId: profileId,
              profileVersion: profileVersion,
              routing: _routingRule(),
              sessionPolicy: sessionPolicy,
              priority: priority,
              status: _status,
              authResolverId: resolverId,
              onUnresolved: _onUnresolved,
              allowedOverrideProfiles: _allowedOverrideProfiles,
              onOverrideDenied: _onOverrideDenied,
              unresolvedReplyText: _autoReply ? _replyText.text.trim() : null,
            ));
      } else {
        await api.updateChannelBinding(
            widget.tenant,
            _existing!.id,
            ChannelBindingUpdate(
              profileId: profileId,
              profileVersion: profileVersion,
              routing: _routingRule(),
              sessionPolicy: sessionPolicy,
              priority: priority,
              status: _status,
              authResolverId: resolverId,
              onUnresolved: _onUnresolved,
              allowedOverrideProfiles: _allowedOverrideProfiles,
              onOverrideDenied: _onOverrideDenied,
              // Null keeps whatever is stored when the policy isn't AUTO_REPLY.
              unresolvedReplyText: _autoReply ? _replyText.text.trim() : null,
            ));
      }
      ref.invalidate(channelBindingsProvider(widget.tenant));
      _form.saving = false;
      _form.markClean();
      if (!mounted) return;
      showToast(context, _existing == null ? 'Route added' : 'Route saved');
      Navigator.of(context).pop();
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(routeFieldError(e));
    }
  }

  @override
  Widget build(BuildContext context) {
    final profilesAsync = ref.watch(profilesProvider);
    final profiles = (profilesAsync.valueOrNull ?? const <ProfileResponse>[]).where((p) => p.tenant == widget.tenant).toList()
      ..sort((a, b) => a.id == b.id ? b.version.compareTo(a.version) : a.id.compareTo(b.id));
    final resolvers = widget.supportsResolver ? ref.watch(authResolversProvider(widget.tenant)) : null;
    final profileKeys = <String, String>{};
    for (final p in profiles) {
      profileKeys.putIfAbsent(p.id, () => p.displayName);
    }

    final fields = <Widget>[
      if (_form.summary.isNotEmpty || _form.formError != null) YErrorSummary(form: _form, action: 'saving this route'),
      YResourcePicker(
        label: 'Profile',
        wireKey: 'profileId',
        form: _form,
        name: 'profile',
        required: true,
        value: _profileSlug,
        loading: profilesAsync.isLoading,
        resources: [
          for (final p in profiles) YResource(id: '${p.id}@${p.version}', name: p.displayName, meta: 'v${p.version}'),
        ],
        helper: 'The assistant that answers matching messages. Routes pin a version; publish a new version and edit '
            'the route to move it.',
        onChanged: (v) => setState(() => _profileSlug = v),
      ),
      YEnumCards<String>(
        label: 'Which messages',
        wireKey: 'routing.kind',
        form: _form,
        name: 'routing',
        value: _routingKind,
        options: _routingOptions,
        onChanged: (v) => setState(() => _routingKind = v),
      ),
      if (_routingKind == 'KeywordPrefix')
        YTextField(
          label: 'Keywords',
          wireKey: 'routing.keywords',
          controller: _keywords,
          form: _form,
          name: 'keywords',
          required: true,
          hint: 'e.g. #sales, #support',
          helper: 'Comma-separated. Matched at the start of the message, ignoring case.',
        ),
      if (_routingKind == 'Locale')
        YTextField(
          label: 'Locales',
          wireKey: 'routing.locales',
          controller: _locales,
          form: _form,
          name: 'locales',
          required: true,
          mono: true,
          hint: 'e.g. en-US, es',
          helper: 'Comma-separated language tags. A language-only entry (es) also matches its variants (es-DO).',
        ),
      if (_routingKind == 'EndUserClaim') ...[
        YTextField(
          label: 'Claim name',
          wireKey: 'routing.claim',
          controller: _claim,
          form: _form,
          name: 'claim',
          required: true,
          mono: true,
          hint: 'e.g. tier',
        ),
        YTextField(
          label: 'Claim value',
          wireKey: 'routing.equals',
          controller: _claimValue,
          form: _form,
          name: 'equals',
          required: true,
          hint: 'e.g. gold',
        ),
      ],
      YTextField(
        label: 'Priority',
        wireKey: 'priority',
        controller: _priority,
        form: _form,
        name: 'priority',
        required: true,
        mono: true,
        keyboardType: TextInputType.number,
        hint: 'e.g. 100',
        helper: 'Lower is tried first. Steps of 10 leave room to insert routes later.',
      ),
      YSwitchField(
        title: 'Active',
        wireKey: 'status',
        description: _status == 'ACTIVE'
            ? 'This route is in use.'
            : 'Paused — skipped when matching; messages fall through to the next route.',
        value: _status == 'ACTIVE',
        onChanged: (v) {
          setState(() => _status = v ? 'ACTIVE' : 'PAUSED');
          _form.refresh();
        },
      ),
      if (resolvers != null) ...[
        if (_platformSender)
          const YBanner(
            tone: YBannerTone.info,
            title: 'Shared platform number.',
            message: 'Only your registered staff may talk to an assistant here, so every route identifies the caller '
                'with a resolver and drops what it can’t resolve. Everyone else gets this workspace’s non-member reply.',
          ),
        YResourcePicker(
          label: 'Identity resolver',
          wireKey: 'authResolverId',
          form: _form,
          name: 'authResolverId',
          optional: !_platformSender,
          required: _platformSender,
          value: _authResolverId?.toString(),
          noneLabel: _platformSender ? null : 'None — FAQ mode',
          loading: resolvers.isLoading,
          resources: [
            for (final r in resolvers.valueOrNull?.resolvers ?? const <AuthResolverResponse>[])
              if (r.status == 'ACTIVE' || r.id == _authResolverId)
                YResource(
                  id: '${r.id}',
                  name: r.name,
                  meta: r.kind,
                  disabledReason: r.status == 'ACTIVE' ? null : 'Deactivated',
                ),
          ],
          helper: _platformSender
              ? 'Every message runs the resolver to identify the caller before the assistant starts. Required on a '
                  'shared platform number.'
              : 'When set, every message runs the resolver to identify the customer before the assistant starts. '
                  'Leave empty for an FAQ bot that only sees the verified phone.',
          onChanged: (v) {
            setState(() => _authResolverId = v == null ? null : int.tryParse(v));
            _form.refresh();
          },
        ),
        if (_authResolverId != null) ...[
          KeyedSubtree(
            key: const Key('route-on-unresolved'),
            child: YEnumCards<String>(
              label: 'When the resolver finds no user',
              wireKey: 'onUnresolved',
              value: _onUnresolved,
              helper: _platformSender
                  ? 'Fixed on a shared platform number: unknown callers never reach the assistant and get the '
                      'workspace’s non-member reply instead.'
                  : null,
              onChanged: (v) {
                setState(() => _onUnresolved = v);
                _form.refresh();
              },
              options: [
                YEnumOption('REJECT',
                    _platformSender ? 'Drop the message (non-member reply)' : 'Drop the message',
                    wire: 'REJECT',
                    description: _platformSender
                        ? 'Unknown callers get this workspace’s non-member reply, at most once a day.'
                        : 'Unknown customers get no answer.'),
                YEnumOption('AUTO_REPLY', 'Drop it, but reply once',
                    wire: 'AUTO_REPLY',
                    enabled: !_platformSender,
                    description: 'Unknown customers get one fixed message a day (e.g. where to call), then nothing.'),
                YEnumOption('ANONYMOUS', 'Answer anonymously',
                    wire: 'ANONYMOUS',
                    caution: true,
                    enabled: !_platformSender,
                    description: 'Falls back to the phone-only channel identity — the assistant answers without a user.'),
              ],
            ),
          ),
          if (_onUnresolved == 'AUTO_REPLY')
            YTextField(
              label: 'Reply to unknown callers',
              wireKey: 'unresolvedReplyText',
              controller: _replyText,
              form: _form,
              name: 'unresolvedReplyText',
              required: true,
              minLines: 2,
              maxLines: 4,
              hint: 'e.g. This number only sends receipts. Call +1 809 555 0100 for help.',
              helper: 'Sent at most once a day per caller. Up to 1000 characters.',
            ),
          YChipsField(
            label: 'Allowed override profiles',
            wireKey: 'allowedOverrideProfiles',
            optional: true,
            selected: _allowedOverrideProfiles,
            resources: [for (final e in profileKeys.entries) YResource(id: e.key, name: e.value)],
            hint: 'Type to add a profile…',
            helper: 'Profiles the resolver may switch the conversation to. Empty means any published profile.',
            onChanged: (v) {
              setState(() => _allowedOverrideProfiles = v);
              _form.refresh();
            },
          ),
          YEnumCards<String>(
            label: 'When an override is denied',
            wireKey: 'onOverrideDenied',
            value: _onOverrideDenied,
            onChanged: (v) {
              setState(() => _onOverrideDenied = v);
              _form.refresh();
            },
            options: const [
              YEnumOption('IGNORE', 'Use the routed profile', wire: 'IGNORE', description: 'The override is ignored.'),
              YEnumOption('REJECT', 'Drop the message', wire: 'REJECT', description: 'The customer gets no answer.'),
            ],
          ),
        ],
      ],
    ];

    return YUnsavedGuard(
      form: _form,
      child: YSaveShortcut(
        onSave: _save,
        child: YSheetScaffold(
          title: _existing == null ? 'Add route' : 'Edit route',
          subtitle: 'Fields marked * are required.',
          body: ListView(
            padding: const EdgeInsets.fromLTRB(28, 20, 28, 28),
            children: [
              for (var i = 0; i < fields.length; i++) ...[if (i > 0) const SizedBox(height: Gap.fieldGap), fields[i]],
            ],
          ),
          footer: YActionBar(
            form: _form,
            primaryLabel: _existing == null ? 'Add route' : 'Save route',
            onPrimary: _save,
            onCancel: () => closeYSheet(context),
          ),
        ),
      ),
    );
  }
}
