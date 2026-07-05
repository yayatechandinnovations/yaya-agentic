// Pure-Dart CSV parsing for the managed-product import (no browser deps, so it's
// testable). Header-mapped: it reads the export's column names and tolerates a
// spreadsheet that reordered or omitted optional columns. Price is parsed as
// integer minor units — a `price` decimal column is converted, never trusted raw.

class CsvImportResult {
  CsvImportResult(this.bodies, this.errors);
  final List<Map<String, dynamic>> bodies;      // ready for the bulk endpoint
  final List<String> errors;                    // per-row problems (human-readable)
}

/// Parse CSV text into product bodies for the bulk-upsert endpoint.
CsvImportResult parseProductsCsv(String csv) {
  final rows = _parseCsv(csv);
  if (rows.isEmpty) return CsvImportResult(const [], const ['empty file']);

  final header = rows.first.map((h) => h.trim().toLowerCase()).toList();
  int col(String name) => header.indexOf(name);

  final iId = _first(col, ['product_id', 'productid', 'sku', 'id']);
  final iTitle = _first(col, ['title', 'name']);
  final iPriceMinor = _first(col, ['price_minor', 'priceminor']);
  final iPriceDecimal = _first(col, ['price', 'amount']);
  final iCurrency = _first(col, ['currency']);
  final iShort = _first(col, ['short_description', 'shortdescription', 'description']);
  final iLong = _first(col, ['long_description', 'longdescription']);
  final iCategory = _first(col, ['category']);
  final iAvail = _first(col, ['availability']);
  final iInv = _first(col, ['inventory', 'stock']);
  final iDetail = _first(col, ['detail_url', 'detailurl']);
  final iImages = _first(col, ['image_urls', 'images']);
  final iAttrs = _first(col, ['attributes']);

  final bodies = <Map<String, dynamic>>[];
  final errors = <String>[];

  for (var r = 1; r < rows.length; r++) {
    final row = rows[r];
    if (row.every((c) => c.trim().isEmpty)) continue;
    String cell(int i) => (i >= 0 && i < row.length) ? row[i].trim() : '';

    final id = cell(iId);
    final title = cell(iTitle);
    if (id.isEmpty || title.isEmpty) {
      errors.add('row ${r + 1}: product_id and title are required');
      continue;
    }

    int? priceMinor;
    if (iPriceMinor >= 0 && cell(iPriceMinor).isNotEmpty) {
      priceMinor = int.tryParse(cell(iPriceMinor));
    } else if (iPriceDecimal >= 0 && cell(iPriceDecimal).isNotEmpty) {
      final d = double.tryParse(cell(iPriceDecimal).replaceAll(RegExp(r'[^0-9.\-]'), ''));
      if (d != null) priceMinor = (d * 100).round();
    }
    if (priceMinor == null) {
      errors.add('row ${r + 1}: missing/invalid price for "$title"');
      continue;
    }

    final body = <String, dynamic>{
      'productId': id,
      'title': title,
      'priceMinor': priceMinor,
    };
    if (iCurrency >= 0 && cell(iCurrency).isNotEmpty) body['currency'] = cell(iCurrency);
    if (iShort >= 0 && cell(iShort).isNotEmpty) body['shortDescription'] = cell(iShort);
    if (iLong >= 0 && cell(iLong).isNotEmpty) body['longDescription'] = cell(iLong);
    if (iCategory >= 0 && cell(iCategory).isNotEmpty) body['category'] = cell(iCategory);
    if (iAvail >= 0 && cell(iAvail).isNotEmpty) body['availability'] = cell(iAvail).toUpperCase();
    if (iDetail >= 0 && cell(iDetail).isNotEmpty) body['detailUrl'] = cell(iDetail);
    if (iInv >= 0 && cell(iInv).isNotEmpty) {
      final inv = int.tryParse(cell(iInv));
      if (inv != null) body['inventory'] = inv;
    }
    if (iImages >= 0 && cell(iImages).isNotEmpty) {
      body['imageUrls'] = cell(iImages).split('|').map((s) => s.trim()).where((s) => s.isNotEmpty).toList();
    }
    if (iAttrs >= 0 && cell(iAttrs).isNotEmpty) {
      final attrs = <String, String>{};
      for (final pair in cell(iAttrs).split('|')) {
        final eq = pair.indexOf('=');
        if (eq > 0) attrs[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim();
      }
      if (attrs.isNotEmpty) body['attributes'] = attrs;
    }
    bodies.add(body);
  }
  return CsvImportResult(bodies, errors);
}

int _first(int Function(String) col, List<String> names) {
  for (final n in names) {
    final i = col(n);
    if (i >= 0) return i;
  }
  return -1;
}

/// Minimal RFC-4180-ish CSV parser: handles quoted fields, escaped quotes ("")
/// and CRLF/LF line endings.
List<List<String>> _parseCsv(String input) {
  final rows = <List<String>>[];
  var row = <String>[];
  final field = StringBuffer();
  var inQuotes = false;
  for (var i = 0; i < input.length; i++) {
    final c = input[i];
    if (inQuotes) {
      if (c == '"') {
        if (i + 1 < input.length && input[i + 1] == '"') {
          field.write('"');
          i++;
        } else {
          inQuotes = false;
        }
      } else {
        field.write(c);
      }
    } else {
      switch (c) {
        case '"':
          inQuotes = true;
        case ',':
          row.add(field.toString());
          field.clear();
        case '\r':
          break;
        case '\n':
          row.add(field.toString());
          field.clear();
          rows.add(row);
          row = <String>[];
        default:
          field.write(c);
      }
    }
  }
  if (field.isNotEmpty || row.isNotEmpty) {
    row.add(field.toString());
    rows.add(row);
  }
  return rows;
}
