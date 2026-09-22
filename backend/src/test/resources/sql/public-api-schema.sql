DROP TABLE IF EXISTS bus_latest_state, bus_locations, bus_trips, buses, route_path_cache,
    route_shape_points, route_stops, notices, routes, users CASCADE;

CREATE TABLE users (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL
);

CREATE TABLE notices (
    id UUID PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    category VARCHAR(50) NOT NULL,
    priority VARCHAR(20) NOT NULL,
    author_id UUID NOT NULL REFERENCES users(id),
    is_pinned BOOLEAN,
    view_count INTEGER,
    image_urls TEXT[],
    content_below TEXT,
    created_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE routes (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(20) NOT NULL,
    description TEXT,
    shuttle_variant VARCHAR(40),
    color VARCHAR(7),
    region VARCHAR(100),
    schedule TEXT,
    schedule_basis VARCHAR(30),
    interval_minutes INTEGER,
    departure_offset_minutes INTEGER,
    boarding_wait_minutes INTEGER,
    continuation_route_id UUID,
    duration VARCHAR(50),
    fare VARCHAR(50),
    is_active BOOLEAN,
    created_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE route_stops (
    id UUID PRIMARY KEY,
    route_id UUID NOT NULL REFERENCES routes(id),
    stop_name VARCHAR(100) NOT NULL,
    stop_order INTEGER NOT NULL,
    latitude DECIMAL(10, 8),
    longitude DECIMAL(11, 8),
    arrival_time TIME
);

CREATE TABLE route_shape_points (
    id UUID PRIMARY KEY,
    route_id UUID NOT NULL REFERENCES routes(id),
    name VARCHAR(100),
    after_stop_order INTEGER NOT NULL,
    point_order INTEGER NOT NULL,
    latitude DECIMAL(10, 8) NOT NULL,
    longitude DECIMAL(11, 8) NOT NULL
);

CREATE TABLE route_path_cache (
    route_id UUID PRIMARY KEY REFERENCES routes(id),
    input_hash TEXT NOT NULL,
    path JSONB NOT NULL,
    generated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE buses (
    id VARCHAR(20) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(20) NOT NULL,
    license_plate VARCHAR(30),
    current_driver_id UUID REFERENCES users(id),
    assigned_driver_id UUID REFERENCES users(id),
    capacity INTEGER,
    status VARCHAR(20),
    is_running BOOLEAN,
    current_route_id UUID REFERENCES routes(id),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE bus_trips (
    id UUID PRIMARY KEY,
    bus_id VARCHAR(20) NOT NULL REFERENCES buses(id),
    route_id UUID REFERENCES routes(id),
    status VARCHAR(20) NOT NULL,
    service_phase VARCHAR(30),
    planned_departure_at TIMESTAMP WITH TIME ZONE,
    current_stop_order INTEGER
);

CREATE TABLE bus_latest_state (
    bus_id VARCHAR(20) PRIMARY KEY REFERENCES buses(id),
    latitude DECIMAL(10, 8) NOT NULL,
    longitude DECIMAL(11, 8) NOT NULL,
    speed DECIMAL(5, 2),
    heading INTEGER,
    timestamp TIMESTAMP WITH TIME ZONE
);

CREATE TABLE bus_locations (
    id UUID PRIMARY KEY,
    bus_id VARCHAR(20) NOT NULL REFERENCES buses(id),
    latitude DECIMAL(10, 8) NOT NULL,
    longitude DECIMAL(11, 8) NOT NULL,
    speed DECIMAL(5, 2),
    heading INTEGER,
    timestamp TIMESTAMP WITH TIME ZONE
);

INSERT INTO users (id, name)
VALUES ('00000000-0000-0000-0000-000000000001', '관리자');

INSERT INTO notices (
    id, title, content, category, priority, author_id, is_pinned, view_count,
    image_urls, content_below, created_at, updated_at
) VALUES (
    '10000000-0000-0000-0000-000000000001', '중요 공지', '내용', 'general', 'high',
    '00000000-0000-0000-0000-000000000001', true, 7, NULL, NULL,
    '2026-07-01T01:02:03Z', '2026-07-02T01:02:03Z'
);

INSERT INTO routes (
    id, name, type, description, shuttle_variant, color, region, schedule,
    schedule_basis, interval_minutes, departure_offset_minutes, boarding_wait_minutes,
    continuation_route_id, duration, fare, is_active, created_at, updated_at
) VALUES
    ('20000000-0000-0000-0000-000000000001', '학내순환', 'shuttle', NULL, 'campus_loop',
     '#1E3B8A', NULL, NULL, 'bus_departure', 10, 0, 0, NULL, NULL, NULL, true,
     '2026-07-03T01:02:03Z', '2026-07-04T01:02:03Z'),
    ('20000000-0000-0000-0000-000000000002', '빈 통학노선', 'commute', NULL, NULL,
     '#123456', NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, false,
     '2026-07-03T01:02:03Z', '2026-07-04T01:02:03Z');

INSERT INTO route_stops (id, route_id, stop_name, stop_order, latitude, longitude, arrival_time)
VALUES
    ('21000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', '정문', 1, 36.77000000, 126.93000000, '08:30:00'),
    ('21000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000001', '후문', 2, 36.78000000, 126.94000000, NULL);

INSERT INTO route_shape_points (id, route_id, name, after_stop_order, point_order, latitude, longitude)
VALUES (
    '22000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001',
    NULL, 1, 1, 36.77500000, 126.93500000
);

INSERT INTO buses (id, name, type, capacity, status, is_running, current_route_id)
VALUES
    ('CM-001', '통학 1호', 'commute', 45, 'active', true, '20000000-0000-0000-0000-000000000001'),
    ('SH-EMPTY', '대기 셔틀', 'shuttle', 30, 'inactive', false, NULL);

INSERT INTO bus_trips (
    id, bus_id, route_id, status, service_phase, planned_departure_at, current_stop_order
) VALUES (
    '30000000-0000-0000-0000-000000000001', 'CM-001',
    '20000000-0000-0000-0000-000000000001', 'active', 'in_service',
    '2026-07-05T08:00:00Z', 1
);

INSERT INTO bus_latest_state (bus_id, latitude, longitude, speed, heading, timestamp)
VALUES ('CM-001', 36.77100000, 126.93100000, 25.50, 90, '2026-07-05T08:05:00Z');

INSERT INTO bus_locations (id, bus_id, latitude, longitude, speed, heading, timestamp)
VALUES (
    '31000000-0000-0000-0000-000000000001', 'CM-001',
    36.77050000, 126.93050000, 24.00, 88, '2026-07-05T08:04:00Z'
);
