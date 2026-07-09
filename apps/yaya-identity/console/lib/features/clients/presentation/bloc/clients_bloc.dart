import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/client.dart';
import '../../domain/usecases/client_usecases.dart';

part 'clients_event.dart';
part 'clients_state.dart';

class ClientsBloc extends Bloc<ClientsEvent, ClientsState> {
  ClientsBloc({
    required ListClients listClients,
    required RegisterServiceAccount registerServiceAccount,
    required RotateClientSecret rotateClientSecret,
  })  : _listClients = listClients,
        _register = registerServiceAccount,
        _rotate = rotateClientSecret,
        super(const ClientsState()) {
    on<ClientsRequested>(_onRequested);
    on<ClientRegisterRequested>(_onRegister);
    on<ClientRotateRequested>(_onRotate);
  }

  final ListClients _listClients;
  final RegisterServiceAccount _register;
  final RotateClientSecret _rotate;

  Future<void> _onRequested(ClientsRequested event, Emitter<ClientsState> emit) async {
    emit(state.copyWith(status: ClientsStatus.loading));
    try {
      emit(state.copyWith(status: ClientsStatus.loaded, clients: await _listClients()));
    } on Failure catch (f) {
      emit(state.copyWith(status: ClientsStatus.failure, error: f.message));
    }
  }

  Future<void> _onRegister(ClientRegisterRequested event, Emitter<ClientsState> emit) async {
    try {
      await _register(event.clientId, event.secret, event.audiences, event.scopes);
      add(const ClientsRequested());
    } on Failure catch (f) {
      emit(state.copyWith(status: ClientsStatus.failure, error: f.message));
    }
  }

  Future<void> _onRotate(ClientRotateRequested event, Emitter<ClientsState> emit) async {
    try {
      await _rotate(event.clientId, event.secret);
      emit(state.copyWith(message: 'Secret rotated for ${event.clientId}.'));
    } on Failure catch (f) {
      emit(state.copyWith(status: ClientsStatus.failure, error: f.message));
    }
  }
}
