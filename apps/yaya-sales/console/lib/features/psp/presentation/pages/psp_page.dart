import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:web/web.dart' as web;

import '../../../../core/widgets/widgets.dart';
import '../../domain/entities/psp_entities.dart';
import '../bloc/psp_bloc.dart';

/// PSP / payments: one card per provider with status + charges badge, connect
/// (opens the hosted onboarding URL in a new tab), paste-return params, and
/// disconnect. The `/psp/return/:provider` route auto-posts redirect params.
class PspPage extends StatelessWidget {
  const PspPage({super.key});

  @override
  Widget build(BuildContext context) {
    return BlocConsumer<PspBloc, PspState>(
      listener: (context, state) {
        if (state.status == PspStatus.failure && state.error != null) {
          showErrorSnack(context, state.error!);
        }
        if (state.onboardingLink != null) {
          web.window.open(state.onboardingLink!.actionUrl, '_blank');
          showInfoSnack(context,
              'Onboarding opened in a new tab. When the PSP sends you back, this page reconciles automatically.');
        }
        if (state.reconciled != null) {
          showInfoSnack(context,
              '${state.reconciled!.provider}: ${state.reconciled!.status}${state.reconciled!.connected ? ' — charges enabled' : ''}');
        }
      },
      builder: (context, state) {
        switch (state.status) {
          case PspStatus.initial:
          case PspStatus.loading:
            return const LoadingView();
          case PspStatus.failure:
            return ErrorView(
              state.error ?? 'Failed to load PSP status',
              onRetry: () => context.read<PspBloc>().add(const PspStatusRequested()),
            );
          case PspStatus.loaded:
            return ListView(
              padding: const EdgeInsets.all(24),
              children: [
                SectionHeader('Payment providers', actions: [
                  OutlinedButton.icon(
                    onPressed: () =>
                        context.read<PspBloc>().add(const PspStatusRequested()),
                    icon: const Icon(Icons.refresh),
                    label: const Text('Refresh status'),
                  ),
                ]),
                Wrap(
                  spacing: 16,
                  runSpacing: 16,
                  children:
                      state.accounts.map((a) => _providerCard(context, a)).toList(),
                ),
              ],
            );
        }
      },
    );
  }

  Widget _providerCard(BuildContext context, PspAccount account) {
    return SizedBox(
      width: 320,
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(
                    account.connected ? Icons.check_circle : Icons.payments,
                    color: account.connected ? Colors.green : null,
                  ),
                  const SizedBox(width: 8),
                  Text(account.provider.toUpperCase(),
                      style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
                  const Spacer(),
                  StatusChip(account.status),
                ],
              ),
              const SizedBox(height: 8),
              Text('Charges: ${account.chargesEnabled ? 'enabled' : 'disabled'}'),
              if (account.accountId != null) Text('Account: ${account.accountId}'),
              const SizedBox(height: 12),
              if (!account.onboardingSupported)
                const Text('No self-serve onboarding for this provider.',
                    style: TextStyle(color: Colors.black54, fontSize: 12))
              else
                Row(
                  children: [
                    if (!account.connected)
                      FilledButton.tonal(
                        onPressed: () {
                          final returnUrl =
                              '${Uri.base.origin}/psp/return/${account.provider}';
                          context
                              .read<PspBloc>()
                              .add(PspConnectRequested(account.provider, returnUrl));
                        },
                        child: Text('Connect ${account.provider}'),
                      ),
                    if (!account.connected) ...[
                      const SizedBox(width: 8),
                      TextButton(
                        onPressed: () => _promptPasteReturn(context, account.provider),
                        child: const Text('Paste return params'),
                      ),
                    ],
                    if (account.connected)
                      OutlinedButton(
                        onPressed: () async {
                          final bloc = context.read<PspBloc>();
                          if (await confirmAction(context, 'Disconnect',
                              'Disconnect ${account.provider}? Routing stops using it.')) {
                            bloc.add(PspDisconnectRequested(account.provider));
                          }
                        },
                        child: const Text('Disconnect'),
                      ),
                  ],
                ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _promptPasteReturn(BuildContext context, String provider) async {
    final bloc = context.read<PspBloc>();
    final raw = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Paste $provider return URL / params'),
        content: SizedBox(
          width: 480,
          child: TextField(
            controller: raw,
            maxLines: 4,
            decoration: const InputDecoration(
              hintText:
                  'Paste the full redirect URL or the query string (a=b&c=d…)',
              border: OutlineInputBorder(),
            ),
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, true), child: const Text('Reconcile')),
        ],
      ),
    );
    if (ok != true || raw.text.trim().isEmpty) return;
    final text = raw.text.trim();
    Map<String, String> params;
    final asUri = Uri.tryParse(text);
    if (asUri != null && asUri.queryParameters.isNotEmpty) {
      params = asUri.queryParameters;
    } else {
      params = Uri.splitQueryString(text.startsWith('?') ? text.substring(1) : text);
    }
    bloc.add(PspReturnReceived(provider, Map<String, dynamic>.from(params)));
  }
}

/// Landing page for the PSP redirect: auto-posts the query params, then shows
/// the reconciliation outcome with a link back to the payments page.
class PspReturnPage extends StatefulWidget {
  const PspReturnPage({super.key, required this.provider, required this.params});

  final String provider;
  final Map<String, String> params;

  @override
  State<PspReturnPage> createState() => _PspReturnPageState();
}

class _PspReturnPageState extends State<PspReturnPage> {
  @override
  void initState() {
    super.initState();
    context
        .read<PspBloc>()
        .add(PspReturnReceived(widget.provider, Map<String, dynamic>.from(widget.params)));
  }

  @override
  Widget build(BuildContext context) {
    return BlocBuilder<PspBloc, PspState>(
      builder: (context, state) {
        if (state.status == PspStatus.failure && state.error != null) {
          return ErrorView(state.error!);
        }
        final account =
            state.accounts.where((a) => a.provider == widget.provider).firstOrNull;
        return Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (account == null) ...[
                const CircularProgressIndicator(),
                const SizedBox(height: 12),
                Text('Reconciling ${widget.provider} onboarding…'),
              ] else ...[
                Icon(account.connected ? Icons.check_circle : Icons.hourglass_top,
                    size: 48, color: account.connected ? Colors.green : null),
                const SizedBox(height: 12),
                Text('${widget.provider}: ${account.status}'
                    '${account.connected ? ' — charges enabled' : ''}'),
              ],
            ],
          ),
        );
      },
    );
  }
}
