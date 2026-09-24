import Link from "next/link";
import Layout from "@/components/Layout";

export default function NotFound() {
  return (
    <Layout title="페이지 없음">
      <div className="flex min-h-[60vh] flex-col items-center justify-center text-center">
        <h1 className="text-[40px] font-medium">페이지를 찾을 수 없습니다</h1>
        <Link href="/" className="btn-primary mt-8">판단 화면으로</Link>
      </div>
    </Layout>
  );
}
