import 'dart:ui_web' as ui_web;

import 'package:flutter/widgets.dart';
import 'package:web/web.dart' as web;

import '../../config.dart';
import '../../models/chat_message.dart';

/// Track view types we've already registered so re-opening the same document
/// doesn't re-register a factory (which asserts on duplicates).
final Set<String> _registered = <String>{};

/// Preview a non-image document inline by hosting an `<iframe>` — browsers
/// render PDFs natively, so the document appears in-app without leaving it.
Widget buildDocumentPreview(ChatAttachment attachment) {
  final url = AppConfig.resolveUrl(attachment.url);
  final viewType = 'yaya-doc-${attachment.assetId}-${url.hashCode}';
  if (!_registered.contains(viewType)) {
    ui_web.platformViewRegistry.registerViewFactory(viewType, (int viewId) {
      final iframe = web.HTMLIFrameElement()
        ..src = url
        ..title = attachment.filename ?? 'document'
        ..style.border = 'none'
        ..style.width = '100%'
        ..style.height = '100%';
      return iframe;
    });
    _registered.add(viewType);
  }
  return HtmlElementView(viewType: viewType);
}

/// Real browser download that stays in-app: a synthetic `<a download>` click on
/// the signed URL.
Future<void> downloadDocument(ChatAttachment attachment) async {
  final url = AppConfig.resolveUrl(attachment.url);
  final anchor = web.HTMLAnchorElement()
    ..href = url
    ..download = attachment.filename ?? 'document'
    ..target = '_blank'
    ..style.display = 'none';
  web.document.body?.append(anchor);
  anchor.click();
  anchor.remove();
}
