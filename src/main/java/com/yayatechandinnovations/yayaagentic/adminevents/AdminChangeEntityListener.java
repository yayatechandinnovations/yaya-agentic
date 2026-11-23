package com.yayatechandinnovations.yayaagentic.adminevents;

import com.yayatechandinnovations.yayaagentic.persistence.*;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostCommitDeleteEventListener;
import org.hibernate.event.spi.PostCommitInsertEventListener;
import org.hibernate.event.spi.PostCommitUpdateEventListener;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.persister.entity.EntityPersister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns committed writes of admin-visible entities into {@link AdminChange}s.
 * Post-commit only: a rolled-back save never reaches the console. Bulk JPQL
 * updates bypass Hibernate events; the console's focus/staleness refetch is
 * the backstop for those.
 */
@Component
class AdminChangeEntityListener
        implements PostCommitInsertEventListener, PostCommitUpdateEventListener, PostCommitDeleteEventListener {

    private static final Logger log = LoggerFactory.getLogger(AdminChangeEntityListener.class);

    /**
     * How an entity maps to a console resource. {@code idGetter} names the row
     * the console knows the resource by (an ingestion job reports its source);
     * {@code tenantGetter} null means a platform-level resource.
     */
    record Mapping(String kind, String idGetter, String tenantGetter) {
        static Mapping tenantScoped(String kind) { return new Mapping(kind, "getId", "getTenantId"); }
        static Mapping tenantScoped(String kind, String idGetter) { return new Mapping(kind, idGetter, "getTenantId"); }
        static Mapping platform(String kind) { return new Mapping(kind, null, null); }
    }

    // Conversation-plane entities (sessions, turns, messages) are deliberately
    // absent except ChannelSessionEntity, which backs the Chats screen; the
    // feed coalesces its frequent last-activity updates.
    static final Map<Class<?>, Mapping> MAPPINGS = Map.ofEntries(
            Map.entry(TenantEntity.class, Mapping.platform("TENANT")),
            Map.entry(ProfileEntity.class, Mapping.tenantScoped("PROFILE")),
            Map.entry(ProfileKnowledgeBindingEntity.class, Mapping.tenantScoped("PROFILE", "getProfileId")),
            Map.entry(PersonalityFragmentEntity.class, Mapping.tenantScoped("PERSONALITY")),
            Map.entry(CapabilityEntity.class, Mapping.tenantScoped("CAPABILITY")),
            Map.entry(ToolEntity.class, Mapping.tenantScoped("TOOL")),
            Map.entry(KnowledgeSourceEntity.class, Mapping.tenantScoped("KNOWLEDGE_SOURCE")),
            Map.entry(KnowledgeIngestionJobEntity.class, Mapping.tenantScoped("KNOWLEDGE_SOURCE", "getSourceId")),
            Map.entry(KnowledgeDocumentEntity.class, Mapping.tenantScoped("KNOWLEDGE_SOURCE", "getSourceId")),
            Map.entry(AuthBindingEntity.class, Mapping.tenantScoped("AUTH_BINDING")),
            Map.entry(RecordingStrategyEntity.class, Mapping.tenantScoped("RECORDING_STRATEGY", "getScopeId")),
            Map.entry(TenantOidcIssuerEntity.class, Mapping.tenantScoped("OIDC_ISSUER")),
            Map.entry(TenantJwtSecretEntity.class, Mapping.tenantScoped("JWT_SECRET")),
            Map.entry(TenantServiceTokenSecretEntity.class, Mapping.tenantScoped("SERVICE_TOKEN", null)),
            Map.entry(ChannelAuthResolverEntity.class, Mapping.tenantScoped("AUTH_RESOLVER")),
            Map.entry(IdentityProfileMappingEntity.class, Mapping.tenantScoped("IDENTITY_ROUTE")),
            Map.entry(StartFlowEntity.class, Mapping.tenantScoped("START_FLOW")),
            Map.entry(TenantNetworkSettingsEntity.class, Mapping.tenantScoped("NETWORK_SETTINGS", null)),
            Map.entry(WhatsAppSenderEntity.class, Mapping.tenantScoped("WHATSAPP_SENDER")),
            Map.entry(WhatsAppOnboardingAttemptEntity.class, Mapping.tenantScoped("WHATSAPP_SENDER", "getSenderId")),
            Map.entry(WhatsAppMessagingAccountEntity.class, Mapping.tenantScoped("WHATSAPP_SENDER", null)),
            Map.entry(WhatsAppKeywordActionEntity.class, Mapping.tenantScoped("WHATSAPP_SENDER", "getSenderId")),
            Map.entry(WhatsAppTemplateHolderEntity.class, Mapping.tenantScoped("WHATSAPP_TEMPLATE", null)),
            Map.entry(WhatsAppTemplateEntity.class, Mapping.platform("WHATSAPP_TEMPLATE")),
            Map.entry(ChannelBindingEntity.class, Mapping.tenantScoped("CHANNEL_BINDING")),
            Map.entry(ChatEntryPointEntity.class, Mapping.tenantScoped("CHAT_ENTRY_POINT")),
            Map.entry(ChannelSessionEntity.class, Mapping.tenantScoped("CHAT")),
            Map.entry(SmsBrandEntity.class, Mapping.tenantScoped("SMS")),
            Map.entry(SmsCampaignEntity.class, Mapping.tenantScoped("SMS")),
            Map.entry(SmsSenderEntity.class, Mapping.tenantScoped("SMS")),
            Map.entry(SystemSenderEntity.class, Mapping.tenantScoped("SYSTEM_SENDER")),
            Map.entry(ReceiptTemplateEntity.class, Mapping.tenantScoped("RECEIPT_TEMPLATE")),
            Map.entry(ForumPostEntity.class, Mapping.tenantScoped("FORUM")),
            Map.entry(ForumSettingsEntity.class, Mapping.tenantScoped("FORUM", null)),
            Map.entry(ForumReportEntity.class, Mapping.platform("FORUM")),
            Map.entry(ChannelOptOutEntity.class, Mapping.platform("OPT_OUT")),
            Map.entry(ApiKeyEntity.class, new Mapping("API_KEY", "getId", "getWorkspaceId")),
            Map.entry(OperatorAuthConfigEntity.class, Mapping.platform("OPERATOR_AUTH"))
    );

    private final EntityManagerFactory emf;
    private final AdminChangeFeed feed;
    private final Map<String, Optional<Method>> getters = new ConcurrentHashMap<>();

    AdminChangeEntityListener(EntityManagerFactory emf, AdminChangeFeed feed) {
        this.emf = emf;
        this.feed = feed;
    }

    @PostConstruct
    void register() {
        EventListenerRegistry registry = emf.unwrap(SessionFactoryImplementor.class)
                .getServiceRegistry().getService(EventListenerRegistry.class);
        registry.appendListeners(EventType.POST_COMMIT_INSERT, this);
        registry.appendListeners(EventType.POST_COMMIT_UPDATE, this);
        registry.appendListeners(EventType.POST_COMMIT_DELETE, this);
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return MAPPINGS.containsKey(persister.getMappedClass());
    }

    @Override public void onPostInsert(PostInsertEvent e) { emit(e.getEntity(), e.getPersister(), AdminChange.Op.CREATED); }
    @Override public void onPostUpdate(PostUpdateEvent e) { emit(e.getEntity(), e.getPersister(), AdminChange.Op.UPDATED); }
    @Override public void onPostDelete(PostDeleteEvent e) { emit(e.getEntity(), e.getPersister(), AdminChange.Op.DELETED); }

    @Override public void onPostInsertCommitFailed(PostInsertEvent e) { }
    @Override public void onPostUpdateCommitFailed(PostUpdateEvent e) { }
    @Override public void onPostDeleteCommitFailed(PostDeleteEvent e) { }

    private void emit(Object entity, EntityPersister persister, AdminChange.Op op) {
        emit(entity, persister.getMappedClass(), op);
    }

    void emit(Object entity, Class<?> mappedClass, AdminChange.Op op) {
        if (entity == null) return;
        Mapping m = MAPPINGS.get(mappedClass);
        if (m == null) return;
        try {
            String tenant = m.tenantGetter() == null ? null : string(call(entity, m.tenantGetter()));
            String id = m.idGetter() == null ? null : string(call(entity, m.idGetter()));
            Object v = call(entity, "getVersion");
            Integer version = v instanceof Number n ? n.intValue() : null;
            feed.publish(tenant, m.kind(), id, version, op);
        } catch (RuntimeException ex) {
            // A notification must never fail the write it describes.
            log.warn("admin change feed: could not describe {} ({})", entity.getClass().getSimpleName(), ex.toString());
        }
    }

    private Object call(Object entity, String getter) {
        String key = entity.getClass().getName() + '#' + getter;
        Optional<Method> method = getters.computeIfAbsent(key, k -> {
            try {
                return Optional.of(entity.getClass().getMethod(getter));
            } catch (NoSuchMethodException e) {
                return Optional.empty();
            }
        });
        if (method.isEmpty()) return null;
        try {
            return method.get().invoke(entity);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String string(Object o) {
        return o == null ? null : o.toString();
    }
}
