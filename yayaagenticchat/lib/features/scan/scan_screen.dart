import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

import '../../app/theme.dart';
import '../../config.dart';
import '../../data/chat_api.dart';
import '../../data/providers.dart';
import '../../l10n/app_localizations.dart';
import '../../models/entry_point_card.dart';

/// Add a tile by scanning its QR (or pasting the link/handle) — the on-ramp
/// described in chat-channel-design.md §11.
class ScanScreen extends ConsumerStatefulWidget {
  const ScanScreen({super.key});

  @override
  ConsumerState<ScanScreen> createState() => _ScanScreenState();
}

class _ScanScreenState extends ConsumerState<ScanScreen> {
  final MobileScannerController _controller = MobileScannerController();
  bool _busy = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _onDetect(BarcodeCapture capture) async {
    if (_busy) return;
    for (final b in capture.barcodes) {
      final raw = b.rawValue;
      if (raw != null && raw.isNotEmpty) {
        await _handle(raw);
        return;
      }
    }
  }

  Future<void> _handle(String raw) async {
    final handle = AppConfig.handleFromDeepLink(raw);
    if (handle == null) {
      _toast(AppL10n.of(context).notAYayaCode);
      return;
    }
    setState(() => _busy = true);
    try {
      final card = await ref.read(chatApiProvider).resolve(handle);
      if (!mounted) return;
      await _confirm(card);
    } on ChatApiException {
      if (mounted) _toast(AppL10n.of(context).chatNotFound);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _confirm(EntryPointCard card) async {
    final accent = colorFromHex(card.accentColorHex);
    final t = AppL10n.of(context);
    final add = await showModalBottomSheet<bool>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              CircleAvatar(
                radius: 28,
                backgroundColor: accent,
                foregroundColor: Colors.white,
                child: ClipOval(
                  child: Image.network(AppConfig.resolveUrl(card.logoUrl), width: 56, height: 56,
                      fit: BoxFit.cover,
                      errorBuilder: (_, _, _) =>
                          Text(card.displayName.isEmpty ? '?' : card.displayName[0])),
                ),
              ),
              const SizedBox(height: 12),
              Text(card.displayName, style: Theme.of(ctx).textTheme.titleLarge),
              const SizedBox(height: 4),
              Text(card.requiresAuth ? t.signInRequired : t.chatAsGuest),
              const SizedBox(height: 20),
              FilledButton(
                onPressed: () => Navigator.pop(ctx, true),
                child: Text(t.addToMyChats),
              ),
            ],
          ),
        ),
      ),
    );
    if (add == true) {
      await ref.read(tilesProvider.notifier).addFromCard(card);
      if (mounted) context.go('/chat/${card.handle}');
    }
  }

  Future<void> _manualEntry() async {
    final t = AppL10n.of(context);
    final controller = TextEditingController();
    final value = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(t.addByLink),
        content: TextField(
          controller: controller,
          autofocus: true,
          decoration: InputDecoration(hintText: t.pasteLinkHint),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: Text(t.cancel)),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, controller.text), child: Text(t.add)),
        ],
      ),
    );
    if (value != null && value.trim().isNotEmpty) await _handle(value);
  }

  void _toast(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  Widget build(BuildContext context) {
    final t = AppL10n.of(context);
    return Scaffold(
      appBar: AppBar(
        title: Text(t.scanTitle),
        actions: [
          IconButton(
            icon: const Icon(Icons.keyboard),
            tooltip: t.addByLink,
            onPressed: _manualEntry,
          ),
        ],
      ),
      body: Stack(
        children: [
          MobileScanner(controller: _controller, onDetect: _onDetect),
          if (_busy) const Center(child: CircularProgressIndicator()),
          Align(
            alignment: Alignment.bottomCenter,
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Text(
                t.scanHint,
                style: const TextStyle(color: Colors.white, backgroundColor: Colors.black54),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
