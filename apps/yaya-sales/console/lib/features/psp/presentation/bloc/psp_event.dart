part of 'psp_bloc.dart';

sealed class PspEvent extends Equatable {
  const PspEvent();

  @override
  List<Object?> get props => [];
}

class PspStatusRequested extends PspEvent {
  const PspStatusRequested();
}

class PspConnectRequested extends PspEvent {
  const PspConnectRequested(this.provider, this.returnUrl);

  final String provider;
  final String returnUrl;

  @override
  List<Object?> get props => [provider, returnUrl];
}

class PspReturnReceived extends PspEvent {
  const PspReturnReceived(this.provider, this.params);

  final String provider;
  final Map<String, dynamic> params;

  @override
  List<Object?> get props => [provider, params];
}

class PspDisconnectRequested extends PspEvent {
  const PspDisconnectRequested(this.provider);

  final String provider;

  @override
  List<Object?> get props => [provider];
}
