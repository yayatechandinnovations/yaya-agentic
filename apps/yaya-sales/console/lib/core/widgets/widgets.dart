import 'package:flutter/material.dart';

import '../domain/money.dart';

/// Small shared building blocks so every feature page stays uniform:
/// tables + dialogs/forms, Material 3, money always rendered from minor units.

class LoadingView extends StatelessWidget {
  const LoadingView({super.key});

  @override
  Widget build(BuildContext context) => const Center(child: CircularProgressIndicator());
}

class ErrorView extends StatelessWidget {
  const ErrorView(this.message, {super.key, this.onRetry});

  final String message;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Semantics(
            liveRegion: true,
            child: Text(message, style: TextStyle(color: Theme.of(context).colorScheme.error)),
          ),
          if (onRetry != null) ...[
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: onRetry,
              icon: const Icon(Icons.refresh),
              label: const Text('Retry'),
            ),
          ],
        ],
      ),
    );
  }
}

class EmptyView extends StatelessWidget {
  const EmptyView(this.message, {super.key});

  final String message;

  @override
  Widget build(BuildContext context) =>
      Center(child: Text(message, style: const TextStyle(color: Colors.black54)));
}

class SectionHeader extends StatelessWidget {
  const SectionHeader(this.title, {super.key, this.actions = const []});

  final String title;
  final List<Widget> actions;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        children: [
          Text(title, style: Theme.of(context).textTheme.titleMedium),
          const Spacer(),
          ...actions,
        ],
      ),
    );
  }
}

class MoneyText extends StatelessWidget {
  const MoneyText(this.money, {super.key, this.style});

  final Money? money;
  final TextStyle? style;

  @override
  Widget build(BuildContext context) => Text(formatMoney(money), style: style);
}

class KeyValueRow extends StatelessWidget {
  const KeyValueRow(this.label, this.value, {super.key});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 180,
            child: Text(label, style: const TextStyle(color: Colors.black54)),
          ),
          Expanded(child: SelectableText(value)),
        ],
      ),
    );
  }
}

class StatusChip extends StatelessWidget {
  const StatusChip(this.label, {super.key});

  final String label;

  @override
  Widget build(BuildContext context) => Chip(
        label: Text(label, style: const TextStyle(fontSize: 12)),
        visualDensity: VisualDensity.compact,
      );
}

String formatInstant(DateTime? at) {
  if (at == null) return '—';
  final local = at.toLocal();
  String two(int v) => v.toString().padLeft(2, '0');
  return '${local.year}-${two(local.month)}-${two(local.day)} ${two(local.hour)}:${two(local.minute)}';
}

void showErrorSnack(BuildContext context, String message) {
  ScaffoldMessenger.of(context)
    ..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(content: Text(message), backgroundColor: Colors.red.shade700));
}

void showInfoSnack(BuildContext context, String message) {
  ScaffoldMessenger.of(context)
    ..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(content: Text(message)));
}

Future<bool> confirmAction(BuildContext context, String title, String message) async {
  final ok = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text(title),
      content: Text(message),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
        FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Confirm')),
      ],
    ),
  );
  return ok ?? false;
}

/// A one-time secret (invite token, courier credential) — shown once, copyable.
Future<void> showSecretDialog(
  BuildContext context,
  String title,
  String secret, {
  String? note,
}) {
  return showDialog<void>(
    context: context,
    barrierDismissible: false,
    builder: (ctx) => AlertDialog(
      title: Text(title),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (note != null) ...[
            Text(note, style: const TextStyle(color: Colors.black54)),
            const SizedBox(height: 12),
          ],
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: Colors.grey.shade100,
              borderRadius: BorderRadius.circular(8),
            ),
            child: SelectableText(secret, style: const TextStyle(fontFamily: 'monospace')),
          ),
          const SizedBox(height: 8),
          const Text(
            'This value is shown ONCE — copy it now.',
            style: TextStyle(fontWeight: FontWeight.bold),
          ),
        ],
      ),
      actions: [
        FilledButton(onPressed: () => Navigator.pop(ctx), child: const Text('Done')),
      ],
    ),
  );
}
