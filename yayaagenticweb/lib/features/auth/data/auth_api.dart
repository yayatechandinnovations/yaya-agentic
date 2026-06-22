import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/api_client.dart';
import '../models/operator.dart';

/// Thin wrapper over /v1/auth/*. Cookies are handled by the browser; the
/// AuthApi never sees the YAYA_SESSION value (which is HttpOnly).
///
/// Errors are normalised into [AuthApiException] carrying the backend's
/// user-safe message ("Invalid username or password") so the login screen
/// can render it verbatim.
class AuthApi {
  AuthApi(this._dio);
  final Dio _dio;

  Future<Operator> login({required String username, required String password}) async {
    try {
      final res = await _dio.post('/v1/auth/login', data: {
        'username': username,
        'password': password,
      });
      return Operator.fromJson(_asMap(res.data));
    } on DioException catch (e) {
      throw _toAuthException(e);
    }
  }

  Future<Operator?> me() async {
    try {
      final res = await _dio.get('/v1/auth/me');
      return Operator.fromJson(_asMap(res.data));
    } on DioException catch (e) {
      if (e.response?.statusCode == 401) return null;
      throw _toAuthException(e);
    }
  }

  Future<void> logout() async {
    try {
      await _dio.post('/v1/auth/logout');
    } on DioException catch (e) {
      // Idempotent — even a 401 from logout means the session is already gone.
      if (e.response?.statusCode == 401) return;
      throw _toAuthException(e);
    }
  }

  // -------------------------------------------------------------------
  // User-accounts surface (M5a; only routed when the backend feature
  // flag is on — endpoints 404 otherwise, which we surface as a generic
  // "feature unavailable" message).
  // -------------------------------------------------------------------

  /// Enumeration-safe — server always responds 202. Throws only on a true
  /// transport / 5xx error, never on "email already taken."
  Future<void> signup({
    required String email,
    required String displayName,
    required String password,
  }) async {
    try {
      await _dio.post('/v1/auth/signup', data: {
        'email': email,
        'displayName': displayName,
        'password': password,
      });
    } on DioException catch (e) {
      throw _toAuthException(e);
    }
  }

  /// Consume a verification token. Returns the default workspace code on
  /// success so the SPA can route the user straight there post-login.
  Future<VerifyResult> verifyEmail(String token) async {
    try {
      final res = await _dio.get('/v1/auth/verify',
          queryParameters: {'token': token});
      final body = _asMap(res.data);
      return VerifyResult(
        userId: body['userId'] as String?,
        defaultWorkspaceCode: body['defaultWorkspaceCode'] as String?,
        invitationsRedeemed: (body['invitationsRedeemed'] as num?)?.toInt() ?? 0,
      );
    } on DioException catch (e) {
      throw _toAuthException(e);
    }
  }

  /// Enumeration-safe — server always responds 202 for a syntactically
  /// valid call, regardless of whether the email belongs to a PENDING
  /// account. Throws [ResendThrottledException] (subclass of
  /// [AuthApiException]) on 429 so the UI can render a countdown.
  Future<void> resendVerification(String email) async {
    try {
      await _dio.post('/v1/auth/verify/resend', data: {'email': email});
    } on DioException catch (e) {
      if (e.response?.statusCode == 429) {
        final retryRaw = e.response?.headers.value('retry-after');
        final retryAfter = int.tryParse(retryRaw ?? '') ?? 60;
        throw ResendThrottledException(
          retryAfterSeconds: retryAfter,
          message: _readApiMessage(e) ??
              'Too many requests. Try again in $retryAfter seconds.',
        );
      }
      throw _toAuthException(e);
    }
  }

  /// Always silent — the response is fixed at 204 regardless of whether the
  /// email is known. The UI shows the same "if an account exists..." copy
  /// in every branch.
  Future<void> requestPasswordReset(String email) async {
    try {
      await _dio.post('/v1/auth/password-reset/request', data: {'email': email});
    } on DioException catch (e) {
      throw _toAuthException(e);
    }
  }

  /// Consume a reset token and set a new password. Server deletes every
  /// active session for the user in the same transaction.
  Future<void> confirmPasswordReset({
    required String token,
    required String newPassword,
  }) async {
    try {
      await _dio.post('/v1/auth/password-reset/confirm', data: {
        'token': token,
        'newPassword': newPassword,
      });
    } on DioException catch (e) {
      throw _toAuthException(e);
    }
  }

  /// Token-bearer accept. Returns a discriminated union so the UI can
  /// route signed-in callers straight into the workspace, or push anon
  /// callers to /signup with the email + token preserved.
  ///
  /// The server returns 200 with body for accepted/needs_signup, and
  /// 410 with body for expired/invalid — we normalise the 410 branches
  /// into the same outcome union (no exception).
  Future<AcceptInvitationResult> acceptInvitation(String token) async {
    try {
      final res = await _dio.get('/v1/auth/invitations/accept',
          queryParameters: {'token': token});
      return _parseAccept(_asMap(res.data));
    } on DioException catch (e) {
      // 410 carries a body too — translate, don't throw, so the screen
      // can render the right inline state without try/catch.
      if (e.response?.statusCode == 410 && e.response?.data is Map) {
        return _parseAccept(_asMap(e.response!.data));
      }
      throw _toAuthException(e);
    }
  }

  static AcceptInvitationResult _parseAccept(Map<String, dynamic> body) {
    switch (body['status']) {
      case 'accepted':
        return AcceptInvitationAccepted(
          userId: body['userId'] as String?,
          workspaceCode: body['workspaceId'] as String?,
          role: body['role'] as String?,
        );
      case 'needs_signup':
        return AcceptInvitationNeedsSignup(
          email: body['email'] as String? ?? '',
          workspaceCode: body['workspaceId'] as String? ?? '',
          role: body['role'] as String?,
          rawToken: body['rawTokenForCarry'] as String? ?? '',
        );
      case 'expired':
        return const AcceptInvitationExpired();
      case 'invalid':
      default:
        return const AcceptInvitationInvalid();
    }
  }

  AuthApiException _toAuthException(DioException e) {
    final data = e.response?.data;
    String? message;
    String? code;
    if (data is Map) {
      if (data['message'] is String) message = data['message'] as String;
      if (data['error'] is String) code = data['error'] as String;
    }
    return AuthApiException(
      statusCode: e.response?.statusCode,
      message: message ?? 'Network error',
      errorCode: code,
    );
  }

  static String? _readApiMessage(DioException e) {
    final data = e.response?.data;
    if (data is Map && data['message'] is String) return data['message'] as String;
    return null;
  }

  static Map<String, dynamic> _asMap(dynamic raw) => Map<String, dynamic>.from(raw as Map);
}

class AuthApiException implements Exception {
  AuthApiException({
    required this.statusCode,
    required this.message,
    this.errorCode,
  });
  final int? statusCode;
  final String message;
  final String? errorCode;

  /// True when the server told us the token in this request is no longer
  /// valid (verify / reset / invite-accept all share this signal).
  bool get isExpiredToken => statusCode == 410 || errorCode == 'token_invalid';

  @override
  String toString() => 'AuthApiException($statusCode, $errorCode): $message';
}

/// Thrown by [AuthApi.resendVerification] when the per-email or per-IP
/// rate-limit bucket is empty. Carries the server's `Retry-After` so the
/// UI can show a live countdown on the CTA.
class ResendThrottledException extends AuthApiException {
  ResendThrottledException({
    required this.retryAfterSeconds,
    required super.message,
  }) : super(statusCode: 429, errorCode: 'rate_limited');

  final int retryAfterSeconds;
}

/// Result of GET /v1/auth/verify. The default workspace code lets the SPA
/// route the user straight into their fresh workspace post-login.
class VerifyResult {
  const VerifyResult({
    this.userId,
    this.defaultWorkspaceCode,
    this.invitationsRedeemed = 0,
  });
  final String? userId;
  final String? defaultWorkspaceCode;
  final int invitationsRedeemed;
}

/// Discriminated outcome of `GET /v1/auth/invitations/accept`. The
/// controller renders one branch per variant.
sealed class AcceptInvitationResult {
  const AcceptInvitationResult();
}

class AcceptInvitationAccepted extends AcceptInvitationResult {
  const AcceptInvitationAccepted({this.userId, this.workspaceCode, this.role});
  final String? userId;
  final String? workspaceCode;
  final String? role;
}

class AcceptInvitationNeedsSignup extends AcceptInvitationResult {
  const AcceptInvitationNeedsSignup({
    required this.email,
    required this.workspaceCode,
    required this.rawToken,
    this.role,
  });
  final String email;
  final String workspaceCode;
  final String? role;
  final String rawToken;
}

class AcceptInvitationExpired extends AcceptInvitationResult {
  const AcceptInvitationExpired();
}

class AcceptInvitationInvalid extends AcceptInvitationResult {
  const AcceptInvitationInvalid();
}

final authApiProvider = FutureProvider<AuthApi>((ref) async {
  final dio = await ref.watch(dioProvider.future);
  return AuthApi(dio);
});
