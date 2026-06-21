package com.yayatechandinnovations.yayaagentic.user_accounts;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Build-failing structural check: any JPA repository that maps to a
 * workspace-scoped entity (i.e. one with a {@code tenant_id} /
 * {@code workspace_id} column) must EITHER carry {@link WorkspaceUnscoped}
 * on the type, OR have every custom query method declare a parameter
 * named {@code tenant*} / {@code workspace*}.
 *
 * <p>The escape hatch covers the legitimate cross-workspace cases (users,
 * memberships, invitations, audit cross-tenant reads for platform
 * admins, mail outbox, session storage).</p>
 *
 * <p>PRD acceptance criterion AC-8.</p>
 */
class RepositoryScanTest {

    private static final String REPO_PACKAGE = "com.yayatechandinnovations.yayaagentic.persistence";

    private static final Pattern TENANT_PARAM_NAME = Pattern.compile(
            "^(tenant.*|workspace.*|TenantId|WorkspaceContext)$");

    /** Method names that come from {@link JpaRepository} and similar inherited bases — not user-defined. */
    private static final Set<String> INHERITED_METHODS = Set.of(
            "save", "saveAll", "saveAndFlush", "saveAllAndFlush", "flush",
            "findById", "existsById", "findAll", "findAllById", "count",
            "deleteById", "delete", "deleteAll", "deleteAllById", "deleteAllInBatch",
            "deleteAllByIdInBatch", "deleteInBatch", "getById", "getOne",
            "getReferenceById", "findOne", "findAllAsStream", "exists", "findBy");

    @Test
    void every_workspace_scoped_repository_either_takes_tenant_args_or_is_marked_unscoped() {
        List<String> violations = new ArrayList<>();
        for (Class<?> repoClass : findRepositoryInterfaces()) {
            Class<?> entityClass = inferEntityClass(repoClass);
            if (entityClass == null) continue;
            if (!entityHasWorkspaceColumn(entityClass)) continue;
            if (repoClass.isAnnotationPresent(WorkspaceUnscoped.class)) continue;

            for (Method m : repoClass.getDeclaredMethods()) {
                if (m.isSynthetic() || m.isBridge()) continue;
                if (m.isAnnotationPresent(WorkspaceUnscoped.class)) continue;
                if (INHERITED_METHODS.contains(m.getName())) continue;
                if (hasTenantLikeParameter(m)) continue;
                violations.add(repoClass.getSimpleName() + "#" + m.getName()
                        + " maps a workspace-scoped entity but takes no tenant/workspace parameter "
                        + "and is not @WorkspaceUnscoped");
            }
        }
        assertThat(violations)
                .as("Every @Repository over a workspace-scoped entity must scope its custom queries "
                        + "or carry @WorkspaceUnscoped. Add the annotation only when the read truly "
                        + "must cross workspaces (e.g. platform-admin paths, token-bearer lookups).")
                .isEmpty();
    }

    // ------------------------------------------------------------------

    private static List<Class<?>> findRepositoryInterfaces() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(
                            org.springframework.beans.factory.annotation.AnnotatedBeanDefinition beanDefinition) {
                        // Allow interface candidates — the default rejects them.
                        return beanDefinition.getMetadata().isIndependent();
                    }
                };
        scanner.setResourceLoader(new PathMatchingResourcePatternResolver());
        scanner.addIncludeFilter(new AssignableTypeFilter(JpaRepository.class));

        List<Class<?>> out = new ArrayList<>();
        for (var bd : scanner.findCandidateComponents(REPO_PACKAGE)) {
            try {
                Class<?> c = Class.forName(bd.getBeanClassName());
                if (JpaRepository.class.isAssignableFrom(c) && c.isInterface()) {
                    out.add(c);
                }
            } catch (ClassNotFoundException ignored) {
                // skip
            }
        }
        // Stable ordering helps the failure message read consistently.
        out.sort((a, b) -> a.getSimpleName().compareTo(b.getSimpleName()));
        return out;
    }

    /** Walk the repository's superinterfaces and pull the first generic Entity type out of JpaRepository<E, ID>. */
    private static Class<?> inferEntityClass(Class<?> repoClass) {
        for (Type t : repoClass.getGenericInterfaces()) {
            if (!(t instanceof ParameterizedType pt)) continue;
            if (pt.getRawType() instanceof Class<?> raw && JpaRepository.class.isAssignableFrom(raw)) {
                Type entityType = pt.getActualTypeArguments()[0];
                if (entityType instanceof Class<?> entity) return entity;
            }
        }
        // Recurse into super-interfaces (e.g. a hand-written intermediate).
        for (Class<?> superIface : repoClass.getInterfaces()) {
            Class<?> e = inferEntityClass(superIface);
            if (e != null) return e;
        }
        return null;
    }

    private static boolean entityHasWorkspaceColumn(Class<?> entityClass) {
        return Stream.of(entityClass.getDeclaredFields())
                .anyMatch(f -> {
                    if (f.isAnnotationPresent(jakarta.persistence.Column.class)) {
                        String name = f.getAnnotation(jakarta.persistence.Column.class).name();
                        if ("tenant_id".equals(name) || "workspace_id".equals(name)) return true;
                    }
                    String fname = f.getName();
                    return "tenantId".equals(fname) || "workspaceId".equals(fname);
                });
    }

    private static boolean hasTenantLikeParameter(Method m) {
        for (java.lang.reflect.Parameter p : m.getParameters()) {
            // Name-based check (handles @Param-annotated string args).
            if (TENANT_PARAM_NAME.matcher(p.getName()).matches()) return true;
            // Type-based check (handles WorkspaceContext / TenantId positional args).
            String typeName = p.getType().getSimpleName();
            if ("TenantId".equals(typeName) || "WorkspaceContext".equals(typeName)) return true;
        }
        return false;
    }

    @SuppressWarnings("unused")
    private static MetadataReaderFactory metadataFactory(ResourcePatternResolver loader) {
        return new CachingMetadataReaderFactory(loader);
    }

    @SuppressWarnings("unused")
    private static Set<String> dedup(Iterable<String> in) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : in) out.add(s);
        return out;
    }
}
