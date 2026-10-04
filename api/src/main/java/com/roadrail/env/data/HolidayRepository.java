package com.roadrail.env.data;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** 공휴일 달력 조회 (ref.holiday) */
@Repository
public class HolidayRepository {
    private final JdbcClient jdbc;

    public HolidayRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** 날짜 → 공휴일 이름 */
    public Map<LocalDate, String> all() {
        Map<LocalDate, String> m = new HashMap<>();
        jdbc.sql("SELECT day, name FROM ref.holiday").query(rs -> {
            m.put(rs.getObject(1, LocalDate.class), rs.getString(2));
        });
        return m;
    }
}
