/// Pure role-tier logic for the API-keys UI, mirroring backend `Role`. Kept
/// dependency-free (no dio/dart:html) so it is unit-testable on the Dart VM
/// without dragging in the browser HTTP client.
///
/// Used for the create-role dropdown bound (a creator may mint at or below
/// their own tier, §6.2) and to gate the create/revoke affordances to ADMIN+.
library;

const _roleTier = <String, int>{
  'VIEWER': 0,
  'MEMBER': 1,
  'ADMIN': 2,
  'OWNER': 3,
  'PLATFORM_ADMIN': 1 << 30,
};

int roleTier(String role) => _roleTier[role] ?? -1;

bool roleAtLeast(String role, String floor) => roleTier(role) >= roleTier(floor);

/// Roles a creator at [creatorRole] may mint, highest-first. Caps at OWNER —
/// PLATFORM_ADMIN creators still only mint workspace tiers.
List<String> mintableRoles(String creatorRole) {
  const ordered = ['OWNER', 'ADMIN', 'MEMBER', 'VIEWER'];
  final cap = creatorRole == 'PLATFORM_ADMIN'
      ? roleTier('OWNER')
      : roleTier(creatorRole);
  return ordered.where((r) => roleTier(r) <= cap).toList();
}
