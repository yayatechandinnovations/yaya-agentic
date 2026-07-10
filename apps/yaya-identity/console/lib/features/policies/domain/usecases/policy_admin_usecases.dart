import '../entities/policy.dart';
import '../entities/policy_draft.dart';
import '../repositories/policy_admin_repository.dart';

class ListPolicies {
  const ListPolicies(this._repo);
  final PolicyAdminRepository _repo;
  Future<List<Policy>> call(String org) => _repo.list(org);
}

class CreatePolicy {
  const CreatePolicy(this._repo);
  final PolicyAdminRepository _repo;
  Future<Policy> call(PolicyDraft draft) => _repo.create(draft);
}

class SetPolicyActive {
  const SetPolicyActive(this._repo);
  final PolicyAdminRepository _repo;
  Future<Policy> call(String org, String policyId, bool active) =>
      _repo.setActive(org, policyId, active);
}
