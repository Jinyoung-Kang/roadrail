import { useRouter } from "next/router";
import { useEffect } from "react";
import Layout from "@/components/Layout";
import { Loading } from "@/components/ui";
import { useCorridors } from "@/lib/useCorridors";

/** 예전 주소 /rail/{길} → 그 길의 역 쌍으로 /rail?dep=…&arr=… */
export default function RailCorridorRedirect() {
  const router = useRouter();
  const corridors = useCorridors();
  useEffect(() => {
    if (!router.isReady || !corridors.data) return;
    const c = corridors.data.find((x) => x.id === router.query.corridor);
    const r = c?.rail[(router.query.dir as string) === "UP" ? "UP" : "DN"];
    router.replace(r ? `/rail?dep=${r.dep.code}&arr=${r.arr.code}` : "/rail");
  }, [router, corridors.data]);
  return <Layout title="철도 분석"><Loading /></Layout>;
}
