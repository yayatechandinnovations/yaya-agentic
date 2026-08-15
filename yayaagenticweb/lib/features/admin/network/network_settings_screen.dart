import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/network_settings.dart';
import '../shared/admin_shared.dart';

/// Settings → Network chat (Corillo). One screen turns `player_network_v1` on
/// and toggles play/claim at runtime — the master switch seeds the profile and
/// auto-provisions the chat front door; play/claim take effect on the next turn
/// with no restart.
class NetworkSettingsScreen extends ConsumerStatefulWidget {
  const NetworkSettingsScreen({super.key});

  @override
  ConsumerState<NetworkSettingsScreen> createState() => _NetworkSettingsScreenState();
}

class _NetworkSettingsScreenState extends ConsumerState<NetworkSettingsScreen> {
  final _hubCtrl = TextEditingController();
  final _credCtrl = TextEditingController();

  String? _tenant;
  bool _loading = true;
  bool _saving = false;
  String? _error;
  NetworkSettings? _data;
  bool _enabled = false;
  bool _play = false;
  bool _claim = false;

  @override
  void dispose() {
    _hubCtrl.dispose();
    _credCtrl.dispose();
    super.dispose();
  }

  Future<void> _load(String tenant) async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final api = await ref.read(adminApiProvider.future);
      final data = await api.getNetworkSettings(tenant);
      if (!mounted) return;
      setState(() {
        _data = data;
        _enabled = data.enabled;
        _play = data.playEnabled;
        _claim = data.claimEnabled;
        _hubCtrl.text = data.hubBaseUrl ?? '';
        _credCtrl.text = data.hubCredentialRef ?? '';
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = formatError(e);
      });
    }
  }

  Future<void> _save(String tenant) async {
    setState(() => _saving = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      final body = NetworkSettings(
        enabled: _enabled,
        playEnabled: _play,
        claimEnabled: _claim,
        hubBaseUrl: _hubCtrl.text.trim(),
        hubCredentialRef: _credCtrl.text.trim(),
      );
      final updated = await api.updateNetworkSettings(tenant, body);
      if (!mounted) return;
      setState(() {
        _data = updated;
        _saving = false;
      });
      showSnack(context, 'Network settings saved.');
    } catch (e) {
      if (!mounted) return;
      setState(() => _saving = false);
      showSnack(context, formatError(e), error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return const TenantScopedEmptyState(resourceLabel: 'Network settings');
    }
    if (_tenant != tenant) {
      _tenant = tenant;
      WidgetsBinding.instance.addPostFrameCallback((_) => _load(tenant));
    }

    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        Text('Network chat (Corillo)',
            style: Theme.of(context).textTheme.headlineSmall),
        const SizedBox(height: 4),
        Text(
          'Enable the player_network_v1 assistant and toggle play/claim. '
          'Enabling seeds the profile and provisions the chat front door; '
          'play/claim take effect immediately (no restart).',
          style: Theme.of(context).textTheme.bodyMedium,
        ),
        const SizedBox(height: 16),
        if (_loading)
          const Padding(
            padding: EdgeInsets.all(32),
            child: Center(child: CircularProgressIndicator()),
          )
        else if (_error != null)
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Text(_error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error)),
            ),
          )
        else
          _form(context, tenant),
      ],
    );
  }

  Widget _form(BuildContext context, String tenant) {
    return FormCard(
      title: 'Feature toggles',
      actions: [
        FilledButton.icon(
          onPressed: _saving ? null : () => _save(tenant),
          icon: _saving
              ? const SizedBox(
                  width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
              : const Icon(Icons.save_outlined),
          label: const Text('Save'),
        ),
      ],
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SwitchListTile(
            title: const Text('Enabled'),
            subtitle: const Text('Master switch — seeds the profile and the chat front door.'),
            value: _enabled,
            onChanged: (v) => setState(() => _enabled = v),
          ),
          SwitchListTile(
            title: const Text('Play'),
            subtitle: const Text('Expose quote/confirm/link tools (live).'),
            value: _play,
            onChanged: _enabled ? (v) => setState(() => _play = v) : null,
          ),
          SwitchListTile(
            title: const Text('Claim'),
            subtitle: const Text('Expose the wallet prize-claim tool (live).'),
            value: _claim,
            onChanged: _enabled ? (v) => setState(() => _claim = v) : null,
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _hubCtrl,
            decoration: const InputDecoration(
              labelText: 'Hub base URL',
              hintText: 'https://hub.smartlottery.example',
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _credCtrl,
            decoration: const InputDecoration(
              labelText: 'Credential ref',
              hintText: 'Env var name holding the X-Agent-Auth secret',
            ),
          ),
          if (_data?.entryPointHandle != null) ...[
            const SizedBox(height: 16),
            const Divider(),
            const SizedBox(height: 8),
            Text('Chat front door',
                style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: 4),
            Row(children: [
              Icon(
                _data!.entryPointActive ? Icons.check_circle_outline : Icons.pause_circle_outline,
                size: 18,
                color: _data!.entryPointActive
                    ? Theme.of(context).colorScheme.primary
                    : Theme.of(context).disabledColor,
              ),
              const SizedBox(width: 6),
              Text(_data!.entryPointActive ? 'Active' : 'Paused'),
            ]),
            const SizedBox(height: 4),
            SelectableText(_data!.chatUrl ?? 'handle: ${_data!.entryPointHandle}'),
          ],
        ],
      ),
    );
  }
}
