import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../features/admin/tenants/tenants_screen.dart' show tenantsProvider;
import '../models/admin/tenant.dart';

/// Single source of truth for "which tenant is the admin console operating
/// against right now." Drives every list provider and every create form.
///
/// Resolution rules (build):
/// 1. If shared_preferences holds a tenant id that still exists in the
///    tenants list and isn't archived, use it.
/// 2. Else, prefer 'default' if it exists and is active.
/// 3. Else, the first non-archived tenant.
/// 4. Else, null — the UI shows a "register a tenant first" banner.
///
/// The selection persists across reloads via shared_preferences. Changing
/// it invalidates every consumer that watches it, which is exactly the
/// invalidation cascade we want: a tenant switch refreshes every list.
class SelectedTenantController extends AsyncNotifier<String?> {
  static const _prefsKey = 'yaya.selectedTenant';

  @override
  Future<String?> build() async {
    final prefs = await SharedPreferences.getInstance();
    final stored = prefs.getString(_prefsKey);

    // We watch the tenants list — when it loads or refreshes, we may
    // resolve the default tenant for the first time.
    final tenants = await ref.watch(tenantsProvider.future);
    final active = tenants
        .where((t) => t.status != 'ARCHIVED')
        .toList();
    if (active.isEmpty) return null;

    if (stored != null && active.any((t) => t.id == stored)) {
      return stored;
    }
    final preferred = active.firstWhere(
      (t) => t.id == 'default' && t.status == 'ACTIVE',
      orElse: () => active.first,
    );
    // Don't write back to prefs on auto-select — that's reserved for an
    // operator choice, so we don't sticky-pin a random default that the
    // operator never explicitly picked.
    return preferred.id;
  }

  /// Operator-initiated selection. Persists across reloads.
  Future<void> select(String tenantId) async {
    state = AsyncValue.data(tenantId);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_prefsKey, tenantId);
  }
}

final selectedTenantProvider =
    AsyncNotifierProvider<SelectedTenantController, String?>(
        SelectedTenantController.new);

/// Synchronous read used by list providers — folds AsyncValue into a String?
/// so the consumers can do `final tenant = ref.watch(currentTenantOrNull);`.
final currentTenantOrNull = Provider<String?>((ref) {
  return ref.watch(selectedTenantProvider).valueOrNull;
});

/// Convenience: the tenant row itself (when one is selected and present).
final currentTenantProvider = Provider<TenantResponse?>((ref) {
  final id = ref.watch(currentTenantOrNull);
  if (id == null) return null;
  final tenants = ref.watch(tenantsProvider).valueOrNull;
  if (tenants == null) return null;
  for (final t in tenants) {
    if (t.id == id) return t;
  }
  return null;
});
