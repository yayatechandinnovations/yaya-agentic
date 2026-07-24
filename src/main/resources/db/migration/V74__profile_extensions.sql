-- Generalize the profile's store pointer into a domain-neutral extension map
-- (S7, docs/milestones/S7-decouple-store.md). The framework core no longer knows
-- what "commerce" is: a profile carries a map of namespaced kind -> opaque
-- payload, surfaced to the client verbatim as start-time traits. yaya-sales owns
-- the `sales.store` kind and its payload shape (yaya-sales design §11.4/§12.3).
--
-- The retired `commerce_json` held a single CommerceBinding/CommerceConfig object
-- ({storeId, checkoutTool, cartEnabled, …}). We rename the column and wrap each
-- non-null value under the `sales.store` kind so existing stores keep working
-- with no operator re-authoring; extra legacy fields ride along untouched and are
-- ignored by the client, exactly as the lenient CommerceBinding read did before.

ALTER TABLE profiles RENAME COLUMN commerce_json TO extensions_json;

UPDATE profiles
   SET extensions_json = jsonb_build_object('sales.store', extensions_json)
 WHERE extensions_json IS NOT NULL
   AND NOT (extensions_json ? 'sales.store');   -- idempotent guard
