import '../../domain/entities/group.dart';

class GroupModel {
  static Group fromJson(Map<String, dynamic> json) =>
      Group(id: json['id'] as String, name: json['name'] as String? ?? '');
}
