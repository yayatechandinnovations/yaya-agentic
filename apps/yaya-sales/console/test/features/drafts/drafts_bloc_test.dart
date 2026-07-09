import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:yaya_sales_console/core/error/failure.dart';
import 'package:yaya_sales_console/features/catalog/domain/repositories/catalog_repository.dart';
import 'package:yaya_sales_console/features/catalog/domain/usecases/catalog_usecases.dart';
import 'package:yaya_sales_console/features/drafts/domain/entities/draft_entities.dart';
import 'package:yaya_sales_console/features/drafts/domain/repositories/draft_repository.dart';
import 'package:yaya_sales_console/features/drafts/domain/usecases/draft_usecases.dart';
import 'package:yaya_sales_console/features/drafts/presentation/bloc/drafts_bloc.dart';
import 'package:yaya_sales_console/features/orders/domain/entities/order_entities.dart';
import 'package:yaya_sales_console/features/orders/domain/repositories/order_repository.dart';
import 'package:yaya_sales_console/features/orders/domain/usecases/order_usecases.dart';

class MockDraftRepository extends Mock implements DraftRepository {}

class MockOrderRepository extends Mock implements OrderRepository {}

class MockCatalogRepository extends Mock implements CatalogRepository {}

void main() {
  late MockDraftRepository drafts;
  late MockOrderRepository orders;
  late MockCatalogRepository catalog;

  DraftsBloc build() => DraftsBloc(
        listOrders: ListOrders(orders),
        createDraft: CreateDraft(drafts),
        updateDraft: UpdateDraft(drafts),
        sendDraft: SendDraft(drafts),
        cancelOrder: CancelOrder(orders),
        listCatalogs: ListCatalogs(catalog),
        listProducts: ListProducts(catalog),
      );

  setUp(() {
    drafts = MockDraftRepository();
    orders = MockOrderRepository();
    catalog = MockCatalogRepository();
  });

  const draft = Order(
    id: 'order_1',
    status: 'DRAFT',
    paymentState: 'UNPAID',
    currency: 'USD',
  );

  group('DraftsBloc', () {
    blocTest<DraftsBloc, DraftsState>(
      'DraftsRequested lists orders with status=DRAFT',
      setUp: () =>
          when(() => orders.orders(status: 'DRAFT')).thenAnswer((_) async => [draft]),
      build: build,
      act: (b) => b.add(const DraftsRequested()),
      expect: () => [
        const DraftsState(status: DraftsStatus.loading),
        const DraftsState(status: DraftsStatus.loaded, drafts: [draft]),
      ],
      verify: (_) => verify(() => orders.orders(status: 'DRAFT')).called(1),
    );

    blocTest<DraftsBloc, DraftsState>(
      'create draft (with a one-off price override) then reloads the list',
      setUp: () {
        when(() => drafts.create(
              channel: any(named: 'channel'),
              regionId: any(named: 'regionId'),
              customerRef: any(named: 'customerRef'),
              note: any(named: 'note'),
              lines: any(named: 'lines'),
            )).thenAnswer((_) async => draft);
        when(() => orders.orders(status: 'DRAFT')).thenAnswer((_) async => [draft]);
      },
      build: build,
      act: (b) => b.add(const DraftCreateRequested(
        customerRef: 'cust_9',
        lines: [DraftLine(sku: 'SKU-1', qty: 2, unitPriceMinor: 1500)],
      )),
      expect: () => [
        const DraftsState(status: DraftsStatus.loading),
        const DraftsState(status: DraftsStatus.loaded, drafts: [draft]),
      ],
      verify: (_) => verify(() => drafts.create(
            channel: null,
            regionId: null,
            customerRef: 'cust_9',
            note: null,
            lines: const [DraftLine(sku: 'SKU-1', qty: 2, unitPriceMinor: 1500)],
          )).called(1),
    );

    blocTest<DraftsBloc, DraftsState>(
      'send with a PSP tender surfaces the hosted-URL outcome',
      setUp: () {
        when(() => drafts.send(
              'order_1',
              tender: 'PSP',
              applyStoreCredit: false,
              returnUrl: any(named: 'returnUrl'),
              customerRef: any(named: 'customerRef'),
            )).thenAnswer((_) async => const SendOutcome(
              status: 'AWAITING_PAYMENT',
              orderId: 'order_1',
              paymentUrl: 'https://psp.example/pay/123',
            ));
        when(() => orders.orders(status: 'DRAFT')).thenAnswer((_) async => []);
      },
      build: build,
      act: (b) => b.add(const DraftSendRequested('order_1', tender: 'PSP')),
      expect: () => [
        const DraftsState(
          lastOutcome: SendOutcome(
            status: 'AWAITING_PAYMENT',
            orderId: 'order_1',
            paymentUrl: 'https://psp.example/pay/123',
          ),
        ),
        const DraftsState(status: DraftsStatus.loading),
        const DraftsState(status: DraftsStatus.loaded),
      ],
    );

    blocTest<DraftsBloc, DraftsState>(
      'send surfacing INSUFFICIENT_STOCK keeps the sku + available detail',
      setUp: () {
        when(() => drafts.send(
              'order_1',
              tender: any(named: 'tender'),
              applyStoreCredit: any(named: 'applyStoreCredit'),
              returnUrl: any(named: 'returnUrl'),
              customerRef: any(named: 'customerRef'),
            )).thenAnswer((_) async => const SendOutcome(
              status: 'INSUFFICIENT_STOCK',
              orderId: 'order_1',
              insufficientSku: 'SKU-1',
              available: 1,
            ));
        when(() => orders.orders(status: 'DRAFT')).thenAnswer((_) async => [draft]);
      },
      build: build,
      act: (b) => b.add(const DraftSendRequested('order_1')),
      verify: (b) {
        // The outcome is one-shot; after the refresh the state carries the list.
        expect(b.state.drafts, [draft]);
      },
    );

    blocTest<DraftsBloc, DraftsState>(
      'send failure surfaces the error',
      setUp: () => when(() => drafts.send(
            any(),
            tender: any(named: 'tender'),
            applyStoreCredit: any(named: 'applyStoreCredit'),
            returnUrl: any(named: 'returnUrl'),
            customerRef: any(named: 'customerRef'),
          )).thenThrow(const ServerFailure('draft has no lines')),
      build: build,
      act: (b) => b.add(const DraftSendRequested('order_1')),
      expect: () => [
        const DraftsState(status: DraftsStatus.failure, error: 'draft has no lines'),
      ],
    );
  });
}
