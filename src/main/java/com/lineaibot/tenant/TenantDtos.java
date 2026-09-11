package com.lineaibot.tenant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalTime;

public final class TenantDtos {

    private TenantDtos() {}

    public record TenantCreate(
            @NotBlank @Size(max = 160) String name,
            @NotBlank
                    @Pattern(regexp = "^[a-z0-9][a-z0-9-]{2,79}$")
                    String slug,
            String timezone,
            Integer slotMinutes) {}

    public record TenantRead(
            String id,
            String name,
            String slug,
            String timezone,
            int slotMinutes,
            boolean active,
            Instant createdAt) {}

    public record TenantCreated(
            String id,
            String name,
            String slug,
            String timezone,
            int slotMinutes,
            boolean active,
            Instant createdAt,
            String adminApiKey) {}

    public record LineChannelUpsert(
            @NotBlank @Size(min = 8) String channelSecret,
            @NotBlank @Size(min = 8) String channelAccessToken,
            Boolean enabled) {}

    public record LineChannelRead(
            String tenantId, boolean configured, boolean enabled, String webhookUrl) {}

    public record BusinessHourUpsert(
            @Min(0) @Max(6) int weekday,
            LocalTime openTime,
            LocalTime closeTime,
            Boolean active) {}

    public record BusinessHourRead(
            String id,
            String tenantId,
            int weekday,
            LocalTime openTime,
            LocalTime closeTime,
            boolean active) {}

    public record BookingServiceCreate(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 2000) String description) {}

    public record BookingServiceRead(
            String id,
            String tenantId,
            String name,
            String description,
            boolean active) {}
}
