import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/catalog_entities.dart';
import '../../domain/usecases/catalog_usecases.dart';

part 'catalogs_event.dart';
part 'catalogs_state.dart';

class CatalogsBloc extends Bloc<CatalogsEvent, CatalogsState> {
  CatalogsBloc({
    required ListCatalogs listCatalogs,
    required CreateCatalog createCatalog,
    required PublishCatalog publishCatalog,
  })  : _listCatalogs = listCatalogs,
        _createCatalog = createCatalog,
        _publishCatalog = publishCatalog,
        super(const CatalogsState()) {
    on<CatalogsRequested>(_onRequested);
    on<CatalogCreateRequested>(_onCreate);
    on<CatalogPublishRequested>(_onPublish);
  }

  final ListCatalogs _listCatalogs;
  final CreateCatalog _createCatalog;
  final PublishCatalog _publishCatalog;

  Future<void> _onRequested(CatalogsRequested event, Emitter<CatalogsState> emit) async {
    emit(state.copyWith(status: CatalogsStatus.loading));
    try {
      emit(state.copyWith(status: CatalogsStatus.loaded, catalogs: await _listCatalogs()));
    } on Failure catch (f) {
      emit(state.copyWith(status: CatalogsStatus.failure, error: f.message));
    }
  }

  Future<void> _onCreate(CatalogCreateRequested event, Emitter<CatalogsState> emit) async {
    try {
      await _createCatalog(event.displayName);
      add(const CatalogsRequested());
    } on Failure catch (f) {
      emit(state.copyWith(status: CatalogsStatus.failure, error: f.message));
    }
  }

  Future<void> _onPublish(CatalogPublishRequested event, Emitter<CatalogsState> emit) async {
    try {
      await _publishCatalog(event.catalogId);
      add(const CatalogsRequested());
    } on Failure catch (f) {
      emit(state.copyWith(status: CatalogsStatus.failure, error: f.message));
    }
  }
}
