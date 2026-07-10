import '../../domain/entities/member.dart';

class MemberModel {
  static Member fromJson(Map<String, dynamic> json) => Member(
        userId: json['user'] as String? ?? '',
        status: json['status'] as String? ?? '',
        email: json['email'] as String?,
        displayName: json['displayName'] as String?,
        kind: json['kind'] as String?,
        userStatus: json['userStatus'] as String?,
      );
}
