import '../../../api/admin_api.dart';
import '../../../models/admin/platform_sender.dart';
import '../../../models/admin/whatsapp_sender.dart';

/// The registration calls behind the sender wizard and the code/submit
/// dialogs. A workspace sender lives under `/v1/admin/tenants/{t}/…`, a shared
/// platform sender under `/v1/admin/platform-senders` (shared-sender §5.1) —
/// the lifecycle is the same state machine, only the path differs.
abstract interface class SenderRegistrationRepository {
  bool get isPlatform;

  Future<WhatsAppSenderResponse> create(WhatsAppSenderRequest body, {String? unroutedReply});

  Future<WhatsAppSenderResponse> update(String senderId, WhatsAppSenderRequest body, {String? unroutedReply});

  Future<WhatsAppSenderResponse> submit(String senderId, {String? verificationMethod});

  Future<WhatsAppSenderResponse> verify(String senderId, String code);

  Future<WhatsAppSenderResponse> sync(String senderId);
}

class WorkspaceSenderRepository implements SenderRegistrationRepository {
  const WorkspaceSenderRepository(this.api, this.tenant);

  final AdminApi api;
  final String tenant;

  @override
  bool get isPlatform => false;

  @override
  Future<WhatsAppSenderResponse> create(WhatsAppSenderRequest body, {String? unroutedReply}) =>
      api.createWhatsAppSender(tenant, body);

  @override
  Future<WhatsAppSenderResponse> update(String senderId, WhatsAppSenderRequest body, {String? unroutedReply}) =>
      api.updateWhatsAppSender(tenant, senderId, body);

  @override
  Future<WhatsAppSenderResponse> submit(String senderId, {String? verificationMethod}) =>
      api.submitWhatsAppSender(tenant, senderId, verificationMethod: verificationMethod);

  @override
  Future<WhatsAppSenderResponse> verify(String senderId, String code) => api.verifyWhatsAppSender(tenant, senderId, code);

  @override
  Future<WhatsAppSenderResponse> sync(String senderId) => api.syncWhatsAppSender(tenant, senderId);
}

class PlatformSenderRepository implements SenderRegistrationRepository {
  const PlatformSenderRepository(this.api);

  final AdminApi api;

  @override
  bool get isPlatform => true;

  PlatformSenderRequest _request(WhatsAppSenderRequest body, String? unroutedReply) => PlatformSenderRequest(
        phoneE164: body.phoneE164,
        displayName: body.displayName,
        businessProfile: body.businessProfile,
        defaultLocale: body.defaultLocale,
        unroutedReply: unroutedReply,
      );

  @override
  Future<WhatsAppSenderResponse> create(WhatsAppSenderRequest body, {String? unroutedReply}) async =>
      (await api.createPlatformSender(_request(body, unroutedReply))).sender;

  @override
  Future<WhatsAppSenderResponse> update(String senderId, WhatsAppSenderRequest body, {String? unroutedReply}) async =>
      (await api.updatePlatformSender(senderId, _request(body, unroutedReply))).sender;

  @override
  Future<WhatsAppSenderResponse> submit(String senderId, {String? verificationMethod}) async =>
      (await api.submitPlatformSender(senderId, verificationMethod: verificationMethod)).sender;

  @override
  Future<WhatsAppSenderResponse> verify(String senderId, String code) async =>
      (await api.verifyPlatformSender(senderId, code)).sender;

  @override
  Future<WhatsAppSenderResponse> sync(String senderId) async => (await api.syncPlatformSender(senderId)).sender;
}
