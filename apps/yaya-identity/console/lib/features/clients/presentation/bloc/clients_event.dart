part of 'clients_bloc.dart';

sealed class ClientsEvent extends Equatable {
  const ClientsEvent();

  @override
  List<Object?> get props => [];
}

class ClientsRequested extends ClientsEvent {
  const ClientsRequested();
}

class ClientRegisterRequested extends ClientsEvent {
  const ClientRegisterRequested(this.clientId, this.secret, this.audiences, this.scopes);
  final String clientId;
  final String secret;
  final List<String> audiences;
  final List<String> scopes;
  @override
  List<Object?> get props => [clientId, secret, audiences, scopes];
}

class ClientRotateRequested extends ClientsEvent {
  const ClientRotateRequested(this.clientId, this.secret);
  final String clientId;
  final String secret;
  @override
  List<Object?> get props => [clientId, secret];
}
