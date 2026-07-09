import '../../domain/entities/decision.dart';

class DecisionModel {
  static Decision fromJson(Map<String, dynamic> json) => Decision(
        permit: (json['decision'] as String?) == 'PERMIT',
        userSafeReason: json['userSafeReason'] as String?,
        auditReason: json['auditReason'] as String?,
      );
}
