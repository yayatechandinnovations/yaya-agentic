import '../../../users/domain/entities/member.dart';
import '../entities/group.dart';

abstract class GroupRepository {
  Future<List<Group>> list(String org);
  Future<Group> create(String org, String name);
  Future<List<Member>> members(String groupId);
  Future<void> addMember(String groupId, String userId);
  Future<void> removeMember(String groupId, String userId);
}
