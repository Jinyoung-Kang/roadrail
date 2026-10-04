package com.roadrail.shared;

import java.security.SecureRandom;

/** ULID 형식(26자, 시각순) 식별자 — 요청 traceId · 관리 명령 id */
public final class Ids {
    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RND = new SecureRandom();

    private Ids() {}

    public static String ulid() {
        long time = System.currentTimeMillis();
        char[] out = new char[26];
        for (int i = 9; i >= 0; i--) {
            out[i] = CROCKFORD[(int) (time & 31)];
            time >>>= 5;
        }
        for (int i = 10; i < 26; i++) out[i] = CROCKFORD[RND.nextInt(32)];
        return new String(out);
    }
}
