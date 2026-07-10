import '../entities/member.dart';
import '../repositories/user_repository.dart';

class ListMembers {
  const ListMembers(this._repo);
  final UserRepository _repo;
  Future<List<Member>> call(String org) => _repo.members(org);
}

class LockUser {
  const LockUser(this._repo);
  final UserRepository _repo;
  Future<void> call(String userId) => _repo.lock(userId);
}

class DisableUser {
  const DisableUser(this._repo);
  final UserRepository _repo;
  Future<void> call(String userId) => _repo.disable(userId);
}

class DeleteUser {
  const DeleteUser(this._repo);
  final UserRepository _repo;
  Future<void> call(String userId) => _repo.delete(userId);
}

class InviteUser {
  const InviteUser(this._repo);
  final UserRepository _repo;
  Future<String> call(String org, String email, {String? role}) =>
      _repo.invite(org, email, role);
}
