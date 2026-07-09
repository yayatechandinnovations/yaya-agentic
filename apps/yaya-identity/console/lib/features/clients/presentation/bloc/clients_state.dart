part of 'clients_bloc.dart';

enum ClientsStatus { initial, loading, loaded, failure }

class ClientsState extends Equatable {
  const ClientsState({
    this.status = ClientsStatus.initial,
    this.clients = const [],
    this.error,
    this.message,
  });

  final ClientsStatus status;
  final List<Client> clients;
  final String? error;
  final String? message;

  ClientsState copyWith({
    ClientsStatus? status,
    List<Client>? clients,
    String? error,
    String? message,
  }) =>
      ClientsState(
        status: status ?? this.status,
        clients: clients ?? this.clients,
        error: error,
        message: message,
      );

  @override
  List<Object?> get props => [status, clients, error, message];
}
