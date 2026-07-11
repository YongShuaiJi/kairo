import { Suspense } from "react";
import { BytecodeInspector } from "@/components/bytecode/bytecode-inspector";
import { PageHeader } from "@/components/layout/page-header";
import { Skeleton } from "@/components/ui/skeleton";

/**
 * V1.1 bytecode enhancement comparison page. Directly accessible at
 * {@code /agents/{agentId}/bytecode?classId=...}; the {@code classId} is read from the
 * query string and is editable in-place. All five platform-proxied diagnostic APIs are
 * reached through the same-origin BFF - the agent URL and {@code X-Agent-Token} are
 * never exposed to the browser.
 */
export default async function BytecodePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return (
    <>
      <PageHeader
        eyebrow="Runtime / Agent 诊断"
        title="字节码增强对比"
        description="按 classId 查看 INPUT / PLANNED / APPLIED 三态字节码、转换历史与结构化 Diff。只读预览不会修改 JVM。"
      />
      <Suspense fallback={<Skeleton className="h-96 w-full" />}>
        <BytecodeInspector agentId={id} />
      </Suspense>
    </>
  );
}
