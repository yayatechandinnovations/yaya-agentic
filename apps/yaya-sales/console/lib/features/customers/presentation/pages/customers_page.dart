import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../core/widgets/address_fields.dart';
import '../../../../core/widgets/widgets.dart';
import '../../domain/entities/customer_entities.dart';
import '../bloc/customers_bloc.dart';

/// The customer directory: who buys here, how to reach them, where to ship.
/// Every card is the profile behind the `customer_ref` orders carry.
class CustomersPage extends StatefulWidget {
  const CustomersPage({super.key});

  @override
  State<CustomersPage> createState() => _CustomersPageState();
}

class _CustomersPageState extends State<CustomersPage> {
  final _search = TextEditingController();

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Customers')),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _promptProfile(context),
        icon: const Icon(Icons.person_add_alt),
        label: const Text('New customer'),
      ),
      body: BlocConsumer<CustomersBloc, CustomersState>(
        listenWhen: (p, c) => c.message != null && p.message != c.message,
        listener: (context, state) => showInfoSnack(context, state.message!),
        builder: (context, state) {
          return Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 16, 24, 0),
                child: TextField(
                  controller: _search,
                  decoration: InputDecoration(
                    prefixIcon: const Icon(Icons.search),
                    hintText: 'Search by name, email, phone or reference…',
                    suffixIcon: _search.text.isEmpty
                        ? null
                        : IconButton(
                            icon: const Icon(Icons.clear),
                            onPressed: () {
                              _search.clear();
                              context.read<CustomersBloc>().add(const CustomersRequested());
                            },
                          ),
                  ),
                  onSubmitted: (q) =>
                      context.read<CustomersBloc>().add(CustomersRequested(q.trim())),
                ),
              ),
              Expanded(child: _body(state)),
            ],
          );
        },
      ),
    );
  }

  Widget _body(CustomersState state) {
    switch (state.status) {
      case CustomersStatus.initial:
      case CustomersStatus.loading:
        return const LoadingView();
      case CustomersStatus.failure:
        return ErrorView(state.error ?? 'Failed to load customers');
      case CustomersStatus.loaded:
        if (state.customers.isEmpty) {
          return const EmptyView('No customers yet — add your first regular.');
        }
        return ListView.builder(
          padding: const EdgeInsets.fromLTRB(24, 8, 24, 88),
          itemCount: state.customers.length,
          itemBuilder: (_, i) => _CustomerCard(
            customer: state.customers[i],
            onEdit: (c) => _promptProfile(context, existing: c),
            onAddAddress: (c) => _promptAddress(context, c),
          ),
        );
    }
  }

  Future<void> _promptProfile(BuildContext context, {Customer? existing}) async {
    final bloc = context.read<CustomersBloc>();
    final name = TextEditingController(text: existing?.displayName ?? '');
    final email = TextEditingController(text: existing?.email ?? '');
    final phone = TextEditingController(text: existing?.phone ?? '');
    final ref = TextEditingController(text: existing?.customerRef ?? '');
    final notes = TextEditingController(text: existing?.notes ?? '');
    final formKey = GlobalKey<FormState>();

    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(existing == null ? 'New customer' : 'Edit ${existing.displayName}'),
        content: SizedBox(
          width: 440,
          child: Form(
            key: formKey,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  TextFormField(
                    controller: name,
                    autofocus: existing == null,
                    decoration: const InputDecoration(labelText: 'Full name'),
                    validator: (v) =>
                        (v == null || v.trim().isEmpty) ? 'Name is required' : null,
                  ),
                  const SizedBox(height: 8),
                  Row(children: [
                    Expanded(
                      child: TextFormField(
                        controller: email,
                        decoration: const InputDecoration(labelText: 'Email'),
                        validator: (v) => (v == null || v.trim().isEmpty || v.contains('@'))
                            ? null
                            : 'Not an email address',
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: TextField(
                        controller: phone,
                        decoration: const InputDecoration(
                            labelText: 'Phone', hintText: '+1809…'),
                      ),
                    ),
                  ]),
                  const SizedBox(height: 8),
                  TextField(
                    controller: ref,
                    decoration: const InputDecoration(
                      labelText: 'Chat identity (optional)',
                      helperText:
                          'The verified sign-in reference their chat orders carry',
                    ),
                  ),
                  const SizedBox(height: 8),
                  TextField(
                    controller: notes,
                    maxLines: 2,
                    decoration: const InputDecoration(
                        labelText: 'Notes', hintText: 'prefers evening delivery…'),
                  ),
                ],
              ),
            ),
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(
            onPressed: () {
              if (formKey.currentState?.validate() ?? false) Navigator.pop(ctx, true);
            },
            child: Text(existing == null ? 'Create' : 'Save'),
          ),
        ],
      ),
    );

    if (ok == true) {
      String? v(TextEditingController c) => c.text.trim().isEmpty ? '' : c.text.trim();
      final draft = CustomerDraft(
        displayName: name.text.trim(),
        email: v(email),
        phone: v(phone),
        customerRef: v(ref),
        notes: v(notes),
      );
      if (existing == null) {
        bloc.add(CustomerCreateRequested(draft));
      } else {
        bloc.add(CustomerUpdateRequested(existing.id, draft));
      }
    }
  }

  Future<void> _promptAddress(BuildContext context, Customer customer) async {
    final bloc = context.read<CustomersBloc>();
    final draft = AddressDraft();
    var kind = 'SHIPPING';

    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setLocal) => AlertDialog(
          title: Text('Add address — ${customer.displayName}'),
          content: SizedBox(
            width: 440,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  SegmentedButton<String>(
                    segments: const [
                      ButtonSegment(value: 'SHIPPING', label: Text('Shipping')),
                      ButtonSegment(value: 'BILLING', label: Text('Billing')),
                      ButtonSegment(value: 'OTHER', label: Text('Other')),
                    ],
                    selected: {kind},
                    onSelectionChanged: (s) => setLocal(() => kind = s.first),
                  ),
                  const SizedBox(height: 12),
                  AddressFields(draft: draft),
                ],
              ),
            ),
          ),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
            FilledButton(
                onPressed: () => Navigator.pop(ctx, true), child: const Text('Add')),
          ],
        ),
      ),
    );

    if (ok == true && !draft.isEmpty) {
      bloc.add(CustomerAddressAdded(customer.id, kind, draft.toJson()));
    }
  }
}

class _CustomerCard extends StatelessWidget {
  const _CustomerCard({
    required this.customer,
    required this.onEdit,
    required this.onAddAddress,
  });

  final Customer customer;
  final ValueChanged<Customer> onEdit;
  final ValueChanged<Customer> onAddAddress;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 8, 12),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            CircleAvatar(
              backgroundColor: scheme.primaryContainer,
              child: Text(customer.initials,
                  style: TextStyle(color: scheme.onPrimaryContainer, fontSize: 13)),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(children: [
                    Text(customer.displayName,
                        style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
                    if (customer.customerRef != null) ...[
                      const SizedBox(width: 8),
                      Tooltip(
                        message: 'Linked to chat identity ${customer.customerRef}',
                        child: Icon(Icons.verified_outlined, size: 16, color: scheme.primary),
                      ),
                    ],
                  ]),
                  const SizedBox(height: 4),
                  Wrap(
                    spacing: 14,
                    runSpacing: 2,
                    children: [
                      if (customer.email != null)
                        _contact(Icons.mail_outline, customer.email!),
                      if (customer.phone != null)
                        _contact(Icons.phone_outlined, customer.phone!),
                    ],
                  ),
                  if (customer.notes != null && customer.notes!.isNotEmpty) ...[
                    const SizedBox(height: 4),
                    Text(customer.notes!,
                        style: const TextStyle(fontSize: 12, color: Colors.black54)),
                  ],
                  for (final address in customer.addresses)
                    Padding(
                      padding: const EdgeInsets.only(top: 6),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Icon(
                            address.kind == 'BILLING'
                                ? Icons.receipt_long_outlined
                                : Icons.local_shipping_outlined,
                            size: 15,
                            color: Colors.black45,
                          ),
                          const SizedBox(width: 6),
                          Expanded(
                            child: Text(address.oneLine,
                                style:
                                    const TextStyle(fontSize: 12.5, color: Colors.black87)),
                          ),
                          InkWell(
                            onTap: () => context.read<CustomersBloc>().add(
                                CustomerAddressRemoved(customer.id, address.id)),
                            child: const Padding(
                              padding: EdgeInsets.all(2),
                              child: Icon(Icons.close, size: 14, color: Colors.black38),
                            ),
                          ),
                        ],
                      ),
                    ),
                ],
              ),
            ),
            PopupMenuButton<String>(
              onSelected: (action) async {
                switch (action) {
                  case 'edit':
                    onEdit(customer);
                  case 'address':
                    onAddAddress(customer);
                  case 'delete':
                    final bloc = context.read<CustomersBloc>();
                    if (await confirmAction(context, 'Delete customer',
                        'Remove "${customer.displayName}" from the directory? Orders keep their history.')) {
                      bloc.add(CustomerDeleteRequested(customer.id));
                    }
                }
              },
              itemBuilder: (_) => const [
                PopupMenuItem(value: 'edit', child: Text('Edit profile')),
                PopupMenuItem(value: 'address', child: Text('Add address')),
                PopupMenuItem(value: 'delete', child: Text('Delete')),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _contact(IconData icon, String value) => Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 14, color: Colors.black45),
          const SizedBox(width: 4),
          Text(value, style: const TextStyle(fontSize: 12.5)),
        ],
      );
}
