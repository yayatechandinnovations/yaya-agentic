import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:yaya_sales_console/core/error/failure.dart';
import 'package:yaya_sales_console/features/customers/domain/entities/customer_entities.dart';
import 'package:yaya_sales_console/features/customers/domain/repositories/customer_repository.dart';
import 'package:yaya_sales_console/features/customers/domain/usecases/customer_usecases.dart';
import 'package:yaya_sales_console/features/orders/domain/entities/order_entities.dart';
import 'package:yaya_sales_console/features/orders/domain/repositories/order_repository.dart';
import 'package:yaya_sales_console/features/orders/domain/usecases/order_usecases.dart';
import 'package:yaya_sales_console/features/orders/presentation/bloc/store_credit_bloc.dart';

class MockOrderRepository extends Mock implements OrderRepository {}

class MockCustomerRepository extends Mock implements CustomerRepository {}

void main() {
  late MockOrderRepository orders;
  late MockCustomerRepository customers;

  StoreCreditBloc build() => StoreCreditBloc(
        getBalance: GetStoreCreditBalance(orders),
        issueCredit: IssueStoreCredit(orders),
        searchCustomers: SearchCustomers(customers),
      );

  setUp(() {
    orders = MockOrderRepository();
    customers = MockCustomerRepository();
  });

  const customer = Customer(id: 'cus_1', displayName: 'Ana Gómez', customerRef: 'ana');

  group('StoreCreditBloc', () {
    blocTest<StoreCreditBloc, StoreCreditState>(
      'context request loads the customer directory into state',
      setUp: () =>
          when(() => customers.search(any())).thenAnswer((_) async => [customer]),
      build: build,
      act: (b) => b.add(const StoreCreditContextRequested()),
      expect: () => [
        const StoreCreditState(customers: [customer]),
      ],
    );

    blocTest<StoreCreditBloc, StoreCreditState>(
      'lookup surfaces the balance',
      setUp: () => when(() => orders.storeCreditBalance('ana', 'USD')).thenAnswer(
          (_) async => const StoreCreditBalance(
              customerRef: 'ana', balanceMinor: 1500, currency: 'USD')),
      build: build,
      act: (b) => b.add(const BalanceLookupRequested('ana', 'USD')),
      expect: () => [
        const StoreCreditState(status: StoreCreditStatus.loading),
        const StoreCreditState(
          status: StoreCreditStatus.loaded,
          balance:
              StoreCreditBalance(customerRef: 'ana', balanceMinor: 1500, currency: 'USD'),
        ),
      ],
    );

    blocTest<StoreCreditBloc, StoreCreditState>(
      'issue failure surfaces the error',
      setUp: () => when(() => orders.issueStoreCredit(any(), any(), any(), any()))
          .thenThrow(const ServerFailure('amount must be positive')),
      build: build,
      act: (b) => b.add(const CreditIssueRequested('ana', 0, 'USD', null)),
      expect: () => [
        const StoreCreditState(
            status: StoreCreditStatus.failure, error: 'amount must be positive'),
      ],
    );
  });
}
