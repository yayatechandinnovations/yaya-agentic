import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../ui/data/y_states.dart';
import '../../../ui/feedback/api_error.dart';
import '../../auth/application/auth_controller.dart';
import '../../auth/models/auth_state.dart';

/// Pulls the human-readable message out of a Dio error. The backend's
/// AdminExceptionHandler emits {error, message}; everything else falls
/// back to the exception's own message.
String formatError(Object e) => ApiErrorInfo.from(e).message;

/// Shown by every tenant-scoped admin screen when no tenant is selected:
/// says where to pick one (the Workspace · Tenant card in the side nav) and
/// offers the way to create one this operator is allowed to use.
class TenantScopedEmptyState extends ConsumerWidget {
  const TenantScopedEmptyState({super.key, required this.resourceLabel});
  final String resourceLabel;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authProvider);
    final platformAdmin = auth is AuthAuthenticated && auth.operator.platformAdmin;
    return YEmptyState(
      icon: Icons.apartment_outlined,
      title: 'Pick a workspace first',
      message: '$resourceLabel belong to a workspace. Choose one in the Workspace · Tenant card at the top of the '
          'side bar${platformAdmin ? ', or register a tenant if none exist yet' : ', or create a workspace'}.',
      action: FilledButton.icon(
        onPressed: () => GoRouter.of(context).go(platformAdmin ? '/admin/tenants' : '/workspaces'),
        icon: Icon(platformAdmin ? Icons.add_business_outlined : Icons.workspaces_outline, size: 18),
        label: Text(platformAdmin ? 'Go to tenants' : 'Go to workspaces'),
      ),
    );
  }
}
