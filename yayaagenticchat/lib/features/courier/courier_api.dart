import 'package:dio/dio.dart';

import '../../config.dart';
import 'courier_models.dart';

class CourierApiException implements Exception {
  final int? status;
  final String message;
  CourierApiException(this.status, this.message);

  bool get unauthorized => status == 401;

  @override
  String toString() => 'CourierApiException($status): $message';
}

/// Thin client over the yaya-sales courier surface `/v1/sales/delivery/*`
/// (yaya-sales-design.md §10.4). Talks to yaya-sales DIRECTLY — never through
/// yaya-agentic — with the yaya-sales-issued courier bearer token. Register and
/// verify are the only public calls (invite token and OTP are their gates).
class CourierApi {
  CourierApi({Dio? dio})
      : _dio = dio ??
            Dio(BaseOptions(
              baseUrl: AppConfig.salesApiBase,
              connectTimeout: const Duration(seconds: 15),
              receiveTimeout: const Duration(seconds: 30),
            ));

  final Dio _dio;

  Options _auth(String token) => Options(headers: {'Authorization': 'Bearer $token'});

  Future<CourierRegistration> register({
    required String inviteToken,
    required String legalName,
    required Map<String, String?> legalAddress,
    required String phone,
    required Map<String, String?> vehicle,
  }) async {
    try {
      final r = await _dio.post('/v1/sales/delivery/agents/register', data: {
        'inviteToken': inviteToken,
        'legalName': legalName,
        'legalAddress': legalAddress,
        'phone': phone,
        'vehicle': vehicle,
      });
      return CourierRegistration.fromJson(_map(r.data));
    } on DioException catch (e) {
      throw _err(e);
    }
  }

  /// OTP check → the registration-scoped courier token, shown once.
  Future<({String agentId, String token})> verify(String phone, String otp) async {
    try {
      final r = await _dio.post('/v1/sales/delivery/agents/verify',
          data: {'phone': phone, 'otp': otp});
      final j = _map(r.data);
      return (
        agentId: (j['agentId'] ?? '').toString(),
        token: (j['courierToken'] ?? '').toString(),
      );
    } on DioException catch (e) {
      throw _err(e);
    }
  }

  /// Encrypted-at-rest KYC uploads: `{kind, contentBase64}` per document.
  Future<CourierProfile> submitDocuments(
      String token, String agentId, List<Map<String, String>> documents) async {
    try {
      final r = await _dio.post('/v1/sales/delivery/agents/$agentId/documents',
          data: {'documents': documents}, options: _auth(token));
      final j = _map(r.data);
      // The documents response is a subset of /me — keep the caller on one shape.
      return CourierProfile.fromJson({
        'agentId': j['agentId'],
        'legalName': '',
        'status': j['status'],
        'phoneVerified': true,
        'documents': j['documents'] ?? const [],
      });
    } on DioException catch (e) {
      throw _err(e);
    }
  }

  Future<CourierProfile> me(String token) async {
    try {
      final r = await _dio.get('/v1/sales/delivery/me', options: _auth(token));
      return CourierProfile.fromJson(_map(r.data));
    } on DioException catch (e) {
      throw _err(e);
    }
  }

  Future<List<CourierAssignment>> assignments(String token) async {
    try {
      final r = await _dio.get('/v1/sales/delivery/assignments', options: _auth(token));
      return [
        for (final a in (r.data as List? ?? const []))
          CourierAssignment.fromJson(Map<String, dynamic>.from(a as Map)),
      ];
    } on DioException catch (e) {
      throw _err(e);
    }
  }

  Future<CourierAssignment> accept(String token, String assignmentId) async {
    try {
      final r = await _dio.post('/v1/sales/delivery/assignments/$assignmentId/accept',
          options: _auth(token));
      return CourierAssignment.fromJson(_map(r.data));
    } on DioException catch (e) {
      throw _err(e);
    }
  }

  /// OUT_FOR_DELIVERY | DELIVERED | FAILED, with the v1 proof map (notes/OTP).
  Future<CourierAssignment> updateStatus(String token, String assignmentId, String status,
      {Map<String, Object>? proof}) async {
    try {
      final r = await _dio.put('/v1/sales/delivery/assignments/$assignmentId/status',
          data: {'status': status, if (proof != null && proof.isNotEmpty) 'proof': proof},
          options: _auth(token));
      return CourierAssignment.fromJson(_map(r.data));
    } on DioException catch (e) {
      throw _err(e);
    }
  }

  /// The doorstep capture — settles the COD tender, flips the order PAID.
  Future<CodCollectionResult> collectCod(
      String token, String assignmentId, int amountMinor) async {
    try {
      final r = await _dio.post('/v1/sales/delivery/assignments/$assignmentId/collect-cod',
          data: {'amountMinor': amountMinor}, options: _auth(token));
      return CodCollectionResult.fromJson(_map(r.data));
    } on DioException catch (e) {
      throw _err(e);
    }
  }

  static Map<String, dynamic> _map(dynamic data) =>
      data is Map ? Map<String, dynamic>.from(data) : <String, dynamic>{};

  static CourierApiException _err(DioException e) {
    final status = e.response?.statusCode;
    final data = e.response?.data;
    String message = 'The delivery service is unreachable. Try again.';
    if (data is Map) {
      final m = data['message'] ?? data['error'] ?? data['detail'];
      if (m is String && m.isNotEmpty) message = m;
    } else if (status == 401) {
      message = 'Your courier access token is no longer valid.';
    } else if (status != null) {
      message = 'Request failed ($status).';
    }
    return CourierApiException(status, message);
  }
}
