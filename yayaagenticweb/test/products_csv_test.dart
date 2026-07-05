import 'package:flutter_test/flutter_test.dart';
import 'package:yayaagenticweb/features/admin/product_catalogs/csv_codec.dart';

/// Locks in the CSV import parser (Pillar A bulk authoring): header-mapped,
/// decimal→minor-unit conversion, and per-row error reporting so a spreadsheet
/// round-trip never silently drops or corrupts rows.
void main() {
  test('parses the export format into bulk bodies (integer minor units)', () {
    const csv =
        'product_id,title,price_minor,currency,category,availability,inventory,image_urls,attributes\n'
        'p_soap,Castor Soap,899,DOP,soap,IN_STOCK,20,https://a | https://b,ingredient=castor oil | form=bar\n';
    final r = parseProductsCsv(csv);

    expect(r.errors, isEmpty);
    expect(r.bodies, hasLength(1));
    final b = r.bodies.first;
    expect(b['productId'], 'p_soap');
    expect(b['priceMinor'], 899);
    expect(b['currency'], 'DOP');
    expect(b['imageUrls'], ['https://a', 'https://b']);
    expect((b['attributes'] as Map)['ingredient'], 'castor oil');
    expect((b['attributes'] as Map)['form'], 'bar');
  });

  test('converts a decimal price column to minor units', () {
    const csv = 'sku,name,price\nX1,Widget,12.50\n';
    final r = parseProductsCsv(csv);
    expect(r.errors, isEmpty);
    expect(r.bodies.single['productId'], 'X1');
    expect(r.bodies.single['priceMinor'], 1250);
  });

  test('reports rows missing id/title or price instead of dropping silently', () {
    const csv = 'product_id,title,price_minor\n'
        ',No Id,100\n'
        'ok,Good,500\n'
        'bad,No Price,\n';
    final r = parseProductsCsv(csv);
    expect(r.bodies, hasLength(1));           // only "ok" is valid
    expect(r.bodies.single['productId'], 'ok');
    expect(r.errors, hasLength(2));           // missing-id and missing-price rows surfaced
  });

  test('handles quoted fields with commas and escaped quotes', () {
    const csv = 'product_id,title,price_minor\n'
        '"p,1","A ""great"" soap, really",750\n';
    final r = parseProductsCsv(csv);
    expect(r.errors, isEmpty);
    expect(r.bodies.single['productId'], 'p,1');
    expect(r.bodies.single['title'], 'A "great" soap, really');
    expect(r.bodies.single['priceMinor'], 750);
  });
}
