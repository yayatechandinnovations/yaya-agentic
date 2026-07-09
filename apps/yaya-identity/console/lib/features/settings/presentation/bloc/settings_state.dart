part of 'settings_bloc.dart';

enum SettingsStatus { initial, loading, loaded, failure }

class SettingsState extends Equatable {
  const SettingsState({
    this.status = SettingsStatus.initial,
    this.settings,
    this.error,
    this.message,
  });

  final SettingsStatus status;
  final OrgSettings? settings;
  final String? error;
  final String? message;

  SettingsState copyWith({
    SettingsStatus? status,
    OrgSettings? settings,
    String? error,
    String? message,
  }) =>
      SettingsState(
        status: status ?? this.status,
        settings: settings ?? this.settings,
        error: error,
        message: message,
      );

  @override
  List<Object?> get props => [status, settings, error, message];
}
