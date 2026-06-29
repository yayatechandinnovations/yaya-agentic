import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:qr_flutter/qr_flutter.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/chat_entry_point.dart';
import '../shared/admin_shared.dart';

/// Chat inbound channel admin: create branded entry points (the `LOGO | NAME`
/// tiles end users see in the chat app), publish them, and hand out the QR /
/// deep link. Each entry point pins one profile and an auth mode. See
/// `chat-channel-design.md` §6, §12.

final chatEntryPointsProvider =
    FutureProvider.family<List<ChatEntryPointResponse>, String>((ref, tenantId) async {
  final api = await ref.watch(adminApiProvider.future);
  return api.listChatEntryPoints(tenantId);
});

const _authModes = <String>['ANONYMOUS_ONLY', 'AUTH_OPTIONAL', 'AUTH_REQUIRED'];

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
          separatorBuilder: (_, _) => const SizedBox(height: 8),
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
      leading: _Logo(url: ep.logoUrl, name: ep.displayName, hex: ep.accentColorHex),
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
      onCloneEdit: () => _editDialog(context, ref),
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
            DetailRow(label: 'Auth config', value: ep.authConfig.kind +
                (ep.authConfig.issuer != null ? ' · ${ep.authConfig.issuer}' : '')),
          DetailRow(label: 'Locale', value: ep.defaultLocale),
          if ((ep.welcomeText ?? '').isNotEmpty)
            DetailRow(label: 'Welcome', value: ep.welcomeText!),
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

  void _editDialog(BuildContext context, WidgetRef ref) {
    showDialog<void>(
      context: context,
      builder: (_) => _EditDialog(tenant: tenant, ep: ep),
    );
  }
}

class _Logo extends StatelessWidget {
  const _Logo({required this.url, required this.name, this.hex});
  final String url;
  final String name;
  final String? hex;

  @override
  Widget build(BuildContext context) {
    final initials = name.isEmpty ? '?' : name.trim()[0].toUpperCase();
    return CircleAvatar(
      backgroundColor: Theme.of(context).colorScheme.primaryContainer,
      child: ClipOval(
        child: Image.network(url, width: 40, height: 40, fit: BoxFit.cover,
            errorBuilder: (_, _, _) => Text(initials)),
      ),
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
  final _profileId = TextEditingController();
  final _displayName = TextEditingController();
  final _logoUrl = TextEditingController();
  final _accent = TextEditingController();
  final _welcome = TextEditingController();
  final _locale = TextEditingController(text: 'en-US');
  final _issuer = TextEditingController();
  final _audience = TextEditingController();
  String _authMode = 'ANONYMOUS_ONLY';
  bool _requireVerifiedPhone = false;
  bool _busy = false;

  @override
  void dispose() {
    for (final c in [_profileId, _displayName, _logoUrl, _accent, _welcome, _locale, _issuer, _audience]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _submit() async {
    if (_profileId.text.trim().isEmpty || _displayName.text.trim().isEmpty || _logoUrl.text.trim().isEmpty) {
      showSnack(context, 'Profile, display name, and logo URL are required.', error: true);
      return;
    }
    setState(() => _busy = true);
    final body = ChatEntryPointRequest(
      profileId: _profileId.text.trim(),
      displayName: _displayName.text.trim(),
      logoUrl: _logoUrl.text.trim(),
      accentColorHex: _accent.text.trim().isEmpty ? null : _accent.text.trim(),
      welcomeText: _welcome.text.trim().isEmpty ? null : _welcome.text.trim(),
      defaultLocale: _locale.text.trim(),
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
    if (_authMode != 'ANONYMOUS_ONLY' && _issuer.text.trim().isNotEmpty) {
      return ChatAuthConfig(
        kind: 'Oidc',
        issuer: _issuer.text.trim(),
        audience: _audience.text.trim().isEmpty ? null : _audience.text.trim(),
      );
    }
    return null;
  }

  void _clear() {
    _profileId.clear();
    _displayName.clear();
    _logoUrl.clear();
    _accent.clear();
    _welcome.clear();
    _issuer.clear();
    _audience.clear();
    setState(() {
      _authMode = 'ANONYMOUS_ONLY';
      _requireVerifiedPhone = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    final showOidc = _authMode != 'ANONYMOUS_ONLY';
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
          children: [
            _field(_profileId, 'Profile id', hint: 'e.g. market_customer_sales'),
            _field(_displayName, 'Display name', hint: 'e.g. Buy Yaya Market'),
            _field(_logoUrl, 'Logo URL', hint: 'https://…'),
            _field(_accent, 'Accent color', hint: '#0B6E4F (optional)'),
            _field(_welcome, 'Welcome text', hint: 'Optional first bubble', lines: 2),
            _field(_locale, 'Default locale', hint: 'en-US'),
            const SizedBox(height: 8),
            DropdownButtonFormField<String>(
              initialValue: _authMode,
              decoration: const InputDecoration(labelText: 'Auth mode', border: OutlineInputBorder()),
              items: _authModes
                  .map((m) => DropdownMenuItem(value: m, child: Text(m)))
                  .toList(),
              onChanged: (v) => setState(() => _authMode = v ?? 'ANONYMOUS_ONLY'),
            ),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Require verified phone'),
              subtitle: const Text('Only phone-verified devices can start this chat'),
              value: _requireVerifiedPhone,
              onChanged: (v) => setState(() => _requireVerifiedPhone = v),
            ),
            if (showOidc) ...[
              const SizedBox(height: 8),
              _field(_issuer, 'OIDC issuer', hint: 'https://idp.tenant.com (optional)'),
              _field(_audience, 'OIDC audience', hint: 'Optional'),
            ],
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
  late final _logoUrl = TextEditingController(text: widget.ep.logoUrl);
  late final _accent = TextEditingController(text: widget.ep.accentColorHex ?? '');
  late final _welcome = TextEditingController(text: widget.ep.welcomeText ?? '');
  late String _authMode = widget.ep.authMode;

  @override
  void dispose() {
    for (final c in [_displayName, _logoUrl, _accent, _welcome]) {
      c.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text('Edit ${widget.ep.displayName}'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            _row(_displayName, 'Display name'),
            _row(_logoUrl, 'Logo URL'),
            _row(_accent, 'Accent color (#RRGGBB)'),
            _row(_welcome, 'Welcome text'),
            const SizedBox(height: 8),
            DropdownButtonFormField<String>(
              initialValue: _authMode,
              decoration: const InputDecoration(labelText: 'Auth mode', border: OutlineInputBorder()),
              items: _authModes.map((m) => DropdownMenuItem(value: m, child: Text(m))).toList(),
              onChanged: (v) => setState(() => _authMode = v ?? widget.ep.authMode),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(
          onPressed: () async {
            final body = ChatEntryPointRequest(
              profileId: widget.ep.profileId,
              displayName: _displayName.text.trim(),
              logoUrl: _logoUrl.text.trim(),
              accentColorHex: _accent.text.trim(),
              welcomeText: _welcome.text.trim(),
              defaultLocale: widget.ep.defaultLocale,
              authMode: _authMode,
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
