import 'package:flutter/material.dart';

import '../../features/modules/domain/entities/permission_module.dart';
import '../theme/app_theme.dart';

/// A module-grouped multi-select over the permission catalog. Shows each
/// module (`app.module`) as a labeled row of action chips — used by the role
/// composer and the policy builder so permissions are always picked in the
/// vocabulary the operator defined on the Modules screen.
class PermissionPicker extends StatelessWidget {
  const PermissionPicker({
    super.key,
    required this.catalog,
    required this.selected,
    required this.onChanged,
    this.maxHeight = 280,
  });

  final List<String> catalog;
  final Set<String> selected;
  final void Function(Set<String>) onChanged;
  final double maxHeight;

  @override
  Widget build(BuildContext context) {
    final modules = PermissionModule.fromCatalog(catalog);
    if (modules.isEmpty) {
      return const Text(
        'No modules declared yet — define them on the Modules screen first.',
        style: TextStyle(color: Colors.black54, fontSize: 13),
      );
    }
    return ConstrainedBox(
      constraints: BoxConstraints(maxHeight: maxHeight),
      child: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            for (final m in modules) ...[
              Padding(
                padding: const EdgeInsets.only(top: 10, bottom: 6),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.widgets_outlined, size: 14, color: AppBrand.violet),
                    const SizedBox(width: 6),
                    Text('${m.app} · ${m.module}',
                        style: const TextStyle(
                            fontSize: 12.5,
                            fontWeight: FontWeight.w600,
                            color: Colors.black87)),
                    const SizedBox(width: 8),
                    TextButton(
                      style: TextButton.styleFrom(
                          visualDensity: VisualDensity.compact,
                          padding: const EdgeInsets.symmetric(horizontal: 6)),
                      onPressed: () {
                        final all = m.actions.map(m.key).toSet();
                        final next = {...selected};
                        if (next.containsAll(all)) {
                          next.removeAll(all);
                        } else {
                          next.addAll(all);
                        }
                        onChanged(next);
                      },
                      child: const Text('all', style: TextStyle(fontSize: 11)),
                    ),
                  ],
                ),
              ),
              Wrap(
                spacing: 6,
                runSpacing: 6,
                children: m.actions.map((a) {
                  final key = m.key(a);
                  return FilterChip(
                    label: Text(a, style: const TextStyle(fontSize: 12)),
                    tooltip: key,
                    visualDensity: VisualDensity.compact,
                    selected: selected.contains(key),
                    onSelected: (on) {
                      final next = {...selected};
                      if (on) {
                        next.add(key);
                      } else {
                        next.remove(key);
                      }
                      onChanged(next);
                    },
                  );
                }).toList(),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
