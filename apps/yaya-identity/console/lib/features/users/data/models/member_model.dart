import '../../domain/entities/member.dart';

class MemberModel {
  static Member fromJson(Map<String, dynamic> json) => Member(
        userId: json['user'] as String? ?? '',
        status: json['status'] as String? ?? '',
      );
}
