import '../entities/member.dart';

abstract class UserRepository {
  Future<List<Member>> members(String org);
  Future<void> lock(String userId);
  Future<void> disable(String userId);
  Future<void> delete(String userId);

  /// Invite by email; returns the one-time invitation token to deliver.
  Future<String> invite(String org, String email);
}
