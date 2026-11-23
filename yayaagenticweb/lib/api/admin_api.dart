import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../models/admin/audit.dart';
import '../models/admin/auth_binding.dart';
import '../models/admin/auth_resolver.dart';
import '../models/admin/capability.dart';
import '../models/admin/channel_binding.dart';
import '../models/admin/channel_opt_out.dart';
import '../models/admin/chat_entry_point.dart';
import '../models/admin/secondary_comms.dart';
import '../models/admin/clone.dart';
import '../models/admin/forum.dart';
import '../models/admin/identity_profile_mapping.dart';
import '../models/admin/knowledge_source.dart';
import '../models/admin/network_settings.dart';
import '../models/admin/operator_auth_strategies.dart';
import '../models/admin/profile.dart';
import '../models/admin/receipt_template.dart';
import '../models/admin/recording_strategy.dart';
import '../models/admin/tenant.dart';
import '../models/admin/tenant_jwt_secret.dart';
import '../models/admin/sms_channel.dart';
import '../models/admin/start_flow.dart';
import '../models/admin/system_sender.dart';
import '../models/admin/tenant_oidc_issuer.dart';
import '../models/admin/tool.dart';
import '../models/admin/whatsapp_embedded_signup.dart';
import '../models/admin/whatsapp_sender.dart';
import '../models/admin/whatsapp_template.dart';
import 'api_client.dart';

/// Typed wrapper over /v1/admin/*. All methods unwrap the {error,message}
/// shape on failure into the message string (Dio errors keep the original
/// response on `error.response`).
class AdminApi {
  AdminApi(this._dio);
  final Dio _dio;

  // ---- Tenants -------------------------------------------------------

  Future<List<TenantResponse>> listTenants({String? status}) async {
    final res = await _dio.get('/v1/admin/tenants',
        queryParameters: {if (status != null) 'status': status});
    return (res.data as List)
        .map((e) => TenantResponse.fromJson(_asMap(e)))
        .toList();
  }

  Future<TenantResponse> getTenant(String id) async {
    final res = await _dio.get('/v1/admin/tenants/$id');
    return TenantResponse.fromJson(_asMap(res.data));
  }

  Future<TenantResponse> createTenant(TenantRequest req) async {
    final res = await _dio.post('/v1/admin/tenants', data: req.toJson());
    return TenantResponse.fromJson(_asMap(res.data));
  }

  Future<TenantResponse> patchTenant(String id, TenantRequest req) async {
    final res = await _dio.patch('/v1/admin/tenants/$id', data: req.toJson());
    return TenantResponse.fromJson(_asMap(res.data));
  }

  Future<TenantResponse> suspendTenant(String id) async {
    final res = await _dio.post('/v1/admin/tenants/$id/suspend');
    return TenantResponse.fromJson(_asMap(res.data));
  }

  Future<TenantResponse> resumeTenant(String id) async {
    final res = await _dio.post('/v1/admin/tenants/$id/resume');
    return TenantResponse.fromJson(_asMap(res.data));
  }

  Future<TenantResponse> archiveTenant(String id) async {
    final res = await _dio.post('/v1/admin/tenants/$id/archive');
    return TenantResponse.fromJson(_asMap(res.data));
  }

  Future<TenantHealthResponse> tenantHealth(String id) async {
    final res = await _dio.get('/v1/admin/tenants/$id/health');
    return TenantHealthResponse.fromJson(_asMap(res.data));
  }

  // ---- Cross-tenant profile clone ------------------------------------

  Future<CloneResult> cloneProfile({
    required String sourceTenant,
    required String profileId,
    required int version,
    required CloneRequest body,
  }) async {
    final res = await _dio.post(
      '/v1/admin/tenants/$sourceTenant/profiles/$profileId/$version/clone',
      data: body.toJson(),
    );
    return CloneResult.fromJson(_asMap(res.data));
  }

  // ---- Absolute→path migrator ----------------------------------------

  Future<MigrateToPathPlan> migrateToolsToPath({
    required String tenant,
    bool dryRun = true,
  }) async {
    final res = await _dio.post(
      '/v1/admin/tools/migrate-to-path',
      queryParameters: {'tenant': tenant, 'dryRun': dryRun},
    );
    return MigrateToPathPlan.fromJson(_asMap(res.data));
  }

  // ---- Profiles ------------------------------------------------------

  Future<List<ProfileResponse>> listProfiles({String tenant = 'default'}) async {
    final res = await _dio.get('/v1/admin/profiles', queryParameters: {'tenant': tenant});
    return (res.data as List)
        .map((e) => ProfileResponse.fromJson(_asMap(e)))
        .toList();
  }

  Future<ProfileResponse> createProfile(ProfileRequest req) async {
    final res = await _dio.post('/v1/admin/profiles', data: req.toWireJson());
    return ProfileResponse.fromJson(_asMap(res.data));
  }

  // ---- Capabilities --------------------------------------------------

  Future<List<CapabilityResponse>> listCapabilities({String tenant = 'default'}) async {
    final res = await _dio.get('/v1/admin/capabilities', queryParameters: {'tenant': tenant});
    return (res.data as List)
        .map((e) => CapabilityResponse.fromJson(_asMap(e)))
        .toList();
  }

  Future<CapabilityResponse> createCapability(CapabilityRequest req) async {
    final res = await _dio.post('/v1/admin/capabilities', data: req.toJson());
    return CapabilityResponse.fromJson(_asMap(res.data));
  }

  // ---- Tools ---------------------------------------------------------

  Future<List<ToolResponse>> listTools({String tenant = 'default'}) async {
    final res = await _dio.get('/v1/admin/tools', queryParameters: {'tenant': tenant});
    return (res.data as List)
        .map((e) => ToolResponse.fromJson(_asMap(e)))
        .toList();
  }

  Future<ToolResponse> createTool(ToolRequest req) async {
    final res = await _dio.post('/v1/admin/tools', data: req.toJson());
    return ToolResponse.fromJson(_asMap(res.data));
  }

  // ---- Auth bindings -------------------------------------------------

  Future<List<AuthBindingResponse>> listAuthBindings({String tenant = 'default'}) async {
    final res = await _dio.get('/v1/admin/auth-bindings',
        queryParameters: {'tenant': tenant});
    return (res.data as List)
        .map((e) => AuthBindingResponse.fromJson(_asMap(e)))
        .toList();
  }

  Future<AuthBindingResponse> createAuthBinding(AuthBindingRequest req) async {
    final res = await _dio.post('/v1/admin/auth-bindings', data: req.toJson());
    return AuthBindingResponse.fromJson(_asMap(res.data));
  }

  Future<AuthAvailability> availableAuth() async {
    final res = await _dio.get('/v1/admin/auth/available');
    return AuthAvailability.fromJson(_asMap(res.data));
  }

  // ---- Recording strategies -----------------------------------------

  Future<RecordingStrategyResponse> createRecordingStrategy(
      RecordingStrategyRequest req) async {
    final res = await _dio.post('/v1/admin/recording-strategies', data: req.toJson());
    return RecordingStrategyResponse.fromJson(_asMap(res.data));
  }

  Future<RecordingStrategyResponse?> getRecordingStrategy({
    String tenant = 'default',
    required String scopeKind,
    required String scopeId,
  }) async {
    try {
      final res = await _dio.get(
        '/v1/admin/recording-strategies/$scopeKind/$scopeId',
        queryParameters: {'tenant': tenant},
      );
      return RecordingStrategyResponse.fromJson(_asMap(res.data));
    } on DioException catch (e) {
      if (e.response?.statusCode == 404) return null;
      rethrow;
    }
  }

  // ---- Knowledge sources ---------------------------------------------

  Future<List<KnowledgeSourceResponse>> listKnowledgeSources({String tenant = 'default'}) async {
    final res = await _dio.get('/v1/admin/knowledge-sources',
        queryParameters: {'tenant': tenant});
    final items = (res.data['items'] as List? ?? const []);
    return items
        .map((e) => KnowledgeSourceResponse.fromJson(_asMap(e)))
        .toList();
  }

  Future<KnowledgeSourceResponse> createKnowledgeSource(
      CreateKnowledgeSourceRequest req) async {
    final res = await _dio.post('/v1/admin/knowledge-sources', data: req.toJson());
    return KnowledgeSourceResponse.fromJson(_asMap(res.data));
  }

  Future<ReindexResponse> reindexKnowledgeSource(
      {String tenant = 'default', required String id}) async {
    final res = await _dio.post('/v1/admin/knowledge-sources/$id/reindex',
        queryParameters: {'tenant': tenant});
    return ReindexResponse.fromJson(_asMap(res.data));
  }

  /// Upload one or more documents. Bytes are scanned, sniffed, and stored; the
  /// returned refs go into a source's `location.files` (locationKind UPLOADED).
  Future<List<UploadedFileRef>> uploadKnowledgeFiles({
    String tenant = 'default',
    required List<MultipartFile> files,
  }) async {
    final form = FormData();
    for (final f in files) {
      form.files.add(MapEntry('files', f));
    }
    final res = await _dio.post(
      '/v1/admin/knowledge-sources/uploads',
      queryParameters: {'tenant': tenant},
      data: form,
    );
    final list = (res.data['files'] as List? ?? const []);
    return list.map((e) => UploadedFileRef.fromJson(_asMap(e))).toList();
  }

  Future<void> deleteKnowledgeBlob(
      {String tenant = 'default', required String blobId}) async {
    await _dio.delete('/v1/admin/knowledge-sources/uploads/$blobId',
        queryParameters: {'tenant': tenant});
  }

  /// Live ingestion status incl. the async job state (QUEUED/RUNNING/DONE/FAILED).
  Future<Map<String, dynamic>> knowledgeSourceStatus(
      {String tenant = 'default', required String id}) async {
    final res = await _dio.get('/v1/admin/knowledge-sources/$id/status',
        queryParameters: {'tenant': tenant});
    return _asMap(res.data);
  }

  // ---- Operator-auth strategies -------------------------------------

  Future<StrategiesResponse> getStrategies() async {
    final res = await _dio.get('/v1/admin/auth/strategies');
    return StrategiesResponse.fromJson(_asMap(res.data));
  }

  Future<StrategiesResponse> putBootstrapStrategy({
    bool? enabled,
    String? newPassword,
  }) async {
    final res = await _dio.put('/v1/admin/auth/strategies/bootstrap', data: {
      if (enabled != null) 'enabled': enabled,
      if (newPassword != null && newPassword.isNotEmpty) 'newPassword': newPassword,
    });
    return StrategiesResponse.fromJson(_asMap(res.data));
  }

  Future<StrategiesResponse> putDelegateStrategy(
    DelegateView delegate, {
    String? sharedSecret,           // null = preserve existing
    bool confirmPermissive = false,
  }) async {
    final res = await _dio.put(
      '/v1/admin/auth/strategies/http-delegate',
      queryParameters: {if (confirmPermissive) 'confirmPermissive': true},
      data: {
        'enabled': delegate.enabled,
        if (delegate.url != null) 'url': delegate.url,
        if (sharedSecret != null && sharedSecret.isNotEmpty) 'sharedSecret': sharedSecret,
        'timeoutMs': delegate.timeoutMs,
        'requireHttps': delegate.requireHttps,
        'request': delegate.request.toJson(),
        'success': delegate.success.toJson(),
        'identity': delegate.identity.toJson(),
        'failure': delegate.failure.toJson(),
      },
    );
    return StrategiesResponse.fromJson(_asMap(res.data));
  }

  Future<ProbeResult> testDelegate({
    required String username,
    required String password,
  }) async {
    final res = await _dio.post(
      '/v1/admin/auth/strategies/http-delegate/test',
      data: {'username': username, 'password': password},
    );
    return ProbeResult.fromJson(_asMap(res.data));
  }

  // ---- Audit ---------------------------------------------------------

  Future<AuthzAuditPage> searchAuthzAudit({
    String tenant = 'default',
    String? decision,
    String? principal,
    String? toolId,
    int page = 0,
    int pageSize = 20,
  }) async {
    final res = await _dio.get('/v1/admin/audit/authz', queryParameters: {
      'tenant': tenant,
      if (decision != null) 'decision': decision,
      if (principal != null) 'principal': principal,
      if (toolId != null) 'toolId': toolId,
      'page': page,
      'pageSize': pageSize,
    });
    return AuthzAuditPage.fromJson(_asMap(res.data));
  }

  // ---- Tenant OIDC issuers -------------------------------------------

  Future<List<TenantOidcIssuerResponse>> listTenantOidcIssuers(String tenantId) async {
    final res = await _dio.get('/v1/admin/tenants/$tenantId/oidc-issuers');
    return (res.data as List)
        .map((e) => TenantOidcIssuerResponse.fromJson(_asMap(e)))
        .toList();
  }

  Future<TenantOidcIssuerResponse> createTenantOidcIssuer(
      String tenantId, TenantOidcIssuerRequest body) async {
    final res = await _dio.post('/v1/admin/tenants/$tenantId/oidc-issuers',
        data: body.toJson());
    return TenantOidcIssuerResponse.fromJson(_asMap(res.data));
  }

  Future<TenantOidcIssuerResponse> updateTenantOidcIssuer(
      String tenantId, int id, TenantOidcIssuerRequest body) async {
    final res = await _dio.put('/v1/admin/tenants/$tenantId/oidc-issuers/$id',
        data: body.toJson());
    return TenantOidcIssuerResponse.fromJson(_asMap(res.data));
  }

  Future<void> deleteTenantOidcIssuer(String tenantId, int id) async {
    await _dio.delete('/v1/admin/tenants/$tenantId/oidc-issuers/$id');
  }

  // ---- Tenant JWT shared secrets (HS256) -----------------------------

  Future<List<TenantJwtSecretResponse>> listTenantJwtSecrets(String tenantId) async {
    final res = await _dio.get('/v1/admin/tenants/$tenantId/jwt-secrets');
    return (res.data as List)
        .map((e) => TenantJwtSecretResponse.fromJson(_asMap(e)))
        .toList();
  }

  Future<TenantJwtSecretResponse> createTenantJwtSecret(
      String tenantId, TenantJwtSecretRequest body) async {
    final res = await _dio.post('/v1/admin/tenants/$tenantId/jwt-secrets',
        data: body.toJson());
    return TenantJwtSecretResponse.fromJson(_asMap(res.data));
  }

  Future<TenantJwtSecretResponse> updateTenantJwtSecret(
      String tenantId, int id, TenantJwtSecretRequest body) async {
    final res = await _dio.put('/v1/admin/tenants/$tenantId/jwt-secrets/$id',
        data: body.toJson());
    return TenantJwtSecretResponse.fromJson(_asMap(res.data));
  }

  Future<void> deleteTenantJwtSecret(String tenantId, int id) async {
    await _dio.delete('/v1/admin/tenants/$tenantId/jwt-secrets/$id');
  }

  // ---- Identity → profile mappings -----------------------------------

  Future<List<IdentityProfileMappingResponse>> listIdentityProfileMappings(
      String tenantId) async {
    final res = await _dio.get(
        '/v1/admin/tenants/$tenantId/identity-profile-mappings');
    return (res.data as List)
        .map((e) => IdentityProfileMappingResponse.fromJson(_asMap(e)))
        .toList();
  }

  Future<IdentityProfileMappingResponse> createIdentityProfileMapping(
      String tenantId, IdentityProfileMappingRequest body) async {
    final res = await _dio.post(
        '/v1/admin/tenants/$tenantId/identity-profile-mappings',
        data: body.toJson());
    return IdentityProfileMappingResponse.fromJson(_asMap(res.data));
  }

  Future<IdentityProfileMappingResponse> updateIdentityProfileMapping(
      String tenantId, int id, IdentityProfileMappingRequest body) async {
    final res = await _dio.put(
        '/v1/admin/tenants/$tenantId/identity-profile-mappings/$id',
        data: body.toJson());
    return IdentityProfileMappingResponse.fromJson(_asMap(res.data));
  }

  Future<void> deleteIdentityProfileMapping(String tenantId, int id) async {
    await _dio.delete(
        '/v1/admin/tenants/$tenantId/identity-profile-mappings/$id');
  }

  // ---- WhatsApp senders ----------------------------------------------

  Future<List<WhatsAppSenderResponse>> listWhatsAppSenders(String tenantId) async {
    final res = await _dio.get('/v1/admin/tenants/$tenantId/whatsapp-senders');
    return (res.data as List)
        .map((e) => WhatsAppSenderResponse.fromJson(_asMap(e)))
        .toList();
  }

  Future<WhatsAppSenderResponse> getWhatsAppSender(
      String tenantId, String senderId) async {
    final res = await _dio
        .get('/v1/admin/tenants/$tenantId/whatsapp-senders/$senderId');
    return WhatsAppSenderResponse.fromJson(_asMap(res.data));
  }

  Future<WhatsAppSenderResponse> createWhatsAppSender(
      String tenantId, WhatsAppSenderRequest body) async {
    final res = await _dio.post(
        '/v1/admin/tenants/$tenantId/whatsapp-senders',
        data: body.toJson());
    return WhatsAppSenderResponse.fromJson(_asMap(res.data));
  }

  /// Upload a sender's profile photo (PNG or JPEG, base64) and get back the
  /// platform-hosted URL to store as `businessProfile.logoUrl`.
  Future<String> uploadWhatsAppSenderLogo(
      String tenantId, String contentType, String dataBase64) async {
    final res = await _dio.post(
      '/v1/admin/tenants/$tenantId/whatsapp-senders/logos',
      data: {'contentType': contentType, 'dataBase64': dataBase64},
    );
    return (_asMap(res.data)['logoUrl'] ?? '') as String;
  }

  Future<WhatsAppSenderResponse> updateWhatsAppSender(
      String tenantId, String senderId, WhatsAppSenderRequest body) async {
    final res = await _dio.put(
        '/v1/admin/tenants/$tenantId/whatsapp-senders/$senderId',
        data: body.toJson());
    return WhatsAppSenderResponse.fromJson(_asMap(res.data));
  }

  /// [verificationMethod] is `sms` (default) or `voice` — how WhatsApp
  /// delivers the ownership code to a number that needs one.
  Future<WhatsAppSenderResponse> submitWhatsAppSender(
      String tenantId, String senderId, {String? verificationMethod}) async {
    final res = await _dio.post(
        '/v1/admin/tenants/$tenantId/whatsapp-senders/$senderId/submit',
        data: {
          if (verificationMethod != null) 'verificationMethod': verificationMethod,
        });
    return WhatsAppSenderResponse.fromJson(_asMap(res.data));
  }

  Future<WhatsAppSenderResponse> verifyWhatsAppSender(
      String tenantId, String senderId, String code) async {
    final res = await _dio.post(
        '/v1/admin/tenants/$tenantId/whatsapp-senders/$senderId/verify',
        data: {'code': code});
    return WhatsAppSenderResponse.fromJson(_asMap(res.data));
  }

  Future<WhatsAppSenderResponse> syncWhatsAppSender(
      String tenantId, String senderId) async {
    final res = await _dio.post(
        '/v1/admin/tenants/$tenantId/whatsapp-senders/$senderId/sync');
    return WhatsAppSenderResponse.fromJson(_asMap(res.data));
  }

  Future<WhatsAppSenderResponse> suspendWhatsAppSender(
      String tenantId, String senderId) async {
    final res = await _dio.post(
        '/v1/admin/tenants/$tenantId/whatsapp-senders/$senderId/suspend');
    return WhatsAppSenderResponse.fromJson(_asMap(res.data));
  }

  Future<WhatsAppSenderResponse> resumeWhatsAppSender(
      String tenantId, String senderId) async {
    final res = await _dio.post(
        '/v1/admin/tenants/$tenantId/whatsapp-senders/$senderId/resume');
    return WhatsAppSenderResponse.fromJson(_asMap(res.data));
  }

  Future<WhatsAppSenderResponse> revokeWhatsAppSender(
      String tenantId, String senderId) async {
    final res = await _dio.post(
        '/v1/admin/tenants/$tenantId/whatsapp-senders/$senderId/revoke');
    return WhatsAppSenderResponse.fromJson(_asMap(res.data));
  }

  /// Move a live sender to another workspace, keeping its WhatsApp
  /// registration. Platform admins only. [dryRun] reports the effects
  /// without changing anything.
  Future<WhatsAppSenderTransferResponse> transferWhatsAppSender(
      String tenantId, String senderId, WhatsAppSenderTransferRequest body,
      {bool dryRun = false}) async {
    final res = await _dio.post(
        '/v1/admin/tenants/$tenantId/whatsapp-senders/$senderId/transfer',
        queryParameters: {'dryRun': dryRun},
        data: body.toJson());
    return WhatsAppSenderTransferResponse.fromJson(_asMap(res.data));
  }

  /// Retry pointing WhatsApp at a transferred sender. Platform admins only.
  Future<WhatsAppSenderResponse> repointWhatsAppSenderWebhooks(
      String tenantId, String senderId) async {
    final res = await _dio.post(
        '/v1/admin/tenants/$tenantId/whatsapp-senders/$senderId/repoint-webhooks');
    return WhatsAppSenderResponse.fromJson(_asMap(res.data));
  }

  /// Probe whether sender registration is enabled on this instance. Used
  /// by the admin UI to render an actionable banner BEFORE the operator
  /// tries to submit a sender for review.
  Future<bool> isWhatsAppSubmitEnabled(String tenantId) async {
    final res =
        await _dio.get('/v1/admin/tenants/$tenantId/whatsapp/readiness');
    final map = _asMap(res.data);
    return map['submitsEnabled'] == true;
  }

  // ---- WhatsApp embedded signup (self-service onboarding) -------------

  /// Meta Embedded Signup configuration for the "Connect WhatsApp" wizard.
  /// `enabled == false` when the platform hasn't configured the feature.
  Future<EmbeddedSignupConfig> getWhatsAppEmbeddedSignupConfig(
      String tenantId) async {
    final res = await _dio
        .get('/v1/admin/tenants/$tenantId/whatsapp/embedded-signup/config');
    return EmbeddedSignupConfig.fromJson(_asMap(res.data));
  }

  /// Submit an embedded-signup onboarding. Idempotent server-side — repeats
  /// resume the same attempt instead of provisioning duplicates.
  Future<OnboardingResponse> startWhatsAppEmbeddedSignup(
      String tenantId, OnboardingRequest body) async {
    final res = await _dio.post(
        '/v1/admin/tenants/$tenantId/whatsapp/embedded-signup',
        data: body.toJson());
    return OnboardingResponse.fromJson(_asMap(res.data));
  }

  Future<List<OnboardingAttemptView>> listWhatsAppOnboardingAttempts(
      String tenantId) async {
    final res = await _dio
        .get('/v1/admin/tenants/$tenantId/whatsapp/embedded-signup/attempts');
    return (res.data as List)
        .map((e) => OnboardingAttemptView.fromJson(_asMap(e)))
        .toList();
  }

  // ---- SMS A2P 10DLC: brand ------------------------------------------

  /// The tenant's SMS brand, or null if none has been created yet (404).
  Future<SmsBrandResponse?> getSmsBrand(String tenantId) async {
    try {
      final res = await _dio.get('/v1/admin/tenants/$tenantId/sms/brand');
      return SmsBrandResponse.fromJson(_asMap(res.data));
    } on DioException catch (e) {
      if (e.response?.statusCode == 404) return null;
      rethrow;
    }
  }

  Future<SmsBrandResponse> createSmsBrand(
      String tenantId, SmsBrandRequest body) async {
    final res = await _dio.post('/v1/admin/tenants/$tenantId/sms/brand',
        data: body.toJson());
    return SmsBrandResponse.fromJson(_asMap(res.data));
  }

  Future<SmsBrandResponse> updateSmsBrand(
      String tenantId, SmsBrandRequest body) async {
    final res = await _dio.put('/v1/admin/tenants/$tenantId/sms/brand',
        data: body.toJson());
    return SmsBrandResponse.fromJson(_asMap(res.data));
  }

  Future<SmsBrandResponse> submitSmsBrand(String tenantId) async {
    final res =
        await _dio.post('/v1/admin/tenants/$tenantId/sms/brand/submit');
    return SmsBrandResponse.fromJson(_asMap(res.data));
  }

  // ---- SMS A2P 10DLC: campaigns --------------------------------------

  Future<List<SmsCampaignResponse>> listSmsCampaigns(String tenantId) async {
    final res = await _dio.get('/v1/admin/tenants/$tenantId/sms/campaigns');
    return (res.data as List)
        .map((e) => SmsCampaignResponse.fromJson(_asMap(e)))
        .toList();
  }

  Future<SmsCampaignResponse> createSmsCampaign(
      String tenantId, SmsCampaignRequest body) async {
    final res = await _dio.post('/v1/admin/tenants/$tenantId/sms/campaigns',
        data: body.toJson());
    return SmsCampaignResponse.fromJson(_asMap(res.data));
  }

  Future<SmsCampaignResponse> submitSmsCampaign(
      String tenantId, String campaignId) async {
    final res = await _dio
        .post('/v1/admin/tenants/$tenantId/sms/campaigns/$campaignId/submit');
    return SmsCampaignResponse.fromJson(_asMap(res.data));
  }

  // ---- SMS senders ---------------------------------------------------

  Future<List<SmsSenderResponse>> listSmsSenders(String tenantId) async {
    final res = await _dio.get('/v1/admin/tenants/$tenantId/sms/senders');
    return (res.data as List)
        .map((e) => SmsSenderResponse.fromJson(_asMap(e)))
        .toList();
  }

  Future<SmsSenderResponse> createSmsSender(
      String tenantId, SmsSenderRequest body) async {
    final res = await _dio.post('/v1/admin/tenants/$tenantId/sms/senders',
        data: body.toJson());
    return SmsSenderResponse.fromJson(_asMap(res.data));
  }

  Future<SmsSenderResponse> submitSmsSender(
      String tenantId, String senderId) async {
    final res = await _dio
        .post('/v1/admin/tenants/$tenantId/sms/senders/$senderId/submit');
    return SmsSenderResponse.fromJson(_asMap(res.data));
  }

  Future<SmsSenderResponse> syncSmsSender(
      String tenantId, String senderId) async {
    final res = await _dio
        .post('/v1/admin/tenants/$tenantId/sms/senders/$senderId/sync');
    return SmsSenderResponse.fromJson(_asMap(res.data));
  }

  // ---- Channel bindings ----------------------------------------------

  Future<List<ChannelBindingResponse>> listChannelBindings(String tenantId) async {
    final res = await _dio.get('/v1/admin/tenants/$tenantId/channel-bindings');
    return (res.data as List)
        .map((e) => ChannelBindingResponse.fromJson(_asMap(e)))
        .toList();
  }

  Future<ChannelBindingResponse> createChannelBinding(
      String tenantId, ChannelBindingRequest body) async {
    final res = await _dio.post(
        '/v1/admin/tenants/$tenantId/channel-bindings',
        data: body.toJson());
    return ChannelBindingResponse.fromJson(_asMap(res.data));
  }

  Future<ChannelBindingResponse> updateChannelBinding(
      String tenantId, int id, ChannelBindingUpdate body) async {
    final res = await _dio.put(
        '/v1/admin/tenants/$tenantId/channel-bindings/$id',
        data: body.toJson());
    return ChannelBindingResponse.fromJson(_asMap(res.data));
  }

  Future<void> deleteChannelBinding(String tenantId, int id) async {
    await _dio.delete('/v1/admin/tenants/$tenantId/channel-bindings/$id');
  }

  // ---- Auth resolvers (channel-auth-resolver) ------------------------

  Future<AuthResolverListResponse> listAuthResolvers(String tenantId) async {
    final res = await _dio.get('/v1/admin/tenants/$tenantId/auth-resolvers');
    return AuthResolverListResponse.fromJson(_asMap(res.data));
  }

  Future<AuthResolverResponse> getAuthResolver(
      String tenantId, String name) async {
    final res = await _dio
        .get('/v1/admin/tenants/$tenantId/auth-resolvers/${Uri.encodeComponent(name)}');
    return AuthResolverResponse.fromJson(_asMap(res.data));
  }

  Future<AuthResolverResponse> createAuthResolver(
      String tenantId, AuthResolverRequest body) async {
    final res = await _dio.post(
        '/v1/admin/tenants/$tenantId/auth-resolvers',
        data: body.toJson());
    return AuthResolverResponse.fromJson(_asMap(res.data));
  }

  Future<AuthResolverResponse> updateAuthResolver(
      String tenantId, String name, AuthResolverRequest body) async {
    final res = await _dio.put(
        '/v1/admin/tenants/$tenantId/auth-resolvers/${Uri.encodeComponent(name)}',
        data: body.toJson());
    return AuthResolverResponse.fromJson(_asMap(res.data));
  }

  Future<AuthResolverDeleteResponse> deleteAuthResolver(
      String tenantId, String name) async {
    final res = await _dio.delete(
        '/v1/admin/tenants/$tenantId/auth-resolvers/${Uri.encodeComponent(name)}');
    return AuthResolverDeleteResponse.fromJson(_asMap(res.data));
  }

  Future<AuthResolverProbeResult> testAuthResolver(
      String tenantId, String name, AuthResolverProbeInput input) async {
    final res = await _dio.post(
        '/v1/admin/tenants/$tenantId/auth-resolvers/${Uri.encodeComponent(name)}/test',
        data: input.toJson());
    return AuthResolverProbeResult.fromJson(_asMap(res.data));
  }

  Future<AuthResolverInvalidateResponse> invalidateAuthResolverCache(
      String tenantId, AuthResolverInvalidateRequest body) async {
    final res = await _dio.post(
        '/v1/admin/tenants/$tenantId/auth-resolvers/invalidate-cache',
        data: body.toJson());
    return AuthResolverInvalidateResponse.fromJson(_asMap(res.data));
  }

  // ---- Tenant secondary comms (W-A9) ---------------------------------

  Future<NetworkSettings> getNetworkSettings(String tenantId) async {
    final res = await _dio.get('/v1/admin/tenants/$tenantId/network-settings');
    return NetworkSettings.fromJson(_asMap(res.data));
  }

  Future<NetworkSettings> updateNetworkSettings(
      String tenantId, NetworkSettings body) async {
    final res = await _dio.put(
        '/v1/admin/tenants/$tenantId/network-settings',
        data: body.toJson());
    return NetworkSettings.fromJson(_asMap(res.data));
  }

  Future<SecondaryComms> getSecondaryComms(String tenantId) async {
    final res = await _dio.get('/v1/admin/tenants/$tenantId/secondary-comms');
    return SecondaryComms.fromJson(_asMap(res.data));
  }

  Future<SecondaryComms> updateSecondaryComms(
      String tenantId, SecondaryComms body) async {
    final res = await _dio.put(
        '/v1/admin/tenants/$tenantId/secondary-comms',
        data: body.toJson());
    return SecondaryComms.fromJson(_asMap(res.data));
  }

  // ---- WhatsApp templates --------------------------------------------

  Future<List<WhatsAppTemplateResponse>> listWhatsAppTemplates(
      String tenantId, String senderId) async {
    final res = await _dio.get(
        '/v1/admin/tenants/$tenantId/whatsapp-senders/$senderId/templates');
    return (res.data as List)
        .map((e) => WhatsAppTemplateResponse.fromJson(_asMap(e)))
        .toList();
  }

  Future<WhatsAppTemplateResponse> createWhatsAppTemplate(
      String tenantId, String senderId, WhatsAppTemplateRequest body) async {
    final res = await _dio.post(
        '/v1/admin/tenants/$tenantId/whatsapp-senders/$senderId/templates',
        data: body.toJson());
    return WhatsAppTemplateResponse.fromJson(_asMap(res.data));
  }

  Future<WhatsAppTemplateResponse> updateWhatsAppTemplate(
      String tenantId, String senderId, int templateId,
      WhatsAppTemplateUpdate body) async {
    final res = await _dio.put(
        '/v1/admin/tenants/$tenantId/whatsapp-senders/$senderId/templates/$templateId',
        data: body.toJson());
    return WhatsAppTemplateResponse.fromJson(_asMap(res.data));
  }

  Future<WhatsAppTemplateResponse> submitWhatsAppTemplate(
      String tenantId, String senderId, int templateId) async {
    final res = await _dio.post(
        '/v1/admin/tenants/$tenantId/whatsapp-senders/$senderId/templates/$templateId/submit');
    return WhatsAppTemplateResponse.fromJson(_asMap(res.data));
  }

  Future<void> deleteWhatsAppTemplate(
      String tenantId, String senderId, int templateId) async {
    await _dio.delete(
        '/v1/admin/tenants/$tenantId/whatsapp-senders/$senderId/templates/$templateId');
  }

  /// Hides ([hidden]) or shows again the templates in the console lists;
  /// sending is unaffected.
  Future<List<WhatsAppTemplateResponse>> setWhatsAppTemplatesHidden(
      String tenantId, String senderId, List<int> templateIds,
      {required bool hidden}) async {
    final res = await _dio.post(
        '/v1/admin/tenants/$tenantId/whatsapp-senders/$senderId/templates/${hidden ? 'hide' : 'unhide'}',
        data: {'templateIds': templateIds});
    return (res.data as List)
        .map((e) => WhatsAppTemplateResponse.fromJson(_asMap(e)))
        .toList();
  }

  // ---- WhatsApp account templates (platform admins) ------------------

  Future<List<WhatsAppAccountResponse>> listWhatsAppAccounts() async {
    final res = await _dio.get('/v1/admin/whatsapp-accounts');
    return (res.data as List)
        .map((e) => WhatsAppAccountResponse.fromJson(_asMap(e)))
        .toList();
  }

  Future<List<WhatsAppAccountTemplate>> listWhatsAppAccountTemplates(
      String accountKey) async {
    final res =
        await _dio.get('/v1/admin/whatsapp-accounts/$accountKey/templates');
    return (res.data as List)
        .map((e) => WhatsAppAccountTemplate.fromJson(_asMap(e)))
        .toList();
  }

  Future<List<WhatsAppAccountTemplate>> syncWhatsAppAccountTemplates(
      String accountKey) async {
    final res = await _dio
        .post('/v1/admin/whatsapp-accounts/$accountKey/templates/sync');
    return (res.data as List)
        .map((e) => WhatsAppAccountTemplate.fromJson(_asMap(e)))
        .toList();
  }

  Future<WhatsAppAccountTemplate> declareWhatsAppAccountTemplate(
      String accountKey, WhatsAppTemplateDeclaration body) async {
    final res = await _dio.put(
        '/v1/admin/whatsapp-accounts/$accountKey/templates/by-name/${body.name}',
        data: body.toJson());
    return WhatsAppAccountTemplate.fromJson(_asMap(res.data));
  }

  Future<WhatsAppAccountTemplate> submitWhatsAppAccountTemplate(
      String accountKey, int templateId) async {
    final res = await _dio.post(
        '/v1/admin/whatsapp-accounts/$accountKey/templates/$templateId/submit');
    return WhatsAppAccountTemplate.fromJson(_asMap(res.data));
  }

  Future<List<WhatsAppAccountTemplate>> setWhatsAppAccountTemplatesHidden(
      String accountKey, List<int> templateIds,
      {required bool hidden}) async {
    final res = await _dio.post(
        '/v1/admin/whatsapp-accounts/$accountKey/templates/${hidden ? 'hide' : 'unhide'}',
        data: {'templateIds': templateIds});
    return (res.data as List)
        .map((e) => WhatsAppAccountTemplate.fromJson(_asMap(e)))
        .toList();
  }

  Future<void> deleteWhatsAppAccountTemplate(
      String accountKey, int templateId) async {
    await _dio
        .delete('/v1/admin/whatsapp-accounts/$accountKey/templates/$templateId');
  }

  /// Every workspace in [tenantIds] gets every template in [templateIds];
  /// all or nothing.
  Future<List<WhatsAppAccountTemplate>> shareWhatsAppTemplates(
      String accountKey, List<int> templateIds, List<String> tenantIds) async {
    final res = await _dio.post(
        '/v1/admin/whatsapp-accounts/$accountKey/templates/share',
        data: {'templateIds': templateIds, 'tenantIds': tenantIds});
    return (res.data as List)
        .map((e) => WhatsAppAccountTemplate.fromJson(_asMap(e)))
        .toList();
  }

  Future<WhatsAppAccountTemplate> unshareWhatsAppTemplate(
      String accountKey, int templateId, String tenantId) async {
    final res = await _dio.delete(
        '/v1/admin/whatsapp-accounts/$accountKey/templates/$templateId/holders/$tenantId');
    return WhatsAppAccountTemplate.fromJson(_asMap(res.data));
  }

  // ---- WhatsApp opt-outs ---------------------------------------------

  Future<List<ChannelOptOutResponse>> listOptOuts(
      String tenantId, String senderId) async {
    final res = await _dio.get(
        '/v1/admin/tenants/$tenantId/channel-opt-outs',
        queryParameters: {'senderId': senderId});
    return (res.data as List)
        .map((e) => ChannelOptOutResponse.fromJson(_asMap(e)))
        .toList();
  }

  Future<void> removeOptOut(String tenantId, String senderId, String phone) async {
    await _dio.delete(
        '/v1/admin/tenants/$tenantId/channel-opt-outs/$senderId/${Uri.encodeComponent(phone)}');
  }

  // ---- Chat entry points ---------------------------------------------

  Future<List<ChatEntryPointResponse>> listChatEntryPoints(String tenantId) async {
    final res = await _dio.get('/v1/admin/tenants/$tenantId/chat-entry-points');
    return (res.data as List)
        .map((e) => ChatEntryPointResponse.fromJson(_asMap(e)))
        .toList();
  }

  Future<ChatEntryPointResponse> createChatEntryPoint(
      String tenantId, ChatEntryPointRequest body) async {
    final res = await _dio.post('/v1/admin/tenants/$tenantId/chat-entry-points',
        data: body.toJson());
    return ChatEntryPointResponse.fromJson(_asMap(res.data));
  }

  Future<ChatEntryPointResponse> updateChatEntryPoint(
      String tenantId, String id, ChatEntryPointRequest body) async {
    final res = await _dio.put('/v1/admin/tenants/$tenantId/chat-entry-points/$id',
        data: body.toUpdateJson());
    return ChatEntryPointResponse.fromJson(_asMap(res.data));
  }

  Future<ChatEntryPointResponse> _chatLifecycle(
      String tenantId, String id, String action) async {
    final res = await _dio.post('/v1/admin/tenants/$tenantId/chat-entry-points/$id/$action');
    return ChatEntryPointResponse.fromJson(_asMap(res.data));
  }

  Future<ChatEntryPointResponse> publishChatEntryPoint(String tenantId, String id) =>
      _chatLifecycle(tenantId, id, 'publish');

  Future<ChatEntryPointResponse> pauseChatEntryPoint(String tenantId, String id) =>
      _chatLifecycle(tenantId, id, 'pause');

  Future<ChatEntryPointResponse> resumeChatEntryPoint(String tenantId, String id) =>
      _chatLifecycle(tenantId, id, 'resume');

  Future<ChatEntryPointResponse> rotateChatEntryPointHandle(String tenantId, String id) =>
      _chatLifecycle(tenantId, id, 'rotate-handle');

  Future<ChatEntryPointResponse> deactivateChatEntryPoint(String tenantId, String id) async {
    final res = await _dio.delete('/v1/admin/tenants/$tenantId/chat-entry-points/$id');
    return ChatEntryPointResponse.fromJson(_asMap(res.data));
  }

  // ---- System senders (system-messaging-design.md §14) ----------------

  Future<List<SystemSenderResponse>> listSystemSenders(String tenantId) async {
    final res = await _dio.get('/v1/admin/tenants/$tenantId/system-senders');
    return (res.data as List).map((e) => SystemSenderResponse.fromJson(_asMap(e))).toList();
  }

  Future<SystemSenderResponse> createSystemSender(
      String tenantId, SystemSenderRequest body) async {
    final res = await _dio.post('/v1/admin/tenants/$tenantId/system-senders', data: body.toJson());
    return SystemSenderResponse.fromJson(_asMap(res.data));
  }

  Future<SystemSenderResponse> updateSystemSender(
      String tenantId, String senderId, SystemSenderRequest body) async {
    final res = await _dio.put('/v1/admin/tenants/$tenantId/system-senders/$senderId',
        data: body.toJson());
    return SystemSenderResponse.fromJson(_asMap(res.data));
  }

  Future<void> deleteSystemSender(String tenantId, String senderId) async {
    await _dio.delete('/v1/admin/tenants/$tenantId/system-senders/$senderId');
  }

  Future<List<SystemInboundLogEntry>> systemSenderLog(String tenantId, String senderId) async {
    final res = await _dio.get('/v1/admin/tenants/$tenantId/system-senders/$senderId/messages');
    return (res.data as List).map((e) => SystemInboundLogEntry.fromJson(_asMap(e))).toList();
  }

  /// Playground: post as if the linked system did (exercises ingest → delivery).
  Future<Map<String, dynamic>> simulateSystemMessage(
      String tenantId, String senderId, Map<String, dynamic> body) async {
    final res = await _dio.post(
        '/v1/admin/tenants/$tenantId/system-senders/$senderId/simulate',
        data: body);
    return _asMap(res.data);
  }

  /// Upload a logo (base64) and get back the platform-hosted URL to store on
  /// the entry point.
  Future<String> uploadChatLogo(String tenantId, String contentType, String dataBase64) async {
    final res = await _dio.post(
      '/v1/admin/tenants/$tenantId/chat-entry-points/logos',
      data: {'contentType': contentType, 'dataBase64': dataBase64},
    );
    return (_asMap(res.data)['url'] ?? '') as String;
  }

  // ---- Session Start Flows ---------------------------------------------

  Future<List<StartFlowResponse>> listStartFlows(String tenantId) async {
    final res = await _dio.get('/v1/admin/tenants/$tenantId/start-flows');
    return (res.data as List).map((e) => StartFlowResponse.fromJson(_asMap(e))).toList();
  }

  Future<StartFlowResponse> getStartFlow(String tenantId, String id) async {
    final res = await _dio.get('/v1/admin/tenants/$tenantId/start-flows/$id');
    return StartFlowResponse.fromJson(_asMap(res.data));
  }

  Future<StartFlowResponse> createStartFlow(String tenantId, StartFlowRequest body) async {
    final res = await _dio.post('/v1/admin/tenants/$tenantId/start-flows', data: body.toJson());
    return StartFlowResponse.fromJson(_asMap(res.data));
  }

  Future<StartFlowResponse> updateStartFlow(String tenantId, String id, StartFlowRequest body) async {
    final res = await _dio.put('/v1/admin/tenants/$tenantId/start-flows/$id', data: body.toUpdateJson());
    return StartFlowResponse.fromJson(_asMap(res.data));
  }

  Future<StartFlowResponse> publishStartFlow(String tenantId, String id) async {
    final res = await _dio.post('/v1/admin/tenants/$tenantId/start-flows/$id/publish');
    return StartFlowResponse.fromJson(_asMap(res.data));
  }

  // ---- Receipt templates (chat-experience-design.md §7.4) --------------

  Future<List<ReceiptTemplateResponse>> listReceiptTemplates(
      String tenantId) async {
    final res = await _dio.get('/v1/admin/tenants/$tenantId/receipt-templates');
    return (res.data as List)
        .map((e) => ReceiptTemplateResponse.fromJson(_asMap(e)))
        .toList();
  }

  Future<ReceiptTemplateResponse> getReceiptTemplate(
      String tenantId, String id) async {
    final res =
        await _dio.get('/v1/admin/tenants/$tenantId/receipt-templates/$id');
    return ReceiptTemplateResponse.fromJson(_asMap(res.data));
  }

  Future<ReceiptTemplateResponse> createReceiptTemplate(
      String tenantId, ReceiptTemplateRequest body) async {
    final res = await _dio.post(
        '/v1/admin/tenants/$tenantId/receipt-templates',
        data: body.toJson());
    return ReceiptTemplateResponse.fromJson(_asMap(res.data));
  }

  Future<ReceiptTemplateResponse> updateReceiptTemplate(
      String tenantId, String id, ReceiptTemplateRequest body) async {
    final res = await _dio.put(
        '/v1/admin/tenants/$tenantId/receipt-templates/$id',
        data: body.toJson());
    return ReceiptTemplateResponse.fromJson(_asMap(res.data));
  }

  Future<void> deleteReceiptTemplate(String tenantId, String id) async {
    await _dio.delete('/v1/admin/tenants/$tenantId/receipt-templates/$id');
  }

  // ---- Forum (entry-point community feed) ------------------------------

  Future<ForumSettingsResponse> getForumSettings(
      String tenantId, String entryPointId) async {
    final res = await _dio.get(
        '/v1/admin/tenants/$tenantId/forum/entry-points/$entryPointId/settings');
    return ForumSettingsResponse.fromJson(_asMap(res.data));
  }

  Future<ForumSettingsResponse> updateForumSettings(
      String tenantId, String entryPointId,
      {required bool enabled}) async {
    final res = await _dio.put(
        '/v1/admin/tenants/$tenantId/forum/entry-points/$entryPointId/settings',
        data: {'enabled': enabled});
    return ForumSettingsResponse.fromJson(_asMap(res.data));
  }

  Future<List<AdminForumPostView>> listForumPosts(
      String tenantId, String entryPointId) async {
    final res = await _dio.get(
        '/v1/admin/tenants/$tenantId/forum/entry-points/$entryPointId/posts');
    return (res.data as List)
        .map((e) => AdminForumPostView.fromJson(_asMap(e)))
        .toList();
  }

  Future<AdminForumPostView> createForumPost(
      String tenantId, String entryPointId, AdminForumPostRequest body) async {
    final res = await _dio.post(
        '/v1/admin/tenants/$tenantId/forum/entry-points/$entryPointId/posts',
        data: body.toJson());
    return AdminForumPostView.fromJson(_asMap(res.data));
  }

  /// Moderate a post: hide/unhide (resolves its open reports server-side),
  /// pin/unpin, or soft-delete.
  Future<void> moderateForumPost(
      String tenantId, String postId, ForumModerationRequest body) async {
    await _dio.patch('/v1/admin/tenants/$tenantId/forum/posts/$postId',
        data: body.toJson());
  }

  Future<List<ForumReportView>> listForumReports(String tenantId) async {
    final res = await _dio.get('/v1/admin/tenants/$tenantId/forum/reports');
    final list = (_asMap(res.data)['reports'] as List? ?? const []);
    return list.map((e) => ForumReportView.fromJson(_asMap(e))).toList();
  }

  static Map<String, dynamic> _asMap(dynamic raw) => Map<String, dynamic>.from(raw);
}

final adminApiProvider = FutureProvider<AdminApi>((ref) async {
  final dio = await ref.watch(dioProvider.future);
  return AdminApi(dio);
});
