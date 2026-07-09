part of 'catalogs_bloc.dart';

enum CatalogsStatus { initial, loading, loaded, failure }

class CatalogsState extends Equatable {
  const CatalogsState({
    this.status = CatalogsStatus.initial,
    this.catalogs = const [],
    this.error,
  });

  final CatalogsStatus status;
  final List<Catalog> catalogs;
  final String? error;

  CatalogsState copyWith({CatalogsStatus? status, List<Catalog>? catalogs, String? error}) =>
      CatalogsState(
        status: status ?? this.status,
        catalogs: catalogs ?? this.catalogs,
        error: error,
      );

  @override
  List<Object?> get props => [status, catalogs, error];
}
