import 'package:dio/dio.dart';
import 'package:get_it/get_it.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../features/auth/data/datasources/auth_remote_data_source.dart';
import '../../features/auth/data/platform/oauth_launcher.dart';
import '../../features/auth/data/platform/web_oauth_launcher.dart';
import '../../features/auth/data/repositories/auth_repository_impl.dart';
import '../../features/auth/domain/repositories/auth_repository.dart';
import '../../features/auth/domain/usecases/auth_usecases.dart';
import '../../features/auth/presentation/bloc/auth_bloc.dart';
import '../../features/catalog/data/datasources/catalog_remote_data_source.dart';
import '../../features/catalog/data/repositories/catalog_repository_impl.dart';
import '../../features/catalog/domain/repositories/catalog_repository.dart';
import '../../features/catalog/domain/usecases/catalog_usecases.dart';
import '../../features/catalog/presentation/bloc/catalogs_bloc.dart';
import '../../features/catalog/presentation/bloc/products_bloc.dart';
import '../../features/drafts/data/datasources/draft_remote_data_source.dart';
import '../../features/drafts/data/repositories/draft_repository_impl.dart';
import '../../features/drafts/domain/repositories/draft_repository.dart';
import '../../features/drafts/domain/usecases/draft_usecases.dart';
import '../../features/drafts/presentation/bloc/drafts_bloc.dart';
import '../../features/fleet/data/datasources/fleet_remote_data_source.dart';
import '../../features/fleet/data/repositories/fleet_repository_impl.dart';
import '../../features/fleet/domain/repositories/fleet_repository.dart';
import '../../features/fleet/domain/usecases/fleet_usecases.dart';
import '../../features/fleet/presentation/bloc/fleet_bloc.dart';
import '../../features/inventory/data/datasources/inventory_remote_data_source.dart';
import '../../features/inventory/data/repositories/inventory_repository_impl.dart';
import '../../features/inventory/domain/repositories/inventory_repository.dart';
import '../../features/inventory/domain/usecases/inventory_usecases.dart';
import '../../features/inventory/presentation/bloc/inventory_bloc.dart';
import '../../features/orders/data/datasources/order_remote_data_source.dart';
import '../../features/orders/data/repositories/order_repository_impl.dart';
import '../../features/orders/domain/repositories/order_repository.dart';
import '../../features/orders/domain/usecases/order_usecases.dart';
import '../../features/orders/presentation/bloc/order_detail_bloc.dart';
import '../../features/orders/presentation/bloc/orders_bloc.dart';
import '../../features/orders/presentation/bloc/store_credit_bloc.dart';
import '../../features/pricing/data/datasources/pricing_remote_data_source.dart';
import '../../features/pricing/data/repositories/pricing_repository_impl.dart';
import '../../features/pricing/domain/repositories/pricing_repository.dart';
import '../../features/pricing/domain/usecases/pricing_usecases.dart';
import '../../features/pricing/presentation/bloc/pricing_bloc.dart';
import '../../features/psp/data/datasources/psp_remote_data_source.dart';
import '../../features/psp/data/repositories/psp_repository_impl.dart';
import '../../features/psp/domain/repositories/psp_repository.dart';
import '../../features/psp/domain/usecases/psp_usecases.dart';
import '../../features/psp/presentation/bloc/psp_bloc.dart';
import '../../features/purchasing/data/datasources/purchasing_remote_data_source.dart';
import '../../features/purchasing/data/repositories/purchasing_repository_impl.dart';
import '../../features/purchasing/domain/repositories/purchasing_repository.dart';
import '../../features/purchasing/domain/usecases/purchasing_usecases.dart';
import '../../features/purchasing/presentation/bloc/purchasing_bloc.dart';
import '../../features/reports/data/datasources/reports_remote_data_source.dart';
import '../../features/reports/data/repositories/reports_repository_impl.dart';
import '../../features/reports/domain/repositories/reports_repository.dart';
import '../../features/reports/domain/usecases/report_usecases.dart';
import '../../features/reports/presentation/bloc/reports_bloc.dart';
import '../../features/returns/data/datasources/rma_remote_data_source.dart';
import '../../features/returns/data/repositories/rma_repository_impl.dart';
import '../../features/returns/domain/repositories/rma_repository.dart';
import '../../features/returns/domain/usecases/rma_usecases.dart';
import '../../features/returns/presentation/bloc/rmas_bloc.dart';
import '../../features/shipments/data/datasources/shipment_remote_data_source.dart';
import '../../features/shipments/data/repositories/shipment_repository_impl.dart';
import '../../features/shipments/domain/repositories/shipment_repository.dart';
import '../../features/shipments/domain/usecases/shipment_usecases.dart';
import '../../features/shipments/presentation/bloc/shipments_bloc.dart';
import '../../features/shipping/data/datasources/shipping_remote_data_source.dart';
import '../../features/shipping/data/repositories/shipping_repository_impl.dart';
import '../../features/shipping/domain/repositories/shipping_repository.dart';
import '../../features/shipping/domain/usecases/shipping_usecases.dart';
import '../../features/shipping/presentation/bloc/shipping_bloc.dart';
import '../../features/stores/data/datasources/store_remote_data_source.dart';
import '../../features/stores/data/repositories/store_repository_impl.dart';
import '../../features/stores/domain/repositories/store_repository.dart';
import '../../features/stores/domain/usecases/store_usecases.dart';
import '../../features/stores/presentation/bloc/store_picker_bloc.dart';
import '../../features/stores/presentation/bloc/store_settings_bloc.dart';
import '../config/app_config.dart';
import '../network/dio_client.dart';
import '../storage/store_context.dart';
import '../storage/token_store.dart';

final GetIt sl = GetIt.instance;

/// The composition root — the only place concrete implementations are wired to
/// interfaces. Everything else depends on abstractions.
Future<void> configureDependencies() async {
  const config = AppConfig.fromEnv;
  final prefs = await SharedPreferences.getInstance();

  sl.registerSingleton<AppConfig>(config);
  sl.registerSingleton<TokenStore>(SharedPrefsTokenStore(prefs));
  sl.registerSingleton<StoreContext>(StoreContext(prefs));
  sl.registerSingleton<Dio>(
      DioClient.sales(config, sl<TokenStore>(), sl<StoreContext>()));
  sl.registerSingleton<Dio>(DioClient.identity(config), instanceName: 'identity');
  sl.registerSingleton<OAuthLauncher>(WebOAuthLauncher());

  // --- auth ---
  sl.registerSingleton(AuthRemoteDataSource(sl<Dio>(instanceName: 'identity'), config));
  sl.registerSingleton<AuthRepository>(AuthRepositoryImpl(
    remote: sl(),
    tokens: sl(),
    launcher: sl(),
    config: config,
  ));
  sl.registerLazySingleton(() => AuthBloc(
        loadSession: LoadSession(sl()),
        beginLogin: BeginLogin(sl()),
        completeLogin: CompleteLogin(sl()),
        logout: Logout(sl()),
      ));

  // --- stores (picker + settings) ---
  sl.registerSingleton(StoreRemoteDataSource(sl<Dio>()));
  sl.registerSingleton<StoreRepository>(StoreRepositoryImpl(sl()));
  sl.registerFactory(() => StorePickerBloc(
        listStores: ListStores(sl()),
        createStore: CreateStore(sl()),
      ));
  sl.registerFactory(() => StoreSettingsBloc(
        getStore: GetStore(sl()),
        updatePolicies: UpdateStorePolicies(sl()),
        listLocations: ListLocations(sl()),
        addLocation: AddLocation(sl()),
        listChannels: ListChannels(sl()),
        addChannel: AddChannel(sl()),
        listRegions: ListRegions(sl()),
        addRegion: AddRegion(sl()),
        updateRegion: UpdateRegion(sl()),
      ));

  // --- catalog ---
  sl.registerSingleton(CatalogRemoteDataSource(sl<Dio>()));
  sl.registerSingleton<CatalogRepository>(CatalogRepositoryImpl(sl()));
  sl.registerFactory(() => CatalogsBloc(
        listCatalogs: ListCatalogs(sl()),
        createCatalog: CreateCatalog(sl()),
        publishCatalog: PublishCatalog(sl()),
      ));
  sl.registerFactory(() => ProductsBloc(
        listProducts: ListProducts(sl()),
        createProduct: CreateProduct(sl()),
        updateProduct: UpdateProduct(sl()),
        archiveProduct: ArchiveProduct(sl()),
        restoreProduct: RestoreProduct(sl()),
        importCsv: ImportProductsCsv(sl()),
        exportCsv: ExportProductsCsv(sl()),
      ));

  // --- inventory ---
  sl.registerSingleton(InventoryRemoteDataSource(sl<Dio>()));
  sl.registerSingleton<InventoryRepository>(InventoryRepositoryImpl(sl()));
  sl.registerFactory(() => InventoryBloc(
        listLevels: ListLevels(sl()),
        readLedger: ReadLedger(sl()),
        receiveStock: ReceiveStock(sl()),
        adjustStock: AdjustStock(sl()),
        transferStock: TransferStock(sl()),
        setReorder: SetReorderPolicy(sl()),
        setBackorder: SetBackorderPolicy(sl()),
      ));

  // --- pricing ---
  sl.registerSingleton(PricingRemoteDataSource(sl<Dio>()));
  sl.registerSingleton<PricingRepository>(PricingRepositoryImpl(sl()));
  sl.registerFactory(() => PricingBloc(
        listPriceLists: ListPriceLists(sl()),
        createPriceList: CreatePriceList(sl()),
        updatePriceList: UpdatePriceList(sl()),
        listPromotions: ListPromotions(sl()),
        createPromotion: CreatePromotion(sl()),
        togglePromotion: TogglePromotion(sl()),
      ));

  // --- orders + store credit ---
  sl.registerSingleton(OrderRemoteDataSource(sl<Dio>()));
  sl.registerSingleton<OrderRepository>(OrderRepositoryImpl(sl()));
  sl.registerFactory(() => OrdersBloc(listOrders: ListOrders(sl())));
  sl.registerFactory(() => OrderDetailBloc(
        getDetail: GetOrderDetail(sl()),
        cancelOrder: CancelOrder(sl()),
        addTender: AddTender(sl()),
        settleTender: SettleTender(sl()),
        refundOrder: RefundOrder(sl()),
      ));
  sl.registerFactory(() => StoreCreditBloc(
        getBalance: GetStoreCreditBalance(sl()),
        issueCredit: IssueStoreCredit(sl()),
      ));

  // --- draft orders ---
  sl.registerSingleton(DraftRemoteDataSource(sl<Dio>()));
  sl.registerSingleton<DraftRepository>(DraftRepositoryImpl(sl()));
  sl.registerFactory(() => DraftsBloc(
        listOrders: ListOrders(sl()),
        createDraft: CreateDraft(sl()),
        updateDraft: UpdateDraft(sl()),
        sendDraft: SendDraft(sl()),
        cancelOrder: CancelOrder(sl()),
        listCatalogs: ListCatalogs(sl()),
        listProducts: ListProducts(sl()),
      ));

  // --- shipments (rendered inside order detail) ---
  sl.registerSingleton(ShipmentRemoteDataSource(sl<Dio>()));
  sl.registerSingleton<ShipmentRepository>(ShipmentRepositoryImpl(sl()));
  sl.registerFactory(() => ShipmentsBloc(
        listShipments: ListShipmentsOfOrder(sl()),
        createShipment: CreateShipment(sl()),
        pickShipment: PickShipment(sl()),
        packShipment: PackShipment(sl()),
        dispatchShipment: DispatchShipment(sl()),
        advanceDelivery: AdvanceDelivery(sl()),
        readTracking: ReadTracking(sl()),
        assignCourier: AssignCourier(sl()),
      ));

  // --- shipping config ---
  sl.registerSingleton(ShippingRemoteDataSource(sl<Dio>()));
  sl.registerSingleton<ShippingRepository>(ShippingRepositoryImpl(sl()));
  sl.registerFactory(() => ShippingBloc(
        listZones: ListZones(sl()),
        addZone: AddZone(sl()),
        listMethods: ListMethods(sl()),
        addMethod: AddMethod(sl()),
        setMethodActive: SetMethodActive(sl()),
        listCarriers: ListCarriers(sl()),
        addCarrier: AddCarrier(sl()),
        listRates: ListRates(sl()),
        addRate: AddRate(sl()),
        addSlot: AddSlot(sl()),
      ));

  // --- fleet ---
  sl.registerSingleton(FleetRemoteDataSource(sl<Dio>()));
  sl.registerSingleton<FleetRepository>(FleetRepositoryImpl(sl()));
  sl.registerFactory(() => FleetBloc(
        mintInvite: MintInvite(sl()),
        listAgents: ListAgents(sl()),
        getAgent: GetAgent(sl()),
        readDocument: ReadDocumentContent(sl()),
        approveAgent: ApproveAgent(sl()),
        rejectAgent: RejectAgent(sl()),
        suspendAgent: SuspendAgent(sl()),
      ));

  // --- returns ---
  sl.registerSingleton(RmaRemoteDataSource(sl<Dio>()));
  sl.registerSingleton<RmaRepository>(RmaRepositoryImpl(sl()));
  sl.registerFactory(() => RmasBloc(
        listRmas: ListRmas(sl()),
        getRma: GetRma(sl()),
        requestRma: RequestRma(sl()),
        authorizeRma: AuthorizeRma(sl()),
        rejectRma: RejectRma(sl()),
        cancelRma: CancelRma(sl()),
        setReturnShipment: SetReturnShipment(sl()),
        receiveRma: ReceiveRma(sl()),
        getOrderDetail: GetOrderDetail(sl()),
      ));

  // --- purchasing ---
  sl.registerSingleton(PurchasingRemoteDataSource(sl<Dio>()));
  sl.registerSingleton<PurchasingRepository>(PurchasingRepositoryImpl(sl()));
  sl.registerFactory(() => PurchasingBloc(
        listSuppliers: ListSuppliers(sl()),
        createSupplier: CreateSupplier(sl()),
        updateSupplier: UpdateSupplier(sl()),
        deleteSupplier: DeleteSupplier(sl()),
        listPos: ListPurchaseOrders(sl()),
        createPo: CreatePurchaseOrder(sl()),
        placePo: PlacePurchaseOrder(sl()),
        cancelPo: CancelPurchaseOrder(sl()),
        receivePo: ReceivePurchaseOrder(sl()),
      ));

  // --- psp ---
  sl.registerSingleton(PspRemoteDataSource(sl<Dio>()));
  sl.registerSingleton<PspRepository>(PspRepositoryImpl(sl()));
  sl.registerFactory(() => PspBloc(
        listAccounts: ListPspAccounts(sl()),
        beginOnboarding: BeginPspOnboarding(sl()),
        reconcileReturn: ReconcilePspReturn(sl()),
        disconnect: DisconnectPsp(sl()),
      ));

  // --- reports ---
  sl.registerSingleton(ReportsRemoteDataSource(sl<Dio>()));
  sl.registerSingleton<ReportsRepository>(ReportsRepositoryImpl(sl()));
  sl.registerFactory(() => ReportsBloc(
        readSales: ReadSalesReport(sl()),
        readValuation: ReadValuationReport(sl()),
        readMargin: ReadMarginReport(sl()),
        readLowStock: ReadLowStockReport(sl()),
        readCod: ReadCodReport(sl()),
      ));
}
