import Link from "next/link";

/**
 * 메인 화면 소개 — 처음 온 사람이 "무엇을 하는 서비스인가"를 한 화면에서 알 수 있게.
 * 한 문장 정의 → 3단계 사용 흐름 → 다른 메뉴 → 데이터 출처 순서 (Tesla 식: 큰 제목 + 짧은 문장 + 간결한 타일).
 */
const STEPS = [
  { n: "1", title: "출발지·도착지 검색", body: "전국의 지역 · 기차역 · 장소를 검색해 고릅니다. 출발 시점(지금 ~ 3시간 뒤)도 정할 수 있습니다." },
  { n: "2", title: "같은 출발 시각으로 비교", body: "자동차는 카카오 경로 예측, 기차는 가까운 역에서 목적지 가까운 역까지 코레일 환승 경로로 계산해 도착 예정 시각을 나란히 보여 줍니다." },
  { n: "3", title: "근거까지 확인", body: "최근 30일 실제 운행으로 계산한 정시율, 날씨 · 대기질, 경로 주변 돌발 안내와 각 데이터의 시각을 함께 보여 줍니다." },
];

const MENUS = [
  { href: "/road", title: "도로 분석", body: "추천 · 고속도로 회피 경로, 도로 구성, 출발 시각별 소요" },
  { href: "/rail", title: "철도 분석", body: "전국 모든 역 쌍의 정시율 · 지연 분포 · 날짜별 운행" },
  { href: "/forecast", title: "예측 성능", body: "고속도로 통행시간 예측 모델과 백테스트 오차" },
  { href: "/ops", title: "수집 상태", body: "수집 작업 · 호출 예산 · 오류 상세" },
];

const SOURCES = ["한국도로공사", "한국철도공사", "카카오모빌리티", "기상청", "에어코리아", "국토교통부 TAGO", "OpenStreetMap"];

export default function About() {
  return (
    <section id="about" aria-labelledby="about-title" className="bg-white py-16 sm:py-24">
      <div className="mx-auto max-w-[1200px] px-4 sm:px-8">
        <div className="mx-auto max-w-3xl text-center">
          <p className="eyebrow mb-2">로드레일 RoadRail</p>
          <h2 id="about-title" className="text-[28px] font-medium text-ink sm:text-[32px]">차로 갈까, 기차로 갈까 — 공공데이터로 비교합니다</h2>
          <p className="mt-4 text-[15px] leading-relaxed text-ink2">
            로드레일은 같은 출발 시각을 기준으로 <b className="font-medium text-ink">자동차</b>와 <b className="font-medium text-ink">기차</b>의
            도착 시각을 계산해, 어느 쪽이 빠를지 근거와 함께 알려 주는 이동 판단 서비스입니다.
          </p>
        </div>

        <ol className="mt-12 grid gap-4 sm:grid-cols-3">
          {STEPS.map((s) => (
            <li key={s.n} className="tile p-6">
              <span className="flex h-7 w-7 items-center justify-center rounded-full bg-ink text-[13px] font-medium text-white" aria-hidden>{s.n}</span>
              <p className="mt-4 text-[17px] font-medium text-ink">{s.title}</p>
              <p className="mt-2 text-sm leading-relaxed text-muted">{s.body}</p>
            </li>
          ))}
        </ol>

        <div className="mt-6 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {MENUS.map((m) => (
            <Link key={m.href} href={m.href} className="group rounded bg-mist px-5 py-4 transition hover:bg-cloud">
              <p className="flex items-center justify-between text-sm font-medium text-ink">
                {m.title}<span className="text-muted transition group-hover:translate-x-0.5" aria-hidden>→</span>
              </p>
              <p className="mt-1 text-xs leading-relaxed text-muted">{m.body}</p>
            </Link>
          ))}
        </div>

        <p className="mt-8 text-center text-xs leading-relaxed text-muted">
          데이터 · {SOURCES.join(" · ")}
          <br />모든 수치에는 데이터 시각과 표본 수를 함께 표시하고, 값을 알 수 없으면 비워 둡니다. 참고 정보이며 교통 안내(내비게이션) 서비스가 아닙니다.
        </p>
      </div>
    </section>
  );
}
