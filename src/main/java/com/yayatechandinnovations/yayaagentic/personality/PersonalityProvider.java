package com.yayatechandinnovations.yayaagentic.personality;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.util.Locale;

public interface PersonalityProvider {
    PersonalityFragment forTenant(Ids.TenantId tenantId, Locale locale);
}
