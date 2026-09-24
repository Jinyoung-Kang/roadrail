package com.roadrail.service;

import com.roadrail.external.KakaoLocalClient;
import com.roadrail.web.dto.TripDtos.Place;
import com.roadrail.web.dto.TripDtos.PlaceSearch;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 어디서 · 어디로 검색 — 전국 어디든: 행정구역(카카오 주소 검색) · 기차역(운행 중인 역, DB) · 장소(카카오 키워드).
 * 행정구역을 먼저, 그다음 역, 장소 순. 같은 이름·좌표는 하나로.
 */
@Service
public class PlaceService {
    private final KakaoLocalClient kakao;
    private final RailService rail;

    public PlaceService(KakaoLocalClient kakao, RailService rail) {
        this.kakao = kakao;
        this.rail = rail;
    }

    public PlaceSearch search(String q) {
        String query = q.trim();
        Map<String, Place> out = new LinkedHashMap<>();
        for (var h : kakao.regions(query)) {
            if ("REGION".equals(h.kind())) put(out, new Place(h.name(), h.address(), h.lat(), h.lon(), "REGION", null));
        }
        for (var s : rail.stations(query.replaceAll("역$", ""), 5)) {
            if (s.lat() != null) put(out, new Place(s.name() + "역", "최근 7일 " + s.trains7d() + "회 정차", s.lat(), s.lon(), "STATION", s.code()));
        }
        for (var h : kakao.places(query)) {
            if ("STATION".equals(h.kind())) continue;  // 기차역은 DB 목록(운행 확인된 역)으로
            put(out, new Place(h.name(), h.address(), h.lat(), h.lon(), "PLACE", null));
        }
        List<Place> items = new ArrayList<>(out.values());
        return new PlaceSearch(query, items.size() > 10 ? items.subList(0, 10) : items);
    }

    private static void put(Map<String, Place> m, Place p) {
        m.putIfAbsent(String.format(Locale.ROOT, "%s@%.3f,%.3f", p.name(), p.lat(), p.lon()), p);
    }
}
