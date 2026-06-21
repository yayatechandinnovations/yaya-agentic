import 'package:flutter/material.dart';

/// Compact role pill used in the workspaces grid, the switcher dropdown,
/// and (eventually) the members table. Color is never the only signal —
/// the label is always visible (UI-UX §10 accessibility row).
class RoleChip extends StatelessWidget {
  const RoleChip(this.role, {super.key, this.dense = false});
  final String role;
  final bool dense;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final (bg, fg, label) = _styleFor(theme, role);
    final pad = dense
        ? const EdgeInsets.symmetric(horizontal: 6, vertical: 2)
        : const EdgeInsets.symmetric(horizontal: 8, vertical: 3);
    return Container(
      padding: pad,
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(99),
        border: Border.all(color: fg.withValues(alpha: 0.25)),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: fg,
          fontSize: dense ? 10 : 11,
          fontWeight: FontWeight.w600,
          letterSpacing: 0.4,
        ),
      ),
    );
  }

  static (Color bg, Color fg, String label) _styleFor(ThemeData t, String role) {
    final c = t.colorScheme;
    switch (role) {
      case 'OWNER':
        return (c.primaryContainer, c.onPrimaryContainer, 'OWNER');
      case 'ADMIN':
        return (c.secondaryContainer, c.onSecondaryContainer, 'ADMIN');
      case 'MEMBER':
        return (c.tertiaryContainer, c.onTertiaryContainer, 'MEMBER');
      case 'VIEWER':
        return (c.surfaceContainerHigh, c.onSurfaceVariant, 'VIEWER');
      case 'PLATFORM_ADMIN':
        return (c.errorContainer, c.onErrorContainer, 'PLATFORM ADMIN');
      default:
        return (c.surfaceContainerHigh, c.onSurfaceVariant, role);
    }
  }
}
