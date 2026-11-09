-- NEXGUARD Phase 1 Database Schema

create extension if not exists pgcrypto;

-- Device telemetry table
create table if not exists public.device_telemetry (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references auth.users(id) on delete cascade,
    battery_percent integer,
    is_charging boolean not null default false,
    network_type text,
    created_at timestamptz not null default now(),

    constraint battery_percent_valid
        check (battery_percent between 0 and 100 or battery_percent = -1)
);

-- Enable Row Level Security
alter table public.device_telemetry enable row level security;

-- Users can view only their own device telemetry
create policy "Users can view own telemetry"
on public.device_telemetry
for select
to authenticated
using (auth.uid() = owner_id);

-- Users can insert only their own telemetry
create policy "Users can insert own telemetry"
on public.device_telemetry
for insert
to authenticated
with check (auth.uid() = owner_id);

-- Pairing codes table
create table if not exists public.device_pairing_codes (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references auth.users(id) on delete cascade,
    code_hash text not null unique,
    device_name text,
    expires_at timestamptz not null,
    used_at timestamptz,
    created_at timestamptz not null default now()
);

alter table public.device_pairing_codes enable row level security;

-- Pairing codes must only be managed by trusted server functions.
revoke all on public.device_pairing_codes from anon, authenticated;

-- Indexes
create index if not exists device_telemetry_owner_created_idx
on public.device_telemetry(owner_id, created_at desc);

create index if not exists device_pairing_codes_owner_idx
on public.device_pairing_codes(owner_id);
