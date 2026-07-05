import { cn } from "@/lib/utils";

export function KairoIcon({ className }: { className?: string }) {
  return (
    <div
      aria-hidden="true"
      className={cn(
        "relative grid place-items-center overflow-hidden rounded-xl bg-indigo-600 text-white shadow-lg shadow-indigo-950/30",
        className,
      )}
    >
      <div className="absolute inset-0 bg-[radial-gradient(circle_at_28%_22%,rgba(255,255,255,0.38),transparent_32%),linear-gradient(135deg,#4f46e5,#0ea5e9)]" />
      <svg viewBox="0 0 32 32" className="relative size-[70%]" fill="none" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round">
        <path d="M4 17h5l3-10 6 18 3-8h7" strokeWidth="2.7" />
        <path d="M20 7l6 6m0-6l-6 6" strokeWidth="2.2" />
      </svg>
    </div>
  );
}
