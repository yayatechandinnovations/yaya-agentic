import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/audit_row.dart';
import '../../domain/usecases/read_audit.dart';

part 'audit_event.dart';
part 'audit_state.dart';

class AuditBloc extends Bloc<AuditEvent, AuditState> {
  AuditBloc({required ReadAudit readAudit})
      : _readAudit = readAudit,
        super(const AuditState()) {
    on<AuditRequested>(_onRequested);
  }

  final ReadAudit _readAudit;

  Future<void> _onRequested(AuditRequested event, Emitter<AuditState> emit) async {
    emit(state.copyWith(status: AuditStatus.loading));
    try {
      final rows = await _readAudit(event.org);
      emit(state.copyWith(status: AuditStatus.loaded, rows: rows));
    } on Failure catch (f) {
      emit(state.copyWith(status: AuditStatus.failure, error: f.message));
    }
  }
}
