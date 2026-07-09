import '../entities/psp_entities.dart';

abstract class PspRepository {
  Future<List<PspAccount>> status();

  /// [returnUrl] is browser-facing — the console knows its own origin.
  Future<OnboardingLink> onboardingLink(String provider, String returnUrl);

  /// Posts the PSP redirect's query params back for reconciliation.
  Future<PspAccount> reconcileReturn(String provider, Map<String, dynamic> params);

  Future<PspAccount> disconnect(String provider);
}
