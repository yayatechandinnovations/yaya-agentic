-- Commerce chat (ecommerce-chat-design.md §15): an optional representative image
-- for a catalog, uploaded through the admin console. Additive + nullable.
ALTER TABLE product_catalogs ADD COLUMN image_url VARCHAR(1024);
