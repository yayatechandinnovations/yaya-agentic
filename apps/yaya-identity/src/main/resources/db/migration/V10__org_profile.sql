-- Org profile (console redesign): organizations carry real business details, not
-- just a name. All optional — the signup flow still creates an org from a name.
alter table organizations
    add column description   text,
    add column contact_email varchar(320),
    add column contact_phone varchar(32),
    add column website       varchar(512),
    add column country       varchar(64);
