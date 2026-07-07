import 'dart:async';
// Web-only admin app: dart:html is the house pattern for popups/downloads.
// ignore: avoid_web_libraries_in_flutter
import 'dart:html' as html;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/psp_account.dart';
import '../shared/admin_shared.dart';

/// Status for one (tenant, provider), refetched after connect/disconnect/poll.
final pspStatusProvider =
    FutureProvider.family<PspAccountView, ({String tenant, String provider})>(
  (ref, key) async {
    final api = await ref.watch(adminApiProvider.future);
    return api.pspStatus(key.tenant, key.provider);
  },
);

/// Payments settings — connect the workspace's PayPal/Stripe merchant account
/// with one click (no keys, no dashboards). The connection is per workspace and
/// is reused by every store (profile) that routes checkout to that provider.
/// See merchant-psp-onboarding-design.md §7.
class PaymentsScreen extends ConsumerWidget {
  const PaymentsScreen({super.key});

  // Providers that support self-serve onboarding (dLocal is provisioned by ops).
  static const _providers = [
    ('paypal', 'PayPal', 'LatAm / Dominican Republic and most markets'),
    ('stripe', 'Stripe', 'US / EU and supported markets'),
  ];

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return const TenantScopedEmptyState(resourceLabel: 'Payments');
    }
    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        Text('Payments', style: Theme.of(context).textTheme.headlineSmall),
        const SizedBox(height: 4),
        Text(
          'Connect a payment provider so your stores can take real payments. '
          'You sign in on the provider\'s own page — no API keys to copy. '
          'One connection is shared by every store in this workspace.',
          style: Theme.of(context).textTheme.bodyMedium,
        ),
        const SizedBox(height: 20),
        for (final p in _providers)
          _ProviderCard(tenant: tenant, provider: p.$1, label: p.$2, blurb: p.$3),
      ],
    );
  }
}

class _ProviderCard extends ConsumerStatefulWidget {
  const _ProviderCard({
    required this.tenant,
    required this.provider,
    required this.label,
    required this.blurb,
  });

  final String tenant;
  final String provider;
  final String label;
  final String blurb;

  @override
  ConsumerState<_ProviderCard> createState() => _ProviderCardState();
}

class _ProviderCardState extends ConsumerState<_ProviderCard> {
  bool _busy = false;
  bool _polling = false;

  ({String tenant, String provider}) get _key =>
      (tenant: widget.tenant, provider: widget.provider);

  Future<void> _connect() async {
    setState(() => _busy = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      final link = await api.pspOnboardingLink(widget.tenant, widget.provider);
      html.window.open(link.actionUrl, 'psp_onboarding_${widget.provider}',
          'width=540,height=760');
      // The popup returns to our /return endpoint which reconciles the account;
      // poll until it flips to connected (or the operator gives up).
      unawaited(_pollUntilConnected());
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _pollUntilConnected() async {
    setState(() => _polling = true);
    try {
      for (var i = 0; i < 30; i++) {
        await Future<void>.delayed(const Duration(seconds: 2));
        if (!mounted) return;
        ref.invalidate(pspStatusProvider(_key));
        final view = await ref.read(pspStatusProvider(_key).future);
        if (view.connected || view.status == 'RESTRICTED') return;
      }
    } catch (_) {
      // best-effort; the manual status still reflects reality
    } finally {
      if (mounted) setState(() => _polling = false);
    }
  }

  Future<void> _disconnect() async {
    final ok = await showDialog<bool>(
          context: context,
          builder: (_) => AlertDialog(
            title: Text('Disconnect ${widget.label}?'),
            content: const Text(
                'Checkout will stop routing funds to this account. You can reconnect anytime.'),
            actions: [
              TextButton(
                  onPressed: () => Navigator.pop(context, false),
                  child: const Text('Cancel')),
              FilledButton(
                  onPressed: () => Navigator.pop(context, true),
                  child: const Text('Disconnect')),
            ],
          ),
        ) ??
        false;
    if (!ok) return;
    setState(() => _busy = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.pspDisconnect(widget.tenant, widget.provider);
      ref.invalidate(pspStatusProvider(_key));
      if (mounted) showSnack(context, '${widget.label} disconnected');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(pspStatusProvider(_key));
    return Card(
      margin: const EdgeInsets.only(bottom: 16),
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(children: [
                    Text(widget.label,
                        style: Theme.of(context).textTheme.titleMedium),
                    const SizedBox(width: 12),
                    async.when(
                      data: (v) => _StatusBadge(view: v, polling: _polling),
                      loading: () => const _Pill(
                          text: 'Checking…', color: Colors.blueGrey),
                      error: (_, __) =>
                          const _Pill(text: 'Unknown', color: Colors.grey),
                    ),
                  ]),
                  const SizedBox(height: 4),
                  Text(widget.blurb,
                      style: Theme.of(context).textTheme.bodySmall),
                ],
              ),
            ),
            const SizedBox(width: 16),
            async.when(
              data: (v) => _actions(v),
              loading: () => const SizedBox.shrink(),
              error: (_, __) => _connectButton(),
            ),
          ],
        ),
      ),
    );
  }

  Widget _actions(PspAccountView v) {
    if (!v.onboardingSupported) {
      return const _Pill(text: 'Not self-serve', color: Colors.grey);
    }
    if (v.connected) {
      return OutlinedButton.icon(
        onPressed: _busy ? null : _disconnect,
        icon: const Icon(Icons.link_off, size: 18),
        label: const Text('Disconnect'),
      );
    }
    return _connectButton(reconnect: v.status == 'DISABLED');
  }

  Widget _connectButton({bool reconnect = false}) {
    return FilledButton.icon(
      onPressed: (_busy || _polling) ? null : _connect,
      icon: _busy || _polling
          ? const SizedBox(
              width: 16,
              height: 16,
              child: CircularProgressIndicator(strokeWidth: 2))
          : const Icon(Icons.account_balance_wallet_outlined, size: 18),
      label: Text(reconnect ? 'Reconnect' : 'Connect ${widget.label}'),
    );
  }
}

class _StatusBadge extends StatelessWidget {
  const _StatusBadge({required this.view, required this.polling});

  final PspAccountView view;
  final bool polling;

  @override
  Widget build(BuildContext context) {
    if (polling && !view.connected) {
      return const _Pill(text: 'Connecting…', color: Colors.orange);
    }
    if (!view.onboardingSupported) {
      return const _Pill(text: 'Manual', color: Colors.grey);
    }
    return switch (view.status) {
      'ACTIVE' when view.chargesEnabled =>
        const _Pill(text: 'Connected ✓', color: Colors.green),
      'ACTIVE' => const _Pill(text: 'Action needed', color: Colors.orange),
      'ONBOARDING' => const _Pill(text: 'Finishing…', color: Colors.orange),
      'RESTRICTED' => const _Pill(text: 'Action needed', color: Colors.orange),
      'DISABLED' => const _Pill(text: 'Disconnected', color: Colors.grey),
      _ => const _Pill(text: 'Not connected', color: Colors.grey),
    };
  }
}

class _Pill extends StatelessWidget {
  const _Pill({required this.text, required this.color});

  final String text;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(text,
          style: TextStyle(color: color, fontWeight: FontWeight.w600, fontSize: 12)),
    );
  }
}
