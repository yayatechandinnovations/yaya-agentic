import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../core/di/service_locator.dart';
import '../../../../core/domain/money.dart';
import '../../../../core/storage/store_context.dart';
import '../../../../core/widgets/widgets.dart';
import '../../../customers/domain/entities/customer_entities.dart';
import '../bloc/store_credit_bloc.dart';

/// Sentinel dropdown value that reveals the free-text field — customer refs
/// are opaque strings, so an ad-hoc ref must stay possible.
const _kOtherCustomer = '__other__';

class StoreCreditPage extends StatefulWidget {
  const StoreCreditPage({super.key});

  @override
  State<StoreCreditPage> createState() => _StoreCreditPageState();
}

class _StoreCreditPageState extends State<StoreCreditPage> {
  String? _customerSel;
  final _customerOther = TextEditingController();
  late final TextEditingController _currency =
      TextEditingController(text: sl<StoreContext>().storeCurrency ?? 'USD');

  @override
  void dispose() {
    _customerOther.dispose();
    _currency.dispose();
    super.dispose();
  }

  String? get _customerRef => _customerSel == _kOtherCustomer
      ? (_customerOther.text.trim().isEmpty ? null : _customerOther.text.trim())
      : _customerSel;

  void _lookup() {
    final ref = _customerRef;
    if (ref == null) return;
    context
        .read<StoreCreditBloc>()
        .add(BalanceLookupRequested(ref, _currency.text.trim().toUpperCase()));
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
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(
                        flex: 2,
                        child: _CustomerRefField(
                          customers: state.customers,
                          selected: _customerSel,
                          freeText: _customerOther,
                          onChanged: (v) => setState(() => _customerSel = v),
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
                      Padding(
                        padding: const EdgeInsets.only(top: 8),
                        child: FilledButton(onPressed: _lookup, child: const Text('Lookup')),
                      ),
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
                    onPressed: () => _promptIssue(context, state.customers),
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

  Future<void> _promptIssue(BuildContext context, List<Customer> customers) async {
    final bloc = context.read<StoreCreditBloc>();
    String? customerSel = _customerSel;
    final customerOther = TextEditingController(text: _customerOther.text);
    final amount = TextEditingController();
    final reason = TextEditingController();
    final currency = _currency.text.trim().toUpperCase();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) => AlertDialog(
          title: const Text('Issue store credit'),
          content: SizedBox(
            width: 380,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                _CustomerRefField(
                  customers: customers,
                  selected: customerSel,
                  freeText: customerOther,
                  onChanged: (v) => setState(() => customerSel = v),
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
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Issue')),
          ],
        ),
      ),
    );
    final ref = customerSel == _kOtherCustomer
        ? (customerOther.text.trim().isEmpty ? null : customerOther.text.trim())
        : customerSel;
    if (ok == true && ref != null) {
      setState(() {
        _customerSel = customerSel;
        _customerOther.text = customerOther.text;
      });
      bloc.add(CreditIssueRequested(
        ref,
        int.tryParse(amount.text) ?? 0,
        currency,
        reason.text.trim().isEmpty ? null : reason.text.trim(),
      ));
    }
  }
}

class _CustomerRefField extends StatelessWidget {
  const _CustomerRefField({
    required this.customers,
    required this.selected,
    required this.freeText,
    required this.onChanged,
  });

  final List<Customer> customers;
  final String? selected;
  final TextEditingController freeText;
  final ValueChanged<String?> onChanged;

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        DropdownButtonFormField<String?>(
          initialValue: selected,
          decoration: const InputDecoration(labelText: 'Customer'),
          items: [
            ...customers.where((c) => c.customerRef != null).map(
                (c) => DropdownMenuItem<String?>(
                    value: c.customerRef, child: Text(c.displayName))),
            const DropdownMenuItem<String?>(
                value: _kOtherCustomer, child: Text('Other…')),
          ],
          onChanged: onChanged,
        ),
        if (selected == _kOtherCustomer) ...[
          const SizedBox(height: 8),
          TextField(
            controller: freeText,
            decoration: const InputDecoration(labelText: 'Customer ref'),
          ),
        ],
      ],
    );
  }
}
