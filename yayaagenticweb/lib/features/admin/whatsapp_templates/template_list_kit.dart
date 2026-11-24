import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../models/admin/whatsapp_template.dart';
import '../../../ui/feedback/api_error.dart';
import '../../../ui/data/y_badge.dart';
import '../../../ui/data/y_sheet.dart';
import '../../../ui/data/y_states.dart';
import '../../../ui/data/y_table.dart';
import '../../../ui/form/y_fields.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/tokens.dart';

/// How the template screens open a side sheet. Tests swap in a plain route:
/// the kit's sheet frame (`Semantics(scopesRoute: true)` without
/// `explicitChildNodes`) fails a debug assertion when it mounts.
Future<R?> Function<R>(BuildContext context, {required WidgetBuilder builder, double width, String label})
    openTemplateSheet = showYSheet;

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

/// What the table lists: hidden templates only when asked for, versions of
/// one name together. Search and status chips are the table's own.
List<T> listedTemplates<T extends TemplateListRow>(Iterable<T> rows, {required bool showHidden}) =>
    rows.where((r) => showHidden || !r.hidden).toList()..sort(compareTemplates);

const _statusOrder = ['APPROVED', 'SUBMITTED', 'REJECTED', 'DRAFT', 'PAUSED'];

/// One chip per status that occurs in [rows], in review order.
List<YFilter<T>> templateStatusFilters<T extends TemplateListRow>(List<T> rows) {
  final present = rows.map((r) => r.status).toSet();
  return [
    for (final s in [..._statusOrder, ...present.where((s) => !_statusOrder.contains(s))])
      if (present.contains(s)) YFilter<T>(TemplateStatusInfo.of(s).label, (r) => r.status == s),
  ];
}

/// Any template WhatsApp is still reviewing — the list keeps polling while so.
bool anyPendingReview(Iterable<TemplateListRow> rows) => rows.any((r) => r.status == 'SUBMITTED');

/// The review state as a word and an icon, never colour alone.
class TemplateStatusBadge extends StatelessWidget {
  const TemplateStatusBadge({super.key, required this.status});
  final String status;

  @override
  Widget build(BuildContext context) {
    final label = TemplateStatusInfo.of(status).label;
    return switch (status) {
      'APPROVED' => YBadge(label, tone: YTone.success, icon: Icons.check_circle_outline),
      'SUBMITTED' => YBadge(label, tone: YTone.info, icon: Icons.hourglass_top),
      'REJECTED' => YBadge(label, tone: YTone.danger, icon: Icons.block),
      'PAUSED' => YBadge(label, tone: YTone.warning, icon: Icons.pause_circle_outline),
      _ => YBadge(label, icon: Icons.edit_note),
    };
  }
}

/// The status cell: the badge, and why WhatsApp said no when it did.
class TemplateStatusCell extends StatelessWidget {
  const TemplateStatusCell({super.key, required this.row});
  final TemplateListRow row;

  @override
  Widget build(BuildContext context) {
    final reason = row.rejectionReason;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
      TemplateStatusBadge(status: row.status),
      if (row.status == 'REJECTED' && reason != null && reason.isNotEmpty)
        Padding(
          padding: const EdgeInsets.only(top: 4),
          child: Tooltip(
            message: reason,
            child: Text(reason,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(fontSize: 12.5, color: Theme.of(context).colorScheme.error)),
          ),
        ),
    ]);
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
    final names = rows.map((r) => r.name).toSet().toList();
    final shown = names.take(3).join(', ');
    final more = names.length > 3 ? ' and ${names.length - 3} more' : '';
    final n = rows.length;
    return YBanner(
      tone: YBannerTone.info,
      message: '$n template${n == 1 ? ' has' : 's have'} a newer version: $shown$more.',
      actions: [FilledButton.tonal(onPressed: onHide, child: const Text('Hide older versions'))],
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
                  style: TextStyle(fontSize: 14, height: 1.5),
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
                          child: Text('${r.name} (${r.locale})  →  ${newer[r.id]}',
                              style: monoStyle(dialogContext, size: 12.5)),
                        ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          actions: [
            OutlinedButton(
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

/// «Show hidden (n)» next to the table search.
class ShowHiddenChip extends StatelessWidget {
  const ShowHiddenChip({super.key, required this.count, required this.on, required this.onChanged});
  final int count;
  final bool on;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) => FilterChip(
        avatar: Icon(on ? Icons.visibility : Icons.visibility_off_outlined, size: 18),
        label: Text('Show hidden ($count)'),
        selected: on,
        showCheckmark: false,
        tooltip: 'Hidden templates are only left out of this list; nothing changes for sending.',
        onSelected: onChanged,
      );
}

/// What is selected and what can be done with it, above the table.
class TemplateSelectionBar extends StatelessWidget {
  const TemplateSelectionBar({
    super.key,
    required this.selected,
    required this.onClear,
    required this.onHide,
    required this.onUnhide,
    this.onShare,
  });

  final List<TemplateListRow> selected;
  final VoidCallback onClear;
  final VoidCallback onHide;
  final VoidCallback onUnhide;

  /// Offered only where the caller decides who uses a template.
  final VoidCallback? onShare;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final toHide = selected.where((r) => !r.hidden).length;
    final toUnhide = selected.where((r) => r.hidden).length;
    return Container(
      padding: const EdgeInsets.fromLTRB(16, 6, 8, 6),
      decoration: BoxDecoration(
        color: StatusColors.of(context).highlight,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: scheme.outlineVariant),
      ),
      child: Wrap(
        crossAxisAlignment: WrapCrossAlignment.center,
        spacing: 4,
        runSpacing: 4,
        children: [
          Padding(
            padding: const EdgeInsets.only(right: 8),
            child: Text('${selected.length} selected',
                style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: scheme.onSurface)),
          ),
          if (onShare != null)
            TextButton.icon(
              onPressed: onShare,
              icon: const Icon(Icons.group_add_outlined, size: 18),
              label: const Text('Share…'),
            ),
          if (toHide > 0)
            TextButton.icon(
              onPressed: onHide,
              icon: const Icon(Icons.visibility_off_outlined, size: 18),
              label: const Text('Hide'),
            ),
          if (toUnhide > 0)
            TextButton.icon(
              onPressed: onUnhide,
              icon: const Icon(Icons.visibility_outlined, size: 18),
              label: const Text('Unhide'),
            ),
          TextButton(onPressed: onClear, child: const Text('Clear selection')),
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

class TemplateActionsMenu extends StatelessWidget {
  const TemplateActionsMenu({super.key, required this.row, required this.actions});
  final TemplateListRow row;
  final List<TemplateAction> actions;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    if (actions.isEmpty) return const SizedBox(width: 40);
    return PopupMenuButton<int>(
      tooltip: 'More actions for ${row.name} (${row.locale})',
      icon: const Icon(Icons.more_vert),
      onSelected: (i) => actions[i].onPressed(),
      itemBuilder: (_) => [
        for (var i = 0; i < actions.length; i++)
          PopupMenuItem(
            value: i,
            child: ListTile(
              dense: true,
              contentPadding: EdgeInsets.zero,
              leading: Icon(actions[i].icon, size: 20, color: actions[i].destructive ? scheme.error : null),
              title: Text(actions[i].label,
                  style: TextStyle(fontSize: 14, color: actions[i].destructive ? scheme.error : scheme.onSurface)),
            ),
          ),
      ],
    );
  }
}

/// The template list both WhatsApp template screens share: selection,
/// status, name with its tags, the next step and the ⋮ menu, on a [YTable].
class TemplateTable<T extends TemplateListRow> extends StatelessWidget {
  const TemplateTable({
    super.key,
    required this.rows,
    required this.newer,
    required this.selected,
    required this.onSelected,
    required this.subtitleOf,
    required this.menuOf,
    required this.onRowTap,
    required this.empty,
    this.canSelect,
    this.notSelectableReason,
    this.tagsOf,
    this.primaryActionOf,
    this.extraColumns = const [],
    this.toolbarTrailing = const [],
    this.highlightIds = const {},
    this.searchTextOf,
  });

  final List<T> rows;
  final Map<int, String> newer;
  final Set<int> selected;
  final void Function(T row, bool on) onSelected;
  final bool Function(T row)? canSelect;
  final String? notSelectableReason;
  final String Function(T row) subtitleOf;
  final List<Widget> Function(T row)? tagsOf;
  final Widget? Function(T row)? primaryActionOf;
  final List<TemplateAction> Function(T row) menuOf;
  final ValueChanged<T> onRowTap;
  final Widget empty;
  final List<YColumn<T>> extraColumns;
  final List<Widget> toolbarTrailing;
  final Set<String> highlightIds;
  final String Function(T row)? searchTextOf;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return YTable<T>(
      rows: rows,
      noun: 'templates',
      idOf: (r) => '${r.id}',
      searchHint: 'Search name or wording',
      searchTextOf: searchTextOf ?? (r) => '${r.name} ${r.locale} ${r.body}',
      filters: templateStatusFilters(rows),
      highlightIds: highlightIds,
      toolbarTrailing: toolbarTrailing,
      onRowTap: onRowTap,
      empty: empty,
      minWidth: 820,
      columns: [
        YColumn<T>(
          label: '',
          width: 44,
          cell: (context, r) {
            final may = canSelect?.call(r) ?? true;
            final box = KeyedSubtree(
              key: ValueKey('select-${r.id}'),
              child: Checkbox(
                value: selected.contains(r.id),
                onChanged: may ? (v) => onSelected(r, v ?? false) : null,
              ),
            );
            return !may && notSelectableReason != null ? Tooltip(message: notSelectableReason!, child: box) : box;
          },
        ),
        YColumn<T>(
          label: 'Status',
          width: 210,
          sortKey: (r) => _statusOrder.indexOf(r.status),
          cell: (context, r) => TemplateStatusCell(row: r),
        ),
        YColumn<T>(
          label: 'Template',
          flex: 4,
          sortKey: (r) => r.name,
          cell: (context, r) => Opacity(
            opacity: r.hidden ? 0.7 : 1,
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
              Wrap(spacing: 6, runSpacing: 4, crossAxisAlignment: WrapCrossAlignment.center, children: [
                Text('${r.name} (${r.locale})', style: monoStyle(context, size: 13, weight: FontWeight.w500)),
                if (newer[r.id] != null)
                  Tooltip(
                    message: 'Replaced by ${newer[r.id]}',
                    child: const YBadge('Older version', icon: Icons.history),
                  ),
                if (r.hidden) const YBadge('Hidden', icon: Icons.visibility_off_outlined),
                ...?tagsOf?.call(r),
              ]),
              const SizedBox(height: 2),
              Text(subtitleOf(r),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(fontSize: 12.5, color: scheme.onSurfaceVariant)),
            ]),
          ),
        ),
        ...extraColumns,
        YColumn<T>(
          label: 'Next step',
          width: 180,
          cell: (context, r) => primaryActionOf?.call(r) ?? const SizedBox.shrink(),
        ),
        YColumn<T>(
          label: '',
          width: 48,
          cell: (context, r) => TemplateActionsMenu(row: r, actions: menuOf(r)),
        ),
      ],
    );
  }
}

/// «Select all» beside the table search.
class SelectAllButton extends StatelessWidget {
  const SelectAllButton({super.key, required this.selectable, required this.selected, required this.onSelectAll, required this.onClear});
  final Set<int> selectable;
  final Set<int> selected;
  final VoidCallback onSelectAll;
  final VoidCallback onClear;

  @override
  Widget build(BuildContext context) {
    final all = selectable.isNotEmpty && selectable.every(selected.contains);
    final n = selectable.length;
    return Tooltip(
      message: all ? 'Clear selection' : 'Select all $n template${n == 1 ? '' : 's'} in the list',
      child: OutlinedButton.icon(
        onPressed: selectable.isEmpty ? null : (all ? onClear : onSelectAll),
        icon: Icon(all ? Icons.deselect : Icons.select_all, size: 18),
        label: Text(all ? 'Clear selection' : 'Select all'),
      ),
    );
  }
}

/// A row of label → value in a template's details.
class TemplateFact extends StatelessWidget {
  const TemplateFact(this.label, this.value, {super.key, this.mono = false});
  final String label;
  final String value;
  final bool mono;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        SizedBox(
          width: 150,
          child: Text(label, style: TextStyle(fontSize: 13, fontWeight: FontWeight.w500, color: scheme.onSurfaceVariant)),
        ),
        Expanded(
          child: SelectableText(value,
              style: mono ? monoStyle(context, size: 13) : TextStyle(fontSize: 14, color: scheme.onSurface)),
        ),
      ]),
    );
  }
}

// ---------------------------------------------------------------------------
// Wording: placeholders and preview
// ---------------------------------------------------------------------------

final _placeholder = RegExp(r'\{\{(\d+)\}\}');

// Java's \p{Punct} is the ASCII punctuation set, which is what the backend uses.
final _adjacent = RegExp(r'\}\}[\s!-/:-@\[-`{-~]*\{\{');
final _braces = RegExp(r'\{\{[^}]*\}\}');

/// The highest `{{n}}` in [body] — what the backend counts as the number of
/// values a send must supply.
int placeholderCount(String body) =>
    _placeholder.allMatches(body).map((m) => int.parse(m.group(1)!)).fold<int>(0, (a, b) => b > a ? b : a);

/// A broken rule about variables. [declarationRule] rules are the ones the
/// backend refuses on a declaration; the rest only WhatsApp's review catches.
class PlaceholderIssue {
  const PlaceholderIssue(this.message, {this.declarationRule = true});
  final String message;
  final bool declarationRule;
}

/// Meta's rules for variables, as the backend checks a declaration:
/// `{{1}}…{{n}}` without gaps, not at the very start or end, never adjacent.
/// Brace pairs that aren't numbered variables are flagged too.
List<PlaceholderIssue> placeholderIssues(String body) {
  final out = <PlaceholderIssue>[];
  final n = placeholderCount(body);
  final seen = _placeholder.allMatches(body).map((m) => int.parse(m.group(1)!)).toSet();
  if (seen.length != n || (n > 0 && !seen.contains(1))) {
    final missing = [for (var i = 1; i <= n; i++) if (!seen.contains(i)) '{{$i}}'];
    out.add(PlaceholderIssue('Number the variables {{1}}, {{2}}, … without gaps'
        '${missing.isEmpty ? '' : ' — ${missing.join(', ')} ${missing.length == 1 ? 'is' : 'are'} missing'}.'));
  }
  final trimmed = body.trim();
  if (trimmed.startsWith('{{') || trimmed.endsWith('}}')) {
    out.add(const PlaceholderIssue(
        'Start and end with words, not a variable — WhatsApp rejects a message that begins or ends with one.'));
  }
  if (_adjacent.hasMatch(body)) {
    out.add(const PlaceholderIssue('Put words between variables; two next to each other are rejected.'));
  }
  final odd = _braces.allMatches(body).map((m) => m.group(0)!).where((s) => !_placeholder.hasMatch(s)).toSet();
  if (odd.isNotEmpty) {
    out.add(PlaceholderIssue(
        '${odd.join(', ')} ${odd.length == 1 ? 'isn’t a variable' : 'aren’t variables'} — use numbers only, like {{1}}.',
        declarationRule: false));
  }
  return out;
}

/// Meta's template categories, with what choosing each one means.
const templateCategoryOptions = <YEnumOption<String>>[
  YEnumOption('UTILITY', 'Utility',
      wire: 'UTILITY',
      description: 'About something the customer already started: an order, an appointment, an account alert.'),
  YEnumOption('MARKETING', 'Marketing',
      wire: 'MARKETING',
      caution: true,
      description: 'Promotions and re-engagement. WhatsApp charges more and customers can opt out of them.'),
  YEnumOption('AUTHENTICATION', 'Authentication',
      wire: 'AUTHENTICATION',
      description: 'One-time passcodes only — WhatsApp rejects any other wording in this category.'),
];

/// The human name of a Meta category.
String templateCategoryLabel(String wire) =>
    templateCategoryOptions.where((o) => o.value == wire).firstOrNull?.title ?? wire;

/// How the customer sees the message: a WhatsApp bubble with each `{{n}}`
/// replaced by its example, or shown as a marker when there is none yet.
class WhatsAppBodyPreview extends StatelessWidget {
  const WhatsAppBodyPreview({
    super.key,
    required this.body,
    this.samples = const [],
    this.issues = const [],
    this.declarationRulesBlock = false,
    this.footer,
  });

  final String body;
  final List<String> samples;

  /// From [placeholderIssues].
  final List<PlaceholderIssue> issues;

  /// True where the backend refuses a save that breaks a declaration rule;
  /// false where only WhatsApp's review would.
  final bool declarationRulesBlock;
  final String? footer;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final status = StatusColors.of(context);
    final n = placeholderCount(body);
    final spans = <InlineSpan>[];
    var at = 0;
    for (final m in _placeholder.allMatches(body)) {
      if (m.start > at) spans.add(TextSpan(text: body.substring(at, m.start)));
      final i = int.parse(m.group(1)!);
      final sample = i - 1 < samples.length ? samples[i - 1].trim() : '';
      spans.add(WidgetSpan(
        alignment: PlaceholderAlignment.middle,
        child: Container(
          margin: const EdgeInsets.symmetric(horizontal: 1),
          padding: const EdgeInsets.symmetric(horizontal: 4),
          decoration: BoxDecoration(
            color: sample.isEmpty ? status.infoContainer : status.successContainer,
            borderRadius: BorderRadius.circular(4),
          ),
          child: Text(
            sample.isEmpty ? '{{$i}}' : sample,
            style: sample.isEmpty
                ? monoStyle(context, size: 13, color: status.onInfoContainer)
                : TextStyle(fontSize: 14, color: status.onSuccessContainer, fontWeight: FontWeight.w500),
          ),
        ),
      ));
      at = m.end;
    }
    if (at < body.length) spans.add(TextSpan(text: body.substring(at)));

    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Wrap(alignment: WrapAlignment.spaceBetween, spacing: 12, runSpacing: 4, children: [
        Text('Preview', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: scheme.onSurface)),
        Text('${body.length} / 1024 · $n variable${n == 1 ? '' : 's'}',
            style: TextStyle(
                fontSize: 12.5, color: body.length > 1024 ? scheme.error : scheme.onSurfaceVariant)),
      ]),
      const SizedBox(height: 8),
      Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(color: scheme.surfaceContainerHigh, borderRadius: BorderRadius.circular(12)),
        child: Align(
          alignment: Alignment.centerLeft,
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 360),
            child: Container(
              padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
              decoration: BoxDecoration(
                color: scheme.surface,
                borderRadius: const BorderRadius.only(
                  topRight: Radius.circular(10),
                  bottomLeft: Radius.circular(10),
                  bottomRight: Radius.circular(10),
                ),
                border: Border.all(color: scheme.outlineVariant),
              ),
              child: body.trim().isEmpty
                  ? Text('The message wording shows here as you type.',
                      style: TextStyle(fontSize: 14, color: scheme.onSurfaceVariant))
                  : Text.rich(TextSpan(children: spans),
                      style: TextStyle(fontSize: 14, height: 1.45, color: scheme.onSurface)),
            ),
          ),
        ),
      ),
      if (footer != null) ...[
        const SizedBox(height: 8),
        Text(footer!, style: TextStyle(fontSize: 13, height: 1.45, color: scheme.onSurfaceVariant)),
      ],
      if (body.trim().isNotEmpty)
        for (final issue in issues) ...[
          const SizedBox(height: 12),
          if (declarationRulesBlock && issue.declarationRule)
            YBanner(tone: YBannerTone.error, title: 'Fix before saving:', message: issue.message)
          else
            YBanner(tone: YBannerTone.warning, title: 'WhatsApp’s review will likely reject this:', message: issue.message),
        ],
    ]);
  }
}

/// An empty list with one line about what lives here and the create action.
YEmptyState templatesEmptyState({required String message, Widget? action}) => YEmptyState(
      icon: Icons.chat_outlined,
      title: 'No templates yet',
      message: message,
      action: action,
    );

/// The backend names a bad field by error code, not `fieldErrors`; put the
/// message under the field it is about.
ApiErrorInfo templateFieldError(Object e, Map<String, String> fieldByCode) {
  final info = ApiErrorInfo.from(e);
  if (info.fieldErrors.isNotEmpty) return info;
  final field = fieldByCode[info.code];
  if (field == null) return info;
  final m = info.message.trim();
  final sentence = m.isEmpty ? m : '${m[0].toUpperCase()}${m.substring(1)}${m.endsWith('.') ? '' : '.'}';
  return ApiErrorInfo(
    message: info.message,
    code: info.code,
    status: info.status,
    path: info.path,
    fieldErrors: {field: sentence},
  );
}

/// The actions under a read-only template sheet.
class TemplateSheetFooter extends StatelessWidget {
  const TemplateSheetFooter({super.key, required this.children});
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      decoration: BoxDecoration(color: scheme.surface, border: Border(top: BorderSide(color: scheme.outlineVariant))),
      padding: const EdgeInsets.symmetric(horizontal: Gap.pagePadding, vertical: 12),
      child: Wrap(alignment: WrapAlignment.end, spacing: 10, runSpacing: 8, children: children),
    );
  }
}

/// A destructive button for a sheet footer.
class DangerButton extends StatelessWidget {
  const DangerButton({super.key, required this.label, required this.onPressed, this.icon = Icons.delete_outline});
  final String label;
  final VoidCallback? onPressed;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return OutlinedButton.icon(
      style: OutlinedButton.styleFrom(foregroundColor: scheme.error, side: BorderSide(color: scheme.error.withValues(alpha: 0.6))),
      onPressed: onPressed,
      icon: Icon(icon, size: 18),
      label: Text(label),
    );
  }
}

/// Lays an editor out with its live preview beside it on a wide sheet,
/// under it on a narrow one.
class EditorWithPreview extends StatelessWidget {
  const EditorWithPreview({super.key, required this.form, required this.preview, this.previewWidth = 380});
  final List<Widget> form;
  final Widget preview;
  final double previewWidth;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return LayoutBuilder(builder: (context, c) {
      if (c.maxWidth < 820) {
        return ListView(
          padding: const EdgeInsets.fromLTRB(28, 20, 28, 28),
          children: [...form, const SizedBox(height: Gap.xl), preview],
        );
      }
      return Row(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Expanded(child: ListView(padding: const EdgeInsets.fromLTRB(28, 20, 24, 28), children: form)),
        Container(
          width: previewWidth,
          decoration: BoxDecoration(
            color: scheme.surfaceContainerLowest,
            border: Border(left: BorderSide(color: scheme.outlineVariant)),
          ),
          child: ListView(padding: const EdgeInsets.fromLTRB(20, 20, 20, 28), children: [preview]),
        ),
      ]);
    });
  }
}

/// The optional «Business name variable» (`businessLabelVar`, shared-sender
/// §9.2): blank, or the number of one of the body's `{{n}}`.
String? businessLabelVarError(String raw, String body) {
  final t = raw.trim();
  if (t.isEmpty) return null;
  final n = int.tryParse(t);
  if (n == null || n < 1) return 'Enter the number of a variable, e.g. 1 for {{1}}.';
  final count = placeholderCount(body);
  if (count == 0) return 'The message has no variables — add one, e.g. {{1}}, for the business name.';
  if (n > count) return 'The message only has {{1}} to {{$count}}.';
  return null;
}

/// Blank → null (no business-name variable).
int? parseBusinessLabelVar(String raw) => int.tryParse(raw.trim());

/// Which `{{n}}` the platform fills with the business name. Shared by the
/// workspace and account template editors.
class BusinessLabelVarField extends StatelessWidget {
  const BusinessLabelVarField({super.key, required this.controller, this.form, this.readOnly = false});

  final TextEditingController controller;
  final YFormController? form;
  final bool readOnly;

  @override
  Widget build(BuildContext context) => YTextField(
        key: const Key('business-label-var'),
        label: 'Business name variable',
        wireKey: 'businessLabelVar',
        controller: controller,
        form: form,
        name: 'businessLabelVar',
        optional: true,
        readOnly: readOnly,
        mono: true,
        keyboardType: TextInputType.number,
        inputFormatters: [FilteringTextInputFormatter.digitsOnly, LengthLimitingTextInputFormatter(2)],
        hint: 'e.g. 1',
        helper: 'The {{n}} the platform fills with the business name on every send, overwriting what the sender '
            'passed: the assigned label on a shared platform number, the workspace’s display name otherwise. '
            'Required to send this template on a shared number.',
        onChanged: form == null ? null : (_) => form!.changed('businessLabelVar'),
      );
}
