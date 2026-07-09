import 'package:dio/dio.dart';

import '../storage/store_context.dart';

/// Attaches the acting store as `X-Yaya-Store-Id` — the header every
/// store-scoped yaya-sales admin endpoint resolves the store from.
class StoreInterceptor extends Interceptor {
  StoreInterceptor(this._stores);

  static const header = 'X-Yaya-Store-Id';

  final StoreContext _stores;

  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    final storeId = _stores.storeId;
    if (storeId != null && storeId.isNotEmpty) {
      options.headers[header] = storeId;
    }
    handler.next(options);
  }
}
