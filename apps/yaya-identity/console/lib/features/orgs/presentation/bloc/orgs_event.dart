part of 'orgs_bloc.dart';

sealed class OrgsEvent extends Equatable {
  const OrgsEvent();

  @override
  List<Object?> get props => [];
}

class OrgsRequested extends OrgsEvent {
  const OrgsRequested();
}

class OrgCreateRequested extends OrgsEvent {
  const OrgCreateRequested(this.name);

  final String name;

  @override
  List<Object?> get props => [name];
}
