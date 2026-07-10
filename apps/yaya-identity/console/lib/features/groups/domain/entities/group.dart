import 'package:equatable/equatable.dart';

class Group extends Equatable {
  const Group({required this.id, required this.name, this.memberCount = 0});

  final String id;
  final String name;
  final int memberCount;

  @override
  List<Object?> get props => [id, name, memberCount];
}
