package com.roadrail.domain;

import com.roadrail.external.AirKoreaClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class KmaGridAndSidoTest {

    /** collector test_latlon_to_grid_matches_kma_table 과 같은 기상청 격자표 값 */
    @ParameterizedTest
    @CsvSource({"37.5665,126.9780,60,127", "35.1796,129.0756,98,76", "36.3504,127.3845,67,100", "33.4996,126.5312,53,38"})
    void gridMatchesKmaTable(double lat, double lon, int nx, int ny) {
        assertThat(KmaGrid.of(lat, lon)).isEqualTo(new KmaGrid.Cell(nx, ny));
    }

    @ParameterizedTest
    @CsvSource({"서울특별시,종로구,서울", "전북특별자치도,전주시,전북", "강원특별자치도,강릉시,강원", "충청남도,천안시,충남",
            "전남광주통합특별시,광산구,광주", "전남광주통합특별시,순천시,전남", "경상북도,포항시,경북", "세종특별자치시,,세종"})
    void kakaoRegionToAirKoreaSido(String r1, String r2, String sido) {
        assertThat(AirKoreaClient.sidoOf(r1, r2)).isEqualTo(sido);
    }
}
