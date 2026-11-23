import 'package:flutter/material.dart';

import '../tokens.dart';

class YColumn<T> {
  const YColumn({
    required this.label,
    required this.cell,
    this.flex = 1,
    this.width,
    this.sortKey,
    this.alignEnd = false,
  });

  final String label;
  final Widget Function(BuildContext context, T row) cell;
  final int flex;

  /// Fixed width instead of [flex].
  final double? width;
  final Comparable<Object?> Function(T row)? sortKey;
  final bool alignEnd;
}

class YFilter<T> {
  const YFilter(this.label, this.test);
  final String label;
  final bool Function(T row) test;
}

/// The admin list: search, filter chips, sortable columns, row → detail.
/// Rows that appear from someone else's change while the operator is
/// scrolled down wait behind a "N new · Show" pill rather than shifting the
/// list under the cursor; rows that just changed are highlighted.
class YTable<T> extends StatefulWidget {
  const YTable({
    super.key,
    required this.rows,
    required this.columns,
    required this.idOf,
    required this.searchTextOf,
    this.filters = const [],
    this.onRowTap,
    this.highlightIds = const {},
    this.alwaysShowIds = const {},
    this.searchHint = 'Search',
    this.noun = 'items',
    this.empty,
    this.toolbarTrailing = const [],
    this.minWidth = 760,
    this.selectedId,
  });

  final List<T> rows;
  final List<YColumn<T>> columns;
  final String Function(T row) idOf;
  final String Function(T row) searchTextOf;
  final List<YFilter<T>> filters;
  final ValueChanged<T>? onRowTap;
  final Set<String> highlightIds;

  /// Never held behind the pill (e.g. the row this operator just created).
  final Set<String> alwaysShowIds;
  final String searchHint;

  /// Plural noun for counts and the pill ("tools").
  final String noun;

  /// Shown when there are no rows at all (not when a search matches nothing).
  final Widget? empty;
  final List<Widget> toolbarTrailing;
  final double minWidth;
  final String? selectedId;

  @override
  State<YTable<T>> createState() => _YTableState<T>();
}

class _YTableState<T> extends State<YTable<T>> {
  final _search = TextEditingController();
  final _searchFocus = FocusNode();
  final _scroll = ScrollController();
  int _filter = -1;
  int? _sortCol;
  bool _sortAsc = true;
  Set<String>? _known;
  final Set<String> _held = {};

  @override
  void initState() {
    super.initState();
    _absorb(widget.rows, initial: true);
  }

  @override
  void didUpdateWidget(YTable<T> old) {
    super.didUpdateWidget(old);
    if (!identical(old.rows, widget.rows)) _absorb(widget.rows);
  }

  void _absorb(List<T> rows, {bool initial = false}) {
    final ids = rows.map(widget.idOf).toSet();
    if (_known == null || initial) {
      _known = ids;
      return;
    }
    final scrolled = _scroll.hasClients && _scroll.offset > 8;
    for (final id in ids.difference(_known!)) {
      if (scrolled && !widget.alwaysShowIds.contains(id)) _held.add(id);
    }
    _held.retainAll(ids);
    _known = ids;
  }

  @override
  void dispose() {
    _search.dispose();
    _searchFocus.dispose();
    _scroll.dispose();
    super.dispose();
  }

  List<T> _visible() {
    final q = _search.text.trim().toLowerCase();
    var out = widget.rows.where((r) => !_held.contains(widget.idOf(r))).where((r) {
      if (_filter >= 0 && _filter < widget.filters.length && !widget.filters[_filter].test(r)) return false;
      return q.isEmpty || widget.searchTextOf(r).toLowerCase().contains(q);
    }).toList();
    final col = _sortCol == null ? null : widget.columns[_sortCol!];
    if (col?.sortKey != null) {
      out.sort((a, b) {
        final c = col!.sortKey!(a).compareTo(col.sortKey!(b));
        return _sortAsc ? c : -c;
      });
    }
    return out;
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    if (widget.rows.isEmpty && widget.empty != null) return widget.empty!;
    final rows = _visible();
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Wrap(spacing: 8, runSpacing: 8, crossAxisAlignment: WrapCrossAlignment.center, children: [
          ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 360, minWidth: 220),
            child: TextField(
              controller: _search,
              focusNode: _searchFocus,
              onChanged: (_) => setState(() {}),
              style: const TextStyle(fontSize: 14),
              decoration: InputDecoration(
                hintText: widget.searchHint,
                prefixIcon: const Icon(Icons.search, size: 18),
                isDense: true,
                suffixIcon: _search.text.isEmpty
                    ? null
                    : IconButton(
                        tooltip: 'Clear',
                        icon: const Icon(Icons.close, size: 18),
                        onPressed: () => setState(_search.clear),
                      ),
              ),
            ),
          ),
          if (widget.filters.isNotEmpty) ...[
            _chip(context, 'All', widget.rows.length, _filter == -1, () => setState(() => _filter = -1)),
            for (var i = 0; i < widget.filters.length; i++)
              _chip(context, widget.filters[i].label, widget.rows.where(widget.filters[i].test).length, _filter == i,
                  () => setState(() => _filter = _filter == i ? -1 : i)),
          ],
          ...widget.toolbarTrailing,
        ]),
        if (_held.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: Center(
              child: FilledButton.icon(
                onPressed: () {
                  setState(_held.clear);
                  _scroll.animateTo(0, duration: const Duration(milliseconds: 200), curve: Curves.easeOut);
                },
                icon: const Icon(Icons.arrow_upward, size: 16),
                label: Text('${_held.length} new ${_held.length == 1 ? _singular(widget.noun) : widget.noun} · Show'),
              ),
            ),
          ),
        const SizedBox(height: 12),
        Expanded(
          child: Card(
            clipBehavior: Clip.antiAlias,
            child: LayoutBuilder(builder: (context, c) {
              final width = c.maxWidth < widget.minWidth ? widget.minWidth : c.maxWidth;
              final table = SizedBox(
                width: width,
                child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                  _header(context),
                  Expanded(
                    child: rows.isEmpty
                        ? Center(
                            child: Padding(
                              padding: const EdgeInsets.all(32),
                              child: Column(mainAxisSize: MainAxisSize.min, children: [
                                Text('No ${widget.noun} match${_search.text.isEmpty ? ' this filter' : ' “${_search.text}”'}.',
                                    style: TextStyle(fontSize: 14, color: scheme.onSurfaceVariant)),
                                TextButton(
                                  onPressed: () => setState(() {
                                    _search.clear();
                                    _filter = -1;
                                  }),
                                  child: const Text('Clear search and filters'),
                                ),
                              ]),
                            ),
                          )
                        : ListView.builder(
                            controller: _scroll,
                            itemCount: rows.length,
                            itemBuilder: (context, i) => _row(context, rows[i]),
                          ),
                  ),
                ]),
              );
              return c.maxWidth < widget.minWidth
                  ? SingleChildScrollView(scrollDirection: Axis.horizontal, child: table)
                  : table;
            }),
          ),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 10),
          child: Text(
            rows.length == widget.rows.length - _held.length
                ? '${rows.length} ${rows.length == 1 ? _singular(widget.noun) : widget.noun}'
                : '${rows.length} of ${widget.rows.length - _held.length} ${widget.noun}',
            style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant),
          ),
        ),
      ]);
  }

  String _singular(String noun) => noun.endsWith('ies')
      ? '${noun.substring(0, noun.length - 3)}y'
      : noun.endsWith('s')
          ? noun.substring(0, noun.length - 1)
          : noun;

  Widget _chip(BuildContext context, String label, int count, bool on, VoidCallback onTap) {
    final scheme = Theme.of(context).colorScheme;
    return FilterChip(
      selected: on,
      showCheckmark: false,
      label: Text('$label  $count'),
      labelStyle: TextStyle(fontSize: 13, fontWeight: FontWeight.w500, color: on ? scheme.onInverseSurface : scheme.onSurface),
      selectedColor: scheme.inverseSurface,
      onSelected: (_) => onTap(),
    );
  }

  Widget _cellBox(YColumn<T> col, Widget child) {
    final aligned = Align(alignment: col.alignEnd ? Alignment.centerRight : Alignment.centerLeft, child: child);
    return col.width != null ? SizedBox(width: col.width, child: aligned) : Expanded(flex: col.flex, child: aligned);
  }

  Widget _header(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      color: scheme.surfaceContainerLow,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      child: Row(children: [
        for (var i = 0; i < widget.columns.length; i++)
          _cellBox(
            widget.columns[i],
            widget.columns[i].sortKey == null
                ? _headerText(context, widget.columns[i].label)
                : InkWell(
                    borderRadius: BorderRadius.circular(4),
                    onTap: () => setState(() {
                      if (_sortCol == i) {
                        _sortAsc = !_sortAsc;
                      } else {
                        _sortCol = i;
                        _sortAsc = true;
                      }
                    }),
                    child: Row(mainAxisSize: MainAxisSize.min, children: [
                      _headerText(context, widget.columns[i].label),
                      Icon(
                        _sortCol == i ? (_sortAsc ? Icons.arrow_upward : Icons.arrow_downward) : Icons.unfold_more,
                        size: 14,
                        color: scheme.onSurfaceVariant,
                      ),
                    ]),
                  ),
          ),
      ]),
    );
  }

  Widget _headerText(BuildContext context, String s) => Text(
        s.toUpperCase(),
        style: TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.w600,
          letterSpacing: 0.3,
          color: Theme.of(context).colorScheme.onSurfaceVariant,
        ),
      );

  Widget _row(BuildContext context, T row) {
    final scheme = Theme.of(context).colorScheme;
    final status = StatusColors.of(context);
    final id = widget.idOf(row);
    final fresh = widget.highlightIds.contains(id);
    final selected = widget.selectedId == id;
    return AnimatedContainer(
      duration: const Duration(milliseconds: 400),
      decoration: BoxDecoration(
        color: selected || fresh ? status.highlight : null,
        border: Border(
          left: BorderSide(color: fresh || selected ? scheme.primary : Colors.transparent, width: 3),
          bottom: BorderSide(color: scheme.outlineVariant.withValues(alpha: 0.6)),
        ),
      ),
      child: InkWell(
        onTap: widget.onRowTap == null ? null : () => widget.onRowTap!(row),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(13, 12, 16, 12),
          child: Row(children: [
            for (final col in widget.columns) _cellBox(col, DefaultTextStyle.merge(style: const TextStyle(fontSize: 14), child: col.cell(context, row))),
          ]),
        ),
      ),
    );
  }
}

/// Two-line cell: a primary value and a muted line under it.
class YCellStack extends StatelessWidget {
  const YCellStack({super.key, required this.primary, this.secondary, this.mono = false, this.emphasis = false});
  final String primary;
  final String? secondary;
  final bool mono;
  final bool emphasis;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
      Text(primary,
          overflow: TextOverflow.ellipsis,
          style: mono
              ? monoStyle(context, size: 13, weight: FontWeight.w500, color: emphasis ? scheme.primary : null)
              : TextStyle(fontSize: 14, fontWeight: emphasis ? FontWeight.w600 : FontWeight.w500, color: emphasis ? scheme.primary : scheme.onSurface)),
      if (secondary != null)
        Text(secondary!, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: 12.5, color: scheme.onSurfaceVariant)),
    ]);
  }
}
