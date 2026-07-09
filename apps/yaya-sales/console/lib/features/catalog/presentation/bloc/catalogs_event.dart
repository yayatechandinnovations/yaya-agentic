part of 'catalogs_bloc.dart';

sealed class CatalogsEvent extends Equatable {
  const CatalogsEvent();

  @override
  List<Object?> get props => [];
}

class CatalogsRequested extends CatalogsEvent {
  const CatalogsRequested();
}

class CatalogCreateRequested extends CatalogsEvent {
  const CatalogCreateRequested(this.displayName);

  final String displayName;

  @override
  List<Object?> get props => [displayName];
}

class CatalogPublishRequested extends CatalogsEvent {
  const CatalogPublishRequested(this.catalogId);

  final String catalogId;

  @override
  List<Object?> get props => [catalogId];
}
