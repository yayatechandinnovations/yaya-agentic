import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:yaya_sales_console/core/error/failure.dart';
import 'package:yaya_sales_console/features/stores/domain/entities/store_entities.dart';
import 'package:yaya_sales_console/features/stores/domain/repositories/store_repository.dart';
import 'package:yaya_sales_console/features/stores/domain/usecases/store_usecases.dart';
import 'package:yaya_sales_console/features/stores/presentation/bloc/store_picker_bloc.dart';

class MockStoreRepository extends Mock implements StoreRepository {}

void main() {
  late MockStoreRepository repo;

  StorePickerBloc build() =>
      StorePickerBloc(listStores: ListStores(repo), createStore: CreateStore(repo));

  setUp(() => repo = MockStoreRepository());

  const policies = StorePolicies(
    deductionPoint: 'ON_PAYMENT',
    backorder: 'DENY',
    softHoldTtlSeconds: 600,
    checkoutHoldTtlSeconds: 1800,
  );
  const stores = [
    Store(
      id: 'store_1',
      tenantId: 'tenant_1',
      displayName: 'Colmado Ana',
      defaultCurrency: 'DOP',
      policies: policies,
    ),
  ];

  group('StorePickerBloc', () {
    blocTest<StorePickerBloc, StorePickerState>(
      'tenant submitted → loading, loaded with the tenant stores',
      setUp: () =>
          when(() => repo.storesOf('tenant_1')).thenAnswer((_) async => stores),
      build: build,
      act: (b) => b.add(const StorePickerTenantSubmitted('tenant_1')),
      expect: () => [
        const StorePickerState(status: StorePickerStatus.loading, tenantId: 'tenant_1'),
        const StorePickerState(
            status: StorePickerStatus.loaded, tenantId: 'tenant_1', stores: stores),
      ],
    );

    blocTest<StorePickerBloc, StorePickerState>(
      'tenant submitted failure → failure state with the message',
      setUp: () => when(() => repo.storesOf(any()))
          .thenThrow(const NetworkFailure('cannot reach yaya-sales')),
      build: build,
      act: (b) => b.add(const StorePickerTenantSubmitted('tenant_1')),
      expect: () => [
        const StorePickerState(status: StorePickerStatus.loading, tenantId: 'tenant_1'),
        const StorePickerState(
          status: StorePickerStatus.failure,
          tenantId: 'tenant_1',
          error: 'cannot reach yaya-sales',
        ),
      ],
    );

    blocTest<StorePickerBloc, StorePickerState>(
      'create store creates then reloads the tenant stores',
      setUp: () {
        when(() => repo.createStore('tenant_1', 'New Store', 'USD'))
            .thenAnswer((_) async => stores.first);
        when(() => repo.storesOf('tenant_1')).thenAnswer((_) async => stores);
      },
      build: build,
      seed: () =>
          const StorePickerState(status: StorePickerStatus.loaded, tenantId: 'tenant_1'),
      act: (b) => b.add(const StoreCreateRequested('New Store', 'USD')),
      expect: () => [
        const StorePickerState(status: StorePickerStatus.loading, tenantId: 'tenant_1'),
        const StorePickerState(
            status: StorePickerStatus.loaded, tenantId: 'tenant_1', stores: stores),
      ],
      verify: (_) =>
          verify(() => repo.createStore('tenant_1', 'New Store', 'USD')).called(1),
    );

    blocTest<StorePickerBloc, StorePickerState>(
      'create store failure surfaces the error',
      setUp: () => when(() => repo.createStore(any(), any(), any()))
          .thenThrow(const ServerFailure('currency must be an ISO-4217 code')),
      build: build,
      seed: () =>
          const StorePickerState(status: StorePickerStatus.loaded, tenantId: 'tenant_1'),
      act: (b) => b.add(const StoreCreateRequested('New Store', 'XX')),
      expect: () => [
        const StorePickerState(
          status: StorePickerStatus.failure,
          tenantId: 'tenant_1',
          error: 'currency must be an ISO-4217 code',
        ),
      ],
    );
  });
}
