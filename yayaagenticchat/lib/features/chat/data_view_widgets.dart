import 'package:flutter/material.dart';

import '../../l10n/app_localizations.dart';
import '../../models/data_view.dart';

/// Styled data views (owner-ai-experience v3 §D4). [DataCardView] is the owner's
/// requested "legend | value" two-column layout; [DataTableView] is a titled,
/// horizontally scrollable table with per-column formatting and an optional
/// totals row.

class DataCardView extends StatelessWidget {
  const DataCardView({super.key, required this.card, required this.accent});

  final DataCardPayload card;
  final Color accent;

  @override
  Widget build(BuildContext context) {
    if (card.rows.isEmpty && (card.title == null || card.title!.isEmpty)) {
      return const SizedBox.shrink();
    }
    final scheme = Theme.of(context).colorScheme;
    return Container(
      margin: const EdgeInsets.symmetric(vertical: 4),
      constraints: BoxConstraints(maxWidth: MediaQuery.of(context).size.width * 0.86),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: scheme.outlineVariant),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          if (card.title != null && card.title!.isNotEmpty)
            Padding(
              padding: const EdgeInsets.fromLTRB(14, 12, 14, 4),
              child: Text(
                card.title!,
                style: Theme.of(context)
                    .textTheme
                    .titleSmall
                    ?.copyWith(fontWeight: FontWeight.w700),
              ),
            ),
          for (var i = 0; i < card.rows.length; i++)
            _CardRow(
              row: card.rows[i],
              divider: i != card.rows.length - 1,
              accent: accent,
            ),
          const SizedBox(height: 6),
        ],
      ),
    );
  }
}

class _CardRow extends StatelessWidget {
  const _CardRow({required this.row, required this.divider, required this.accent});

  final DataCardRow row;
  final bool divider;
  final Color accent;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
      decoration: divider
          ? BoxDecoration(
              border: Border(bottom: BorderSide(color: scheme.outlineVariant.withValues(alpha: 0.4))),
            )
          : null,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            flex: 5,
            child: Text(
              row.label,
              style: text.bodyMedium?.copyWith(color: scheme.onSurfaceVariant),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            flex: 4,
            child: Text(
              formatDataValue(row.value, row.type),
              textAlign: TextAlign.right,
              style: text.bodyMedium?.copyWith(fontWeight: FontWeight.w700),
            ),
          ),
        ],
      ),
    );
  }
}

class DataTableView extends StatelessWidget {
  const DataTableView({super.key, required this.table, required this.accent});

  final DataTablePayload table;
  final Color accent;

  @override
  Widget build(BuildContext context) {
    if (table.columns.isEmpty) return const SizedBox.shrink();
    final scheme = Theme.of(context).colorScheme;
    final t = AppL10n.of(context);
    return Container(
      margin: const EdgeInsets.symmetric(vertical: 4),
      constraints: BoxConstraints(maxWidth: MediaQuery.of(context).size.width * 0.9),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: scheme.outlineVariant),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          if (table.title != null && table.title!.isNotEmpty)
            Padding(
              padding: const EdgeInsets.fromLTRB(14, 12, 14, 4),
              child: Text(
                table.title!,
                style: Theme.of(context)
                    .textTheme
                    .titleSmall
                    ?.copyWith(fontWeight: FontWeight.w700),
              ),
            ),
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: DataTable(
              headingRowHeight: 40,
              dataRowMinHeight: 36,
              dataRowMaxHeight: 48,
              columnSpacing: 24,
              horizontalMargin: 14,
              columns: [
                for (final c in table.columns)
                  DataColumn(
                    label: Text(
                      c.label,
                      style: const TextStyle(fontWeight: FontWeight.w700),
                    ),
                    numeric: _isNumeric(c.type),
                  ),
              ],
              rows: [
                for (final r in table.rows)
                  DataRow(
                    cells: [
                      for (final c in table.columns)
                        DataCell(Text(formatDataValue(r[c.key], c.type))),
                    ],
                  ),
                if (table.hasTotals)
                  DataRow(
                    color: WidgetStatePropertyAll(scheme.surfaceContainerHigh),
                    cells: [
                      for (var i = 0; i < table.columns.length; i++)
                        DataCell(Text(
                          i == 0 && !table.totals!.containsKey(table.columns[i].key)
                              ? t.totalsLabel
                              : formatDataValue(
                                  table.totals![table.columns[i].key],
                                  table.columns[i].type),
                          style: const TextStyle(fontWeight: FontWeight.w700),
                        )),
                    ],
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  static bool _isNumeric(DataValueType type) =>
      type == DataValueType.money ||
      type == DataValueType.count ||
      type == DataValueType.percent;
}
