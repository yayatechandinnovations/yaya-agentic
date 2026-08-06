import 'signup_launcher.dart';

/// Non-web fallback — the admin console is web-only, so this only exists to
/// keep VM compilation (tests) working. Widget tests inject a fake launcher.
EmbeddedSignupLauncher create() =>
    throw UnsupportedError('Embedded Signup requires the web platform');
