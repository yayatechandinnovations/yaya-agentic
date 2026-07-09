import 'package:flutter/material.dart';
import 'package:flutter_web_plugins/url_strategy.dart';

import 'app.dart';
import 'core/di/service_locator.dart';

Future<void> main() async {
  // Clean path URLs (no #) so the OAuth /callback route + ?code work, and the
  // PSP /psp/return/:provider redirect lands with its query params intact.
  usePathUrlStrategy();
  WidgetsFlutterBinding.ensureInitialized();
  await configureDependencies();
  runApp(const ConsoleApp());
}
