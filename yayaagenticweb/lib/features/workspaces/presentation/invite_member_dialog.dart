import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../application/members_controller.dart';
import '../data/members_api.dart';

/// Invite-a-teammate modal. ADMIN can pick MEMBER or VIEWER; OWNERs (and
/// platform admins) can additionally pick ADMIN. Promoting someone to
/// OWNER is intentionally NOT done via invitation — see the membership
/// service (must promote post-join).
class InviteMemberDialog extends ConsumerStatefulWidget {
  const InviteMemberDialog({
    super.key,
    required this.workspaceCode,
    required this.actorRole,
  });

  final String workspaceCode;
  /// Caller's role in the workspace. Drives which roles they may grant.
  final String actorRole;

  @override
  ConsumerState<InviteMemberDialog> createState() => _InviteMemberDialogState();
}

class _InviteMemberDialogState extends ConsumerState<InviteMemberDialog> {
  final _formKey = GlobalKey<FormState>();
  final _email = TextEditingController();
  String _role = 'MEMBER';
  bool _sending = false;
  String? _error;

  @override
  void dispose() {
    _email.dispose();
    super.dispose();
  }

  List<String> _allowedRoles() {
    // ADMIN can grant ADMIN/MEMBER/VIEWER; OWNER + PLATFORM_ADMIN same set
    // (OWNER is granted via a separate promotion path, not invitation).
    return ['ADMIN', 'MEMBER', 'VIEWER'];
  }

  Future<void> _submit() async {
    if (_sending) return;
    if (!(_formKey.currentState?.validate() ?? false)) return;
    setState(() {
      _sending = true;
      _error = null;
    });
    try {
      await ref.read(membersControllerProvider).invite(
            workspaceCode: widget.workspaceCode,
            email: _email.text.trim(),
            role: _role,
          );
      if (!mounted) return;
      Navigator.of(context).pop(true);
    } on MembersApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _sending = false;
        _error = e.message;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _sending = false;
        _error = 'Invite failed: $e';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return AlertDialog(
      title: const Text('Invite a teammate'),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 380),
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextFormField(
                controller: _email,
                autofocus: true,
                keyboardType: TextInputType.emailAddress,
                decoration: const InputDecoration(
                  labelText: 'Email',
                  hintText: 'teammate@example.com',
                ),
                validator: (v) {
                  final s = v?.trim() ?? '';
                  if (s.isEmpty) return 'Required';
                  if (!s.contains('@') || !s.contains('.')) return 'Invalid email';
                  return null;
                },
              ),
              const SizedBox(height: 12),
              Text('Role', style: theme.textTheme.labelLarge),
              const SizedBox(height: 4),
              for (final role in _allowedRoles())
                RadioListTile<String>(
                  value: role,
                  // groupValue/onChanged are the available API in this
                  // Flutter channel; RadioGroup is post-3.32 only.
                  // ignore: deprecated_member_use
                  groupValue: _role,
                  // ignore: deprecated_member_use
                  onChanged: (v) => setState(() => _role = v ?? _role),
                  dense: true,
                  contentPadding: EdgeInsets.zero,
                  title: Text(role),
                  subtitle: Text(_helpFor(role),
                      style: theme.textTheme.bodySmall),
                ),
              const SizedBox(height: 4),
              Text(
                'The invitation expires in 7 days.',
                style: theme.textTheme.bodySmall
                    ?.copyWith(color: theme.colorScheme.outline),
              ),
              if (_error != null) const SizedBox(height: 12),
              if (_error != null)
                Text(_error!,
                    style: TextStyle(color: theme.colorScheme.error)),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _sending ? null : () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _sending ? null : _submit,
          child: _sending
              ? const SizedBox(
                  height: 16,
                  width: 16,
                  child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Send invitation'),
        ),
      ],
    );
  }

  String _helpFor(String role) {
    switch (role) {
      case 'ADMIN':
        return 'Can manage members, secrets, and bindings.';
      case 'MEMBER':
        return 'Can build profiles, capabilities, and tools.';
      case 'VIEWER':
        return 'Read-only.';
      default:
        return '';
    }
  }
}
