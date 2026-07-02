import { RuleWorkbench } from "@/components/editor/rule-workbench";

export default async function RuleVersionPage({ params }: { params: Promise<{ id: string; version: string }> }) {
  const { id, version } = await params;
  return <RuleWorkbench ruleId={id} version={version} />;
}
