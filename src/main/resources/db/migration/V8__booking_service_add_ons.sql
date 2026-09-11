alter table booking_services
    add column duration_minutes integer not null default 60;
alter table booking_services
    add column price_amount integer not null default 0;
alter table booking_services
    add column updated_at timestamp with time zone;

update booking_services s
set duration_minutes = (
        select t.slot_minutes
        from tenants t
        where t.id = s.tenant_id
    ),
    updated_at = s.created_at;

alter table booking_services alter column updated_at set not null;
alter table booking_services
    add constraint ck_booking_service_duration check (duration_minutes > 0);
alter table booking_services
    add constraint ck_booking_service_price check (price_amount >= 0);

create table booking_add_ons (
    id varchar(36) primary key,
    tenant_id varchar(36) not null references tenants(id) on delete cascade,
    name varchar(160) not null,
    description text,
    duration_minutes integer not null,
    price_amount integer not null,
    active boolean not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uq_booking_add_on_name unique (tenant_id, name),
    constraint ck_booking_add_on_duration check (duration_minutes >= 0),
    constraint ck_booking_add_on_price check (price_amount >= 0)
);

create table booking_service_add_ons (
    tenant_id varchar(36) not null references tenants(id) on delete cascade,
    service_id varchar(36) not null references booking_services(id) on delete cascade,
    add_on_id varchar(36) not null references booking_add_ons(id) on delete cascade,
    created_at timestamp with time zone not null,
    primary key (service_id, add_on_id)
);

alter table reservations
    add column service_name varchar(160);
alter table reservations
    add column total_duration_minutes integer;
alter table reservations
    add column total_price_amount integer;

update reservations r
set service_name = (
        select s.name
        from booking_services s
        where s.id = r.service_id and s.tenant_id = r.tenant_id
    ),
    total_duration_minutes = (
        select s.duration_minutes
        from booking_services s
        where s.id = r.service_id and s.tenant_id = r.tenant_id
    ),
    total_price_amount = (
        select s.price_amount
        from booking_services s
        where s.id = r.service_id and s.tenant_id = r.tenant_id
    );

alter table reservations alter column service_name set not null;
alter table reservations alter column total_duration_minutes set not null;
alter table reservations alter column total_price_amount set not null;
alter table reservations
    add constraint ck_reservation_total_duration check (total_duration_minutes > 0);
alter table reservations
    add constraint ck_reservation_total_price check (total_price_amount >= 0);

create table reservation_add_ons (
    id varchar(36) primary key,
    tenant_id varchar(36) not null references tenants(id) on delete cascade,
    reservation_id varchar(36) not null references reservations(id) on delete cascade,
    add_on_id varchar(36) not null references booking_add_ons(id),
    name varchar(160) not null,
    duration_minutes integer not null,
    price_amount integer not null,
    created_at timestamp with time zone not null,
    constraint uq_reservation_add_on unique (reservation_id, add_on_id),
    constraint ck_reservation_add_on_duration check (duration_minutes >= 0),
    constraint ck_reservation_add_on_price check (price_amount >= 0)
);

alter table booking_slot_occupancies
    drop constraint uq_booking_slot_reference;
alter table booking_slot_occupancies
    add constraint uq_booking_slot_reference
        unique (tenant_id, occupancy_type, reference_id, starts_at);

create index ix_booking_add_ons_tenant_active
    on booking_add_ons(tenant_id, active, created_at);
create index ix_booking_service_add_ons_tenant_service
    on booking_service_add_ons(tenant_id, service_id);
create index ix_reservation_add_ons_tenant_reservation
    on reservation_add_ons(tenant_id, reservation_id);
