create table merchant_staff (
    id varchar(36) primary key,
    tenant_id varchar(36) not null references tenants(id) on delete cascade,
    line_user_key varchar(64) not null,
    line_user_id_encrypted text not null,
    display_name varchar(160) not null,
    role varchar(24) not null,
    status varchar(24) not null,
    notify_new_booking boolean not null,
    notify_cancellation boolean not null,
    daily_summary_enabled boolean not null,
    daily_summary_time time not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uq_merchant_staff_line_user unique (tenant_id, line_user_key)
);

create table merchant_staff_link_tokens (
    id varchar(36) primary key,
    tenant_id varchar(36) not null references tenants(id) on delete cascade,
    token_hash varchar(64) not null,
    display_name varchar(160) not null,
    role varchar(24) not null,
    expires_at timestamp with time zone not null,
    consumed_at timestamp with time zone,
    created_at timestamp with time zone not null,
    constraint uq_merchant_staff_link_token unique (tenant_id, token_hash)
);

create table merchant_manage_tokens (
    id varchar(36) primary key,
    tenant_id varchar(36) not null references tenants(id) on delete cascade,
    staff_id varchar(36) not null references merchant_staff(id) on delete cascade,
    token_hash varchar(64) not null unique,
    expires_at timestamp with time zone not null,
    consumed_at timestamp with time zone,
    created_at timestamp with time zone not null
);

create table booking_slot_occupancies (
    id varchar(36) primary key,
    tenant_id varchar(36) not null references tenants(id) on delete cascade,
    starts_at timestamp with time zone not null,
    ends_at timestamp with time zone not null,
    occupancy_type varchar(24) not null,
    reference_id varchar(36) not null,
    created_at timestamp with time zone not null,
    constraint uq_booking_slot_occupancy unique (tenant_id, starts_at),
    constraint uq_booking_slot_reference unique (tenant_id, occupancy_type, reference_id)
);

insert into booking_slot_occupancies (
    id, tenant_id, starts_at, ends_at, occupancy_type, reference_id, created_at
)
select id, tenant_id, starts_at, ends_at, 'RESERVATION', id, created_at
from reservations
where status in ('HELD', 'CONFIRMED');

create table booking_blocks (
    id varchar(36) primary key,
    tenant_id varchar(36) not null references tenants(id) on delete cascade,
    starts_at timestamp with time zone not null,
    ends_at timestamp with time zone not null,
    reason varchar(500),
    active boolean not null,
    created_by_staff_id varchar(36) not null references merchant_staff(id),
    created_at timestamp with time zone not null,
    released_at timestamp with time zone
);

create table booking_events (
    id varchar(36) primary key,
    tenant_id varchar(36) not null references tenants(id) on delete cascade,
    reservation_id varchar(36) not null references reservations(id) on delete cascade,
    event_type varchar(32) not null,
    actor_type varchar(24) not null,
    actor_id varchar(128),
    dedupe_key varchar(160) not null unique,
    status varchar(24) not null,
    attempts integer not null,
    next_attempt_at timestamp with time zone not null,
    locked_at timestamp with time zone,
    error text,
    created_at timestamp with time zone not null,
    processed_at timestamp with time zone
);

create table booking_activity_logs (
    id varchar(36) primary key,
    tenant_id varchar(36) not null references tenants(id) on delete cascade,
    reservation_id varchar(36),
    block_id varchar(36),
    action varchar(40) not null,
    actor_type varchar(24) not null,
    actor_id varchar(128),
    details varchar(1000),
    created_at timestamp with time zone not null
);

create table merchant_daily_summary_runs (
    id varchar(36) primary key,
    tenant_id varchar(36) not null references tenants(id) on delete cascade,
    staff_id varchar(36) not null references merchant_staff(id) on delete cascade,
    local_date date not null,
    created_at timestamp with time zone not null,
    constraint uq_merchant_daily_summary unique (tenant_id, staff_id, local_date)
);

alter table outbox_messages add column dedupe_key varchar(200);

create unique index uq_outbox_messages_dedupe_key
    on outbox_messages(dedupe_key);
create index ix_merchant_staff_tenant_status
    on merchant_staff(tenant_id, status);
create index ix_merchant_staff_links_ready
    on merchant_staff_link_tokens(tenant_id, token_hash, expires_at);
create index ix_merchant_manage_tokens_ready
    on merchant_manage_tokens(token_hash, expires_at);
create index ix_booking_slot_occupancies_window
    on booking_slot_occupancies(tenant_id, starts_at);
create index ix_booking_blocks_window
    on booking_blocks(tenant_id, starts_at, active);
create index ix_booking_events_ready
    on booking_events(status, next_attempt_at);
create index ix_booking_activity_tenant_created
    on booking_activity_logs(tenant_id, created_at);
