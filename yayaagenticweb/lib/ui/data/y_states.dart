import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../feedback/api_error.dart';
import '../feedback/toast.dart';

class YEmptyState extends StatelessWidget {
  const YEmptyState({super.key, required this.title, this.message, this.icon = Icons.inbox_outlined, this.action});
  final String title;
  final String? message;
  final IconData icon;
  final Widget? action;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 48),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 440),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Icon(icon, size: 36, color: scheme.onSurfaceVariant),
            const SizedBox(height: 12),
            Text(title, textAlign: TextAlign.center, style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: scheme.onSurface)),
            if (message != null) ...[
              const SizedBox(height: 6),
              Text(message!, textAlign: TextAlign.center, style: TextStyle(fontSize: 14, height: 1.5, color: scheme.onSurfaceVariant)),
            ],
            if (action != null) ...[const SizedBox(height: 16), action!],
          ]),
        ),
      ),
    );
  }
}

/// What failed, a Retry, and copyable details for a bug report.
class YErrorState extends StatelessWidget {
  const YErrorState({super.key, required this.error, this.onRetry, this.what = 'this list'});
  final Object error;
  final VoidCallback? onRetry;
  final String what;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final info = ApiErrorInfo.from(error);
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 480),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Icon(Icons.cloud_off_outlined, size: 36, color: scheme.error),
            const SizedBox(height: 12),
            Text('Couldn’t load $what', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: scheme.onSurface)),
            const SizedBox(height: 6),
            Text(info.message, textAlign: TextAlign.center, style: TextStyle(fontSize: 14, height: 1.5, color: scheme.onSurfaceVariant)),
            const SizedBox(height: 16),
            Wrap(spacing: 8, children: [
              if (onRetry != null)
                FilledButton.icon(onPressed: onRetry, icon: const Icon(Icons.refresh, size: 18), label: const Text('Retry')),
              OutlinedButton.icon(
                onPressed: () {
                  Clipboard.setData(ClipboardData(text: info.details));
                  showToast(context, 'Error details copied');
                },
                icon: const Icon(Icons.copy, size: 16),
                label: const Text('Copy details'),
              ),
            ]),
          ]),
        ),
      ),
    );
  }
}

/// Placeholder rows for a first load (refetches keep the real rows instead).
class YSkeletonRows extends StatelessWidget {
  const YSkeletonRows({super.key, this.count = 5});
  final int count;

  @override
  Widget build(BuildContext context) {
    final c = Theme.of(context).colorScheme.surfaceContainerHigh;
    Widget bar(double w) => Container(width: w, height: 12, decoration: BoxDecoration(color: c, borderRadius: BorderRadius.circular(4)));
    return Semantics(
      label: 'Loading',
      child: Column(children: [
        for (var i = 0; i < count; i++)
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
            child: Row(children: [bar(160), const SizedBox(width: 40), bar(80), const SizedBox(width: 40), bar(120), const Spacer(), bar(60)]),
          ),
      ]),
    );
  }
}
