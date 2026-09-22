DROP FUNCTION IF EXISTS record_bus_location(VARCHAR, UUID, DECIMAL, DECIMAL, DECIMAL, INTEGER);
DROP FUNCTION IF EXISTS admin_force_stop_bus(VARCHAR);
DROP TABLE IF EXISTS admin_action_logs, bus_latest_state, bus_locations, bus_trips, buses,
    route_stops, routes, auth_tokens, users CASCADE;

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

CREATE TABLE routes (
    id UUID PRIMARY KEY,
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
    continuation_route_id UUID REFERENCES routes(id),
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
    bus_id VARCHAR(20) NOT NULL REFERENCES buses(id) ON DELETE CASCADE,
    route_id UUID REFERENCES routes(id) ON DELETE SET NULL,
    origin_route_id UUID REFERENCES routes(id) ON DELETE SET NULL,
    driver_id UUID REFERENCES users(id) ON DELETE SET NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    current_stop_order INTEGER NOT NULL DEFAULT 0,
    service_phase VARCHAR(30) NOT NULL DEFAULT 'in_service',
    scheduled_event_at TIMESTAMPTZ,
    planned_departure_at TIMESTAMPTZ,
    one_loop_only BOOLEAN NOT NULL DEFAULT FALSE,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ended_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX driver_test_one_active_bus
    ON bus_trips(bus_id) WHERE status = 'active';
CREATE UNIQUE INDEX driver_test_one_active_driver
    ON bus_trips(driver_id) WHERE status = 'active' AND driver_id IS NOT NULL;

CREATE TABLE bus_locations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bus_id VARCHAR(20) REFERENCES buses(id) ON DELETE CASCADE,
    latitude NUMERIC,
    longitude NUMERIC,
    speed NUMERIC,
    heading INTEGER,
    timestamp TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE bus_latest_state (
    bus_id VARCHAR(20) PRIMARY KEY REFERENCES buses(id) ON DELETE CASCADE,
    trip_id UUID REFERENCES bus_trips(id) ON DELETE SET NULL,
    latitude NUMERIC NOT NULL,
    longitude NUMERIC NOT NULL,
    speed NUMERIC NOT NULL DEFAULT 0,
    heading INTEGER NOT NULL DEFAULT 0,
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_history_at TIMESTAMPTZ
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

CREATE FUNCTION record_bus_location(
    p_bus_id VARCHAR,
    p_trip_id UUID,
    p_latitude DECIMAL,
    p_longitude DECIMAL,
    p_speed DECIMAL,
    p_heading INTEGER
) RETURNS BOOLEAN LANGUAGE plpgsql AS '
DECLARE
    v_now TIMESTAMPTZ := NOW();
    v_last_history_at TIMESTAMPTZ;
    v_sampled BOOLEAN := FALSE;
BEGIN
    INSERT INTO bus_latest_state (
        bus_id, trip_id, latitude, longitude, speed, heading, timestamp
    ) VALUES (
        p_bus_id, p_trip_id, p_latitude, p_longitude, p_speed, p_heading, v_now
    )
    ON CONFLICT (bus_id) DO UPDATE SET
        trip_id = EXCLUDED.trip_id,
        latitude = EXCLUDED.latitude,
        longitude = EXCLUDED.longitude,
        speed = EXCLUDED.speed,
        heading = EXCLUDED.heading,
        timestamp = EXCLUDED.timestamp
    RETURNING last_history_at INTO v_last_history_at;

    IF v_last_history_at IS NULL OR v_last_history_at <= v_now - INTERVAL ''30 seconds'' THEN
        INSERT INTO bus_locations (bus_id, latitude, longitude, speed, heading, timestamp)
        VALUES (p_bus_id, p_latitude, p_longitude, p_speed, p_heading, v_now);
        UPDATE bus_latest_state SET last_history_at = v_now WHERE bus_id = p_bus_id;
        v_sampled := TRUE;
    END IF;
    RETURN v_sampled;
END;
';

CREATE FUNCTION admin_force_stop_bus(target_bus_id VARCHAR)
RETURNS VOID LANGUAGE plpgsql AS '
DECLARE
    restore_route_id UUID;
BEGIN
    SELECT origin_route_id INTO restore_route_id
    FROM bus_trips
    WHERE bus_id = target_bus_id AND status = ''active''
    ORDER BY started_at DESC
    LIMIT 1;

    UPDATE bus_trips
    SET status = ''cancelled'', ended_at = NOW(), updated_at = NOW()
    WHERE bus_id = target_bus_id AND status = ''active'';

    UPDATE buses
    SET is_running = FALSE,
        current_driver_id = NULL,
        current_route_id = COALESCE(restore_route_id, current_route_id),
        updated_at = NOW()
    WHERE id = target_bus_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION ''Bus not found'';
    END IF;
END;
';

INSERT INTO users (id, email, name, role, provider) VALUES
    ('50000000-0000-0000-0000-000000000001', 'admin@example.com', '관리자', 'admin', 'local'),
    ('50000000-0000-0000-0000-000000000002', 'user@example.com', '일반 사용자', 'user', 'local'),
    ('50000000-0000-0000-0000-000000000003', 'driver1@example.com', '기사 1', 'driver', 'local'),
    ('50000000-0000-0000-0000-000000000004', 'driver2@example.com', '기사 2', 'driver', 'local');

INSERT INTO auth_tokens (user_id, token, expires_at) VALUES
    ('50000000-0000-0000-0000-000000000001', 'sha256:QbfhHRvcjGb3AS4_pVNdON2n_XIoztNJy9zTJWPpKes', '2099-01-01T00:00:00Z'),
    ('50000000-0000-0000-0000-000000000002', 'sha256:GDNoe-H3GxZILHXyznR8beYcYCcGFMgiDbeO242w71M', '2099-01-01T00:00:00Z'),
    ('50000000-0000-0000-0000-000000000003', 'sha256:Mfl6aR7knOBLDaUFXADbUBySHc6W4gBS-sw_1LJX4qQ', '2099-01-01T00:00:00Z'),
    ('50000000-0000-0000-0000-000000000004', 'sha256:-XKdfb2uDsYJ-YNzHAeF9DClTnC_h5dAjcCsb3ZpkFg', '2099-01-01T00:00:00Z'),
    ('50000000-0000-0000-0000-000000000003', 'sha256:oyi-PXKaC82MbwozdMrrykIIMI6xaw9bYM5eWCdLD50', '2020-01-01T00:00:00Z');

INSERT INTO routes (
    id, name, type, shuttle_variant, color, schedule, schedule_basis,
    boarding_wait_minutes, continuation_route_id, is_active
) VALUES (
    '60000000-0000-0000-0000-000000000001', '신창역 → 학교', 'shuttle',
    'station_to_campus_loop', '#123456', '23:59', 'train_arrival', 5,
    NULL, TRUE
);

INSERT INTO routes (
    id, name, type, shuttle_variant, color, interval_minutes, schedule_basis, is_active
) VALUES (
    '60000000-0000-0000-0000-000000000002', '학내순환', 'shuttle',
    'campus_loop', '#654321', 10, 'bus_departure', TRUE
);

UPDATE routes
SET continuation_route_id = '60000000-0000-0000-0000-000000000002'
WHERE id = '60000000-0000-0000-0000-000000000001';

INSERT INTO route_stops (route_id, stop_name, stop_order, latitude, longitude) VALUES
    ('60000000-0000-0000-0000-000000000001', '신창역', 1, 36.769, 126.951),
    ('60000000-0000-0000-0000-000000000001', '학교 후문', 2, 36.770, 126.952),
    ('60000000-0000-0000-0000-000000000002', '학교 후문', 1, 36.770, 126.952),
    ('60000000-0000-0000-0000-000000000002', '도서관', 2, 36.771, 126.953);

INSERT INTO buses (
    id, name, type, capacity, license_plate, status, assigned_driver_id, current_route_id
) VALUES
    ('SH-CONCURRENT', '공용 셔틀', 'shuttle', 45, '10가1000', 'active', NULL,
        '60000000-0000-0000-0000-000000000001'),
    ('SH-ASSIGNED', '배정 셔틀', 'shuttle', 35, '20나2000', 'active',
        '50000000-0000-0000-0000-000000000003', '60000000-0000-0000-0000-000000000001'),
    ('SH-OTHER', '다른 기사 셔틀', 'shuttle', 35, '30다3000', 'active',
        '50000000-0000-0000-0000-000000000004', '60000000-0000-0000-0000-000000000001'),
    ('CM-INACTIVE', '정비 버스', 'commute', 40, '40라4000', 'maintenance', NULL,
        '60000000-0000-0000-0000-000000000001');
