import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:go_router/go_router.dart';

import '../../features/auth/presentation/bloc/auth_bloc.dart';
import '../di/service_locator.dart';
import '../storage/store_context.dart';

/// The authenticated, store-scoped shell: a scrollable left nav across every
/// admin area, with the acting store + switch/sign-out in the app bar.
class ConsoleShell extends StatelessWidget {
  const ConsoleShell({super.key, required this.child, required this.location});

  final Widget child;
  final String location;

  static const _destinations = [
    ('/orders', Icons.receipt_long, 'Orders'),
    ('/draft-orders', Icons.edit_note, 'Draft orders'),
    ('/customers', Icons.people_alt_outlined, 'Customers'),
    ('/catalog', Icons.menu_book, 'Catalog'),
    ('/inventory', Icons.inventory_2, 'Inventory'),
    ('/pricing', Icons.price_change, 'Pricing'),
    ('/shipping', Icons.local_shipping, 'Shipping'),
    ('/fleet', Icons.sports_motorsports, 'Fleet'),
    ('/returns', Icons.assignment_return, 'Returns'),
    ('/purchasing', Icons.shopping_basket, 'Purchasing'),
    ('/store-credit', Icons.account_balance_wallet, 'Store credit'),
    ('/psp', Icons.payments, 'Payments'),
    ('/reports', Icons.insights, 'Reports'),
    ('/settings', Icons.store, 'Store settings'),
  ];

  @override
  Widget build(BuildContext context) {
    final stores = sl<StoreContext>();
    return Scaffold(
      appBar: AppBar(
        title: ListenableBuilder(
          listenable: stores,
          builder: (_, __) => Text(
            stores.storeName == null
                ? 'yaya-sales console'
                : '${stores.storeName}  ·  ${stores.storeId}',
            style: const TextStyle(fontSize: 16),
          ),
        ),
        actions: [
          TextButton.icon(
            onPressed: () => context.go('/stores'),
            icon: const Icon(Icons.swap_horiz),
            label: const Text('Switch store'),
          ),
          IconButton(
            tooltip: 'Sign out',
            icon: const Icon(Icons.logout),
            onPressed: () => context.read<AuthBloc>().add(const AuthLogoutRequested()),
          ),
          const SizedBox(width: 8),
        ],
      ),
      body: Row(
        children: [
          SizedBox(
            width: 200,
            child: ListView(
              children: _destinations.map((d) {
                final selected = location.startsWith(d.$1);
                return ListTile(
                  dense: true,
                  selected: selected,
                  leading: Icon(d.$2, size: 20),
                  title: Text(d.$3),
                  onTap: () => context.go(d.$1),
                );
              }).toList(),
            ),
          ),
          const VerticalDivider(width: 1),
          Expanded(child: child),
        ],
      ),
    );
  }
}
