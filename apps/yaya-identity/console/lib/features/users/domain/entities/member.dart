import 'package:equatable/equatable.dart';

/// A member of an org — the person behind the membership, not just an id.
class Member extends Equatable {
  const Member({
    required this.userId,
    required this.status,
    this.email,
    this.displayName,
    this.kind,
    this.userStatus,
  });

  final String userId;
  final String status;
  final String? email;
  final String? displayName;
  final String? kind;
  final String? userStatus;

  /// Best human-readable handle for pickers and lists.
  String get label {
    if ((displayName ?? '').isNotEmpty) return displayName!;
    if ((email ?? '').isNotEmpty) return email!;
    return userId;
  }

  String get initials {
    final source = label.trim();
    if (source.isEmpty) return '?';
    final parts = source.split(RegExp(r'\s+'));
    if (parts.length >= 2 && parts[0].isNotEmpty && parts[1].isNotEmpty) {
      return (parts[0][0] + parts[1][0]).toUpperCase();
    }
    return source[0].toUpperCase();
  }

  @override
  List<Object?> get props => [userId, status, email, displayName, kind, userStatus];
}
