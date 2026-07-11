import 'dart:typed_data';

import 'package:dio/dio.dart';

import '../config/app_config.dart';

/// Uploads images to the sales media store and resolves the relative URLs it
/// hands back (what product media / catalog covers persist) into absolute ones
/// the browser can render.
class MediaService {
  MediaService(this._salesDio, this._config);

  final Dio _salesDio;
  final AppConfig _config;

  /// Returns the relative serving URL (e.g. `/v1/sales/media/<id>`).
  Future<String> upload(Uint8List bytes, String filename, String contentType) async {
    final res = await _salesDio.post<Map<String, dynamic>>(
      '/v1/sales/admin/media',
      data: FormData.fromMap({
        'file': MultipartFile.fromBytes(
          bytes,
          filename: filename,
          contentType: DioMediaType.parse(contentType),
        ),
      }),
    );
    final url = '${res.data?['url'] ?? ''}';
    if (url.isEmpty) {
      throw StateError('media upload returned no url');
    }
    return url;
  }

  /// Absolute URL for rendering. External (http…) URLs pass through untouched.
  String absolute(String url) => url.startsWith('http')
      ? url
      : '${_config.salesOrigin}${_config.salesBasePath}$url';
}
