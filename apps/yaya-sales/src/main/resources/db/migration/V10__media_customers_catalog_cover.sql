-- Console-enrichment round: uploaded media (product photos / catalog covers),
-- a catalog cover, and the customer directory the design sketches in §9
-- (customers + addresses). Media bytes live in Postgres — self-hosted, no
-- object store; products.media_json / catalogs.cover_url hold the serving URLs.

create table media (
    id           varchar(64) primary key,
    store_id     varchar(64) not null references stores(id),
    content_type varchar(100) not null,
    filename     varchar(300),
    bytes        bytea not null,
    size_bytes   int not null,
    created_at   timestamptz not null
);
create index media_store_idx on media(store_id);

alter table catalogs add column cover_url varchar(1000);

create table customers (
    id           varchar(64) primary key,
    store_id     varchar(64) not null references stores(id),
    -- the verified principal subject orders/carts carry as customer_ref;
    -- null for directory-only customers (e.g. phone-order regulars)
    customer_ref varchar(200),
    display_name varchar(300) not null,
    email        varchar(320),
    phone        varchar(40),
    notes        text,
    created_at   timestamptz not null,
    unique (store_id, customer_ref)
);
create index customers_store_idx on customers(store_id);

create table customer_addresses (
    id          varchar(64) primary key,
    customer_id varchar(64) not null references customers(id) on delete cascade,
    kind        varchar(16) not null,   -- SHIPPING | BILLING | OTHER
    data_json   jsonb not null,
    created_at  timestamptz not null
);
create index customer_addresses_customer_idx on customer_addresses(customer_id);
