import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:yaya_identity_console/features/clients/domain/entities/client.dart';
import 'package:yaya_identity_console/features/clients/domain/repositories/client_repository.dart';
import 'package:yaya_identity_console/features/clients/domain/usecases/client_usecases.dart';
import 'package:yaya_identity_console/features/clients/presentation/bloc/clients_bloc.dart';

class MockClientRepository extends Mock implements ClientRepository {}

void main() {
  late MockClientRepository repo;

  ClientsBloc build() => ClientsBloc(
        listClients: ListClients(repo),
        registerServiceAccount: RegisterServiceAccount(repo),
        rotateClientSecret: RotateClientSecret(repo),
      );

  setUp(() => repo = MockClientRepository());

  const clients = [Client(clientId: 'yaya-agentic', type: 'SERVICE', audiences: ['yaya-sales'])];

  blocTest<ClientsBloc, ClientsState>(
    'ClientsRequested → loading, loaded',
    setUp: () => when(repo.list).thenAnswer((_) async => clients),
    build: build,
    act: (b) => b.add(const ClientsRequested()),
    expect: () => [
      const ClientsState(status: ClientsStatus.loading),
      const ClientsState(status: ClientsStatus.loaded, clients: clients),
    ],
  );

  blocTest<ClientsBloc, ClientsState>(
    'ClientRotateRequested reports success',
    setUp: () => when(() => repo.rotateSecret(any(), any())).thenAnswer((_) async {}),
    build: build,
    act: (b) => b.add(const ClientRotateRequested('yaya-agentic', 'new-secret')),
    expect: () => [
      isA<ClientsState>().having((s) => s.message, 'message', 'Secret rotated for yaya-agentic.'),
    ],
    verify: (_) => verify(() => repo.rotateSecret('yaya-agentic', 'new-secret')).called(1),
  );

  blocTest<ClientsBloc, ClientsState>(
    'ClientRegisterRequested registers then refreshes',
    setUp: () {
      when(() => repo.registerServiceAccount(any(), any(), any(), any())).thenAnswer((_) async {});
      when(repo.list).thenAnswer((_) async => clients);
    },
    build: build,
    act: (b) => b.add(const ClientRegisterRequested('svc', 's3cr3t', ['yaya-sales'], ['identity.pdp'])),
    expect: () => [
      const ClientsState(status: ClientsStatus.loading),
      const ClientsState(status: ClientsStatus.loaded, clients: clients),
    ],
  );
}
