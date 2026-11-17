package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessProfileTest {

    private static BusinessProfile withWebsite(String website) {
        return new BusinessProfile("Legal", "Display", "ops@example.com", null, website, "OTHER", null, null);
    }

    @Test
    void bare_domains_get_an_https_scheme() {
        assertThat(withWebsite("test.corillo.do").websiteUrl()).isEqualTo("https://test.corillo.do");
        assertThat(withWebsite("  www.example.com/shop ").websiteUrl()).isEqualTo("https://www.example.com/shop");
    }

    @Test
    void urls_with_a_scheme_and_blank_websites_are_kept() {
        assertThat(withWebsite("https://example.com").websiteUrl()).isEqualTo("https://example.com");
        assertThat(withWebsite("HTTP://example.com").websiteUrl()).isEqualTo("HTTP://example.com");
        assertThat(withWebsite(null).websiteUrl()).isNull();
        assertThat(withWebsite("").websiteUrl()).isEmpty();
    }

    @Test
    void website_validation() {
        assertThat(BusinessProfile.isValidWebsiteUrl(null)).isTrue();
        assertThat(BusinessProfile.isValidWebsiteUrl("")).isTrue();
        assertThat(BusinessProfile.isValidWebsiteUrl("test.corillo.do")).isTrue();
        assertThat(BusinessProfile.isValidWebsiteUrl("https://example.com/path?q=1")).isTrue();
        assertThat(BusinessProfile.isValidWebsiteUrl("not a website")).isFalse();
        assertThat(BusinessProfile.isValidWebsiteUrl("localhost")).isFalse();
        assertThat(BusinessProfile.isValidWebsiteUrl("ftp://example.com")).isFalse();
        assertThat(BusinessProfile.isValidWebsiteUrl("example.")).isFalse();
    }

    @Test
    void logo_validation() {
        assertThat(BusinessProfile.isValidLogoUrl(null)).isTrue();
        assertThat(BusinessProfile.isValidLogoUrl("")).isTrue();
        assertThat(BusinessProfile.isValidLogoUrl("/v1/chat/logos/logo_abc/logo.png")).isTrue();
        assertThat(BusinessProfile.isValidLogoUrl("https://cdn.example.com/logo.jpg")).isTrue();
        assertThat(BusinessProfile.isValidLogoUrl("//evil.example/logo.png")).isFalse();
        assertThat(BusinessProfile.isValidLogoUrl("cdn.example.com/logo.png")).isFalse();
        assertThat(BusinessProfile.isValidLogoUrl("ftp://example.com/logo.png")).isFalse();
    }
}
