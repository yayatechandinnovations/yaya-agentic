import 'package:flutter/material.dart';

import '../../../ui/data/y_badge.dart';
import '../../../ui/form/y_fields.dart';

/// Role pill for tables and headers. The word is always visible; colour is
/// a second signal only.
class RoleChip extends StatelessWidget {
  const RoleChip(this.role, {super.key, this.dense = false});
  final String role;

  /// Kept for callers that used the compact variant; the badge has one size.
  final bool dense;

  @override
  Widget build(BuildContext context) {
    final (label, tone, icon) = switch (role) {
      'OWNER' => ('Owner', YTone.brand, Icons.workspace_premium_outlined),
      'ADMIN' => ('Admin', YTone.info, Icons.admin_panel_settings_outlined),
      'MEMBER' => ('Member', YTone.success, Icons.edit_outlined),
      'VIEWER' => ('Viewer', YTone.neutral, Icons.visibility_outlined),
      'PLATFORM_ADMIN' => ('Platform admin', YTone.danger, Icons.shield_outlined),
      _ => (role, YTone.neutral, null),
    };
    return YBadge(label, tone: tone, icon: icon);
  }
}

/// What each workspace role can do — one line each, shown on role pickers.
const roleSummaries = <String, String>{
  'OWNER': 'Everything an admin can do, plus manage other owners and archive the workspace.',
  'ADMIN': 'Manage members, invitations, API keys, secrets and auth bindings, plus everything a member can do.',
  'MEMBER': 'Build and change profiles, capabilities, tools and knowledge sources.',
  'VIEWER': 'Read-only: can look at everything in the workspace but change nothing.',
};

String roleLabel(String role) => switch (role) {
      'OWNER' => 'Owner',
      'ADMIN' => 'Admin',
      'MEMBER' => 'Member',
      'VIEWER' => 'Viewer',
      'PLATFORM_ADMIN' => 'Platform admin',
      _ => role,
    };

/// Radio-card options for [roles] (highest first), with what each can do.
List<YEnumOption<String>> roleOptions(Iterable<String> roles) => [
      for (final r in roles)
        YEnumOption<String>(
          r,
          roleLabel(r),
          wire: r,
          description: roleSummaries[r],
          caution: r == 'OWNER',
        ),
    ];

/// "an owner", "a member" — for sentences.
String roleWithArticle(String role) {
  final l = roleLabel(role).toLowerCase();
  return '${'aeiou'.contains(l[0]) ? 'an' : 'a'} $l';
}

const _tier = {'VIEWER': 0, 'MEMBER': 1, 'ADMIN': 2, 'OWNER': 3, 'PLATFORM_ADMIN': 4};

/// [actor] holds at least [floor] (PLATFORM_ADMIN clears everything).
bool roleAtLeastTier(String actor, String floor) => (_tier[actor] ?? -1) >= (_tier[floor] ?? 0);
