import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:yaya_sales_console/core/error/failure.dart';
import 'package:yaya_sales_console/features/customers/domain/entities/customer_entities.dart';
import 'package:yaya_sales_console/features/customers/domain/repositories/customer_repository.dart';
import 'package:yaya_sales_console/features/customers/domain/usecases/customer_usecases.dart';
import 'package:yaya_sales_console/features/customers/presentation/bloc/customers_bloc.dart';

class MockCustomerRepository extends Mock implements CustomerRepository {}

void main() {
  late MockCustomerRepository repo;

  CustomersBloc build() => CustomersBloc(
        searchCustomers: SearchCustomers(repo),
        createCustomer: CreateCustomer(repo),
        updateCustomer: UpdateCustomer(repo),
        deleteCustomer: DeleteCustomer(repo),
        addAddress: AddCustomerAddress(repo),
        removeAddress: RemoveCustomerAddress(repo),
      );

  setUpAll(() => registerFallbackValue(const CustomerDraft(displayName: 'x')));
  setUp(() => repo = MockCustomerRepository());

  const rosa = Customer(
    id: 'cust_1',
    displayName: 'Rosa Peña',
    customerRef: 'user:rosa',
    email: 'rosa@example.do',
    phone: '+18095550101',
  );

  blocTest<CustomersBloc, CustomersState>(
    'CustomersRequested → loading, loaded with the directory',
    setUp: () => when(() => repo.search(any())).thenAnswer((_) async => [rosa]),
    build: build,
    act: (b) => b.add(const CustomersRequested()),
    expect: () => [
      const CustomersState(status: CustomersStatus.loading),
      isA<CustomersState>()
          .having((s) => s.status, 'status', CustomersStatus.loaded)
          .having((s) => s.customers, 'customers', [rosa]),
    ],
  );

  blocTest<CustomersBloc, CustomersState>(
    'create confirms and reloads with the active query preserved',
    setUp: () {
      when(() => repo.create(any())).thenAnswer((_) async => rosa);
      when(() => repo.search(any())).thenAnswer((_) async => [rosa]);
    },
    build: build,
    seed: () => const CustomersState(status: CustomersStatus.loaded, query: 'rosa'),
    act: (b) => b.add(const CustomerCreateRequested(
        CustomerDraft(displayName: 'Rosa Peña', customerRef: 'user:rosa'))),
    expect: () => [
      isA<CustomersState>()
          .having((s) => s.message, 'message', 'Customer "Rosa Peña" created.'),
      isA<CustomersState>().having((s) => s.status, 'status', CustomersStatus.loading),
      isA<CustomersState>().having((s) => s.customers, 'customers', [rosa]),
    ],
    verify: (_) => verify(() => repo.search('rosa')).called(1),
  );

  blocTest<CustomersBloc, CustomersState>(
    'address add swaps the customer in place — no list reload flicker',
    setUp: () => when(() => repo.addAddress(any(), any(), any())).thenAnswer(
        (_) async => const Customer(id: 'cust_1', displayName: 'Rosa Peña', addresses: [
              CustomerAddress(id: 'addr_1', kind: 'SHIPPING', city: 'Santo Domingo'),
            ])),
    build: build,
    seed: () => const CustomersState(status: CustomersStatus.loaded, customers: [rosa]),
    act: (b) => b.add(const CustomerAddressAdded(
        'cust_1', 'SHIPPING', {'city': 'Santo Domingo'})),
    expect: () => [
      isA<CustomersState>()
          .having((s) => s.customers.single.addresses.single.city, 'city', 'Santo Domingo')
          .having((s) => s.message, 'message', 'Address added.'),
    ],
  );

  blocTest<CustomersBloc, CustomersState>(
    'duplicate-ref conflict surfaces as a failure',
    setUp: () => when(() => repo.create(any()))
        .thenThrow(const ServerFailure("a customer with ref 'user:rosa' already exists")),
    build: build,
    act: (b) => b.add(const CustomerCreateRequested(
        CustomerDraft(displayName: 'Dup', customerRef: 'user:rosa'))),
    expect: () => [
      isA<CustomersState>()
          .having((s) => s.status, 'status', CustomersStatus.failure)
          .having((s) => s.error, 'error', contains('already exists')),
    ],
  );
}
