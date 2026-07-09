import 'package:flutter_test/flutter_test.dart';
import 'package:yaya_identity_console/core/config/app_config.dart';
import 'package:yaya_identity_console/core/network/base_path_interceptor.dart';

void main() {
  group('AppConfig origin/basePath parsing', () {
    test('subpath deploy splits origin and prefix', () {
      const c = AppConfig(
        issuerBaseUrl: 'https://yayaagentic.smartlottery.do/identity',
        clientId: 'yaya-console',
        redirectUri: 'https://yayaagentic.smartlottery.do/identity/callback',
      );
      expect(c.apiOrigin, 'https://yayaagentic.smartlottery.do');
      expect(c.basePath, '/identity');
    });

    test('root deploy has empty base path', () {
      const c = AppConfig(
        issuerBaseUrl: 'http://localhost:8091',
        clientId: 'yaya-console',
        redirectUri: 'http://localhost:8091/callback',
      );
      expect(c.apiOrigin, 'http://localhost:8091');
      expect(c.basePath, '');
    });
  });

  group('BasePathInterceptor.apply', () {
    test('prepends the prefix to leading-slash paths', () {
      expect(BasePathInterceptor.apply('/v1/identity/admin/orgs', '/identity'),
          '/identity/v1/identity/admin/orgs');
      expect(BasePathInterceptor.apply('/oauth2/token', '/identity'), '/identity/oauth2/token');
    });

    test('is a no-op at the root', () {
      expect(BasePathInterceptor.apply('/v1/x', ''), '/v1/x');
    });

    test('does not double-prefix', () {
      expect(BasePathInterceptor.apply('/identity/v1/x', '/identity'), '/identity/v1/x');
    });
  });
}
