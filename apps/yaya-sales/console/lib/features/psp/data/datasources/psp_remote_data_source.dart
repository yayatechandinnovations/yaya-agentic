import 'package:dio/dio.dart';

class PspRemoteDataSource {
  PspRemoteDataSource(this._dio);

  final Dio _dio;

  Future<List<Map<String, dynamic>>> status() async {
    final res = await _dio.get<List<dynamic>>('/v1/sales/admin/psp');
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<Map<String, dynamic>> onboardingLink(String provider, String returnUrl) async {
    final res = await _dio.post<Map<String, dynamic>>(
      '/v1/sales/admin/psp/$provider/onboarding-link',
      data: {'returnUrl': returnUrl},
    );
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> reconcileReturn(
      String provider, Map<String, dynamic> params) async {
    final res = await _dio.post<Map<String, dynamic>>(
      '/v1/sales/admin/psp/$provider/return',
      data: {'params': params},
    );
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> disconnect(String provider) async {
    final res = await _dio.delete<Map<String, dynamic>>('/v1/sales/admin/psp/$provider');
    return res.data ?? const {};
  }
}
