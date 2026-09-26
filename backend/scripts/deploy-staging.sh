#!/usr/bin/env bash

set -Eeuo pipefail
umask 077

readonly AWS_REGION="ap-northeast-2"
readonly SECRET_ID="unibus/staging/backend"
readonly APP_DIR="/opt/unibus/backend"
readonly RELEASE_DIR="${APP_DIR}/releases"
readonly ENV_FILE="${APP_DIR}/.env.staging"
readonly COMPOSE_FILE="${APP_DIR}/compose.staging.yaml"
readonly EXPECTED_IMAGE_PREFIX="041443079423.dkr.ecr.ap-northeast-2.amazonaws.com/unibus-backend-staging@sha256:"

image_uri="${1:-}"
git_sha="${2:-}"

if [[ "${EUID}" -ne 0 ]]; then
  echo "deploy-staging.sh must run as root" >&2
  exit 1
fi

if [[ ! "${image_uri}" =~ ^${EXPECTED_IMAGE_PREFIX}[a-f0-9]{64}$ ]]; then
  echo "invalid immutable staging image URI" >&2
  exit 1
fi

if [[ ! "${git_sha}" =~ ^[a-f0-9]{40}$ ]]; then
  echo "invalid Git SHA" >&2
  exit 1
fi

install -d -m 0750 -o root -g root "${APP_DIR}" "${RELEASE_DIR}"

if [[ ! -f "${COMPOSE_FILE}" ]]; then
  echo "missing ${COMPOSE_FILE}" >&2
  exit 1
fi

secret_file="$(mktemp "${APP_DIR}/.secret.XXXXXX")"
next_env_file="$(mktemp "${APP_DIR}/.env.staging.XXXXXX")"
rollback_env_file=""

cleanup() {
  rm -f "${secret_file}" "${next_env_file}"
  if [[ -n "${rollback_env_file}" ]]; then
    rm -f "${rollback_env_file}"
  fi
}
trap cleanup EXIT

aws secretsmanager get-secret-value \
  --region "${AWS_REGION}" \
  --secret-id "${SECRET_ID}" \
  --query SecretString \
  --output text > "${secret_file}"

required_keys=(
  SUPABASE_DB_URL
  SUPABASE_DB_USERNAME
  SUPABASE_DB_PASSWORD
  SUPABASE_API_URL
  SUPABASE_SERVICE_ROLE_KEY
  APP_CORS_ALLOWED_ORIGINS
  NAVER_CLIENT_ID
  NAVER_SECRET_KEY
  KAKAO_USER_INFO_URL
  VAPID_PUBLIC_KEY
  VAPID_PRIVATE_KEY
  VAPID_SUBJECT
  PUSH_ALLOWED_HOSTS
)

jq -e 'type == "object"' "${secret_file}" >/dev/null
for key in "${required_keys[@]}"; do
  if ! jq -e --arg key "${key}" \
    'has($key) and (.[$key] | type == "string" and length > 0)' \
    "${secret_file}" >/dev/null; then
    echo "staging secret is missing required key: ${key}" >&2
    exit 1
  fi
done

jq -r \
  --arg image "${image_uri}" \
  '. + {
      UNIBUS_BACKEND_IMAGE: $image,
      UNIBUS_STAGING_BIND_ADDRESS: "127.0.0.1",
      UNIBUS_STAGING_PORT: "18080",
      APP_CORS_ALLOWED_ORIGIN_PATTERNS: (.APP_CORS_ALLOWED_ORIGIN_PATTERNS // ""),
      DB_POOL_MAX_SIZE: (.DB_POOL_MAX_SIZE // "10"),
      DB_POOL_MIN_IDLE: (.DB_POOL_MIN_IDLE // "2"),
      DB_CONNECTION_TIMEOUT_MS: (.DB_CONNECTION_TIMEOUT_MS // "10000"),
      DB_VALIDATION_TIMEOUT_MS: (.DB_VALIDATION_TIMEOUT_MS // "3000"),
      UNIBUS_LOG_FORMAT: "logstash",
      UNIBUS_LOG_LEVEL_ROOT: (.UNIBUS_LOG_LEVEL_ROOT // "INFO"),
      UNIBUS_LOG_LEVEL_APP: (.UNIBUS_LOG_LEVEL_APP // "INFO"),
      AWS_REGION: "ap-northeast-2",
      UNIBUS_AWS_LOG_GROUP: "/unibus/staging/backend",
      UNIBUS_AWS_LOG_STREAM: "api"
    }
    | with_entries(.value |= tostring)
    | to_entries[]
    | "\(.key)=\(.value | @json)"' \
  "${secret_file}" > "${next_env_file}"

chmod 0600 "${next_env_file}"
chown root:root "${next_env_file}"

previous_image=""
if [[ -f "${ENV_FILE}" ]]; then
  previous_image="$(awk -F= '
    $1 == "UNIBUS_BACKEND_IMAGE" {
      value = substr($0, index($0, "=") + 1)
      gsub(/^"|"$/, "", value)
      print value
      exit
    }
  ' "${ENV_FILE}")"
fi

registry="${image_uri%%/*}"
aws ecr get-login-password --region "${AWS_REGION}" \
  | docker login --username AWS --password-stdin "${registry}" >/dev/null

mv "${next_env_file}" "${ENV_FILE}"
docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" config --quiet
docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" pull api
docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" up -d --no-deps api

healthy=false
for _ in $(seq 1 36); do
  container_id="$(docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" ps -q api)"
  if [[ -n "${container_id}" ]]; then
    health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${container_id}")"
    if [[ "${health}" == "healthy" ]]; then
      healthy=true
      break
    fi
  fi
  sleep 5
done

if [[ "${healthy}" != "true" ]] || ! curl --fail --silent --show-error \
  "http://127.0.0.1:18080/actuator/health/readiness" >/dev/null; then
  echo "staging readiness check failed" >&2
  if [[ "${previous_image}" =~ ^${EXPECTED_IMAGE_PREFIX}[a-f0-9]{64}$ ]] \
    && [[ "${previous_image}" != "${image_uri}" ]]; then
    echo "restoring the previous staging image" >&2
    rollback_env_file="$(mktemp "${APP_DIR}/.env.rollback.XXXXXX")"
    awk -v previous="${previous_image}" '
      $1 ~ /^UNIBUS_BACKEND_IMAGE=/ {
        print "UNIBUS_BACKEND_IMAGE=\"" previous "\""
        next
      }
      { print }
    ' "${ENV_FILE}" > "${rollback_env_file}"
    chmod 0600 "${rollback_env_file}"
    mv "${rollback_env_file}" "${ENV_FILE}"
    rollback_env_file=""
    docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" pull api
    docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" up -d --no-deps api
  fi
  exit 1
fi

if [[ "${previous_image}" =~ ^${EXPECTED_IMAGE_PREFIX}[a-f0-9]{64}$ ]] \
  && [[ "${previous_image}" != "${image_uri}" ]]; then
  printf '%s\n' "${previous_image}" > "${RELEASE_DIR}/previous-image"
  chmod 0600 "${RELEASE_DIR}/previous-image"
fi

jq -n \
  --arg image "${image_uri}" \
  --arg gitSha "${git_sha}" \
  --arg deployedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  '{image: $image, gitSha: $gitSha, deployedAt: $deployedAt}' \
  > "${RELEASE_DIR}/current-release.json"
chmod 0600 "${RELEASE_DIR}/current-release.json"

echo "staging deployment is healthy"
