package com.roadrail.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TrainKindTest {
    @Test
    void numberRanges() {
        assertThat(TrainKind.of("00001", "서울", "부산", 130.0)).isEqualTo("KTX");
        assertThat(TrainKind.of("00381", "수서", "진주", 85.0)).isEqualTo("SRT");
        assertThat(TrainKind.of("00603", "수서", "광주송정", 150.0)).isEqualTo("SRT");
        assertThat(TrainKind.of("00701", "청량리", "부전", 80.0)).isEqualTo("KTX-이음");
        assertThat(TrainKind.of("00840", "동해", "서울", 70.0)).isEqualTo("KTX");
        assertThat(TrainKind.of("01001", "서울", "부산", 70.0)).isEqualTo("ITX-새마을");
        assertThat(TrainKind.of("01201", "용산", "익산", 50.0)).isEqualTo("무궁화호");
        assertThat(TrainKind.of("02001", "용산", "춘천", 50.0)).isEqualTo("ITX-청춘");
        assertThat(TrainKind.of("02601", "대곡", "의정부", 30.0)).isEqualTo("무궁화호");
    }

    @Test
    void extraTrainsUseEndpointsAndSpeed() {
        assertThat(TrainKind.of("04010", "광주송정", "수서", 150.0)).isEqualTo("SRT (임시)");
        assertThat(TrainKind.of("04002", "광주송정", "서울", 160.0)).isEqualTo("KTX (임시)");
        assertThat(TrainKind.of("04301", "동대구", "영주", 20.0)).isEqualTo("일반 열차 (임시)");
        assertThat(TrainKind.of("abc", null, null, null)).isEqualTo("열차");
    }
}
