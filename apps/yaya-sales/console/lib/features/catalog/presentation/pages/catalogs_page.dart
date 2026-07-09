import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/widgets/widgets.dart';
import '../bloc/catalogs_bloc.dart';

class CatalogsPage extends StatelessWidget {
  const CatalogsPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _promptCreate(context),
        icon: const Icon(Icons.add),
        label: const Text('New catalog'),
      ),
      body: BlocConsumer<CatalogsBloc, CatalogsState>(
        listenWhen: (prev, next) =>
            next.status == CatalogsStatus.failure && next.error != null,
        listener: (context, state) => showErrorSnack(context, state.error!),
        builder: (context, state) {
          switch (state.status) {
            case CatalogsStatus.initial:
            case CatalogsStatus.loading:
              return const LoadingView();
            case CatalogsStatus.failure:
              return ErrorView(
                state.error ?? 'Failed to load catalogs',
                onRetry: () =>
                    context.read<CatalogsBloc>().add(const CatalogsRequested()),
              );
            case CatalogsStatus.loaded:
              if (state.catalogs.isEmpty) {
                return const EmptyView('No catalogs yet — create the first one.');
              }
              return ListView.separated(
                padding: const EdgeInsets.all(16),
                itemCount: state.catalogs.length,
                separatorBuilder: (_, __) => const Divider(height: 1),
                itemBuilder: (_, i) {
                  final catalog = state.catalogs[i];
                  return ListTile(
                    leading: const Icon(Icons.menu_book),
                    title: Text(catalog.displayName),
                    subtitle: Text('v${catalog.version} · ${catalog.id}'),
                    trailing: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        StatusChip(catalog.status),
                        const SizedBox(width: 8),
                        if (catalog.status == 'DRAFT')
                          FilledButton.tonal(
                            onPressed: () => context
                                .read<CatalogsBloc>()
                                .add(CatalogPublishRequested(catalog.id)),
                            child: const Text('Publish'),
                          ),
                        const SizedBox(width: 8),
                        const Icon(Icons.chevron_right),
                      ],
                    ),
                    onTap: () => context.go('/catalog/${catalog.id}'),
                  );
                },
              );
          }
        },
      ),
    );
  }

  Future<void> _promptCreate(BuildContext context) async {
    final bloc = context.read<CatalogsBloc>();
    final controller = TextEditingController();
    final name = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('New catalog'),
        content: TextField(
          controller: controller,
          autofocus: true,
          decoration: const InputDecoration(labelText: 'Display name'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, controller.text.trim()),
            child: const Text('Create'),
          ),
        ],
      ),
    );
    if (name != null && name.isNotEmpty) {
      bloc.add(CatalogCreateRequested(name));
    }
  }
}
