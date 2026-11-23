import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../../app/selected_tenant.dart';
import '../../../models/admin/workspace.dart';
import '../data/workspaces_api.dart';
import '../../../app/live/live_resource.dart';

/// Caller's workspace memberships. Auto-refreshed when the user creates
/// or archives a workspace (the create-success path calls
/// `ref.invalidate(myWorkspacesProvider)`).
final myWorkspacesProvider =
    FutureProvider<List<WorkspaceListItem>>((ref) async {
  liveRefresh(ref);
  final api = await ref.watch(workspacesApiProvider.future);
  return api.listMine();
});

/// "Which workspace am I currently working in." Single source of truth
/// for the workspace switcher; bridged into [selectedTenantProvider] via
/// [workspaceToTenantBridgeProvider] so legacy admin screens re-fetch
/// when the user switches.
///
/// Resolution rules (build):
///   1. shared_preferences holds a code that the user has membership in
///   2. else: the first non-archived workspace in the list
///   3. else: null (UI shows the "create your first workspace" empty state)
class CurrentWorkspaceController extends AsyncNotifier<String?> {
  static const _prefsKey = 'yaya.currentWorkspace';

  @override
  Future<String?> build() async {
    final prefs = await SharedPreferences.getInstance();
    final stored = prefs.getString(_prefsKey);
    final list = await ref.watch(myWorkspacesProvider.future);
    final active =
        list.where((w) => w.workspace.status != 'ARCHIVED').toList();
    if (active.isEmpty) return null;
    if (stored != null && active.any((w) => w.workspace.code == stored)) {
      return stored;
    }
    return active.first.workspace.code;
  }

  /// User-initiated switch. Persists across reloads; the tenant-bridge
  /// provider picks up the change and cascades through every legacy
  /// `currentTenantOrNull`-watching feature provider.
  Future<void> switchTo(String code) async {
    state = AsyncValue.data(code);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_prefsKey, code);
  }
}

final currentWorkspaceProvider =
    AsyncNotifierProvider<CurrentWorkspaceController, String?>(
        CurrentWorkspaceController.new);

/// Sync read for downstream widgets — folds the AsyncValue so
/// consumers can `final code = ref.watch(currentWorkspaceOrNull);`.
final currentWorkspaceOrNull = Provider<String?>((ref) {
  return ref.watch(currentWorkspaceProvider).valueOrNull;
});

/// Convenience: the currently-selected membership (workspace + role) row.
final currentMembershipProvider = Provider<WorkspaceListItem?>((ref) {
  final code = ref.watch(currentWorkspaceOrNull);
  if (code == null) return null;
  final list = ref.watch(myWorkspacesProvider).valueOrNull;
  if (list == null) return null;
  for (final m in list) {
    if (m.workspace.code == code) return m;
  }
  return null;
});

/// One-way bridge: whenever the chosen workspace code changes, push the
/// same code into the legacy [selectedTenantProvider] so admin feature
/// providers (`profilesProvider`, `toolsProvider`, …) — which watch
/// `currentTenantOrNull` — re-fetch in step.
///
/// Started by the app shell on first build via `ref.watch`. Doing the
/// bridge here rather than inside `CurrentWorkspaceController.build()`
/// keeps the notifier pure and avoids cascading invalidation loops.
final workspaceToTenantBridgeProvider = Provider<void>((ref) {
  ref.listen<AsyncValue<String?>>(currentWorkspaceProvider, (_, next) {
    final code = next.valueOrNull;
    if (code == null || code.isEmpty) return;
    ref.read(selectedTenantProvider.notifier).select(code);
  }, fireImmediately: true);
});
