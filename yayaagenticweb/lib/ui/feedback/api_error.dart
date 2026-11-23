import 'package:dio/dio.dart';

/// The admin API's error body (`{error, message, fieldErrors?, currentVersion?}`)
/// lifted out of whatever was thrown.
class ApiErrorInfo {
  const ApiErrorInfo({
    required this.message,
    this.code,
    this.status,
    this.fieldErrors = const {},
    this.currentVersion,
    this.path,
  });

  final String message;
  final String? code;
  final int? status;
  final Map<String, String> fieldErrors;
  final int? currentVersion;
  final String? path;

  bool get isVersionConflict => code == 'version_conflict';

  /// One line for a bug report: what failed, where, and why.
  String get details => [
        if (status != null) 'HTTP $status',
        if (path != null) path,
        if (code != null) code,
        message,
      ].join(' · ');

  factory ApiErrorInfo.from(Object e) {
    if (e is ApiErrorInfo) return e;
    if (e is DioException) {
      final data = e.response?.data;
      final status = e.response?.statusCode;
      final path = e.requestOptions.path;
      if (data is Map) {
        final fe = data['fieldErrors'];
        return ApiErrorInfo(
          message: data['message'] is String ? data['message'] as String : (e.message ?? 'request failed'),
          code: data['error'] is String ? data['error'] as String : null,
          status: status,
          fieldErrors: fe is Map ? fe.map((k, v) => MapEntry('$k', '$v')) : const {},
          currentVersion: data['currentVersion'] is int ? data['currentVersion'] as int : null,
          path: path,
        );
      }
      return ApiErrorInfo(
        message: switch (e.type) {
          DioExceptionType.connectionError || DioExceptionType.connectionTimeout =>
            'Can’t reach the server. Check your connection and try again.',
          DioExceptionType.receiveTimeout => 'The server took too long to answer.',
          _ => e.message ?? 'request failed',
        },
        status: status,
        path: path,
      );
    }
    return ApiErrorInfo(message: e.toString());
  }
}
