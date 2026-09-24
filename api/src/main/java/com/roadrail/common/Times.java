package com.roadrail.common;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class Times {
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    public static final ZoneOffset KST_OFFSET = ZoneOffset.ofHours(9);
    public static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private Times() {}

    public static OffsetDateTime now() {
        return OffsetDateTime.now(KST).withNano(0);
    }

    public static OffsetDateTime kst(OffsetDateTime t) {
        return t == null ? null : t.atZoneSameInstant(KST).toOffsetDateTime();
    }

    /** 5분 슬롯 내림 */
    public static OffsetDateTime alignTo5Min(OffsetDateTime t) {
        OffsetDateTime k = kst(t);
        return k.withMinute(k.getMinute() - k.getMinute() % 5).withSecond(0).withNano(0);
    }

    public static int slotIdx(OffsetDateTime t) {
        OffsetDateTime k = kst(t);
        return (k.getHour() * 60 + k.getMinute()) / 5;
    }

    /** 1=월 … 7=일 */
    public static int isoDow(OffsetDateTime t) {
        return kst(t).getDayOfWeek().getValue();
    }

    /** "5분 전", "3시간 20분 전" 같은 상대 표현 (신선도 표시용) */
    public static String ago(OffsetDateTime t, OffsetDateTime now) {
        if (t == null) return "—";
        long min = Math.max(Duration.between(t, now).toMinutes(), 0);
        if (min < 1) return "방금";
        if (min < 60) return min + "분 전";
        long h = min / 60, m = min % 60;
        if (h < 48) return m == 0 ? h + "시간 전" : h + "시간 " + m + "분 전";
        return (h / 24) + "일 전";
    }
}
