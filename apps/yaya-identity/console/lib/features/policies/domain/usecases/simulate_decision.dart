import '../entities/decision.dart';
import '../entities/decision_query.dart';
import '../repositories/policy_repository.dart';

class SimulateDecision {
  const SimulateDecision(this._repo);
  final PolicyRepository _repo;
  Future<Decision> call(DecisionQuery query) => _repo.simulate(query);
}
