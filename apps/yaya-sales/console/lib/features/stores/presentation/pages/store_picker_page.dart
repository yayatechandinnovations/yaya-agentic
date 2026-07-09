import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/di/service_locator.dart';
import '../../../../core/storage/store_context.dart';
import '../../../../core/widgets/widgets.dart';
import '../../../auth/presentation/bloc/auth_bloc.dart';
import '../bloc/store_picker_bloc.dart';

/// Post-login store picker: tenant id (remembered), the tenant's stores, and a
/// create-store form. Picking a store sets the X-Yaya-Store-Id context.
class StorePickerPage extends StatefulWidget {
  const StorePickerPage({super.key});

  @override
  State<StorePickerPage> createState() => _StorePickerPageState();
}

class _StorePickerPageState extends State<StorePickerPage> {
  late final TextEditingController _tenant;

  @override
  void initState() {
    super.initState();
    final remembered = sl<StoreContext>().tenantId ?? '';
    _tenant = TextEditingController(text: remembered);
    if (remembered.isNotEmpty) {
      context.read<StorePickerBloc>().add(StorePickerTenantSubmitted(remembered));
    }
  }

  @override
  void dispose() {
    _tenant.dispose();
    super.dispose();
  }

  void _loadTenant() {
    final tenantId = _tenant.text.trim();
    if (tenantId.isEmpty) return;
    sl<StoreContext>().rememberTenant(tenantId);
    context.read<StorePickerBloc>().add(StorePickerTenantSubmitted(tenantId));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Choose a store'),
        actions: [
          IconButton(
            tooltip: 'Sign out',
            icon: const Icon(Icons.logout),
            onPressed: () => context.read<AuthBloc>().add(const AuthLogoutRequested()),
          ),
        ],
      ),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 560),
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: TextField(
                        controller: _tenant,
                        decoration: const InputDecoration(
                          labelText: 'Tenant id',
                          helperText: 'The agent-side tenant that owns your stores',
                        ),
                        onSubmitted: (_) => _loadTenant(),
                      ),
                    ),
                    const SizedBox(width: 12),
                    FilledButton(onPressed: _loadTenant, child: const Text('Load stores')),
                  ],
                ),
                const SizedBox(height: 16),
                Expanded(
                  child: BlocBuilder<StorePickerBloc, StorePickerState>(
                    builder: (context, state) {
                      switch (state.status) {
                        case StorePickerStatus.initial:
                          return const EmptyView('Enter your tenant id to list its stores.');
                        case StorePickerStatus.loading:
                          return const LoadingView();
                        case StorePickerStatus.failure:
                          return ErrorView(state.error ?? 'Failed to load stores',
                              onRetry: _loadTenant);
                        case StorePickerStatus.loaded:
                          if (state.stores.isEmpty) {
                            return const EmptyView('No stores yet — create the first one.');
                          }
                          return ListView.separated(
                            itemCount: state.stores.length,
                            separatorBuilder: (_, __) => const Divider(height: 1),
                            itemBuilder: (_, i) {
                              final store = state.stores[i];
                              return ListTile(
                                leading: const Icon(Icons.storefront),
                                title: Text(store.displayName),
                                subtitle: Text('${store.id} · ${store.defaultCurrency}'),
                                trailing: const Icon(Icons.chevron_right),
                                onTap: () async {
                                  await sl<StoreContext>().selectStore(
                                      store.id, store.displayName, store.defaultCurrency);
                                  if (context.mounted) context.go('/orders');
                                },
                              );
                            },
                          );
                      }
                    },
                  ),
                ),
                const SizedBox(height: 12),
                OutlinedButton.icon(
                  onPressed: () => _promptCreate(context),
                  icon: const Icon(Icons.add_business),
                  label: const Text('Create store'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _promptCreate(BuildContext context) async {
    final bloc = context.read<StorePickerBloc>();
    if (_tenant.text.trim().isEmpty) {
      showErrorSnack(context, 'Enter a tenant id first');
      return;
    }
    final name = TextEditingController();
    final currency = TextEditingController(text: 'USD');
    final created = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Create store'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: name,
              autofocus: true,
              decoration: const InputDecoration(labelText: 'Display name'),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: currency,
              decoration: const InputDecoration(
                labelText: 'Default currency (ISO-4217)',
                helperText: 'e.g. USD, DOP',
              ),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Create')),
        ],
      ),
    );
    if (created == true && name.text.trim().isNotEmpty) {
      sl<StoreContext>().rememberTenant(_tenant.text.trim());
      bloc
        ..add(StorePickerTenantSubmitted(_tenant.text.trim()))
        ..add(StoreCreateRequested(name.text.trim(), currency.text.trim().toUpperCase()));
    }
  }
}
