"use client";

import { useMemo, useState } from "react";
import { CalendarClock, X } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Calendar } from "@/components/ui/calendar";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";

type DateTimePickerProps = {
  id?: string;
  value: string;
  onChange: (value: string) => void;
  required?: boolean;
  disabled?: boolean;
  inline?: boolean;
  "aria-label"?: string;
  className?: string;
};

function pad(value: number) {
  return String(value).padStart(2, "0");
}

function toDateOnly(date: Date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function parseDateOnly(value: string) {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(value);
  if (!match) return undefined;
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
  return Number.isNaN(date.getTime()) ? undefined : date;
}

function displayDate(value: string) {
  const date = parseDateOnly(value);
  if (!date) return "";
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(date);
}

export function DateTimePicker({
  id,
  value,
  onChange,
  required,
  disabled,
  inline,
  className,
  "aria-label": ariaLabel,
}: DateTimePickerProps) {
  const [open, setOpen] = useState(false);
  const now = useMemo(() => new Date(), []);
  const selected = parseDateOnly(value);
  const startMonth = useMemo(() => new Date(now.getFullYear(), now.getMonth(), 1), [now]);
  const endMonth = useMemo(() => new Date(now.getFullYear() + 2, 11, 31), [now]);

  function clear() {
    onChange("");
    setOpen(false);
  }

  function selectDate(date: Date) {
    if (disabled) return;
    onChange(toDateOnly(date));
    if (!inline) setOpen(false);
  }

  const label = value ? displayDate(value) : required ? "选择日期" : "长期";

  if (inline) {
    return (
      <div id={id} className={cn("space-y-3", className)} aria-label={ariaLabel} data-kairo-date-picker>
        <div className="flex gap-2">
          <div
            className={cn(
              "theme-field flex h-10 flex-1 items-center gap-2 rounded-lg border px-3 text-sm shadow-sm",
              !value && "text-[color:var(--muted)]",
              disabled && "opacity-60",
            )}
          >
            <CalendarClock className="size-4 text-[color:var(--muted)]" />
            <span>{label}</span>
          </div>
          {!required ? (
            <Button
              type="button"
              variant="secondary"
              size="icon"
              onClick={clear}
              disabled={disabled || !value}
              aria-label="设为长期"
              title="设为长期"
            >
              <X />
            </Button>
          ) : null}
        </div>
        <Calendar
          mode="single"
          selected={selected}
          onDayClick={selectDate}
          startMonth={startMonth}
          endMonth={endMonth}
          defaultMonth={selected ?? now}
          className={cn("mx-auto rounded-xl border border-[color:var(--border)] bg-[var(--surface-elevated)]", disabled && "pointer-events-none opacity-60")}
        />
      </div>
    );
  }

  return (
    <div id={id} className={cn("flex gap-2", className)} aria-label={ariaLabel}>
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <Button
            type="button"
            variant="secondary"
            className={cn("h-10 flex-1 justify-start px-3 text-left font-normal", !value && "text-[color:var(--muted)]")}
            disabled={disabled}
            aria-required={required}
          >
            <CalendarClock className="text-[color:var(--muted)]" />
            <span>{label}</span>
          </Button>
        </PopoverTrigger>
        <PopoverContent className="w-auto p-0" align="start" data-kairo-date-picker>
          <Calendar
            mode="single"
            selected={selected}
            onDayClick={selectDate}
            startMonth={startMonth}
            endMonth={endMonth}
            defaultMonth={selected ?? now}
          />
        </PopoverContent>
      </Popover>
      {!required ? (
        <Button
          type="button"
          variant="secondary"
          size="icon"
          onClick={clear}
          disabled={disabled || !value}
          aria-label="设为长期"
          title="设为长期"
        >
          <X />
        </Button>
      ) : null}
    </div>
  );
}
