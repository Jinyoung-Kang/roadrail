package com.roadrail.domain;

/**
 * 자동차의 마지막 출발 시각 (AR-2, 1단계) — 출발 시각 t 의 예측 소요 T(t)로 t + T(t) ≤ 기한인 가장 늦은 t.
 * 예측 호출은 바깥에서 주입한다(카카오 미래 운행 정보, 10분 단위 캐시). 호출은 maxCalls 이하 — 공유 예산 보호.
 * 확률은 내지 않는다(예측 오차 근거가 생기기 전까지, 결정 D1-A).
 */
public final class LatestDeparture {
    private LatestDeparture() {}

    public static final long STEP_SEC = 600;   // 카카오 출발 시각 단위(10분)

    /** 출발 epoch 초 → 예측 소요(초). 아직 모르면(조회 중) null */
    @FunctionalInterface
    public interface Eta { Integer seconds(long departEpoch); }

    /**
     * @param depart   마지막 출발 시각 (없으면 null)
     * @param pending  예측을 아직 받지 못해 멈춤 — 화면이 다시 부른다
     * @param feasible 기한 안에 도착하는 출발 시각이 earliest 이후에 있음 (모르면 true)
     */
    public record Result(Long depart, Integer durationSec, int calls, boolean pending, boolean feasible) {}

    static long floorStep(long t) { return Math.floorDiv(t, STEP_SEC) * STEP_SEC; }

    static long ceilStep(long s) { return Math.floorDiv(s + STEP_SEC - 1, STEP_SEC) * STEP_SEC; }

    public static Result search(Eta eta, long deadline, long earliest, int guessSec, int maxCalls) {
        long t = Math.max(floorStep(deadline - Math.max(guessSec, 0)), ceilStep(earliest));
        Long found = null;
        Integer foundSec = null;
        int calls = 0;
        while (calls < maxCalls) {
            Integer s = eta.seconds(t);
            calls++;
            if (s == null) return new Result(found, foundSec, calls, true, true);
            if (t + s <= deadline) {
                found = t;
                foundSec = s;
                // 더 늦게 떠나도 되는지 한 칸씩 더 본다 (짐작이 일렀을 때)
                while (calls < maxCalls && found + STEP_SEC < deadline) {
                    Integer s2 = eta.seconds(found + STEP_SEC);
                    calls++;
                    if (s2 == null) return new Result(found, foundSec, calls, true, true);
                    if (found + STEP_SEC + s2 > deadline) break;
                    found += STEP_SEC;
                    foundSec = s2;
                }
                return new Result(found, foundSec, calls, false, true);
            }
            if (t <= ceilStep(earliest)) return new Result(null, null, calls, false, false);   // 지금 떠나도 늦는다
            t = Math.max(t - ceilStep(t + s - deadline), ceilStep(earliest));
        }
        return new Result(null, null, calls, false, true);   // 호출 상한 — 모름
    }
}
