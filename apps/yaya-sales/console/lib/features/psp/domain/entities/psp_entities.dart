import 'package:equatable/equatable.dart';

class PspAccount extends Equatable {
  const PspAccount({
    required this.provider,
    required this.status,
    required this.chargesEnabled,
    this.accountId,
    required this.onboardingSupported,
  });

  final String provider; // stripe | paypal | dlocal
  final String status; // PENDING | ACTIVE | …
  final bool chargesEnabled;
  final String? accountId;
  final bool onboardingSupported;

  bool get connected => status == 'ACTIVE' && chargesEnabled;

  @override
  List<Object?> get props =>
      [provider, status, chargesEnabled, accountId, onboardingSupported];
}

class OnboardingLink extends Equatable {
  const OnboardingLink({required this.actionUrl, this.trackingRef});

  final String actionUrl;
  final String? trackingRef;

  @override
  List<Object?> get props => [actionUrl, trackingRef];
}
