import '../../domain/entities/policy.dart';

class PolicyModel {
  static Policy fromJson(Map<String, dynamic> json) => Policy(
        id: json['id'] as String? ?? '',
        name: json['name'] as String? ?? '',
        effect: json['effect'] as String? ?? '',
        priority: (json['priority'] as num?)?.toInt() ?? 0,
        active: json['active'] as bool? ?? true,
        targetJson: json['targetJson'] as String?,
        conditionJson: json['conditionJson'] as String?,
        userReason: json['userReason'] as String?,
        auditReason: json['auditReason'] as String?,
      );
}
