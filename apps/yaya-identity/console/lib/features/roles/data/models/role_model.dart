import '../../domain/entities/role.dart';

class RoleModel {
  static Role fromJson(Map<String, dynamic> json) =>
      Role(id: json['id'] as String, name: json['name'] as String? ?? '');
}
