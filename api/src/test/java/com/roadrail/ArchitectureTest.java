package com.roadrail;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 의존 방향 — 새 의존성 없이 소스의 com.roadrail 참조(import · 와일드카드 import · 코드 안의 정규화된 이름)로 검사한다 (ADR-025).
 * <pre>
 *   기능(corridor · trip · rail · env · ops):  web → app → data      (model 은 어느 계층이든 쓴다)
 *   바깥 → 안쪽만:  web · app · data → model · domain · shared,   app → external
 *   domain(순수 규칙)은 shared 만, model(레코드)은 domain · shared · model 만.   external · shared 는 기능을 모른다.
 *   web 은 저장소 · 외부 어댑터를 직접 쓰지 않는다.   SQL(JDBC)은 data 에만.   기능 사이: 다른 기능의 app · model 만, 순환 없음.
 * </pre>
 */
class ArchitectureTest {
    static final Path SRC = Path.of("src/main/java/com/roadrail");
    static final Set<String> FEATURES = Set.of("corridor", "trip", "rail", "env", "ops");
    /** com.roadrail.<패키지>.<클래스 또는 *> — import 든 코드 안이든 */
    static final Pattern REF = Pattern.compile("(?<![\\w.])com\\.roadrail\\.((?:[a-z]+\\.)*[a-z]+)\\.(?:[A-Z]|\\*)");
    static final Pattern JDBC = Pattern.compile("(?<![\\w.])(?:org\\.springframework\\.jdbc|javax\\.sql)\\.");

    /** 소스 파일 하나 — 패키지(com.roadrail 아래)와 참조한 com.roadrail 패키지들 */
    record Unit(String file, String pkg, Set<String> refs, boolean jdbc) {
        String feature() { return ArchitectureTest.feature(pkg); }
        String layer() { return ArchitectureTest.layer(pkg); }
    }

    static Unit unit(String file, String pkg, String text) {
        String code = text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "").replaceAll("(?m)^package .*$", "");
        Set<String> refs = new TreeSet<>();
        Matcher m = REF.matcher(code);
        while (m.find()) if (!m.group(1).equals(pkg)) refs.add(m.group(1));
        return new Unit(file, pkg, refs, JDBC.matcher(code).find());
    }

    static List<Unit> units() throws IOException {
        try (Stream<Path> s = Files.walk(SRC)) {
            return s.filter(p -> p.toString().endsWith(".java")).map(p -> {
                try {
                    return unit(SRC.relativize(p).toString(), SRC.relativize(p.getParent()).toString().replace('/', '.'), Files.readString(p));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        }
    }

    static String feature(String pkg) { return pkg.split("\\.")[0]; }
    static String layer(String pkg) { String[] p = pkg.split("\\."); return p.length > 1 ? p[1] : ""; }

    /** 규칙 위반 목록 — "파일 → 참조한 패키지 (규칙)" */
    static List<String> violations(List<Unit> units) {
        List<String> out = new ArrayList<>();
        for (Unit u : units) {
            if (FEATURES.contains(u.feature()) && !Set.of("web", "app", "data", "model").contains(u.layer())) out.add(u.file() + " (계층 이름)");
            if (!FEATURES.contains(u.feature()) && !u.pkg().matches("|shared(\\.web)?|domain|external")) out.add(u.file() + " (패키지 이름)");
            if (u.jdbc() && (FEATURES.contains(u.feature()) ? !u.layer().equals("data") : u.pkg().matches("domain|external"))) {
                out.add(u.file() + " (SQL 은 data 에만)");
            }
            for (String r : u.refs()) {
                String why = rule(u, r);
                if (why != null) out.add(u.file() + " → " + r + " (" + why + ")");
            }
        }
        return out;
    }

    private static String rule(Unit u, String r) {
        boolean other = FEATURES.contains(feature(r)) && !feature(r).equals(u.feature());
        if (u.pkg().equals("domain") && !r.equals("shared")) return "domain 은 shared 만";
        if (u.layer().equals("model") && !(r.equals("domain") || r.equals("shared") || layer(r).equals("model"))) return "model 은 domain · shared · model 만";
        if (u.layer().equals("data") && (layer(r).equals("app") || layer(r).equals("web") || r.startsWith("shared.web")
                || r.equals("external") || (layer(r).equals("data") && other))) return "data 는 쿼리 · 행 매핑만";
        if (u.layer().equals("app") && (layer(r).equals("web") || r.startsWith("shared.web") || (layer(r).equals("data") && other))) {
            return "app 은 웹 계층 · 다른 기능의 저장소를 모름";
        }
        if (u.layer().equals("web") && (layer(r).equals("data") || r.equals("external"))) return "web 은 저장소 · 외부 어댑터를 직접 쓰지 않음";
        if ((u.pkg().equals("external") || u.pkg().startsWith("shared")) && FEATURES.contains(feature(r))) return "external · shared 는 기능을 모름";
        if (u.pkg().equals("shared") && r.equals("shared.web")) return "shared 는 shared.web 을 모름";
        return null;
    }

    @Test
    void sourceFollowsTheLayering() throws IOException {
        List<Unit> units = units();
        assertThat(units).hasSizeGreaterThan(50);
        assertThat(units.stream().mapToInt(u -> u.refs().size()).sum()).as("참조를 실제로 읽는지").isGreaterThan(100);
        assertThat(violations(units)).isEmpty();
    }

    @Test
    void rulesCatchWildcardInlineAndJdbcReferences() {
        // 규칙이 헛돌지 않는지 — 와일드카드 import · 코드 안의 정규화된 이름 · JdbcTemplate · domain → model 을 잡는다
        List<Unit> bad = List.of(
                unit("trip/web/A.java", "trip.web", "import com.roadrail.rail.data.*;\nclass A {}"),
                unit("trip/web/B.java", "trip.web", "class B { Object x = new com.roadrail.external.KmaClient(null); }"),
                unit("rail/app/C.java", "rail.app", "import org.springframework.jdbc.core.JdbcTemplate;\nclass C {}"),
                unit("domain/D.java", "domain", "import com.roadrail.trip.model.TripDtos;\nclass D {}"),
                unit("env/data/E.java", "env.data", "import com.roadrail.env.app.EnvService;\nclass E {}"),
                unit("ops/app/F.java", "ops.app", "/* com.roadrail.ops.web.X 는 주석이라 셈하지 않는다 */ import com.roadrail.ops.data.OpsRepository;\nclass F {}"));
        assertThat(violations(bad)).containsExactly(
                "trip/web/A.java → rail.data (web 은 저장소 · 외부 어댑터를 직접 쓰지 않음)",
                "trip/web/B.java → external (web 은 저장소 · 외부 어댑터를 직접 쓰지 않음)",
                "rail/app/C.java (SQL 은 data 에만)",
                "domain/D.java → trip.model (domain 은 shared 만)",
                "env/data/E.java → env.app (data 는 쿼리 · 행 매핑만)");
    }

    @Test
    void featuresDependOnEachOtherWithoutCycles() throws IOException {
        Map<String, Set<String>> edges = new TreeMap<>();
        for (Unit u : units()) {
            if (!FEATURES.contains(u.feature())) continue;
            for (String r : u.refs()) {
                if (FEATURES.contains(feature(r)) && !feature(r).equals(u.feature())) {
                    edges.computeIfAbsent(u.feature(), k -> new TreeSet<>()).add(feature(r));
                }
            }
        }
        assertThat(edges).as("기능 의존이 실제로 있는지").isNotEmpty();
        // 깊이 우선 탐색으로 순환 찾기
        Map<String, Integer> state = new HashMap<>();
        Deque<String> path = new ArrayDeque<>();
        List<String> cycles = new ArrayList<>();
        for (String f : FEATURES) dfs(f, edges, state, path, cycles);
        assertThat(cycles).as("기능 의존: " + edges).isEmpty();
    }

    private static void dfs(String f, Map<String, Set<String>> edges, Map<String, Integer> state, Deque<String> path, List<String> cycles) {
        Integer s = state.get(f);
        if (s != null && s == 2) return;
        if (s != null && s == 1) { cycles.add(String.join(" → ", path) + " → " + f); return; }
        state.put(f, 1);
        path.addLast(f);
        for (String g : edges.getOrDefault(f, Set.of())) dfs(g, edges, state, path, cycles);
        path.removeLast();
        state.put(f, 2);
    }
}
