import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../models/admin/audit.dart';
import '../models/admin/auth_binding.dart';
import '../models/admin/capability.dart';
import '../models/admin/clone.dart';
import '../models/admin/knowledge_source.dart';
import '../models/admin/operator_auth_strategies.dart';
import '../models/admin/profile.dart';
import '../models/admin/recording_strategy.dart';
import '../models/admin/tenant.dart';
import '../models/admin/tool.dart';
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
    final res = await _dio.post('/v1/admin/profiles', data: req.toJson());
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

  static Map<String, dynamic> _asMap(dynamic raw) => Map<String, dynamic>.from(raw);
}

final adminApiProvider = FutureProvider<AdminApi>((ref) async {
  final dio = await ref.watch(dioProvider.future);
  return AdminApi(dio);
});
