import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../models/admin/audit.dart';
import '../models/admin/auth_binding.dart';
import '../models/admin/capability.dart';
import '../models/admin/knowledge_source.dart';
import '../models/admin/profile.dart';
import '../models/admin/recording_strategy.dart';
import '../models/admin/tool.dart';
import 'api_client.dart';

/// Typed wrapper over /v1/admin/*. All methods unwrap the {error,message}
/// shape on failure into the message string (Dio errors keep the original
/// response on `error.response`).
class AdminApi {
  AdminApi(this._dio);
  final Dio _dio;

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
