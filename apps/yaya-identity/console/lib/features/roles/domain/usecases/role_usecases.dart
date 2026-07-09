import '../entities/role.dart';
import '../entities/role_assignment_request.dart';
import '../repositories/role_repository.dart';

class ListRoles {
  const ListRoles(this._repo);
  final RoleRepository _repo;
  Future<List<Role>> call(String org) => _repo.list(org);
}

class CreateRole {
  const CreateRole(this._repo);
  final RoleRepository _repo;
  Future<Role> call(String org, String name, List<String> permissions) =>
      _repo.create(org, name, permissions);
}

class AssignRole {
  const AssignRole(this._repo);
  final RoleRepository _repo;
  Future<void> call(RoleAssignmentRequest request) => _repo.assign(request);
}
