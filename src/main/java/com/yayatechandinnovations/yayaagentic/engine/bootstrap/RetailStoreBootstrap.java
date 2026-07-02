package com.yayatechandinnovations.yayaagentic.engine.bootstrap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.commerce.Availability;
import com.yayatechandinnovations.yayaagentic.commerce.CatalogLocation;
import com.yayatechandinnovations.yayaagentic.commerce.CatalogSyncPolicy;
import com.yayatechandinnovations.yayaagentic.commerce.CommerceConfig;
import com.yayatechandinnovations.yayaagentic.commerce.Money;
import com.yayatechandinnovations.yayaagentic.commerce.Product;
import com.yayatechandinnovations.yayaagentic.commerce.ProductCatalog;
import com.yayatechandinnovations.yayaagentic.commerce.ProductCatalogRegistry;
import com.yayatechandinnovations.yayaagentic.commerce.tool.CommerceTools;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.knowledge.RetrievalPolicy;
import com.yayatechandinnovations.yayaagentic.persistence.CapabilityEntity;
import com.yayatechandinnovations.yayaagentic.persistence.CapabilityRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ToolEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ToolRepository;
import com.yayatechandinnovations.yayaagentic.profile.Capability;
import com.yayatechandinnovations.yayaagentic.profile.Profile;
import com.yayatechandinnovations.yayaagentic.profile.ProfileRegistry;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandlerRef;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Seeds the conversational-storefront demo end-to-end at startup: the managed
 * commerce Bean tools (browse, cart, and {@code checkout}), three capabilities
 * (browse + cart + checkout), an Inline {@link ProductCatalog} of body-care
 * products, and the {@code retail-store@1} profile carrying a
 * {@link CommerceConfig} (catalog + USD + checkout tool + cart enabled). This is
 * the managed-fallback path (§8) — no merchant wiring — so the playground can
 * exercise browse → filter → cart → tap → checkout immediately. Checkout hands
 * off to {@code DLocalPaymentProvider}, which runs in sandbox mode until
 * credentials are configured (§11).</p>
 */
@Component
@Order(70)
public class RetailStoreBootstrap implements ApplicationRunner {

    public static final Ids.TenantId DEFAULT_TENANT = HelloWorldProfileBootstrap.DEFAULT_TENANT;
    public static final Ids.ProfileId RETAIL_STORE_PROFILE = new Ids.ProfileId("retail-store", 1);
    public static final Ids.ProductCatalogId BODY_CARE_CATALOG = new Ids.ProductCatalogId("body-care", 1);

    public static final Ids.CapabilityId BROWSE_CATALOG = new Ids.CapabilityId("browse-catalog");
    public static final Ids.CapabilityId MANAGE_CART = new Ids.CapabilityId("manage-cart");
    public static final Ids.CapabilityId CHECKOUT = new Ids.CapabilityId("checkout");

    private final M0Catalog catalog;
    private final ProfileRegistry profileRegistry;
    private final ProductCatalogRegistry catalogRegistry;
    private final ToolRepository toolRepo;
    private final CapabilityRepository capabilityRepo;
    private final ObjectMapper json;

    public RetailStoreBootstrap(M0Catalog catalog,
                                ProfileRegistry profileRegistry,
                                ProductCatalogRegistry catalogRegistry,
                                ToolRepository toolRepo,
                                CapabilityRepository capabilityRepo,
                                ObjectMapper json) {
        this.catalog = catalog;
        this.profileRegistry = profileRegistry;
        this.catalogRegistry = catalogRegistry;
        this.toolRepo = toolRepo;
        this.capabilityRepo = capabilityRepo;
        this.json = json;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        CommerceTools.managedDescriptors().forEach(this::registerTool);
        registerCapabilities();
        registerCatalog();
        registerProfile();
    }

    // ---- tools + capabilities ---------------------------------------------

    private void registerTool(ToolDescriptor descriptor) {
        String beanName = ((ToolHandlerRef.Bean) descriptor.handler()).beanName();
        ToolEntity.PK pk = new ToolEntity.PK(DEFAULT_TENANT.value(), descriptor.id().value(), 1);
        if (!toolRepo.existsById(pk)) {
            ToolEntity entity = new ToolEntity(
                    DEFAULT_TENANT.value(), descriptor.id().value(), 1,
                    descriptor.inputSchemaJson(), descriptor.outputSchemaJson(), "BEAN");
            entity.setHandlerBeanName(beanName);
            entity.setPolicyJson(writeJson(descriptor.policy()));
            entity.setRequiresJson(writeJson(PermissionRequirement.none()));
            toolRepo.save(entity);
        }
        catalog.registerTool(descriptor);
    }

    private void registerCapabilities() {
        registerCapability(new Capability(
                BROWSE_CATALOG,
                "Browse the store",
                "Search and filter the product catalog and view product details.",
                "Use search_products when the shopper wants to browse or filter "
                        + "the catalog (by category, ingredient, or a max price in "
                        + "cents). The UI renders results as cards and the shopper "
                        + "can tap facet chips to filter locally — give a one-line "
                        + "caption, never list products or prices in prose. Use "
                        + "get_product when they ask to see one item in detail.",
                List.of(new Ids.ToolId(CommerceTools.SEARCH_PRODUCTS),
                        new Ids.ToolId(CommerceTools.GET_PRODUCT)),
                List.of("show me soaps", "anything with castor oil", "gifts under $20"),
                PermissionRequirement.none()));

        registerCapability(new Capability(
                MANAGE_CART,
                "Manage my cart",
                "Add, update, remove, or clear cart items and view the cart.",
                "Use add_to_cart with a productId and qty — NEVER a price; the "
                        + "catalog is the authoritative source of price. Use "
                        + "view_cart, set_cart_quantity, remove_from_cart, and "
                        + "clear_cart to edit. The cart renders as a widget with a "
                        + "running total; summarize what changed, don't enumerate "
                        + "the lines or restate prices.",
                List.of(new Ids.ToolId(CommerceTools.ADD_TO_CART),
                        new Ids.ToolId(CommerceTools.REMOVE_FROM_CART),
                        new Ids.ToolId(CommerceTools.SET_CART_QUANTITY),
                        new Ids.ToolId(CommerceTools.VIEW_CART),
                        new Ids.ToolId(CommerceTools.CLEAR_CART)),
                List.of("view my cart", "keep shopping"),
                PermissionRequirement.none()));

        registerCapability(new Capability(
                CHECKOUT,
                "Check out",
                "Place the order and hand off to secure payment.",
                "Use checkout when the shopper is ready to pay. It is confirmable "
                        + "— the engine will show a confirm prompt with the total "
                        + "before anything is charged, and payment happens on the "
                        + "PSP's secure page, never here. Never quote or invent a "
                        + "total; the cart's total is authoritative.",
                List.of(new Ids.ToolId(CommerceTools.CHECKOUT)),
                List.of("checkout", "keep shopping"),
                PermissionRequirement.none()));
    }

    private void registerCapability(Capability cap) {
        CapabilityEntity.PK pk = new CapabilityEntity.PK(DEFAULT_TENANT.value(), cap.id().value(), 1);
        if (!capabilityRepo.existsById(pk)) {
            CapabilityEntity entity = new CapabilityEntity(
                    DEFAULT_TENANT.value(), cap.id().value(), 1, cap.userFacingLabel());
            entity.setDescription(cap.userFacingDescription());
            entity.setLlmGuidance(cap.llmGuidance());
            entity.setToolIdsJson(writeJson(cap.tools().stream().map(Ids.ToolId::value).toList()));
            entity.setFollowUpHintsJson(writeJson(cap.followUpHints()));
            entity.setRequiresJson(writeJson(PermissionRequirement.none()));
            capabilityRepo.save(entity);
        }
        catalog.registerCapability(cap);
    }

    // ---- catalog ----------------------------------------------------------

    private void registerCatalog() {
        if (catalogRegistry.find(DEFAULT_TENANT, BODY_CARE_CATALOG).isPresent()) return;
        catalogRegistry.register(new ProductCatalog(
                BODY_CARE_CATALOG, DEFAULT_TENANT, "Body Care",
                new CatalogLocation.Inline(bodyCareProducts()),
                CatalogSyncPolicy.live(), RetrievalPolicy.defaults(),
                PermissionRequirement.none(), "USD"));
    }

    // ---- profile ----------------------------------------------------------

    private void registerProfile() {
        if (profileRegistry.find(DEFAULT_TENANT, RETAIL_STORE_PROFILE).isPresent()) return;
        profileRegistry.register(new Profile(
                RETAIL_STORE_PROFILE,
                DEFAULT_TENANT,
                "Yaya Store",
                "Welcome! Browse our body-care range and I'll help you find and buy what you need.",
                "You are Yaya, a friendly storefront assistant for a body-care "
                        + "shop. Help shoppers browse, filter, and buy. When you "
                        + "surface products or the cart, the UI renders them as "
                        + "cards and a cart widget — give a short, warm caption and "
                        + "do NOT list products, prices, or cart contents in prose. "
                        + "Never invent a product, a price, or stock — only what a "
                        + "tool returned is real, and prices come from the catalog, "
                        + "never from you. Add to the cart with productId + qty. If "
                        + "a tool is denied, paraphrase the user-safe reason only.",
                List.of(BROWSE_CATALOG, MANAGE_CART, CHECKOUT),
                List.of(),
                HelloWorldProfileBootstrap.DEV_NOOP_AUTH,
                "en",
                Map.of("introQuickReplies", List.of(
                        "show me soaps", "anything with castor oil", "gifts under $20")),
                new CommerceConfig(List.of(BODY_CARE_CATALOG), "USD",
                        new Ids.ToolId(CommerceTools.CHECKOUT), true)));
    }

    // ---- inline products --------------------------------------------------

    private static List<Product> bodyCareProducts() {
        return List.of(
                product("p_castor_soap", "Castor Oil Soap", 899, "soap", "castor oil"),
                product("p_shea_soap", "Shea Butter Soap", 999, "soap", "shea"),
                product("p_coconut_soap", "Coconut Milk Soap", 799, "soap", "coconut"),
                product("p_castor_oil", "Cold-Pressed Castor Oil", 1599, "oil", "castor oil"),
                product("p_argan_oil", "Argan Hair Oil", 2499, "oil", "argan"),
                product("p_shea_balm", "Shea Body Balm", 1899, "balm", "shea"),
                product("p_lip_balm", "Coconut Lip Balm", 499, "balm", "coconut"),
                product("p_gift_set", "Body Care Gift Set", 4999, "set", "mixed"));
    }

    private static Product product(String id, String title, long minor, String form, String ingredient) {
        return new Product(
                new Ids.ProductId(id),
                title,
                title + " — small-batch, gentle, plant-based.",
                new Money(minor, "USD"),
                List.of("https://cdn.example.com/body-care/" + id + ".jpg"),
                form,
                Map.of("form", form, "ingredient", ingredient),
                List.of(),
                Availability.IN_STOCK,
                null);
    }

    private String writeJson(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
