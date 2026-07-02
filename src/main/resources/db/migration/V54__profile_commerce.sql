-- Commerce chat (ecommerce-chat-design.md §7.4): a profile becomes a store by
-- carrying a CommerceConfig (catalogs + currency + checkout tool + cartEnabled).
-- Additive and nullable — NULL means "not a store", so existing profiles are
-- untouched and no backfill is needed.
ALTER TABLE profiles ADD COLUMN commerce_json JSONB;
