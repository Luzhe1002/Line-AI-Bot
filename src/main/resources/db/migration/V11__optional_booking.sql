-- Existing merchants retain booking. New merchants explicitly choose their mode.
alter table tenants add column booking_enabled boolean not null default true;

create table customer_menu_sync (
    tenant_id varchar(36) primary key references tenants(id) on delete cascade,
    revision integer not null default 1,
    synced_revision integer not null default 0,
    next_attempt_at timestamp with time zone not null default current_timestamp,
    locked_until timestamp with time zone
);
insert into customer_menu_sync (tenant_id)
select tenant_id from line_channels;
