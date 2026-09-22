DROP FUNCTION IF EXISTS replace_route_details(UUID, BOOLEAN, JSONB, BOOLEAN, JSONB);
DROP FUNCTION IF EXISTS admin_force_stop_bus(VARCHAR);
DROP TABLE IF EXISTS notification_deliveries, push_subscriptions, user_reports, admin_action_logs,
    bus_latest_state, bus_locations, bus_trips, buses, route_path_cache, route_shape_points,
    route_stops, routes, notices, auth_tokens, users CASCADE;

CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255),
    name VARCHAR(100) NOT NULL,
    student_id VARCHAR(50),
    role VARCHAR(20) NOT NULL,
    provider VARCHAR(20) DEFAULT 'local',
    provider_id VARCHAR(255),
    profile_image TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE auth_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token VARCHAR(255) UNIQUE NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE notices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    category VARCHAR(20) NOT NULL,
    priority VARCHAR(20) NOT NULL,
    is_pinned BOOLEAN DEFAULT FALSE,
    view_count INTEGER DEFAULT 0,
    author_id UUID NOT NULL REFERENCES users(id),
    image_urls TEXT[] DEFAULT ARRAY[]::TEXT[],
    content_below TEXT DEFAULT '',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE routes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    type VARCHAR(20) NOT NULL,
    description TEXT,
    shuttle_variant VARCHAR(40),
    color VARCHAR(20),
    region VARCHAR(100),
    schedule TEXT,
    schedule_basis VARCHAR(40),
    interval_minutes INTEGER,
    departure_offset_minutes INTEGER DEFAULT 0,
    boarding_wait_minutes INTEGER DEFAULT 0,
    continuation_route_id UUID,
    duration VARCHAR(100),
    fare VARCHAR(100),
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE route_stops (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    route_id UUID REFERENCES routes(id) ON DELETE CASCADE,
    stop_name VARCHAR(255),
    stop_order INTEGER,
    latitude NUMERIC,
    longitude NUMERIC,
    arrival_time VARCHAR(20)
);

CREATE TABLE route_shape_points (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    route_id UUID REFERENCES routes(id) ON DELETE CASCADE,
    name VARCHAR(255),
    after_stop_order INTEGER,
    point_order INTEGER,
    latitude NUMERIC,
    longitude NUMERIC
);

CREATE TABLE route_path_cache (
    route_id UUID PRIMARY KEY REFERENCES routes(id) ON DELETE CASCADE,
    input_hash TEXT,
    path JSONB,
    generated_at TIMESTAMPTZ
);

CREATE TABLE buses (
    id VARCHAR(20) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(20) NOT NULL,
    capacity INTEGER,
    license_plate VARCHAR(30),
    status VARCHAR(20) DEFAULT 'inactive',
    is_running BOOLEAN DEFAULT FALSE,
    current_driver_id UUID REFERENCES users(id),
    assigned_driver_id UUID REFERENCES users(id),
    current_route_id UUID REFERENCES routes(id),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE bus_trips (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bus_id VARCHAR(20) REFERENCES buses(id),
    route_id UUID REFERENCES routes(id),
    driver_id UUID REFERENCES users(id),
    status VARCHAR(20),
    service_phase VARCHAR(40),
    planned_departure_at TIMESTAMPTZ,
    current_stop_order INTEGER,
    ended_at TIMESTAMPTZ
);

CREATE TABLE bus_locations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bus_id VARCHAR(20) REFERENCES buses(id),
    latitude NUMERIC,
    longitude NUMERIC,
    speed NUMERIC,
    heading INTEGER,
    timestamp TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE bus_latest_state (
    bus_id VARCHAR(20) PRIMARY KEY REFERENCES buses(id),
    latitude NUMERIC,
    longitude NUMERIC,
    speed NUMERIC,
    heading INTEGER,
    timestamp TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE admin_action_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id UUID REFERENCES users(id),
    action TEXT NOT NULL,
    target_type TEXT,
    target_id TEXT,
    metadata JSONB DEFAULT '{}'::JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE user_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id),
    category VARCHAR(40),
    title VARCHAR(160),
    details TEXT,
    status VARCHAR(20) DEFAULT 'open',
    related_bus_id VARCHAR(20),
    related_route_id UUID,
    admin_note TEXT DEFAULT '',
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE push_subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id),
    endpoint TEXT UNIQUE,
    p256dh TEXT,
    auth TEXT,
    user_agent TEXT,
    enabled BOOLEAN DEFAULT TRUE,
    last_error TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE notification_deliveries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    notice_id UUID REFERENCES notices(id) ON DELETE SET NULL,
    target VARCHAR(20),
    attempted INTEGER,
    sent INTEGER,
    failed INTEGER,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE FUNCTION replace_route_details(UUID, BOOLEAN, JSONB, BOOLEAN, JSONB)
RETURNS VOID LANGUAGE sql AS 'SELECT';

CREATE FUNCTION admin_force_stop_bus(target_bus_id VARCHAR)
RETURNS VOID LANGUAGE sql AS 'UPDATE buses SET is_running = FALSE, current_driver_id = NULL, status = ''inactive'' WHERE id = target_bus_id';

INSERT INTO users (id, email, name, role, provider) VALUES
    ('50000000-0000-0000-0000-000000000001', 'admin@example.com', '관리자', 'admin', 'local'),
    ('50000000-0000-0000-0000-000000000002', 'user@example.com', '일반 사용자', 'user', 'local'),
    ('50000000-0000-0000-0000-000000000003', 'driver@example.com', '기사', 'driver', 'local');

INSERT INTO auth_tokens (user_id, token, expires_at) VALUES
    ('50000000-0000-0000-0000-000000000001', 'sha256:QbfhHRvcjGb3AS4_pVNdON2n_XIoztNJy9zTJWPpKes', '2099-01-01T00:00:00Z'),
    ('50000000-0000-0000-0000-000000000002', 'sha256:GDNoe-H3GxZILHXyznR8beYcYCcGFMgiDbeO242w71M', '2099-01-01T00:00:00Z'),
    ('50000000-0000-0000-0000-000000000001', 'sha256:1oAMZXZhntt0876mLhNkbsc5JsJP5sTSf8GmI87PmKs', '2020-01-01T00:00:00Z');

INSERT INTO routes (id, name, type, color, is_active) VALUES
    ('60000000-0000-0000-0000-000000000001', '기존 노선', 'commute', '#123456', TRUE);

INSERT INTO buses (id, name, type, capacity, license_plate, assigned_driver_id, current_route_id)
VALUES ('CM-TEST0001', '기존 버스', 'commute', 45, '12가3456',
    '50000000-0000-0000-0000-000000000003', '60000000-0000-0000-0000-000000000001');

INSERT INTO user_reports (id, user_id, category, title, details)
VALUES ('70000000-0000-0000-0000-000000000001',
    '50000000-0000-0000-0000-000000000002', 'other', '문의', '문의 내용');
