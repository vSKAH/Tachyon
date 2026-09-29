package tech.skworks.tachyon.api.audit;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Short class explaination
 * <p>
 * Long class explaination with @link if needed
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 24/08/2026
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public record AuditEntry(@Nullable String playerId,
                         @NotNull String module, @NotNull String action,
                         @Nullable String description,
                         @NotNull AuditLevel auditLevel, long timestamp) {


    public AuditEntry {
        if (description == null) description = "NONE";
        if (playerId == null) playerId = "GLOBAL";
        if (auditLevel == null) auditLevel = AuditLevel.NORMAL;
        Objects.requireNonNull(module, "module cannot be null");
        Objects.requireNonNull(action, "action cannot be null");
    }

    public static AuditEntry of(@NotNull UUID playerId, @NotNull String module, @NotNull String action, @Nullable String description) {
        return new AuditEntry(playerId.toString(), module, action, description, AuditLevel.NORMAL, System.currentTimeMillis());
    }

    public static AuditEntry of(@NotNull UUID playerId, @NotNull String module, @NotNull String action) {
        return new AuditEntry(playerId.toString(), module, action, null, AuditLevel.NORMAL, System.currentTimeMillis());
    }

    public static AuditEntry of(@NotNull String module, @NotNull String action) {
        return new AuditEntry(null, module, action, null, AuditLevel.NORMAL, System.currentTimeMillis());
    }

    public static AuditEntry of(@NotNull String module, @NotNull String action, @Nullable String description) {
        return new AuditEntry(null, module, action, description, AuditLevel.NORMAL, System.currentTimeMillis());
    }

    public static AuditEntry of(@NotNull UUID playerId, @NotNull String module, @NotNull String action, @Nullable String description, @NotNull AuditLevel auditLevel) {
        return new AuditEntry(playerId.toString(), module, action, description, auditLevel, System.currentTimeMillis());
    }

    public static AuditEntry of(@NotNull UUID playerId, @NotNull String module, @NotNull String action, @NotNull AuditLevel auditLevel) {
        return new AuditEntry(playerId.toString(), module, action, null, auditLevel, System.currentTimeMillis());
    }

    public static AuditEntry of(@NotNull String module, @NotNull String action, @NotNull AuditLevel auditLevel) {
        return new AuditEntry(null, module, action, null, auditLevel, System.currentTimeMillis());
    }

    public static AuditEntry of(@NotNull String module, @NotNull String action, @Nullable String description, @NotNull AuditLevel auditLevel) {
        return new AuditEntry(null, module, action, description, auditLevel, System.currentTimeMillis());
    }
}

