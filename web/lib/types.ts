// Spring Boot api 응답 타입 (api/src/main/java/com/roadrail/web/dto 와 1:1)
export type Dir = "DN" | "UP";

export interface Point { code: string; name: string; lat: number | null; lon: number | null }
export interface Corridor {
  id: string; name: string; originCity: string; destCity: string;
  road: Record<Dir, { segments: number; distanceKm: number; units: Point[] }>;
  rail: Record<Dir, { dep: Point; arr: Point }>;
  env: { role: string; name: string; lat: number; lon: number; nx: number; ny: number; sido: string }[];
}

export interface NextTrain {
  trnNo: string; planDepAt: string; planArrAt: string; planDep: string; planArr: string; planRideMin: number;
  avgArrDelayMin30d: number | null; onTimeRate30d: number | null; samples: number; delayEstimated: boolean;
}
export interface Decision {
  rule: string; verdict: "CAR" | "TRAIN" | "SIMILAR" | "UNKNOWN"; summary: string;
  carTotalMin: number | null; trainTotalMin: number | null; diffMin: number | null; reasons: string[]; warnings: string[];
}
export interface EnvPoint { name: string; pop: number | null; pty: string | null; tmp: number | null; sky: string | null;
  pm25: number | null; pm25Grade: number | null; khaiGrade: number | null }
export interface Incident { sentAt: string; typeCode: string; typeName: string; routeName: string; direction: string;
  process: string; content: string; corridorIds: string[] }
export interface NowCard {
  corridorId: string; corridorName: string; direction: Dir; asOf: string; departAt: string; accessMin: number;
  carAccessMin: number; status: "OK" | "STALE_DATA";
  road: {
    travelSec: number | null; baselineP50Sec: number | null; vsBaselinePct: number | null; slotTs: string | null;
    quality: string | null; coverage: number | null; targetAt: string; leadMin: number; predictedSec: number | null;
    model: string | null; forecast: { model: string; travelSec: number | null }[];
    kakao: { durationSec: number; distanceM: number; departAt: string } | null; from: string; to: string; distanceKm: number;
  };
  rail: { depStation: string; arrStation: string; referenceDate: string | null; basis: string; nextTrains: NextTrain[] };
  env: Record<"origin" | "dest", EnvPoint>;
  incidents: Incident[];
  decision: Decision;
  freshness: Record<string, string>;
  caveat: string;
  cache: string;
}

export interface SeriesPoint { t: string; travelSec: number; baselineP50Sec: number | null; quality: string; coverage: number }
export interface Series {
  corridorId: string; direction: Dir; agg: string; from: string; to: string; points: SeriesPoint[];
  rain: { t: string; pop: number | null; pty: string | null }[]; kakaoEta: { departAt: string; durationSec: number }[];
  stats: { points: number; expectedPoints: number; filledShare: number | null }; note: string;
}
export interface Baseline { corridorId: string; direction: Dir; windowFrom: string | null; windowTo: string | null;
  computedAt: string | null; cells: { dow: number; slotIdx: number; p50Sec: number; p90Sec: number; n: number }[]; note: string }
export interface BacktestCell { horizonMin: number; model: string; maeSec: number | null; mape: number | null; n: number }
export interface Forecast {
  corridorId: string; direction: Dir; issuedAt: string; lastObservedSlot: string | null; lastObservedSec: number | null;
  items: { horizonMin: number; leadMin: number; targetAt: string; M0: number | null; M1: number | null; persistence: number; baselineN: number }[];
  backtest: { period: string; evalDate: string | null; modelVersion: string | null; maeSec: Record<string, number | null>; cells: BacktestCell[] };
  note: string;
}
export interface TrainStats { samples: number; verified: number; onTimeRate: number | null; avgArrDelayMin: number | null;
  p90ArrDelayMin: number | null; avgRideMin: number | null; delayEstimated: boolean }
export interface TrainRun { trnNo: string; actDepAt: string; actArrAt: string; planDepAt: string | null; planArrAt: string | null;
  depDelayMin: number | null; arrDelayMin: number | null; depBasis: string; arrBasis: string; rideMin: number;
  onTime: boolean | null; stats30d: TrainStats | null }
export interface Trains { depCode: string; arrCode: string; date: string | null; depStation: string; arrStation: string;
  trains: TrainRun[]; availableDates: string[]; note: string }
export interface PunctualitySummary { samples: number; verified: number; unverified: number; onTimeRate: number | null;
  avgArrDelayMin: number | null; p90ArrDelayMin: number | null }
export interface Punctuality {
  depCode: string; arrCode: string; depStation: string; arrStation: string; from: string; to: string; groupBy: string; onTimeThresholdMin: number;
  summary: PunctualitySummary; nationwideExact: PunctualitySummary;
  items: { key: string; samples: number; verified: number; onTimeRate: number | null; avgArrDelayMin: number | null;
    p90ArrDelayMin: number | null; avgRideMin: number | null; estimatedShare: number | null }[];
  histogram: { label: string; count: number }[]; rules: Record<string, string>; note: string;
}
export interface EnvResp { corridorId: string; note: string; points: { role: string; name: string; sido: string; baseAt: string | null;
  hourly: { at: string; tmp: number | null; pop: number | null; pty: string | null; sky: string | null }[];
  air: { dataTime: string; pm10: number | null; pm25: number | null; pm25Grade: number | null; khaiGrade: number | null; stations: number } | null }[] }
export interface OpsStatus {
  asOf: string; collectorAlive: boolean; collectorHeartbeat: string | null;
  jobs: { job: string; provider: string; cron: string; description: string; enabled: boolean; lastStatus: string | null;
    lastRunAt: string | null; lastDurationMs: number | null; lastCalls: number | null; lastRows: number | null;
    lastMessage: string | null; completeness24h: number | null; gaps24h: number | null; running: boolean; warn: boolean }[];
  quota: { provider: string; day: string; limit: number; used: number; reserved: number; remaining: number }[];
  recentRuns: { runId: number; job: string; trigger: string; startedAt: string; finishedAt: string | null; status: string;
    calls: number; rows: number; message: string | null }[];
  recentErrors: { calledAt: string; provider: string; endpoint: string; httpStatus: number | null; error: string | null }[];
  backfills: { backfillId: string; provider: string; job: string; from: string; to: string; plannedCalls: number; doneDays: number;
    status: string; requestedAt: string; finishedAt: string | null }[];
  publicationLag: { series: string; medianMin: number | null; p90Min: number | null; n: number }[];
  volumes: Record<string, number | string | null>;
}
export interface ApiError { code: string; message: string; traceId: string }

// ---- 어디서 → 어디로 (자유 선택)
export type PlaceKind = "REGION" | "STATION" | "PLACE" | "ADDRESS" | "CORRIDOR";
export interface Place { name: string; address: string | null; lat: number; lon: number; kind: PlaceKind; stationCode: string | null }
export interface Station { code: string; name: string; lat: number | null; lon: number | null; trains7d: number }
export interface StationEnd { code: string; name: string; lat: number; lon: number; distanceKm: number; minutes: number; estimated: boolean }
export interface Trip {
  from: Place; to: Place; distanceKm: number; asOf: string; departAt: string; accessMin: number | null;
  car: { durationSec: number | null; distanceM: number | null; departAt: string | null; path: [number, number][]; pending: boolean; source: string };
  observed: { corridorId: string; corridorName: string; direction: Dir; travelSec: number; baselineP50Sec: number | null;
    vsBaselinePct: number | null; slotTs: string; predictedSec: number | null; model: string; leadMin: number } | null;
  rail: { dep: StationEnd | null; arr: StationEnd | null; referenceDate: string | null; basis: string | null;
    nextTrains: NextTrain[]; pairsTried: number; note: string | null } | null;
  env: Record<"origin" | "dest", EnvPoint>;
  incidents: Incident[];
  decision: Decision;
  freshness: Record<string, string>;
  caveat: string;
  pending: boolean;
  cache: string;
}
