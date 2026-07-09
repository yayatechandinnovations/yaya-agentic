part of 'settings_bloc.dart';

sealed class SettingsEvent extends Equatable {
  const SettingsEvent();

  @override
  List<Object?> get props => [];
}

class SettingsRequested extends SettingsEvent {
  const SettingsRequested(this.org);
  final String org;
  @override
  List<Object?> get props => [org];
}

class MfaToggled extends SettingsEvent {
  const MfaToggled(this.org, this.required);
  final String org;
  final bool required;
  @override
  List<Object?> get props => [org, required];
}

class KeysRotateRequested extends SettingsEvent {
  const KeysRotateRequested();
}
