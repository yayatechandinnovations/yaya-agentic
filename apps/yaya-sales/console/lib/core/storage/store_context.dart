import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// The acting store + remembered tenant. A [ChangeNotifier] so the router
/// redirect and the shell app bar react when the operator switches store;
/// persisted so a reload keeps the selection.
class StoreContext extends ChangeNotifier {
  StoreContext(this._prefs)
      : _storeId = _prefs.getString(_storeKey),
        _storeName = _prefs.getString(_storeNameKey),
        _storeCurrency = _prefs.getString(_storeCurrencyKey),
        _tenantId = _prefs.getString(_tenantKey);

  static const _storeKey = 'yaya_sales_store_id';
  static const _storeNameKey = 'yaya_sales_store_name';
  static const _storeCurrencyKey = 'yaya_sales_store_currency';
  static const _tenantKey = 'yaya_sales_tenant_id';

  final SharedPreferences _prefs;

  String? _storeId;
  String? _storeName;
  String? _storeCurrency;
  String? _tenantId;

  String? get storeId => _storeId;
  String? get storeName => _storeName;
  String? get storeCurrency => _storeCurrency;
  String? get tenantId => _tenantId;
  bool get hasStore => _storeId != null && _storeId!.isNotEmpty;

  Future<void> selectStore(String id, String displayName, String defaultCurrency) async {
    _storeId = id;
    _storeName = displayName;
    _storeCurrency = defaultCurrency;
    await _prefs.setString(_storeKey, id);
    await _prefs.setString(_storeNameKey, displayName);
    await _prefs.setString(_storeCurrencyKey, defaultCurrency);
    notifyListeners();
  }

  Future<void> rememberTenant(String tenantId) async {
    _tenantId = tenantId;
    await _prefs.setString(_tenantKey, tenantId);
    notifyListeners();
  }

  Future<void> clearStore() async {
    _storeId = null;
    _storeName = null;
    _storeCurrency = null;
    await _prefs.remove(_storeKey);
    await _prefs.remove(_storeNameKey);
    await _prefs.remove(_storeCurrencyKey);
    notifyListeners();
  }
}
