import 'package:flutter/material.dart';

import '../../../models/admin/whatsapp_template.dart';

/// `sl_ops_alert_v2` is version 2 of `sl_ops_alert`; a name without a `_vN`
/// suffix is version 0 of itself.
class TemplateVersion {
  const TemplateVersion(this.family, this.number);

  final String family;
  final int number;

  static final _suffix = RegExp(r'^(.+)_v(\d+)$');

  factory TemplateVersion.of(String name) {
    final m = _suffix.firstMatch(name);
    if (m == null) return TemplateVersion(name, 0);
    return TemplateVersion(m.group(1)!, int.parse(m.group(2)!));
  }
}

/// For each template that a later version of the same name and language
/// replaces, the name of the latest version. Approved wording cannot change,
/// so integrations bump the name instead; the old versions stay on the
/// account long after nothing sends them.
Map<int, String> newerVersions(Iterable<TemplateListRow> rows) {
  String key(TemplateListRow r) => '${TemplateVersion.of(r.name).family}|${r.locale}';
  final latest = <String, TemplateListRow>{};
  for (final r in rows) {
    final current = latest[key(r)];
    if (current == null ||
        TemplateVersion.of(current.name).number < TemplateVersion.of(r.name).number) {
      latest[key(r)] = r;
    }
  }
  return {
    for (final r in rows)
      if (TemplateVersion.of(latest[key(r)]!.name).number > TemplateVersion.of(r.name).number)
        r.id: latest[key(r)]!.name,
  };
}

/// Versions of one name together, the newest first.
int compareTemplates(TemplateListRow a, TemplateListRow b) {
  final va = TemplateVersion.of(a.name);
  final vb = TemplateVersion.of(b.name);
  final byFamily = va.family.compareTo(vb.family);
  if (byFamily != 0) return byFamily;
  final byVersion = vb.number.compareTo(va.number);
  if (byVersion != 0) return byVersion;
  return a.locale.compareTo(b.locale);
}

/// The search box, status chip and «Show hidden» switch of a template list.
class TemplateListFilter {
  const TemplateListFilter({this.query = '', this.status, this.showHidden = false});

  final String query;

  /// `null` shows every status.
  final String? status;
  final bool showHidden;

  TemplateListFilter copyWith({String? query, String? Function()? status, bool? showHidden}) =>
      TemplateListFilter(
        query: query ?? this.query,
        status: status == null ? this.status : status(),
        showHidden: showHidden ?? this.showHidden,
      );

  bool _visible(TemplateListRow r) {
    if (r.hidden && !showHidden) return false;
    final q = query.trim().toLowerCase();
    return q.isEmpty || r.name.toLowerCase().contains(q) || r.body.toLowerCase().contains(q);
  }

  /// What the status chips count: everything the other filters let through.
  Map<String, int> statusCounts(Iterable<TemplateListRow> rows) {
    final out = <String, int>{};
    for (final r in rows.where(_visible)) {
      out[r.status] = (out[r.status] ?? 0) + 1;
    }
    return out;
  }

  List<T> apply<T extends TemplateListRow>(Iterable<T> rows) =>
      rows.where((r) => _visible(r) && (status == null || r.status == status)).toList()
        ..sort(compareTemplates);
}

const _statusOrder = ['APPROVED', 'SUBMITTED', 'REJECTED', 'DRAFT', 'PAUSED'];

class TemplateListToolbar extends StatefulWidget {
  const TemplateListToolbar({
    super.key,
    required this.rows,
    required this.filter,
    required this.onChanged,
  });

  final List<TemplateListRow> rows;
  final TemplateListFilter filter;
  final ValueChanged<TemplateListFilter> onChanged;

  @override
  State<TemplateListToolbar> createState() => _TemplateListToolbarState();
}

class _TemplateListToolbarState extends State<TemplateListToolbar> {
  late final _search = TextEditingController(text: widget.filter.query);

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final filter = widget.filter;
    final counts = filter.statusCounts(widget.rows);
    final total = counts.values.fold<int>(0, (a, b) => a + b);
    final hiddenCount = widget.rows.where((r) => r.hidden).length;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Expanded(
              child: TextField(
                controller: _search,
                decoration: InputDecoration(
                  isDense: true,
                  prefixIcon: const Icon(Icons.search, size: 20),
                  hintText: 'Search name or wording',
                  border: const OutlineInputBorder(),
                  suffixIcon: _search.text.isEmpty
                      ? null
                      : IconButton(
                          tooltip: 'Clear search',
                          icon: const Icon(Icons.close, size: 18),
                          onPressed: () {
                            _search.clear();
                            widget.onChanged(filter.copyWith(query: ''));
                          },
                        ),
                ),
                onChanged: (v) => widget.onChanged(filter.copyWith(query: v)),
              ),
            ),
            if (hiddenCount > 0 || filter.showHidden) ...[
              const SizedBox(width: 8),
              FilterChip(
                avatar: Icon(
                  filter.showHidden ? Icons.visibility : Icons.visibility_off_outlined,
                  size: 18,
                ),
                label: Text('Show hidden ($hiddenCount)'),
                selected: filter.showHidden,
                showCheckmark: false,
                tooltip:
                    'Hidden templates are only left out of this list; nothing changes for sending.',
                onSelected: (v) => widget.onChanged(filter.copyWith(showHidden: v)),
              ),
            ],
          ],
        ),
        const SizedBox(height: 8),
        Wrap(
          spacing: 6,
          runSpacing: 6,
          children: [
            ChoiceChip(
              label: Text('All ($total)'),
              selected: filter.status == null,
              onSelected: (_) => widget.onChanged(filter.copyWith(status: () => null)),
            ),
            for (final s in _statusOrder)
              if ((counts[s] ?? 0) > 0 || filter.status == s)
                ChoiceChip(
                  label: Text('${TemplateStatusInfo.of(s).label} (${counts[s] ?? 0})'),
                  selected: filter.status == s,
                  onSelected: (on) =>
                      widget.onChanged(filter.copyWith(status: () => on ? s : null)),
                ),
          ],
        ),
      ],
    );
  }
}

/// Offers to hide, in one go, the templates a later version replaces.
class OlderVersionsBanner extends StatelessWidget {
  const OlderVersionsBanner({super.key, required this.rows, required this.onHide});

  /// Not hidden yet, and the caller may hide them.
  final List<TemplateListRow> rows;
  final VoidCallback onHide;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final names = rows.map((r) => r.name).toSet().toList();
    final shown = names.take(3).join(', ');
    final more = names.length > 3 ? ' and ${names.length - 3} more' : '';
    final n = rows.length;
    return Container(
      padding: const EdgeInsets.fromLTRB(12, 8, 8, 8),
      decoration: BoxDecoration(
        color: scheme.secondaryContainer,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        children: [
          Icon(Icons.history, size: 20, color: scheme.onSecondaryContainer),
          const SizedBox(width: 10),
          Expanded(
            // The button moves under the text when both do not fit on one line.
            child: Wrap(
              alignment: WrapAlignment.spaceBetween,
              crossAxisAlignment: WrapCrossAlignment.center,
              spacing: 8,
              runSpacing: 6,
              children: [
                Text(
                  '$n template${n == 1 ? ' has' : 's have'} a newer version: $shown$more.',
                  style: TextStyle(color: scheme.onSecondaryContainer),
                ),
                FilledButton.tonal(onPressed: onHide, child: const Text('Hide older versions')),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// Lists what «Hide older versions» will hide and asks before doing it.
Future<bool> confirmHideOlderVersions(
  BuildContext context,
  List<TemplateListRow> rows,
  Map<int, String> newer,
) async {
  return await showDialog<bool>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          title: Text('Hide ${rows.length} older version${rows.length == 1 ? '' : 's'}?'),
          content: SizedBox(
            width: 460,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'They stay on the WhatsApp account and nothing changes for '
                  'sending; they are only left out of this list. «Show hidden» '
                  'brings them back.',
                ),
                const SizedBox(height: 12),
                ConstrainedBox(
                  constraints: const BoxConstraints(maxHeight: 280),
                  child: ListView(
                    shrinkWrap: true,
                    children: [
                      for (final r in rows)
                        Padding(
                          padding: const EdgeInsets.symmetric(vertical: 2),
                          child: Text(
                            '${r.name} (${r.locale})  →  ${newer[r.id]}',
                            style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                          ),
                        ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(dialogContext, false),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(dialogContext, true),
              child: Text('Hide ${rows.length}'),
            ),
          ],
        ),
      ) ??
      false;
}

/// Above the rows: how many are shown, or what is selected and what can be
/// done with it.
class TemplateSelectionBar extends StatelessWidget {
  const TemplateSelectionBar({
    super.key,
    required this.shown,
    required this.total,
    required this.selectable,
    required this.selected,
    required this.onSelectAll,
    required this.onClear,
    required this.onHide,
    required this.onUnhide,
    this.onShare,
  });

  final List<TemplateListRow> shown;
  final int total;

  /// Ids among [shown] the caller may hide or unhide.
  final Set<int> selectable;
  final Set<int> selected;
  final VoidCallback onSelectAll;
  final VoidCallback onClear;
  final VoidCallback onHide;
  final VoidCallback onUnhide;

  /// Offered only where the caller decides who uses a template.
  final VoidCallback? onShare;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final picked = shown.where((r) => selected.contains(r.id)).toList();
    final all = selectable.isNotEmpty && selectable.every(selected.contains);
    return ConstrainedBox(
      constraints: const BoxConstraints(minHeight: 40),
      child: Row(
        children: [
          Tooltip(
            message: all || picked.isNotEmpty ? 'Clear selection' : 'Select all shown',
            child: Checkbox(
              tristate: true,
              value: picked.isEmpty ? false : (all ? true : null),
              onChanged: selectable.isEmpty
                  ? null
                  : (_) => picked.isEmpty ? onSelectAll() : onClear(),
            ),
          ),
          Expanded(
            child: picked.isEmpty
                ? Text(
                    shown.length == total
                        ? '$total template${total == 1 ? '' : 's'}'
                        : 'Showing ${shown.length} of $total',
                    style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.outline),
                  )
                // The actions move under the count when the pane is narrow.
                : Wrap(
                    crossAxisAlignment: WrapCrossAlignment.center,
                    spacing: 4,
                    children: [
                      Padding(
                        padding: const EdgeInsets.only(right: 8),
                        child: Text('${picked.length} selected', style: theme.textTheme.labelLarge),
                      ),
                      if (onShare != null)
                        TextButton.icon(
                          onPressed: onShare,
                          icon: const Icon(Icons.group_add_outlined, size: 18),
                          label: const Text('Share…'),
                        ),
                      if (picked.any((r) => !r.hidden))
                        TextButton.icon(
                          onPressed: onHide,
                          icon: const Icon(Icons.visibility_off_outlined, size: 18),
                          label: const Text('Hide'),
                        ),
                      if (picked.any((r) => r.hidden))
                        TextButton.icon(
                          onPressed: onUnhide,
                          icon: const Icon(Icons.visibility_outlined, size: 18),
                          label: const Text('Unhide'),
                        ),
                      TextButton(onPressed: onClear, child: const Text('Clear')),
                    ],
                  ),
          ),
        ],
      ),
    );
  }
}

/// An entry of a row's ⋮ menu.
class TemplateAction {
  const TemplateAction(this.label, this.icon, this.onPressed, {this.destructive = false});

  final String label;
  final IconData icon;
  final VoidCallback onPressed;
  final bool destructive;
}

/// One template as a compact row: status, name and tags at a glance; the
/// wording and the rest of the record when expanded.
class TemplateRowCard extends StatefulWidget {
  const TemplateRowCard({
    super.key,
    required this.row,
    required this.subtitle,
    required this.details,
    this.newerVersion,
    this.tags = const [],
    this.primaryAction,
    this.trailing,
    this.menu = const [],
    this.selected = false,
    this.onSelected,
    this.notSelectableReason,
  });

  final TemplateListRow row;
  final String subtitle;
  final Widget details;

  /// The name of the version that replaces this one, if any.
  final String? newerVersion;
  final List<Widget> tags;

  /// The next step for this template, shown on the row itself.
  final Widget? primaryAction;
  final Widget? trailing;
  final List<TemplateAction> menu;
  final bool selected;

  /// Null when the caller may not hide this template.
  final ValueChanged<bool>? onSelected;
  final String? notSelectableReason;

  @override
  State<TemplateRowCard> createState() => _TemplateRowCardState();
}

class _TemplateRowCardState extends State<TemplateRowCard> {
  bool _expanded = false;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final row = widget.row;
    final checkbox = Checkbox(
      value: widget.selected,
      onChanged: widget.onSelected == null ? null : (v) => widget.onSelected!(v ?? false),
    );
    return Opacity(
      opacity: row.hidden ? 0.6 : 1,
      child: Card(
        margin: EdgeInsets.zero,
        clipBehavior: Clip.antiAlias,
        color: widget.selected ? theme.colorScheme.secondaryContainer.withValues(alpha: 0.5) : null,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            InkWell(
              onTap: () => setState(() => _expanded = !_expanded),
              child: Padding(
                padding: const EdgeInsets.fromLTRB(4, 6, 4, 6),
                // Narrow panes put the status and the next step under the name,
                // so the name keeps its width.
                child: LayoutBuilder(
                  builder: (context, constraints) {
                    final wide = constraints.maxWidth >= 640;
                    final badge = TemplateStatusBadge(status: row.status);
                    return Row(
                      children: [
                        widget.onSelected == null && widget.notSelectableReason != null
                            ? Tooltip(message: widget.notSelectableReason!, child: checkbox)
                            : checkbox,
                        // A fixed width keeps the names in one column whatever the status.
                        if (wide)
                          SizedBox(
                            width: 116,
                            child: Align(alignment: Alignment.centerLeft, child: badge),
                          ),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Wrap(
                                spacing: 6,
                                runSpacing: 2,
                                crossAxisAlignment: WrapCrossAlignment.center,
                                children: [
                                  if (!wide) badge,
                                  Text(
                                    '${row.name} (${row.locale})',
                                    style: theme.textTheme.titleSmall,
                                  ),
                                  if (widget.newerVersion != null)
                                    TemplateTag(
                                      label: 'Older version',
                                      icon: Icons.history,
                                      tooltip: 'Replaced by ${widget.newerVersion}',
                                    ),
                                  if (row.hidden)
                                    const TemplateTag(
                                      label: 'Hidden',
                                      icon: Icons.visibility_off_outlined,
                                    ),
                                  ...widget.tags,
                                ],
                              ),
                              const SizedBox(height: 2),
                              Text(
                                widget.subtitle,
                                style: theme.textTheme.bodySmall?.copyWith(
                                  color: theme.colorScheme.outline,
                                ),
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                              ),
                              if (!wide && widget.primaryAction != null)
                                Padding(
                                  padding: const EdgeInsets.only(top: 6),
                                  child: widget.primaryAction!,
                                ),
                            ],
                          ),
                        ),
                        if (wide && widget.primaryAction != null) ...[
                          const SizedBox(width: 8),
                          widget.primaryAction!,
                        ],
                        if (widget.trailing != null) ...[
                          const SizedBox(width: 8),
                          widget.trailing!,
                        ],
                        if (widget.menu.isNotEmpty)
                          PopupMenuButton<int>(
                            tooltip: 'More actions',
                            icon: const Icon(Icons.more_vert),
                            onSelected: (i) => widget.menu[i].onPressed(),
                            itemBuilder: (_) => [
                              for (var i = 0; i < widget.menu.length; i++)
                                PopupMenuItem(
                                  value: i,
                                  child: ListTile(
                                    dense: true,
                                    contentPadding: EdgeInsets.zero,
                                    leading: Icon(
                                      widget.menu[i].icon,
                                      size: 20,
                                      color: widget.menu[i].destructive
                                          ? theme.colorScheme.error
                                          : null,
                                    ),
                                    title: Text(
                                      widget.menu[i].label,
                                      style: widget.menu[i].destructive
                                          ? TextStyle(color: theme.colorScheme.error)
                                          : null,
                                    ),
                                  ),
                                ),
                            ],
                          ),
                        Icon(_expanded ? Icons.expand_less : Icons.expand_more, size: 20),
                        const SizedBox(width: 4),
                      ],
                    );
                  },
                ),
              ),
            ),
            if (_expanded) ...[
              const Divider(height: 1),
              Padding(padding: const EdgeInsets.fromLTRB(16, 12, 16, 12), child: widget.details),
            ],
          ],
        ),
      ),
    );
  }
}

class TemplateTag extends StatelessWidget {
  const TemplateTag({super.key, required this.label, this.icon, this.tooltip});

  final String label;
  final IconData? icon;
  final String? tooltip;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final tag = Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
      decoration: BoxDecoration(
        border: Border.all(color: scheme.outlineVariant),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (icon != null) ...[
            Icon(icon, size: 12, color: scheme.onSurfaceVariant),
            const SizedBox(width: 3),
          ],
          Text(label, style: TextStyle(fontSize: 11, color: scheme.onSurfaceVariant)),
        ],
      ),
    );
    return tooltip == null ? tag : Tooltip(message: tooltip!, child: tag);
  }
}

class TemplateStatusBadge extends StatelessWidget {
  const TemplateStatusBadge({super.key, required this.status});
  final String status;

  Color _bg(ColorScheme s) {
    switch (status) {
      case 'APPROVED':
        return s.primaryContainer;
      case 'SUBMITTED':
        return s.tertiaryContainer;
      case 'REJECTED':
        return s.errorContainer;
      case 'PAUSED':
        return s.secondaryContainer;
      default:
        return s.surfaceContainerHighest;
    }
  }

  Color _fg(ColorScheme s) {
    switch (status) {
      case 'APPROVED':
        return s.onPrimaryContainer;
      case 'SUBMITTED':
        return s.onTertiaryContainer;
      case 'REJECTED':
        return s.onErrorContainer;
      case 'PAUSED':
        return s.onSecondaryContainer;
      default:
        return s.onSurfaceVariant;
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(color: _bg(scheme), borderRadius: BorderRadius.circular(12)),
      child: Text(
        TemplateStatusInfo.of(status).label,
        style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: _fg(scheme)),
      ),
    );
  }
}
