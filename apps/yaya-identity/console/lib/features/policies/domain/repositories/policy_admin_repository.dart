import '../entities/policy.dart';
import '../entities/policy_draft.dart';

abstract class PolicyAdminRepository {
  Future<List<Policy>> list(String org);
  Future<Policy> create(PolicyDraft draft);

  /// Enable/disable without deleting — the policy stays listed and auditable.
  Future<Policy> setActive(String org, String policyId, bool active);
}
