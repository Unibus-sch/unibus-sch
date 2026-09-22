package com.unibus.backend.user;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.unibus.backend.common.api.ApiRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
class UserAdminService {

    private static final Set<String> ROLES = Set.of("user", "admin", "driver");
    private final UserAdminRepository repository;
    private final ObjectMapper objectMapper;

    UserAdminService(UserAdminRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    List<UserResponse> findAll() {
        return repository.findAll();
    }

    @Transactional
    UserResponse updateName(String rawId, JsonNode body, String adminId) {
        String name = body != null && body.has("name") && body.get("name").isString()
            ? body.get("name").stringValue().trim() : "";
        if (name.isEmpty()) throw badRequest("이름을 입력해주세요");
        if (name.length() > 50) throw badRequest("이름은 50자 이하로 입력해주세요");
        UUID id = parse(rawId);
        UserResponse updated = id == null ? null : repository.updateName(id, name).orElse(null);
        if (updated == null) throw new ApiRequestException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to update user");
        repository.log(UUID.fromString(adminId), "user_name_updated", id, "{}");
        return updated;
    }

    @Transactional
    Map<String, Object> updateRole(String rawId, JsonNode body, String adminId) {
        String role = body != null && body.has("role") && body.get("role").isString()
            ? body.get("role").stringValue() : null;
        if (!ROLES.contains(role)) throw badRequest("Invalid role. Must be user, admin, or driver");
        UUID id = parse(rawId);
        UUID adminUuid = UUID.fromString(adminId);
        if (id != null && id.equals(adminUuid)) throw badRequest("자신의 역할은 변경할 수 없습니다");
        UserResponse target = id == null ? null : repository.find(id).orElse(null);
        if (target == null) throw new ApiRequestException(HttpStatus.NOT_FOUND, "User not found");

        if (!"driver".equals(role)) {
            for (UserAdminRepository.DrivenBus bus : repository.findDrivenBuses(id)) {
                if (bus.running() && rawId.equals(bus.currentDriverId())) {
                    try {
                        repository.forceStop(bus.id());
                    } catch (RuntimeException error) {
                        throw new ApiRequestException(HttpStatus.CONFLICT, "운행 종료 후 역할을 변경해 주세요");
                    }
                }
            }
            repository.clearAssignments(id);
        }
        repository.updateRole(id, role);
        try {
            repository.log(adminUuid, "user_role_updated", id, objectMapper.writeValueAsString(
                Map.of("previousRole", target.role(), "role", role)
            ));
        } catch (JacksonException error) {
            throw new IllegalStateException(error);
        }
        return Map.of("id", rawId, "role", role);
    }

    private UUID parse(String value) {
        try { return UUID.fromString(value); } catch (IllegalArgumentException error) { return null; }
    }

    private ApiRequestException badRequest(String message) {
        return new ApiRequestException(HttpStatus.BAD_REQUEST, message);
    }
}
