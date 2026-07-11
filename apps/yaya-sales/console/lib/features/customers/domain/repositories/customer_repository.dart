import '../entities/customer_entities.dart';

abstract class CustomerRepository {
  Future<List<Customer>> search(String? q);

  Future<Customer> create(CustomerDraft draft);

  Future<Customer> update(String id, CustomerDraft draft);

  Future<void> delete(String id);

  Future<Customer> addAddress(String id, String kind, Map<String, dynamic> address);

  Future<Customer> removeAddress(String id, String addressId);
}
