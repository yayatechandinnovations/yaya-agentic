package com.yayatechandinnovations.yayaagentic.profile.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.ProfileEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ProfileRepository;
import com.yayatechandinnovations.yayaagentic.profile.Profile;
import com.yayatechandinnovations.yayaagentic.profile.ProfileRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * M1 ProfileRegistry. Backed by JPA against the {@code profiles} table.
 * Reads always return the requested version; M5 adds the "latest active"
 * resolver. See design §5.2.
 */
@Component
@ConditionalOnMissingBean(value = ProfileRegistry.class, ignored = PostgresProfileRegistry.class)
public class PostgresProfileRegistry implements ProfileRegistry {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> STRING_MAP = new TypeReference<>() {};

    private final ProfileRepository repo;
    private final ObjectMapper json;

    public PostgresProfileRegistry(ProfileRepository repo, ObjectMapper json) {
        this.repo = repo;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Profile> find(Ids.TenantId tenant, Ids.ProfileId profile) {
        return repo.findById(new ProfileEntity.PK(tenant.value(), profile.value(), profile.version()))
                .map(this::toRecord);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Profile> listFor(Ids.TenantId tenant) {
        return repo.findByTenantId(tenant.value()).stream().map(this::toRecord).toList();
    }

    @Override
    @Transactional
    public void register(Profile profile) {
        ProfileEntity.PK pk = new ProfileEntity.PK(
                profile.tenant().value(), profile.id().value(), profile.id().version());
        ProfileEntity entity = repo.findById(pk).orElseGet(() -> new ProfileEntity(
                profile.tenant().value(),
                profile.id().value(),
                profile.id().version(),
                profile.displayName(),
                profile.introOneLiner(),
                profile.systemPromptFragment()));

        entity.setDisplayName(profile.displayName());
        entity.setIntro(profile.introOneLiner());
        entity.setSystemPrompt(profile.systemPromptFragment());
        entity.setCapabilitiesJson(writeJson(
                profile.capabilities().stream().map(Ids.CapabilityId::value).toList()));
        entity.setMetadataJson(writeJson(profile.metadata()));
        entity.setAuthBindingId(profile.authBinding() == null ? null : profile.authBinding().value());
        entity.setLanguage(profile.language());
        repo.save(entity);
    }

    private Profile toRecord(ProfileEntity e) {
        List<String> capIds = readJson(e.getCapabilitiesJson(), STRING_LIST);
        Map<String, Object> metadata = readJson(e.getMetadataJson(), STRING_MAP);
        return new Profile(
                new Ids.ProfileId(e.getId(), e.getVersion()),
                new Ids.TenantId(e.getTenantId()),
                e.getDisplayName(),
                e.getIntro(),
                e.getSystemPrompt(),
                capIds.stream().map(Ids.CapabilityId::new).toList(),
                List.of(),                                              // knowledge bindings join in M2.5
                e.getAuthBindingId() == null ? null : new Ids.AuthBindingId(e.getAuthBindingId()),
                e.getLanguage(),
                metadata);
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to encode JSON for profile column", ex);
        }
    }

    private <T> T readJson(String raw, TypeReference<T> type) {
        try {
            return json.readValue(raw, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to decode JSON from profile column", ex);
        }
    }
}
