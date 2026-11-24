import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../models/admin/platform_sender.dart';
import '../../../models/admin/whatsapp_sender.dart';
import '../../../ui/data/y_badge.dart';
import '../../../ui/feedback/api_error.dart';
import '../../../ui/feedback/toast.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/tokens.dart';
import '../channel_bindings/channel_ui.dart';
import '../channel_bindings/sender_routes_section.dart';
import 'whatsapp_senders_screen.dart' show whatsAppSendersProvider;

typedef SenderKey = ({String tenant, String sender});

/// The calling workspace's registered staff on a platform sender.
final senderMembersProvider = FutureProvider.family<SenderMembers, SenderKey>((ref, key) async {
  final api = await ref.watch(adminApiProvider.future);
  return api.listSenderMembers(key.tenant, key.sender);
});

/// «Platform · shared», plus the business name recipients read when known.
class PlatformSharedChip extends StatelessWidget {
  const PlatformSharedChip({super.key, this.label});

  final String? label;

  @override
  Widget build(BuildContext context) {
    return Wrap(spacing: 6, runSpacing: 4, crossAxisAlignment: WrapCrossAlignment.center, children: [
      const YBadge('Platform · shared', tone: YTone.info, icon: Icons.hub_outlined),
      if (label != null && label!.isNotEmpty)
        Text('as “$label”',
            overflow: TextOverflow.ellipsis,
            style: TextStyle(fontSize: 12.5, color: Theme.of(context).colorScheme.onSurfaceVariant)),
    ]);
  }
}

/// The detail of a platform sender assigned to [tenant] (shared-sender §12):
/// the number, its label and cap read-only; members, keyword actions, routes
/// and the non-member reply editable for this workspace only. Lifecycle
/// actions (edit, submit, verify, pause, remove, move) belong to the platform
/// and are not offered.
class AssignedPlatformSenderDetails extends StatelessWidget {
  const AssignedPlatformSenderDetails({super.key, required this.tenant, required this.sender});

  final String tenant;
  final WhatsAppSenderResponse sender;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final s = sender;
    final a = s.assignment;
    final label = a?.label ?? '';
    final paused = a?.status == 'PAUSED';
    return ListView(
      padding: const EdgeInsets.fromLTRB(28, 20, 28, 28),
      children: [
        Wrap(spacing: 10, runSpacing: 8, crossAxisAlignment: WrapCrossAlignment.center, children: [
          PlatformSharedChip(label: label),
          YBadge(SenderStatusInfo.of(s.status).label, tone: toneForStatus(s.status), icon: iconForStatus(s.status)),
          if (paused) const YBadge('Paused for this workspace', tone: YTone.warning, icon: Icons.pause_circle_outline),
        ]),
        const SizedBox(height: 8),
        Text(
          'The platform owns this number and shares it with several businesses. Every message you send on it is '
          'signed “$label”. Only the phones registered below as your staff reach your assistants; anyone else who '
          'writes gets your non-member reply.',
          style: TextStyle(fontSize: 14, height: 1.5, color: scheme.onSurfaceVariant),
        ),
        if (paused)
          const Padding(
            padding: EdgeInsets.only(top: Gap.md),
            child: YBanner(
              tone: YBannerTone.warning,
              title: 'Paused by the platform.',
              message: 'This workspace can’t send on the number until a platform administrator resumes it. Other '
                  'businesses on it are unaffected.',
            ),
          ),
        const ChannelSheetHeading('Details'),
        ChannelDetailRow(label: 'Phone', value: s.phoneE164, mono: true),
        ChannelDetailRow(label: 'Display name', value: s.approvedDisplayName ?? s.displayName),
        ChannelDetailRow(label: 'Your business name', value: label.isEmpty ? '—' : label),
        if (a != null) ...[
          ChannelDetailRow(label: 'Daily template cap', value: '${a.dailyOutboundCap} per 24 h'),
          ChannelDetailRow(
            key: const Key('assignment-sends'),
            label: 'Template sends, 24 h',
            value: '${a.templateSends24h} of ${a.dailyOutboundCap}',
          ),
        ],
        ChannelDetailRow(label: 'Sender ID', value: s.id, mono: true),
        Padding(
          padding: const EdgeInsets.only(top: 4),
          child: Text(
            'The business name and cap are set by your platform administrator.',
            style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant),
          ),
        ),
        _MembersSection(tenant: tenant, senderId: s.id),
        if (a != null) NonMemberReplyEditor(tenant: tenant, senderId: s.id, assignment: a),
        KeywordActionsEditor(tenant: tenant, senderId: s.id, platformSender: true),
        const SizedBox(height: Gap.sm),
        SenderRoutesSection(endpointKind: 'WHATSAPP_SENDER', endpointId: s.id, platformSender: true),
      ],
    );
  }
}

class _MembersSection extends ConsumerWidget {
  const _MembersSection({required this.tenant, required this.senderId});

  final String tenant;
  final String senderId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scheme = Theme.of(context).colorScheme;
    final key = (tenant: tenant, sender: senderId);
    final members = ref.watch(senderMembersProvider(key));
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      ChannelSheetHeading(
        'Members',
        trailing: members.hasValue
            ? YBadge('${members.value!.count}', key: const Key('members-count'), mono: true)
            : null,
      ),
      Text(
        'Staff phones your integration registered on this number. Only they reach your assistants, and only once '
        'your identity resolver accepts them.',
        style: TextStyle(fontSize: 13, height: 1.45, color: scheme.onSurfaceVariant),
      ),
      const SizedBox(height: Gap.sm),
      if (members.hasError && !members.hasValue)
        YBanner(
          tone: YBannerTone.error,
          title: 'Couldn’t load members.',
          message: ApiErrorInfo.from(members.error!).message,
          actions: [TextButton(onPressed: () => ref.invalidate(senderMembersProvider(key)), child: const Text('Retry'))],
        )
      else if (!members.hasValue)
        const LinearProgressIndicator()
      else if (members.value!.phones.isEmpty)
        const YBanner(
          tone: YBannerTone.info,
          message: 'No staff registered yet. Until there are, nobody reaches an assistant on this number.',
        )
      else
        Wrap(spacing: 6, runSpacing: 6, children: [
          for (final p in members.value!.phones) YBadge(p, mono: true, icon: Icons.person_outline),
        ]),
    ]);
  }
}

/// This workspace's reply to people who write but aren't its staff (§8.3).
class NonMemberReplyEditor extends ConsumerStatefulWidget {
  const NonMemberReplyEditor({super.key, required this.tenant, required this.senderId, required this.assignment});

  final String tenant;
  final String senderId;
  final SenderAssignmentView assignment;

  @override
  ConsumerState<NonMemberReplyEditor> createState() => _NonMemberReplyEditorState();
}

class _NonMemberReplyEditorState extends ConsumerState<NonMemberReplyEditor> {
  static const _max = 1000;
  late final _text = TextEditingController(text: widget.assignment.nonMemberReply ?? '');
  late String _saved = widget.assignment.nonMemberReply ?? '';
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final value = _text.text.trim();
    if (value.length > _max) {
      setState(() => _error = 'Keep it to $_max characters.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final api = await ref.read(adminApiProvider.future);
      final saved = await api.updateSenderAssignment(widget.tenant, widget.senderId, nonMemberReply: value);
      _saved = saved.nonMemberReply ?? value;
      ref.invalidate(whatsAppSendersProvider(widget.tenant));
      if (mounted) showToast(context, 'Non-member reply saved');
    } catch (e) {
      if (mounted) setState(() => _error = ApiErrorInfo.from(e).message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final dirty = _text.text.trim() != _saved.trim();
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      const ChannelSheetHeading('Reply to non-members'),
      TextField(
        key: const Key('non-member-reply'),
        controller: _text,
        minLines: 2,
        maxLines: 4,
        enabled: !_busy,
        inputFormatters: [LengthLimitingTextInputFormatter(_max)],
        onChanged: (_) => setState(() => _error = null),
        decoration: InputDecoration(
          hintText: 'e.g. Gracias por escribir a ${widget.assignment.label}. Este número solo envía avisos; para jugar usa …',
          errorText: _error,
          helperText: 'Sent at most once a day to someone who writes here about your business but isn’t your staff. '
              'Leave empty to use the platform’s generic reply.',
          helperMaxLines: 3,
        ),
        style: TextStyle(fontSize: 14, color: scheme.onSurface),
      ),
      const SizedBox(height: 8),
      Align(
        alignment: Alignment.centerRight,
        child: FilledButton.tonal(
          key: const Key('non-member-reply-save'),
          onPressed: _busy || !dirty ? null : _save,
          child: Text(_busy ? 'Saving…' : 'Save reply'),
        ),
      ),
    ]);
  }
}

/// ROUTED or REPLY for one keyword action (§8.2).
class KeywordScopeSelector extends StatelessWidget {
  const KeywordScopeSelector({super.key, required this.value, required this.onChanged, this.enabled = true});

  final String value;
  final ValueChanged<String> onChanged;
  final bool enabled;

  static String describe(String scope) => scope == KeywordScope.reply
      ? 'Matches only a message already talking to your business (a reply to something you sent).'
      : 'Anyone can send it, and it brings the message to your business. Unique on the number.';

  @override
  Widget build(BuildContext context) {
    return SegmentedButton<String>(
      showSelectedIcon: false,
      segments: const [
        ButtonSegment(
          value: KeywordScope.routed,
          label: Text('Routes by itself'),
          tooltip: 'ROUTED — anyone may send it; it routes to your business',
        ),
        ButtonSegment(
          value: KeywordScope.reply,
          label: Text('Replies only'),
          tooltip: 'REPLY — only matches once the message is with your business',
        ),
      ],
      selected: {value},
      onSelectionChanged: enabled ? (s) => onChanged(s.first) : null,
    );
  }
}

class _KeywordRow {
  _KeywordRow(KeywordActionItem? item)
      : prefix = TextEditingController(text: item?.prefix ?? ''),
        url = TextEditingController(text: item?.forwardUrl ?? ''),
        scope = item?.scope ?? KeywordScope.routed;

  final TextEditingController prefix;
  final TextEditingController url;
  String scope;
  String? prefixError;
  String? urlError;

  KeywordActionItem get item =>
      KeywordActionItem(prefix: prefix.text.trim(), forwardUrl: url.text.trim(), scope: scope);

  void dispose() {
    prefix.dispose();
    url.dispose();
  }
}

/// The words this workspace answers deterministically on the sender by
/// forwarding to its integration. A save replaces this workspace's set only.
class KeywordActionsEditor extends ConsumerStatefulWidget {
  const KeywordActionsEditor({super.key, required this.tenant, required this.senderId, this.platformSender = false});

  final String tenant;
  final String senderId;

  /// Shared number: ROUTED prefixes are unique across businesses and some
  /// words are reserved.
  final bool platformSender;

  @override
  ConsumerState<KeywordActionsEditor> createState() => _KeywordActionsEditorState();
}

class _KeywordActionsEditorState extends ConsumerState<KeywordActionsEditor> {
  final List<_KeywordRow> _rows = [];
  List<KeywordActionItem> _saved = const [];
  bool _loading = true;
  bool _busy = false;
  String? _loadError;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    for (final r in _rows) {
      r.dispose();
    }
    super.dispose();
  }

  void _reset(List<KeywordActionItem> items) {
    for (final r in _rows) {
      r.dispose();
    }
    _rows
      ..clear()
      ..addAll(items.map(_KeywordRow.new));
    _saved = items;
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _loadError = null;
    });
    try {
      final api = await ref.read(adminApiProvider.future);
      final items = await api.listKeywordActions(widget.tenant, widget.senderId);
      if (!mounted) return;
      setState(() => _reset(items));
    } catch (e) {
      if (mounted) setState(() => _loadError = ApiErrorInfo.from(e).message);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  bool get _dirty {
    final now = _rows.map((r) => r.item).toList();
    if (now.length != _saved.length) return true;
    for (var i = 0; i < now.length; i++) {
      final a = now[i], b = _saved[i];
      if (a.prefix != b.prefix || a.forwardUrl != b.forwardUrl || a.scope != b.scope) return true;
    }
    return false;
  }

  bool _validate() {
    final seen = <String>{};
    var ok = true;
    for (final r in _rows) {
      final prefix = r.prefix.text.trim();
      final upper = prefix.toUpperCase();
      r.prefixError = prefix.isEmpty
          ? 'Enter the keyword.'
          : widget.platformSender && kPlatformReservedKeywords.contains(upper)
              ? '“$prefix” is reserved on shared numbers.'
              : !seen.add('${r.scope}:$upper')
                  ? '“$prefix” is listed twice.'
                  : null;
      r.urlError = Validators.httpUrl(r.url.text);
      ok = ok && r.prefixError == null && r.urlError == null;
    }
    return ok;
  }

  /// The row a server message names — it names the prefix and nothing else.
  _KeywordRow? _rowNamedIn(String message) {
    final m = message.toUpperCase();
    _KeywordRow? best;
    for (final r in _rows) {
      final p = r.prefix.text.trim().toUpperCase();
      if (p.isNotEmpty && m.contains(p) && (best == null || p.length > best.prefix.text.trim().length)) best = r;
    }
    return best;
  }

  Future<void> _save() async {
    setState(() => _error = null);
    if (!_validate()) {
      setState(() {});
      return;
    }
    setState(() => _busy = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      final saved = await api.replaceKeywordActions(widget.tenant, widget.senderId, [for (final r in _rows) r.item]);
      if (!mounted) return;
      setState(() => _reset(saved));
      showToast(context, 'Keyword actions saved');
    } catch (e) {
      final info = ApiErrorInfo.from(e);
      final row = info.code == 'keyword_prefix_taken' || info.code == 'reserved_keyword' ? _rowNamedIn(info.message) : null;
      if (!mounted) return;
      setState(() {
        if (row != null) {
          row.prefixError = info.code == 'keyword_prefix_taken'
              ? '“${row.prefix.text.trim()}” already routes to another business on this number. Pick another '
                  'keyword, or make it «Replies only».'
              : '“${row.prefix.text.trim()}” is reserved on shared numbers.';
        } else {
          _error = info.message;
        }
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      ChannelSheetHeading(
        'Keyword actions',
        trailing: TextButton.icon(
          key: const Key('keyword-add'),
          onPressed: _loading || _busy ? null : () => setState(() => _rows.add(_KeywordRow(null))),
          icon: const Icon(Icons.add, size: 18),
          label: const Text('Add keyword'),
        ),
      ),
      Text(
        'A message that starts with one of these words is forwarded to your integration and answered there, '
        'without an assistant.${widget.platformSender ? ' CAMBIAR, CAMBIAR NEGOCIO, #NEGOCIO, STOP and START are '
            'reserved on shared numbers.' : ''}',
        style: TextStyle(fontSize: 13, height: 1.45, color: scheme.onSurfaceVariant),
      ),
      const SizedBox(height: Gap.sm),
      if (_loading)
        const LinearProgressIndicator()
      else if (_loadError != null)
        YBanner(
          tone: YBannerTone.error,
          title: 'Couldn’t load keyword actions.',
          message: _loadError!,
          actions: [TextButton(onPressed: _load, child: const Text('Retry'))],
        )
      else ...[
        if (_rows.isEmpty)
          Text('No keyword actions.', style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant)),
        for (var i = 0; i < _rows.length; i++) _rowEditor(context, i),
        if (_error != null) ...[
          const SizedBox(height: 8),
          YBanner(tone: YBannerTone.error, title: 'Couldn’t save keyword actions.', message: _error!),
        ],
        if (_dirty || _rows.isNotEmpty)
          Align(
            alignment: Alignment.centerRight,
            child: Padding(
              padding: const EdgeInsets.only(top: 8),
              child: FilledButton.tonal(
                key: const Key('keyword-save'),
                onPressed: _busy || !_dirty ? null : _save,
                child: Text(_busy ? 'Saving…' : 'Save keywords'),
              ),
            ),
          ),
      ],
    ]);
  }

  Widget _rowEditor(BuildContext context, int i) {
    final scheme = Theme.of(context).colorScheme;
    final r = _rows[i];
    return Container(
      key: ValueKey('keyword-row-$i'),
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.fromLTRB(12, 10, 4, 10),
      decoration: BoxDecoration(border: Border.all(color: scheme.outlineVariant), borderRadius: BorderRadius.circular(10)),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Expanded(
                flex: 2,
                child: TextField(
                  key: ValueKey('keyword-prefix-$i'),
                  controller: r.prefix,
                  enabled: !_busy,
                  onChanged: (_) => setState(() => r.prefixError = null),
                  decoration: InputDecoration(labelText: 'Keyword', hintText: 'e.g. TICKET', errorText: r.prefixError, errorMaxLines: 3),
                  style: monoStyle(context, size: 14),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                flex: 3,
                child: TextField(
                  key: ValueKey('keyword-url-$i'),
                  controller: r.url,
                  enabled: !_busy,
                  onChanged: (_) => setState(() => r.urlError = null),
                  decoration: InputDecoration(
                      labelText: 'Forward to', hintText: 'https://…', errorText: r.urlError, errorMaxLines: 2),
                  style: monoStyle(context, size: 13),
                ),
              ),
            ]),
            const SizedBox(height: 10),
            Align(
              alignment: Alignment.centerLeft,
              child: KeywordScopeSelector(
                key: ValueKey('keyword-scope-$i'),
                value: r.scope,
                enabled: !_busy,
                onChanged: (v) => setState(() {
                  r.scope = v;
                  r.prefixError = null;
                }),
              ),
            ),
            const SizedBox(height: 4),
            Text(KeywordScopeSelector.describe(r.scope),
                style: TextStyle(fontSize: 12.5, height: 1.4, color: scheme.onSurfaceVariant)),
          ]),
        ),
        IconButton(
          tooltip: 'Remove keyword',
          icon: Icon(Icons.delete_outline, size: 18, color: scheme.error),
          onPressed: _busy
              ? null
              : () => setState(() {
                    _rows.removeAt(i).dispose();
                  }),
        ),
      ]),
    );
  }
}
