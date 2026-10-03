package com.miunlock.sniper.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;

public final class Schedule {

    public static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    public static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    private static final long DAY_MS = 86400000L;

    private Schedule() {
    }

    public static long nextOccurrence(long trustedNowMs, ZoneId zone, int hour, int minute) {
        final Instant now = Instant.ofEpochMilli(trustedNowMs);
        ZonedDateTime candidate = now.atZone(zone).toLocalDate().atTime(hour, minute).atZone(zone);
        if (!candidate.toInstant().isAfter(now.plusMillis(2000))) {
            candidate = candidate.plusDays(1);
        }
        return candidate.toInstant().toEpochMilli();
    }

    public static long resolve(String mode, long customMs, long trustedNowMs) {
        if (Prefs.MODE_CUSTOM.equals(mode)) {
            long target = customMs;
            if (target <= 0L) {
                return 0L;
            }
            while (target <= trustedNowMs + 2000L) {
                target += DAY_MS;
            }
            return target;
        }
        if (Prefs.MODE_MSK_1700.equals(mode)) {
            return nextOccurrence(trustedNowMs, MOSCOW, 17, 0);
        }
        if (Prefs.MODE_MSK_1900.equals(mode)) {
            return nextOccurrence(trustedNowMs, MOSCOW, 19, 0);
        }
        return nextOccurrence(trustedNowMs, BEIJING, 0, 0);
    }

    public static String modeLabel(String mode) {
        if (Prefs.MODE_MSK_1700.equals(mode)) {
            return "17:00 МСК";
        }
        if (Prefs.MODE_MSK_1900.equals(mode)) {
            return "19:00 МСК (= 00:00 Пекин)";
        }
        if (Prefs.MODE_CUSTOM.equals(mode)) {
            return "своё время";
        }
        return "00:00 Пекин (UTC+8)";
    }

    public static String beijingStamp(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(BEIJING).toLocalDateTime().toString().replace('T', ' ');
    }

    public static String moscowStamp(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(MOSCOW).toLocalDateTime().toString().replace('T', ' ');
    }

    public static String countdown(long remainingMs) {
        final long total = Math.max(remainingMs, 0L);
        final long hours = total / 3600000L;
        final long minutes = (total % 3600000L) / 60000L;
        final long seconds = (total % 60000L) / 1000L;
        final long millis = total % 1000L;
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis);
    }
}
