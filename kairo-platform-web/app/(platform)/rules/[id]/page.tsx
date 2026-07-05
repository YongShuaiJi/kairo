import { RuleLedgerPage } from "@/components/rules/rule-ledger-page";

export default async function RuleVersionPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <RuleLedgerPage ruleId={id} />;
}
