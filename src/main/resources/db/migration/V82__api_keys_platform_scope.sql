-- One-click YayaOps provisioning (gap B): platform-scope API keys. A key
-- with platform_scope=true acts with platform-admin reach across every
-- workspace (the hub's single machine credential for cross-tenant
-- provisioning). Mintable ONLY by users whose account carries
-- is_platform_admin — enforced in ApiKeyController/ApiKeyService, and the
-- default keeps every existing key exactly as scoped as before.
ALTER TABLE api_keys
    ADD COLUMN platform_scope BOOLEAN NOT NULL DEFAULT FALSE;
