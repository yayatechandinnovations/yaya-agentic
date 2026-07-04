import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'flows_admin_screen.dart' show startFlowsProvider;

/// Reusable dropdown for binding a Session Start Flow to a channel / profile.
/// Lists the tenant's flows; `null` == no flow. Used by the chat entry point,
/// WhatsApp sender, and profile editors.
class StartFlowPicker extends ConsumerWidget {
  const StartFlowPicker({
    super.key,
    required this.tenant,
    required this.value,
    required this.onChanged,
    this.label = 'Start flow (optional)',
  });

  final String tenant;
  final String? value;
  final ValueChanged<String?> onChanged;
  final String label;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(startFlowsProvider(tenant));
    final ids = (async.valueOrNull ?? const []).map((f) => f.id).toSet().toList()..sort();
    final selectable = {if (value != null && value!.isNotEmpty) value!, ...ids}.toList();
    return DropdownButtonFormField<String?>(
      initialValue: (value != null && value!.isEmpty) ? null : value,
      isExpanded: true,
      decoration: InputDecoration(
        labelText: label,
        border: const OutlineInputBorder(),
        isDense: true,
        helperText: 'Runs deterministically when a conversation starts here.',
      ),
      items: [
        const DropdownMenuItem<String?>(value: null, child: Text('None')),
        for (final id in selectable) DropdownMenuItem<String?>(value: id, child: Text(id)),
      ],
      onChanged: onChanged,
    );
  }
}
