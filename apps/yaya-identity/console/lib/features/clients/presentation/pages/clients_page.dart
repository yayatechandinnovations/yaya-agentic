import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../domain/entities/client.dart';
import '../bloc/clients_bloc.dart';

/// Clients & service accounts — list, register a service account (client-creds +
/// audience binding), and rotate a secret.
class ClientsPage extends StatelessWidget {
  const ClientsPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Clients & service accounts')),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _promptRegister(context),
        icon: const Icon(Icons.add),
        label: const Text('Service account'),
      ),
      body: BlocConsumer<ClientsBloc, ClientsState>(
        listenWhen: (p, c) => c.message != null && p.message != c.message,
        listener: (context, state) =>
            ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(state.message!))),
        builder: (context, state) => switch (state.status) {
          ClientsStatus.initial => const Center(child: Text('Load registered clients.')),
          ClientsStatus.loading => const Center(child: CircularProgressIndicator()),
          ClientsStatus.failure =>
            Center(child: Semantics(liveRegion: true, child: Text(state.error ?? 'Failed'))),
          ClientsStatus.loaded => state.clients.isEmpty
              ? const Center(child: Text('No clients registered.'))
              : ListView.separated(
                  itemCount: state.clients.length,
                  separatorBuilder: (_, __) => const Divider(height: 1),
                  itemBuilder: (_, i) => _ClientTile(client: state.clients[i]),
                ),
        },
      ),
    );
  }

  Future<void> _promptRegister(BuildContext context) async {
    final id = TextEditingController();
    final secret = TextEditingController();
    final audiences = TextEditingController();
    final scopes = TextEditingController();
    final bloc = context.read<ClientsBloc>();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Register service account'),
        content: Column(mainAxisSize: MainAxisSize.min, children: [
          TextField(controller: id, decoration: const InputDecoration(labelText: 'Client id')),
          TextField(controller: secret, decoration: const InputDecoration(labelText: 'Secret')),
          TextField(controller: audiences, decoration: const InputDecoration(
              labelText: 'Audiences (comma-separated)', hintText: 'yaya-sales')),
          TextField(controller: scopes, decoration: const InputDecoration(
              labelText: 'Scopes (comma-separated)', hintText: 'identity.pdp')),
        ]),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Register')),
        ],
      ),
    );
    if (ok == true && id.text.trim().isNotEmpty) {
      bloc.add(ClientRegisterRequested(
        id.text.trim(),
        secret.text.trim(),
        _split(audiences.text),
        _split(scopes.text),
      ));
    }
  }

  static List<String> _split(String s) =>
      s.split(',').map((e) => e.trim()).where((e) => e.isNotEmpty).toList();
}

class _ClientTile extends StatelessWidget {
  const _ClientTile({required this.client});

  final Client client;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      leading: Icon(client.type == 'SERVICE' ? Icons.smart_toy : Icons.web),
      title: Text(client.clientId),
      subtitle: Text([client.type, if (client.audiences.isNotEmpty) 'aud=${client.audiences.join(',')}'].join('   ')),
      trailing: client.type == 'SERVICE'
          ? TextButton.icon(
              icon: const Icon(Icons.autorenew),
              label: const Text('Rotate'),
              onPressed: () => _promptRotate(context, client.clientId),
            )
          : null,
    );
  }

  Future<void> _promptRotate(BuildContext context, String clientId) async {
    final secret = TextEditingController();
    final bloc = context.read<ClientsBloc>();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Rotate secret · $clientId'),
        content: TextField(controller: secret, decoration: const InputDecoration(labelText: 'New secret')),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Rotate')),
        ],
      ),
    );
    if (ok == true && secret.text.trim().isNotEmpty) {
      bloc.add(ClientRotateRequested(clientId, secret.text.trim()));
    }
  }
}
