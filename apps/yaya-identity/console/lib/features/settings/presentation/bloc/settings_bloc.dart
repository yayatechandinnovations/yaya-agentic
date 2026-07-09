import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/org_settings.dart';
import '../../domain/usecases/settings_usecases.dart';

part 'settings_event.dart';
part 'settings_state.dart';

class SettingsBloc extends Bloc<SettingsEvent, SettingsState> {
  SettingsBloc({
    required LoadOrgSettings loadOrgSettings,
    required SetMfaRequired setMfaRequired,
    required RotateKeys rotateKeys,
  })  : _load = loadOrgSettings,
        _setMfa = setMfaRequired,
        _rotate = rotateKeys,
        super(const SettingsState()) {
    on<SettingsRequested>(_onRequested);
    on<MfaToggled>(_onMfaToggled);
    on<KeysRotateRequested>(_onRotate);
  }

  final LoadOrgSettings _load;
  final SetMfaRequired _setMfa;
  final RotateKeys _rotate;

  Future<void> _onRequested(SettingsRequested event, Emitter<SettingsState> emit) async {
    emit(state.copyWith(status: SettingsStatus.loading));
    try {
      emit(state.copyWith(status: SettingsStatus.loaded, settings: await _load(event.org)));
    } on Failure catch (f) {
      emit(state.copyWith(status: SettingsStatus.failure, error: f.message));
    }
  }

  Future<void> _onMfaToggled(MfaToggled event, Emitter<SettingsState> emit) async {
    try {
      await _setMfa(event.org, event.required);
      add(SettingsRequested(event.org));
    } on Failure catch (f) {
      emit(state.copyWith(status: SettingsStatus.failure, error: f.message));
    }
  }

  Future<void> _onRotate(KeysRotateRequested event, Emitter<SettingsState> emit) async {
    try {
      final kid = await _rotate();
      emit(state.copyWith(message: 'Signing key rotated. New active kid: $kid'));
    } on Failure catch (f) {
      emit(state.copyWith(status: SettingsStatus.failure, error: f.message));
    }
  }
}
