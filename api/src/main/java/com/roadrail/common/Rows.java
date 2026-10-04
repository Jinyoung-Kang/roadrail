package com.roadrail.common;

import java.sql.ResultSet;
import java.sql.SQLException;

/** 조회 결과 행 읽기 도우미 */
public final class Rows {
    private Rows() {}

    /** 숫자 열을 소수 digits 자리로 — NULL 이면 null */
    public static Double round(ResultSet rs, String col, int digits) throws SQLException {
        Object v = rs.getObject(col);
        if (v == null) return null;
        double d = ((Number) v).doubleValue();
        double f = Math.pow(10, digits);
        return Math.round(d * f) / f;
    }
}
