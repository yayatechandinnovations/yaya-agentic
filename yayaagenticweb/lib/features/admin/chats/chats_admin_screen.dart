import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:qr_flutter/qr_flutter.dart';

import '../../../api/admin_api.dart';
import '../../../api/api_config.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/auth_resolver.dart';
import '../../../models/admin/chat_entry_point.dart';
import '../../../models/admin/profile.dart';
import '../auth_resolvers/auth_resolvers_screen.dart';
import '../profiles/profiles_screen.dart';
import '../shared/admin_shared.dart';
import 'web_image_picker.dart';

/// Chat inbound channel admin: create branded entry points (the `LOGO | NAME`
/// tiles end users see in the chat app), publish them, and hand out the QR /
/// deep link. Each entry point pins one profile and an auth mode. See
/// `chat-channel-design.md` §6, §9.

final chatEntryPointsProvider =
    FutureProvider.family<List<ChatEntryPointResponse>, String>((ref, tenantId) async {
  final api = await ref.watch(adminApiProvider.future);
  return api.listChatEntryPoints(tenantId);
});

const _authModes = <String>['ANONYMOUS_ONLY', 'AUTH_OPTIONAL', 'AUTH_REQUIRED'];

/// Supported conversation locales (BCP-47) the chat app ships translations for.
const _localeOptions = <(String, String)>[('en-US', 'English'), ('es-DO', 'Spanish')];

/// Normalize an arbitrary stored locale onto one of the offered options.
String _normalizeLocale(String? raw) =>
    (raw != null && raw.toLowerCase().startsWith('es')) ? 'es-DO' : 'en-US';

Widget _localeDropdown(String value, void Function(String) onChanged) {
  return DropdownButtonFormField<String>(
    initialValue: value,
    decoration: const InputDecoration(labelText: 'Default locale', border: OutlineInputBorder()),
    items: _localeOptions
        .map((o) => DropdownMenuItem(value: o.$1, child: Text('${o.$2} (${o.$1})')))
        .toList(),
    onChanged: (v) => onChanged(v ?? 'en-US'),
  );
}

/// Preset accent palette (tile + chat-header theming).
const _palette = <String>[
  '#7A3CFF', '#D000FF', '#00C2FF', '#0B6E4F', '#2563EB', '#16A34A',
  '#DC2626', '#EA580C', '#CA8A04', '#0891B2', '#DB2777', '#475569',
];

/// Resolve a (possibly root-relative) logo URL against the configured API base
/// so previews load whether the backend returned an absolute or relative URL.
String resolveLogoUrl(WidgetRef ref, String url) {
  if (url.isEmpty || url.startsWith('http')) return url;
  final base = ref.watch(apiConfigProvider).valueOrNull?.baseUrl ?? '';
  return '${base.replaceAll(RegExp(r'/+$'), '')}$url';
}

class ChatsAdminScreen extends ConsumerWidget {
  const ChatsAdminScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('Chats')),
        body: const TenantScopedEmptyState(resourceLabel: 'Chat entry points'),
      );
    }
    return Scaffold(
      body: AdminTwoPane(
        title: 'Chats',
        subtitle: 'Branded, scannable chat surfaces. Each tile pins one profile; '
            'publish it to hand out the QR.',
        list: _List(tenant: tenant),
        form: _CreateForm(tenant: tenant),
      ),
    );
  }
}

Future<void> _runWrite(BuildContext context, WidgetRef ref, String tenant,
    Future<void> Function(AdminApi api) call, String okMsg) async {
  try {
    final api = await ref.read(adminApiProvider.future);
    await call(api);
    ref.invalidate(chatEntryPointsProvider(tenant));
    if (context.mounted) showSnack(context, okMsg);
  } catch (e) {
    if (context.mounted) showSnack(context, formatError(e), error: true);
  }
}

Widget _statusChip(BuildContext context, String status) {
  Color c = switch (status) {
    'ACTIVE' => Colors.green,
    'PAUSED' => Colors.orange,
    'DEACTIVATED' => Theme.of(context).colorScheme.error,
    _ => Colors.blueGrey,
  };
  return Chip(
    label: Text(status, style: const TextStyle(fontSize: 12)),
    backgroundColor: c.withValues(alpha: 0.15),
    side: BorderSide(color: c.withValues(alpha: 0.5)),
    visualDensity: VisualDensity.compact,
  );
}

Color _colorFromHex(String? hex, Color fallback) {
  if (hex == null || hex.isEmpty) return fallback;
  var h = hex.replaceFirst('#', '').trim();
  if (h.length == 6) h = 'FF$h';
  final v = int.tryParse(h, radix: 16);
  return v == null ? fallback : Color(v);
}

// ---------------------------------------------------------------------------
// List pane
// ---------------------------------------------------------------------------

class _List extends ConsumerWidget {
  const _List({required this.tenant});
  final String tenant;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(chatEntryPointsProvider(tenant));
    return async.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => Text(formatError(e)),
      data: (items) {
        if (items.isEmpty) {
          return const Center(child: Text('No chat entry points yet. Create one on the right.'));
        }
        return ListView.separated(
          itemCount: items.length,
          separatorBuilder: (_, __) => const SizedBox(height: 8),
          itemBuilder: (context, i) => _EntryPointCard(tenant: tenant, ep: items[i]),
        );
      },
    );
  }
}

class _EntryPointCard extends ConsumerWidget {
  const _EntryPointCard({required this.tenant, required this.ep});
  final String tenant;
  final ChatEntryPointResponse ep;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return EditableRecordCard(
      leading: _LogoAvatar(
          url: resolveLogoUrl(ref, ep.logoUrl), name: ep.displayName, hex: ep.accentColorHex),
      header: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(ep.displayName, style: const TextStyle(fontWeight: FontWeight.w600)),
                Text('${ep.profileId}  ·  ${ep.authMode}',
                    style: Theme.of(context).textTheme.bodySmall),
              ],
            ),
          ),
          _statusChip(context, ep.status),
        ],
      ),
      onCloneEdit: () => showDialog<void>(
          context: context, builder: (_) => _EditDialog(tenant: tenant, ep: ep)),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          DetailRow(label: 'Handle', value: ep.handle, mono: true),
          DetailRow(label: 'Deep link', value: ep.deepLink, mono: true),
          DetailRow(label: 'Profile', value: '${ep.profileId} @ v${ep.profileVersion}'),
          DetailRow(label: 'Auth mode', value: ep.authMode),
          DetailRow(
              label: 'Verified phone',
              value: ep.requiresVerifiedPhone ? 'Required' : 'Not required'),
          if (ep.authConfig.kind != 'None')
            DetailRow(
                label: 'Identity',
                value: ep.authConfig.kind == 'Oidc'
                    ? 'OIDC · ${ep.authConfig.issuer ?? ''}'
                    : 'Auth resolver #${ep.authConfig.authResolverId ?? ''}'),
          DetailRow(label: 'Locale', value: ep.defaultLocale),
          if ((ep.welcomeText ?? '').isNotEmpty)
            DetailRow(label: 'Welcome', value: ep.welcomeText!),
          DetailRow(
              label: 'Directory',
              value: ep.discoverable
                  ? (ep.isActive ? 'Listed (public)' : 'Listed — live once ACTIVE')
                  : 'Not listed'),
          if ((ep.tagline ?? '').isNotEmpty) DetailRow(label: 'Tagline', value: ep.tagline!),
        ],
      ),
      extraActions: _actions(context, ref),
    );
  }

  List<Widget> _actions(BuildContext context, WidgetRef ref) {
    final actions = <Widget>[
      TextButton.icon(
        onPressed: () => _showQr(context),
        icon: const Icon(Icons.qr_code_2, size: 18),
        label: const Text('QR / link'),
      ),
    ];
    if (ep.isDraft) {
      actions.add(_btn(context, ref, 'Publish', Icons.publish,
          (api) => api.publishChatEntryPoint(tenant, ep.id).then((_) {}), 'Published'));
    }
    if (ep.isActive) {
      actions.add(_btn(context, ref, 'Pause', Icons.pause,
          (api) => api.pauseChatEntryPoint(tenant, ep.id).then((_) {}), 'Paused'));
    }
    if (ep.isPaused) {
      actions.add(_btn(context, ref, 'Resume', Icons.play_arrow,
          (api) => api.resumeChatEntryPoint(tenant, ep.id).then((_) {}), 'Resumed'));
    }
    if (!ep.isDeactivated) {
      actions.add(_btn(context, ref, 'Rotate handle', Icons.autorenew,
          (api) => api.rotateChatEntryPointHandle(tenant, ep.id).then((_) {}),
          'Handle rotated — the old QR no longer works'));
      actions.add(_btn(context, ref, 'Deactivate', Icons.delete_outline,
          (api) => api.deactivateChatEntryPoint(tenant, ep.id).then((_) {}), 'Deactivated',
          danger: true));
    }
    return actions;
  }

  Widget _btn(BuildContext context, WidgetRef ref, String label, IconData icon,
      Future<void> Function(AdminApi) call, String okMsg, {bool danger = false}) {
    return TextButton.icon(
      onPressed: () => _runWrite(context, ref, tenant, call, okMsg),
      icon: Icon(icon, size: 18),
      label: Text(label),
      style: danger
          ? TextButton.styleFrom(foregroundColor: Theme.of(context).colorScheme.error)
          : null,
    );
  }

  void _showQr(BuildContext context) {
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(ep.displayName),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              color: Colors.white,
              padding: const EdgeInsets.all(12),
              child: QrImageView(data: ep.deepLink, size: 220),
            ),
            const SizedBox(height: 12),
            SelectableText(ep.deepLink, style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
          ],
        ),
        actions: [
          TextButton.icon(
            onPressed: () {
              Clipboard.setData(ClipboardData(text: ep.deepLink));
              showSnack(ctx, 'Deep link copied');
            },
            icon: const Icon(Icons.copy, size: 18),
            label: const Text('Copy link'),
          ),
          FilledButton(onPressed: () => Navigator.pop(ctx), child: const Text('Done')),
        ],
      ),
    );
  }
}

class _LogoAvatar extends StatelessWidget {
  const _LogoAvatar({required this.url, required this.name, this.hex});
  final String url;
  final String name;
  final String? hex;

  @override
  Widget build(BuildContext context) {
    final initials = name.isEmpty ? '?' : name.trim()[0].toUpperCase();
    return CircleAvatar(
      backgroundColor: _colorFromHex(hex, Theme.of(context).colorScheme.primaryContainer),
      child: ClipOval(
        child: url.isEmpty
            ? Text(initials)
            : Image.network(url, width: 40, height: 40, fit: BoxFit.cover,
                errorBuilder: (_, __, ___) => Text(initials)),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Shared form controls
// ---------------------------------------------------------------------------

/// Tappable preset accent swatches (no extra dependency).
class _ColorPalette extends StatelessWidget {
  const _ColorPalette({required this.selectedHex, required this.onPick});
  final String? selectedHex;
  final void Function(String hex) onPick;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(bottom: 6),
          child: Text('Accent color',
              style: Theme.of(context).textTheme.labelMedium),
        ),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: _palette.map((hex) {
            final selected = selectedHex?.toUpperCase() == hex.toUpperCase();
            return InkWell(
              onTap: () => onPick(hex),
              customBorder: const CircleBorder(),
              child: Container(
                width: 30,
                height: 30,
                decoration: BoxDecoration(
                  color: _colorFromHex(hex, Colors.grey),
                  shape: BoxShape.circle,
                  border: Border.all(
                    color: selected ? Theme.of(context).colorScheme.onSurface : Colors.transparent,
                    width: 2,
                  ),
                ),
                child: selected
                    ? const Icon(Icons.check, size: 16, color: Colors.white)
                    : null,
              ),
            );
          }).toList(),
        ),
      ],
    );
  }
}

/// Pick an image from disk, upload it, and surface the hosted URL + a preview.
class _LogoPicker extends ConsumerStatefulWidget {
  const _LogoPicker({required this.tenant, required this.url, required this.onUploaded});
  final String tenant;
  final String? url;
  final void Function(String url) onUploaded;

  @override
  ConsumerState<_LogoPicker> createState() => _LogoPickerState();
}

class _LogoPickerState extends ConsumerState<_LogoPicker> {
  bool _busy = false;

  static const _allowed = {'image/png', 'image/jpeg', 'image/webp', 'image/gif'};
  static const _maxBytes = 2 * 1024 * 1024; // keep in sync with ChatLogoService

  Future<void> _pick() async {
    final picked = await pickImageFromBrowser();
    if (picked == null) return;
    if (!_allowed.contains(picked.contentType)) {
      if (mounted) showSnack(context, 'Pick a PNG, JPEG, WebP, or GIF image.', error: true);
      return;
    }
    if (picked.bytes.length > _maxBytes) {
      if (mounted) showSnack(context, 'Logo must be under 2 MB. Try a smaller image.', error: true);
      return;
    }
    setState(() => _busy = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      final url = await api.uploadChatLogo(
          widget.tenant, picked.contentType, base64Encode(picked.bytes));
      widget.onUploaded(url);
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final preview = (widget.url ?? '').isEmpty ? null : resolveLogoUrl(ref, widget.url!);
    return Row(
      children: [
        Container(
          width: 56,
          height: 56,
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.surfaceContainerHighest,
            borderRadius: BorderRadius.circular(8),
          ),
          clipBehavior: Clip.antiAlias,
          child: preview == null
              ? const Icon(Icons.image_outlined)
              : Image.network(preview, fit: BoxFit.cover,
                  errorBuilder: (_, __, ___) => const Icon(Icons.broken_image_outlined)),
        ),
        const SizedBox(width: 12),
        OutlinedButton.icon(
          onPressed: _busy ? null : _pick,
          icon: _busy
              ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
              : const Icon(Icons.upload, size: 18),
          label: Text((widget.url ?? '').isEmpty ? 'Upload logo' : 'Replace logo'),
        ),
      ],
    );
  }
}

/// Auth mode + identity method (OIDC issuer or an auth resolver) — mirrors the
/// channel-binding "Identity" selection.
class _AuthSection extends ConsumerWidget {
  const _AuthSection({
    required this.tenant,
    required this.authMode,
    required this.authMethod,
    required this.issuer,
    required this.audience,
    required this.authResolverId,
    required this.senderKey,
    required this.onMode,
    required this.onMethod,
    required this.onResolver,
  });

  final String tenant;
  final String authMode;
  final String authMethod;
  final TextEditingController issuer;
  final TextEditingController audience;
  final int? authResolverId;
  // The sender key is minted by yaya-agentic, never typed. Null until the entry
  // point has been saved once (create form); shown read-only afterwards so the
  // operator can copy it into the tenant's resolver config.
  final String? senderKey;
  final void Function(String) onMode;
  final void Function(String) onMethod;
  final void Function(int?) onResolver;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final needsIdentity = authMode != 'ANONYMOUS_ONLY';
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        DropdownButtonFormField<String>(
          initialValue: authMode,
          decoration: const InputDecoration(labelText: 'Auth mode', border: OutlineInputBorder()),
          items: _authModes.map((m) => DropdownMenuItem(value: m, child: Text(m))).toList(),
          onChanged: (v) => onMode(v ?? 'ANONYMOUS_ONLY'),
        ),
        if (needsIdentity) ...[
          const SizedBox(height: 10),
          DropdownButtonFormField<String>(
            initialValue: authMethod,
            decoration: const InputDecoration(labelText: 'Identity', border: OutlineInputBorder()),
            items: const [
              DropdownMenuItem(value: 'OIDC', child: Text('OIDC (user signs in)')),
              DropdownMenuItem(value: 'RESOLVER', child: Text('Auth resolver')),
            ],
            onChanged: (v) => onMethod(v ?? 'OIDC'),
          ),
          const SizedBox(height: 10),
          if (authMethod == 'OIDC') ...[
            _plainField(issuer, 'OIDC issuer', 'https://idp.tenant.com'),
            const SizedBox(height: 10),
            _plainField(audience, 'OIDC audience (optional)', null),
          ] else ...[
            _ResolverDropdown(
                tenant: tenant, selected: authResolverId, onChanged: onResolver),
            const SizedBox(height: 10),
            _SenderKeyField(value: senderKey),
          ],
        ],
      ],
    );
  }

  static Widget _plainField(TextEditingController c, String label, String? hint) => TextField(
        controller: c,
        decoration: InputDecoration(
            labelText: label, hintText: hint, border: const OutlineInputBorder(), isDense: true),
      );
}

/// Read-only display of the yaya-minted sender key. Before the first save there
/// is no code yet, so we tell the operator it will be generated; afterwards we
/// show it with a copy affordance to paste into the tenant's resolver config.
class _SenderKeyField extends StatelessWidget {
  const _SenderKeyField({required this.value});
  final String? value;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    if (value == null || value!.isEmpty) {
      return InputDecorator(
        decoration: const InputDecoration(labelText: 'Sender key', border: OutlineInputBorder()),
        child: Text('Generated automatically when you save. Register it on the tenant resolver.',
            style: theme.textTheme.bodySmall),
      );
    }
    return InputDecorator(
      decoration: InputDecoration(
        labelText: 'Sender key',
        border: const OutlineInputBorder(),
        helperText: 'Register this code on the tenant resolver.',
        suffixIcon: IconButton(
          icon: const Icon(Icons.copy, size: 18),
          tooltip: 'Copy',
          onPressed: () {
            Clipboard.setData(ClipboardData(text: value!));
            ScaffoldMessenger.of(context)
                .showSnackBar(const SnackBar(content: Text('Sender key copied')));
          },
        ),
      ),
      child: SelectableText(value!,
          style: theme.textTheme.titleMedium?.copyWith(letterSpacing: 1.5)),
    );
  }
}

class _ResolverDropdown extends ConsumerWidget {
  const _ResolverDropdown({required this.tenant, required this.selected, required this.onChanged});
  final String tenant;
  final int? selected;
  final void Function(int?) onChanged;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(authResolversProvider(tenant));
    return async.when(
      loading: () => const LinearProgressIndicator(),
      error: (e, _) => Text(formatError(e), style: TextStyle(color: Theme.of(context).colorScheme.error)),
      data: (resp) {
        final active = resp.resolvers.where((r) => r.status == 'ACTIVE').toList();
        if (active.isEmpty) {
          return InputDecorator(
            decoration: const InputDecoration(labelText: 'Auth resolver', border: OutlineInputBorder()),
            child: Text('No auth resolvers — create one in Auth resolvers first.',
                style: Theme.of(context).textTheme.bodySmall),
          );
        }
        return DropdownButtonFormField<int>(
          initialValue: active.any((r) => r.id == selected) ? selected : null,
          isExpanded: true,
          decoration: const InputDecoration(labelText: 'Auth resolver', border: OutlineInputBorder()),
          items: active
              .map((AuthResolverResponse r) => DropdownMenuItem(
                    value: r.id,
                    child: Text('${r.name} · ${r.kind}', overflow: TextOverflow.ellipsis),
                  ))
              .toList(),
          onChanged: onChanged,
        );
      },
    );
  }
}

// ---------------------------------------------------------------------------
// Create form
// ---------------------------------------------------------------------------

class _CreateForm extends ConsumerStatefulWidget {
  const _CreateForm({required this.tenant});
  final String tenant;

  @override
  ConsumerState<_CreateForm> createState() => _CreateFormState();
}

class _CreateFormState extends ConsumerState<_CreateForm> {
  final _displayName = TextEditingController();
  final _welcome = TextEditingController();
  final _tagline = TextEditingController();
  final _issuer = TextEditingController();
  final _audience = TextEditingController();

  String? _profileSlug;
  String? _logoUrl;
  String? _accentHex;
  String _locale = 'en-US';
  String _authMode = 'ANONYMOUS_ONLY';
  String _authMethod = 'OIDC';
  int? _authResolverId;
  bool _requireVerifiedPhone = false;
  bool _discoverable = false;
  bool _busy = false;

  @override
  void dispose() {
    for (final c in [_displayName, _welcome, _tagline, _issuer, _audience]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _submit() async {
    if (_profileSlug == null) {
      showSnack(context, 'Select a profile.', error: true);
      return;
    }
    if (_displayName.text.trim().isEmpty || (_logoUrl ?? '').isEmpty) {
      showSnack(context, 'Display name and a logo are required.', error: true);
      return;
    }
    final parts = _profileSlug!.split('@');
    setState(() => _busy = true);
    final body = ChatEntryPointRequest(
      profileId: parts[0],
      profileVersion: parts.length > 1 ? int.tryParse(parts[1]) : null,
      displayName: _displayName.text.trim(),
      logoUrl: _logoUrl!,
      accentColorHex: _accentHex,
      welcomeText: _welcome.text.trim().isEmpty ? null : _welcome.text.trim(),
      defaultLocale: _locale,
      tagline: _tagline.text.trim().isEmpty ? null : _tagline.text.trim(),
      discoverable: _discoverable,
      authMode: _authMode,
      authConfig: _buildAuthConfig(),
      requiresVerifiedPhone: _requireVerifiedPhone,
    );
    await _runWrite(context, ref, widget.tenant,
        (api) => api.createChatEntryPoint(widget.tenant, body).then((_) {}),
        'Chat entry point created as draft — publish it to go live.');
    if (mounted) {
      setState(() => _busy = false);
      _clear();
    }
  }

  ChatAuthConfig? _buildAuthConfig() {
    if (_authMode == 'ANONYMOUS_ONLY') return null;
    if (_authMethod == 'OIDC' && _issuer.text.trim().isNotEmpty) {
      return ChatAuthConfig(
        kind: 'Oidc',
        issuer: _issuer.text.trim(),
        audience: _audience.text.trim().isEmpty ? null : _audience.text.trim(),
      );
    }
    if (_authMethod == 'RESOLVER' && _authResolverId != null) {
      // No senderKey on create — yaya-agentic mints it server-side.
      return ChatAuthConfig(kind: 'ResolverExchange', authResolverId: _authResolverId);
    }
    return null;
  }

  void _clear() {
    _displayName.clear();
    _welcome.clear();
    _tagline.clear();
    _issuer.clear();
    _audience.clear();
    setState(() {
      _profileSlug = null;
      _logoUrl = null;
      _accentHex = null;
      _locale = 'en-US';
      _authMode = 'ANONYMOUS_ONLY';
      _authMethod = 'OIDC';
      _authResolverId = null;
      _requireVerifiedPhone = false;
      _discoverable = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    final profiles = ref.watch(profilesProvider);
    return SingleChildScrollView(
      child: FormCard(
        title: 'New chat entry point',
        actions: [
          FilledButton.icon(
            onPressed: _busy ? null : _submit,
            icon: _busy
                ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.add),
            label: const Text('Create'),
          ),
        ],
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            profiles.when(
              loading: () => const LinearProgressIndicator(),
              error: (e, _) => Text(formatError(e)),
              data: (list) => DropdownButtonFormField<String>(
                initialValue: _profileSlug,
                isExpanded: true,
                decoration: const InputDecoration(labelText: 'Profile', border: OutlineInputBorder()),
                items: list
                    .map((ProfileResponse p) => DropdownMenuItem(
                          value: '${p.id}@${p.version}',
                          child: Text('${p.displayName} (${p.id}@${p.version})',
                              overflow: TextOverflow.ellipsis),
                        ))
                    .toList(),
                onChanged: (v) => setState(() => _profileSlug = v),
              ),
            ),
            const SizedBox(height: 12),
            _field(_displayName, 'Display name', hint: 'e.g. Buy Yaya Market'),
            _LogoPicker(
                tenant: widget.tenant,
                url: _logoUrl,
                onUploaded: (u) => setState(() => _logoUrl = u)),
            const SizedBox(height: 12),
            _ColorPalette(selectedHex: _accentHex, onPick: (h) => setState(() => _accentHex = h)),
            const SizedBox(height: 12),
            _field(_welcome, 'Welcome text', hint: 'Optional first bubble', lines: 2),
            _field(_tagline, 'Directory tagline',
                hint: 'One line shown when this chat is listed publicly'),
            _localeDropdown(_locale, (v) => setState(() => _locale = v)),
            const SizedBox(height: 12),
            _AuthSection(
              tenant: widget.tenant,
              authMode: _authMode,
              authMethod: _authMethod,
              issuer: _issuer,
              audience: _audience,
              authResolverId: _authResolverId,
              senderKey: null, // minted on save; nothing to show on a fresh draft
              onMode: (v) => setState(() => _authMode = v),
              onMethod: (v) => setState(() => _authMethod = v),
              onResolver: (v) => setState(() => _authResolverId = v),
            ),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Require verified phone'),
              subtitle: const Text('Only phone-verified devices can start this chat'),
              value: _requireVerifiedPhone,
              onChanged: (v) => setState(() => _requireVerifiedPhone = v),
            ),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('List in public directory'),
              subtitle: const Text('Let end users find this chat by searching Discover. '
                  'Only takes effect once the chat is published (ACTIVE).'),
              value: _discoverable,
              onChanged: (v) => setState(() => _discoverable = v),
            ),
          ],
        ),
      ),
    );
  }

  Widget _field(TextEditingController c, String label, {String? hint, int lines = 1}) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: TextField(
        controller: c,
        minLines: lines,
        maxLines: lines,
        decoration: InputDecoration(
          labelText: label,
          hintText: hint,
          border: const OutlineInputBorder(),
          isDense: true,
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Edit dialog (rebrand / change auth) — profile is fixed at creation.
// ---------------------------------------------------------------------------

class _EditDialog extends ConsumerStatefulWidget {
  const _EditDialog({required this.tenant, required this.ep});
  final String tenant;
  final ChatEntryPointResponse ep;

  @override
  ConsumerState<_EditDialog> createState() => _EditDialogState();
}

class _EditDialogState extends ConsumerState<_EditDialog> {
  late final _displayName = TextEditingController(text: widget.ep.displayName);
  late final _welcome = TextEditingController(text: widget.ep.welcomeText ?? '');
  late final _tagline = TextEditingController(text: widget.ep.tagline ?? '');
  late final _issuer = TextEditingController(text: widget.ep.authConfig.issuer ?? '');
  late final _audience = TextEditingController(text: widget.ep.authConfig.audience ?? '');
  late String _logoUrl = widget.ep.logoUrl;
  late String? _accentHex = widget.ep.accentColorHex;
  late String _localeValue = _normalizeLocale(widget.ep.defaultLocale);
  late String _authMode = widget.ep.authMode;
  late String _authMethod = widget.ep.authConfig.kind == 'ResolverExchange' ? 'RESOLVER' : 'OIDC';
  late int? _authResolverId = widget.ep.authConfig.authResolverId;
  late bool _discoverable = widget.ep.discoverable;

  @override
  void dispose() {
    for (final c in [_displayName, _welcome, _tagline, _issuer, _audience]) {
      c.dispose();
    }
    super.dispose();
  }

  ChatAuthConfig? _buildAuthConfig() {
    if (_authMode == 'ANONYMOUS_ONLY') return ChatAuthConfig(kind: 'None');
    if (_authMethod == 'OIDC' && _issuer.text.trim().isNotEmpty) {
      return ChatAuthConfig(
          kind: 'Oidc',
          issuer: _issuer.text.trim(),
          audience: _audience.text.trim().isEmpty ? null : _audience.text.trim());
    }
    if (_authMethod == 'RESOLVER' && _authResolverId != null) {
      // Carry the minted key back so the round-trip preserves it; the backend
      // is the authority and keeps the stored code regardless.
      return ChatAuthConfig(
          kind: 'ResolverExchange',
          authResolverId: _authResolverId,
          senderKey: widget.ep.authConfig.senderKey);
    }
    return ChatAuthConfig(kind: 'None');
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text('Edit ${widget.ep.displayName}'),
      content: SizedBox(
        width: 420,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _row(_displayName, 'Display name'),
              _LogoPicker(
                  tenant: widget.tenant,
                  url: _logoUrl,
                  onUploaded: (u) => setState(() => _logoUrl = u)),
              const SizedBox(height: 12),
              _ColorPalette(selectedHex: _accentHex, onPick: (h) => setState(() => _accentHex = h)),
              const SizedBox(height: 12),
              _row(_welcome, 'Welcome text'),
              _row(_tagline, 'Directory tagline'),
              _localeDropdown(_localeValue, (v) => setState(() => _localeValue = v)),
              const SizedBox(height: 12),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('List in public directory'),
                subtitle: const Text('Discoverable in Discover once ACTIVE'),
                value: _discoverable,
                onChanged: (v) => setState(() => _discoverable = v),
              ),
              _AuthSection(
                tenant: widget.tenant,
                authMode: _authMode,
                authMethod: _authMethod,
                issuer: _issuer,
                audience: _audience,
                authResolverId: _authResolverId,
                senderKey: widget.ep.authConfig.senderKey,
                onMode: (v) => setState(() => _authMode = v),
                onMethod: (v) => setState(() => _authMethod = v),
                onResolver: (v) => setState(() => _authResolverId = v),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(
          onPressed: () async {
            final body = ChatEntryPointRequest(
              profileId: widget.ep.profileId,
              displayName: _displayName.text.trim(),
              logoUrl: _logoUrl,
              accentColorHex: _accentHex,
              welcomeText: _welcome.text.trim(),
              defaultLocale: _localeValue,
              tagline: _tagline.text.trim(),
              discoverable: _discoverable,
              authMode: _authMode,
              authConfig: _buildAuthConfig(),
            );
            final nav = Navigator.of(context);
            await _runWrite(context, ref, widget.tenant,
                (api) => api.updateChatEntryPoint(widget.tenant, widget.ep.id, body).then((_) {}),
                'Updated');
            if (nav.mounted) nav.pop();
          },
          child: const Text('Save'),
        ),
      ],
    );
  }

  Widget _row(TextEditingController c, String label) => Padding(
        padding: const EdgeInsets.only(bottom: 10),
        child: TextField(
          controller: c,
          decoration: InputDecoration(labelText: label, border: const OutlineInputBorder(), isDense: true),
        ),
      );
}
