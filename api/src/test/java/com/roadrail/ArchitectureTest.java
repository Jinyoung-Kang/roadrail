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
 * 의존 방향 — 새 의존성 없이 import 문으로 검사한다 (ADR-025).
 * <pre>
 *   기능(corridor · trip · rail · env · ops):  web → app → data      (model 은 어느 계층이든 쓴다)
 *   바깥 → 안쪽만:  web · app · data → model · domain · shared,   app → external
 *   domain(순수 규칙) · model(레코드)은 바깥 계층을 모른다.   external(외부 API 어댑터)은 기능을 모른다.
 *   SQL(JdbcClient)은 data 에만.   기능 사이: 다른 기능의 app · model 만, 순환 없음.
 * </pre>
 */
class ArchitectureTest {
    static final Path SRC = Path.of("src/main/java/com/roadrail");
    static final Set<String> FEATURES = Set.of("corridor", "trip", "rail", "env", "ops");
    static final Pattern IMPORT = Pattern.compile("^import (?:static )?com\\.roadrail\\.([a-z.]+?)\\.[A-Z]", Pattern.MULTILINE);

    /** 소스 파일 하나 — 패키지(com.roadrail 아래)와 import 한 com.roadrail 패키지들 */
    record Unit(String file, String pkg, Set<String> imports, String text) {
        String feature() { return pkg.split("\\.")[0]; }
        String layer() { String[] p = pkg.split("\\."); return p.length > 1 ? p[1] : ""; }
    }

    static List<Unit> units() throws IOException {
        try (Stream<Path> s = Files.walk(SRC)) {
            return s.filter(p -> p.toString().endsWith(".java")).map(p -> {
                try {
                    String text = Files.readString(p);
                    String rel = SRC.relativize(p.getParent()).toString().replace('/', '.');
                    Set<String> imports = new TreeSet<>();
                    Matcher m = IMPORT.matcher(text);
                    while (m.find()) imports.add(m.group(1));
                    return new Unit(SRC.relativize(p).toString(), rel, imports, text);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        }
    }

    static String feature(String pkg) { return pkg.split("\\.")[0]; }
    static String layer(String pkg) { String[] p = pkg.split("\\."); return p.length > 1 ? p[1] : ""; }

    static List<String> violations(List<Unit> units, java.util.function.BiPredicate<Unit, String> forbidden) {
        List<String> out = new ArrayList<>();
        for (Unit u : units) for (String imp : u.imports()) if (forbidden.test(u, imp)) out.add(u.file() + " → " + imp);
        return out;
    }

    @Test
    void everyFeatureFollowsWebAppDataModel() throws IOException {
        List<Unit> units = units();
        assertThat(units).isNotEmpty();
        for (Unit u : units) {
            if (FEATURES.contains(u.feature())) {
                assertThat(u.layer()).as(u.file()).isIn("web", "app", "data", "model");
            } else {
                assertThat(u.pkg()).as(u.file()).matches("|shared(\\.web)?|domain|external");
            }
        }
    }

    @Test
    void innerLayersDoNotKnowOuterOnes() throws IOException {
        List<Unit> units = units();
        // domain · model: 규칙과 레코드 — 서비스 · 저장소 · 웹 · 외부 어댑터를 모른다
        assertThat(violations(units, (u, imp) -> (u.pkg().equals("domain") || u.layer().equals("model"))
                && !(imp.equals("domain") || imp.equals("shared") || layer(imp).equals("model")))).isEmpty();
        // data: 쿼리와 행 매핑만 — app · web 을 모르고, 다른 기능의 저장소도 쓰지 않는다
        assertThat(violations(units, (u, imp) -> u.layer().equals("data")
                && (layer(imp).equals("app") || layer(imp).equals("web") || imp.startsWith("shared.web")
                    || imp.equals("external") || (layer(imp).equals("data") && !feature(imp).equals(u.feature()))))).isEmpty();
        // app: 웹 계층을 모르고, 다른 기능의 데이터에 바로 손대지 않는다(그 기능의 app 을 거친다)
        assertThat(violations(units, (u, imp) -> u.layer().equals("app")
                && (layer(imp).equals("web") || imp.startsWith("shared.web")
                    || (layer(imp).equals("data") && !feature(imp).equals(u.feature()))))).isEmpty();
        // web: 저장소를 직접 쓰지 않는다
        assertThat(violations(units, (u, imp) -> u.layer().equals("web") && layer(imp).equals("data"))).isEmpty();
        // external · shared: 기능을 모른다
        assertThat(violations(units, (u, imp) -> (u.pkg().equals("external") || u.pkg().startsWith("shared"))
                && FEATURES.contains(feature(imp)))).isEmpty();
        assertThat(violations(units, (u, imp) -> u.pkg().equals("shared") && imp.equals("shared.web"))).isEmpty();
    }

    @Test
    void sqlLivesOnlyInDataLayer() throws IOException {
        List<String> out = units().stream()
                .filter(u -> !u.layer().equals("data") && u.text().contains("org.springframework.jdbc.core.simple.JdbcClient"))
                .map(Unit::file).toList();
        assertThat(out).as("JdbcClient 는 data 계층에만").isEmpty();
    }

    @Test
    void featuresDependOnEachOtherWithoutCycles() throws IOException {
        Map<String, Set<String>> edges = new TreeMap<>();
        for (Unit u : units()) {
            if (!FEATURES.contains(u.feature())) continue;
            for (String imp : u.imports()) {
                if (FEATURES.contains(feature(imp)) && !feature(imp).equals(u.feature())) {
                    edges.computeIfAbsent(u.feature(), k -> new TreeSet<>()).add(feature(imp));
                }
            }
        }
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
