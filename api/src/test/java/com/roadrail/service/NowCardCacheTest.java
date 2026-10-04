package com.roadrail.service;

import com.roadrail.web.dto.NowDtos;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 길 판단 카드 — 카카오 예측이 아직 없는 카드는 캐시하지 않는다 (RVW-06) */
class NowCardCacheTest {
    private static NowDtos.NowCard card(NowDtos.Kakao kakao) {
        var road = new NowDtos.Road(null, null, null, null, null, null, OffsetDateTime.now(), 0, null, null, List.of(), kakao,
                "서울", "대전", 139.0);
        return new NowDtos.NowCard("SEL-DJN", "서울–대전", "DN", OffsetDateTime.now(), OffsetDateTime.now(), 20, 0, "OK", road, null,
                Map.of(), List.of(), null, Map.of(), "", "MISS");
    }

    @Test
    void cardWithoutKakaoYetIsNotCachedWhileKakaoIsOn() {
        assertThat(NowCardService.cacheable(card(null), true)).isFalse();
        assertThat(NowCardService.cacheable(card(new NowDtos.Kakao(5400, 139000, "202610041700")), true)).isTrue();
        assertThat(NowCardService.cacheable(card(null), false)).isTrue();   // 카카오 키가 없으면 늘 비어 있다 — 캐시해도 된다
    }
}
