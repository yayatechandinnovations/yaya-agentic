import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../data/chat_api.dart';
import '../../data/providers.dart';
import '../../l10n/app_localizations.dart';
import '../../models/receipts.dart';
import '../chat/receipts/receipt_bubble.dart';

/// The receipts wallet (chat-experience-design.md §7.5): every ticket/receipt
/// issued to this account, rendered from the layout snapshotted at issuance —
/// a document handed to the user never retro-edits.
class ReceiptsScreen extends ConsumerStatefulWidget {
  const ReceiptsScreen({super.key});

  @override
  ConsumerState<ReceiptsScreen> createState() => _ReceiptsScreenState();
}

class _ReceiptsScreenState extends ConsumerState<ReceiptsScreen> {
  final List<ReceiptInstance> _items = [];
  String? _cursor;
  bool _loading = true;
  bool _loadingMore = false;
  bool _hasMore = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load({bool more = false}) async {
    if (more && (_loadingMore || !_hasMore)) return;
    setState(() => more ? _loadingMore = true : _loading = true);
    try {
      final page = await ref
          .read(chatApiProvider)
          .accountReceipts(cursor: more ? _cursor : null);
      if (!mounted) return;
      setState(() {
        if (!more) _items.clear();
        _items.addAll(page.receipts);
        _cursor = page.nextCursor;
        _hasMore = page.nextCursor != null;
        _loading = false;
        _loadingMore = false;
        _error = null;
      });
    } on ChatApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _loadingMore = false;
        _error = e.message;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = AppL10n.of(context);
    Widget body;
    if (_loading) {
      body = const Center(child: CircularProgressIndicator());
    } else if (_error != null) {
      body = Center(
          child: Padding(
              padding: const EdgeInsets.all(24),
              child: Text(_error!, textAlign: TextAlign.center)));
    } else if (_items.isEmpty) {
      body = Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.receipt_long_outlined,
                size: 56, color: Theme.of(context).colorScheme.outline),
            const SizedBox(height: 12),
            Text(t.receiptsEmpty, textAlign: TextAlign.center),
          ],
        ),
      );
    } else {
      body = RefreshIndicator(
        onRefresh: () => _load(),
        child: NotificationListener<ScrollNotification>(
          onNotification: (n) {
            if (n.metrics.pixels > n.metrics.maxScrollExtent - 200) {
              _load(more: true);
            }
            return false;
          },
          child: ListView.separated(
            padding: const EdgeInsets.all(12),
            itemCount: _items.length + (_hasMore ? 1 : 0),
            separatorBuilder: (_, _) => const SizedBox(height: 4),
            itemBuilder: (context, i) {
              if (i >= _items.length) {
                return const Center(
                    child: Padding(
                        padding: EdgeInsets.all(12),
                        child: CircularProgressIndicator()));
              }
              final r = _items[i];
              final template = r.snapshot;
              return ListTile(
                leading: const Icon(Icons.receipt_long_outlined),
                title: Text(template?.name ?? t.receiptsFallbackTitle),
                subtitle: Text([
                  if (r.handle != null) r.handle!,
                  if (r.createdAt != null) _shortDate(r.createdAt!),
                ].join(' · ')),
                trailing: const Icon(Icons.chevron_right),
                onTap: template == null
                    ? null
                    : () => showReceiptSheet(context,
                        instance: r, template: template, accent: Brand.violet),
              );
            },
          ),
        ),
      );
    }
    return Scaffold(
      appBar: AppBar(title: Text(t.receiptsTitle)),
      body: body,
    );
  }

  static String _shortDate(String iso) {
    final dt = DateTime.tryParse(iso);
    if (dt == null) return iso;
    final l = dt.toLocal();
    return '${l.year}-${l.month.toString().padLeft(2, '0')}-${l.day.toString().padLeft(2, '0')}';
  }
}
