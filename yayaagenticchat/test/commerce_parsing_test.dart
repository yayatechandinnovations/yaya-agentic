import 'package:flutter_test/flutter_test.dart';
import 'package:yayaagenticchat/models/commerce/cart.dart';
import 'package:yayaagenticchat/models/commerce/catalog_payload.dart';
import 'package:yayaagenticchat/models/commerce/checkout_result.dart';
import 'package:yayaagenticchat/models/commerce/commerce_config.dart';
import 'package:yayaagenticchat/models/commerce/confirm_prompt.dart';
import 'package:yayaagenticchat/models/commerce/product.dart';

/// These payloads mirror the real backend wire shapes (the `payload` object of a
/// `ui_hint` SSE frame). Typed ids may arrive as bare strings OR `{value: …}`,
/// so parsing must tolerate both.
void main() {
  group('Product.fromJson', () {
    test('parses a product with a plain-string id', () {
      final p = Product.fromJson({
        'id': 'p_44',
        'title': 'Castor Oil Soap',
        'shortDescription': 'Nourishing.',
        'price': {'amountMinor': 1299, 'currency': 'USD'},
        'imageUrls': ['https://x/1.jpg', 'https://x/2.jpg'],
        'category': 'soap',
        'attributes': {'ingredient': 'castor oil', 'form': 'soap'},
        'availability': 'IN_STOCK',
        'variants': [
          {'variantId': 'v1', 'label': 'Large', 'price': {'amountMinor': 1599, 'currency': 'USD'}, 'availability': 'LOW'}
        ],
      });
      expect(p.id, 'p_44');
      expect(p.title, 'Castor Oil Soap');
      expect(p.price!.amountMinor, 1299);
      expect(p.heroImage, 'https://x/1.jpg');
      expect(p.attributes['ingredient'], 'castor oil');
      expect(p.hasVariants, true);
      expect(p.variants.first.availability, Availability.low);
      expect(p.availability, Availability.inStock);
    });

    test('tolerates a typed id serialized as {value: …}', () {
      final p = Product.fromJson({'id': {'value': 'p_99'}, 'title': 'X'});
      expect(p.id, 'p_99');
    });

    test('defaults missing/unknown availability to inStock', () {
      final p = Product.fromJson({'id': 'p', 'title': 'X'});
      expect(p.availability, Availability.inStock);
      expect(p.price, isNull);
      expect(p.imageUrls, isEmpty);
    });
  });

  group('Cart.fromJson', () {
    test('parses lines and derives itemCount (server @JsonIgnore)', () {
      final cart = Cart.fromJson({
        'cartHandle': 'mrc_1',
        'currency': 'USD',
        'lines': [
          {'lineId': 'l1', 'productId': 'p1', 'title': 'Soap', 'qty': 2,
           'unitPrice': {'amountMinor': 1299, 'currency': 'USD'},
           'lineTotal': {'amountMinor': 2598, 'currency': 'USD'}},
          {'lineId': 'l2', 'productId': 'p2', 'title': 'Oil', 'qty': 1,
           'unitPrice': {'amountMinor': 999, 'currency': 'USD'},
           'lineTotal': {'amountMinor': 999, 'currency': 'USD'}},
        ],
        'subtotal': {'amountMinor': 3597, 'currency': 'USD'},
      });
      expect(cart.isEmpty, false);
      expect(cart.lines.length, 2);
      expect(cart.itemCount, 3);
      expect(cart.subtotal!.amountMinor, 3597);
    });

    test('empty cart', () {
      final cart = Cart.fromJson({'currency': 'USD', 'lines': []});
      expect(cart.isEmpty, true);
      expect(cart.itemCount, 0);
    });
  });

  group('CatalogPayload.fromJson', () {
    test('parses items, facets, page and echoed query', () {
      final c = CatalogPayload.fromJson({
        'items': [
          {'id': 'p1', 'title': 'A', 'price': {'amountMinor': 500, 'currency': 'USD'}},
        ],
        'facets': [
          {'key': 'ingredient', 'label': 'Ingredient', 'values': [
            {'v': 'castor oil', 'count': 12}, {'v': 'shea', 'count': 7}]},
          {'key': 'price', 'label': 'Price', 'buckets': [
            {'label': '< \$10', 'max': 1000, 'count': 4}]},
        ],
        'page': {'index': 0, 'hasMore': true},
        'total': 19,
        'query': {'echo': 'body care'},
      });
      expect(c.items.length, 1);
      expect(c.facets.length, 2);
      expect(c.facets.first.values.first.v, 'castor oil');
      expect(c.facets.last.isPrice, true);
      expect(c.hasMore, true);
      expect(c.total, 19);
      expect(c.queryEcho, 'body care');
    });
  });

  group('CheckoutResult.fromJson', () {
    test('hosted_url mode', () {
      final r = CheckoutResult.fromJson({
        'orderRef': 'ord_abc_5999', 'amountMinor': 5999, 'currency': 'USD',
        'mode': 'hosted_url', 'url': 'https://sandbox.dlocal.com/pay/x',
      });
      expect(r.mode, CheckoutMode.hostedUrl);
      expect(r.url, 'https://sandbox.dlocal.com/pay/x');
      expect(r.amount.amountMinor, 5999);
    });

    test('payment_intent mode', () {
      final r = CheckoutResult.fromJson({
        'orderRef': 'ord_1', 'amountMinor': 100, 'currency': 'USD',
        'mode': 'payment_intent', 'clientSecret': 'pi_x_secret', 'publishableKey': 'pk_test_x',
      });
      expect(r.mode, CheckoutMode.paymentIntent);
      expect(r.clientSecret, 'pi_x_secret');
      expect(r.publishableKey, 'pk_test_x');
    });

    test('order_placed mode', () {
      final r = CheckoutResult.fromJson({
        'orderRef': 'ord_1', 'amountMinor': 100, 'currency': 'USD',
        'mode': 'order_placed', 'status': 'PENDING',
      });
      expect(r.mode, CheckoutMode.orderPlaced);
      expect(r.status, 'PENDING');
    });
  });

  group('ConfirmPrompt / CommerceConfig', () {
    test('ConfirmPrompt carries the tool + summary', () {
      final c = ConfirmPrompt.fromJson({
        'toolId': 'checkout', 'callId': 'c1', 'args': {'note': 'x'},
        'summary': 'Run checkout with {} ?',
      });
      expect(c.toolId, 'checkout');
      expect(c.callId, 'c1');
      expect(c.summary, contains('checkout'));
    });

    test('CommerceConfig defaults checkoutTool when absent', () {
      final c = CommerceConfig.fromJson({'currency': 'USD', 'cartEnabled': true});
      expect(c.isStore, true);
      expect(c.checkoutTool, 'checkout');
      final c2 = CommerceConfig.fromJson(
          {'currency': 'USD', 'cartEnabled': true, 'checkoutTool': 'place_order'});
      expect(c2.checkoutTool, 'place_order');
    });
  });
}
