package com.roadrail.rail.app;

import com.roadrail.domain.RailRouter;
import com.roadrail.rail.data.RailNetworkRepository;
import com.roadrail.rail.data.RailRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 철도망 자료 — 기차 여정(trip)이 CSA · 선로 경로 · 구간 시각에 쓰는 하루 정차 시간표 · OSM 선로 경로 · 구간 계획 시각 · 역 좌표 · 역 이름.
 * 캐시는 쓰는 쪽(RailJourneyService)이 정한다.
 */
@Service
public class RailNetworkService {
    private final RailNetworkRepository network;
    private final RailRepository rail;

    public RailNetworkService(RailNetworkRepository network, RailRepository rail) {
        this.network = network;
        this.rail = rail;
    }

    /** 구간의 기준일 실제 시간표 — 계획 시각을 모르면(보간) dep · arr 는 null */
    public record PlannedLeg(OffsetDateTime dep, OffsetDateTime arr, String grade) {}

    /** 그날(기준 운행일) 전국 여객열차의 역별 정차 */
    public List<RailRouter.Stop> dayStops(LocalDate refDate) {
        return network.dayStops(refDate);
    }

    /** (출발역, 도착역) → OSM 선로 경로 [[lat, lon], …] — 키 '출발역>도착역' */
    public Map<String, List<double[]>> trackLinks() {
        return network.trackLinks();
    }

    /** 구간의 기준일 실제 시간표 (운행계획 EXACT · TAGO TT) — 두 끝이 모두 실제 계획 시각일 때만 시각을 낸다 */
    public PlannedLeg plannedLeg(String from, String to, String trn, LocalDate ref) {
        return network.legPlan(from, to, trn, ref).map(r -> {
            boolean real = List.of("EXACT", "TT").contains(r.depBasis()) && List.of("EXACT", "TT").contains(r.arrBasis());
            return new PlannedLeg(real ? r.dep() : null, real ? r.arr() : null, r.grade());
        }).orElse(new PlannedLeg(null, null, null));
    }

    /** 역 좌표 [lat, lon] — 좌표를 모르는 역은 빈 값 */
    public Optional<double[]> findStationCoords(String code) {
        return network.stationCoords(code);
    }

    /** 역 이름 — 모르는 역 코드는 빈 값 */
    public Optional<String> findStationName(String code) {
        return rail.stationName(code);
    }
}
