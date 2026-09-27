import dynamic from "next/dynamic";
import { Loading } from "@/components/ui";

/**
 * 차트(recharts)는 화면 아래쪽 · 데이터를 받은 뒤에 그려지므로 첫 로드 번들에서 빼고 필요할 때 받는다 (PERF-05).
 * 브라우저에서만 그린다(ssr: false) — 정적 페이지 HTML 에는 원래 차트가 없다(데이터가 클라이언트에서 옴).
 */
export const SimpleBars = dynamic(() => import("./Charts").then((m) => m.SimpleBars),
  { ssr: false, loading: () => <Loading label="차트 불러오는 중" /> });
export const MaeChart = dynamic(() => import("./Charts").then((m) => m.MaeChart),
  { ssr: false, loading: () => <Loading label="차트 불러오는 중" /> });
export const TravelChart = dynamic(() => import("./Charts").then((m) => m.TravelChart),
  { ssr: false, loading: () => <Loading label="차트 불러오는 중" /> });
export const Heatmap = dynamic(() => import("./Charts").then((m) => m.Heatmap),
  { ssr: false, loading: () => <Loading label="차트 불러오는 중" /> });
