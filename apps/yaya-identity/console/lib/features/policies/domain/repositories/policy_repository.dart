import '../entities/decision.dart';
import '../entities/decision_query.dart';

abstract class PolicyRepository {
  /// Dry-run a decision (persists nothing).
  Future<Decision> simulate(DecisionQuery query);
}
