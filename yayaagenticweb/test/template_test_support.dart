import 'package:flutter/material.dart';
import 'package:yayaagenticweb/features/admin/receipt_templates/receipt_templates_screen.dart';
import 'package:yayaagenticweb/features/admin/whatsapp_templates/template_list_kit.dart';
import 'package:yayaagenticweb/ui/data/y_sheet.dart';

/// `showYSheet`'s frame (`Semantics(scopesRoute: true)` without
/// `explicitChildNodes`) fails a debug assertion when it mounts, so template
/// tests open sheets as a plain full-screen route. Everything inside the
/// sheet — fields, validation, the action bar, closeYSheet — is the real one.
void useTestSheets() {
  Future<R?> open<R>(BuildContext context, {required WidgetBuilder builder, double width = 640, String label = 'Editor'}) =>
      Navigator.of(context).push<R>(MaterialPageRoute(builder: (c) => Material(child: builder(c))));
  openTemplateSheet = open;
  openReceiptSheet = open;
}

/// Restores the real sheets.
void useRealSheets() {
  openTemplateSheet = showYSheet;
  openReceiptSheet = showYSheet;
}
