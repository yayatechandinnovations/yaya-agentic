import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:qr_flutter/qr_flutter.dart';

import '../../../api/admin_api.dart';
import '../../../api/api_config.dart';
import '../../../app/live/live_resource.dart';
import '../../../app/live/resource_kind.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/auth_resolver.dart';
import '../../../models/admin/chat_entry_point.dart';
import '../../../models/admin/profile.dart';
import '../../../models/admin/start_flow.dart';
import '../../../ui/data/live_async.dart';
import '../../../ui/data/relative_time.dart';
import '../../../ui/data/y_badge.dart';
import '../../../ui/data/y_page.dart';
import '../../../ui/data/y_sheet.dart';
import '../../../ui/data/y_states.dart';
import '../../../ui/data/y_table.dart';
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
import '../flows/flows_admin_screen.dart' show startFlowsProvider;
import '../profiles/profiles_screen.dart' show profilesProvider;
import '../shared/admin_shared.dart';
import 'web_image_picker.dart';

/// Chat inbound channel admin: branded entry points (the `LOGO | NAME` tiles
/// end users see in the chat app), their publish → pause → deactivate
/// lifecycle, and the QR / deep link to hand out. Each entry point pins one
/// profile and an auth mode. See `chat-channel-design.md` §6, §9.
final chatEntryPointsProvider = FutureProvider.family<List<ChatEntryPointResponse>, String>((ref, tenantId) async {
  liveResource(ref, AdminResourceKind.chatEntryPoint);
  final api = await ref.watch(adminApiProvider.future);
  return api.listChatEntryPoints(tenantId);
});

/// Resolve a (possibly root-relative) logo URL against the configured API base
/// so previews load whether the backend returned an absolute or relative URL.
String resolveLogoUrl(WidgetRef ref, String url) {
  if (url.isEmpty || url.startsWith('http')) return url;
  final base = ref.watch(apiConfigProvider).valueOrNull?.baseUrl ?? '';
  return '${base.replaceAll(RegExp(r'/+$'), '')}$url';
}

/// Conversation locales (BCP-47) the chat app ships translations for.
const _localeOptions = [YOption('en-US', 'English', meta: 'en-US'), YOption('es-DO', 'Spanish', meta: 'es-DO')];

String _normalizeLocale(String? raw) => (raw != null && raw.toLowerCase().startsWith('es')) ? 'es-DO' : 'en-US';

/// Preset accent palette (tile + chat-header theming).
const _palette = <String>[
  '#7A3CFF', '#D000FF', '#00C2FF', '#0B6E4F', '#2563EB', '#16A34A',
  '#DC2626', '#EA580C', '#CA8A04', '#0891B2', '#DB2777', '#475569',
];

/// Mirrors ChatEntryPointService.HEX_COLOR.
final _hexColor = RegExp(r'^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$');

Color? _colorFromHex(String? hex) {
  if (hex == null || !_hexColor.hasMatch(hex)) return null;
  var h = hex.substring(1);
  if (h.length == 6) h = 'FF$h';
  if (h.length == 8 && hex.length == 9) h = '${h.substring(6)}${h.substring(0, 6)}';
  final v = int.tryParse(h, radix: 16);
  return v == null ? null : Color(v);
}

/// Readable text on a brand colour the operator picked: whichever of the
/// scheme's surface / onSurface contrasts with it.
Color _onBrand(BuildContext context, Color bg) {
  final scheme = Theme.of(context).colorScheme;
  final surfaceIsLight = scheme.surface.computeLuminance() > scheme.onSurface.computeLuminance();
  final light = surfaceIsLight ? scheme.surface : scheme.onSurface;
  final dark = surfaceIsLight ? scheme.onSurface : scheme.surface;
  return ThemeData.estimateBrightnessForColor(bg) == Brightness.dark ? light : dark;
}

YBadge chatStatusBadge(String status) => switch (status) {
      'ACTIVE' => const YBadge('Live', tone: YTone.success, icon: Icons.check_circle_outline),
      'PAUSED' => const YBadge('Paused', tone: YTone.warning, icon: Icons.pause_circle_outline),
      'DEACTIVATED' => const YBadge('Deactivated', tone: YTone.danger, icon: Icons.block),
      _ => const YBadge('Draft', icon: Icons.edit_note),
    };

String _accessLabel(ChatEntryPointResponse ep) {
  final mode = switch (ep.authMode) {
    'AUTH_REQUIRED' => 'Sign-in required',
    'AUTH_OPTIONAL' => 'Sign-in optional',
    _ => 'Anyone',
  };
  return switch (ep.authConfig.kind) {
    'Oidc' => '$mode · OIDC',
    'ResolverExchange' => '$mode · resolver',
    _ => mode,
  };
}

class ChatsAdminScreen extends ConsumerStatefulWidget {
  const ChatsAdminScreen({super.key});

  @override
  ConsumerState<ChatsAdminScreen> createState() => _ChatsAdminScreenState();
}

class _ChatsAdminScreenState extends ConsumerState<ChatsAdminScreen> {
  String? _handledLink;

  Future<void> _open(String tenant, ChatEntryPointResponse? existing) async {
    await showYSheet<void>(
      context,
      width: 720,
      label: existing == null ? 'New chat' : 'Edit chat',
      builder: (_) => ChatEntryPointSheet(tenant: tenant, existing: existing),
    );
  }

  /// `?id=` / `?new=1` (⌘K, cross-links) open the editor once the list is in.
  void _handleDeepLink(String tenant, List<ChatEntryPointResponse> list) {
    final params = GoRouterState.of(context).uri.queryParameters;
    final link = params['id'] ?? (params['new'] != null ? '' : null);
    if (link == null || link == _handledLink) return;
    _handledLink = link;
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      final match = link.isEmpty ? null : list.where((e) => e.id == link || e.handle == link).firstOrNull;
      if (link.isNotEmpty && match == null) {
        showToast(context, 'No chat “$link” on this tenant', error: true);
      } else {
        await _open(tenant, match);
      }
      if (mounted) context.go('/admin/chats');
      _handledLink = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) return const TenantScopedEmptyState(resourceLabel: 'Chat entry points');
    final entryPoints = ref.watch(chatEntryPointsProvider(tenant));
    final fresh = ref.watch(freshIdsProvider(AdminResourceKind.chatEntryPoint));
    entryPoints.whenData((l) => _handleDeepLink(tenant, l));
    final scheme = Theme.of(context).colorScheme;

    return YPage(
      title: 'Chats',
      subtitle: 'Branded chat tiles people open from a QR code or link. Each one talks to a single profile; '
          'publish it to make the link work.',
      actions: [
        FilledButton.icon(onPressed: () => _open(tenant, null), icon: const Icon(Icons.add, size: 18), label: const Text('New chat')),
      ],
      child: LiveAsyncView<List<ChatEntryPointResponse>>(
        value: entryPoints,
        what: 'chats',
        onRetry: () => ref.invalidate(chatEntryPointsProvider(tenant)),
        data: (context, list) => YTable<ChatEntryPointResponse>(
          rows: list,
          noun: 'chats',
          idOf: (e) => e.id,
          searchHint: 'Search by name, profile or handle',
          searchTextOf: (e) => '${e.displayName} ${e.profileId} ${e.handle} ${e.tagline ?? ''} ${e.id}',
          highlightIds: fresh,
          onRowTap: (e) => _open(tenant, e),
          filters: [
            YFilter('Live', (e) => e.isActive),
            YFilter('Drafts', (e) => e.isDraft),
            YFilter('Paused', (e) => e.isPaused),
            YFilter('Deactivated', (e) => e.isDeactivated),
            YFilter('In directory', (e) => e.discoverable),
            YFilter('Sign-in required', (e) => e.authMode == 'AUTH_REQUIRED'),
          ],
          empty: YEmptyState(
            icon: Icons.chat_bubble_outline,
            title: 'No chats yet',
            message: 'A chat is a branded tile — logo, name, colour — that opens a conversation with one of your profiles '
                'from a QR code or link.',
            action: FilledButton.icon(
              onPressed: () => _open(tenant, null),
              icon: const Icon(Icons.add, size: 18),
              label: const Text('Create your first chat'),
            ),
          ),
          columns: [
            YColumn(
              label: 'Chat',
              flex: 3,
              sortKey: (e) => e.displayName.toLowerCase(),
              cell: (context, e) => Row(children: [
                _LogoAvatar(url: resolveLogoUrl(ref, e.logoUrl), name: e.displayName, hex: e.accentColorHex),
                const SizedBox(width: 12),
                Expanded(child: YCellStack(primary: e.displayName, secondary: (e.tagline ?? '').isNotEmpty ? e.tagline : e.handle)),
              ]),
            ),
            YColumn(
              label: 'Profile',
              flex: 2,
              sortKey: (e) => e.profileId,
              cell: (context, e) => YCellStack(primary: e.profileId, mono: true, secondary: 'v${e.profileVersion}'),
            ),
            YColumn(
              label: 'Who can chat',
              flex: 2,
              sortKey: (e) => e.authMode,
              cell: (context, e) => Text(_accessLabel(e), overflow: TextOverflow.ellipsis),
            ),
            YColumn(label: 'Status', width: 140, sortKey: (e) => e.status, cell: (context, e) => chatStatusBadge(e.status)),
            YColumn(
              label: 'Directory',
              width: 130,
              sortKey: (e) => e.discoverable ? 0 : 1,
              cell: (context, e) => e.discoverable
                  ? YBadge('Listed', tone: e.isActive ? YTone.info : YTone.neutral, icon: Icons.travel_explore)
                  : Text('Not listed', style: TextStyle(color: scheme.onSurfaceVariant)),
            ),
            YColumn(
              label: 'Updated',
              width: 110,
              sortKey: (e) => e.updatedAt ?? e.createdAt ?? '',
              cell: (context, e) => Text(fresh.contains(e.id) ? 'Just now' : formatRelativeIso(e.updatedAt ?? e.createdAt)),
            ),
            YColumn(
              label: '',
              width: 56,
              alignEnd: true,
              cell: (context, e) => IconButton(
                tooltip: 'QR code and link',
                icon: const Icon(Icons.qr_code_2, size: 20),
                onPressed: e.isDeactivated ? null : () => showChatQrDialog(context, e),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// The scannable code and link for one chat.
Future<void> showChatQrDialog(BuildContext context, ChatEntryPointResponse ep) {
  return showDialog<void>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text(ep.displayName),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 320),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          _QrCode(data: ep.deepLink, size: 220),
          const SizedBox(height: Gap.md),
          SelectableText(ep.deepLink, textAlign: TextAlign.center, style: monoStyle(ctx, size: 12.5)),
          if (!ep.isActive) ...[
            const SizedBox(height: Gap.md),
            Text(
              ep.isDraft ? 'This chat is a draft — the link works once you publish it.' : 'This chat is paused — the link works again once you resume it.',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 13, color: Theme.of(ctx).colorScheme.onSurfaceVariant),
            ),
          ],
        ]),
      ),
      actions: [
        OutlinedButton.icon(
          onPressed: () {
            Clipboard.setData(ClipboardData(text: ep.deepLink));
            showToast(ctx, 'Link copied');
          },
          icon: const Icon(Icons.copy, size: 18),
          label: const Text('Copy link'),
        ),
        FilledButton(onPressed: () => Navigator.pop(ctx), child: const Text('Done')),
      ],
    ),
  );
}

class _QrCode extends StatelessWidget {
  const _QrCode({required this.data, required this.size});
  final String data;
  final double size;

  @override
  Widget build(BuildContext context) {
    // Dark modules on white regardless of theme: phone scanners can't read an
    // inverted code, and operators print these.
    return Semantics(
      label: 'QR code for $data',
      image: true,
      child: Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: Theme.of(context).colorScheme.outlineVariant),
        ),
        child: QrImageView(data: data, size: size, backgroundColor: Colors.white),
      ),
    );
  }
}

class _LogoAvatar extends StatelessWidget {
  const _LogoAvatar({required this.url, required this.name, this.hex, this.size = 36});
  final String url;
  final String name;
  final String? hex;
  final double size;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final brand = _colorFromHex(hex);
    final bg = brand ?? scheme.primaryContainer;
    final fg = brand == null ? scheme.onPrimaryContainer : _onBrand(context, brand);
    final initials = Text(name.trim().isEmpty ? '?' : name.trim()[0].toUpperCase(),
        style: TextStyle(fontSize: size * 0.42, fontWeight: FontWeight.w600, color: fg));
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(color: bg, shape: BoxShape.circle),
      clipBehavior: Clip.antiAlias,
      alignment: Alignment.center,
      child: url.isEmpty
          ? initials
          : Image.network(url, width: size, height: size, fit: BoxFit.cover, errorBuilder: (_, _, _) => initials),
    );
  }
}

// ---------------------------------------------------------------------------
// Editor sheet
// ---------------------------------------------------------------------------

const _labels = {
  'profile': 'Profile',
  'displayName': 'Display name',
  'logoUrl': 'Logo',
  'accentColorHex': 'Accent colour',
  'welcomeText': 'Welcome message',
  'defaultLocale': 'Language',
  'startFlowId': 'Start flow',
  'authMode': 'Who can chat',
  'authMethod': 'Sign-in method',
  'issuer': 'OIDC issuer',
  'audience': 'OIDC audience',
  'authResolverId': 'Auth resolver',
  'allowedOverrideProfiles': 'Profiles the resolver may switch to',
  'onOverrideDenied': 'When a switch isn’t allowed',
  'requiresVerifiedPhone': 'Verified phone',
  'tagline': 'Directory tagline',
  'discoverable': 'Public directory',
};

/// Create a chat (as a draft), or edit one and drive its lifecycle, in a side sheet.
class ChatEntryPointSheet extends ConsumerStatefulWidget {
  const ChatEntryPointSheet({super.key, required this.tenant, this.existing});
  final String tenant;
  final ChatEntryPointResponse? existing;

  @override
  ConsumerState<ChatEntryPointSheet> createState() => _ChatEntryPointSheetState();
}

class _ChatEntryPointSheetState extends ConsumerState<ChatEntryPointSheet> {
  final _displayName = TextEditingController();
  final _welcome = TextEditingController();
  final _tagline = TextEditingController();
  final _issuer = TextEditingController();
  final _audience = TextEditingController();

  String? _profile; // "<id>@<version>"
  String? _logoUrl;
  String? _logoError;
  bool _uploading = false;
  String? _accent;
  String _locale = 'en-US';
  String? _startFlowId;
  String _authMode = 'ANONYMOUS_ONLY';
  String _authMethod = 'OIDC';
  int? _resolverId;
  List<String> _allowlist = [];
  String _onDenied = 'IGNORE';
  bool _verifiedPhone = false;
  bool _discoverable = false;

  ChatEntryPointResponse? _ep;
  bool _lifecycleBusy = false;
  late final YFormController _form;

  bool get _editing => _ep != null;
  bool get _needsIdentity => _authMode != 'ANONYMOUS_ONLY';
  bool get _usesResolver => _needsIdentity && _authMethod == 'RESOLVER';

  @override
  void initState() {
    super.initState();
    _ep = widget.existing;
    _load(_ep);
    _form = YFormController(
      snapshot: _snapshot,
      validate: _validate,
      labels: _labels,
      draftKey: 'yaya.draft.chatEntryPoint.${widget.tenant}.${_ep?.id ?? 'new'}',
    );
    _form.addListener(_rebuild);
    _form.loadDraft().then((d) {
      if (d == null || !mounted) return;
      setState(() => _applySnapshot(d.values));
      _form.refresh();
    });
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  void _load(ChatEntryPointResponse? e) {
    _profile = e == null ? null : '${e.profileId}@${e.profileVersion}';
    _displayName.text = e?.displayName ?? '';
    _logoUrl = e?.logoUrl;
    _accent = e?.accentColorHex;
    _welcome.text = e?.welcomeText ?? '';
    _tagline.text = e?.tagline ?? '';
    _locale = _normalizeLocale(e?.defaultLocale);
    _startFlowId = e?.startFlowId;
    _authMode = e?.authMode ?? 'ANONYMOUS_ONLY';
    _authMethod = e?.authConfig.kind == 'ResolverExchange' ? 'RESOLVER' : 'OIDC';
    _issuer.text = e?.authConfig.issuer ?? '';
    _audience.text = e?.authConfig.audience ?? '';
    _resolverId = e?.authConfig.authResolverId;
    _allowlist = List.of(e?.allowedOverrideProfiles ?? const []);
    _onDenied = e?.onOverrideDenied ?? 'IGNORE';
    _verifiedPhone = e?.requiresVerifiedPhone ?? false;
    _discoverable = e?.discoverable ?? false;
  }

  Map<String, Object?> _snapshot() => {
        'profile': _profile,
        'displayName': _displayName.text.trim(),
        'logoUrl': _logoUrl ?? '',
        'accentColorHex': _accent ?? '',
        'welcomeText': _welcome.text.trim(),
        'defaultLocale': _locale,
        'startFlowId': _startFlowId ?? '',
        'authMode': _authMode,
        'authMethod': _authMethod,
        'issuer': _issuer.text.trim(),
        'audience': _audience.text.trim(),
        'authResolverId': _resolverId,
        'allowedOverrideProfiles': _allowlist,
        'onOverrideDenied': _onDenied,
        'requiresVerifiedPhone': _verifiedPhone,
        'tagline': _tagline.text.trim(),
        'discoverable': _discoverable,
      };

  void _applySnapshot(Map<String, Object?> v) {
    String? s(String k) => v[k] is String ? v[k] as String : null;
    _profile = s('profile') ?? _profile;
    _displayName.text = s('displayName') ?? _displayName.text;
    final logo = s('logoUrl');
    if (logo != null && logo.isNotEmpty) _logoUrl = logo;
    final accent = s('accentColorHex');
    if (accent != null) _accent = accent.isEmpty ? null : accent;
    _welcome.text = s('welcomeText') ?? _welcome.text;
    _locale = _normalizeLocale(s('defaultLocale') ?? _locale);
    final flow = s('startFlowId');
    if (flow != null) _startFlowId = flow.isEmpty ? null : flow;
    _authMode = s('authMode') ?? _authMode;
    _authMethod = s('authMethod') ?? _authMethod;
    _issuer.text = s('issuer') ?? _issuer.text;
    _audience.text = s('audience') ?? _audience.text;
    if (v['authResolverId'] is num) _resolverId = (v['authResolverId'] as num).toInt();
    if (v['allowedOverrideProfiles'] is List) _allowlist = (v['allowedOverrideProfiles'] as List).map((e) => '$e').toList();
    _onDenied = s('onOverrideDenied') ?? _onDenied;
    if (v['requiresVerifiedPhone'] is bool) _verifiedPhone = v['requiresVerifiedPhone'] as bool;
    _tagline.text = s('tagline') ?? _tagline.text;
    if (v['discoverable'] is bool) _discoverable = v['discoverable'] as bool;
  }

  Map<String, String?> _validate() {
    String? accentErr;
    final a = _accent;
    if (a != null && a.isNotEmpty && !_hexColor.hasMatch(a)) accentErr = 'Use a colour like #2563EB.';
    return {
      'profile': _profile == null ? 'Choose the profile people talk to in this chat.' : null,
      'displayName': Validators.required(_displayName.text, 'the name people see on the tile'),
      'logoUrl': _logoError ?? ((_logoUrl ?? '').isEmpty ? 'Upload a logo — it’s the tile people tap.' : null),
      'accentColorHex': accentErr,
      'issuer': _needsIdentity && _authMethod == 'OIDC' ? Validators.httpUrl(_issuer.text) : null,
      'authResolverId': _usesResolver && _resolverId == null ? 'Choose the auth resolver that identifies people.' : null,
    };
  }

  void _set(String field, VoidCallback change) {
    setState(change);
    _form.changed(field);
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _form.dispose();
    for (final c in [_displayName, _welcome, _tagline, _issuer, _audience]) {
      c.dispose();
    }
    super.dispose();
  }

  ChatAuthConfig? _authConfig() {
    if (!_needsIdentity) return _editing ? ChatAuthConfig(kind: 'None') : null;
    if (_authMethod == 'OIDC') {
      final base = _ep?.authConfig;
      return ChatAuthConfig(
        kind: 'Oidc',
        issuer: _issuer.text.trim(),
        audience: _audience.text.trim().isEmpty ? null : _audience.text.trim(),
        // Scopes aren't edited here; keep whatever the record already asks for.
        scopes: base?.kind == 'Oidc' ? base!.scopes : const [],
      );
    }
    // The backend mints the sender key on create and keeps the stored one on update.
    return ChatAuthConfig(kind: 'ResolverExchange', authResolverId: _resolverId, senderKey: _ep?.authConfig.senderKey);
  }

  ChatEntryPointRequest _request() {
    final slug = _profile!.split('@');
    return ChatEntryPointRequest(
      profileId: slug.first,
      profileVersion: slug.length > 1 ? int.tryParse(slug[1]) : null,
      displayName: _displayName.text.trim(),
      logoUrl: _logoUrl!,
      // An empty string clears a stored accent; null leaves it alone.
      accentColorHex: _accent ?? ((_ep?.accentColorHex ?? '').isNotEmpty ? '' : null),
      welcomeText: _welcome.text.trim(),
      defaultLocale: _locale,
      tagline: _tagline.text.trim().isEmpty && !_editing ? null : _tagline.text.trim(),
      discoverable: _discoverable,
      startFlowId: _startFlowId,
      authMode: _authMode,
      authConfig: _authConfig(),
      requiresVerifiedPhone: _usesResolver || _verifiedPhone,
      allowedOverrideProfiles: _usesResolver ? _allowlist : (_editing ? const [] : null),
      onOverrideDenied: _usesResolver ? _onDenied : (_editing ? 'IGNORE' : null),
    );
  }

  Future<void> _save() async {
    if (_uploading) return;
    if (!_form.submit()) return;
    _form.saving = true;
    try {
      final api = await ref.read(adminApiProvider.future);
      final saved = _editing
          ? await api.updateChatEntryPoint(widget.tenant, _ep!.id, _request())
          : await api.createChatEntryPoint(widget.tenant, _request());
      ref.invalidate(chatEntryPointsProvider(widget.tenant));
      _form.saving = false;
      _form.markClean();
      if (!mounted) return;
      showToast(context, _editing ? 'Saved ${saved.displayName}' : 'Created ${saved.displayName} as a draft — publish it to make the link work');
      Navigator.of(context).pop();
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(e);
    }
  }

  Future<void> _uploadLogo() async {
    final picked = await pickImageFromBrowser();
    if (picked == null || !mounted) return;
    const allowed = {'image/png', 'image/jpeg', 'image/webp', 'image/gif'};
    const maxBytes = 2 * 1024 * 1024; // keep in sync with ChatLogoService
    String? err;
    if (!allowed.contains(picked.contentType)) {
      err = 'That file isn’t a PNG, JPEG, WebP or GIF image.';
    } else if (picked.bytes.length > maxBytes) {
      err = 'That image is ${(picked.bytes.length / (1024 * 1024)).toStringAsFixed(1)} MB — logos must be under 2 MB.';
    }
    if (err != null) {
      setState(() => _logoError = err);
      _form.touch('logoUrl');
      return;
    }
    setState(() {
      _uploading = true;
      _logoError = null;
    });
    try {
      final api = await ref.read(adminApiProvider.future);
      final url = await api.uploadChatLogo(widget.tenant, picked.contentType, base64Encode(picked.bytes));
      if (!mounted) return;
      _set('logoUrl', () => _logoUrl = url);
    } catch (e) {
      if (!mounted) return;
      setState(() => _logoError = 'Upload failed: ${ApiErrorInfo.from(e).message}');
      _form.touch('logoUrl');
    } finally {
      if (mounted) setState(() => _uploading = false);
    }
  }

  // ---- lifecycle ---------------------------------------------------------

  Future<void> _lifecycle(
    Future<ChatEntryPointResponse> Function(AdminApi api) call,
    String Function(ChatEntryPointResponse) done,
  ) async {
    setState(() => _lifecycleBusy = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      final next = await call(api);
      ref.invalidate(chatEntryPointsProvider(widget.tenant));
      if (!mounted) return;
      setState(() => _ep = next);
      showToast(context, done(next));
    } catch (e) {
      if (mounted) showToast(context, ApiErrorInfo.from(e).message, error: true);
    } finally {
      if (mounted) setState(() => _lifecycleBusy = false);
    }
  }

  Future<void> _publish() => _lifecycle(
        (api) => api.publishChatEntryPoint(widget.tenant, _ep!.id),
        (e) => e.discoverable ? '${e.displayName} is live and listed in Discover' : '${e.displayName} is live — its link and QR code work now',
      );

  Future<void> _pause() async {
    final ep = _ep!;
    final ok = await confirmAction(
      context,
      title: 'Pause ${ep.displayName}?',
      message: 'New conversations can’t start from its link or QR code until you resume it'
          '${ep.discoverable ? ', and it drops out of the public directory' : ''}.',
      confirmLabel: 'Pause chat',
    );
    if (!ok) return;
    await _lifecycle((api) => api.pauseChatEntryPoint(widget.tenant, ep.id), (e) => '${e.displayName} is paused');
  }

  Future<void> _resume() =>
      _lifecycle((api) => api.resumeChatEntryPoint(widget.tenant, _ep!.id), (e) => '${e.displayName} is live again');

  Future<void> _rotate() async {
    final ep = _ep!;
    final ok = await confirmAction(
      context,
      title: 'Replace the link for ${ep.displayName}?',
      message: 'The current link and QR code stop working immediately — printed codes and links you’ve shared will no longer '
          'open this chat. You’ll get a new link to hand out.',
      confirmLabel: 'Replace link',
      destructive: true,
    );
    if (!ok) return;
    await _lifecycle((api) => api.rotateChatEntryPointHandle(widget.tenant, ep.id), (_) => 'New link ready — the old QR code no longer works');
  }

  Future<void> _deactivate() async {
    final ep = _ep!;
    final ok = await confirmTyped(
      context,
      title: 'Deactivate ${ep.displayName} for good?',
      message: 'Its link and QR code stop working permanently and it can’t be published again. '
          'Create a new chat if you need one later.',
      expected: ep.displayName,
      confirmLabel: 'Deactivate chat',
    );
    if (!ok) return;
    await _lifecycle((api) => api.deactivateChatEntryPoint(widget.tenant, ep.id), (e) => '${e.displayName} is deactivated');
  }

  // ---- build -------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    final ep = _ep;
    final subtitle = ep == null
        ? 'Saved as a draft; publish it to make the link work. Fields marked * are required.'
        : ep.isActive
            ? 'Live. Changes apply to new conversations; ones already running keep their profile.'
            : ep.isDeactivated
                ? 'Deactivated — its link no longer works.'
                : ep.isPaused
                    ? 'Paused — its link is off until you resume it.'
                    : 'Draft — publish it to make the link work.';

    return YUnsavedGuard(
      form: _form,
      child: YSaveShortcut(
        onSave: _save,
        child: YSheetScaffold(
          title: ep?.displayName ?? 'New chat',
          subtitle: subtitle,
          headerActions: [if (ep != null) Padding(padding: const EdgeInsets.only(top: 8, right: 4), child: chatStatusBadge(ep.status))],
          body: ListView(
            padding: const EdgeInsets.fromLTRB(28, 12, 28, 28),
            children: [
              if (_form.summary.isNotEmpty || _form.formError != null) ...[
                const SizedBox(height: Gap.md),
                YErrorSummary(form: _form, action: _editing ? 'saving' : 'creating this chat'),
              ],
              if (ep != null) _shareSection(context, ep),
              _basicsSection(context),
              _conversationSection(),
              _accessSection(context),
              _directorySection(ep),
            ],
          ),
          footer: YActionBar(
            form: _form,
            primaryLabel: _editing ? 'Save changes' : 'Create draft',
            onPrimary: _save,
            onCancel: () => closeYSheet(context),
          ),
        ),
      ),
    );
  }

  Widget _shareSection(BuildContext context, ChatEntryPointResponse ep) {
    final scheme = Theme.of(context).colorScheme;
    final busy = _lifecycleBusy;
    return YFormSection(
      title: 'Link and status',
      card: false,
      children: [
        if (ep.isDeactivated)
          const YBanner(tone: YBannerTone.error, message: 'This chat is deactivated. Its link and QR code no longer open it, and it can’t be published again.')
        else
          Wrap(spacing: 20, runSpacing: 16, crossAxisAlignment: WrapCrossAlignment.start, children: [
            _QrCode(data: ep.deepLink, size: 132),
            ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 420),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                const YFieldLabel(label: 'Link', wireKey: 'deepLink'),
                SelectableText(ep.deepLink, style: monoStyle(context, size: 12.5)),
                const SizedBox(height: Gap.xs),
                Text(
                  ep.isActive
                      ? 'Anyone with this link or QR code can start a chat.'
                      : ep.isDraft
                          ? 'The link works once you publish.'
                          : 'The link is off while the chat is paused.',
                  style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant),
                ),
                const SizedBox(height: Gap.md),
                Wrap(spacing: 8, runSpacing: 8, children: [
                  OutlinedButton.icon(
                    onPressed: () {
                      Clipboard.setData(ClipboardData(text: ep.deepLink));
                      showToast(context, 'Link copied');
                    },
                    icon: const Icon(Icons.copy, size: 18),
                    label: const Text('Copy link'),
                  ),
                  OutlinedButton.icon(
                    onPressed: () => showChatQrDialog(context, ep),
                    icon: const Icon(Icons.qr_code_2, size: 18),
                    label: const Text('Large QR'),
                  ),
                ]),
              ]),
            ),
          ]),
        if (!ep.isDeactivated)
          Wrap(spacing: 8, runSpacing: 8, crossAxisAlignment: WrapCrossAlignment.center, children: [
            if (ep.isDraft)
              FilledButton.icon(onPressed: busy ? null : _publish, icon: const Icon(Icons.publish, size: 18), label: const Text('Publish')),
            if (ep.isActive)
              OutlinedButton.icon(onPressed: busy ? null : _pause, icon: const Icon(Icons.pause, size: 18), label: const Text('Pause')),
            if (ep.isPaused)
              FilledButton.icon(onPressed: busy ? null : _resume, icon: const Icon(Icons.play_arrow, size: 18), label: const Text('Resume')),
            TextButton.icon(onPressed: busy ? null : _rotate, icon: const Icon(Icons.autorenew, size: 18), label: const Text('Replace link')),
            TextButton.icon(
              onPressed: busy ? null : _deactivate,
              icon: const Icon(Icons.block, size: 18),
              label: const Text('Deactivate'),
              style: TextButton.styleFrom(foregroundColor: scheme.error),
            ),
            if (busy) const SizedBox.square(dimension: 18, child: CircularProgressIndicator(strokeWidth: 2)),
          ]),
        Text('ID ${ep.id} · created ${formatRelativeIso(ep.createdAt)}',
            style: monoStyle(context, size: 12, color: scheme.onSurfaceVariant)),
      ],
    );
  }

  Widget _basicsSection(BuildContext context) {
    final profiles = (ref.watch(profilesProvider).valueOrNull ?? const <ProfileResponse>[])
        .where((p) => p.tenant == widget.tenant)
        .toList()
      ..sort((a, b) => a.id == b.id ? b.version.compareTo(a.version) : a.id.compareTo(b.id));
    return YFormSection(
      title: 'Tile',
      description: 'What people see before they start: the logo, name and colour, and who answers.',
      card: false,
      children: [
        YResourcePicker(
          label: 'Profile',
          wireKey: 'profileId',
          form: _form,
          name: 'profile',
          required: true,
          loading: ref.watch(profilesProvider).isLoading,
          value: _profile,
          hint: 'Search profiles…',
          helper: _editing
              ? 'Who answers. Switching applies to new conversations; running ones keep the profile they started with.'
              : 'Who answers in this chat.',
          resources: [
            for (final p in profiles)
              YResource(id: '${p.id}@${p.version}', name: p.displayName.isEmpty ? p.id : p.displayName, meta: 'v${p.version}${p.status == null ? '' : ' · ${p.status!.toLowerCase()}'}'),
          ],
          onChanged: (v) => _set('profile', () => _profile = v),
        ),
        YTextField(
          label: 'Display name',
          wireKey: 'displayName',
          controller: _displayName,
          form: _form,
          name: 'displayName',
          required: true,
          autofocus: !_editing,
          hint: 'e.g. Yaya Market',
          helper: 'Shown on the tile and in the chat header.',
        ),
        YFieldShell(
          label: 'Logo',
          wireKey: 'logoUrl',
          form: _form,
          name: 'logoUrl',
          required: true,
          helper: 'PNG, JPEG, WebP or GIF under 2 MB. Square images look best.',
          child: Row(children: [
            _LogoAvatar(url: resolveLogoUrl(ref, _logoUrl ?? ''), name: _displayName.text, hex: _accent, size: 56),
            const SizedBox(width: Gap.md),
            OutlinedButton.icon(
              onPressed: _uploading ? null : _uploadLogo,
              icon: _uploading
                  ? const SizedBox.square(dimension: 16, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.upload, size: 18),
              label: Text(_uploading ? 'Uploading…' : (_logoUrl ?? '').isEmpty ? 'Upload logo' : 'Replace logo'),
            ),
          ]),
        ),
        YFieldShell(
          label: 'Accent colour',
          wireKey: 'accentColorHex',
          form: _form,
          name: 'accentColorHex',
          optional: true,
          helper: 'Colours the tile and the chat header.',
          child: _AccentPicker(value: _accent, onPick: (hex) => _set('accentColorHex', () => _accent = hex)),
        ),
      ],
    );
  }

  Widget _conversationSection() {
    final flows = ref.watch(startFlowsProvider(widget.tenant));
    final latest = <String, StartFlowResponse>{};
    for (final f in flows.valueOrNull ?? const <StartFlowResponse>[]) {
      final cur = latest[f.id];
      if (cur == null || f.version > cur.version) latest[f.id] = f;
    }
    return YFormSection(
      title: 'Conversation',
      card: false,
      children: [
        YTextField(
          label: 'Welcome message',
          wireKey: 'welcomeText',
          controller: _welcome,
          form: _form,
          name: 'welcomeText',
          optional: true,
          minLines: 2,
          maxLines: 4,
          hint: 'e.g. Hi! Ask me about today’s specials.',
          helper: 'The first bubble people see, before they type anything.',
        ),
        YSelectField<String>(
          label: 'Language',
          wireKey: 'defaultLocale',
          form: _form,
          name: 'defaultLocale',
          value: _locale,
          options: _localeOptions,
          helper: 'The chat app’s language for this tile.',
          onChanged: (v) => _set('defaultLocale', () => _locale = v ?? 'en-US'),
        ),
        YResourcePicker(
          label: 'Start flow',
          wireKey: 'startFlowId',
          form: _form,
          name: 'startFlowId',
          optional: true,
          noneLabel: 'None',
          loading: flows.isLoading,
          value: _startFlowId,
          helper: 'Steps that run automatically when a conversation starts here, before the greeting.',
          resources: [
            for (final f in latest.values)
              YResource(id: f.id, name: f.name.isEmpty ? null : f.name, meta: 'v${f.version} · ${f.status.toLowerCase()}'),
          ],
          onChanged: (v) => _set('startFlowId', () => _startFlowId = v),
        ),
      ],
    );
  }

  Widget _accessSection(BuildContext context) {
    final resolvers = ref.watch(authResolversProvider(widget.tenant));
    final active = (resolvers.valueOrNull?.resolvers ?? const <AuthResolverResponse>[])
        .where((r) => r.status == 'ACTIVE' || r.id == _resolverId)
        .toList();
    final profileKeys = <String, String>{};
    for (final p in ref.watch(profilesProvider).valueOrNull ?? const <ProfileResponse>[]) {
      if (p.tenant == widget.tenant) profileKeys.putIfAbsent(p.id, () => p.displayName);
    }
    final senderKey = _ep?.authConfig.senderKey;

    return YFormSection(
      title: 'Who can chat',
      card: false,
      children: [
        YEnumCards<String>(
          label: 'Sign-in',
          wireKey: 'authMode',
          form: _form,
          name: 'authMode',
          value: _authMode,
          options: const [
            YEnumOption('ANONYMOUS_ONLY', 'Anyone, no sign-in',
                wire: 'ANONYMOUS_ONLY', description: 'People chat as guests. Tools that need a verified user are refused.'),
            YEnumOption('AUTH_OPTIONAL', 'Sign-in optional',
                wire: 'AUTH_OPTIONAL', description: 'People start as guests and can sign in mid-chat to unlock account actions.'),
            YEnumOption('AUTH_REQUIRED', 'Sign-in required',
                wire: 'AUTH_REQUIRED', description: 'People must sign in before their first message.'),
          ],
          onChanged: (v) => _set('authMode', () => _authMode = v),
        ),
        if (_needsIdentity) ...[
          YEnumCards<String>(
            label: 'Sign-in method',
            wireKey: 'authConfig.kind',
            form: _form,
            name: 'authMethod',
            value: _authMethod,
            options: const [
              YEnumOption('OIDC', 'Your identity provider (OIDC)',
                  wire: 'Oidc', description: 'The chat app sends people to your sign-in page. Its tokens must be trusted under OIDC issuers.'),
              YEnumOption('RESOLVER', 'Your backend vouches (auth resolver)',
                  wire: 'ResolverExchange',
                  description: 'Your service swaps the app’s session for a token. People need a verified phone.'),
            ],
            onChanged: (v) => _set('authMethod', () => _authMethod = v),
          ),
          if (_authMethod == 'OIDC') ...[
            YTextField(
              label: 'OIDC issuer',
              wireKey: 'authConfig.issuer',
              controller: _issuer,
              form: _form,
              name: 'issuer',
              required: true,
              mono: true,
              hint: 'e.g. https://login.acme.com',
              helper: 'Where people sign in. Add the same issuer under OIDC issuers so their tokens are accepted.',
            ),
            YTextField(
              label: 'OIDC audience',
              wireKey: 'authConfig.audience',
              controller: _audience,
              form: _form,
              name: 'audience',
              optional: true,
              mono: true,
              hint: 'e.g. yaya-chat',
              helper: 'The client ID the app signs in as, if your provider needs one.',
            ),
          ] else ...[
            YResourcePicker(
              label: 'Auth resolver',
              wireKey: 'authConfig.authResolverId',
              form: _form,
              name: 'authResolverId',
              required: true,
              loading: resolvers.isLoading,
              value: _resolverId?.toString(),
              hint: active.isEmpty && !resolvers.isLoading ? 'No active resolvers — create one under Auth resolvers' : 'Search resolvers…',
              helper: 'The service that turns the app’s session into a verified user.',
              resources: [
                for (final r in active)
                  YResource(
                    id: '${r.id}',
                    name: r.name,
                    meta: r.kind,
                    disabledReason: r.status == 'ACTIVE' ? null : 'Not active',
                  ),
              ],
              onChanged: (v) => _set('authResolverId', () => _resolverId = v == null ? null : int.tryParse(v)),
            ),
            YFieldShell(
              label: 'Sender key',
              wireKey: 'authConfig.senderKey',
              helper: senderKey == null || senderKey.isEmpty
                  ? 'Generated when you save. Register it on your resolver so it accepts requests from this chat.'
                  : 'Register this code on your resolver so it accepts requests from this chat. It never changes.',
              child: senderKey == null || senderKey.isEmpty
                  ? Text('Generated on save', style: TextStyle(fontSize: 14, color: Theme.of(context).colorScheme.onSurfaceVariant))
                  : Row(children: [
                      SelectableText(senderKey, style: monoStyle(context, size: 16, weight: FontWeight.w600)),
                      const SizedBox(width: Gap.sm),
                      IconButton(
                        tooltip: 'Copy sender key',
                        icon: const Icon(Icons.copy, size: 18),
                        onPressed: () {
                          Clipboard.setData(ClipboardData(text: senderKey));
                          showToast(context, 'Sender key copied');
                        },
                      ),
                    ]),
            ),
            YChipsField(
              label: 'Profiles the resolver may switch to',
              wireKey: 'allowedOverrideProfiles',
              form: _form,
              name: 'allowedOverrideProfiles',
              optional: true,
              selected: _allowlist,
              resources: [
                for (final e in profileKeys.entries) YResource(id: e.key, name: e.value.isEmpty ? null : e.value),
              ],
              hint: 'Type to add a profile…',
              helper: _allowlist.isEmpty
                  ? 'Empty: the resolver may route people to any published profile.'
                  : 'The resolver may only route people to these profiles.',
              onChanged: (v) => _set('allowedOverrideProfiles', () => _allowlist = v),
            ),
            YEnumCards<String>(
              label: 'When a switch isn’t allowed',
              wireKey: 'onOverrideDenied',
              form: _form,
              name: 'onOverrideDenied',
              value: _onDenied,
              horizontal: true,
              options: const [
                YEnumOption('IGNORE', 'Keep this chat’s profile', wire: 'IGNORE', description: 'The conversation goes ahead with the profile above.'),
                YEnumOption('REJECT', 'Refuse the conversation',
                    wire: 'REJECT', caution: true, description: 'The person can’t start a chat.'),
              ],
              onChanged: (v) => _set('onOverrideDenied', () => _onDenied = v),
            ),
          ],
        ],
        YSwitchField(
          title: 'Require a verified phone',
          wireKey: 'requiresVerifiedPhone',
          description: 'Only devices that confirmed a phone number by SMS code can start this chat.',
          value: _usesResolver || _verifiedPhone,
          disabledReason: _usesResolver ? 'Always on with an auth resolver — it identifies people by their verified phone.' : null,
          onChanged: (v) => _set('requiresVerifiedPhone', () => _verifiedPhone = v),
        ),
      ],
    );
  }

  Widget _directorySection(ChatEntryPointResponse? ep) {
    return YFormSection(
      title: 'Public directory',
      card: false,
      children: [
        YSwitchField(
          title: 'List in the public directory',
          wireKey: 'discoverable',
          description: ep == null || !ep.isActive
              ? 'People can find this chat by searching Discover in the chat app — once it’s published.'
              : 'People can find this chat by searching Discover in the chat app.',
          value: _discoverable,
          onChanged: (v) => _set('discoverable', () => _discoverable = v),
        ),
        YTextField(
          label: 'Directory tagline',
          wireKey: 'tagline',
          controller: _tagline,
          form: _form,
          name: 'tagline',
          optional: true,
          hint: 'e.g. Fresh groceries delivered in Santo Domingo',
          helper: 'One line shown under the name in Discover.',
        ),
      ],
    );
  }
}

/// Tappable preset accent swatches, plus "Default" and any custom colour already stored.
class _AccentPicker extends StatelessWidget {
  const _AccentPicker({required this.value, required this.onPick});
  final String? value;
  final ValueChanged<String?> onPick;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final current = value?.toUpperCase();
    final swatches = [
      ..._palette,
      if (current != null && current.isNotEmpty && !_palette.contains(current)) current,
    ];
    Widget swatch(String? hex) {
      final selected = hex == null ? (current == null || current.isEmpty) : current == hex.toUpperCase();
      final color = _colorFromHex(hex);
      return Tooltip(
        message: hex ?? 'Default colour',
        child: Semantics(
          button: true,
          selected: selected,
          label: hex == null ? 'Default accent colour' : 'Accent $hex',
          child: InkWell(
            onTap: () => onPick(hex),
            customBorder: const CircleBorder(),
            child: Container(
              width: 34,
              height: 34,
              padding: const EdgeInsets.all(3),
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                border: Border.all(color: selected ? scheme.onSurface : scheme.outlineVariant, width: selected ? 2 : 1),
              ),
              child: Container(
                decoration: BoxDecoration(color: color ?? scheme.surfaceContainerHighest, shape: BoxShape.circle),
                child: hex == null ? Icon(Icons.format_color_reset_outlined, size: 14, color: scheme.onSurfaceVariant) : null,
              ),
            ),
          ),
        ),
      );
    }

    return Wrap(spacing: 8, runSpacing: 8, children: [swatch(null), for (final h in swatches) swatch(h)]);
  }
}
