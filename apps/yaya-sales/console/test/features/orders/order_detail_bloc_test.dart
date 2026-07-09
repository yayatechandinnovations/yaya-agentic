import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:yaya_sales_console/core/domain/money.dart';
import 'package:yaya_sales_console/core/error/failure.dart';
import 'package:yaya_sales_console/features/orders/domain/entities/order_entities.dart';
import 'package:yaya_sales_console/features/orders/domain/repositories/order_repository.dart';
import 'package:yaya_sales_console/features/orders/domain/usecases/order_usecases.dart';
import 'package:yaya_sales_console/features/orders/presentation/bloc/order_detail_bloc.dart';

class MockOrderRepository extends Mock implements OrderRepository {}

void main() {
  late MockOrderRepository repo;

  OrderDetailBloc build() => OrderDetailBloc(
        getDetail: GetOrderDetail(repo),
        cancelOrder: CancelOrder(repo),
        addTender: AddTender(repo),
        settleTender: SettleTender(repo),
        refundOrder: RefundOrder(repo),
      );

  setUp(() => repo = MockOrderRepository());

  const order = Order(
    id: 'order_1',
    status: 'PAID',
    paymentState: 'PAID',
    currency: 'USD',
    totals: OrderTotals(
      grand: Money(amountMinor: 5000, currency: 'USD'),
      taxInclusive: true,
    ),
  );
  const detail = OrderDetail(
    order: order,
    events: [OrderEvent(type: 'status', fromState: 'PENDING_PAYMENT', toState: 'PAID')],
    tenders: [
      Tender(
        id: 'tender_1',
        kind: 'COD',
        status: 'PENDING',
        amount: Money(amountMinor: 5000, currency: 'USD'),
      ),
    ],
  );

  group('OrderDetailBloc', () {
    blocTest<OrderDetailBloc, OrderDetailState>(
      'OrderDetailRequested → loading, loaded with events + tenders',
      setUp: () => when(() => repo.detail('order_1')).thenAnswer((_) async => detail),
      build: build,
      act: (b) => b.add(const OrderDetailRequested('order_1')),
      expect: () => [
        const OrderDetailState(status: OrderDetailStatus.loading, orderId: 'order_1'),
        const OrderDetailState(
            status: OrderDetailStatus.loaded, orderId: 'order_1', detail: detail),
      ],
    );

    blocTest<OrderDetailBloc, OrderDetailState>(
      'partial refund to store credit refunds then reloads the detail',
      setUp: () {
        when(() => repo.refund('order_1', amountMinor: 1000, toStoreCredit: true))
            .thenAnswer((_) async => order);
        when(() => repo.detail('order_1')).thenAnswer((_) async => detail);
      },
      build: build,
      seed: () =>
          const OrderDetailState(status: OrderDetailStatus.loaded, orderId: 'order_1'),
      act: (b) =>
          b.add(const OrderRefundRequested(amountMinor: 1000, toStoreCredit: true)),
      expect: () => [
        const OrderDetailState(status: OrderDetailStatus.loading, orderId: 'order_1'),
        const OrderDetailState(
            status: OrderDetailStatus.loaded, orderId: 'order_1', detail: detail),
      ],
      verify: (_) =>
          verify(() => repo.refund('order_1', amountMinor: 1000, toStoreCredit: true))
              .called(1),
    );

    blocTest<OrderDetailBloc, OrderDetailState>(
      'full refund passes a null amount (server refunds everything)',
      setUp: () {
        when(() => repo.refund('order_1', amountMinor: null, toStoreCredit: false))
            .thenAnswer((_) async => order);
        when(() => repo.detail('order_1')).thenAnswer((_) async => detail);
      },
      build: build,
      seed: () =>
          const OrderDetailState(status: OrderDetailStatus.loaded, orderId: 'order_1'),
      act: (b) => b.add(const OrderRefundRequested(toStoreCredit: false)),
      verify: (_) =>
          verify(() => repo.refund('order_1', amountMinor: null, toStoreCredit: false))
              .called(1),
    );

    blocTest<OrderDetailBloc, OrderDetailState>(
      'refund failure surfaces the error without a reload',
      setUp: () => when(() => repo.refund(any(),
              amountMinor: any(named: 'amountMinor'),
              toStoreCredit: any(named: 'toStoreCredit')))
          .thenThrow(const ServerFailure('order is not refundable')),
      build: build,
      seed: () =>
          const OrderDetailState(status: OrderDetailStatus.loaded, orderId: 'order_1'),
      act: (b) =>
          b.add(const OrderRefundRequested(amountMinor: 1000, toStoreCredit: false)),
      expect: () => [
        const OrderDetailState(
          status: OrderDetailStatus.failure,
          orderId: 'order_1',
          error: 'order is not refundable',
        ),
      ],
      verify: (_) => verifyNever(() => repo.detail(any())),
    );

    blocTest<OrderDetailBloc, OrderDetailState>(
      'settle tender settles the COD/MANUAL tender then reloads',
      setUp: () {
        when(() => repo.settleTender('order_1', 'tender_1')).thenAnswer((_) async {});
        when(() => repo.detail('order_1')).thenAnswer((_) async => detail);
      },
      build: build,
      seed: () =>
          const OrderDetailState(status: OrderDetailStatus.loaded, orderId: 'order_1'),
      act: (b) => b.add(const TenderSettleRequested('tender_1')),
      verify: (_) => verify(() => repo.settleTender('order_1', 'tender_1')).called(1),
    );

    blocTest<OrderDetailBloc, OrderDetailState>(
      'cancel cancels the order then reloads',
      setUp: () {
        when(() => repo.cancel('order_1')).thenAnswer((_) async => order);
        when(() => repo.detail('order_1')).thenAnswer((_) async => detail);
      },
      build: build,
      seed: () =>
          const OrderDetailState(status: OrderDetailStatus.loaded, orderId: 'order_1'),
      act: (b) => b.add(const OrderCancelRequested()),
      verify: (_) => verify(() => repo.cancel('order_1')).called(1),
    );
  });
}
