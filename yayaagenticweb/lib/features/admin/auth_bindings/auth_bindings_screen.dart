import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../models/admin/auth_binding.dart';
import '../profiles/profiles_screen.dart' show authBindingsProvider;
import '../shared/admin_shared.dart';

final authAvailabilityProvider = FutureProvider<AuthAvailability>((ref) async {
  final api = await ref.watch(adminApiProvider.future);
  return api.availableAuth();
});

class AuthBindingsScreen extends ConsumerStatefulWidget {
  const AuthBindingsScreen({super.key});
  @override
  ConsumerState<AuthBindingsScreen> createState() => _AuthBindingsScreenState();
}

class _AuthBindingsScreenState extends ConsumerState<AuthBindingsScreen> {
  final _idCtrl = TextEditingController();
  String? _authenticatorRef;
  final _chainSelected = <String>{};
  bool _saving = false;

  @override
  void dispose() {
    _idCtrl.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_idCtrl.text.trim().isEmpty || _authenticatorRef == null) {
      showSnack(context, 'id and authenticator are required', error: true);
      return;
    }
    setState(() => _saving = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.createAuthBinding(AuthBindingRequest(
        tenant: 'default',
        id: _idCtrl.text.trim(),
        authenticatorRef: _authenticatorRef!,
        authorizerChain: _chainSelected.toList(),
      ));
      ref.invalidate(authBindingsProvider);
      if (!mounted) return;
      _idCtrl.clear();
      setState(() {
        _authenticatorRef = null;
        _chainSelected.clear();
      });
      showSnack(context, 'binding saved');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final bindings = ref.watch(authBindingsProvider);
    final avail = ref.watch(authAvailabilityProvider);

    return AdminTwoPane(
      title: 'Auth bindings',
      subtitle: 'Selects which Authenticator + Authorizer chain a profile uses.',
      list: bindings.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Text(formatError(e), style: TextStyle(color: Theme.of(context).colorScheme.error)),
        data: (list) => list.isEmpty
            ? const Center(child: Text('No bindings yet.'))
            : ListView.separated(
                itemBuilder: (_, i) => _Tile(b: list[i]),
                separatorBuilder: (_, __) => const SizedBox(height: 8),
                itemCount: list.length,
              ),
      ),
      form: SingleChildScrollView(
        child: FormCard(
          title: 'New / upsert auth binding',
          child: avail.when(
            loading: () => const LinearProgressIndicator(),
            error: (e, _) => Text(formatError(e),
                style: TextStyle(color: Theme.of(context).colorScheme.error)),
            data: (data) => Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                TextField(controller: _idCtrl, decoration: const InputDecoration(labelText: 'binding id')),
                const SizedBox(height: 12),
                DropdownButtonFormField<String>(
                  initialValue: _authenticatorRef,
                  decoration: const InputDecoration(labelText: 'authenticator'),
                  items: data.authenticators
                      .map((a) => DropdownMenuItem(value: a, child: Text(a)))
                      .toList(),
                  onChanged: (v) => setState(() => _authenticatorRef = v),
                ),
                const SizedBox(height: 16),
                Text('Authorizer chain (order = priority)',
                    style: Theme.of(context).textTheme.labelLarge),
                const SizedBox(height: 6),
                Wrap(
                  spacing: 6,
                  runSpacing: 4,
                  children: data.authorizers
                      .map((a) => FilterChip(
                            label: Text(a),
                            selected: _chainSelected.contains(a),
                            onSelected: (sel) => setState(() {
                              if (sel) {
                                _chainSelected.add(a);
                              } else {
                                _chainSelected.remove(a);
                              }
                            }),
                          ))
                      .toList(),
                ),
              ],
            ),
          ),
          actions: [
            FilledButton(
              onPressed: _saving ? null : _save,
              child: _saving
                  ? const SizedBox.square(dimension: 16, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Text('Save'),
            ),
          ],
        ),
      ),
    );
  }
}

class _Tile extends StatelessWidget {
  const _Tile({required this.b});
  final AuthBindingResponse b;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: ListTile(
        title: Text(b.id),
        subtitle: Text('authenticator: ${b.authenticatorRef}'
            '${b.authorizerChain.isEmpty ? "" : "\nchain: ${b.authorizerChain.join(" → ")}"}'),
        isThreeLine: b.authorizerChain.isNotEmpty,
      ),
    );
  }
}
