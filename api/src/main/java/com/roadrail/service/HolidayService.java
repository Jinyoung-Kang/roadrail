package com.roadrail.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** 공휴일 달력 (ref.holiday — 한국천문연구원 특일 정보, 수집기가 매월 갱신). 조회가 잦아 10분 동안 메모리에 둔다. */
@Service
public class HolidayService {
    private final JdbcClient jdbc;
    private volatile Map<LocalDate, String> days = Map.of();
    private volatile long loadedAt = 0;

    public HolidayService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private Map<LocalDate, String> days() {
        if (System.currentTimeMillis() - loadedAt > 600_000) {
            Map<LocalDate, String> m = new HashMap<>();
            jdbc.sql("SELECT day, name FROM ref.holiday").query(rs -> {
                m.put(rs.getObject(1, LocalDate.class), rs.getString(2));
            });
            days = m;
            loadedAt = System.currentTimeMillis();
        }
        return days;
    }

    /** 공휴일이면 이름 (예: 추석, 대체공휴일(개천절)) */
    public Optional<String> name(LocalDate d) {
        return Optional.ofNullable(days().get(d));
    }

    public boolean is(LocalDate d) {
        return days().containsKey(d);
    }
}
