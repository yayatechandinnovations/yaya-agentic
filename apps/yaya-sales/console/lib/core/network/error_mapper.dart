import 'package:dio/dio.dart';

import '../error/failure.dart';

/// One place where transport errors become domain [Failure]s, shared by every
/// repository implementation.
Failure mapDioError(DioException e) {
  final code = e.response?.statusCode;
  if (code == 401) {
    return const AuthFailure('session expired — please sign in again');
  }
  if (e.type == DioExceptionType.connectionError ||
      e.type == DioExceptionType.connectionTimeout) {
    return const NetworkFailure('cannot reach yaya-sales');
  }
  final data = e.response?.data;
  if (data is Map && data['message'] != null) {
    return ServerFailure('${data['message']}');
  }
  return ServerFailure('request failed: ${code ?? e.message}');
}
