import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/live/live_resource.dart';
import '../../../app/live/resource_kind.dart';
import '../../../models/admin/platform_sender.dart';

/// Every shared platform sender (platform admins only).
final platformSendersProvider = FutureProvider<List<PlatformSenderResponse>>((ref) async {
  liveResource(ref, AdminResourceKind.whatsappSender);
  final api = await ref.watch(adminApiProvider.future);
  final list = await api.listPlatformSenders();
  // WhatsApp review settles outside the admin API.
  final settling = list.any((p) => p.sender.status == 'SUBMITTING' || p.sender.status == 'PENDING_REVIEW');
  final quick = list.any((p) => p.sender.status == 'SUBMITTING');
  pollWhile(ref, settling, every: Duration(seconds: quick ? 3 : 10));
  return list;
});

final platformSenderAssignmentsProvider =
    FutureProvider.family<List<PlatformSenderAssignment>, String>((ref, senderId) async {
  liveResource(ref, AdminResourceKind.whatsappSender);
  final api = await ref.watch(adminApiProvider.future);
  return api.listPlatformSenderAssignments(senderId);
});

final platformSenderUnroutedProvider =
    FutureProvider.family<List<UnroutedInbound>, String>((ref, senderId) async {
  final api = await ref.watch(adminApiProvider.future);
  return api.listPlatformSenderUnrouted(senderId);
});
