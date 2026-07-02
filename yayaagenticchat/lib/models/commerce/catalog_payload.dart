import 'commerce_json.dart';
import 'facet.dart';
import 'product.dart';

/// The `product_catalog` UiHint payload: a page of products + the facets that
/// travel for instant local filtering + pagination + the echoed query so the
/// client can show "N results for …" (ecommerce-chat-design.md §10.2).
class CatalogPayload {
  final List<Product> items;
  final List<Facet> facets;
  final int pageIndex;
  final bool hasMore;
  final int total;
  final String queryEcho;

  const CatalogPayload({
    this.items = const [],
    this.facets = const [],
    this.pageIndex = 0,
    this.hasMore = false,
    this.total = 0,
    this.queryEcho = '',
  });

  factory CatalogPayload.fromJson(Map<String, dynamic> j) {
    final page = asMap(j['page']);
    final query = asMap(j['query']);
    return CatalogPayload(
      items: asList(j['items']).map((e) => Product.fromJson(asMap(e))).toList(),
      facets: asList(j['facets']).map((e) => Facet.fromJson(asMap(e))).toList(),
      pageIndex: asInt(page['index']),
      hasMore: asBool(page['hasMore']),
      total: asInt(j['total']),
      queryEcho: (query['echo'] ?? '').toString(),
    );
  }
}
