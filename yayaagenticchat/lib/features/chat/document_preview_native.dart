import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:pdfx/pdfx.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../config.dart';
import '../../models/chat_message.dart';

/// Preview a PDF inline with `pdfx` from the fetched bytes. Fetching happens
/// inside the widget so the caller stays platform-agnostic.
Widget buildDocumentPreview(ChatAttachment attachment) =>
    _NativePdfPreview(attachment: attachment);

/// Native "download": open the signed URL in the OS handler so the user can
/// save/share it. Kept simple — native is not the owner's primary surface.
Future<void> downloadDocument(ChatAttachment attachment) async {
  final uri = Uri.parse(AppConfig.resolveUrl(attachment.url));
  if (await canLaunchUrl(uri)) {
    await launchUrl(uri, mode: LaunchMode.externalApplication);
  }
}

class _NativePdfPreview extends StatefulWidget {
  const _NativePdfPreview({required this.attachment});
  final ChatAttachment attachment;

  @override
  State<_NativePdfPreview> createState() => _NativePdfPreviewState();
}

class _NativePdfPreviewState extends State<_NativePdfPreview> {
  PdfControllerPinch? _controller;
  String? _error;

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
      if (!mounted) return;
      setState(() {
        _controller = PdfControllerPinch(document: PdfDocument.openData(bytes));
      });
    } catch (_) {
      if (mounted) setState(() => _error = 'error');
    }
  }

  @override
  void dispose() {
    _controller?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (_error != null) {
      return Center(
        child: Icon(Icons.broken_image_outlined,
            size: 48, color: Theme.of(context).colorScheme.outline),
      );
    }
    final controller = _controller;
    if (controller == null) {
      return const Center(child: CircularProgressIndicator());
    }
    return PdfViewPinch(controller: controller);
  }
}
