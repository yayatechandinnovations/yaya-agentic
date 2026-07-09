import 'package:equatable/equatable.dart';

class Policy extends Equatable {
  const Policy({required this.id, required this.name, required this.effect, required this.priority});

  final String id;
  final String name;
  final String effect; // PERMIT | DENY
  final int priority;

  @override
  List<Object?> get props => [id, name, effect, priority];
}
