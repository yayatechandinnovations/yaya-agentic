import '../entities/psp_entities.dart';
import '../repositories/psp_repository.dart';

class ListPspAccounts {
  const ListPspAccounts(this._repo);
  final PspRepository _repo;
  Future<List<PspAccount>> call() => _repo.status();
}

class BeginPspOnboarding {
  const BeginPspOnboarding(this._repo);
  final PspRepository _repo;
  Future<OnboardingLink> call(String provider, String returnUrl) =>
      _repo.onboardingLink(provider, returnUrl);
}

class ReconcilePspReturn {
  const ReconcilePspReturn(this._repo);
  final PspRepository _repo;
  Future<PspAccount> call(String provider, Map<String, dynamic> params) =>
      _repo.reconcileReturn(provider, params);
}

class DisconnectPsp {
  const DisconnectPsp(this._repo);
  final PspRepository _repo;
  Future<PspAccount> call(String provider) => _repo.disconnect(provider);
}
