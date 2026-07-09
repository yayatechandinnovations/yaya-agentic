import 'package:flutter/foundation.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:go_router/go_router.dart';

import '../../features/auth/presentation/bloc/auth_bloc.dart';
import '../../features/auth/presentation/pages/callback_page.dart';
import '../../features/auth/presentation/pages/login_page.dart';
import '../../features/catalog/presentation/bloc/catalogs_bloc.dart';
import '../../features/catalog/presentation/bloc/products_bloc.dart';
import '../../features/catalog/presentation/pages/catalog_products_page.dart';
import '../../features/catalog/presentation/pages/catalogs_page.dart';
import '../../features/drafts/presentation/bloc/drafts_bloc.dart';
import '../../features/drafts/presentation/pages/drafts_page.dart';
import '../../features/fleet/presentation/bloc/fleet_bloc.dart';
import '../../features/fleet/presentation/pages/agent_review_page.dart';
import '../../features/fleet/presentation/pages/fleet_page.dart';
import '../../features/inventory/presentation/bloc/inventory_bloc.dart';
import '../../features/inventory/presentation/pages/inventory_page.dart';
import '../../features/orders/presentation/bloc/order_detail_bloc.dart';
import '../../features/orders/presentation/bloc/orders_bloc.dart';
import '../../features/orders/presentation/bloc/store_credit_bloc.dart';
import '../../features/orders/presentation/pages/order_detail_page.dart';
import '../../features/orders/presentation/pages/orders_page.dart';
import '../../features/orders/presentation/pages/store_credit_page.dart';
import '../../features/pricing/presentation/bloc/pricing_bloc.dart';
import '../../features/pricing/presentation/pages/pricing_page.dart';
import '../../features/psp/presentation/bloc/psp_bloc.dart';
import '../../features/psp/presentation/pages/psp_page.dart';
import '../../features/purchasing/presentation/bloc/purchasing_bloc.dart';
import '../../features/purchasing/presentation/pages/purchasing_page.dart';
import '../../features/reports/presentation/bloc/reports_bloc.dart';
import '../../features/reports/presentation/pages/reports_page.dart';
import '../../features/returns/presentation/bloc/rmas_bloc.dart';
import '../../features/returns/presentation/pages/returns_page.dart';
import '../../features/returns/presentation/pages/rma_detail_page.dart';
import '../../features/shipments/presentation/bloc/shipments_bloc.dart';
import '../../features/shipping/presentation/bloc/shipping_bloc.dart';
import '../../features/shipping/presentation/pages/shipping_page.dart';
import '../../features/stores/presentation/bloc/store_picker_bloc.dart';
import '../../features/stores/presentation/bloc/store_settings_bloc.dart';
import '../../features/stores/presentation/pages/store_picker_page.dart';
import '../../features/stores/presentation/pages/store_settings_page.dart';
import '../di/service_locator.dart';
import '../storage/store_context.dart';
import 'console_shell.dart';
import 'go_router_refresh_stream.dart';

/// Routing with two guards: unauthenticated → /signin; authenticated without
/// an acting store → /stores. Each protected route provides its feature BLoCs.
class AppRouter {
  static GoRouter build(AuthBloc authBloc, StoreContext stores) {
    return GoRouter(
      initialLocation: '/orders',
      refreshListenable:
          Listenable.merge([GoRouterRefreshStream(authBloc.stream), stores]),
      redirect: (context, state) {
        final authed = authBloc.state.isAuthenticated;
        final loc = state.matchedLocation;
        if (loc == '/callback') return null; // callback exchanges the code
        if (!authed && loc != '/signin') return '/signin';
        if (authed && loc == '/signin') return stores.hasStore ? '/orders' : '/stores';
        if (authed && !stores.hasStore && loc != '/stores') return '/stores';
        return null;
      },
      routes: [
        GoRoute(path: '/signin', builder: (_, __) => const LoginPage()),
        GoRoute(
          path: '/callback',
          builder: (_, state) => CallbackPage(code: state.uri.queryParameters['code']),
        ),
        GoRoute(
          path: '/stores',
          builder: (_, __) => BlocProvider(
            create: (_) => sl<StorePickerBloc>(),
            child: const StorePickerPage(),
          ),
        ),
        ShellRoute(
          builder: (context, state, child) =>
              ConsoleShell(location: state.matchedLocation, child: child),
          routes: [
            GoRoute(
              path: '/settings',
              builder: (_, __) => BlocProvider(
                create: (_) => sl<StoreSettingsBloc>()
                  ..add(StoreSettingsRequested(sl<StoreContext>().storeId ?? '')),
                child: const StoreSettingsPage(),
              ),
            ),
            GoRoute(
              path: '/catalog',
              builder: (_, __) => BlocProvider(
                create: (_) => sl<CatalogsBloc>()..add(const CatalogsRequested()),
                child: const CatalogsPage(),
              ),
            ),
            GoRoute(
              path: '/catalog/:id',
              builder: (_, state) {
                final catalogId = state.pathParameters['id']!;
                return BlocProvider(
                  create: (_) => sl<ProductsBloc>()..add(ProductsRequested(catalogId)),
                  child: CatalogProductsPage(catalogId: catalogId),
                );
              },
            ),
            GoRoute(
              path: '/inventory',
              builder: (_, __) => BlocProvider(
                create: (_) => sl<InventoryBloc>()..add(const LevelsRequested()),
                child: const InventoryPage(),
              ),
            ),
            GoRoute(
              path: '/pricing',
              builder: (_, __) => BlocProvider(
                create: (_) => sl<PricingBloc>()..add(const PricingRequested()),
                child: const PricingPage(),
              ),
            ),
            GoRoute(
              path: '/orders',
              builder: (_, __) => BlocProvider(
                create: (_) => sl<OrdersBloc>()..add(const OrdersRequested()),
                child: const OrdersPage(),
              ),
            ),
            GoRoute(
              path: '/orders/:id',
              builder: (_, state) {
                final orderId = state.pathParameters['id']!;
                return MultiBlocProvider(
                  providers: [
                    BlocProvider(
                      create: (_) =>
                          sl<OrderDetailBloc>()..add(OrderDetailRequested(orderId)),
                    ),
                    BlocProvider(
                      create: (_) =>
                          sl<ShipmentsBloc>()..add(ShipmentsRequested(orderId)),
                    ),
                  ],
                  child: OrderDetailPage(orderId: orderId),
                );
              },
            ),
            GoRoute(
              path: '/draft-orders',
              builder: (_, __) => BlocProvider(
                create: (_) => sl<DraftsBloc>()..add(const DraftsRequested()),
                child: const DraftsPage(),
              ),
            ),
            GoRoute(
              path: '/store-credit',
              builder: (_, __) => BlocProvider(
                create: (_) => sl<StoreCreditBloc>(),
                child: const StoreCreditPage(),
              ),
            ),
            GoRoute(
              path: '/shipping',
              builder: (_, __) => BlocProvider(
                create: (_) => sl<ShippingBloc>()..add(const ShippingRequested()),
                child: const ShippingPage(),
              ),
            ),
            GoRoute(
              path: '/fleet',
              builder: (_, __) => BlocProvider(
                create: (_) => sl<FleetBloc>()..add(const AgentsRequested()),
                child: const FleetPage(),
              ),
            ),
            GoRoute(
              path: '/fleet/:id',
              builder: (_, state) {
                final agentId = state.pathParameters['id']!;
                return BlocProvider(
                  create: (_) => sl<FleetBloc>()..add(AgentDetailRequested(agentId)),
                  child: AgentReviewPage(agentId: agentId),
                );
              },
            ),
            GoRoute(
              path: '/returns',
              builder: (_, __) => BlocProvider(
                create: (_) => sl<RmasBloc>()..add(const RmasRequested()),
                child: const ReturnsPage(),
              ),
            ),
            GoRoute(
              path: '/returns/:id',
              builder: (_, state) {
                final rmaId = state.pathParameters['id']!;
                return BlocProvider(
                  create: (_) => sl<RmasBloc>()..add(RmaDetailRequested(rmaId)),
                  child: RmaDetailPage(rmaId: rmaId),
                );
              },
            ),
            GoRoute(
              path: '/purchasing',
              builder: (_, __) => BlocProvider(
                create: (_) => sl<PurchasingBloc>()..add(const PurchasingRequested()),
                child: const PurchasingPage(),
              ),
            ),
            GoRoute(
              path: '/psp',
              builder: (_, __) => BlocProvider(
                create: (_) => sl<PspBloc>()..add(const PspStatusRequested()),
                child: const PspPage(),
              ),
            ),
            GoRoute(
              path: '/psp/return/:provider',
              builder: (_, state) => BlocProvider(
                create: (_) => sl<PspBloc>()..add(const PspStatusRequested()),
                child: PspReturnPage(
                  provider: state.pathParameters['provider']!,
                  params: state.uri.queryParameters,
                ),
              ),
            ),
            GoRoute(
              path: '/reports',
              builder: (_, __) => BlocProvider(
                create: (_) => sl<ReportsBloc>()..add(const SalesReportRequested()),
                child: const ReportsPage(),
              ),
            ),
          ],
        ),
      ],
    );
  }
}
