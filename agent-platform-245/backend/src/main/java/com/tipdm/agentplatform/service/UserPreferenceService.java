package com.tipdm.agentplatform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.domain.UserPreference;
import com.tipdm.agentplatform.repository.UserPreferenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 用户偏好读写。
 *
 * <p><b>读不到行就返回默认值，不报错</b>：偏好是体验参数，没有它系统照常工作。
 * 反过来说，一旦让「没设置过偏好」变成一个错误，新用户第一次进设置页就会看到红色报错。</p>
 */
@Service
public class UserPreferenceService {

    private static final Logger log = LoggerFactory.getLogger(UserPreferenceService.class);

    private final UserPreferenceRepository repository;
    private final ObjectMapper mapper;

    public UserPreferenceService(UserPreferenceRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /** 读某人的偏好；没有记录时返回 {@link UserPreferencePayload#DEFAULT}。 */
    @Transactional(readOnly = true)
    public UserPreferencePayload get(UUID userId) {
        return repository.findById(userId)
                .map(this::parse)
                .orElse(UserPreferencePayload.DEFAULT);
    }

    /**
     * 整体保存某人的偏好。
     *
     * <p>写入前先 {@code normalize()}：非法字段被替换为默认值并记 warn 日志，
     * 而不是拒绝整个请求。理由见 {@link UserPreferencePayload#normalize()}。</p>
     */
    @Transactional
    public UserPreferencePayload save(UUID userId, UserPreferencePayload payload) {
        UserPreferencePayload normalized = payload.normalize();
        Instant now = Instant.now();
        UserPreference row = repository.findById(userId).orElseGet(() -> {
            UserPreference r = new UserPreference();
            r.setUserId(userId);
            r.setCreatedAt(now);
            return r;
        });
        row.setPrefs(toMap(normalized));
        row.setUpdatedAt(now);
        repository.save(row);
        log.info("用户偏好已更新：{}", userId);
        return normalized;
    }

    /**
     * 解析库里存的偏好。
     *
     * <p>解析失败退回默认值而非抛错：坏数据不该让用户的整个设置页打不开。
     * 但要记 error——这是唯一能发现「谁写坏了」的时机。</p>
     */
    private UserPreferencePayload parse(UserPreference row) {
        Map<String, Object> raw = row.getPrefs();
        if (raw == null || raw.isEmpty()) {
            return UserPreferencePayload.DEFAULT;
        }
        try {
            UserPreferencePayload p = mapper.convertValue(raw, UserPreferencePayload.class);
            return (p == null ? UserPreferencePayload.DEFAULT : p).normalize();
        } catch (Exception e) {
            log.error("用户 {} 的偏好数据无法解析，已退回默认值", row.getUserId(), e);
            return UserPreferencePayload.DEFAULT;
        }
    }

    /** record → Map：键取 {@code @JsonProperty} 上的名字，与 JSON 形态一致。 */
    private Map<String, Object> toMap(UserPreferencePayload payload) {
        Map<String, Object> map = mapper.convertValue(payload, new TypeReference<>() {
        });
        return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
    }

    /** 免打扰时段是否覆盖当前时刻。 */
    public static boolean inQuietHours(UserPreferencePayload prefs, java.time.LocalTime now) {
        if (prefs.dndEnabled() == null || !prefs.dndEnabled()) {
            return false;
        }
        java.time.LocalTime start = parseClock(prefs.dndStart(), java.time.LocalTime.of(22, 0));
        java.time.LocalTime end = parseClock(prefs.dndEnd(), java.time.LocalTime.of(8, 0));
        // end <= start 表示跨零点（如 22:00 → 08:00），需要拆成两段判断
        if (!end.isAfter(start)) {
            return !now.isBefore(start) || now.isBefore(end);
        }
        return !now.isBefore(start) && now.isBefore(end);
    }

    private static java.time.LocalTime parseClock(String raw, java.time.LocalTime fallback) {
        if (raw == null || !raw.matches("([01]\\d|2[0-3]):[0-5]\\d")) {
            return fallback;
        }
        String[] parts = raw.split(":");
        return java.time.LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }
}
