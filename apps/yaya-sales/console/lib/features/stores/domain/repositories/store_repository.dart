import '../entities/store_entities.dart';

abstract class StoreRepository {
  Future<List<Store>> storesOf(String tenantId);
  Future<Store> createStore(String tenantId, String displayName, String defaultCurrency);
  Future<Store> store(String id);
  Future<Store> updatePolicies(String id, StorePolicies policies);

  Future<List<StoreLocation>> locations();
  Future<StoreLocation> addLocation(String label, String kind, bool sellable);

  Future<List<Channel>> channels();
  Future<Channel> addChannel(String kind, String? label);

  Future<List<Region>> regions();
  Future<Region> addRegion(String name, String currency, TaxPolicy tax, List<String> tenders);
  Future<Region> updateRegion(String id, String name, TaxPolicy tax, List<String> tenders);
}
