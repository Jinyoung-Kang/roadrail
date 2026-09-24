package com.roadrail.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 판단 규칙 R-DEC-01 (6-3). 근거 없이 결론만 내지 않는다 (FR-502, NFR-06).
 *
 * <pre>
 * 자동차 = 도로 예측(출발 시점, 기본 M1) + IC 접근 시간(기본 0)
 * 기차   = 역 대기(출발 + 역 접근 이후 첫 열차까지) + 역 접근 시간 + 계획 소요 + 최근 30일 평균 도착 지연
 * |차이| &lt; 10분 → "비슷함", 아니면 빠른 쪽
 * 경고: 강수확률 ≥ 60%, 길 돌발 1건 이상, 초미세먼지 나쁨(등급 3) 이상
 * </pre>
 */
public final class DecisionRule {
    public static final String RULE = "R-DEC-01";

    public record Car(Integer travelSec, String model, Double vsBaselinePct, int accessMin, String dataAge) {}

    /** egressMin = 도착역에서 목적지까지 (길 판단에서는 0, 어디서→어디로 판단에서는 거리 기반 추정) */
    public record Train(String trnNo, String planDep, int waitMin, int rideMin, Double avgArrDelayMin30d,
                        Double onTimeRate30d, int samples, boolean delayEstimated, int egressMin) {

        public Train(String trnNo, String planDep, int waitMin, int rideMin, Double avgArrDelayMin30d,
                     Double onTimeRate30d, int samples, boolean delayEstimated) {
            this(trnNo, planDep, waitMin, rideMin, avgArrDelayMin30d, onTimeRate30d, samples, delayEstimated, 0);
        }
    }

    public record Env(Integer popOrigin, Integer popDest, Integer pm25GradeOrigin, Integer pm25GradeDest) {}

    public record Params(int similarMin, int popWarn, int accessMin) {}

    public record Result(String rule, String verdict, String summary, Integer carTotalMin, Integer trainTotalMin,
                         Integer diffMin, List<String> reasons, List<String> warnings) {}

    private DecisionRule() {}

    public static Result decide(Car car, Train train, Env env, int incidentCount, Params p) {
        List<String> reasons = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Integer carMin = null, trainMin = null;

        if (car != null && car.travelSec() != null) {
            int drive = Math.round(car.travelSec() / 60f);
            carMin = drive + car.accessMin();
            reasons.add(String.format(Locale.ROOT, "자동차: %s · %s%s", fmtMin(drive), ForecastModels.label(car.model()),
                    car.accessMin() > 0 ? " + IC 접근 " + car.accessMin() + "분" : ""));
            if (car.vsBaselinePct() != null) {
                double pct = car.vsBaselinePct();
                reasons.add(String.format(Locale.ROOT, "최근 관측 통행시간이 같은 요일·시간대 중앙값보다 %.0f%% %s (%s)",
                        Math.abs(pct), pct >= 0 ? "김" : "짧음", car.dataAge()));
            } else if (car.travelSec() != null && !car.dataAge().isEmpty()) {
                reasons.add("기준선 표본이 4일 미만이라 평소 대비 비교는 하지 않습니다 (" + car.dataAge() + ")");
            }
        }
        if (train != null) {
            double delay = train.avgArrDelayMin30d() == null ? 0 : Math.max(train.avgArrDelayMin30d(), 0);
            trainMin = (int) Math.round(p.accessMin() + train.waitMin() + train.rideMin() + delay + train.egressMin());
            reasons.add(String.format(Locale.ROOT, "기차: %s 열차 %s 출발 · 역까지 %d분 + 대기 %d분 + 소요 %s + 평균 지연 %.1f분%s%s",
                    train.trnNo().replaceFirst("^0+", ""), train.planDep(), p.accessMin(), train.waitMin(), fmtMin(train.rideMin()), delay,
                    train.egressMin() > 0 ? " + 역에서 " + train.egressMin() + "분" : "",
                    train.delayEstimated() ? " (⚠ 중간역 지연 추정)" : ""));
            if (train.onTimeRate30d() != null) {
                reasons.add(String.format(Locale.ROOT, "해당 열차의 최근 30일 정시율 %.0f%% (표본 %d회)",
                        train.onTimeRate30d() * 100, train.samples()));
            }
        }

        if (env != null) {
            int popMax = Math.max(nz(env.popOrigin()), nz(env.popDest()));
            if (popMax >= p.popWarn()) warnings.add("강수확률 " + popMax + "% — 도로 지연 가능성");
            int gradeMax = Math.max(nz(env.pm25GradeOrigin()), nz(env.pm25GradeDest()));
            if (gradeMax >= 3) warnings.add("초미세먼지 " + (gradeMax >= 4 ? "매우나쁨" : "나쁨"));
        }
        if (incidentCount > 0) warnings.add("길 관련 돌발 안내 " + incidentCount + "건");

        if (carMin == null || trainMin == null) {
            String missing = carMin == null && trainMin == null ? "도로·철도" : carMin == null ? "도로" : "철도";
            reasons.add(missing + " 데이터가 없어 한쪽만 표시합니다");
            return new Result(RULE, "UNKNOWN", "비교할 수 없습니다 — " + missing + " 데이터가 부족합니다.",
                    carMin, trainMin, null, reasons, warnings);
        }
        int diff = carMin - trainMin;
        String verdict, summary;
        if (Math.abs(diff) < p.similarMin()) {
            verdict = "SIMILAR";
            summary = "자동차와 기차가 비슷합니다 (차이 " + Math.abs(diff) + "분).";
        } else if (diff > 0) {
            verdict = "TRAIN";
            summary = "기차가 약 " + fmtMin(diff) + " 빠를 것으로 보입니다.";
        } else {
            verdict = "CAR";
            summary = "자동차가 약 " + fmtMin(-diff) + " 빠를 것으로 보입니다.";
        }
        return new Result(RULE, verdict, summary, carMin, trainMin, diff, reasons, warnings);
    }

    static String fmtMin(int min) {
        if (min < 60) return min + "분";
        return min / 60 + "시간" + (min % 60 == 0 ? "" : " " + min % 60 + "분");
    }

    private static int nz(Integer v) { return v == null ? 0 : v; }
}
