import 'dart:async';
import 'dart:convert';
import 'dart:html' as html;

/// Web-only CSV helpers for the managed-product workspace (the admin app is
/// web-only, same as the image picker). Import = pick a .csv and read its text;
/// export = trigger a browser download of CSV text. Parsing/formatting lives in
/// csv_codec.dart so it's testable without the browser.

/// Pick a .csv file from the browser and return its text (null if dismissed).
Future<String?> pickCsvText() async {
  final input = html.FileUploadInputElement()
    ..accept = '.csv,text/csv'
    ..multiple = false;
  input.click();
  await input.onChange.first;
  final files = input.files;
  if (files == null || files.isEmpty) return null;

  final reader = html.FileReader();
  reader.readAsText(files.first);
  await reader.onLoadEnd.first;
  final result = reader.result;
  return result is String ? result : null;
}

/// Trigger a client-side download of [csv] as [filename].
void downloadCsv(String filename, String csv) {
  final bytes = utf8.encode(csv);
  final blob = html.Blob([bytes], 'text/csv');
  final url = html.Url.createObjectUrlFromBlob(blob);
  final anchor = html.AnchorElement(href: url)
    ..download = filename
    ..style.display = 'none';
  html.document.body?.append(anchor);
  anchor.click();
  anchor.remove();
  html.Url.revokeObjectUrl(url);
}
