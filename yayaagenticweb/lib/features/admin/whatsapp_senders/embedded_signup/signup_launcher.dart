/// Platform-agnostic seam over the Facebook JS SDK. The wizard talks to this
/// interface; the web implementation (`signup_launcher_web.dart`) does the
/// actual SDK loading + `FB.login`. Widget tests inject a fake.
library;

import 'embedded_signup_events.dart';
import 'signup_launcher_stub.dart'
    if (dart.library.html) 'signup_launcher_web.dart' as impl;

abstract class EmbeddedSignupLauncher {
  /// Load the Facebook JS SDK (once) and initialize it with [appId]. Called
  /// lazily from the wizard page only — the SDK is never loaded elsewhere.
  Future<void> load({required String appId});

  /// Open Meta's Embedded Signup popup. Results arrive on [events].
  void launch({required String configurationId, String? partnerSolutionId});

  /// Parsed, origin-validated signup events. `SignupIgnored` is already
  /// filtered out.
  Stream<EmbeddedSignupEvent> get events;

  /// Remove the window message listener. Must be called from dispose().
  void dispose();
}

EmbeddedSignupLauncher createEmbeddedSignupLauncher() => impl.create();
