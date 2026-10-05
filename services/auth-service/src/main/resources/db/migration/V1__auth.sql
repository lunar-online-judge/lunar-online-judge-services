create schema if not exists auth_schema;

create table if not exists auth_schema.users (
    id uuid primary key,
    username varchar(100) not null unique,
    password_hash varchar(255) not null,
    display_name varchar(120) not null,
    role varchar(32) not null,
    status varchar(32) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
