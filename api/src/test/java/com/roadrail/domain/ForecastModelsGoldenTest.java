package com.roadrail.domain;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 언어 간 계약: collector(pytest)와 같은 fixtures/forecast_cases.json 을 검증한다. */
class ForecastModelsGoldenTest {

    @TestFactory
    List<DynamicTest> goldenCases() {
        Path file = Path.of(System.getProperty("fixtures.dir", "../fixtures"), "forecast_cases.json");
        JsonNode doc = JsonMapper.builder().build().readTree(file.toFile());
        Map<ForecastModels.Key, ForecastModels.Value> bl = new HashMap<>();
        for (JsonNode e : doc.get("baseline")) {
            bl.put(new ForecastModels.Key(e.get("dow").asInt(), e.get("slot").asInt()),
                    new ForecastModels.Value(e.get("p50").asInt(), e.get("n").asInt()));
        }
        assertThat(doc.get("cases").size()).isGreaterThanOrEqualTo(6);
        return doc.get("cases").valueStream().map(c -> DynamicTest.dynamicTest(c.get("name").asString(), () -> {
            var f = ForecastModels.predictAll(bl, OffsetDateTime.parse(c.get("current").asString()),
                    c.get("currentSec").asInt(), OffsetDateTime.parse(c.get("target").asString()), c.get("tauMin").asDouble());
            JsonNode exp = c.get("expected");
            assertThat(f.m0()).isEqualTo(exp.get("M0").isNull() ? null : exp.get("M0").asInt());
            assertThat(f.m1()).isEqualTo(exp.get("M1").isNull() ? null : exp.get("M1").asInt());
            assertThat(f.persistence()).isEqualTo(exp.get("persistence").asInt());
        })).toList();
    }
}
