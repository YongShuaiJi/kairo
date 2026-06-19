"use client";

import * as React from "react";
import { Minus, Plus } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

type NumberInputProps = Omit<React.ComponentProps<typeof Input>, "type" | "value" | "onChange"> & {
  value: string;
  onValueChange: (value: string) => void;
  min?: number;
  max?: number;
  step?: number;
};

export function NumberInput({
  value,
  onValueChange,
  min,
  max,
  step = 1,
  className,
  disabled,
  ...props
}: NumberInputProps) {
  const parsed = Number(value);
  const normalize = (next: number) => {
    const bounded = Math.min(max ?? Number.POSITIVE_INFINITY, Math.max(min ?? Number.NEGATIVE_INFINITY, next));
    onValueChange(String(bounded));
  };

  return (
    <div className={cn("flex h-10 overflow-hidden rounded-lg border border-slate-200 bg-white shadow-sm focus-within:border-indigo-400 focus-within:ring-4 focus-within:ring-indigo-100", className)}>
      <Button
        type="button"
        variant="ghost"
        size="icon"
        className="h-full w-9 shrink-0 rounded-none border-r border-slate-100 text-slate-400 hover:bg-slate-50 hover:text-indigo-600"
        disabled={disabled || (min !== undefined && parsed <= min)}
        onClick={() => normalize((Number.isFinite(parsed) ? parsed : 0) - step)}
        aria-label="减少"
      >
        <Minus />
      </Button>
      <Input
        {...props}
        disabled={disabled}
        value={value}
        inputMode="numeric"
        pattern="-?[0-9]*"
        onChange={(event) => {
          const next = event.target.value;
          if (next === "" || next === "-" || /^-?\d+$/.test(next)) onValueChange(next);
        }}
        className="h-full min-w-0 flex-1 rounded-none border-0 px-2 text-center shadow-none focus:ring-0"
      />
      <Button
        type="button"
        variant="ghost"
        size="icon"
        className="h-full w-9 shrink-0 rounded-none border-l border-slate-100 text-slate-400 hover:bg-slate-50 hover:text-indigo-600"
        disabled={disabled || (max !== undefined && parsed >= max)}
        onClick={() => normalize((Number.isFinite(parsed) ? parsed : 0) + step)}
        aria-label="增加"
      >
        <Plus />
      </Button>
    </div>
  );
}
