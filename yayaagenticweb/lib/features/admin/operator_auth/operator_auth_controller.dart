import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/live/live_resource.dart';
import '../../../app/live/resource_kind.dart';
import '../../../models/admin/operator_auth_strategies.dart';

/// Current operator-login configuration (bootstrap + HTTP delegate).
final operatorAuthStrategiesProvider = FutureProvider<StrategiesResponse>((ref) async {
  liveResource(ref, AdminResourceKind.operatorAuth);
  final api = await ref.watch(adminApiProvider.future);
  return api.getStrategies();
});

/// The writes, each refreshing the shared view. Errors propagate so the
/// caller's form can put them next to the field.
class OperatorAuthActions {
  OperatorAuthActions(this.ref);
  final Ref ref;

  Future<StrategiesResponse> saveBootstrap({bool? enabled, String? newPassword}) async {
    final api = await ref.read(adminApiProvider.future);
    final updated = await api.putBootstrapStrategy(enabled: enabled, newPassword: newPassword);
    ref.invalidate(operatorAuthStrategiesProvider);
    return updated;
  }

  Future<StrategiesResponse> saveDelegate(
    DelegateView delegate, {
    String? sharedSecret,
    bool confirmPermissive = false,
  }) async {
    final api = await ref.read(adminApiProvider.future);
    final updated = await api.putDelegateStrategy(
      delegate,
      sharedSecret: sharedSecret,
      confirmPermissive: confirmPermissive,
    );
    ref.invalidate(operatorAuthStrategiesProvider);
    return updated;
  }

  /// Probes the SAVED delegate config with these credentials.
  Future<ProbeResult> test({required String username, required String password}) async {
    final api = await ref.read(adminApiProvider.future);
    return api.testDelegate(username: username, password: password);
  }
}

final operatorAuthActionsProvider = Provider<OperatorAuthActions>((ref) => OperatorAuthActions(ref));
