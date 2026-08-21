// PLACEHOLDER — replace by running `flutterfire configure` for the shared Yaya
// Firebase project. That command overwrites this whole file with real, per-
// platform options AND drops android/app/google-services.json +
// ios/Runner/GoogleService-Info.plist into place.
//
// Until it runs, `configured` stays false and the push layer (bootstrapPush)
// never initializes Firebase — so dev/web builds and `flutter test` work with
// no Firebase project at all. Do NOT hand-edit the values below; regenerate.
//
// ignore_for_file: type=lint
import 'package:firebase_core/firebase_core.dart' show FirebaseOptions;
import 'package:flutter/foundation.dart'
    show defaultTargetPlatform, kIsWeb, TargetPlatform;

class DefaultFirebaseOptions {
  /// Flipped to true by `flutterfire configure` (it emits real options). While
  /// false, the app treats push as unconfigured and degrades to inbox polling.
  static const bool configured = false;

  static FirebaseOptions get currentPlatform {
    if (!configured) {
      throw UnsupportedError(
        'Firebase not configured — run `flutterfire configure`. '
        'bootstrapPush() guards on DefaultFirebaseOptions.configured, so this '
        'should never be reached in a build without real options.',
      );
    }
    if (kIsWeb) return _placeholder;
    switch (defaultTargetPlatform) {
      case TargetPlatform.android:
      case TargetPlatform.iOS:
        return _placeholder;
      default:
        throw UnsupportedError(
          'No Firebase options for $defaultTargetPlatform — regenerate with '
          '`flutterfire configure`.',
        );
    }
  }

  // Never used while configured=false; present only so the file type-checks.
  static const FirebaseOptions _placeholder = FirebaseOptions(
    apiKey: 'PLACEHOLDER',
    appId: 'PLACEHOLDER',
    messagingSenderId: 'PLACEHOLDER',
    projectId: 'PLACEHOLDER',
  );
}
