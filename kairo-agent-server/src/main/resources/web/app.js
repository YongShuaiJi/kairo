const state = {
  token: "",
  selectedClass: null,
  selectedMethod: null,
  phase: "BEFORE"
};

const $ = (id) => document.getElementById(id);

const templates = {
  fixedReturn: `return mock.returnJson('''\n{\n  "id": "MOCK-001",\n  "status": "SUCCESS",\n  "amount": 1,\n  "message": "mocked"\n}\n''')`,
  throwException: `return mock.throwException('com.example.demo.BizException', 'mock failure')`,
  setArg: `mock.set(args[0], 'amount', new java.math.BigDecimal('1'))\nreturn mock.proceed()`,
  setResult: `result.status = 'REVIEW_REQUIRED'\nresult.message = 'changed by Kairo'\nreturn mock.returnValue(result)`,
  throwsReturn: `return mock.returnJson('''\n{\n  "id": "DEGRADED",\n  "status": "DEGRADED",\n  "amount": 0,\n  "message": "fallback"\n}\n''')`
};

function api(path, options = {}) {
  const url = `/v1${path}`;
  return fetch(url, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      "Authorization": `Bearer ${state.token}`,
      ...(options.headers || {})
    }
  }).then(async (response) => {
    const text = await response.text();
    const body = text ? JSON.parse(text) : null;
    if (!response.ok) {
      throw new Error(body?.message || body?.error || response.statusText);
    }
    return body;
  });
}

function setConnection(ok, message) {
  const el = $("connectionState");
  el.textContent = message;
  el.className = `state ${ok ? "ok" : "bad"}`;
}

async function refreshAll() {
  const [jvm, metrics, rules, events] = await Promise.all([
    api("/jvm"),
    api("/metrics"),
    api("/rules"),
    api("/events")
  ]);
  $("pid").textContent = jvm.pid;
  $("jdk").textContent = jvm.javaVersion;
  $("enhanced").textContent = `${jvm.enhancedClassCount}/${jvm.enhancedMethodCount}`;
  $("activeRules").textContent = metrics.activeRuleCount;
  $("hits").textContent = metrics.totalHits;
  $("errors").textContent = metrics.totalErrors;
  renderRules(rules);
  renderEvents(events);
  setConnection(true, `${jvm.status} ${jvm.loadMode}`);
}

async function searchClasses(event) {
  event?.preventDefault();
  const keyword = $("classKeyword").value.trim();
  const classes = await api(`/classes?keyword=${encodeURIComponent(keyword)}&limit=80`);
  const root = $("classResults");
  root.innerHTML = "";
  classes.forEach((item) => {
    const row = document.createElement("div");
    row.className = "row";
    row.innerHTML = `<strong>${escapeHtml(item.className)}</strong><span>${escapeHtml(item.classLoaderId)} ${escapeHtml(item.classLoaderClassName)}</span>`;
    row.onclick = () => selectClass(item, row);
    root.appendChild(row);
  });
}

async function selectClass(item, row) {
  document.querySelectorAll("#classResults .row").forEach((el) => el.classList.remove("active"));
  row.classList.add("active");
  state.selectedClass = item;
  $("className").value = item.className;
  $("classLoaderId").value = item.classLoaderId;
  const methods = await api(`/classes/${encodeURIComponent(item.classId)}/methods`);
  const root = $("methodResults");
  root.innerHTML = "";
  methods.forEach((method) => {
    const methodRow = document.createElement("div");
    methodRow.className = "row";
    methodRow.innerHTML = `<strong>${escapeHtml(method.name)}</strong><code>${escapeHtml(method.descriptor)}</code><span>${escapeHtml(method.returnType)}</span>`;
    methodRow.onclick = () => selectMethod(method, methodRow);
    root.appendChild(methodRow);
  });
}

function selectMethod(method, row) {
  document.querySelectorAll("#methodResults .row").forEach((el) => el.classList.remove("active"));
  row.classList.add("active");
  state.selectedMethod = method;
  $("methodName").value = method.name;
  $("methodDescriptor").value = method.descriptor;
  if (!$("ruleId").value) {
    $("ruleId").value = `${method.name}-${Date.now()}`;
    $("ruleName").value = $("ruleId").value;
  }
}

async function compileScript() {
  const body = {
    ruleId: $("ruleId").value || "compile-check",
    version: Number(Date.now()),
    script: $("script").value
  };
  try {
    const result = await api("/scripts/compile", { method: "POST", body: JSON.stringify(body) });
    $("compileResult").textContent = `OK ${result.scriptHash}`;
  } catch (error) {
    $("compileResult").textContent = error.message;
  }
}

async function publishRule() {
  if (!state.selectedClass || !state.selectedMethod) {
    $("compileResult").textContent = "Select a class and method first.";
    return;
  }
  const body = {
    id: $("ruleId").value,
    version: Number(Date.now()),
    name: $("ruleName").value,
    classId: state.selectedClass.classId,
    className: $("className").value,
    classLoaderId: $("classLoaderId").value,
    methodName: $("methodName").value,
    methodDescriptor: $("methodDescriptor").value,
    phase: state.phase,
    script: $("script").value,
    priority: Number($("priority").value),
    percentage: Number($("percentage").value),
    maxHits: Number($("maxHits").value),
    expireAt: Number($("expireAt").value),
    failOpen: $("failOpen").checked,
    enabled: $("enabled").checked
  };
  await api("/rules", { method: "POST", body: JSON.stringify(body) });
  await refreshAll();
}

function renderRules(rules) {
  const root = $("ruleList");
  root.innerHTML = "";
  rules.forEach((rule) => {
    const row = document.createElement("div");
    row.className = "row";
    row.innerHTML = `<strong>${escapeHtml(rule.id)} ${rule.enabled ? "" : "(disabled)"}</strong><span>${escapeHtml(rule.className)}#${escapeHtml(rule.methodName)} ${escapeHtml(rule.phase)}</span><span>hits ${rule.hits} errors ${rule.errors}</span>`;
    const controls = document.createElement("div");
    controls.className = "toolbar";
    controls.innerHTML = `<button data-action="toggle">${rule.enabled ? "Disable" : "Enable"}</button><button data-action="delete" class="danger">Delete</button>`;
    controls.querySelector("[data-action=toggle]").onclick = async () => {
      await api(`/rules/${encodeURIComponent(rule.id)}/${rule.enabled ? "disable" : "enable"}`, { method: "POST" });
      await refreshAll();
    };
    controls.querySelector("[data-action=delete]").onclick = async () => {
      await api(`/rules/${encodeURIComponent(rule.id)}`, { method: "DELETE" });
      await refreshAll();
    };
    row.appendChild(controls);
    root.appendChild(row);
  });
}

function renderEvents(events) {
  const root = $("eventList");
  root.innerHTML = "";
  events.slice(-80).reverse().forEach((event) => {
    const row = document.createElement("div");
    row.className = "event";
    row.innerHTML = `<strong>${escapeHtml(event.type)}</strong><br><time>${new Date(event.timestamp).toLocaleString()}</time><br><span>${escapeHtml(event.message || "")}</span>`;
    root.appendChild(row);
  });
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

document.querySelectorAll("[data-phase]").forEach((button) => {
  button.onclick = () => {
    document.querySelectorAll("[data-phase]").forEach((el) => el.classList.remove("active"));
    button.classList.add("active");
    state.phase = button.dataset.phase;
  };
});

document.querySelectorAll("[data-template]").forEach((button) => {
  button.onclick = () => {
    $("script").value = templates[button.dataset.template];
  };
});

$("connectionForm").onsubmit = async (event) => {
  event.preventDefault();
  state.token = $("agentToken").value;
  try {
    await refreshAll();
  } catch (error) {
    setConnection(false, error.message);
  }
};

$("classSearch").onsubmit = searchClasses;
$("refreshJvm").onclick = refreshAll;
$("refreshRules").onclick = refreshAll;
$("compileScript").onclick = compileScript;
$("saveRule").onclick = publishRule;
$("formatScript").onclick = () => {
  $("script").value = $("script").value.replace(/\t/g, "    ").trim() + "\n";
};
$("disableAll").onclick = async () => {
  await api("/agent/disable-all", { method: "POST" });
  await refreshAll();
};
$("resetAll").onclick = async () => {
  await api("/agent/reset-all", { method: "POST" });
  await refreshAll();
};

refreshAll().catch((error) => setConnection(false, error.message));
