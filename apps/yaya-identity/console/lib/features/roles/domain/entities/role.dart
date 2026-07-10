import 'package:equatable/equatable.dart';

class Role extends Equatable {
  const Role({
    required this.id,
    required this.name,
    this.system = false,
    this.permissions = const [],
  });

  final String id;
  final String name;

  /// Built-in platform roles (owner/admin/member) — assignable but not editable.
  final bool system;

  final List<String> permissions;

  @override
  List<Object?> get props => [id, name, system, permissions];
}
