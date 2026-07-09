import '../entities/client.dart';
import '../repositories/client_repository.dart';

class ListClients {
  const ListClients(this._repo);
  final ClientRepository _repo;
  Future<List<Client>> call() => _repo.list();
}

class RegisterServiceAccount {
  const RegisterServiceAccount(this._repo);
  final ClientRepository _repo;
  Future<void> call(String clientId, String secret, List<String> audiences, List<String> scopes) =>
      _repo.registerServiceAccount(clientId, secret, audiences, scopes);
}

class RotateClientSecret {
  const RotateClientSecret(this._repo);
  final ClientRepository _repo;
  Future<void> call(String clientId, String secret) => _repo.rotateSecret(clientId, secret);
}
