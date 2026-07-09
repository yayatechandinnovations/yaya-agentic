import '../entities/group.dart';

abstract class GroupRepository {
  Future<List<Group>> list(String org);
  Future<Group> create(String org, String name);
  Future<void> addMember(String groupId, String userId);
}
