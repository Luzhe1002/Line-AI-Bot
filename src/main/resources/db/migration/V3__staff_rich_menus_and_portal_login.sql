alter table merchant_manage_tokens
    add column purpose varchar(32) not null default 'BOOKING_MANAGE';

create table merchant_rich_menus (
    id varchar(36) primary key,
    tenant_id varchar(36) not null references tenants(id) on delete cascade,
    role varchar(24) not null,
    line_rich_menu_id varchar(128),
    status varchar(24) not null,
    last_error varchar(1000),
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uq_merchant_rich_menu_role unique (tenant_id, role)
);

create table merchant_rich_menu_sync (
    staff_id varchar(36) primary key references merchant_staff(id) on delete cascade,
    tenant_id varchar(36) not null references tenants(id) on delete cascade,
    desired_role varchar(24) not null,
    desired_linked boolean not null,
    revision integer not null,
    status varchar(24) not null,
    attempts integer not null,
    next_attempt_at timestamp with time zone not null,
    locked_at timestamp with time zone,
    last_error varchar(1000),
    updated_at timestamp with time zone not null
);

insert into merchant_rich_menu_sync (
    staff_id, tenant_id, desired_role, desired_linked, revision, status,
    attempts, next_attempt_at, locked_at, last_error, updated_at
)
select id, tenant_id, role, status = 'ACTIVE', 1, 'READY',
       0, updated_at, null, null, updated_at
from merchant_staff;

create index ix_merchant_rich_menu_sync_ready
    on merchant_rich_menu_sync(status, next_attempt_at);
create index ix_merchant_rich_menu_tenant
    on merchant_rich_menus(tenant_id, role, status);
create index ix_merchant_manage_tokens_purpose
    on merchant_manage_tokens(purpose, token_hash, expires_at);
