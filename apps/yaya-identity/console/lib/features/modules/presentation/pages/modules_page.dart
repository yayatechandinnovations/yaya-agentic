import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../core/theme/app_theme.dart';
import '../../domain/entities/permission_module.dart';
import '../bloc/modules_bloc.dart';

/// Permission modules — the vocabulary of authorization. An operator defines a
/// module (e.g. app `sales`, module `users`) and its actions (read, update, …);
/// those become the permission keys that role composition, policy targets and
/// the simulator pick from. Declaring is idempotent, platform-wide (not
/// org-scoped), and keys are never silently deleted — roles may reference them.
class ModulesPage extends StatelessWidget {
  const ModulesPage({super.key});

  static final _segment = RegExp(r'^[a-z0-9_-]+$');

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Permission modules')),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _promptDeclare(context),
        icon: const Icon(Icons.add),
        label: const Text('New module'),
      ),
      body: BlocConsumer<ModulesBloc, ModulesState>(
        listenWhen: (p, c) => c.message != null && p.message != c.message,
        listener: (context, state) => ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(state.message!))),
        builder: (context, state) {
          switch (state.status) {
            case ModulesStatus.initial:
            case ModulesStatus.loading:
              return const Center(child: CircularProgressIndicator());
            case ModulesStatus.failure:
              return Center(
                  child: Semantics(liveRegion: true, child: Text(state.error ?? 'Failed')));
            case ModulesStatus.loaded:
              if (state.modules.isEmpty) {
                return Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(Icons.widgets_outlined, size: 48, color: Colors.black26),
                      const SizedBox(height: 12),
                      const Text('No modules yet.'),
                      const SizedBox(height: 4),
                      const Text(
                        'Define a module and its actions — roles and policies pick from them.',
                        style: TextStyle(color: Colors.black54, fontSize: 13),
                      ),
                      const SizedBox(height: 16),
                      FilledButton.icon(
                        onPressed: () => _promptDeclare(context),
                        icon: const Icon(Icons.add),
                        label: const Text('Create the first module'),
                      ),
                    ],
                  ),
                );
              }
              return ListView.builder(
                padding: const EdgeInsets.fromLTRB(24, 8, 24, 88),
                itemCount: state.modules.length,
                itemBuilder: (_, i) => _ModuleCard(module: state.modules[i]),
              );
          }
        },
      ),
    );
  }

  /// Create a module or add actions to an existing one — the same declare call.
  static Future<void> _promptDeclare(BuildContext context, {PermissionModule? existing}) async {
    final bloc = context.read<ModulesBloc>();
    final knownApps = bloc.state.modules.map((m) => m.app).toSet().toList()..sort();
    final app = TextEditingController(text: existing?.app ?? (knownApps.length == 1 ? knownApps.first : ''));
    final module = TextEditingController(text: existing?.module ?? '');
    final customAction = TextEditingController();
    final formKey = GlobalKey<FormState>();
    const presets = ['read', 'list', 'create', 'update', 'delete', 'approve', 'export'];
    final already = existing?.actions.toSet() ?? const <String>{};
    final selected = <String>{};
    final custom = <String>[];

    String? segmentError(String? v, String what) {
      final t = (v ?? '').trim();
      if (t.isEmpty) return '$what is required';
      if (!_segment.hasMatch(t)) return 'lower-case letters, digits, - or _ only';
      return null;
    }

    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setLocal) => AlertDialog(
          title: Text(existing == null
              ? 'New module'
              : 'Add actions to ${existing.app}.${existing.module}'),
          content: SizedBox(
            width: 460,
            child: Form(
              key: formKey,
              child: SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(children: [
                      Expanded(
                        child: TextFormField(
                          controller: app,
                          enabled: existing == null,
                          decoration: InputDecoration(
                            labelText: 'Application',
                            hintText: knownApps.isEmpty ? 'sales' : knownApps.join(', '),
                          ),
                          validator: (v) => segmentError(v, 'Application'),
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: TextFormField(
                          controller: module,
                          enabled: existing == null,
                          autofocus: existing == null,
                          decoration: const InputDecoration(
                              labelText: 'Module', hintText: 'users, orders, …'),
                          validator: (v) => segmentError(v, 'Module'),
                        ),
                      ),
                    ]),
                    const SizedBox(height: 16),
                    const Text('Actions', style: TextStyle(fontWeight: FontWeight.w600)),
                    const SizedBox(height: 8),
                    Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: [
                        for (final a in presets)
                          FilterChip(
                            label: Text(a),
                            selected: selected.contains(a) || already.contains(a),
                            onSelected: already.contains(a)
                                ? null // declared already — shown, not removable here
                                : (on) => setLocal(() {
                                      if (on) {
                                        selected.add(a);
                                      } else {
                                        selected.remove(a);
                                      }
                                    }),
                          ),
                        for (final a in custom)
                          InputChip(
                            label: Text(a),
                            onDeleted: () => setLocal(() => custom.remove(a)),
                          ),
                      ],
                    ),
                    const SizedBox(height: 12),
                    Row(children: [
                      Expanded(
                        child: TextField(
                          controller: customAction,
                          decoration: const InputDecoration(
                            labelText: 'Custom action',
                            hintText: 'collect-cod, assign, …',
                          ),
                          onSubmitted: (_) => _addCustom(customAction, custom, already, setLocal),
                        ),
                      ),
                      const SizedBox(width: 8),
                      IconButton.filledTonal(
                        icon: const Icon(Icons.add),
                        onPressed: () => _addCustom(customAction, custom, already, setLocal),
                      ),
                    ]),
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
              child: Text(existing == null ? 'Create module' : 'Add actions'),
            ),
          ],
        ),
      ),
    );

    final actions = [...selected, ...custom];
    if (ok == true && actions.isNotEmpty) {
      bloc.add(ModuleDeclareRequested(app.text.trim(), module.text.trim(), actions));
    }
  }

  static void _addCustom(TextEditingController field, List<String> custom,
      Set<String> already, void Function(void Function()) setLocal) {
    final value = field.text.trim();
    if (value.isEmpty || !_segment.hasMatch(value)) return;
    if (custom.contains(value) || already.contains(value)) return;
    setLocal(() {
      custom.add(value);
      field.clear();
    });
  }
}

class _ModuleCard extends StatelessWidget {
  const _ModuleCard({required this.module});

  final PermissionModule module;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 12, 12),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              width: 36,
              height: 36,
              decoration: BoxDecoration(
                gradient: AppBrand.gradient,
                borderRadius: BorderRadius.circular(9),
              ),
              child: const Icon(Icons.widgets_outlined, color: Colors.white, size: 20),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(children: [
                    Text(module.module,
                        style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
                    const SizedBox(width: 8),
                    Text(module.app,
                        style: const TextStyle(fontSize: 12, color: Colors.black45)),
                  ]),
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 6,
                    runSpacing: 6,
                    children: module.actions
                        .map((a) => Tooltip(
                              message: module.key(a),
                              child: Chip(
                                label: Text(a, style: const TextStyle(fontSize: 11)),
                                visualDensity: VisualDensity.compact,
                              ),
                            ))
                        .toList(),
                  ),
                ],
              ),
            ),
            TextButton.icon(
              icon: const Icon(Icons.add, size: 18),
              label: const Text('Add actions'),
              onPressed: () => ModulesPage._promptDeclare(context, existing: module),
            ),
          ],
        ),
      ),
    );
  }
}
