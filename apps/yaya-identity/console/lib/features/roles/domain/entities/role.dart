import 'package:equatable/equatable.dart';

class Role extends Equatable {
  const Role({required this.id, required this.name});

  final String id;
  final String name;

  @override
  List<Object?> get props => [id, name];
}
