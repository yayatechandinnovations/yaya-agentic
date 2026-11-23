import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../models/admin/invitation.dart';
import '../../../models/admin/member.dart';
import '../../../models/admin/workspace.dart';
import '../../../ui/data/live_async.dart';
import '../../../ui/data/relative_time.dart';
import '../../../ui/data/y_badge.dart';
import '../../../ui/data/y_page.dart';
import '../../../ui/data/y_sheet.dart';
import '../../../ui/data/y_states.dart';
import '../../../ui/data/y_table.dart';
import '../../../ui/feedback/api_error.dart';
import '../../../ui/feedback/confirm.dart';
import '../../../ui/feedback/toast.dart';
import '../../../ui/form/unsaved_guard.dart';
import '../../../ui/form/y_fields.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/tokens.dart';
import '../../admin/tenants/tenant_validators.dart';
import '../../admin/tenants/tenants_screen.dart' show tenantsProvider;
import '../../auth/application/auth_controller.dart';
import '../../auth/models/auth_state.dart';
import '../application/members_controller.dart';
import '../application/workspaces_controller.dart';
import '../data/members_api.dart';
import '../data/workspaces_api.dart';
import 'invite_member_dialog.dart';
import 'role_chip.dart';

/// Per-workspace settings: General · Members · Invitations · Danger zone.
/// Actions the caller can't perform are disabled with the reason, never
/// hidden; the server stays authoritative.
class WorkspaceSettingsScreen extends ConsumerStatefulWidget {
  const WorkspaceSettingsScreen({super.key, required this.code});
  final String code;

  @override
  ConsumerState<WorkspaceSettingsScreen> createState() => _WorkspaceSettingsScreenState();
}

class _WorkspaceSettingsScreenState extends ConsumerState<WorkspaceSettingsScreen> with SingleTickerProviderStateMixin {
  late final TabController _tab = TabController(length: 4, vsync: this);

  @override
  void dispose() {
    _tab.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final workspaces = ref.watch(myWorkspacesProvider);
    return LiveAsyncView<List<WorkspaceListItem>>(
      value: workspaces,
      what: 'this workspace',
      onRetry: () => ref.invalidate(myWorkspacesProvider),
      data: (context, list) {
        final membership = list.where((m) => m.workspace.code == widget.code).firstOrNull;
        if (membership == null) {
          // 404-as-no-access: the same view whether it doesn't exist or isn't yours.
          return YEmptyState(
            icon: Icons.lock_outline,
            title: 'Not available',
            message: 'This workspace doesn’t exist or you don’t have access to it.',
            action: FilledButton(onPressed: () => context.go('/workspaces'), child: const Text('Back to your workspaces')),
          );
        }
        final ws = membership.workspace;
        return YPage(
          title: ws.displayName,
          subtitle: 'Workspace ${ws.code}. You’re ${roleWithArticle(membership.role)} here.',
          actions: [
            RoleChip(membership.role),
            TextButton.icon(
              onPressed: () => context.go('/workspaces'),
              icon: const Icon(Icons.arrow_back, size: 18),
              label: const Text('All workspaces'),
            ),
          ],
          toolbar: TabBar(
            controller: _tab,
            isScrollable: true,
            tabAlignment: TabAlignment.start,
            tabs: const [
              Tab(text: 'General'),
              Tab(text: 'Members'),
              Tab(text: 'Invitations'),
              Tab(text: 'Danger zone'),
            ],
          ),
          child: TabBarView(
            controller: _tab,
            children: [
              _GeneralTab(workspace: ws, role: membership.role),
              _MembersTab(workspace: ws, actorRole: membership.role),
              _InvitationsTab(workspace: ws, actorRole: membership.role),
              _DangerZoneTab(workspace: ws, role: membership.role),
            ],
          ),
        );
      },
    );
  }
}

// ---------------------------------------------------------------------------
// General
// ---------------------------------------------------------------------------

const _generalLabels = {
  'displayName': 'Workspace name',
  'hostBaseUrl': 'Host base URL',
  'hostBaseUrlAllowlist': 'Per-call override hosts',
  'inboundOriginAllowlist': 'Allowed browser origins',
};

class _GeneralTab extends ConsumerStatefulWidget {
  const _GeneralTab({required this.workspace, required this.role});
  final Workspace workspace;
  final String role;

  @override
  ConsumerState<_GeneralTab> createState() => _GeneralTabState();
}

class _GeneralTabState extends ConsumerState<_GeneralTab> with AutomaticKeepAliveClientMixin {
  final _name = TextEditingController();
  final _hostBase = TextEditingController();
  final _hostAllowlist = TextEditingController();
  final _inboundOrigins = TextEditingController();
  late final YFormController _form;

  bool get _canEdit => roleAtLeastTier(widget.role, 'ADMIN');

  @override
  bool get wantKeepAlive => true;

  @override
  void initState() {
    super.initState();
    _load(widget.workspace);
    _form = YFormController(
      snapshot: () => {
        'displayName': _name.text.trim(),
        'hostBaseUrl': _hostBase.text.trim(),
        'hostBaseUrlAllowlist': TenantValidators.splitLines(_hostAllowlist.text),
        'inboundOriginAllowlist': TenantValidators.splitLines(_inboundOrigins.text),
      },
      validate: _validate,
      labels: _generalLabels,
      draftKey: _canEdit ? 'yaya.draft.workspace.${widget.workspace.code}.general' : null,
    );
    _form.addListener(_rebuild);
    if (_canEdit) {
      _form.loadDraft().then((d) {
        if (d == null || !mounted) return;
        final v = d.values;
        _name.text = (v['displayName'] as String?) ?? _name.text;
        _hostBase.text = (v['hostBaseUrl'] as String?) ?? _hostBase.text;
        if (v['hostBaseUrlAllowlist'] is List) _hostAllowlist.text = (v['hostBaseUrlAllowlist'] as List).join('\n');
        if (v['inboundOriginAllowlist'] is List) _inboundOrigins.text = (v['inboundOriginAllowlist'] as List).join('\n');
        _form.refresh();
      });
    }
  }

  @override
  void didUpdateWidget(_GeneralTab old) {
    super.didUpdateWidget(old);
    if (old.workspace != widget.workspace && !_form.isDirty) {
      _load(widget.workspace);
      _form.markClean();
    }
  }

  void _load(Workspace w) {
    _name.text = w.displayName;
    _hostBase.text = w.hostBaseUrl ?? '';
    _hostAllowlist.text = w.hostBaseUrlAllowlist.join('\n');
    _inboundOrigins.text = w.inboundOriginAllowlist.join('\n');
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  /// The tenant row carries "Require HTTPS"; when it isn't visible here the
  /// URL shape is still checked and the server rules on the scheme.
  bool get _requireHttps {
    final tenants = ref.read(tenantsProvider).valueOrNull ?? const [];
    final t = tenants.where((t) => t.id == widget.workspace.id || t.id == widget.workspace.code).firstOrNull;
    return t?.requireHttps ?? false;
  }

  Map<String, String?> _validate() {
    final https = _requireHttps;
    return {
      'displayName': Validators.required(_name.text, 'a workspace name'),
      'hostBaseUrl': _hostBase.text.trim().isEmpty ? null : TenantValidators.hostBaseUrl(_hostBase.text, requireHttps: https),
      'hostBaseUrlAllowlist': TenantValidators.lines(_hostAllowlist.text, (l) => TenantValidators.urlPattern(l, requireHttps: https)),
      'inboundOriginAllowlist': TenantValidators.lines(_inboundOrigins.text, (l) => TenantValidators.originPattern(l, requireHttps: https)),
    };
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _form.dispose();
    for (final c in [_name, _hostBase, _hostAllowlist, _inboundOrigins]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _save() async {
    if (!_canEdit || !_form.submit()) return;
    _form.saving = true;
    try {
      final api = await ref.read(workspacesApiProvider.future);
      // Every field is sent as it stands: "" clears the host, [] an allowlist.
      final saved = await api.patch(
        widget.workspace.code,
        displayName: _name.text.trim(),
        hostBaseUrl: _hostBase.text.trim(),
        hostBaseUrlAllowlist: TenantValidators.splitLines(_hostAllowlist.text),
        inboundOriginAllowlist: TenantValidators.splitLines(_inboundOrigins.text),
      );
      ref.invalidate(myWorkspacesProvider);
      ref.invalidate(tenantsProvider);
      _load(saved);
      _form.saving = false;
      _form.markClean();
      if (mounted) showToast(context, 'Saved ${saved.displayName}');
    } on WorkspaceApiException catch (e) {
      _form.saving = false;
      _form.applyServerError(ApiErrorInfo(message: e.message, code: e.errorCode, status: e.statusCode));
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(e);
    }
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final scheme = Theme.of(context).colorScheme;
    final ro = !_canEdit;
    return YUnsavedGuard(
      form: _form,
      child: YSaveShortcut(
        onSave: _save,
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Expanded(
            child: ListView(
              padding: const EdgeInsets.only(top: Gap.lg, bottom: Gap.xl),
              children: [
                if (ro) ...[
                  const YBanner(tone: YBannerTone.info, message: 'Only admins and owners can change these settings.'),
                  const SizedBox(height: Gap.lg),
                ],
                if (_form.summary.isNotEmpty || _form.formError != null) ...[
                  YErrorSummary(form: _form, action: 'saving'),
                  const SizedBox(height: Gap.lg),
                ],
                YFormSection(
                  title: 'Workspace details',
                  children: [
                    YTextField(
                      label: _generalLabels['displayName']!,
                      wireKey: 'displayName',
                      controller: _name,
                      form: _form,
                      name: 'displayName',
                      required: true,
                      readOnly: ro,
                      hint: 'e.g. Acme Co',
                    ),
                    _readOnly(context, 'Workspace code', widget.workspace.code, 'Fixed — used in URLs and audit logs.'),
                    _readOnly(context, 'Workspace ID', widget.workspace.id, 'Stable internal identifier.'),
                  ],
                ),
                const SizedBox(height: Gap.lg),
                YFormSection(
                  title: 'Outbound — where HTTP tools call',
                  description: 'HTTP tools declare a path only; it’s joined onto this base URL when they run. Leave empty if the workspace only uses Bean tools.',
                  children: [
                    YTextField(
                      label: _generalLabels['hostBaseUrl']!,
                      wireKey: 'hostBaseUrl',
                      controller: _hostBase,
                      form: _form,
                      name: 'hostBaseUrl',
                      optional: true,
                      readOnly: ro,
                      mono: true,
                      keyboardType: TextInputType.url,
                      hint: 'e.g. https://api.acme.com',
                      helper: 'scheme://host[:port][/path], no ? or #. Clearing it stops HTTP tools from running.',
                    ),
                    YTextField(
                      label: _generalLabels['hostBaseUrlAllowlist']!,
                      wireKey: 'hostBaseUrlAllowlist',
                      controller: _hostAllowlist,
                      form: _form,
                      name: 'hostBaseUrlAllowlist',
                      optional: true,
                      readOnly: ro,
                      mono: true,
                      maxLines: 6,
                      minLines: 2,
                      hint: 'https://*.acme.com',
                      helper: 'One pattern per line, * allowed. Lets the X-Yaya-Host-Base-Url header switch the host for a single call — only to these.',
                    ),
                  ],
                ),
                const SizedBox(height: Gap.lg),
                YFormSection(
                  title: 'Inbound — which browsers may start chats',
                  description: 'Browser origins allowed to call /v1/sessions/* for this workspace. Empty falls back to the host base URL’s origin — rarely what you want. List the console’s own origin if it differs.',
                  children: [
                    YTextField(
                      label: _generalLabels['inboundOriginAllowlist']!,
                      wireKey: 'inboundOriginAllowlist',
                      controller: _inboundOrigins,
                      form: _form,
                      name: 'inboundOriginAllowlist',
                      optional: true,
                      readOnly: ro,
                      mono: true,
                      maxLines: 6,
                      minLines: 2,
                      hint: 'https://shop.acme.com\nhttps://*.acme.com',
                      helper: 'One origin per line: scheme://host[:port], no path. * allowed.',
                    ),
                  ],
                ),
                if (ro)
                  Padding(
                    padding: const EdgeInsets.only(top: Gap.md),
                    child: Text('Ask an admin of this workspace to change them.',
                        style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant)),
                  ),
              ],
            ),
          ),
          if (_canEdit)
            YActionBar(
              form: _form,
              primaryLabel: 'Save workspace',
              onPrimary: _save,
              onCancel: _form.isDirty
                  ? () {
                      _load(widget.workspace);
                      _form.markClean();
                    }
                  : null,
              cancelLabel: 'Discard changes',
            ),
        ]),
      ),
    );
  }

  Widget _readOnly(BuildContext context, String label, String value, String helper) {
    final scheme = Theme.of(context).colorScheme;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      YFieldLabel(label: label),
      Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
        decoration: BoxDecoration(color: scheme.surfaceContainer, borderRadius: BorderRadius.circular(8)),
        child: SelectableText(value, style: monoStyle(context)),
      ),
      YFieldMessage(helper: helper),
    ]);
  }
}

// ---------------------------------------------------------------------------
// Members
// ---------------------------------------------------------------------------

String _who(Member m) => (m.displayName?.isNotEmpty ?? false) ? m.displayName! : (m.email ?? m.userId);

String? _selfUserId(WidgetRef ref) {
  final auth = ref.watch(authProvider);
  if (auth is! AuthAuthenticated) return null;
  final id = auth.operator.attributes['user_id'];
  return id is String && id.isNotEmpty ? id : null;
}

class _MembersTab extends ConsumerWidget {
  const _MembersTab({required this.workspace, required this.actorRole});
  final Workspace workspace;
  final String actorRole;

  bool get _canManage => roleAtLeastTier(actorRole, 'ADMIN');

  Future<void> _invite(BuildContext context) async {
    final email = await showYSheet<String>(
      context,
      width: 600,
      label: 'Invite a teammate',
      builder: (_) => InviteMemberSheet(workspaceCode: workspace.code, workspaceName: workspace.displayName),
    );
    if (email != null && context.mounted) showToast(context, 'Invitation sent to $email');
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final members = ref.watch(membersProvider(workspace.code));
    final me = _selfUserId(ref);
    final inviteBtn = Tooltip(
      message: _canManage ? '' : 'Only admins and owners can invite.',
      child: FilledButton.icon(
        onPressed: _canManage ? () => _invite(context) : null,
        icon: const Icon(Icons.person_add_alt_1, size: 18),
        label: const Text('Invite teammate'),
      ),
    );
    return Padding(
      padding: const EdgeInsets.only(top: Gap.lg),
      child: LiveAsyncView<List<Member>>(
        value: members,
        what: 'members',
        onRetry: () => ref.invalidate(membersProvider(workspace.code)),
        data: (context, list) => YTable<Member>(
          rows: list,
          noun: 'members',
          idOf: (m) => m.userId,
          searchHint: 'Search by name or email',
          searchTextOf: (m) => '${m.displayName ?? ''} ${m.email ?? ''} ${m.role}',
          onRowTap: (m) => showYSheet<void>(
            context,
            width: 620,
            label: 'Member ${_who(m)}',
            builder: (_) => _MemberSheet(workspace: workspace, member: m, actorRole: actorRole, selfUserId: me, members: list),
          ),
          toolbarTrailing: [inviteBtn],
          filters: [
            YFilter('Owners', (m) => m.role == 'OWNER'),
            YFilter('Admins', (m) => m.role == 'ADMIN'),
            YFilter('Members', (m) => m.role == 'MEMBER'),
            YFilter('Viewers', (m) => m.role == 'VIEWER'),
          ],
          empty: YEmptyState(
            icon: Icons.group_outlined,
            title: 'No members yet',
            message: 'Members are the people who can see or change this workspace. Invite a teammate by email.',
            action: inviteBtn,
          ),
          columns: [
            YColumn(
              label: 'Member',
              flex: 4,
              sortKey: (m) => _who(m).toLowerCase(),
              cell: (context, m) => YCellStack(
                primary: '${_who(m)}${m.userId == me ? ' (you)' : ''}',
                secondary: m.email,
              ),
            ),
            YColumn(label: 'Role', width: 150, sortKey: (m) => roleSummaries.keys.toList().indexOf(m.role), cell: (context, m) => RoleChip(m.role)),
            YColumn(
              label: 'Joined',
              width: 120,
              sortKey: (m) => m.addedAt ?? '',
              cell: (context, m) => Text(formatRelativeIso(m.addedAt)),
            ),
          ],
        ),
      ),
    );
  }
}

/// One member: change their role, transfer ownership to them, or remove them.
class _MemberSheet extends ConsumerStatefulWidget {
  const _MemberSheet({
    required this.workspace,
    required this.member,
    required this.actorRole,
    required this.selfUserId,
    required this.members,
  });
  final Workspace workspace;
  final Member member;
  final String actorRole;
  final String? selfUserId;
  final List<Member> members;

  @override
  ConsumerState<_MemberSheet> createState() => _MemberSheetState();
}

class _MemberSheetState extends ConsumerState<_MemberSheet> {
  late String _role = widget.member.role;
  late final YFormController _form;
  bool _busy = false;

  Member get m => widget.member;
  bool get _actorIsOwner => widget.actorRole == 'OWNER' || widget.actorRole == 'PLATFORM_ADMIN';
  bool get _isSelf => widget.selfUserId != null && widget.selfUserId == m.userId;
  int get _ownerCount => widget.members.where((x) => x.role == 'OWNER').length;

  /// Why the role can't change here, or null when it can.
  String? get _lockReason {
    if (!roleAtLeastTier(widget.actorRole, 'ADMIN')) return 'Only admins and owners can change roles.';
    if (m.role == 'OWNER' && !_actorIsOwner) return 'Only an owner can change another owner’s role.';
    if (m.role == 'OWNER' && _ownerCount <= 1) return 'This is the only owner. Make someone else an owner first.';
    return null;
  }

  List<String> get _targets => _actorIsOwner ? const ['OWNER', 'ADMIN', 'MEMBER', 'VIEWER'] : const ['ADMIN', 'MEMBER', 'VIEWER'];

  @override
  void initState() {
    super.initState();
    _form = YFormController(snapshot: () => {'role': _role}, validate: () => const {}, labels: const {'role': 'Role'});
    _form.addListener(_rebuild);
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _form.dispose();
    super.dispose();
  }

  void _fail(Object e) {
    final msg = e is MembersApiException ? e.message : ApiErrorInfo.from(e).message;
    _form.applyServerError(ApiErrorInfo(message: msg));
  }

  Future<void> _saveRole() async {
    if (!_form.submit() || _role == m.role) return;
    String? warning;
    if (_role == 'OWNER') {
      warning = '${_who(m)} will be able to manage other owners and archive the workspace.';
    } else if (_isSelf) {
      warning = 'You’re changing your own role to ${roleLabel(_role).toLowerCase()}. ${roleAtLeastTier(_role, 'ADMIN') ? '' : 'You’ll lose the ability to manage members and keys here, and can’t undo this yourself.'}';
    } else if (m.role == 'OWNER') {
      warning = '${_who(m)} will no longer be an owner.';
    }
    if (warning != null) {
      final ok = await confirmAction(
        context,
        title: 'Make ${_isSelf ? 'yourself' : _who(m)} ${roleWithArticle(_role)}?',
        message: warning,
        confirmLabel: 'Change role',
        destructive: _isSelf || m.role == 'OWNER',
      );
      if (!ok) return;
    }
    _form.saving = true;
    try {
      await ref.read(membersControllerProvider).changeRole(workspaceCode: widget.workspace.code, userId: m.userId, newRole: _role);
      if (_isSelf) ref.invalidate(myWorkspacesProvider);
      _form.saving = false;
      _form.markClean();
      if (!mounted) return;
      showToast(context, '${_who(m)} is now ${roleWithArticle(_role)}');
      Navigator.of(context).pop();
    } catch (e) {
      _form.saving = false;
      _fail(e);
    }
  }

  Future<void> _transferOwnership() async {
    final ok = await confirmTyped(
      context,
      title: 'Transfer ownership to ${_who(m)}?',
      message: '${_who(m)} becomes an owner of ${widget.workspace.displayName}, and you step down to admin. '
          'You keep admin access, but only an owner can make you an owner again.',
      expected: m.email ?? m.userId,
      confirmLabel: 'Transfer ownership',
    );
    if (!ok) return;
    setState(() => _busy = true);
    final ctrl = ref.read(membersControllerProvider);
    try {
      await ctrl.changeRole(workspaceCode: widget.workspace.code, userId: m.userId, newRole: 'OWNER');
    } catch (e) {
      if (mounted) setState(() => _busy = false);
      _fail(e);
      return;
    }
    try {
      await ctrl.changeRole(workspaceCode: widget.workspace.code, userId: widget.selfUserId!, newRole: 'ADMIN');
      ref.invalidate(myWorkspacesProvider);
      if (!mounted) return;
      showToast(context, '${_who(m)} is now the owner; you’re an admin');
      Navigator.of(context).pop();
    } catch (e) {
      // The promotion stuck; only stepping down failed. Say exactly that.
      if (!mounted) return;
      setState(() => _busy = false);
      _form.applyServerError(ApiErrorInfo(
        message: '${_who(m)} is now an owner, but you couldn’t be moved to admin (${e is MembersApiException ? e.message : ApiErrorInfo.from(e).message}). You’re still an owner too.',
      ));
    }
  }

  Future<void> _remove() async {
    final ok = await confirmAction(
      context,
      title: _isSelf ? 'Leave ${widget.workspace.displayName}?' : 'Remove ${_who(m)}?',
      message: _isSelf
          ? 'You lose access to this workspace right away. Someone with admin rights would have to invite you again.'
          : '${_who(m)} loses access to ${widget.workspace.displayName} right away. Their account isn’t deleted, and API keys they created keep working until revoked.',
      confirmLabel: _isSelf ? 'Leave workspace' : 'Remove member',
      destructive: true,
    );
    if (!ok) return;
    setState(() => _busy = true);
    try {
      await ref.read(membersControllerProvider).removeMember(workspaceCode: widget.workspace.code, userId: m.userId);
      if (_isSelf) ref.invalidate(myWorkspacesProvider);
      if (!mounted) return;
      showToast(context, _isSelf ? 'You left ${widget.workspace.displayName}' : 'Removed ${_who(m)}');
      Navigator.of(context).pop();
      if (_isSelf) GoRouter.of(context).go('/workspaces');
    } catch (e) {
      if (mounted) setState(() => _busy = false);
      _fail(e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final lock = _lockReason;
    final canRemove = roleAtLeastTier(widget.actorRole, 'ADMIN') && !(m.role == 'OWNER' && !_actorIsOwner);
    final removeBlocker = !canRemove
        ? (m.role == 'OWNER' ? 'Only an owner can remove an owner.' : 'Only admins and owners can remove members.')
        : (m.role == 'OWNER' && _ownerCount <= 1)
            ? 'The last owner can’t be removed. Make someone else an owner first.'
            : null;
    final canTransfer = widget.actorRole == 'OWNER' && !_isSelf && m.role != 'OWNER' && widget.selfUserId != null;
    return YUnsavedGuard(
      form: _form,
      child: YSheetScaffold(
        title: '${_who(m)}${_isSelf ? ' (you)' : ''}',
        subtitle: [if (m.email != null) m.email!, if (m.addedAt != null) 'joined ${formatRelativeIso(m.addedAt)}'].join(' · '),
        headerActions: [Padding(padding: const EdgeInsets.only(top: 6, right: 8), child: RoleChip(m.role))],
        body: ListView(
          padding: const EdgeInsets.fromLTRB(28, 20, 28, 28),
          children: [
            if (_form.summary.isNotEmpty || _form.formError != null) ...[
              YErrorSummary(form: _form, action: 'changing this member'),
              const SizedBox(height: Gap.lg),
            ],
            if (lock != null) ...[
              YBanner(tone: YBannerTone.info, message: lock),
              const SizedBox(height: Gap.lg),
            ],
            IgnorePointer(
              ignoring: lock != null,
              child: Opacity(
                opacity: lock != null ? 0.6 : 1,
                child: YEnumCards<String>(
                  label: 'Role',
                  wireKey: 'role',
                  form: _form,
                  name: 'role',
                  value: _role,
                  options: roleOptions({..._targets, m.role}.toList()..sort((a, b) => _order(a) - _order(b))),
                  helper: _actorIsOwner ? null : 'Only an owner can make someone an owner.',
                  onChanged: (r) {
                    setState(() => _role = r);
                    _form.refresh();
                  },
                ),
              ),
            ),
            const SizedBox(height: Gap.xl),
            Text('Other actions', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: scheme.onSurface)),
            const SizedBox(height: Gap.sm),
            if (canTransfer) ...[
              OutlinedButton.icon(
                onPressed: _busy ? null : _transferOwnership,
                icon: const Icon(Icons.swap_horiz, size: 18),
                label: const Text('Transfer ownership to them…'),
              ),
              Padding(
                padding: const EdgeInsets.only(top: 4, bottom: Gap.md),
                child: Text('Makes them an owner and moves you to admin, in one step.',
                    style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant)),
              ),
            ],
            Tooltip(
              message: removeBlocker ?? '',
              child: OutlinedButton.icon(
                style: OutlinedButton.styleFrom(foregroundColor: scheme.error),
                onPressed: _busy || removeBlocker != null ? null : _remove,
                icon: Icon(_isSelf ? Icons.logout : Icons.person_remove_alt_1_outlined, size: 18),
                label: Text(_isSelf ? 'Leave workspace…' : 'Remove from workspace…'),
              ),
            ),
            if (removeBlocker != null)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Text(removeBlocker, style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant)),
              ),
          ],
        ),
        footer: lock != null
            ? null
            : YActionBar(
                form: _form,
                primaryLabel: _role == m.role ? 'No role change' : 'Make ${roleLabel(_role).toLowerCase()}',
                onPrimary: _saveRole,
                onCancel: () => closeYSheet(context),
              ),
      ),
    );
  }

  static int _order(String r) => const ['OWNER', 'ADMIN', 'MEMBER', 'VIEWER'].indexOf(r);
}

// ---------------------------------------------------------------------------
// Invitations
// ---------------------------------------------------------------------------

class _InvitationsTab extends ConsumerWidget {
  const _InvitationsTab({required this.workspace, required this.actorRole});
  final Workspace workspace;
  final String actorRole;

  bool get _canManage => roleAtLeastTier(actorRole, 'ADMIN');

  Future<void> _invite(BuildContext context) async {
    final email = await showYSheet<String>(
      context,
      width: 600,
      label: 'Invite a teammate',
      builder: (_) => InviteMemberSheet(workspaceCode: workspace.code, workspaceName: workspace.displayName),
    );
    if (email != null && context.mounted) showToast(context, 'Invitation sent to $email');
  }

  Future<void> _revoke(BuildContext context, WidgetRef ref, Invitation inv) async {
    final ok = await confirmAction(
      context,
      title: 'Revoke the invitation for ${inv.email}?',
      message: 'The link in their email stops working immediately. You can invite them again later.',
      confirmLabel: 'Revoke invitation',
      destructive: true,
    );
    if (!ok) return;
    try {
      await ref.read(membersControllerProvider).revoke(workspaceCode: workspace.code, invitationId: inv.id);
      if (context.mounted) showToast(context, 'Revoked the invitation for ${inv.email}');
    } catch (e) {
      if (context.mounted) {
        showToast(context, e is MembersApiException ? e.message : ApiErrorInfo.from(e).message, error: true);
      }
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (!_canManage) {
      return const YEmptyState(
        icon: Icons.lock_outline,
        title: 'Only admins and owners can see invitations',
        message: 'Ask an admin of this workspace if you need to invite someone.',
      );
    }
    final pending = ref.watch(invitationsProvider(workspace.code));
    final inviteBtn = FilledButton.icon(
      onPressed: () => _invite(context),
      icon: const Icon(Icons.person_add_alt_1, size: 18),
      label: const Text('Invite teammate'),
    );
    return Padding(
      padding: const EdgeInsets.only(top: Gap.lg),
      child: LiveAsyncView<List<Invitation>>(
        value: pending,
        what: 'invitations',
        onRetry: () => ref.invalidate(invitationsProvider(workspace.code)),
        data: (context, list) => YTable<Invitation>(
          rows: list,
          noun: 'invitations',
          idOf: (i) => i.id,
          searchHint: 'Search by email',
          searchTextOf: (i) => '${i.email} ${i.role}',
          toolbarTrailing: [inviteBtn],
          empty: YEmptyState(
            icon: Icons.mail_outline,
            title: 'No pending invitations',
            message: 'Invitations you send appear here until they’re accepted or expire.',
            action: inviteBtn,
          ),
          columns: [
            YColumn(label: 'Email', flex: 4, sortKey: (i) => i.email, cell: (context, i) => Text(i.email, overflow: TextOverflow.ellipsis)),
            YColumn(label: 'Role', width: 140, sortKey: (i) => i.role, cell: (context, i) => RoleChip(i.role)),
            YColumn(label: 'Sent', width: 120, sortKey: (i) => i.createdAt ?? '', cell: (context, i) => Text(formatRelativeIso(i.createdAt))),
            YColumn(
              label: 'Expires',
              width: 140,
              sortKey: (i) => i.expiresAt ?? '',
              cell: (context, i) {
                final exp = DateTime.tryParse(i.expiresAt ?? '');
                if (exp == null) return const Text('—');
                if (exp.isBefore(DateTime.now())) return const YBadge('Expired', tone: YTone.warning, icon: Icons.schedule);
                return Tooltip(message: i.expiresAt!, child: Text(_inDays(exp)));
              },
            ),
            YColumn(
              label: '',
              width: 110,
              alignEnd: true,
              cell: (context, i) => TextButton(
                style: TextButton.styleFrom(foregroundColor: Theme.of(context).colorScheme.error),
                onPressed: () => _revoke(context, ref, i),
                child: const Text('Revoke'),
              ),
            ),
          ],
        ),
      ),
    );
  }

  static String _inDays(DateTime exp) {
    final d = exp.difference(DateTime.now());
    if (d.inDays >= 1) return 'in ${d.inDays} ${d.inDays == 1 ? 'day' : 'days'}';
    if (d.inHours >= 1) return 'in ${d.inHours} h';
    return 'in ${d.inMinutes.clamp(1, 59)} min';
  }
}

// ---------------------------------------------------------------------------
// Danger zone — archive
// ---------------------------------------------------------------------------

class _DangerZoneTab extends ConsumerStatefulWidget {
  const _DangerZoneTab({required this.workspace, required this.role});
  final Workspace workspace;
  final String role;

  @override
  ConsumerState<_DangerZoneTab> createState() => _DangerZoneTabState();
}

class _DangerZoneTabState extends ConsumerState<_DangerZoneTab> {
  bool _busy = false;

  bool get _canArchive => widget.role == 'OWNER' || widget.role == 'PLATFORM_ADMIN';

  Future<void> _archive() async {
    final ws = widget.workspace;
    final ok = await confirmTyped(
      context,
      title: 'Archive ${ws.displayName}?',
      message: 'The workspace disappears from everyone’s list and its agents stop serving. Data is kept for audit; only a platform admin can restore it.',
      expected: ws.code,
      confirmLabel: 'Archive workspace',
    );
    if (!ok) return;
    setState(() => _busy = true);
    try {
      final api = await ref.read(workspacesApiProvider.future);
      await api.archive(ws.code, ws.code);
      ref.invalidate(myWorkspacesProvider);
      if (ref.read(currentWorkspaceOrNull) == ws.code) {
        await ref.read(currentWorkspaceProvider.notifier).switchTo('');
      }
      if (!mounted) return;
      context.go('/workspaces');
      showToast(context, 'Archived ${ws.code}');
    } on WorkspaceApiException catch (e) {
      if (mounted) {
        setState(() => _busy = false);
        showToast(context, e.message, error: true);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return ListView(
      padding: const EdgeInsets.only(top: Gap.lg, bottom: Gap.xl),
      children: [
        Container(
          padding: const EdgeInsets.all(Gap.xl),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: scheme.error.withValues(alpha: 0.5)),
          ),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('Archive this workspace', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: scheme.onSurface)),
            const SizedBox(height: 6),
            Text(
              'Hides the workspace from every member’s list and stops its agents. Data is preserved for audit; only a platform admin can restore it. You’ll be asked to type the workspace code.',
              style: TextStyle(fontSize: 14, height: 1.5, color: scheme.onSurfaceVariant),
            ),
            const SizedBox(height: Gap.lg),
            Tooltip(
              message: _canArchive ? '' : 'Only an owner can archive the workspace.',
              child: FilledButton.icon(
                style: FilledButton.styleFrom(backgroundColor: scheme.error, foregroundColor: scheme.onError),
                onPressed: _canArchive && !_busy ? _archive : null,
                icon: const Icon(Icons.archive_outlined, size: 18),
                label: const Text('Archive workspace…'),
              ),
            ),
            if (!_canArchive)
              Padding(
                padding: const EdgeInsets.only(top: 6),
                child: Text('Only an owner can archive the workspace.', style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant)),
              ),
          ]),
        ),
      ],
    );
  }
}
