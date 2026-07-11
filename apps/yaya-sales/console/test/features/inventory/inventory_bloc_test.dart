import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:yaya_sales_console/core/error/failure.dart';
import 'package:yaya_sales_console/features/inventory/domain/entities/inventory_entities.dart';
import 'package:yaya_sales_console/features/inventory/domain/repositories/inventory_repository.dart';
import 'package:yaya_sales_console/features/inventory/domain/usecases/inventory_usecases.dart';
import 'package:yaya_sales_console/features/inventory/presentation/bloc/inventory_bloc.dart';
import 'package:yaya_sales_console/features/stores/domain/entities/store_entities.dart';
import 'package:yaya_sales_console/features/stores/domain/repositories/store_repository.dart';
import 'package:yaya_sales_console/features/stores/domain/usecases/store_usecases.dart';

class MockInventoryRepository extends Mock implements InventoryRepository {}

class MockStoreRepository extends Mock implements StoreRepository {}

void main() {
  late MockInventoryRepository repo;
  late MockStoreRepository stores;

  InventoryBloc build() => InventoryBloc(
        listLevels: ListLevels(repo),
        readLedger: ReadLedger(repo),
        receiveStock: ReceiveStock(repo),
        adjustStock: AdjustStock(repo),
        transferStock: TransferStock(repo),
        setReorder: SetReorderPolicy(repo),
        setBackorder: SetBackorderPolicy(repo),
        listLocations: ListLocations(stores),
      );

  setUp(() {
    repo = MockInventoryRepository();
    stores = MockStoreRepository();
    when(() => stores.locations()).thenAnswer((_) async => []);
  });

  const levels = [
    StockLevel(
      sku: 'SKU-1',
      locationId: 'loc_1',
      onHand: 10,
      reserved: 3,
      reorderPoint: 5,
      reorderQty: 20,
      backorder: 'DENY',
    ),
  ];

  group('InventoryBloc', () {
    blocTest<InventoryBloc, InventoryState>(
      'LevelsRequested → loading, loaded (available = onHand − reserved)',
      setUp: () => when(() => repo.levels(location: null, lowOnly: false))
          .thenAnswer((_) async => levels),
      build: build,
      act: (b) => b.add(const LevelsRequested()),
      expect: () => [
        const InventoryState(status: InventoryStatus.loading),
        const InventoryState(status: InventoryStatus.loaded, levels: levels),
      ],
      verify: (b) => expect(b.state.levels.first.available, 7),
    );

    blocTest<InventoryBloc, InventoryState>(
      'LevelsRequested loads the location picker options alongside the levels',
      setUp: () {
        when(() => repo.levels(location: null, lowOnly: false))
            .thenAnswer((_) async => levels);
        when(() => stores.locations()).thenAnswer((_) async => const [
              StoreLocation(id: 'loc_1', label: 'Main', kind: 'WAREHOUSE', sellable: true),
            ]);
      },
      build: build,
      act: (b) => b.add(const LevelsRequested()),
      expect: () => [
        const InventoryState(status: InventoryStatus.loading),
        const InventoryState(
          status: InventoryStatus.loaded,
          levels: levels,
          locations: [
            StoreLocation(id: 'loc_1', label: 'Main', kind: 'WAREHOUSE', sellable: true),
          ],
        ),
      ],
    );

    blocTest<InventoryBloc, InventoryState>(
      'LevelsRequested passes the location filter and low-only flag',
      setUp: () => when(() => repo.levels(location: 'loc_1', lowOnly: true))
          .thenAnswer((_) async => levels),
      build: build,
      act: (b) => b.add(const LevelsRequested(location: 'loc_1', lowOnly: true)),
      expect: () => [
        const InventoryState(
            status: InventoryStatus.loading, locationFilter: 'loc_1', lowOnly: true),
        const InventoryState(
          status: InventoryStatus.loaded,
          levels: levels,
          locationFilter: 'loc_1',
          lowOnly: true,
        ),
      ],
      verify: (_) =>
          verify(() => repo.levels(location: 'loc_1', lowOnly: true)).called(1),
    );

    blocTest<InventoryBloc, InventoryState>(
      'ReceiveRequested posts a costed receipt then refreshes the levels',
      setUp: () {
        when(() => repo.receive('SKU-1', 'loc_1', 5, 1999, 'USD', null))
            .thenAnswer((_) async {});
        when(() => repo.levels(location: null, lowOnly: false))
            .thenAnswer((_) async => levels);
      },
      build: build,
      act: (b) => b.add(const ReceiveRequested(
        sku: 'SKU-1',
        location: 'loc_1',
        qty: 5,
        unitCostMinor: 1999,
        currency: 'USD',
      )),
      expect: () => [
        const InventoryState(status: InventoryStatus.loading),
        const InventoryState(status: InventoryStatus.loaded, levels: levels),
      ],
      verify: (_) =>
          verify(() => repo.receive('SKU-1', 'loc_1', 5, 1999, 'USD', null)).called(1),
    );

    blocTest<InventoryBloc, InventoryState>(
      'AdjustRequested posts the delta with its reason then refreshes',
      setUp: () {
        when(() => repo.adjust('SKU-1', 'loc_1', -2, 'damaged in storage'))
            .thenAnswer((_) async {});
        when(() => repo.levels(location: null, lowOnly: false))
            .thenAnswer((_) async => levels);
      },
      build: build,
      act: (b) => b.add(const AdjustRequested(
        sku: 'SKU-1',
        location: 'loc_1',
        delta: -2,
        reason: 'damaged in storage',
      )),
      expect: () => [
        const InventoryState(status: InventoryStatus.loading),
        const InventoryState(status: InventoryStatus.loaded, levels: levels),
      ],
      verify: (_) =>
          verify(() => repo.adjust('SKU-1', 'loc_1', -2, 'damaged in storage')).called(1),
    );

    blocTest<InventoryBloc, InventoryState>(
      'TransferRequested failure surfaces the error and does not refresh',
      setUp: () => when(() => repo.transfer(any(), any(), any(), any()))
          .thenThrow(const ServerFailure('insufficient stock at source')),
      build: build,
      act: (b) => b.add(const TransferRequested(
        sku: 'SKU-1',
        from: 'loc_1',
        to: 'loc_2',
        qty: 99,
      )),
      expect: () => [
        const InventoryState(
            status: InventoryStatus.failure, error: 'insufficient stock at source'),
      ],
      verify: (_) => verifyNever(() => repo.levels(
          location: any(named: 'location'), lowOnly: any(named: 'lowOnly'))),
    );

    blocTest<InventoryBloc, InventoryState>(
      'LedgerRequested loads the movement history for the SKU',
      setUp: () => when(() => repo.ledger('SKU-1', location: 'loc_1'))
          .thenAnswer((_) async => const [
                Movement(
                  id: 'mov_1',
                  sku: 'SKU-1',
                  locationId: 'loc_1',
                  qtyDelta: 5,
                  kind: 'RECEIPT',
                ),
              ]),
      build: build,
      act: (b) => b.add(const LedgerRequested('SKU-1', location: 'loc_1')),
      expect: () => [
        const InventoryState(
          ledgerSku: 'SKU-1',
          ledger: [
            Movement(
              id: 'mov_1',
              sku: 'SKU-1',
              locationId: 'loc_1',
              qtyDelta: 5,
              kind: 'RECEIPT',
            ),
          ],
        ),
      ],
    );
  });
}
