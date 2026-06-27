"use client";

import dynamic from "next/dynamic";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { loader } from "@monaco-editor/react";
import type { editor, IDisposable, languages } from "monaco-editor";
import {
  AlertTriangle,
  ArrowLeft,
  Beaker,
  BookOpen,
  Braces,
  CheckCircle2,
  ChevronDown,
  Clock3,
  Code2,
  Copy,
  Download,
  Expand,
  FileDiff,
  Focus,
  Info,
  Loader2,
  PanelBottomOpen,
  PanelRightOpen,
  Play,
  Search,
  Save,
  ShieldAlert,
  Shrink,
  Sparkles,
  Target,
  TerminalSquare,
  XCircle,
} from "lucide-react";
import { toast } from "sonner";
import { platformFetch } from "@/lib/api/client";
import type { PlatformRecord, ScriptDiagnostic, ScriptTestResult, ScriptValidationResult } from "@/lib/api/types";
import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Slider } from "@/components/ui/slider";
import { Textarea } from "@/components/ui/textarea";

const MonacoEditor = dynamic(() => import("@monaco-editor/react").then((module) => module.Editor), {
  ssr: false,
  loading: () => <div className="flex h-full items-center justify-center bg-slate-50 text-sm text-slate-400"><Loader2 className="mr-2 size-4 animate-spin" />正在加载代码编辑器…</div>,
});
const MonacoDiffEditor = dynamic(() => import("@monaco-editor/react").then((module) => module.DiffEditor), {
  ssr: false,
  loading: () => <div className="flex h-full items-center justify-center bg-slate-50 text-sm text-slate-400"><Loader2 className="mr-2 size-4 animate-spin" />正在加载 Diff…</div>,
});

const initialScript = `// 安全默认行为：继续执行原方法
return mock.proceed()`;

const emptyPreviousScript = "// 当前规则还没有更早版本";
const initialTestInput = `{
  "args": []
}`;

type InvokePhase = "BEFORE" | "RETURN" | "THROWS";
type PagedResult = { items: PlatformRecord[]; page: number; size: number; total: number };
const VISIBLE_ENVIRONMENTS = new Set(["DEV", "SIT", "UAT"]);
type TargetOption = {
  classId: string;
  className: string;
  classLoaderId: string;
  classLoaderClassName: string;
  methodName: string;
  descriptor: string;
  returnType: string;
  parameterTypes: string[];
  modifiable: boolean;
  instanceCount: number;
  protocol: string;
};

function stringValue(record: PlatformRecord, ...keys: string[]) {
  for (const key of keys) {
    const value = record[key];
    if (value !== null && value !== undefined && String(value).trim()) return String(value);
  }
  return "";
}

function stringArrayValue(record: PlatformRecord, ...keys: string[]) {
  for (const key of keys) {
    const value = record[key];
    if (Array.isArray(value)) return value.map(String);
  }
  return [] as string[];
}

function applicationLabel(record: PlatformRecord) {
  return stringValue(record, "applicationName", "application_name", "application", "applicationId", "application_id");
}

function environmentLabel(record: PlatformRecord) {
  return stringValue(record, "environmentName", "environment_name", "environment", "type", "name", "environmentId", "environment_id").toUpperCase();
}

function phaseLabel(phase: InvokePhase) {
  return phase === "BEFORE" ? "调用前" : phase === "RETURN" ? "正常返回后" : "抛出异常时";
}

type Monaco = typeof import("monaco-editor");

function defineEditorThemes(monaco: Monaco) {
  monaco.editor.defineTheme("runtime-mock-light", {
    base: "vs",
    inherit: true,
    rules: [
      { token: "comment", foreground: "8A96AA", fontStyle: "italic" },
      { token: "keyword", foreground: "5B4FF0", fontStyle: "bold" },
      { token: "string", foreground: "A84D16" },
      { token: "number", foreground: "087C64" },
      { token: "identifier", foreground: "26334D" },
      { token: "delimiter", foreground: "66758E" },
      { token: "operator", foreground: "7C3AED" },
    ],
    colors: {
      "editor.background": "#F8FAFD",
      "editor.foreground": "#26334D",
      "editorLineNumber.foreground": "#AAB4C5",
      "editorLineNumber.activeForeground": "#5B4FF0",
      "editorGutter.background": "#F4F7FB",
      "editor.lineHighlightBackground": "#EEF2FF",
      "editor.lineHighlightBorder": "#00000000",
      "editor.selectionBackground": "#DDE3FF",
      "editor.inactiveSelectionBackground": "#E9EDFA",
      "editorCursor.foreground": "#5B4FF0",
      "editorIndentGuide.background1": "#E4E9F2",
      "editorIndentGuide.activeBackground1": "#C5CCDB",
      "editorWhitespace.foreground": "#D7DEE9",
      "editorBracketMatch.background": "#EEF0FF",
      "editorBracketMatch.border": "#7C6FF6",
      "editorOverviewRuler.border": "#00000000",
      "editor.foldBackground": "#E9EDFF80",
      "editorWidget.background": "#FFFFFF",
      "editorWidget.border": "#E2E8F0",
      "editorWidget.foreground": "#26334D",
      "editorSuggestWidget.background": "#FFFFFF",
      "editorSuggestWidget.border": "#E2E8F0",
      "editorSuggestWidget.selectedBackground": "#EEF0FF",
      "editorSuggestWidget.highlightForeground": "#5B4FF0",
      "editorHoverWidget.background": "#FFFFFF",
      "editorHoverWidget.border": "#E2E8F0",
      "scrollbarSlider.background": "#94A3B833",
      "scrollbarSlider.hoverBackground": "#94A3B855",
      "scrollbarSlider.activeBackground": "#64748B66",
    },
  });
  monaco.editor.defineTheme("runtime-mock-focus", {
    base: "vs-dark",
    inherit: true,
    rules: [
      { token: "comment", foreground: "73809A", fontStyle: "italic" },
      { token: "keyword", foreground: "A99CFF", fontStyle: "bold" },
      { token: "string", foreground: "F3B36A" },
      { token: "number", foreground: "6ED6B2" },
      { token: "identifier", foreground: "D9E0EC" },
      { token: "operator", foreground: "C4B9FF" },
    ],
    colors: {
      "editor.background": "#0F172A",
      "editor.foreground": "#D9E0EC",
      "editorLineNumber.foreground": "#4B5870",
      "editorLineNumber.activeForeground": "#A99CFF",
      "editorGutter.background": "#0F172A",
      "editor.lineHighlightBackground": "#17213A",
      "editor.selectionBackground": "#353264",
      "editorCursor.foreground": "#A99CFF",
      "editorIndentGuide.background1": "#263149",
      "editorIndentGuide.activeBackground1": "#4B5870",
    },
  });
}

function registerGroovy(monaco: Monaco) {
  if (monaco.languages.getLanguages().some((language) => language.id === "groovy-runtime-mock")) return [] as IDisposable[];
  monaco.languages.register({ id: "groovy-runtime-mock", extensions: [".groovy"], aliases: ["Groovy"] });
  monaco.languages.setMonarchTokensProvider("groovy-runtime-mock", {
    keywords: ["as", "assert", "break", "case", "catch", "class", "continue", "def", "default", "do", "else", "enum", "extends", "false", "finally", "for", "if", "implements", "import", "in", "instanceof", "interface", "new", "null", "package", "return", "super", "switch", "this", "throw", "throws", "trait", "true", "try", "while"],
    operators: ["=", ">", "<", "!", "~", "?", ":", "==", "<=", ">=", "!=", "&&", "||", "++", "--", "+", "-", "*", "/", "&", "|", "^", "%", "+=", "-=", "*=", "/="],
    symbols: /[=><!~?:&|+\-*/^%]+/,
    tokenizer: {
      root: [
        [/[a-zA-Z_$][\w$]*/, { cases: { "@keywords": "keyword", "@default": "identifier" } }],
        [/[{}()[\]]/, "@brackets"],
        [/[<>](?!@symbols)/, "@brackets"],
        [/@symbols/, { cases: { "@operators": "operator", "@default": "" } }],
        [/\d*\.\d+([eE][-+]?\d+)?/, "number.float"],
        [/0[xX][0-9a-fA-F]+/, "number.hex"],
        [/\d+/, "number"],
        [/[;,.]/, "delimiter"],
        [/"/, { token: "string.quote", bracket: "@open", next: "@string" }],
        [/'[^']*'/, "string"],
        [/\/\/.*$/, "comment"],
        [/\/\*/, "comment", "@comment"],
      ],
      comment: [[/[^\/*]+/, "comment"], [/\*\//, "comment", "@pop"], [/[\/*]/, "comment"]],
      string: [[/[^\\"]+/, "string"], [/\\./, "string.escape.invalid"], [/"/, { token: "string.quote", bracket: "@close", next: "@pop" }]],
    },
  } as languages.IMonarchLanguage);
  const completion = monaco.languages.registerCompletionItemProvider("groovy-runtime-mock", {
    provideCompletionItems(model, position) {
      const range = {
        startLineNumber: position.lineNumber,
        endLineNumber: position.lineNumber,
        startColumn: model.getWordUntilPosition(position).startColumn,
        endColumn: model.getWordUntilPosition(position).endColumn,
      };
      const suggestions: languages.CompletionItem[] = [
        { label: "args", kind: monaco.languages.CompletionItemKind.Variable, insertText: "args", detail: "List<Object> · 当前方法参数", range },
        { label: "result", kind: monaco.languages.CompletionItemKind.Variable, insertText: "result", detail: "Object · 原始方法返回值", range },
        { label: "throwable", kind: monaco.languages.CompletionItemKind.Variable, insertText: "throwable", detail: "Throwable · 原始异常", range },
        { label: "ctx", kind: monaco.languages.CompletionItemKind.Variable, insertText: "ctx", detail: "InvocationContext · 调用阶段与方法元数据", range },
        { label: "mock", kind: monaco.languages.CompletionItemKind.Variable, insertText: "mock", detail: "MockApi · 创建 proceed/return/throw 决策", range },
        { label: "返回对象模板", kind: monaco.languages.CompletionItemKind.Snippet, insertText: "return mock.returnValue([\n    code: ${1:200},\n    message: \"${2:mocked}\",\n    data: ${3:result}\n])", insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet, detail: "Runtime Mock 返回值模板", range },
        { label: "条件故障模板", kind: monaco.languages.CompletionItemKind.Snippet, insertText: "if (${1:args[0]} == ${2:null}) {\n    return mock.throwException(\"java.lang.IllegalStateException\", \"${3:runtime mock fault}\")\n}\n\nreturn mock.proceed()", insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet, detail: "条件触发异常模板", range },
      ];
      return { suggestions };
    },
  });
  const hover = monaco.languages.registerHoverProvider("groovy-runtime-mock", {
    provideHover(model, position) {
      const word = model.getWordAtPosition(position)?.word;
      const docs: Record<string, string> = {
        args: "**args** · 只读方法参数列表。修改嵌套对象前请先复制。",
        result: "**result** · 原始方法返回值。返回它可以保持原始行为。",
        throwable: "**throwable** · 原始调用抛出的异常，没有异常时为 `null`。",
        ctx: "**ctx** · 当前调用阶段、方法签名、参数和原始结果。",
        mock: "**mock** · 返回 `MockDecision` 的安全 API。",
      };
      return word && docs[word] ? { contents: [{ value: docs[word] }] } : null;
    },
  });
  return [completion, hover];
}

export function RuleWorkbench({ ruleId }: { ruleId?: string }) {
  const router = useRouter();
  const [script, setScript] = useState(initialScript);
  const [previousScript, setPreviousScript] = useState(emptyPreviousScript);
  const [name, setName] = useState(ruleId ? `规则 ${ruleId}` : "");
  const [applicationId, setApplicationId] = useState("");
  const [environmentId, setEnvironmentId] = useState("");
  const [classId, setClassId] = useState("");
  const [className, setClassName] = useState("");
  const [classLoaderId, setClassLoaderId] = useState("");
  const [methodName, setMethodName] = useState("");
  const [methodDescriptor, setMethodDescriptor] = useState("");
  const [executionPhase, setExecutionPhase] = useState<InvokePhase | "">("");
  const [testInput, setTestInput] = useState(initialTestInput);
  const [diagnostics, setDiagnostics] = useState<ScriptDiagnostic[]>([]);
  const [validationStatus, setValidationStatus] = useState<"idle" | "valid" | "invalid">("idle");
  const [testResult, setTestResult] = useState<ScriptTestResult | null>(null);
  const [bottomTab, setBottomTab] = useState<"test" | "diff">("test");
  const [sideTab, setSideTab] = useState<"diagnostics" | "context">("diagnostics");
  const [bottomOpen, setBottomOpen] = useState(Boolean(ruleId));
  const [sideOpen, setSideOpen] = useState(Boolean(ruleId));
  const [busy, setBusy] = useState<"validate" | "test" | "save" | null>(null);
  const [dirty, setDirty] = useState(false);
  const [focusMode, setFocusMode] = useState(false);
  const [leftWidth, setLeftWidth] = useState(300);
  const [monacoReady, setMonacoReady] = useState(false);
  const [instances, setInstances] = useState<PlatformRecord[]>([]);
  const [metadataLoading, setMetadataLoading] = useState(true);
  const [metadataError, setMetadataError] = useState("");
  const [targetQuery, setTargetQuery] = useState("");
  const [targetOptions, setTargetOptions] = useState<TargetOption[]>([]);
  const [targetsLoading, setTargetsLoading] = useState(false);
  const [manualTarget, setManualTarget] = useState(false);
  const [pendingNavigation, setPendingNavigation] = useState<string | null>(null);
  const editorRef = useRef<editor.IStandaloneCodeEditor | null>(null);
  const monacoRef = useRef<Monaco | null>(null);
  const disposables = useRef<IDisposable[]>([]);

  const markDirty = useCallback((value: string | undefined) => {
    setScript(value ?? "");
    setDirty(true);
    setDiagnostics([]);
    setValidationStatus("idle");
    setTestResult(null);
  }, []);

  const applications = Array.from(new Map(instances
    .map((item) => {
      const id = stringValue(item, "applicationId", "application_id", "application");
      return id ? [id, applicationLabel(item)] as const : null;
    })
    .filter((item): item is readonly [string, string] => Boolean(item))).entries())
    .map(([id, label]) => ({ id, label }))
    .sort((left, right) => left.label.localeCompare(right.label));
  const applicationIds = applications.map((item) => item.id);
  const environments = Array.from(new Map(instances
    .filter((item) => stringValue(item, "applicationId", "application_id", "application") === applicationId)
    .map((item) => {
      const id = stringValue(item, "environmentId", "environment_id", "environment");
      const label = environmentLabel(item);
      return id && VISIBLE_ENVIRONMENTS.has(label) ? [id, label] as const : null;
    })
    .filter((item): item is readonly [string, string] => Boolean(item))).entries())
    .map(([id, label]) => ({ id, label }))
    .sort((left, right) => ["DEV", "SIT", "UAT"].indexOf(left.label) - ["DEV", "SIT", "UAT"].indexOf(right.label));
  const environmentIds = environments.map((item) => item.id);
  const targetSelected = Boolean(
    classId.trim()
    && className.trim()
    && classLoaderId.trim()
    && methodName.trim()
    && methodDescriptor.trim(),
  );
  const editorEnabled = Boolean(targetSelected && executionPhase);
  const formComplete = Boolean(
    name.trim()
    && applicationId
    && environmentId
    && targetSelected
    && executionPhase
    && script.trim(),
  );

  useEffect(() => {
    const beforeUnload = (event: BeforeUnloadEvent) => {
      if (!dirty) return;
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", beforeUnload);
    return () => window.removeEventListener("beforeunload", beforeUnload);
  }, [dirty]);

  useEffect(() => {
    if (!dirty) return;
    const interceptNavigation = (event: MouseEvent) => {
      if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
      const anchor = (event.target as HTMLElement | null)?.closest("a[href]");
      if (!(anchor instanceof HTMLAnchorElement) || anchor.target || anchor.hasAttribute("download")) return;
      const nextUrl = new URL(anchor.href, window.location.href);
      if (nextUrl.origin !== window.location.origin) return;
      event.preventDefault();
      setPendingNavigation(`${nextUrl.pathname}${nextUrl.search}${nextUrl.hash}`);
    };
    document.addEventListener("click", interceptNavigation, true);
    return () => document.removeEventListener("click", interceptNavigation, true);
  }, [dirty]);

  useEffect(() => () => disposables.current.forEach((item) => item.dispose()), []);

  useEffect(() => {
    let active = true;
    setMetadataLoading(true);
    void platformFetch<PagedResult>("query/instances?page=0&size=200&q=")
      .then((result) => {
        if (!active) return;
        setInstances(result.items);
        setMetadataError("");
      })
      .catch((error) => {
        if (!active) return;
        setMetadataError(error instanceof Error ? error.message : "应用与环境加载失败");
      })
      .finally(() => {
        if (active) setMetadataLoading(false);
      });
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (!applicationId || !environmentId || manualTarget) {
      setTargetOptions([]);
      setTargetsLoading(false);
      return;
    }
    let active = true;
    const timer = window.setTimeout(() => {
      setTargetsLoading(true);
      const search = new URLSearchParams({
        q: targetQuery.trim(),
        applicationId,
        environmentId,
      });
      void platformFetch<PlatformRecord[]>(`targets/search?${search.toString()}`)
        .then((result) => {
          if (!active) return;
          setTargetOptions(result.map((item) => ({
            classId: stringValue(item, "classId", "class_id"),
            className: stringValue(item, "className", "class_name"),
            classLoaderId: stringValue(item, "classLoaderId", "class_loader_id"),
            classLoaderClassName: stringValue(item, "classLoaderClassName", "class_loader_class_name"),
            methodName: stringValue(item, "methodName", "method_name"),
            descriptor: stringValue(item, "descriptor", "methodDescriptor", "method_descriptor"),
            returnType: stringValue(item, "returnType", "return_type"),
            parameterTypes: stringArrayValue(item, "parameterTypes", "parameter_types"),
            modifiable: Boolean(item.modifiable),
            instanceCount: Number(item.instanceCount ?? item.instance_count ?? 0),
            protocol: stringValue(item, "protocol") || "JAVA_METHOD",
          })).filter((item) =>
            item.classId && item.className && item.classLoaderId && item.methodName && item.descriptor,
          ));
        })
        .catch((error) => {
          if (active) toast.error(error instanceof Error ? error.message : "目标方法加载失败");
        })
        .finally(() => {
          if (active) setTargetsLoading(false);
        });
    }, 250);
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [applicationId, environmentId, manualTarget, targetQuery]);

  useEffect(() => {
    if (!ruleId) return;
    let active = true;
    void platformFetch<{
      rule: Record<string, unknown>;
      versions: Array<Record<string, unknown>>;
      targets: Array<Record<string, unknown>>;
    }>(`rules/${ruleId}/detail`).then((detail) => {
      if (!active) return;
      const latest = detail.versions[0];
      const previous = detail.versions[1];
      const target = detail.targets[0];
      const decodeScript = (value: unknown): { source: string; phase: InvokePhase } => {
        if (typeof value !== "string") return { source: initialScript, phase: "BEFORE" };
        try {
          const parsed = JSON.parse(value) as unknown;
          if (typeof parsed === "string") return { source: parsed, phase: "BEFORE" };
          if (parsed && typeof parsed === "object" && "script" in parsed) {
            const record = parsed as { script: unknown; phase?: unknown };
            const phase = ["BEFORE", "RETURN", "THROWS"].includes(String(record.phase))
              ? String(record.phase) as InvokePhase
              : "BEFORE";
            return { source: String(record.script), phase };
          }
        } catch {
          return { source: value, phase: "BEFORE" };
        }
        return { source: value, phase: "BEFORE" };
      };
      const currentScript = decodeScript(latest?.script_json);
      const targetMatcher = (() => {
        const raw = target?.matcher_json;
        if (raw && typeof raw === "object") return raw as Record<string, unknown>;
        if (typeof raw === "string") {
          try {
            return JSON.parse(raw) as Record<string, unknown>;
          } catch {
            return {};
          }
        }
        return {};
      })();
      setName(String(detail.rule.name ?? ruleId));
      setApplicationId(String(detail.rule.application_id ?? ""));
      setEnvironmentId(String(detail.rule.environment_id ?? ""));
      setScript(currentScript.source);
      setExecutionPhase(currentScript.phase);
      setTestInput(JSON.stringify({ phase: currentScript.phase, args: [] }, null, 2));
      setPreviousScript(previous ? decodeScript(previous.script_json).source : emptyPreviousScript);
      setClassId(String(targetMatcher.classId ?? target?.class_name ?? ""));
      setClassName(String(target?.class_name ?? ""));
      setClassLoaderId(String(targetMatcher.classLoaderId ?? ""));
      setMethodName(String(target?.method_name ?? ""));
      setMethodDescriptor(String(targetMatcher.descriptor ?? ""));
      setDirty(false);
    }).catch((error) => toast.error(error instanceof Error ? error.message : "规则详情加载失败"));
    return () => { active = false; };
  }, [ruleId]);

  useEffect(() => {
    let active = true;
    const host = globalThis as typeof globalThis & {
      MonacoEnvironment?: { getWorker: () => Worker };
    };
    host.MonacoEnvironment = {
      getWorker: () =>
        new Worker(new URL("monaco-editor/esm/vs/editor/editor.worker.js", import.meta.url), {
          type: "module",
        }),
    };
    void import("monaco-editor").then((monaco) => {
      loader.config({ monaco });
      defineEditorThemes(monaco);
      if (active) setMonacoReady(true);
    });
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    if (!monacoRef.current || !editorRef.current) return;
    monacoRef.current.editor.setModelMarkers(
      editorRef.current.getModel()!,
      "runtime-mock-server",
      diagnostics.map((item) => ({
        startLineNumber: item.line,
        endLineNumber: item.line,
        startColumn: item.column,
        endColumn: item.column + 1,
        message: `[${item.code}] ${item.message}`,
        severity:
          item.severity === "error"
            ? monacoRef.current!.MarkerSeverity.Error
            : item.severity === "warning"
              ? monacoRef.current!.MarkerSeverity.Warning
              : monacoRef.current!.MarkerSeverity.Info,
      })),
    );
  }, [diagnostics]);

  useEffect(() => {
    if (!monacoRef.current) return;
    const theme = focusMode ? "runtime-mock-focus" : "runtime-mock-light";
    defineEditorThemes(monacoRef.current);
    monacoRef.current.editor.setTheme(theme);
  }, [focusMode]);

  function editorWillMount(monaco: Monaco) {
    defineEditorThemes(monaco);
    monaco.editor.setTheme(focusMode ? "runtime-mock-focus" : "runtime-mock-light");
  }

  function editorMount(editorInstance: editor.IStandaloneCodeEditor, monaco: Monaco) {
    editorRef.current = editorInstance;
    monacoRef.current = monaco;
    defineEditorThemes(monaco);
    monaco.editor.setTheme(focusMode ? "runtime-mock-focus" : "runtime-mock-light");
    disposables.current = registerGroovy(monaco);
    editorInstance.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => void save());
  }

  function changeApplication(value: string) {
    setApplicationId(value);
    setEnvironmentId("");
    setClassId("");
    setClassName("");
    setClassLoaderId("");
    setMethodName("");
    setMethodDescriptor("");
    setTargetQuery("");
    setManualTarget(false);
    setDirty(true);
    setDiagnostics([]);
    setValidationStatus("idle");
    setTestResult(null);
  }

  function changeEnvironment(value: string) {
    setEnvironmentId(value);
    setClassId("");
    setClassName("");
    setClassLoaderId("");
    setMethodName("");
    setMethodDescriptor("");
    setTargetQuery("");
    setManualTarget(false);
    setDirty(true);
    setDiagnostics([]);
    setValidationStatus("idle");
    setTestResult(null);
  }

  function selectTarget(target: TargetOption) {
    setClassId(target.classId);
    setClassName(target.className);
    setClassLoaderId(target.classLoaderId);
    setMethodName(target.methodName);
    setMethodDescriptor(target.descriptor);
    setTargetQuery(`${target.className}#${target.methodName}${target.descriptor}`);
    setDirty(true);
    setDiagnostics([]);
    setValidationStatus("idle");
    setTestResult(null);
  }

  function changePhase(value: InvokePhase | "") {
    setExecutionPhase(value);
    if (value) {
      setTestInput((current) => {
        try {
          return JSON.stringify({ ...(JSON.parse(current) as object), phase: value }, null, 2);
        } catch {
          return JSON.stringify({ phase: value, args: [] }, null, 2);
        }
      });
    }
    setDirty(true);
    setDiagnostics([]);
    setValidationStatus("idle");
    setTestResult(null);
  }

  function requestPayload() {
    return {
      name,
      applicationId,
      environmentId,
      status: "DRAFT",
      versionStatus: "DRAFT",
      riskLevel: "LOW",
      script: { phase: executionPhase, script },
      matcher: { phase: executionPhase },
      targets: [{
        protocol: "JAVA_METHOD",
        className,
        methodName,
        matcher: { classId, classLoaderId, descriptor: methodDescriptor },
      }],
      capabilities: ["RETURN_VALUE", "THROW_EXCEPTION"],
      target: { classId, className, classLoaderId, methodName, methodDescriptor },
    };
  }

  async function validate() {
    if (!formComplete) {
      toast.error("请先完成规则名称、应用、环境、目标方法和执行阶段");
      return false;
    }
    setBusy("validate");
    setSideOpen(true);
    try {
      const result = await platformFetch<ScriptValidationResult>("scripts/validate", { method: "POST", body: JSON.stringify(requestPayload()) });
      setDiagnostics(result.diagnostics);
      setValidationStatus(result.valid ? "valid" : "invalid");
      setSideTab("diagnostics");
      toast[result.valid ? "success" : "error"](result.valid ? `校验通过（${result.compileTimeMs ?? 0} ms）` : "脚本存在需要处理的问题");
      return result.valid;
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "服务端校验失败");
      return false;
    } finally {
      setBusy(null);
    }
  }

  async function runTest() {
    if (!formComplete) {
      toast.error("请先完成规则配置，再进行试运行");
      return;
    }
    setBusy("test");
    setBottomTab("test");
    setBottomOpen(true);
    try {
      const input = JSON.parse(testInput) as object;
      const result = await platformFetch<ScriptTestResult>("scripts/test", { method: "POST", body: JSON.stringify({ ...requestPayload(), input }) });
      setTestResult(result);
      toast[result.status === "SUCCESS" ? "success" : "error"](result.status === "SUCCESS" ? "受控试运行完成" : "试运行返回异常");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "试运行失败，请检查 JSON 输入");
    } finally {
      setBusy(null);
    }
  }

  async function save() {
    if (!formComplete) {
      toast.error("请先完成规则名称、应用、环境、目标方法和执行阶段");
      return;
    }
    setBusy("save");
    try {
      const validation = await platformFetch<ScriptValidationResult>("scripts/validate", { method: "POST", body: JSON.stringify(requestPayload()) });
      setDiagnostics(validation.diagnostics);
      setValidationStatus(validation.valid ? "valid" : "invalid");
      if (!validation.valid) {
        toast.error("脚本校验未通过，已阻止保存");
        return;
      }
      const endpoint = ruleId ? `rules/${ruleId}/versions` : "rules";
      const saved = await platformFetch<{ id?: string; rule_id?: string }>(endpoint, { method: "POST", body: JSON.stringify(requestPayload()), idempotencyKey: crypto.randomUUID() });
      setDirty(false);
      toast.success(ruleId ? "已创建不可变规则版本" : "规则草稿已保存");
      if (!ruleId && saved.id) router.replace(`/rules/${saved.id}`);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "保存失败，编辑内容仍保留在当前页面");
    } finally {
      setBusy(null);
    }
  }

  function downloadDraft() {
    const blob = new Blob([script], { type: "text/x-groovy" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `${name || "runtime-mock-rule"}.groovy`;
    link.click();
    URL.revokeObjectURL(url);
  }

  function requestNavigation(href: string) {
    if (dirty) {
      setPendingNavigation(href);
      return;
    }
    router.push(href);
  }

  function leaveWithoutSaving() {
    const href = pendingNavigation;
    setPendingNavigation(null);
    setDirty(false);
    if (href) router.push(href);
  }

  return (
    <div
      data-testid="rule-workbench"
      className={cn(
        "flex min-h-0 flex-col",
        focusMode ? "fixed inset-0 z-50 h-screen overflow-hidden bg-slate-950 p-3" : "lg:h-full lg:overflow-hidden",
      )}
    >
      <div className="mb-4 flex shrink-0 flex-wrap items-center gap-3">
        <Button variant="ghost" size="icon" className={cn(focusMode && "text-slate-300 hover:bg-white/10 hover:text-white")} onClick={() => requestNavigation("/rules")}><ArrowLeft /></Button>
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <h1 className={cn("truncate text-lg font-semibold", focusMode ? "text-white" : "text-slate-950")}>{name.trim() || "创建规则"}</h1>
            {!ruleId ? <Badge variant="warning">尚未保存</Badge> : dirty ? <Badge variant="warning">未保存</Badge> : <Badge variant="success">已保存</Badge>}
          </div>
          <p className={cn("text-xs", focusMode ? "text-slate-500" : "text-slate-400")}>{ruleId ? `创建 ${ruleId} 的新版本` : "新建规则"} · Groovy 安全沙箱</p>
        </div>
        <div className="ml-auto flex flex-wrap items-center gap-2">
          <Button variant="secondary" size="sm" className={cn(focusMode && "border-white/10 bg-white/5 text-slate-200 hover:bg-white/10")} onClick={downloadDraft} disabled={!editorEnabled}><Download />下载草稿</Button>
          <Button variant="secondary" size="sm" className={cn(focusMode && "border-indigo-400/30 bg-indigo-400/10 text-indigo-200 hover:bg-indigo-400/20")} onClick={() => setFocusMode((value) => !value)}>{focusMode ? <Shrink /> : <Expand />}{focusMode ? "退出专注" : "专注模式"}</Button>
          <Button variant="secondary" size="sm" className={cn(focusMode && "border-white/10 bg-white/5 text-slate-200 hover:bg-white/10")} onClick={validate} disabled={Boolean(busy) || !formComplete}>{busy === "validate" ? <Loader2 className="animate-spin" /> : <ShieldAlert />}校验</Button>
          <Button variant="secondary" size="sm" className={cn(focusMode && "border-white/10 bg-white/5 text-slate-200 hover:bg-white/10")} onClick={runTest} disabled={Boolean(busy) || !formComplete}>{busy === "test" ? <Loader2 className="animate-spin" /> : <Play />}试运行</Button>
          <Button size="sm" onClick={save} disabled={Boolean(busy) || !formComplete}>{busy === "save" ? <Loader2 className="animate-spin" /> : <Save />}{ruleId ? "保存新版本" : "保存草稿"}</Button>
        </div>
      </div>

      <div
        data-testid="rule-workbench-frame"
        className={cn(
          "min-h-0 overflow-clip rounded-xl border bg-white shadow-sm",
          focusMode
            ? "flex-1 border-white/10 bg-slate-900 shadow-2xl"
            : "h-[720px] min-h-[620px] border-slate-200 lg:h-auto lg:min-h-0 lg:flex-1",
        )}
      >
        <div
          className="grid h-full min-h-0 overflow-hidden"
          style={{
            gridTemplateColumns: `${focusMode ? 0 : leftWidth}px minmax(460px,1fr) ${focusMode || !sideOpen ? 0 : 300}px`,
            gridTemplateRows: bottomOpen && !focusMode ? "minmax(240px,1fr) minmax(180px,34%)" : "minmax(0,1fr) 0px",
          }}
        >
          <aside
            data-testid="rule-config-scroll"
            className={cn("scrollbar-thin min-h-0 overflow-y-auto overscroll-contain border-r bg-slate-50/70", focusMode && "invisible")}
          >
            <div className="flex items-center justify-between border-b bg-white px-4 py-3"><span className="flex items-center gap-2 text-sm font-semibold"><Focus className="size-4 text-indigo-600" />目标与策略</span><ChevronDown className="size-4 text-slate-400" /></div>
            <div className="space-y-4 p-4">
              <div>
                <div className="mb-3 flex items-center gap-2">
                  <span className="flex size-5 items-center justify-center rounded-full bg-indigo-600 text-[10px] font-bold text-white">1</span>
                  <p className="text-xs font-semibold text-slate-700">基础信息</p>
                </div>
                <label className="block" htmlFor="rule-name">
                  <span className="mb-1.5 block text-xs font-medium text-slate-600">规则名称</span>
                  <Input id="rule-name" placeholder="例如：支付超时故障注入" value={name} onChange={(event) => { setName(event.target.value); setDirty(true); }} />
                </label>
                <div className="mt-3 block">
                  <span className="mb-1.5 block text-xs font-medium text-slate-600">应用</span>
                  <Select
                    value={applicationId}
                    onValueChange={changeApplication}
                    disabled={metadataLoading || Boolean(metadataError)}
                  >
                    <SelectTrigger id="rule-application" aria-label="应用">
                      <SelectValue placeholder={metadataLoading ? "正在加载应用…" : "请选择应用"} />
                    </SelectTrigger>
                    <SelectContent>
                      {applicationId && !applicationIds.includes(applicationId) ? <SelectItem value={applicationId}>{applicationId}</SelectItem> : null}
                      {applications.map((application) => <SelectItem key={application.id} value={application.id}>{application.label}</SelectItem>)}
                    </SelectContent>
                  </Select>
                </div>
                <div className="mt-3 block">
                  <span className="mb-1.5 block text-xs font-medium text-slate-600">环境</span>
                  <Select
                    value={environmentId}
                    onValueChange={changeEnvironment}
                    disabled={!applicationId}
                  >
                    <SelectTrigger id="rule-environment" aria-label="环境">
                      <SelectValue placeholder={applicationId ? "请选择环境" : "请先选择应用"} />
                    </SelectTrigger>
                    <SelectContent>
                      {environmentId && !environmentIds.includes(environmentId) ? <SelectItem value={environmentId}>{environmentId}</SelectItem> : null}
                      {environments.map((environment) => <SelectItem key={environment.id} value={environment.id}>{environment.label}</SelectItem>)}
                    </SelectContent>
                  </Select>
                </div>
                {metadataError ? (
                  <div className="mt-3 rounded-lg border border-red-100 bg-red-50 p-3 text-xs leading-5 text-red-700">
                    {metadataError}。请先确认 Platform API 可用。
                  </div>
                ) : !metadataLoading && !applications.length ? (
                  <div className="mt-3 rounded-lg border border-amber-100 bg-amber-50 p-3 text-xs leading-5 text-amber-800">
                    尚未发现应用实例。请先在“应用与环境”中完成接入。
                  </div>
                ) : null}
              </div>

              <div className="border-t pt-4">
                <div className="mb-3 flex items-center gap-2">
                  <span className={cn("flex size-5 items-center justify-center rounded-full text-[10px] font-bold", environmentId ? "bg-indigo-600 text-white" : "bg-slate-200 text-slate-500")}>2</span>
                  <p className="text-xs font-semibold text-slate-700">目标方法</p>
                </div>
                {!environmentId ? (
                  <p className="rounded-lg border border-dashed p-3 text-xs leading-5 text-slate-400">选择应用和环境后，再搜索目标方法。</p>
                ) : manualTarget ? (
                  <>
                    <label className="block">
                      <span className="mb-1.5 block text-xs font-medium text-slate-600">运行时类 ID</span>
                      <Input
                        className="font-mono text-xs"
                        placeholder="目标发现返回的 classId；也可填写完整类名"
                        value={classId}
                        onChange={(event) => { setClassId(event.target.value); setDirty(true); }}
                      />
                    </label>
                    <label className="block">
                      <span className="mb-1.5 mt-3 block text-xs font-medium text-slate-600">Java 类名</span>
                      <Input
                        className="font-mono text-xs"
                        placeholder="com.example.Service"
                        value={className}
                        onChange={(event) => { setClassName(event.target.value); setDirty(true); }}
                      />
                    </label>
                    <label className="mt-3 block">
                      <span className="mb-1.5 block text-xs font-medium text-slate-600">方法名</span>
                      <Input
                        className="font-mono text-xs"
                        placeholder="execute"
                        value={methodName}
                        onChange={(event) => { setMethodName(event.target.value); setDirty(true); }}
                      />
                    </label>
                    <label className="mt-3 block">
                      <span className="mb-1.5 block text-xs font-medium text-slate-600">类加载器 ID</span>
                      <Input
                        className="font-mono text-xs"
                        placeholder="例如：jdk.internal.loader.ClassLoaders$AppClassLoader@..."
                        value={classLoaderId}
                        onChange={(event) => { setClassLoaderId(event.target.value); setDirty(true); }}
                      />
                    </label>
                    <label className="mt-3 block">
                      <span className="mb-1.5 block text-xs font-medium text-slate-600">JVM 方法描述符</span>
                      <Input
                        className="font-mono text-xs"
                        placeholder="例如：(I)I"
                        value={methodDescriptor}
                        onChange={(event) => { setMethodDescriptor(event.target.value); setDirty(true); }}
                      />
                    </label>
                    <p className="mt-2 text-[10px] leading-4 text-amber-700">手动模式用于目标发现不可用时的技术兜底；发布仍会按类加载器和 JVM 描述符精确匹配。</p>
                    <button type="button" onClick={() => {
                      setManualTarget(false);
                      setClassId("");
                      setClassName("");
                      setClassLoaderId("");
                      setMethodName("");
                      setMethodDescriptor("");
                    }} className="mt-2 text-xs font-medium text-indigo-600 hover:text-indigo-700">返回搜索运行时方法</button>
                  </>
                ) : (
                  <>
                    <div className="relative">
                      <Search className="absolute left-3 top-3 size-4 text-slate-400" />
                      <Input
                        className="pl-9"
                        placeholder="搜索类名或方法名"
                        value={targetQuery}
                        onChange={(event) => setTargetQuery(event.target.value)}
                      />
                    </div>
                    {targetSelected ? (
                      <div className="mt-3 rounded-lg border border-indigo-100 bg-indigo-50 p-3">
                        <div className="flex items-start gap-2">
                          <Target className="mt-0.5 size-4 shrink-0 text-indigo-600" />
                          <div className="min-w-0">
                            <p className="truncate font-mono text-[11px] text-indigo-900">{className}</p>
                            <p className="mt-1 truncate font-mono text-xs font-semibold text-indigo-700">#{methodName}{methodDescriptor}</p>
                            <p className="mt-1 truncate font-mono text-[10px] text-indigo-500">加载器：{classLoaderId}</p>
                          </div>
                          <button type="button" onClick={() => {
                            setClassId("");
                            setClassName("");
                            setClassLoaderId("");
                            setMethodName("");
                            setMethodDescriptor("");
                            setTargetQuery("");
                            setExecutionPhase("");
                            setDirty(true);
                            setDiagnostics([]);
                            setValidationStatus("idle");
                            setTestResult(null);
                          }} className="ml-auto shrink-0 text-[10px] font-medium text-indigo-600 hover:text-indigo-800">更换</button>
                        </div>
                      </div>
                    ) : (
                      <div className="mt-2 max-h-56 space-y-1 overflow-y-auto pr-1">
                        {targetsLoading ? (
                          <div className="flex items-center justify-center py-5 text-xs text-slate-400"><Loader2 className="mr-2 size-3.5 animate-spin" />正在搜索目标方法…</div>
                        ) : targetOptions.length ? targetOptions.map((target) => (
                          <button
                            type="button"
                            key={`${target.classId}#${target.methodName}${target.descriptor}`}
                            onClick={() => selectTarget(target)}
                            className="w-full min-w-0 overflow-hidden rounded-lg border border-transparent px-3 py-2 text-left hover:border-indigo-100 hover:bg-indigo-50"
                          >
                            <p className="truncate font-mono text-[10px] text-slate-500">{target.className}</p>
                            <p className="mt-1 truncate font-mono text-xs font-medium text-slate-800">#{target.methodName}{target.descriptor}</p>
                            <p className="mt-1 truncate text-[10px] text-slate-400">
                              {target.parameterTypes.join(", ") || "无参数"} → {target.returnType || "void"} · {target.instanceCount} 个在线实例
                            </p>
                          </button>
                        )) : (
                          <p className="rounded-lg border border-dashed p-3 text-xs leading-5 text-slate-400">当前在线 JVM 中没有发现匹配方法；可切换到手动填写。</p>
                        )}
                      </div>
                    )}
                    <button type="button" onClick={() => {
                      setManualTarget(true);
                      setTargetQuery("");
                      setClassId("");
                      setClassName("");
                      setClassLoaderId("");
                      setMethodName("");
                      setMethodDescriptor("");
                    }} className="mt-2 text-xs font-medium text-indigo-600 hover:text-indigo-700">手动填写精确目标</button>
                  </>
                )}
              </div>

              <div className="border-t pt-4">
                <div className="mb-3 flex items-center gap-2">
                  <span className={cn("flex size-5 items-center justify-center rounded-full text-[10px] font-bold", targetSelected ? "bg-indigo-600 text-white" : "bg-slate-200 text-slate-500")}>3</span>
                  <p className="text-xs font-semibold text-slate-700">执行策略</p>
                </div>
                {!targetSelected ? (
                  <p className="rounded-lg border border-dashed p-3 text-xs leading-5 text-slate-400">选择目标方法后设置执行阶段。规则发布后会持续生效，直到在发布管理中卸载。</p>
                ) : (
                  <>
                    <div className="block">
                      <span className="mb-1.5 block text-xs font-medium text-slate-600">执行阶段</span>
                      <Select value={executionPhase} onValueChange={(value) => changePhase(value as InvokePhase)}>
                        <SelectTrigger id="rule-phase" aria-label="执行阶段">
                          <SelectValue placeholder="请选择执行阶段" />
                        </SelectTrigger>
                        <SelectContent>
                          <SelectItem value="BEFORE">调用前</SelectItem>
                          <SelectItem value="RETURN">正常返回后</SelectItem>
                          <SelectItem value="THROWS">抛出异常时</SelectItem>
                        </SelectContent>
                      </Select>
                    </div>
                    <p className="mt-3 rounded-lg border border-indigo-100 bg-indigo-50 px-3 py-2 text-xs leading-5 text-indigo-700">
                      规则发布后持续生效；需要恢复原始行为时，请到发布管理执行卸载。
                    </p>
                  </>
                )}
              </div>
              <div className="rounded-lg border border-slate-200/80 bg-white/70 px-3 py-2">
                <div className="mb-1.5 flex items-center justify-between text-[10px] text-slate-400">
                  <span>配置区宽度</span>
                  <span>{leftWidth}px</span>
                </div>
                <Slider
                  aria-label="左侧面板宽度"
                  min={220}
                  max={380}
                  step={10}
                  value={[leftWidth]}
                  onValueChange={([value]) => setLeftWidth(value)}
                />
              </div>
            </div>
          </aside>

          <section
            className={cn("min-h-0 min-w-0 overflow-hidden border-r", focusMode ? "border-white/10 bg-slate-900" : "border-slate-200 bg-[#f8fafd]")}
            data-testid="rule-editor-surface"
            data-editor-theme={focusMode ? "dark" : "light"}
          >
            <div className={cn("flex h-11 items-center border-b px-3 text-xs", focusMode ? "border-white/10 bg-[#111a2d] text-slate-300" : "border-slate-200 bg-white/85 text-slate-600")}>
              <Code2 className={cn("mr-2 size-4", focusMode ? "text-indigo-300" : "text-indigo-600")} />
              <span className="font-mono">rule.groovy</span>
              {executionPhase ? <Badge variant="neutral" className={cn("ml-3", focusMode && "border-white/10 bg-white/10 text-slate-300")}>{phaseLabel(executionPhase)}</Badge> : null}
              <div className="ml-auto flex items-center gap-1">
                {!focusMode ? (
                  <>
                    <button type="button" onClick={() => setBottomOpen((value) => !value)} className={cn("rounded p-1.5 hover:bg-slate-200/70", bottomOpen ? "text-indigo-600" : "text-slate-400")} aria-label={bottomOpen ? "收起试运行面板" : "展开试运行面板"}><PanelBottomOpen className="size-3.5" /></button>
                    <button type="button" onClick={() => setSideOpen((value) => !value)} className={cn("rounded p-1.5 hover:bg-slate-200/70", sideOpen ? "text-indigo-600" : "text-slate-400")} aria-label={sideOpen ? "收起诊断面板" : "展开诊断面板"}><PanelRightOpen className="size-3.5" /></button>
                  </>
                ) : null}
                <span className={cn("ml-1 flex items-center gap-1.5", focusMode ? "text-slate-500" : "text-slate-400")}><Braces className="size-3.5" />Groovy · UTF-8</span>
              </div>
            </div>
            <div className="relative min-h-0 h-[calc(100%-44px)]">
              {editorEnabled && monacoReady ? (
                <MonacoEditor
                  height="100%"
                  language="groovy-runtime-mock"
                  value={script}
                  onChange={markDirty}
                  beforeMount={editorWillMount}
                  onMount={editorMount}
                  path={`runtime-mock://rules/${ruleId ?? "new"}/draft.groovy`}
                  theme={focusMode ? "runtime-mock-focus" : "runtime-mock-light"}
                  options={{ minimap: { enabled: false }, fontFamily: "'JetBrains Mono', 'SFMono-Regular', Consolas, 'Liberation Mono', monospace", fontSize: 13, lineHeight: 22, fontLigatures: true, tabSize: 4, insertSpaces: true, automaticLayout: true, padding: { top: 16, bottom: 16 }, scrollBeyondLastLine: false, bracketPairColorization: { enabled: true }, suggest: { showSnippets: true }, wordWrap: "on", renderLineHighlight: "all", overviewRulerBorder: false, hideCursorInOverviewRuler: true, foldingHighlight: false, smoothScrolling: true }}
                />
              ) : editorEnabled ? (
                <div className={cn("flex h-full items-center justify-center text-sm", focusMode ? "bg-slate-900 text-slate-500" : "bg-slate-50 text-slate-400")}><Loader2 className="mr-2 size-4 animate-spin" />正在初始化本地代码编辑器…</div>
              ) : (
                <div className="flex h-full items-center justify-center bg-[radial-gradient(circle_at_top,_#f2f3ff_0,_#fbfcfe_45%,_#f8fafc_100%)] p-8 text-center">
                  <div className="max-w-md rounded-2xl border border-white bg-white/75 px-8 py-7 shadow-[0_18px_45px_-30px_rgba(79,70,229,0.45)] backdrop-blur">
                    <div className="mx-auto mb-4 flex size-12 items-center justify-center rounded-2xl border border-indigo-100 bg-indigo-50 text-indigo-600 shadow-sm"><Code2 className="size-5" /></div>
                    <p className="font-semibold text-slate-900">{targetSelected ? "请选择执行阶段" : "请先选择目标方法"}</p>
                    <p className="mt-2 text-xs leading-5 text-slate-500">完成左侧配置后再加载代码编辑器，让注意力保持在当前步骤。编辑器将以安全的 <code className="rounded bg-slate-100 px-1.5 py-0.5 text-indigo-700">mock.proceed()</code> 骨架开始。</p>
                    <div className="mt-5 flex flex-wrap justify-center gap-2 text-[10px] font-medium text-slate-500">
                      <span className="rounded-full border border-slate-200 bg-white px-2.5 py-1">安全默认脚本</span>
                      <span className="rounded-full border border-slate-200 bg-white px-2.5 py-1">服务端校验</span>
                      <span className="rounded-full border border-slate-200 bg-white px-2.5 py-1">受控试运行</span>
                    </div>
                  </div>
                </div>
              )}
            </div>
          </section>

          <aside className={cn("scrollbar-thin min-h-0 overflow-y-auto overscroll-contain bg-white", (focusMode || !sideOpen) && "invisible")}>
            <div className="flex h-11 border-b">
              <button onClick={() => setSideTab("diagnostics")} className={cn("flex-1 border-b-2 text-xs font-medium", sideTab === "diagnostics" ? "border-indigo-600 text-indigo-700" : "border-transparent text-slate-400")}>诊断 ({diagnostics.length})</button>
              <button onClick={() => setSideTab("context")} className={cn("flex-1 border-b-2 text-xs font-medium", sideTab === "context" ? "border-indigo-600 text-indigo-700" : "border-transparent text-slate-400")}>上下文</button>
            </div>
            {sideTab === "diagnostics" ? (
              <div className="p-3">
                {!diagnostics.length ? (
                  validationStatus === "valid"
                    ? <div className="rounded-xl border border-emerald-200 bg-emerald-50/60 p-6 text-center"><CheckCircle2 className="mx-auto mb-2 size-6 text-emerald-500" /><p className="text-sm font-medium text-emerald-800">校验通过</p><p className="mt-1 text-xs leading-5 text-emerald-600">未发现语法或安全策略问题。</p></div>
                    : validationStatus === "invalid"
                      ? <div className="rounded-xl border border-red-200 bg-red-50/60 p-6 text-center"><XCircle className="mx-auto mb-2 size-6 text-red-500" /><p className="text-sm font-medium text-red-800">校验未通过</p><p className="mt-1 text-xs leading-5 text-red-600">服务端未返回具体诊断，请稍后重试或检查平台日志。</p></div>
                      : <div className="rounded-xl border border-dashed p-6 text-center"><ShieldAlert className="mx-auto mb-2 size-6 text-slate-400" /><p className="text-sm font-medium text-slate-700">尚未校验</p><p className="mt-1 text-xs leading-5 text-slate-400">完成规则配置后，点击“校验”进行语法与安全策略检查。</p></div>
                ) : diagnostics.map((item, index) => (
                  <button key={`${item.code}-${index}`} onClick={() => { editorRef.current?.revealPositionInCenter({ lineNumber: item.line, column: item.column }); editorRef.current?.setPosition({ lineNumber: item.line, column: item.column }); editorRef.current?.focus(); }} className="mb-2 w-full rounded-lg border p-3 text-left hover:bg-slate-50">
                    <div className="flex items-center gap-2">{item.severity === "error" ? <XCircle className="size-4 text-red-500" /> : item.severity === "warning" ? <AlertTriangle className="size-4 text-amber-500" /> : <Info className="size-4 text-sky-500" />}<span className="font-mono text-[10px] text-slate-400">{item.code}</span><span className="ml-auto text-[10px] text-slate-400">{item.line}:{item.column}</span></div>
                    <p className="mt-2 text-xs leading-5 text-slate-700">{item.message}</p>
                  </button>
                ))}
              </div>
            ) : (
              <div className="space-y-3 p-3">
                {[
                  ["args", "List<Object>", "当前方法的参数列表"],
                  ["result", "Object", "原始方法返回值"],
                  ["throwable", "Throwable", "原始调用异常"],
                  ["ctx", "InvocationContext", "阶段、方法、参数、结果和异常"],
                  ["mock", "MockApi", "创建 proceed、returnValue 和 throwException 决策"],
                ].map(([variable, type, detail]) => <div key={variable} className="rounded-lg border p-3"><div className="flex items-center justify-between"><code className="text-xs font-semibold text-indigo-700">{variable}</code><Badge variant="neutral">{type}</Badge></div><p className="mt-2 text-xs text-slate-500">{detail}</p></div>)}
                <div className="rounded-lg border border-indigo-100 bg-indigo-50 p-3 text-xs leading-5 text-indigo-800"><BookOpen className="mb-2 size-4" />脚本只在服务端受控沙箱执行；浏览器仅提供编辑、高亮和诊断定位。</div>
              </div>
            )}
          </aside>

          <section className={cn("col-span-3 min-h-0 min-w-0 overflow-hidden border-t bg-white", (!bottomOpen || focusMode) && "invisible")}>
            <div className="flex h-10 items-center border-b px-3">
              <button onClick={() => setBottomTab("test")} className={cn("flex h-full items-center gap-2 border-b-2 px-3 text-xs font-medium", bottomTab === "test" ? "border-indigo-600 text-indigo-700" : "border-transparent text-slate-400")}><Beaker className="size-3.5" />试运行</button>
              <button onClick={() => setBottomTab("diff")} className={cn("flex h-full items-center gap-2 border-b-2 px-3 text-xs font-medium", bottomTab === "diff" ? "border-indigo-600 text-indigo-700" : "border-transparent text-slate-400")}><FileDiff className="size-3.5" />版本 Diff</button>
              <div className="ml-auto flex items-center gap-2 text-[10px] text-slate-400"><Clock3 className="size-3" />服务端执行上限 1000 ms</div>
            </div>
            {bottomTab === "test" ? (
              <div className="grid h-[calc(100%-40px)] min-h-0 grid-cols-2 divide-x">
                <div className="min-h-0 overflow-y-auto overscroll-contain p-3"><div className="mb-2 flex items-center justify-between"><span className="text-xs font-medium text-slate-600">受控输入（JSON）</span><Button size="sm" variant="ghost" onClick={() => navigator.clipboard.writeText(testInput)}><Copy />复制</Button></div><Textarea value={testInput} onChange={(event) => setTestInput(event.target.value)} className="min-h-24 h-[calc(100%-36px)] resize-none font-mono text-xs" /></div>
                <div className="scrollbar-thin min-h-0 overflow-y-auto overscroll-contain bg-slate-50/70 p-3 text-xs text-slate-600">
                  {!testResult ? <div className="flex h-full flex-col items-center justify-center text-slate-400"><div className="mb-3 flex size-10 items-center justify-center rounded-xl border border-slate-200 bg-white shadow-sm"><TerminalSquare className="size-5 text-slate-500" /></div><span>点击“试运行”查看输出、异常、日志和对象差异</span></div> : (
                    <>
                      <div className="mb-3 flex items-center gap-2">{testResult.status === "SUCCESS" ? <CheckCircle2 className="size-4 text-emerald-500" /> : <XCircle className="size-4 text-red-500" />}<span className="font-semibold text-slate-800">{testResult.status}</span><span className="ml-auto text-slate-400">{testResult.durationMs} ms</span></div>
                      <pre className="whitespace-pre-wrap rounded-lg bg-slate-900 p-3 font-mono leading-5 text-slate-200 shadow-inner">{JSON.stringify(testResult.output ?? testResult.exception, null, 2)}</pre>
                      <div className="mt-3 space-y-1 text-slate-500">{testResult.logs.map((log) => <div key={log}>› {log}</div>)}</div>
                    </>
                  )}
                </div>
              </div>
            ) : (
              <div className="h-[calc(100%-40px)] min-h-0">
                {monacoReady ? <MonacoDiffEditor original={previousScript} modified={script} language="groovy-runtime-mock" theme="runtime-mock-light" options={{ readOnly: true, renderSideBySide: true, minimap: { enabled: false }, automaticLayout: true, fontSize: 11, lineHeight: 17 }} /> : null}
              </div>
            )}
          </section>
        </div>
      </div>
      <div className={cn("mt-2 flex shrink-0 items-center text-[10px]", focusMode ? "text-slate-600" : "text-slate-400")}><Sparkles className="mr-1 size-3" />Ctrl/⌘ + S 保存 · 拖动左侧滑块可调整配置区宽度 · 所有脚本校验和执行都由 Platform API 完成</div>
      <Dialog open={Boolean(pendingNavigation)} onOpenChange={(open) => { if (!open) setPendingNavigation(null); }}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>离开前保存当前规则？</DialogTitle>
            <DialogDescription>
              当前规则还有未保存的配置或脚本。直接离开会丢失这些改动。
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="secondary" onClick={() => setPendingNavigation(null)}>继续编辑</Button>
            <Button variant="destructive" onClick={leaveWithoutSaving}>放弃并离开</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
