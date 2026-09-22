DROP FUNCTION IF EXISTS consume_api_rate_limit(TEXT, INTEGER, INTEGER);
DROP TABLE IF EXISTS api_rate_limits, auth_tokens, users CASCADE;

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255),
    name VARCHAR(100) NOT NULL,
    student_id VARCHAR(50),
    role VARCHAR(20) NOT NULL DEFAULT 'user',
    provider VARCHAR(20) DEFAULT 'local',
    provider_id VARCHAR(255),
    profile_image TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_users_email_lower ON users (LOWER(email));

CREATE TABLE auth_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token VARCHAR(255) UNIQUE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE DEFAULT (NOW() + INTERVAL '30 days'),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE api_rate_limits (
    rate_key TEXT PRIMARY KEY,
    window_started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    request_count INTEGER NOT NULL DEFAULT 0 CHECK (request_count >= 0),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE OR REPLACE FUNCTION consume_api_rate_limit(
    p_rate_key TEXT,
    p_max_requests INTEGER,
    p_window_seconds INTEGER
)
RETURNS BOOLEAN
LANGUAGE sql
AS $function$
    INSERT INTO api_rate_limits (rate_key, window_started_at, request_count, updated_at)
    VALUES (p_rate_key, NOW(), 1, NOW())
    ON CONFLICT (rate_key) DO UPDATE SET
        request_count = CASE
            WHEN api_rate_limits.window_started_at <= NOW() - make_interval(secs => p_window_seconds)
                THEN 1
            ELSE api_rate_limits.request_count + 1
        END,
        window_started_at = CASE
            WHEN api_rate_limits.window_started_at <= NOW() - make_interval(secs => p_window_seconds)
                THEN NOW()
            ELSE api_rate_limits.window_started_at
        END,
        updated_at = NOW()
    RETURNING request_count <= p_max_requests
$function$;

INSERT INTO users (
    id, email, password_hash, name, student_id, role, provider
) VALUES (
    '40000000-0000-0000-0000-000000000001',
    'legacy@example.com',
    '$2b$10$R09SClXZReLIXPLYF/t0nezxDXEBBiUQ8YFHoP3rZ50ynwzk52U3W',
    '기존 사용자',
    NULL,
    'user',
    'local'
);

INSERT INTO auth_tokens (user_id, token, expires_at) VALUES
    (
        '40000000-0000-0000-0000-000000000001',
        'sha256:98LYBMfn0abs1M6zx6IJwUpA7m3fRKMlF3JJNKPenhU',
        '2099-01-01T00:00:00Z'
    ),
    (
        '40000000-0000-0000-0000-000000000001',
        'legacy-plaintext-session-token',
        '2099-01-01T00:00:00Z'
    ),
    (
        '40000000-0000-0000-0000-000000000001',
        'expired-edge-session-token',
        '2020-01-01T00:00:00Z'
    );
