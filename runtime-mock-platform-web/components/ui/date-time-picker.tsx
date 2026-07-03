"use client";

import { useEffect, useMemo, useState } from "react";
import { CalendarClock, X } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

type DateTimeParts = {
  year: string;
  month: string;
  day: string;
  hour: string;
  minute: string;
  second: string;
};

type DateTimePickerProps = {
  id?: string;
  value: string;
  onChange: (value: string) => void;
  required?: boolean;
  disabled?: boolean;
  "aria-label"?: string;
  className?: string;
};

const emptyParts: DateTimeParts = {
  year: "",
  month: "",
  day: "",
  hour: "",
  minute: "",
  second: "",
};

function pad(value: number) {
  return String(value).padStart(2, "0");
}

function parseValue(value: string): DateTimeParts {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(value);
  if (!match) return emptyParts;
  return {
    year: match[1],
    month: match[2],
    day: match[3],
    hour: match[4],
    minute: match[5],
    second: match[6] ?? "00",
  };
}

function daysInMonth(year: string, month: string) {
  const numericYear = Number(year);
  const numericMonth = Number(month);
  if (!numericYear || !numericMonth) return 31;
  return new Date(numericYear, numericMonth, 0).getDate();
}

function complete(parts: DateTimeParts) {
  return Boolean(parts.year && parts.month && parts.day && parts.hour && parts.minute && parts.second);
}

function serialize(parts: DateTimeParts) {
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}:${parts.second}`;
}

function range(length: number, start = 0) {
  return Array.from({ length }, (_, index) => start + index);
}

export function DateTimePicker({
  id,
  value,
  onChange,
  required,
  disabled,
  className,
  "aria-label": ariaLabel,
}: DateTimePickerProps) {
  const [parts, setParts] = useState<DateTimeParts>(() => parseValue(value));
  const now = useMemo(() => new Date(), []);
  const years = useMemo(() => range(3, now.getFullYear()), [now]);
  const maxDay = daysInMonth(parts.year, parts.month);

  useEffect(() => {
    setParts(parseValue(value));
  }, [value]);

  function update(key: keyof DateTimeParts, nextValue: string) {
    const next = { ...parts, [key]: nextValue };
    if ((key === "year" || key === "month") && next.day && Number(next.day) > daysInMonth(next.year, next.month)) {
      next.day = pad(daysInMonth(next.year, next.month));
    }
    setParts(next);
    onChange(complete(next) ? serialize(next) : "");
  }

  function clear() {
    setParts(emptyParts);
    onChange("");
  }

  const segmentClass = "h-10";

  return (
    <div id={id} className={cn("rounded-xl border bg-[var(--surface-elevated)] p-2 shadow-sm", className)} aria-label={ariaLabel}>
      <div className="grid grid-cols-[1fr_0.8fr_0.8fr] gap-2">
        <Select value={parts.year} onValueChange={(selected) => update("year", selected)} disabled={disabled}>
          <SelectTrigger className={segmentClass} aria-label="年">
            <SelectValue placeholder="年" />
          </SelectTrigger>
          <SelectContent>
            {years.map((year) => <SelectItem key={year} value={String(year)}>{year}</SelectItem>)}
          </SelectContent>
        </Select>
        <Select value={parts.month} onValueChange={(selected) => update("month", selected)} disabled={disabled}>
          <SelectTrigger className={segmentClass} aria-label="月">
            <SelectValue placeholder="月" />
          </SelectTrigger>
          <SelectContent>
            {range(12, 1).map((month) => <SelectItem key={month} value={pad(month)}>{pad(month)}</SelectItem>)}
          </SelectContent>
        </Select>
        <Select value={parts.day} onValueChange={(selected) => update("day", selected)} disabled={disabled}>
          <SelectTrigger className={segmentClass} aria-label="日">
            <SelectValue placeholder="日" />
          </SelectTrigger>
          <SelectContent>
            {range(maxDay, 1).map((day) => <SelectItem key={day} value={pad(day)}>{pad(day)}</SelectItem>)}
          </SelectContent>
        </Select>
      </div>
      <div className="mt-2 grid grid-cols-[1fr_1fr_1fr_auto] gap-2">
        <Select value={parts.hour} onValueChange={(selected) => update("hour", selected)} disabled={disabled}>
          <SelectTrigger className={segmentClass} aria-label="时">
            <SelectValue placeholder="时" />
          </SelectTrigger>
          <SelectContent>
            {range(24).map((hour) => <SelectItem key={hour} value={pad(hour)}>{pad(hour)}</SelectItem>)}
          </SelectContent>
        </Select>
        <Select value={parts.minute} onValueChange={(selected) => update("minute", selected)} disabled={disabled}>
          <SelectTrigger className={segmentClass} aria-label="分">
            <SelectValue placeholder="分" />
          </SelectTrigger>
          <SelectContent>
            {range(60).map((minute) => <SelectItem key={minute} value={pad(minute)}>{pad(minute)}</SelectItem>)}
          </SelectContent>
        </Select>
        <Select value={parts.second} onValueChange={(selected) => update("second", selected)} disabled={disabled}>
          <SelectTrigger className={segmentClass} aria-label="秒">
            <SelectValue placeholder="秒" />
          </SelectTrigger>
          <SelectContent>
            {range(60).map((second) => <SelectItem key={second} value={pad(second)}>{pad(second)}</SelectItem>)}
          </SelectContent>
        </Select>
        <Button
          type="button"
          variant="secondary"
          size="icon"
          onClick={clear}
          disabled={disabled || (!value && !complete(parts))}
          aria-label={required ? "清空时间" : "设为长期"}
          title={required ? "清空时间" : "设为长期"}
        >
          {value || complete(parts) ? <X /> : <CalendarClock />}
        </Button>
      </div>
      {!value && !complete(parts) ? (
        <div className="mt-2 rounded-lg bg-[var(--surface-muted)] px-3 py-2 text-xs text-[color:var(--muted-strong)]">长期</div>
      ) : null}
    </div>
  );
}
