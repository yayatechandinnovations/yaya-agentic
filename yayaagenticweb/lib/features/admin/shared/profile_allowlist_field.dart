import 'package:flutter/material.dart';

import '../../../models/admin/profile.dart';

/// Multi-select for a resolver profile-override allowlist. Renders one
/// checkable chip per distinct published profile *key* so operators pick from
/// the real set instead of typing keys by hand. An empty selection means "any
/// published profile is allowed" (the resolver is tenant-trusted).
///
/// A currently-selected key that is no longer among the published profiles
/// (unpublished / renamed) is still shown as a checked chip flagged "not
/// published", so editing a binding never silently drops it.
class ProfileAllowlistField extends StatelessWidget {
  const ProfileAllowlistField({
    super.key,
    required this.profiles,
    required this.selected,
    required this.onChanged,
  });

  /// The tenant's published profiles (may contain several versions per key).
  final List<ProfileResponse> profiles;

  /// Currently-allowlisted profile keys.
  final List<String> selected;

  final ValueChanged<List<String>> onChanged;

  @override
  Widget build(BuildContext context) {
    // Distinct by key, keeping a readable label; keys the operator already
    // selected but which aren't in the list are appended so they stay visible.
    final labels = <String, String>{};
    for (final p in profiles) {
      labels.putIfAbsent(p.id, () => p.displayName.isEmpty ? p.id : p.displayName);
    }
    final keys = <String>[
      ...labels.keys,
      ...selected.where((k) => !labels.containsKey(k)),
    ];

    void toggle(String key, bool on) {
      final next = [...selected];
      if (on) {
        if (!next.contains(key)) next.add(key);
      } else {
        next.remove(key);
      }
      onChanged(next);
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Allowed override profiles',
            style: Theme.of(context).textTheme.bodyMedium),
        const SizedBox(height: 2),
        Text(
          selected.isEmpty
              ? 'None selected — any published profile is allowed.'
              : 'The resolver may only override into the ${selected.length} '
                  'selected profile${selected.length == 1 ? '' : 's'}.',
          style: Theme.of(context).textTheme.bodySmall?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant),
        ),
        const SizedBox(height: 8),
        if (keys.isEmpty)
          Text('No profiles published yet.',
              style: Theme.of(context).textTheme.bodySmall)
        else
          Wrap(
            spacing: 8,
            runSpacing: 4,
            children: [
              for (final key in keys)
                FilterChip(
                  selected: selected.contains(key),
                  label: Text(labels.containsKey(key)
                      ? labels[key]!
                      : '$key (not published)'),
                  onSelected: (on) => toggle(key, on),
                ),
            ],
          ),
      ],
    );
  }
}
