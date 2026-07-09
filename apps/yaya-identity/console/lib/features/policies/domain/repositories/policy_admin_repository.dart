import '../entities/policy.dart';
import '../entities/policy_draft.dart';

abstract class PolicyAdminRepository {
  Future<List<Policy>> list(String org);
  Future<Policy> create(PolicyDraft draft);
}
