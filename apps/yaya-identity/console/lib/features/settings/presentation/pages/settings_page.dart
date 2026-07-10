import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../core/widgets/org_scope_gate.dart';
import '../../../../core/widgets/org_scope_picker.dart';
import '../bloc/settings_bloc.dart';

/// Settings — per-org MFA policy (scoped by the console-wide org picker) and
/// the platform signing-key rotation.
class SettingsPage extends StatelessWidget {
  const SettingsPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Settings'), actions: const [OrgScopePicker()]),
      body: OrgScopeGate(
        onOrg: (context, orgId) =>
            context.read<SettingsBloc>().add(SettingsRequested(orgId)),
        builder: (context, orgId) => BlocConsumer<SettingsBloc, SettingsState>(
          listenWhen: (p, c) => c.message != null && p.message != c.message,
          listener: (context, state) => ScaffoldMessenger.of(context)
              .showSnackBar(SnackBar(content: Text(state.message!))),
          builder: (context, state) => ListView(
            padding: const EdgeInsets.all(24),
            children: [
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text('Organization MFA policy',
                          style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
                      const SizedBox(height: 12),
                      if (state.status == SettingsStatus.loading) const LinearProgressIndicator(),
                      if (state.status == SettingsStatus.failure)
                        Semantics(
                          liveRegion: true,
                          child: Text(state.error ?? 'Failed',
                              style: const TextStyle(color: Colors.red)),
                        ),
                      if (state.settings != null)
                        SwitchListTile(
                          title: Text('Require MFA — ${state.settings!.name}'),
                          subtitle:
                              const Text('Privileged roles (owner/admin) must enrol TOTP'),
                          value: state.settings!.mfaRequired,
                          onChanged: (v) => context
                              .read<SettingsBloc>()
                              .add(MfaToggled(state.settings!.org, v)),
                        ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 16),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text('Signing keys',
                          style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
                      const SizedBox(height: 8),
                      const Text(
                        'Rotation is make-before-break: the previous key stays in JWKS until its '
                        'tokens expire, so verification never gaps.',
                        style: TextStyle(color: Colors.black54),
                      ),
                      const SizedBox(height: 12),
                      FilledButton.icon(
                        onPressed: () => _confirmRotate(context),
                        icon: const Icon(Icons.autorenew),
                        label: const Text('Rotate signing keys'),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _confirmRotate(BuildContext context) async {
    final bloc = context.read<SettingsBloc>();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Rotate signing keys?'),
        content: const Text(
            'A new active key is minted; the previous one moves to RETIRING and stays in JWKS '
            'until its tokens expire. Proceed?'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Rotate')),
        ],
      ),
    );
    if (ok == true) bloc.add(const KeysRotateRequested());
  }
}
