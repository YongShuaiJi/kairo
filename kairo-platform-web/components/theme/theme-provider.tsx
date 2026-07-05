"use client";

import { createContext, useContext, useEffect, useMemo, useState } from "react";

export type ThemeMode = "light" | "dark" | "system";
type EffectiveTheme = "light" | "dark";

type ThemeContextValue = {
  mode: ThemeMode;
  effectiveTheme: EffectiveTheme;
  setMode: (mode: ThemeMode) => void;
};

const ThemeContext = createContext<ThemeContextValue | null>(null);
const storageKey = "kairo-theme";

function systemTheme(): EffectiveTheme {
  if (typeof window === "undefined") return "light";
  return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

function applyTheme(mode: ThemeMode, effectiveTheme: EffectiveTheme) {
  const root = document.documentElement;
  root.dataset.themeMode = mode;
  root.dataset.theme = effectiveTheme;
  root.classList.toggle("theme-night", effectiveTheme === "dark");
  root.classList.toggle("theme-day", effectiveTheme === "light");
  root.style.colorScheme = effectiveTheme === "dark" ? "dark" : "light";
}

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const [mode, setModeState] = useState<ThemeMode>("system");
  const [effectiveTheme, setEffectiveTheme] = useState<EffectiveTheme>("light");

  useEffect(() => {
    const stored = window.localStorage.getItem(storageKey);
    const initialMode: ThemeMode = stored === "light" || stored === "dark" || stored === "system" ? stored : "system";
    const initialEffective = initialMode === "system" ? systemTheme() : initialMode;
    setModeState(initialMode);
    setEffectiveTheme(initialEffective);
    applyTheme(initialMode, initialEffective);
  }, []);

  useEffect(() => {
    const media = window.matchMedia("(prefers-color-scheme: dark)");
    const sync = () => {
      if (mode !== "system") return;
      const next = media.matches ? "dark" : "light";
      setEffectiveTheme(next);
      applyTheme(mode, next);
    };
    media.addEventListener("change", sync);
    return () => media.removeEventListener("change", sync);
  }, [mode]);

  const value = useMemo<ThemeContextValue>(() => ({
    mode,
    effectiveTheme,
    setMode: (nextMode) => {
      const nextEffective = nextMode === "system" ? systemTheme() : nextMode;
      window.localStorage.setItem(storageKey, nextMode);
      setModeState(nextMode);
      setEffectiveTheme(nextEffective);
      applyTheme(nextMode, nextEffective);
    },
  }), [effectiveTheme, mode]);

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme() {
  const value = useContext(ThemeContext);
  if (!value) throw new Error("useTheme must be used inside ThemeProvider");
  return value;
}

