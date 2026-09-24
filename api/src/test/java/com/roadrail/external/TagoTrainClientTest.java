package com.roadrail.external;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TagoTrainClientTest {
    @Test
    void parsesPlanTimeAsKst() {
        // 응답 예: depplandtime "20260918050300" (KST)
        assertThat(TagoTrainClient.at("20260918050300").toString()).isEqualTo("2026-09-18T05:03+09:00");
    }

    @Test
    void onlyVerifiedAliases() {
        // 코레일 '여수엑스포' = TAGO '여수EXPO' (열차 번호 · 시각 일치로 검증). 그 밖의 이름은 바꾸지 않는다.
        assertThat(TagoTrainClient.ALIAS).containsOnlyKeys("여수엑스포").containsEntry("여수엑스포", "여수EXPO");
    }
}
