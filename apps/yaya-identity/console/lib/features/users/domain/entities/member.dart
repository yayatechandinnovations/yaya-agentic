import 'package:equatable/equatable.dart';

/// A member of an org — the subject id and membership status.
class Member extends Equatable {
  const Member({required this.userId, required this.status});

  final String userId;
  final String status;

  @override
  List<Object?> get props => [userId, status];
}
