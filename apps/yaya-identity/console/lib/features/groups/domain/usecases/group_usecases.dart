import '../../../users/domain/entities/member.dart';
import '../entities/group.dart';
import '../repositories/group_repository.dart';

class ListGroups {
  const ListGroups(this._repo);
  final GroupRepository _repo;
  Future<List<Group>> call(String org) => _repo.list(org);
}

class CreateGroup {
  const CreateGroup(this._repo);
  final GroupRepository _repo;
  Future<Group> call(String org, String name) => _repo.create(org, name);
}

class ListGroupMembers {
  const ListGroupMembers(this._repo);
  final GroupRepository _repo;
  Future<List<Member>> call(String groupId) => _repo.members(groupId);
}

class AddGroupMember {
  const AddGroupMember(this._repo);
  final GroupRepository _repo;
  Future<void> call(String groupId, String userId) => _repo.addMember(groupId, userId);
}

class RemoveGroupMember {
  const RemoveGroupMember(this._repo);
  final GroupRepository _repo;
  Future<void> call(String groupId, String userId) => _repo.removeMember(groupId, userId);
}
