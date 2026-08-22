import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:pdfx/pdfx.dart';

import '../../config.dart';
import '../../l10n/app_localizations.dart';
import '../../models/chat_message.dart';

/// Shared in-app PDF preview (owner-ai-experience v3 §D1). Fetches the signed
/// bytes and renders them into the Flutter tree with `pdfx` — on BOTH web and
/// native — so it never depends on HtmlElementView/iframe compositing (which
/// rendered blank on web). If rendering fails (e.g. pdf.js unavailable) it
/// degrades to an "open in new tab" affordance supplied by the platform layer.
class PdfPreviewView extends StatefulWidget {
  const PdfPreviewView({
    super.key,
    required this.attachment,
    required this.onOpenExternal,
  });

  final ChatAttachment attachment;
  final VoidCallback onOpenExternal;

  @override
  State<PdfPreviewView> createState() => _PdfPreviewViewState();
}

class _PdfPreviewViewState extends State<PdfPreviewView> {
  PdfControllerPinch? _controller;
  bool _failed = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final url = AppConfig.resolveUrl(widget.attachment.url);
      final res = await Dio().get<List<int>>(
        url,
        options: Options(responseType: ResponseType.bytes),
      );
      final bytes = Uint8List.fromList(res.data ?? const []);
      if (bytes.isEmpty) throw StateError('empty');
      final controller =
          PdfControllerPinch(document: PdfDocument.openData(bytes));
      if (!mounted) {
        controller.dispose();
        return;
      }
      setState(() => _controller = controller);
    } catch (_) {
      if (mounted) setState(() => _failed = true);
    }
  }

  @override
  void dispose() {
    _controller?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = AppL10n.of(context);
    if (_failed) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.picture_as_pdf_outlined,
                size: 48, color: Theme.of(context).colorScheme.outline),
            const SizedBox(height: 16),
            FilledButton.icon(
              onPressed: widget.onOpenExternal,
              icon: const Icon(Icons.open_in_new),
              label: Text(t.openInNewTab),
            ),
          ],
        ),
      );
    }
    final controller = _controller;
    if (controller == null) {
      return const Center(child: CircularProgressIndicator());
    }
    return PdfViewPinch(controller: controller);
  }
}
