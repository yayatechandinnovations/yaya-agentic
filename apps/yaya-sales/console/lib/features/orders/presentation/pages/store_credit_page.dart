import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../core/di/service_locator.dart';
import '../../../../core/domain/money.dart';
import '../../../../core/storage/store_context.dart';
import '../../../../core/widgets/widgets.dart';
import '../bloc/store_credit_bloc.dart';

class StoreCreditPage extends StatefulWidget {
  const StoreCreditPage({super.key});

  @override
  State<StoreCreditPage> createState() => _StoreCreditPageState();
}

class _StoreCreditPageState extends State<StoreCreditPage> {
  final _customer = TextEditingController();
  late final TextEditingController _currency =
      TextEditingController(text: sl<StoreContext>().storeCurrency ?? 'USD');

  @override
  void dispose() {
    _customer.dispose();
    _currency.dispose();
    super.dispose();
  }

  void _lookup() {
    if (_customer.text.trim().isEmpty) return;
    context.read<StoreCreditBloc>().add(BalanceLookupRequested(
        _customer.text.trim(), _currency.text.trim().toUpperCase()));
  }

  @override
  Widget build(BuildContext context) {
    return BlocConsumer<StoreCreditBloc, StoreCreditState>(
      listenWhen: (prev, next) =>
          next.status == StoreCreditStatus.failure && next.error != null,
      listener: (context, state) => showErrorSnack(context, state.error!),
      builder: (context, state) {
        return Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 520),
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text('Store credit', style: Theme.of(context).textTheme.headlineSmall),
                  const SizedBox(height: 16),
                  Row(
                    children: [
                      Expanded(
                        flex: 2,
                        child: TextField(
                          controller: _customer,
                          decoration: const InputDecoration(labelText: 'Customer ref'),
                          onSubmitted: (_) => _lookup(),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: TextField(
                          controller: _currency,
                          decoration: const InputDecoration(labelText: 'Currency'),
                        ),
                      ),
                      const SizedBox(width: 8),
                      FilledButton(onPressed: _lookup, child: const Text('Lookup')),
                    ],
                  ),
                  const SizedBox(height: 24),
                  if (state.status == StoreCreditStatus.loading)
                    const LoadingView()
                  else if (state.balance != null)
                    Card(
                      child: ListTile(
                        leading: const Icon(Icons.account_balance_wallet),
                        title: Text(state.balance!.customerRef),
                        subtitle: const Text('Current balance'),
                        trailing: Text(
                          formatMinor(state.balance!.balanceMinor, state.balance!.currency),
                          style: Theme.of(context).textTheme.titleLarge,
                        ),
                      ),
                    ),
                  const SizedBox(height: 16),
                  OutlinedButton.icon(
                    onPressed: () => _promptIssue(context),
                    icon: const Icon(Icons.add_card),
                    label: const Text('Issue store credit'),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  Future<void> _promptIssue(BuildContext context) async {
    final bloc = context.read<StoreCreditBloc>();
    final customer = TextEditingController(text: _customer.text);
    final amount = TextEditingController();
    final reason = TextEditingController();
    final currency = _currency.text.trim().toUpperCase();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Issue store credit'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: customer,
              decoration: const InputDecoration(labelText: 'Customer ref'),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: amount,
              keyboardType: TextInputType.number,
              decoration: InputDecoration(
                labelText: 'Amount (minor units, $currency)',
                helperText: '1999 = 19.99',
              ),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: reason,
              decoration: const InputDecoration(labelText: 'Reason (optional)'),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Issue')),
        ],
      ),
    );
    if (ok == true && customer.text.trim().isNotEmpty) {
      _customer.text = customer.text.trim();
      bloc.add(CreditIssueRequested(
        customer.text.trim(),
        int.tryParse(amount.text) ?? 0,
        currency,
        reason.text.trim().isEmpty ? null : reason.text.trim(),
      ));
    }
  }
}
