import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/di/service_locator.dart';
import '../../../../core/media/image_picker.dart';
import '../../../../core/media/media_service.dart';
import '../../../../core/widgets/image_manager.dart';
import '../../../../core/widgets/widgets.dart';
import '../../domain/entities/catalog_entities.dart';
import '../bloc/catalogs_bloc.dart';

class CatalogsPage extends StatelessWidget {
  const CatalogsPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _promptEdit(context),
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
              return ListView.builder(
                padding: const EdgeInsets.fromLTRB(16, 16, 16, 88),
                itemCount: state.catalogs.length,
                itemBuilder: (_, i) => _CatalogCard(
                  catalog: state.catalogs[i],
                  onEdit: (c) => _promptEdit(context, existing: c),
                ),
              );
          }
        },
      ),
    );
  }

  /// Create or edit: display name + a cover image that fronts the catalog in
  /// the storefront and the console.
  static Future<void> _promptEdit(BuildContext context, {Catalog? existing}) async {
    final bloc = context.read<CatalogsBloc>();
    final name = TextEditingController(text: existing?.displayName ?? '');
    var cover = existing?.coverUrl;

    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setLocal) => AlertDialog(
          title: Text(existing == null ? 'New catalog' : 'Edit catalog'),
          content: SizedBox(
            width: 420,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                TextField(
                  controller: name,
                  autofocus: existing == null,
                  decoration: const InputDecoration(labelText: 'Display name'),
                ),
                if (existing != null) ...[
                  const SizedBox(height: 16),
                  const Text('Cover image', style: TextStyle(fontWeight: FontWeight.w600)),
                  const SizedBox(height: 8),
                  ImageManager(
                    urls: [if (cover != null && cover!.isNotEmpty) cover!],
                    maxImages: 1,
                    media: sl<MediaService>(),
                    picker: sl<ImagePicker>(),
                    onChanged: (urls) =>
                        setLocal(() => cover = urls.isEmpty ? '' : urls.first),
                  ),
                ] else
                  const Padding(
                    padding: EdgeInsets.only(top: 12),
                    child: Text('You can add a cover image right after creating it.',
                        style: TextStyle(fontSize: 12, color: Colors.black54)),
                  ),
              ],
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
            FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: Text(existing == null ? 'Create' : 'Save'),
            ),
          ],
        ),
      ),
    );

    if (ok != true) return;
    if (existing == null) {
      if (name.text.trim().isNotEmpty) {
        bloc.add(CatalogCreateRequested(name.text.trim()));
      }
    } else {
      bloc.add(CatalogUpdateRequested(
        existing.id,
        displayName: name.text.trim().isEmpty ? null : name.text.trim(),
        coverUrl: cover,
      ));
    }
  }
}

class _CatalogCard extends StatelessWidget {
  const _CatalogCard({required this.catalog, required this.onEdit});

  final Catalog catalog;
  final ValueChanged<Catalog> onEdit;

  @override
  Widget build(BuildContext context) {
    final media = sl<MediaService>();
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      child: InkWell(
        borderRadius: BorderRadius.circular(14),
        onTap: () => context.go('/catalog/${catalog.id}'),
        child: Padding(
          padding: const EdgeInsets.all(10),
          child: Row(
            children: [
              ClipRRect(
                borderRadius: BorderRadius.circular(10),
                child: catalog.coverUrl == null || catalog.coverUrl!.isEmpty
                    ? Container(
                        width: 64,
                        height: 64,
                        color: Theme.of(context).colorScheme.primaryContainer,
                        child: const Icon(Icons.menu_book, size: 26),
                      )
                    : Image.network(
                        media.absolute(catalog.coverUrl!),
                        width: 64,
                        height: 64,
                        fit: BoxFit.cover,
                        errorBuilder: (_, __, ___) => Container(
                          width: 64,
                          height: 64,
                          color: Colors.black12,
                          child: const Icon(Icons.broken_image_outlined),
                        ),
                      ),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(catalog.displayName,
                        style:
                            const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
                    const SizedBox(height: 4),
                    Text('version ${catalog.version}',
                        style: const TextStyle(fontSize: 12, color: Colors.black54)),
                  ],
                ),
              ),
              StatusChip(catalog.status),
              const SizedBox(width: 8),
              if (catalog.status == 'DRAFT')
                FilledButton.tonal(
                  onPressed: () => context
                      .read<CatalogsBloc>()
                      .add(CatalogPublishRequested(catalog.id)),
                  child: const Text('Publish'),
                ),
              IconButton(
                tooltip: 'Edit name & cover',
                icon: const Icon(Icons.edit_outlined, size: 20),
                onPressed: () => onEdit(catalog),
              ),
              const Icon(Icons.chevron_right),
            ],
          ),
        ),
      ),
    );
  }
}
