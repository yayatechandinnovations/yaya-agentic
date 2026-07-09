part of 'audit_bloc.dart';

enum AuditStatus { initial, loading, loaded, failure }

class AuditState extends Equatable {
  const AuditState({this.status = AuditStatus.initial, this.rows = const [], this.error});

  final AuditStatus status;
  final List<AuditRow> rows;
  final String? error;

  AuditState copyWith({AuditStatus? status, List<AuditRow>? rows, String? error}) =>
      AuditState(status: status ?? this.status, rows: rows ?? this.rows, error: error);

  @override
  List<Object?> get props => [status, rows, error];
}
