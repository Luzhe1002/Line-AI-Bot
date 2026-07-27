create table tenants (
    id varchar(36) primary key,
    slug varchar(80) not null unique,
    name varchar(160) not null,
    timezone varchar(64) not null,
    slot_minutes integer not null,
    admin_api_key_hash varchar(512) not null,
    active boolean not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create table line_channels (
    id varchar(36) primary key,
    tenant_id varchar(36) not null unique references tenants(id) on delete cascade,
    channel_secret_encrypted text not null,
    channel_access_token_encrypted text not null,
    enabled boolean not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create table business_hours (
    id varchar(36) primary key,
    tenant_id varchar(36) not null references tenants(id) on delete cascade,
    weekday integer not null,
    open_time time not null,
    close_time time not null,
    active boolean not null,
    constraint uq_business_hour_day unique (tenant_id, weekday)
);

create table booking_services (
    id varchar(36) primary key,
    tenant_id varchar(36) not null references tenants(id) on delete cascade,
    name varchar(160) not null,
    description text,
    active boolean not null,
    created_at timestamp with time zone not null,
    constraint uq_booking_service_name unique (tenant_id, name)
);

create table reservations (
    id varchar(36) primary key,
    tenant_id varchar(36) not null references tenants(id) on delete cascade,
    service_id varchar(36) not null references booking_services(id),
    line_user_id varchar(64) not null,
    customer_name varchar(160),
    starts_at timestamp with time zone not null,
    ends_at timestamp with time zone not null,
    active_slot_key timestamp with time zone,
    status varchar(24) not null,
    idempotency_key varchar(128) not null,
    created_at timestamp with time zone not null,
    cancelled_at timestamp with time zone,
    constraint uq_reservation_idempotency unique (tenant_id, idempotency_key),
    constraint uq_reservation_active_slot unique (tenant_id, active_slot_key)
);

create table datasets (
    id varchar(36) primary key,
    tenant_id varchar(36) not null references tenants(id) on delete cascade,
    name varchar(160) not null,
    version integer not null,
    status varchar(24) not null,
    active_marker varchar(8),
    created_at timestamp with time zone not null,
    published_at timestamp with time zone,
    constraint uq_dataset_version unique (tenant_id, name, version),
    constraint uq_dataset_active_per_tenant unique (tenant_id, active_marker)
);

create table knowledge_documents (
    id varchar(36) primary key,
    tenant_id varchar(36) not null references tenants(id) on delete cascade,
    dataset_id varchar(36) not null references datasets(id) on delete cascade,
    title varchar(240) not null,
    content text not null,
    source_url varchar(1024),
    active boolean not null,
    content_hash varchar(64),
    index_status varchar(24) not null,
    index_error text,
    indexed_at timestamp with time zone,
    created_at timestamp with time zone not null
);

create table knowledge_chunks (
    id varchar(36) primary key,
    tenant_id varchar(36) not null references tenants(id) on delete cascade,
    dataset_id varchar(36) not null references datasets(id) on delete cascade,
    document_id varchar(36) not null references knowledge_documents(id) on delete cascade,
    position integer not null,
    content text not null,
    content_hash varchar(64) not null,
    embedding_json text not null,
    embedding_model varchar(160) not null,
    embedding_dimensions integer not null,
    token_count_estimate integer not null,
    created_at timestamp with time zone not null,
    constraint uq_knowledge_chunk_position unique (document_id, position)
);

create table line_events (
    id varchar(36) primary key,
    tenant_id varchar(36) not null references tenants(id) on delete cascade,
    webhook_event_id varchar(64) not null,
    event_type varchar(64) not null,
    line_user_id varchar(64),
    payload_json text not null,
    status varchar(24) not null,
    attempts integer not null,
    next_attempt_at timestamp with time zone not null,
    locked_at timestamp with time zone,
    error text,
    received_at timestamp with time zone not null,
    processed_at timestamp with time zone,
    constraint uq_line_event_id unique (tenant_id, webhook_event_id)
);

create table conversation_messages (
    id varchar(36) primary key,
    tenant_id varchar(36) not null references tenants(id) on delete cascade,
    line_user_id varchar(64) not null,
    direction varchar(16) not null,
    message_type varchar(32) not null,
    content text not null,
    metadata_json text,
    created_at timestamp with time zone not null
);

create table handoff_tickets (
    id varchar(36) primary key,
    tenant_id varchar(36) not null references tenants(id) on delete cascade,
    line_user_id varchar(64) not null,
    status varchar(24) not null,
    reason text,
    created_at timestamp with time zone not null,
    closed_at timestamp with time zone
);

create table outbox_messages (
    id varchar(36) primary key,
    tenant_id varchar(36) not null references tenants(id) on delete cascade,
    line_user_id varchar(64),
    reply_token varchar(128),
    delivery_type varchar(16) not null,
    payload_json text not null,
    status varchar(24) not null,
    attempts integer not null,
    error text,
    created_at timestamp with time zone not null,
    sent_at timestamp with time zone
);

create index ix_line_channels_tenant_id on line_channels(tenant_id);
create index ix_business_hours_tenant_id on business_hours(tenant_id);
create index ix_booking_services_tenant_id on booking_services(tenant_id);
create index ix_reservations_tenant_id on reservations(tenant_id);
create index ix_reservations_line_user_id on reservations(line_user_id);
create index ix_reservations_starts_at on reservations(starts_at);
create index ix_datasets_tenant_id on datasets(tenant_id);
create index ix_knowledge_documents_tenant_id on knowledge_documents(tenant_id);
create index ix_knowledge_documents_dataset_id on knowledge_documents(dataset_id);
create index ix_knowledge_chunks_tenant_id on knowledge_chunks(tenant_id);
create index ix_knowledge_chunks_dataset_id on knowledge_chunks(dataset_id);
create index ix_knowledge_chunks_document_id on knowledge_chunks(document_id);
create index ix_line_events_ready on line_events(status, next_attempt_at);
create index ix_conversation_messages_tenant_user
    on conversation_messages(tenant_id, line_user_id);
create index ix_handoff_tickets_tenant_user_status
    on handoff_tickets(tenant_id, line_user_id, status);
create index ix_outbox_messages_status on outbox_messages(status);
