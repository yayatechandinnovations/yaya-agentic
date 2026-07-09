import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:yaya_identity_console/core/error/failure.dart';
import 'package:yaya_identity_console/features/audit/domain/entities/audit_row.dart';
import 'package:yaya_identity_console/features/audit/domain/repositories/audit_repository.dart';
import 'package:yaya_identity_console/features/audit/domain/usecases/read_audit.dart';
import 'package:yaya_identity_console/features/audit/presentation/bloc/audit_bloc.dart';

class MockAuditRepository extends Mock implements AuditRepository {}

void main() {
  late MockAuditRepository repo;

  AuditBloc build() => AuditBloc(readAudit: ReadAudit(repo));

  setUp(() => repo = MockAuditRepository());

  const rows = [
    AuditRow(eventType: 'authz.decision', action: 'sales.order:refund', decision: 'DENY',
        userReason: 'blocked', auditReason: 'policy refund-cap: 750 > 500'),
  ];

  blocTest<AuditBloc, AuditState>(
    'AuditRequested → loading, loaded',
    setUp: () => when(() => repo.read(any(), limit: any(named: 'limit')))
        .thenAnswer((_) async => rows),
    build: build,
    act: (b) => b.add(const AuditRequested('org_1')),
    expect: () => [
      const AuditState(status: AuditStatus.loading),
      const AuditState(status: AuditStatus.loaded, rows: rows),
    ],
    verify: (_) => verify(() => repo.read('org_1', limit: 50)).called(1),
  );

  blocTest<AuditBloc, AuditState>(
    'AuditRequested failure → loading, failure',
    setUp: () => when(() => repo.read(any(), limit: any(named: 'limit')))
        .thenThrow(const AuthFailure('session expired — please sign in again')),
    build: build,
    act: (b) => b.add(const AuditRequested('org_1')),
    expect: () => [
      const AuditState(status: AuditStatus.loading),
      const AuditState(status: AuditStatus.failure, error: 'session expired — please sign in again'),
    ],
  );
}
