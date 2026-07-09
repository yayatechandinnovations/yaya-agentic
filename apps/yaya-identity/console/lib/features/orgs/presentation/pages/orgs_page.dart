import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../bloc/orgs_bloc.dart';

/// Organizations list + create. The route provides the [OrgsBloc]; this widget
/// only reads state and dispatches events.
class OrgsPage extends StatelessWidget {
  const OrgsPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Organizations')),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _promptCreate(context),
        icon: const Icon(Icons.add),
        label: const Text('New org'),
      ),
      body: BlocBuilder<OrgsBloc, OrgsState>(
        builder: (context, state) {
          switch (state.status) {
            case OrgsStatus.initial:
            case OrgsStatus.loading:
              return const Center(child: CircularProgressIndicator());
            case OrgsStatus.failure:
              return Center(
                child: Semantics(liveRegion: true, child: Text(state.error ?? 'Failed to load')),
              );
            case OrgsStatus.loaded:
              if (state.orgs.isEmpty) {
                return const Center(child: Text('No organizations yet.'));
              }
              return ListView.separated(
                itemCount: state.orgs.length,
                separatorBuilder: (_, __) => const Divider(height: 1),
                itemBuilder: (_, i) {
                  final org = state.orgs[i];
                  return ListTile(
                    leading: const Icon(Icons.apartment),
                    title: Text(org.name),
                    subtitle: Text(org.slug),
                    trailing: org.mfaRequired
                        ? const Chip(label: Text('MFA'), avatar: Icon(Icons.shield, size: 16))
                        : null,
                  );
                },
              );
          }
        },
      ),
    );
  }

  Future<void> _promptCreate(BuildContext context) async {
    final controller = TextEditingController();
    final bloc = context.read<OrgsBloc>();
    final name = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('New organization'),
        content: TextField(
          controller: controller,
          autofocus: true,
          decoration: const InputDecoration(labelText: 'Name'),
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
      bloc.add(OrgCreateRequested(name));
    }
  }
}
