part of 'psp_bloc.dart';

enum PspStatus { initial, loading, loaded, failure }

class PspState extends Equatable {
  const PspState({
    this.status = PspStatus.initial,
    this.accounts = const [],
    this.onboardingLink,
    this.reconciled,
    this.error,
  });

  final PspStatus status;
  final List<PspAccount> accounts;

  /// One-shot: set when a connect begins; the page opens the URL and moves on.
  final OnboardingLink? onboardingLink;
  final PspAccount? reconciled;
  final String? error;

  PspState copyWith({
    PspStatus? status,
    List<PspAccount>? accounts,
    OnboardingLink? onboardingLink,
    PspAccount? reconciled,
    String? error,
  }) =>
      PspState(
        status: status ?? this.status,
        accounts: accounts ?? this.accounts,
        onboardingLink: onboardingLink,
        reconciled: reconciled,
        error: error,
      );

  @override
  List<Object?> get props => [status, accounts, onboardingLink, reconciled, error];
}
