package com.yayatechandinnovations.yayaagentic.engine.bootstrap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.channel.ChannelEndpointRef;
import com.yayatechandinnovations.yayaagentic.channel.binding.ChannelBindingService;
import com.yayatechandinnovations.yayaagentic.channel.binding.OnOverrideDenied;
import com.yayatechandinnovations.yayaagentic.channel.binding.OnUnresolvedPolicy;
import com.yayatechandinnovations.yayaagentic.channel.binding.RoutingRule;
import com.yayatechandinnovations.yayaagentic.channel.binding.SessionPolicy;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPointId;
import com.yayatechandinnovations.yayaagentic.commerce.Availability;
import com.yayatechandinnovations.yayaagentic.commerce.CatalogLocation;
import com.yayatechandinnovations.yayaagentic.commerce.CatalogSyncPolicy;
import com.yayatechandinnovations.yayaagentic.commerce.CommerceConfig;
import com.yayatechandinnovations.yayaagentic.commerce.ManagedProduct;
import com.yayatechandinnovations.yayaagentic.commerce.Money;
import com.yayatechandinnovations.yayaagentic.commerce.Product;
import com.yayatechandinnovations.yayaagentic.commerce.ProductCatalog;
import com.yayatechandinnovations.yayaagentic.commerce.ProductCatalogRegistry;
import com.yayatechandinnovations.yayaagentic.commerce.ProductRegistry;
import com.yayatechandinnovations.yayaagentic.commerce.tool.CommerceTools;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.knowledge.RetrievalPolicy;
import com.yayatechandinnovations.yayaagentic.persistence.ChatEntryPointEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatEntryPointRepository;
import com.yayatechandinnovations.yayaagentic.profile.Profile;
import com.yayatechandinnovations.yayaagentic.profile.ProfileRegistry;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Seeds a ready-to-demo storefront end-to-end so a fresh install always has a
 * clickable sample store for presentations. Unlike {@link RetailStoreBootstrap}
 * (an Inline catalog for the playground), this exercises the managed path from
 * the commerce redesign: a {@code Database} catalog backed by the {@code
 * products} table (so the admin "Manage products" table, CSV import, and
 * shopper storefront card all light up), plus a real chat entry point + its
 * {@code MatchAny} binding so the store is reachable from the end-user chat
 * front door at handle {@code demo-store}.
 *
 * <p>The shared commerce Bean tools and the browse/cart/checkout capabilities
 * are registered by {@link RetailStoreBootstrap} (@Order 70); this runs after
 * and reuses them. Every step is guarded (registry lookup / handle lookup), so
 * the seed is idempotent — it plants the demo on an empty DB and thereafter
 * leaves operator edits alone. See
 * {@code commerce-redesign-authoring-rendering-paypal.md} Pillar A.
 */
@Component
@Order(80)
public class DemoStoreBootstrap implements ApplicationRunner {

    public static final Ids.TenantId DEMO_TENANT = HelloWorldProfileBootstrap.DEFAULT_TENANT;
    public static final Ids.ProfileId DEMO_STORE_PROFILE = new Ids.ProfileId("demo-store", 1);
    public static final Ids.ProductCatalogId DEMO_STORE_CATALOG = new Ids.ProductCatalogId("demo-store", 1);
    public static final String DEMO_STORE_HANDLE = "demo-store";

    // The logical row set in the products table (== the catalog id value, so the
    // admin "Manage products" screen and DatabaseCatalogLoader agree).
    private static final String CATALOG_KEY = "demo-store";
    private static final String ENTRY_POINT_ID = "chat_demostore";
    private static final String CURRENCY = "USD";
    private static final String IMG = "https://picsum.photos/seed/";

    private final ProfileRegistry profileRegistry;
    private final ProductCatalogRegistry catalogRegistry;
    private final ProductRegistry products;
    private final ChatEntryPointRepository entryPoints;
    private final ChannelBindingService bindings;
    private final ObjectMapper json;

    public DemoStoreBootstrap(ProfileRegistry profileRegistry,
                              ProductCatalogRegistry catalogRegistry,
                              ProductRegistry products,
                              ChatEntryPointRepository entryPoints,
                              ChannelBindingService bindings,
                              ObjectMapper json) {
        this.profileRegistry = profileRegistry;
        this.catalogRegistry = catalogRegistry;
        this.products = products;
        this.entryPoints = entryPoints;
        this.bindings = bindings;
        this.json = json;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedInto(DEMO_TENANT);
    }

    /** Seed the managed demo storefront (Database catalog + products + commerce
     *  profile + chat entry point/binding) into {@code tenant}. Idempotent; reused
     *  by the per-workspace demo provisioner so every account gets a demo store.
     *  The chat-entry-point handle is per-tenant (the handle column is globally
     *  unique) — {@code demo-store} for the default tenant, {@code <slug>-demo-store}
     *  otherwise. */
    @Transactional
    public void seedInto(Ids.TenantId tenant) {
        seedCatalogAndProducts(tenant);
        seedProfile(tenant);
        seedChatEntryPoint(tenant);
    }

    // ---- catalog + products (managed / Database path) ---------------------

    private void seedCatalogAndProducts(Ids.TenantId tenant) {
        if (catalogRegistry.find(tenant, DEMO_STORE_CATALOG).isEmpty()) {
            catalogRegistry.register(new ProductCatalog(
                    DEMO_STORE_CATALOG, tenant, "Aroma Coffee Roasters",
                    new CatalogLocation.Database(CATALOG_KEY),
                    CatalogSyncPolicy.live(), RetrievalPolicy.defaults(),
                    PermissionRequirement.none(), CURRENCY,
                    IMG + "aroma-coffee/1200/600"));
        }
        // Seed products when the catalog has none — covers a fresh catalog AND
        // self-heals a stale/partial one (exists but empty, e.g. an interrupted
        // earlier seed). Guarding on countActive==0 never clobbers operator edits:
        // once at least one product is active, the demo set is the operator's.
        if (products.countActive(tenant, CATALOG_KEY) == 0) {
            products.saveAll(demoProducts(tenant));
        }
    }

    private static List<ManagedProduct> demoProducts(Ids.TenantId tenant) {
        // Builders below stamp DEMO_TENANT; remap onto the target tenant so the
        // same catalog seeds into any account (CATALOG_KEY is tenant-independent).
        return catalogTemplates().stream()
                .map(p -> new ManagedProduct(tenant, p.catalogId(), p.product(),
                        p.longDescription(), p.inventory(), p.status(), p.sortOrder(), p.updatedAt()))
                .toList();
    }

    private static List<ManagedProduct> catalogTemplates() {
        int s = 0;
        return List.of(
                bean(++s, "p_ethiopia", "Ethiopia Yirgacheffe", 1699,
                        "light", "Ethiopia", "Floral and bright, with jasmine and citrus.", 42),
                bean(++s, "p_colombia", "Colombia Supremo", 1499,
                        "medium", "Colombia", "Balanced and smooth, caramel and red apple.", 60),
                bean(++s, "p_sumatra", "Sumatra Mandheling", 1599,
                        "dark", "Indonesia", "Full-bodied and earthy, dark chocolate and cedar.", 28),
                bean(++s, "p_espresso", "House Espresso Blend", 1799,
                        "dark", "Blend", "Our signature shot: cocoa, brown sugar, a syrupy body.", 75),
                bean(++s, "p_decaf", "Swiss Water Decaf", 1599,
                        "medium", "Colombia", "All the flavour, none of the caffeine.", 33),
                gear(++s, "p_v60", "Ceramic V60 Dripper", 2499,
                        "pour-over", "A classic cone dripper for a clean, bright cup.", 18, Availability.IN_STOCK),
                gear(++s, "p_french_press", "Glass French Press", 3299,
                        "immersion", "800ml borosilicate press for a rich, full body.", 12, Availability.IN_STOCK),
                gear(++s, "p_aeropress", "AeroPress Go", 3999,
                        "pressure", "Travel-ready brewer — smooth coffee anywhere.", 6, Availability.LOW),
                gear(++s, "p_kettle", "Gooseneck Kettle 0.9L", 5499,
                        "pour-over", "Precise pour control with a built-in thermometer.", 9, Availability.IN_STOCK),
                gear(++s, "p_grinder", "Hand Burr Grinder", 4599,
                        "grinding", "Conical burrs, 40 clicks — espresso to French press.", 0, Availability.OUT),
                accessory(++s, "p_filters", "Paper Filters (100)", 799,
                        "100 natural paper filters for cone drippers.", 140),
                gift(++s, "p_gift_box", "Roaster's Gift Box", 5999,
                        "Two 250g single-origins, a V60, and filters — beautifully boxed.", 20));
    }

    /** Whole-bean coffee, sold by weight — 250g / 1kg variants; facets: roast + origin. */
    private static ManagedProduct bean(int sort, String id, String title, long price250,
                                       String roast, String origin, String longDesc, int inventory) {
        List<Product.Variant> variants = List.of(
                new Product.Variant(id + "_250g", "250g", new Money(price250, CURRENCY),
                        id + "-250", Availability.IN_STOCK),
                new Product.Variant(id + "_1kg", "1kg", new Money(price250 * 3 + 500, CURRENCY),
                        id + "-1kg", Availability.IN_STOCK));
        return managed(sort, id, title,
                roast + " roast · " + origin, price250, "beans",
                Map.of("roast", roast, "origin", origin),
                variants, Availability.IN_STOCK, inventory, longDesc);
    }

    /** Brewing equipment — no variants; facet: method. */
    private static ManagedProduct gear(int sort, String id, String title, long price,
                                       String method, String longDesc, int inventory, Availability avail) {
        return managed(sort, id, title,
                "Brewing gear · " + method, price, "equipment",
                Map.of("method", method), List.of(), avail, inventory, longDesc);
    }

    private static ManagedProduct accessory(int sort, String id, String title, long price,
                                            String longDesc, int inventory) {
        return managed(sort, id, title, "Accessory", price, "accessories",
                Map.of(), List.of(), Availability.IN_STOCK, inventory, longDesc);
    }

    private static ManagedProduct gift(int sort, String id, String title, long price,
                                       String longDesc, int inventory) {
        return managed(sort, id, title, "Gift set", price, "gifts",
                Map.of(), List.of(), Availability.IN_STOCK, inventory, longDesc);
    }

    private static ManagedProduct managed(int sort, String id, String title, String shortDesc,
                                          long price, String category, Map<String, String> attrs,
                                          List<Product.Variant> variants, Availability avail,
                                          int inventory, String longDesc) {
        Product product = new Product(
                new Ids.ProductId(id), title, shortDesc,
                new Money(price, CURRENCY),
                List.of(IMG + id + "/600/600"),
                category, attrs, variants, avail, null);
        return new ManagedProduct(DEMO_TENANT, CATALOG_KEY, product,
                longDesc, inventory, ManagedProduct.ACTIVE, sort, null);
    }

    // ---- profile ----------------------------------------------------------

    private void seedProfile(Ids.TenantId tenant) {
        if (profileRegistry.find(tenant, DEMO_STORE_PROFILE).isPresent()) return;
        profileRegistry.register(new Profile(
                DEMO_STORE_PROFILE,
                tenant,
                "Aroma Coffee Roasters",
                "Welcome to Aroma! Tell me how you brew and I'll help you find your beans.",
                "You are the shopping assistant for Aroma Coffee Roasters, a specialty "
                        + "coffee store. Help shoppers browse beans and brewing gear, filter by "
                        + "roast, origin, or brew method, build a cart, and check out. When you "
                        + "surface products or the cart, the UI renders them as cards and a cart "
                        + "widget — give a short, warm caption and do NOT list products, prices, "
                        + "or cart contents in prose. Never invent a product, a price, or stock — "
                        + "only what a tool returned is real, and prices come from the catalog, "
                        + "never from you. Add to the cart with productId + qty. If a tool is "
                        + "denied, paraphrase the user-safe reason only.",
                List.of(RetailStoreBootstrap.BROWSE_CATALOG,
                        RetailStoreBootstrap.MANAGE_CART,
                        RetailStoreBootstrap.CHECKOUT),
                List.of(),
                HelloWorldProfileBootstrap.DEV_NOOP_AUTH,
                "en",
                Map.of("introQuickReplies", List.of(
                        "show me light roasts", "beans from Ethiopia", "pour-over gear", "gifts under $60")),
                new CommerceConfig(List.of(DEMO_STORE_CATALOG), CURRENCY,
                        new Ids.ToolId(CommerceTools.CHECKOUT), true,
                        /* connectedAccountId */ null,
                        /* paymentProvider */ null)));  // framework default PSP (switchable per-profile in admin)
    }

    // ---- chat entry point (the reachable channel) -------------------------

    private void seedChatEntryPoint(Ids.TenantId tenant) {
        boolean isDefault = DEMO_TENANT.value().equals(tenant.value());
        // handle is globally unique — scope it per tenant for non-default accounts.
        String handle = isDefault ? DEMO_STORE_HANDLE : tenant.value() + "-demo-store";
        String entryPointId = isDefault ? ENTRY_POINT_ID : "chat_demostore_" + tenant.value();
        if (entryPoints.findByHandle(handle).isPresent()) return;

        ChatEntryPointEntity e = new ChatEntryPointEntity(
                entryPointId, tenant.value(), handle,
                DEMO_STORE_PROFILE.value(), DEMO_STORE_PROFILE.version(),
                "Aroma Coffee Roasters",
                IMG + "aroma-logo/200/200",
                writeJson(SessionPolicy.defaults()),
                "ACTIVE");
        e.setAccentColorHex("#6F4E37");
        e.setWelcomeText("Welcome to Aroma! Tell me how you brew and I'll find your beans.");
        e.setTagline("Specialty coffee, roasted to order.");
        e.setDiscoverable(true);
        entryPoints.save(e);

        // The MatchAny binding pins the entry point to the profile so
        // ChannelProfileResolver routes it the instant it is ACTIVE.
        bindings.create(tenant,
                new ChannelEndpointRef.ChatEntryPointRef(new ChatEntryPointId(entryPointId)),
                DEMO_STORE_PROFILE,
                Optional.of(DEMO_STORE_PROFILE.version()),
                new RoutingRule.MatchAny(),
                SessionPolicy.defaults(),
                100,
                /* authResolverId */ null,
                OnUnresolvedPolicy.ANONYMOUS,
                Set.of(), OnOverrideDenied.IGNORE);
    }

    private String writeJson(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to encode JSON", ex);
        }
    }
}
