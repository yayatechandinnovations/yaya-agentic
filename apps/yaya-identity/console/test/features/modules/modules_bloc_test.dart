import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:yaya_identity_console/core/error/failure.dart';
import 'package:yaya_identity_console/features/modules/domain/entities/permission_module.dart';
import 'package:yaya_identity_console/features/modules/domain/repositories/module_repository.dart';
import 'package:yaya_identity_console/features/modules/domain/usecases/module_usecases.dart';
import 'package:yaya_identity_console/features/modules/presentation/bloc/modules_bloc.dart';

class MockModuleRepository extends Mock implements ModuleRepository {}

void main() {
  late MockModuleRepository repo;

  ModulesBloc build() =>
      ModulesBloc(loadModules: LoadModules(repo), declareModule: DeclareModule(repo));

  setUp(() => repo = MockModuleRepository());

  group('PermissionModule.fromCatalog', () {
    test('groups keys by app.module and sorts', () {
      final modules = PermissionModule.fromCatalog([
        'sales.users:update',
        'sales.orders:refund',
        'sales.users:read',
        'billing.invoices:read',
      ]);
      expect(modules.map((m) => '${m.app}.${m.module}'),
          ['billing.invoices', 'sales.orders', 'sales.users']);
      expect(modules.last.actions, ['read', 'update']);
    });

    test('ignores malformed keys instead of crashing', () {
      expect(PermissionModule.fromCatalog(['garbage', 'no-colon.here']), isEmpty);
    });
  });

  blocTest<ModulesBloc, ModulesState>(
    'ModulesRequested groups the catalog into modules',
    setUp: () => when(repo.catalog).thenAnswer(
        (_) async => ['sales.users:read', 'sales.users:update', 'sales.orders:refund']),
    build: build,
    act: (b) => b.add(const ModulesRequested()),
    expect: () => [
      const ModulesState(status: ModulesStatus.loading),
      isA<ModulesState>()
          .having((s) => s.status, 'status', ModulesStatus.loaded)
          .having((s) => s.modules.length, 'modules', 2)
          .having((s) => s.modules.last.actions, 'users actions', ['read', 'update']),
    ],
  );

  blocTest<ModulesBloc, ModulesState>(
    'ModuleDeclareRequested composes full keys and reloads',
    setUp: () {
      when(() => repo.declare(any(), any())).thenAnswer((_) async {});
      when(repo.catalog).thenAnswer((_) async => ['sales.users:read']);
    },
    build: build,
    act: (b) => b.add(const ModuleDeclareRequested('sales', 'users', ['read'])),
    expect: () => [
      isA<ModulesState>().having((s) => s.message, 'message', contains('sales.users')),
      isA<ModulesState>().having((s) => s.status, 'status', ModulesStatus.loading),
      isA<ModulesState>().having((s) => s.status, 'status', ModulesStatus.loaded),
    ],
    verify: (_) => verify(() => repo.declare('sales', ['sales.users:read'])).called(1),
  );

  blocTest<ModulesBloc, ModulesState>(
    'declare failure is surfaced',
    setUp: () =>
        when(() => repo.declare(any(), any())).thenThrow(const ServerFailure('nope')),
    build: build,
    act: (b) => b.add(const ModuleDeclareRequested('sales', 'users', ['read'])),
    expect: () =>
        [isA<ModulesState>().having((s) => s.error, 'error', 'nope')],
  );
}
