import '../entities/purchasing_entities.dart';

abstract class PurchasingRepository {
  Future<List<Supplier>> suppliers();
  Future<Supplier> createSupplier(String name, Map<String, dynamic> contact);
  Future<Supplier> updateSupplier(String id, String name, Map<String, dynamic> contact);
  Future<void> deleteSupplier(String id);

  Future<List<PurchaseOrder>> purchaseOrders({String? status});
  Future<PurchaseOrder> createPo(
      String supplierId, String locationId, List<PoLine> lines, DateTime? expectedAt);
  Future<PurchaseOrder> place(String poId);
  Future<PurchaseOrder> cancel(String poId);

  /// Partial receipts are fine — the PO tracks received-so-far per line.
  Future<PurchaseOrder> receive(String poId, List<PoLine> lines);
}
