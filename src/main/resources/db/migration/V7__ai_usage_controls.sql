create table ai_usage_control (
    id integer primary key,
    updated_at timestamp with time zone not null,
    constraint ck_ai_usage_control_singleton check (id = 1)
);

insert into ai_usage_control (id, updated_at) values (1, current_timestamp);

create table ai_usage_events (
    id varchar(36) primary key,
    tenant_id varchar(36) not null references tenants(id) on delete cascade,
    actor_key varchar(64) not null,
    source varchar(32) not null,
    status varchar(16) not null,
    reserved_tokens bigint not null,
    input_tokens bigint not null,
    cached_input_tokens bigint not null,
    output_tokens bigint not null,
    reasoning_tokens bigint not null,
    total_tokens bigint not null,
    provider varchar(64),
    models varchar(512),
    provider_request_ids varchar(1024),
    rejection_reason varchar(64),
    error varchar(2000),
    created_at timestamp with time zone not null,
    completed_at timestamp with time zone,
    constraint ck_ai_usage_event_status
        check (status in ('PENDING', 'SUCCEEDED', 'FAILED', 'REJECTED')),
    constraint ck_ai_usage_event_tokens
        check (
            reserved_tokens >= 0
            and input_tokens >= 0
            and cached_input_tokens >= 0
            and output_tokens >= 0
            and reasoning_tokens >= 0
            and total_tokens >= 0
        )
);

create index ix_ai_usage_events_tenant_created
    on ai_usage_events(tenant_id, created_at);
create index ix_ai_usage_events_actor_created
    on ai_usage_events(tenant_id, actor_key, created_at);
create index ix_ai_usage_events_status_created
    on ai_usage_events(status, created_at);
