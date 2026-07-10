import '../../domain/entities/role.dart';

class RoleModel {
  static Role fromJson(Map<String, dynamic> json) => Role(
        id: json['id'] as String,
        name: json['name'] as String? ?? '',
        system: json['system'] as bool? ?? false,
        permissions: (json['permissions'] as List<dynamic>?)?.cast<String>() ?? const [],
      );
}
