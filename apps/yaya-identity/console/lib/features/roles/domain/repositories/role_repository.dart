import '../entities/role.dart';
import '../entities/role_assignment_request.dart';

abstract class RoleRepository {
  Future<List<Role>> list(String org);
  Future<Role> create(String org, String name, List<String> permissions);
  Future<void> assign(RoleAssignmentRequest request);
}
