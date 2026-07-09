import '../entities/auth_session.dart';
import '../repositories/auth_repository.dart';

/// Thin use-cases — one intention each — so the BLoC depends on verbs, not on
/// the repository surface. Clean-architecture application layer.

class BeginLogin {
  const BeginLogin(this._repo);
  final AuthRepository _repo;
  Future<void> call() => _repo.beginLogin();
}

class CompleteLogin {
  const CompleteLogin(this._repo);
  final AuthRepository _repo;
  Future<AuthSession> call(String code) => _repo.completeLogin(code);
}

class LoadSession {
  const LoadSession(this._repo);
  final AuthRepository _repo;
  Future<AuthSession?> call() => _repo.currentSession();
}

class Logout {
  const Logout(this._repo);
  final AuthRepository _repo;
  Future<void> call() => _repo.logout();
}
