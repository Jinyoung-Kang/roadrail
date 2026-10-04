package com.roadrail.rail.app;

import com.roadrail.rail.data.RailRepository;
import com.roadrail.rail.data.TimetableRepository;
import com.roadrail.shared.SingleFlight;
import com.roadrail.shared.Times;
import com.roadrail.external.TagoTrainClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

/**
 * 역 쌍 · 날짜별 실제 시간표(TAGO) 를 rail.tt_plan 에 채운다 — 한 번 받은 역 쌍 · 날짜는 rail.tt_fetch 에 남겨 다시 부르지 않는다.
 * 지난 날짜만 받는다(당일 · 미래는 TAGO 에 시간표가 없거나 일부만 있음, 2026-09-25 실측).
 * 지난 날짜도 비어 있는 날이 있어, 받은 편수가 코레일 운행 편수의 80% 미만이면 불완전으로 두고 12시간 뒤 다시 받는다.
 * 같은 역 쌍 · 날짜를 여러 요청이 동시에 원하면 호출은 한 번만 (in-flight 공유), 동시 호출은 최대 6건.
 * 요청 하나가 새로 받는 날짜는 최근 것부터 {@value #MAX_NEW_PER_REQUEST}일, 요청이 시작하는 받기는 시간당 {@value #MAX_NEW_PER_HOUR}건까지 —
 * 공개 기간 조회(최대 366일) 몇십 번으로 공용 일일 예산(9,000)을 다 쓰지 않게(RVW-03). 남은 날짜는 다음 요청이 이어 받는다.
 */
@Service
public class TimetableService {
    private static final Logger log = LoggerFactory.getLogger(TimetableService.class);
    private final TimetableRepository repo;
    private final RailRepository rail;
    private final TagoTrainClient tago;
    private final ExecutorService exec;
    private final Semaphore gate = new Semaphore(6);
    private final SingleFlight<String, Boolean> inflight = new SingleFlight<>();
    static final int MAX_NEW_PER_REQUEST = 31;
    static final int MAX_NEW_PER_HOUR = 600;
    private final HourlyLimit hourly = new HourlyLimit(MAX_NEW_PER_HOUR);

    public TimetableService(TimetableRepository repo, RailRepository rail, TagoTrainClient tago, ExecutorService exec) {
        this.exec = exec;
        this.repo = repo;
        this.rail = rail;
        this.tago = tago;
    }

    /**
     * dep → arr 의 dates 시간표가 DB 에 있게 한다. wait 까지만 기다리고(0 이면 기다리지 않음) 나머지는 뒤에서 계속 받는다.
     * 반환: 기다린 뒤 모든 날짜가 준비됐는지.
     */
    public boolean ensure(String dep, String arr, Collection<LocalDate> dates, Duration wait) {
        if (!tago.enabled() || dates.isEmpty()) return true;
        LocalDate today = Times.now().toLocalDate();
        List<LocalDate> past = dates.stream().filter(d -> d.isBefore(today)).distinct().toList();
        if (past.isEmpty()) return true;
        Set<LocalDate> have = new HashSet<>(repo.fetchedDates(dep, arr, past));
        List<CompletableFuture<Boolean>> todo = new ArrayList<>();
        int started = 0;
        boolean deferred = false;
        for (LocalDate d : missingRecentFirst(past, have)) {
            String key = dep + ">" + arr + "@" + d;
            boolean joining = inflight.running(key);   // 이미 받는 중이면 함께 기다린다(새 호출 아님)
            if (!joining && (started >= MAX_NEW_PER_REQUEST || !hourly.tryTake(System.currentTimeMillis()))) {
                deferred = true;
                continue;
            }
            if (!joining) started++;
            todo.add(inflight.run(key, () -> fetch(dep, arr, d), exec));
        }
        if (deferred) return false;   // 남은 날짜가 있다 — 기다리지 않고 '받는 중'으로
        if (todo.isEmpty()) return true;
        if (wait.isZero()) return false;
        try {
            CompletableFuture.allOf(todo.toArray(CompletableFuture[]::new)).get(wait.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            return false;
        }
    }

    /** 받아야 할 날짜 — 받아 둔 날짜를 빼고 최근 것부터 */
    static List<LocalDate> missingRecentFirst(Collection<LocalDate> past, Set<LocalDate> have) {
        return past.stream().filter(d -> !have.contains(d)).sorted(Comparator.reverseOrder()).toList();
    }

    /** 고정 창(시 단위) 호출 상한 — API 는 한 인스턴스라 메모리로 충분하다. 잠금 안에는 계산만 있다 */
    static final class HourlyLimit {
        private final int max;
        private long hour = -1;
        private int used;

        HourlyLimit(int max) {
            this.max = max;
        }

        synchronized boolean tryTake(long nowMillis) {
            long h = nowMillis / 3_600_000;
            if (h != hour) {
                hour = h;
                used = 0;
            }
            if (used >= max) return false;
            used++;
            return true;
        }
    }

    /** 운행 기록이 있는 날짜 (od_trips 를 부르기 전에 받을 날짜를 고른다) */
    public List<LocalDate> runDates(String dep, String arr, LocalDate from, LocalDate to) {
        return repo.runDates(dep, arr, from, to);
    }

    /** 받고 기록했으면 true (SingleFlight 의 결과 값 — 기다리는 쪽은 완료 여부만 본다) */
    private boolean fetch(String dep, String arr, LocalDate date) {
        try {
            gate.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        try {
            String depNode = tago.nodeId(name(dep)), arrNode = tago.nodeId(name(arr));
            if (depNode == null || arrNode == null) {
                if (!tago.nodes().isEmpty()) repo.recordFetch(dep, arr, date, null, true);  // TAGO 에 없는 역 → 조회 불가로 기록
                return false;
            }
            var plans = tago.plans(depNode, arrNode, date);
            if (plans == null) return false;  // 호출 실패 → 기록하지 않고 다음에 다시
            if (!plans.isEmpty()) {
                repo.savePlans(dep, arr, date, plans.stream()
                        .map(p -> new TimetableRepository.PlanRow(p.trnNo(), p.grade(), p.planDep(), p.planArr())).toList());
            }
            int korail = repo.odTripCount(dep, arr, date);
            repo.recordFetch(dep, arr, date, plans.size(), plans.size() >= 0.8 * korail);
            return true;
        } catch (RuntimeException e) {
            log.warn("시간표 저장 실패 {}→{} {}: {}", dep, arr, date, e.getMessage());
            return false;
        } finally {
            gate.release();
        }
    }

    private String name(String code) {
        return rail.stationName(code).orElse(null);
    }
}
