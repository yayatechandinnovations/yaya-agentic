import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../models/admin/api_key.dart';
import '../data/api_keys_api.dart';

export 'api_key_roles.dart' show roleTier, roleAtLeast, mintableRoles;

/// Keys for one workspace (keyed by code). Re-fetched when the workspace
/// switches (the screen watches `currentWorkspaceOrNull`) and after any
/// mutation via [ApiKeysController].
final apiKeysProvider =
    FutureProvider.family<List<ApiKey>, String>((ref, workspaceCode) async {
  final api = await ref.watch(apiKeysApiProvider.future);
  return api.list(workspaceCode);
});

/// Recent audit events for one key. Keyed by (workspaceCode, keyId).
final apiKeyAuditProvider = FutureProvider.family<List<ApiKeyAuditEvent>,
    (String, String)>((ref, key) async {
  final api = await ref.watch(apiKeysApiProvider.future);
  return api.audit(key.$1, key.$2);
});

/// Mutating calls, each invalidation-aware so the list refreshes without the
/// callsite re-implementing it.
class ApiKeysController {
  ApiKeysController(this.ref);
  final Ref ref;

  /// Mint a key. Returns the one-time secret payload for the show-once panel.
  Future<ApiKeyCreated> create({
    required String workspaceCode,
    required String name,
    required String role,
    String? expiresAt,
  }) async {
    final api = await ref.read(apiKeysApiProvider.future);
    final created = await api.create(workspaceCode,
        name: name, role: role, expiresAt: expiresAt);
    ref.invalidate(apiKeysProvider(workspaceCode));
    return created;
  }

  Future<void> rename({
    required String workspaceCode,
    required String keyId,
    required String name,
  }) async {
    final api = await ref.read(apiKeysApiProvider.future);
    await api.rename(workspaceCode, keyId, name: name);
    ref.invalidate(apiKeysProvider(workspaceCode));
  }

  Future<void> revoke({
    required String workspaceCode,
    required String keyId,
  }) async {
    final api = await ref.read(apiKeysApiProvider.future);
    await api.revoke(workspaceCode, keyId);
    ref.invalidate(apiKeysProvider(workspaceCode));
    ref.invalidate(apiKeyAuditProvider((workspaceCode, keyId)));
  }
}

final apiKeysControllerProvider =
    Provider<ApiKeysController>((ref) => ApiKeysController(ref));
