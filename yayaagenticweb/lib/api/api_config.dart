import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

class ApiConfig {
  ApiConfig(this.baseUrl);
  final String baseUrl;

  static const String _kBaseUrl = 'yaya.backend.base_url';

  /// Default backend URL. Compile-time override:
  ///   flutter build web --dart-define=BACKEND_BASE_URL=https://api.example
  /// The compose `web` service uses this to bake in `http://localhost:8080`
  /// so the browser hits the host-exposed backend port.
  static const String defaultBaseUrl = String.fromEnvironment(
    'BACKEND_BASE_URL',
    defaultValue: 'http://localhost:8080',
  );
}

/// Async-loaded config. Persisted to SharedPreferences so the operator can
/// override the backend URL once and have it stick across reloads.
final apiConfigProvider = FutureProvider<ApiConfig>((ref) async {
  final prefs = await SharedPreferences.getInstance();
  final url = prefs.getString(ApiConfig._kBaseUrl) ?? ApiConfig.defaultBaseUrl;
  return ApiConfig(url);
});

/// Imperative override used by the Settings drawer.
Future<void> setBackendBaseUrl(WidgetRef ref, String url) async {
  final prefs = await SharedPreferences.getInstance();
  await prefs.setString(ApiConfig._kBaseUrl, url);
  ref.invalidate(apiConfigProvider);
}
