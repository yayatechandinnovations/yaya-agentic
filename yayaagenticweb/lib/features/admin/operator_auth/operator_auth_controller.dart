import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../models/admin/operator_auth_strategies.dart';

/// Screen-level state for Settings → Operator authentication.
/// One Notifier owns everything: the current persisted config, the
/// in-flight loading flag, error toasts, and the most recent test
/// (probe) result.
class OperatorAuthState {
  const OperatorAuthState({
    this.data,
    this.loading = false,
    this.savingBootstrap = false,
    this.savingDelegate = false,
    this.testing = false,
    this.error,
    this.notice,
    this.lastProbe,
  });

  final StrategiesResponse? data;
  final bool loading;
  final bool savingBootstrap;
  final bool savingDelegate;
  final bool testing;

  /// Error toast — cleared on the next action.
  final String? error;

  /// Transient success message ("Bootstrap password updated").
  final String? notice;

  /// Most recent test-delegate response, shown in the modal.
  final ProbeResult? lastProbe;

  OperatorAuthState copyWith({
    StrategiesResponse? data,
    bool? loading,
    bool? savingBootstrap,
    bool? savingDelegate,
    bool? testing,
    String? error,
    bool clearError = false,
    String? notice,
    bool clearNotice = false,
    ProbeResult? lastProbe,
    bool clearLastProbe = false,
  }) =>
      OperatorAuthState(
        data: data ?? this.data,
        loading: loading ?? this.loading,
        savingBootstrap: savingBootstrap ?? this.savingBootstrap,
        savingDelegate: savingDelegate ?? this.savingDelegate,
        testing: testing ?? this.testing,
        error: clearError ? null : (error ?? this.error),
        notice: clearNotice ? null : (notice ?? this.notice),
        lastProbe: clearLastProbe ? null : (lastProbe ?? this.lastProbe),
      );
}

class OperatorAuthController extends Notifier<OperatorAuthState> {
  @override
  OperatorAuthState build() {
    Future.microtask(load);
    return const OperatorAuthState(loading: true);
  }

  Future<void> load() async {
    state = state.copyWith(loading: true, clearError: true);
    try {
      final api = await ref.read(adminApiProvider.future);
      final data = await api.getStrategies();
      state = state.copyWith(data: data, loading: false);
    } catch (e) {
      state = state.copyWith(loading: false, error: _msg(e));
    }
  }

  Future<void> saveBootstrap({bool? enabled, String? newPassword}) async {
    state = state.copyWith(savingBootstrap: true, clearError: true, clearNotice: true);
    try {
      final api = await ref.read(adminApiProvider.future);
      final updated = await api.putBootstrapStrategy(
        enabled: enabled,
        newPassword: newPassword,
      );
      state = state.copyWith(
        data: updated,
        savingBootstrap: false,
        notice: newPassword != null && newPassword.isNotEmpty
            ? 'Bootstrap password updated.'
            : 'Bootstrap settings saved.',
      );
    } catch (e) {
      state = state.copyWith(savingBootstrap: false, error: _msg(e));
    }
  }

  Future<void> saveDelegate(
    DelegateView delegate, {
    String? sharedSecret,
    bool confirmPermissive = false,
  }) async {
    state = state.copyWith(savingDelegate: true, clearError: true, clearNotice: true);
    try {
      final api = await ref.read(adminApiProvider.future);
      final updated = await api.putDelegateStrategy(
        delegate,
        sharedSecret: sharedSecret,
        confirmPermissive: confirmPermissive,
      );
      state = state.copyWith(
        data: updated,
        savingDelegate: false,
        notice: 'HTTP delegate settings saved.',
      );
    } catch (e) {
      state = state.copyWith(savingDelegate: false, error: _msg(e));
    }
  }

  Future<void> runTest({required String username, required String password}) async {
    state = state.copyWith(testing: true, clearError: true, clearLastProbe: true);
    try {
      final api = await ref.read(adminApiProvider.future);
      final probe = await api.testDelegate(username: username, password: password);
      state = state.copyWith(testing: false, lastProbe: probe);
    } catch (e) {
      state = state.copyWith(testing: false, error: _msg(e));
    }
  }

  void dismissNotice() => state = state.copyWith(clearNotice: true);
  void dismissError() => state = state.copyWith(clearError: true);
  void dismissProbe() => state = state.copyWith(clearLastProbe: true);

  String _msg(Object e) {
    if (e is DioException) {
      final data = e.response?.data;
      if (data is Map && data['message'] is String) return data['message'] as String;
      return e.message ?? 'Request failed';
    }
    return e.toString();
  }
}

final operatorAuthProvider =
    NotifierProvider<OperatorAuthController, OperatorAuthState>(OperatorAuthController.new);
