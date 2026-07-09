import '../entities/client.dart';

abstract class ClientRepository {
  Future<List<Client>> list();
  Future<void> registerServiceAccount(
      String clientId, String secret, List<String> audiences, List<String> scopes);
  Future<void> rotateSecret(String clientId, String secret);
}
