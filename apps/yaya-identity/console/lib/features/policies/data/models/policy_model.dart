import '../../domain/entities/policy.dart';

class PolicyModel {
  static Policy fromJson(Map<String, dynamic> json) => Policy(
        id: json['id'] as String? ?? '',
        name: json['name'] as String? ?? '',
        effect: json['effect'] as String? ?? '',
        priority: (json['priority'] as num?)?.toInt() ?? 0,
      );
}
