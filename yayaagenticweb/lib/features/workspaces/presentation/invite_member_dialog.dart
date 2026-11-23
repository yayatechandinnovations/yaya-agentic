import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../ui/data/y_sheet.dart';
import '../../../ui/feedback/api_error.dart';
import '../../../ui/form/unsaved_guard.dart';
import '../../../ui/form/y_fields.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/tokens.dart';
import '../application/members_controller.dart';
import '../data/members_api.dart';
import 'role_chip.dart';

final _email = RegExp(r'^[^@\s]+@[^@\s]+\.[^@\s]+$');

String? validateEmail(String v) {
  final s = v.trim();
  if (s.isEmpty) return 'Enter the teammate’s email address.';
  if (!_email.hasMatch(s)) return 'Enter a full email address, e.g. ana@acme.com';
  return null;
}

/// Invite a teammate by email. Owner is never granted by invitation — the
/// backend accepts ADMIN, MEMBER or VIEWER; promote to owner after they join.
/// Pops with the invited email on success.
class InviteMemberSheet extends ConsumerStatefulWidget {
  const InviteMemberSheet({super.key, required this.workspaceCode, required this.workspaceName});
  final String workspaceCode;
  final String workspaceName;

  @override
  ConsumerState<InviteMemberSheet> createState() => _InviteMemberSheetState();
}

class _InviteMemberSheetState extends ConsumerState<InviteMemberSheet> {
  static const _roles = ['ADMIN', 'MEMBER', 'VIEWER'];
  final _emailCtrl = TextEditingController();
  String _role = 'MEMBER';
  late final YFormController _form;

  @override
  void initState() {
    super.initState();
    _form = YFormController(
      snapshot: () => {'email': _emailCtrl.text.trim(), 'role': _role},
      validate: () => {'email': validateEmail(_emailCtrl.text)},
      labels: const {'email': 'Email', 'role': 'Role'},
      draftKey: 'yaya.draft.invitation.${widget.workspaceCode}.new',
    );
    _form.addListener(_rebuild);
    _form.loadDraft().then((d) {
      if (d == null || !mounted) return;
      _emailCtrl.text = (d.values['email'] as String?) ?? '';
      final r = d.values['role'];
      if (r is String && _roles.contains(r)) _role = r;
      _form.refresh();
    });
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _form.dispose();
    _emailCtrl.dispose();
    super.dispose();
  }

  Future<void> _send() async {
    if (!_form.submit()) return;
    _form.saving = true;
    try {
      await ref.read(membersControllerProvider).invite(
            workspaceCode: widget.workspaceCode,
            email: _emailCtrl.text.trim(),
            role: _role,
          );
      _form.saving = false;
      _form.markClean();
      if (mounted) Navigator.of(context).pop(_emailCtrl.text.trim());
    } on MembersApiException catch (e) {
      _form.saving = false;
      _form.applyServerError(ApiErrorInfo(message: e.message, code: e.errorCode, status: e.statusCode));
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(e);
    }
  }

  @override
  Widget build(BuildContext context) {
    return YUnsavedGuard(
      form: _form,
      child: YSaveShortcut(
        onSave: _send,
        child: YSheetScaffold(
          title: 'Invite a teammate',
          subtitle: 'They get an email with a link to join ${widget.workspaceName}. The invitation expires in 7 days.',
          body: ListView(
            padding: const EdgeInsets.fromLTRB(28, 20, 28, 28),
            children: [
              if (_form.summary.isNotEmpty || _form.formError != null) ...[
                YErrorSummary(form: _form, action: 'sending the invitation'),
                const SizedBox(height: Gap.lg),
              ],
              YTextField(
                label: 'Email',
                wireKey: 'email',
                controller: _emailCtrl,
                form: _form,
                name: 'email',
                required: true,
                autofocus: true,
                keyboardType: TextInputType.emailAddress,
                hint: 'e.g. ana@acme.com',
                onSubmitted: (_) => _send(),
              ),
              const SizedBox(height: Gap.fieldGap),
              YEnumCards<String>(
                label: 'Role',
                wireKey: 'role',
                form: _form,
                name: 'role',
                required: true,
                value: _role,
                options: roleOptions(_roles),
                helper: 'To make someone an owner, invite them first, then change their role once they’ve joined.',
                onChanged: (r) => setState(() => _role = r),
              ),
            ],
          ),
          footer: YActionBar(
            form: _form,
            primaryLabel: 'Send invitation',
            onPrimary: _send,
            onCancel: () => closeYSheet(context),
          ),
        ),
      ),
    );
  }
}
