import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../models/admin/invitation.dart';
import '../../../models/admin/member.dart';
import '../data/members_api.dart';

/// Members of a given workspace (keyed by code). Invalidated by mutation
/// methods on [MembersController] below.
final membersProvider =
    FutureProvider.family<List<Member>, String>((ref, workspaceCode) async {
  final api = await ref.watch(workspaceMembersApiProvider.future);
  return api.listMembers(workspaceCode);
});

/// Pending invitations for a given workspace.
final invitationsProvider =
    FutureProvider.family<List<Invitation>, String>((ref, workspaceCode) async {
  final api = await ref.watch(workspaceMembersApiProvider.future);
  return api.listInvitations(workspaceCode);
});

/// Tiny controller wrapping the mutating calls so the UI layer is
/// invalidation-aware without each callsite re-implementing it.
class MembersController {
  MembersController(this.ref);
  final Ref ref;

  Future<void> changeRole({
    required String workspaceCode,
    required String userId,
    required String newRole,
  }) async {
    final api = await ref.read(workspaceMembersApiProvider.future);
    await api.changeRole(
        workspaceCode: workspaceCode, userId: userId, newRole: newRole);
    ref.invalidate(membersProvider(workspaceCode));
  }

  Future<void> removeMember({
    required String workspaceCode,
    required String userId,
  }) async {
    final api = await ref.read(workspaceMembersApiProvider.future);
    await api.remove(workspaceCode: workspaceCode, userId: userId);
    ref.invalidate(membersProvider(workspaceCode));
  }

  Future<void> invite({
    required String workspaceCode,
    required String email,
    required String role,
  }) async {
    final api = await ref.read(workspaceMembersApiProvider.future);
    await api.invite(
        workspaceCode: workspaceCode, email: email, role: role);
    ref.invalidate(invitationsProvider(workspaceCode));
  }

  Future<void> revoke({
    required String workspaceCode,
    required String invitationId,
  }) async {
    final api = await ref.read(workspaceMembersApiProvider.future);
    await api.revokeInvitation(
        workspaceCode: workspaceCode, invitationId: invitationId);
    ref.invalidate(invitationsProvider(workspaceCode));
  }
}

final membersControllerProvider =
    Provider<MembersController>((ref) => MembersController(ref));
