import { RuleWorkbench } from "@/components/editor/rule-workbench";

export default async function RuleVersionPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <RuleWorkbench ruleId={id} />;
}
