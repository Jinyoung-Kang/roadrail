import Head from "next/head";
import Nav from "./Nav";

export default function Layout({ title, overlay = false, children }: { title: string; overlay?: boolean; children: React.ReactNode }) {
  return (
    <>
      <Head>
        <title>{`${title} · RoadRail`}</title>
      </Head>
      <Nav overlay={overlay} />
      <main className={overlay ? "" : "pt-14"}>{children}</main>
      <footer className="border-t border-line bg-white">
        <div className="mx-auto max-w-[1600px] px-4 sm:px-8 py-10 text-center text-xs text-muted space-y-2">
          <p>RoadRail © 2026 · 고속도로·열차 이동 판단 & 정시성 분석 · 참고 정보이며 교통 안내 서비스가 아닙니다.</p>
          <p>
            데이터: 한국도로공사 고속도로 공공데이터 포털 · 한국철도공사 열차운행정보 · 기상청 단기예보 · 한국환경공단 에어코리아 · 카카오
            · 국토교통부 TAGO · 선로 © OpenStreetMap contributors (ODbL)
            <span className="mx-2">·</span>
            <a className="underline underline-offset-2" href="/docs">API 문서</a>
          </p>
        </div>
      </footer>
    </>
  );
}
