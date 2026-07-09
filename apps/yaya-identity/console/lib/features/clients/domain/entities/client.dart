import 'package:equatable/equatable.dart';

class Client extends Equatable {
  const Client({required this.clientId, required this.type, this.audiences = const []});

  final String clientId;
  final String type; // PUBLIC | CONFIDENTIAL | SERVICE
  final List<String> audiences;

  @override
  List<Object?> get props => [clientId, type, audiences];
}
