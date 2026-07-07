/// Merchant PSP onboarding status for the "Connect PayPal/Stripe" UI.
/// Mirrors the backend `MerchantAccountView` (merchant-psp-onboarding-design.md §7).
class PspAccountView {
  const PspAccountView({
    required this.provider,
    required this.status,
    required this.chargesEnabled,
    required this.onboardingSupported,
    this.accountId,
  });

  final String provider;
  final String status; // PENDING | ONBOARDING | ACTIVE | RESTRICTED | DISABLED
  final bool chargesEnabled;
  final bool onboardingSupported;
  final String? accountId;

  /// Funds may route here only when ACTIVE + charges enabled (computed client-side;
  /// the backend's `connected()` is a method, not a serialized field).
  bool get connected => status == 'ACTIVE' && chargesEnabled;

  factory PspAccountView.fromJson(Map<String, dynamic> j) => PspAccountView(
        provider: j['provider'] as String? ?? '',
        status: j['status'] as String? ?? 'PENDING',
        chargesEnabled: j['chargesEnabled'] as bool? ?? false,
        onboardingSupported: j['onboardingSupported'] as bool? ?? false,
        accountId: j['accountId'] as String?,
      );
}

/// The hosted onboarding URL the UI opens, plus the tracking ref for correlation.
class PspOnboardingLink {
  const PspOnboardingLink({required this.actionUrl, required this.trackingRef});

  final String actionUrl;
  final String trackingRef;

  factory PspOnboardingLink.fromJson(Map<String, dynamic> j) => PspOnboardingLink(
        actionUrl: j['actionUrl'] as String? ?? '',
        trackingRef: j['trackingRef'] as String? ?? '',
      );
}
