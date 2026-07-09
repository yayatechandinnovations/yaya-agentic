import '../entities/inventory_entities.dart';

abstract class InventoryRepository {
  Future<List<StockLevel>> levels({String? location, bool lowOnly = false});
  Future<List<Movement>> ledger(String sku, {String? location});
  Future<void> receive(String sku, String location, int qty, int unitCostMinor,
      String currency, String? poRef);
  Future<void> adjust(String sku, String location, int delta, String reason);
  Future<void> transfer(String sku, String from, String to, int qty);
  Future<void> setReorder(String sku, String location, int point, int qty);
  Future<void> setBackorder(String sku, String location, String policy);
}
