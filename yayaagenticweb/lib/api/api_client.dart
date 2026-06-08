import 'dart:html' as html;

import 'package:dio/browser.dart';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../features/auth/application/auth_controller.dart';
import 'api_config.dart';

const _stateChangingMethods = {'POST', 'PUT', 'PATCH', 'DELETE'};

/// Reads a cookie value from `document.cookie`. The XSRF-TOKEN cookie is
/// minted by the backend's CsrfWebFilter (intentionally NOT HttpOnly so
/// JS can read it). Returns null when the cookie isn't present yet —
/// the first GET after page load is what triggers minting.
String? _readCookie(String name) {
  final raw = html.document.cookie ?? '';
  if (raw.isEmpty) return null;
  for (final part in raw.split(';')) {
    final eq = part.indexOf('=');
    if (eq < 0) continue;
    final k = part.substring(0, eq).trim();
    if (k == name) return Uri.decodeComponent(part.substring(eq + 1));
  }
  return null;
}

/// Dio HTTP client wired with the configured base URL, credentialed CORS
/// (so the browser sends the YAYA_SESSION cookie), and a 401 interceptor
/// that pushes the auth controller into the [AuthSessionExpired] state so
/// the router redirects the operator back to /login.
///
/// The 401 hook deliberately ignores /v1/auth/login responses — those are
/// credential failures, not session expirations, and the login screen
/// surfaces them directly.
final dioProvider = FutureProvider<Dio>((ref) async {
  final config = await ref.watch(apiConfigProvider.future);
  final dio = Dio(BaseOptions(
    baseUrl: config.baseUrl,
    connectTimeout: const Duration(seconds: 10),
    receiveTimeout: const Duration(seconds: 30),
    headers: {'Accept': 'application/json'},
  ));

  // Browser-only: opt into credentialed CORS so YAYA_SESSION travels.
  // Backend's CorsWebFilter is already setAllowCredentials(true) with
  // specific origin patterns (not '*'), which is required for this to work.
  dio.httpClientAdapter = BrowserHttpClientAdapter()..withCredentials = true;

  dio.interceptors.add(InterceptorsWrapper(
    onRequest: (options, handler) {
      // Echo the XSRF token on state-changing requests so the backend
      // CsrfWebFilter accepts them. Reads the cookie minted by the
      // first GET of the session; if it isn't there yet (very first
      // request), we let it go through and the backend will mint it.
      if (_stateChangingMethods.contains(options.method.toUpperCase())) {
        final token = _readCookie('XSRF-TOKEN');
        if (token != null) options.headers['X-XSRF-TOKEN'] = token;
      }
      handler.next(options);
    },
    onError: (e, handler) {
      final status = e.response?.statusCode;
      final path = e.requestOptions.path;
      if (status == 401 && !path.endsWith('/v1/auth/login')) {
        // Don't loop: only flip to expired if we currently think we're
        // authenticated. Avoids fighting the controller during boot when
        // /me legitimately returns 401 for an anonymous visitor.
        ref.read(authProvider.notifier).markExpiredFromInterceptor();
      }
      handler.next(e);
    },
  ));

  return dio;
});
