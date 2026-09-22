import React, { useState, useMemo } from "react";

/* ============================================================================
   HYDRATION — operational dashboard, vertical slice
   ----------------------------------------------------------------------------
   Implements, end to end, on a synthetic subject:
     L1  deficit tracker        (grey-box water balance)
     L2  need estimator         (forward deficit projection, physiology only)
     L3  receptivity            (fixed heuristic — NOT yet learned)
     L4  policy                 (need gate + receptivity timing + hard floor)
     Baseline policy            (hourly volume-lapse, i.e. what sipIT does)

   NOT IMPLEMENTED (state this plainly in any demo):
     - published heat-balance equation (a transparent stand-in is used)
     - L3 online learning from observed outcomes
     - real sensor input, signal-quality gating, firmware
     - micro-randomisation and the MRT analysis path
     - manual intake entry (every drink here is bottle-observed)
============================================================================ */

/* ---------- deterministic PRNG so runs are reproducible ------------------- */
function makeRng(seed) {
  let s = seed >>> 0;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

const T0 = 6 * 60;        // day starts 06:00
const T1 = 22 * 60;       // day ends 22:00
const STEP = 1;           // minute resolution
const N = (T1 - T0) / STEP;

const clock = (m) =>
  `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;

/* ---------- synthetic subject: activity + environment --------------------- */
function buildDay({ trainStart, trainLen, peakTemp, humidity, rng }) {
  const rows = [];
  for (let i = 0; i < N; i++) {
    const t = T0 + i * STEP;
    const inTrain = t >= trainStart && t < trainStart + trainLen;

    // MET: metabolic equivalent. Sedentary baseline with a training block.
    let met = 1.3 + 0.25 * Math.sin((t - T0) / 90) + 0.15 * rng();
    if (inTrain) {
      const frac = (t - trainStart) / trainLen;
      met = 6.5 + 2.6 * Math.sin(Math.PI * frac) + 0.5 * rng();
    } else if (t > trainStart && t < trainStart + trainLen + 40) {
      met = 2.2 + 0.4 * rng();               // post-session cooldown
    }

    // Ambient temperature: diurnal curve peaking ~14:30
    const tAmb = peakTemp - 6.5 * Math.cos(((t - 300) / 1440) * 2 * Math.PI);
    const rh = humidity + 6 * Math.cos(((t - 300) / 1440) * 2 * Math.PI);

    // Skin temperature rises with workload and ambient
    const tSkin = 32.4 + 0.34 * (met - 1) + 0.11 * (tAmb - 25);

    // Heart rate from MET, with drift under thermal load
    const hr = 58 + 11.5 * (met - 1) + 0.9 * Math.max(0, tAmb - 28) + 2.5 * rng();

    rows.push({ t, met, tAmb, rh, tSkin, hr, inTrain });
  }
  return rows;
}

/* ---------- L1: sweat rate + deficit -------------------------------------
   STAND-IN for the published whole-body heat-balance equation.
   Deliberately simple and transparent so it is obvious what it is.
   Replace with Gonzalez / JAPPL 2024 form + per-user fitted coefficients.
-------------------------------------------------------------------------- */
function sweatRateLh(row, coef) {
  const metabolic = coef.k * Math.max(0, row.met - 1);      // workload term
  const thermal = coef.a * Math.max(0, row.tAmb - 22);      // ambient drive
  const evapPenalty = 1 + coef.h * Math.max(0, row.rh - 40) / 100; // humid air evaporates poorly
  return Math.max(0.05, (coef.base + metabolic + thermal) * evapPenalty);
}

const INSENSIBLE_LH = 0.035;   // fixed literature constant; not modelled

/* ---------- L3: receptivity (heuristic placeholder) ---------------------- */
function receptivity(row, minsSincePrompt) {
  let p = 0.62;
  if (row.met > 5) p = 0.10;                    // mid-effort: cannot act
  else if (row.met > 3) p = 0.34;
  if (row.t > 20 * 60) p *= 0.7;                // late evening
  if (minsSincePrompt < 25) p *= 0.45;          // prompt fatigue
  return Math.max(0.03, Math.min(0.95, p));
}

/* ---------- simulation over one policy -----------------------------------
   Every decision point writes a row with a reason (invariant I6), which is
   what the prompt log renders. Logging is pure observation: it does not
   change what the policy decides.
-------------------------------------------------------------------------- */
function simulate(day, cfg, policy, seed) {
  const rng = makeRng(seed);                    // common random numbers across policies
  const coef = { base: cfg.base, k: cfg.k, a: cfg.a, h: cfg.h };

  let deficit = 0;                              // mL, positive = behind
  let lastPrompt = -999;
  let intakeTotal = 0;
  let prompts = 0, answered = 0, floorFires = 0;
  const trace = [];
  const events = [];
  const decisions = [];
  const hardFloorML = cfg.bodyMass * 1000 * 0.02;   // 2% body mass

  // rolling intake for the baseline lapse rule
  const recent = [];

  for (let i = 0; i < day.length; i++) {
    const row = day[i];
    const sr = sweatRateLh(row, coef);
    deficit += ((sr + INSENSIBLE_LH) * 1000) / 60;   // mL this minute

    const minsSincePrompt = row.t - lastPrompt;
    const rec = receptivity(row, minsSincePrompt);

    /* ---- decide whether to prompt ---- */
    let fire = false, viaFloor = false, reason = null;
    const decisionPoint = (row.t - T0) % 30 === 0;

    if (policy === "adaptive") {
      // L2 need: project deficit 30 min forward at current sweat rate
      const projected = deficit + ((sr + INSENSIBLE_LH) * 1000 / 60) * 30;

      if (projected >= hardFloorML && minsSincePrompt >= cfg.floorCooldown) {
        fire = true; viaFloor = true; reason = "floor";     // INVARIANT: unconditional
      } else if (decisionPoint) {
        if (projected < cfg.needThresholdML) reason = "nowindow";
        else if (minsSincePrompt < cfg.cooldown) reason = "cooldown";
        else if (rec >= cfg.recThreshold) { fire = true; reason = "delivered"; }
        else if (minsSincePrompt >= cfg.maxDefer) { fire = true; reason = "defercap"; }
        else reason = "deferred";
      }
    } else {
      // BASELINE: hourly volume-lapse, the sipIT-style rule
      const hourIntake = recent.reduce((a, b) => a + b, 0);
      if (decisionPoint && row.t - lastPrompt >= 60 && hourIntake < cfg.hourlyTargetML) {
        fire = true; reason = "delivered";
      } else if (decisionPoint) {
        reason = "nowindow";
      }
    }

    if (reason) {
      decisions.push({
        t: row.t, reason, fired: fire, rec,
        pctBM: (deficit / (cfg.bodyMass * 1000)) * 100,
      });
    }

    if (fire) {
      prompts++; lastPrompt = row.t;
      if (viaFloor) floorFires++;
      events.push({ t: row.t, kind: viaFloor ? "floor" : "prompt" });
    }

    /* ---- drinking behaviour ---- */
    // baseline hazard, grounded in free-living inter-drink intervals
    let pDrink = cfg.baseHazard;
    // prompt effect, gated by whether the person can actually act
    if (row.t - lastPrompt <= 30 && row.t >= lastPrompt) {
      pDrink += cfg.promptEffect * rec;
    }
    if (row.inTrain) pDrink *= cfg.trainingDrinkMult;

    let drankNow = 0;
    if (rng() < pDrink) {
      drankNow = cfg.sipML * (0.6 + 0.8 * rng());
      deficit -= drankNow;
      intakeTotal += drankNow;
      if (row.t - lastPrompt <= 30) answered++;
      events.push({ t: row.t, kind: "drink", ml: drankNow });
    }
    recent.push(drankNow);
    if (recent.length > 60) recent.shift();

    if (deficit < 0) deficit = 0;   // no credit for overhydration

    trace.push({
      t: row.t,
      deficit,
      pctBM: (deficit / (cfg.bodyMass * 1000)) * 100,
      sr, rec, met: row.met, tAmb: row.tAmb,
    });
  }

  // annotate delivered prompts with the 30-minute proximal outcome
  const drinks = events.filter((e) => e.kind === "drink");
  const annotated = decisions.map((d) =>
    d.fired ? { ...d, drank: drinks.some((e) => e.t >= d.t && e.t <= d.t + 30) } : d
  );

  const thresholdML = cfg.bodyMass * 1000 * 0.01;    // "in deficit" = >1% body mass
  const timeInDeficit = trace.filter((r) => r.deficit > thresholdML).length;
  const peak = Math.max(...trace.map((r) => r.deficit));

  return {
    trace, events, decisions: annotated, prompts, answered, floorFires,
    intakeTotal, timeInDeficit, peak,
    finalPctBM: trace[trace.length - 1].pctBM,
    responseRate: prompts ? answered / prompts : 0,
  };
}

/* ---------- invariant check (this is your C2 claim, as a live test) ------- */
function checkInvariant(res, cfg) {
  const floorML = cfg.bodyMass * 1000 * 0.02;
  // find minutes where projected deficit crossed the floor
  const violations = res.trace.filter((r) => {
    if (r.deficit < floorML) return false;
    // was a prompt fired within the floor cooldown? (a shorter window than the
    // cooldown itself would flag a policy that is in fact prompting as fast as it may)
    const w = cfg.floorCooldown + 5;
    const near = res.events.some((e) => e.t >= r.t - w && e.t <= r.t + w && e.kind !== "drink");
    return !near;
  });
  return violations.length;
}

/* ============================== UI =======================================
   Palette and type mirror the project deck: deep maroon on white, Georgia
   for headings, one accent (gold) reserved for warnings.
========================================================================== */

const C = {
  maroon:    "#6E1423",
  maroonDk:  "#470B15",
  maroonMid: "#8C2233",
  maroonLt:  "#B03A4B",
  rose:      "#D99AA3",
  blush:     "#F8EFF1",
  blush2:    "#F0DFE3",
  white:     "#FFFFFF",
  ink:       "#2A2224",
  grey:      "#8A7F82",
  greyLt:    "#C9C0C2",
  gold:      "#C2943A",
};

const PANEL_H = 268;

const SERIF = "Georgia, 'Times New Roman', serif";
const SANS = "system-ui, -apple-system, 'Segoe UI', Helvetica, Arial, sans-serif";

/* how each logged decision reason renders in the prompt log */
const REASON = {
  delivered: { dot: C.maroon,    text: "delivered" },
  defercap:  { dot: C.maroonMid, text: "delivered · defer cap" },
  floor:     { dot: C.maroonDk,  text: "hard floor · override" },
  deferred:  { dot: C.maroonLt,  text: "deferred · low receptivity" },
  cooldown:  { dot: C.grey,      text: "held · cooldown" },
  nowindow:  { dot: C.greyLt,    text: "no prompt · below window" },
};

function Label({ children, tone = C.maroon }) {
  return (
    <div style={{
      fontSize: 10.5, fontWeight: 700, letterSpacing: "0.09em",
      textTransform: "uppercase", color: tone, marginBottom: 9,
    }}>{children}</div>
  );
}

function Tag({ children, bg = C.maroonLt, fg = C.white }) {
  return (
    <span style={{
      background: bg, color: fg, fontSize: 8.5, fontWeight: 700,
      letterSpacing: "0.06em", textTransform: "uppercase",
      padding: "2.5px 7px", borderRadius: 999, whiteSpace: "nowrap",
    }}>{children}</span>
  );
}

/* ---------- the deficit trace -------------------------------------------- */
function DeficitTrace({ res }) {
  const W = 760, H = 300;
  const PL = 6, PR = 6, PT = 10, PB = 30;
  const x1 = W - PR, y1 = H - PB;

  const peakPct = Math.max(...res.trace.map((r) => r.pctBM));
  const maxY = Math.max(2.35, peakPct * 1.14);

  const X = (t) => PL + ((t - T0) / (T1 - T0)) * (x1 - PL);
  const Y = (p) => y1 - (p / maxY) * (y1 - PT);

  const path = res.trace
    .map((r, i) => `${i ? "L" : "M"}${X(r.t).toFixed(1)},${Y(r.pctBM).toFixed(1)}`)
    .join(" ");

  const marks = res.events.filter((e) => e.kind !== "drink");
  const at = (t) => res.trace[Math.max(0, Math.min(res.trace.length - 1, t - T0))];

  return (
    <div style={{ height: PANEL_H, display: "flex", alignItems: "center" }}>
    <svg viewBox={`0 0 ${W} ${H}`} style={{ width: "100%", height: "auto", display: "block" }}>
      <rect x={PL} y={PT} width={x1 - PL} height={y1 - PT} fill={C.blush} />

      {/* above the hard floor is the override region */}
      <rect x={PL} y={PT} width={x1 - PL} height={Math.max(0, Y(2) - PT)} fill={C.maroonDk} opacity="0.05" />

      {/* thresholds — the window label drops below its line when the two crowd */}
      <line x1={PL} x2={x1} y1={Y(2)} y2={Y(2)} stroke={C.maroonDk} strokeWidth="1.4" strokeDasharray="6 4" />
      <text x={x1 - 8} y={Y(2) - 6} textAnchor="end" fontSize="10" fontWeight="700" fill={C.maroonDk}>hard floor  2 %</text>
      <line x1={PL} x2={x1} y1={Y(0.5)} y2={Y(0.5)} stroke={C.maroonLt} strokeWidth="1.2" strokeDasharray="6 4" />
      <text x={x1 - 8} y={Y(0.5) + (Y(0.5) - Y(2) < 22 ? 14 : -6)} textAnchor="end"
        fontSize="10" fontWeight="700" fill={C.maroonLt}>window  0.5 %</text>

      {/* series */}
      <path d={path} fill="none" stroke={C.maroonDk} strokeWidth="2.4" strokeLinejoin="round" />

      {/* prompt markers */}
      {marks.map((e, i) => {
        const p = at(e.t).pctBM;
        return (
          <g key={i}>
            <line x1={X(e.t)} x2={X(e.t)} y1={Y(p) + 7} y2={y1}
              stroke={e.kind === "floor" ? C.maroonDk : C.maroon}
              strokeWidth="1" strokeDasharray="3 3" />
            <circle cx={X(e.t)} cy={Y(p)} r="6"
              fill={e.kind === "floor" ? C.maroonDk : C.white}
              stroke={e.kind === "floor" ? C.maroonDk : C.maroon} strokeWidth="2" />
          </g>
        );
      })}

      {/* legend */}
      <g>
        <rect x={PL + 10} y={PT + 8} width="132" height="22" rx="11" fill={C.white} />
        <circle cx={PL + 24} cy={PT + 19} r="4" fill={C.white} stroke={C.maroon} strokeWidth="1.6" />
        <text x={PL + 34} y={PT + 23} fontSize="9.5" fontWeight="700" fill={C.maroon}>prompt delivered</text>
      </g>

      {/* time axis — first and last labels tuck inside the plot edge */}
      {[6, 10, 14, 18, 22].map((h, i, a) => (
        <text key={h}
          x={i === 0 ? PL + 2 : i === a.length - 1 ? x1 - 2 : X(h * 60)}
          y={y1 + 19}
          textAnchor={i === 0 ? "start" : i === a.length - 1 ? "end" : "middle"}
          fontSize="9.5" fill={C.grey}>
          {clock(h * 60)}
        </text>
      ))}
    </svg>
    </div>
  );
}

/* ---------- intake log ---------------------------------------------------- */
function IntakeLog({ res }) {
  const drinks = res.events.filter((e) => e.kind === "drink");
  return (
    <div style={{ background: C.blush, padding: "4px 12px", height: PANEL_H, overflowY: "auto" }}>
      {drinks.map((e, i) => (
        <div key={i} style={{
          display: "flex", alignItems: "center", gap: 10,
          padding: "9px 0", borderBottom: `1px solid ${C.blush2}`,
        }}>
          <span style={{ fontSize: 11, fontWeight: 700, color: C.grey, width: 38 }}>{clock(e.t)}</span>
          <span style={{ fontSize: 12.5, fontWeight: 700, color: C.maroonDk, flex: 1 }}>
            {Math.round(e.ml)} mL
          </span>
          <Tag>bottle</Tag>
        </div>
      ))}
      {drinks.length === 0 && (
        <div style={{ fontSize: 11.5, color: C.grey, padding: "14px 0" }}>no intake recorded</div>
      )}
    </div>
  );
}

/* ---------- prompt log ---------------------------------------------------- */
function PromptLog({ res }) {
  return (
    <div style={{ background: C.blush, padding: "4px 12px", height: PANEL_H, overflowY: "auto" }}>
      {res.decisions.map((d, i) => {
        const r = REASON[d.reason] || REASON.nowindow;
        const suffix = d.fired ? (d.drank ? " · drank" : " · no drink") : "";
        return (
          <div key={i} style={{
            display: "flex", alignItems: "center", gap: 9,
            padding: "9px 0", borderBottom: `1px solid ${C.blush2}`,
          }}>
            <span style={{
              width: 9, height: 9, borderRadius: "50%", background: r.dot, flexShrink: 0,
            }} />
            <span style={{ fontSize: 11, fontWeight: 700, color: C.grey, width: 38 }}>{clock(d.t)}</span>
            <span style={{ fontSize: 11, fontWeight: 700, color: C.maroonDk, lineHeight: 1.3 }}>
              {r.text}{suffix}
            </span>
          </div>
        );
      })}
    </div>
  );
}

/* ---------- controls ------------------------------------------------------ */
function Slider({ label, value, set, min, max, step, unit }) {
  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", marginBottom: 5 }}>
        <span style={{ fontSize: 11.5, color: C.ink }}>{label}</span>
        <span style={{ fontSize: 11.5, fontWeight: 700, color: C.maroon }}>
          {typeof value === "number" && value % 1 !== 0 ? value.toFixed(3) : value}{unit}
        </span>
      </div>
      <input type="range" min={min} max={max} step={step} value={value}
        onChange={(e) => set(parseFloat(e.target.value))} style={{ width: "100%" }} />
    </div>
  );
}

const signed = (n) => (n > 0 ? "+" : n < 0 ? "\u2212" : "") + Math.abs(n);

function Stat({ label, value, unit, tone = C.maroonDk }) {
  return (
    <div style={{ background: C.blush, padding: "13px 15px", borderRadius: 3, flex: "1 1 150px" }}>
      <div style={{ fontFamily: SERIF, fontSize: 21, fontWeight: 700, color: tone, lineHeight: 1.1 }}>
        {value}<span style={{ fontSize: 11, fontFamily: SANS, color: C.grey, marginLeft: 3 }}>{unit}</span>
      </div>
      <div style={{
        fontSize: 9.5, fontWeight: 700, letterSpacing: "0.07em",
        textTransform: "uppercase", color: C.grey, marginTop: 5,
      }}>{label}</div>
    </div>
  );
}

/* ============================== PAGE ===================================== */
export default function HydrationSliceDemo() {
  const [bodyMass, setBodyMass] = useState(70);
  const [peakTemp, setPeakTemp] = useState(34);
  const [humidity, setHumidity] = useState(70);
  const [k, setK] = useState(0.14);
  const [promptEffect, setPromptEffect] = useState(0.05);
  const [recThreshold, setRecThreshold] = useState(0.45);
  const [seed, setSeed] = useState(7);

  const { adaptive, baseline, violations } = useMemo(() => {
    const cfg = {
      bodyMass, base: 0.10, k, a: 0.022, h: 0.5,
      needThresholdML: bodyMass * 1000 * 0.008,
      cooldown: 40, maxDefer: 90, floorCooldown: 20,
      recThreshold,
      hourlyTargetML: 240,
      baseHazard: 0.006, promptEffect, sipML: 190, trainingDrinkMult: 1.4,
    };
    const rng = makeRng(seed * 31 + 5);
    const d = buildDay({ trainStart: 17 * 60, trainLen: 75, peakTemp, humidity, rng });
    const a = simulate(d, cfg, "adaptive", seed);
    const b = simulate(d, cfg, "baseline", seed);
    return { adaptive: a, baseline: b, violations: checkInvariant(a, cfg) };
  }, [bodyMass, peakTemp, humidity, k, promptEffect, recThreshold, seed]);

  const delta = baseline.timeInDeficit - adaptive.timeInDeficit;
  const promptDelta = baseline.prompts - adaptive.prompts;

  return (
    <div style={{ background: C.white, minHeight: "100%", color: C.ink, fontFamily: SANS }}>
      <style>{`
        .dash-grid { display: grid; gap: 18px;
          grid-template-columns: minmax(0,2.45fr) minmax(0,1fr) minmax(0,1.06fr); }
        .lower-grid { display: grid; gap: 18px; grid-template-columns: 268px minmax(0,1fr); }
        @media (max-width: 980px) {
          .dash-grid, .lower-grid { grid-template-columns: 1fr; }
        }
        input[type=range] { height: 3px; background: ${C.blush2}; border-radius: 2px;
          outline: none; -webkit-appearance: none; appearance: none; }
        input[type=range]::-webkit-slider-thumb { -webkit-appearance: none; width: 13px;
          height: 13px; border-radius: 50%; background: ${C.maroon}; cursor: pointer; }
        input[type=range]::-moz-range-thumb { width: 13px; height: 13px; border: none;
          border-radius: 50%; background: ${C.maroon}; cursor: pointer; }
        input[type=range]:focus-visible { outline: 2px solid ${C.maroon}; outline-offset: 3px; }
        ::-webkit-scrollbar { width: 6px; }
        ::-webkit-scrollbar-thumb { background: ${C.blush2}; border-radius: 3px; }
      `}</style>

      <div style={{ maxWidth: 1240, margin: "0 auto", padding: "30px 24px 44px" }}>

        {/* ---- page heading ---- */}
        <div style={{
          fontSize: 10.5, fontWeight: 700, letterSpacing: "0.09em",
          textTransform: "uppercase", color: C.maroonLt, marginBottom: 7,
        }}>
          Vertical slice · synthetic subject · one day
        </div>
        <h1 style={{
          fontFamily: SERIF, fontSize: 34, fontWeight: 400, color: C.maroonDk,
          margin: "0 0 10px", letterSpacing: "-0.01em",
        }}>
          Hydration dashboard
        </h1>
        <div style={{ height: 1.5, background: C.blush2, marginBottom: 22 }} />

        {/* ---- the dashboard ---- */}
        <div style={{ border: `1px solid ${C.greyLt}`, borderRadius: 4, overflow: "hidden" }}>
          <div style={{
            background: C.maroonDk, padding: "11px 18px", display: "flex",
            justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 10,
          }}>
            <div style={{
              fontSize: 12, fontWeight: 700, color: C.white, letterSpacing: "0.05em",
              textTransform: "uppercase",
            }}>
              Hydration &nbsp;·&nbsp; synthetic subject &nbsp;·&nbsp; seed {seed}
            </div>
            <div style={{ fontSize: 11.5, fontWeight: 700, color: C.rose }}>
              deficit&nbsp; {adaptive.finalPctBM.toFixed(2)} % BM
              &nbsp;&nbsp;&nbsp;&nbsp;
              intake&nbsp; {Math.round(adaptive.intakeTotal).toLocaleString()} mL
            </div>
          </div>

          <div className="dash-grid" style={{ padding: "16px 18px 18px" }}>
            <div>
              <Label>Deficit trace</Label>
              <DeficitTrace res={adaptive} />
            </div>
            <div>
              <Label>Intake log</Label>
              <IntakeLog res={adaptive} />
            </div>
            <div>
              <Label>Prompt log</Label>
              <PromptLog res={adaptive} />
            </div>
          </div>
        </div>

        <div style={{
          background: C.maroon, color: C.white, textAlign: "center",
          padding: "13px 18px", marginTop: 10, borderRadius: 4,
          fontSize: 12.5, fontStyle: "italic",
        }}>
          Deficit trace · intake log · prompt log. A five-panel dashboard is not graded and consumes weeks.
        </div>

        {/* ---- controls and the RQ4 comparison ---- */}
        <div className="lower-grid" style={{ marginTop: 26 }}>
          <div style={{ border: `1px solid ${C.blush2}`, borderRadius: 4, padding: "16px 16px 18px" }}>
            <Label>Subject &amp; conditions</Label>
            <div style={{ display: "grid", gap: 13 }}>
              <Slider label="Body mass" value={bodyMass} set={setBodyMass} min={50} max={95} step={1} unit=" kg" />
              <Slider label="Peak ambient temp" value={peakTemp} set={setPeakTemp} min={24} max={42} step={0.5} unit=" °C" />
              <Slider label="Relative humidity" value={humidity} set={setHumidity} min={25} max={90} step={1} unit=" %" />
              <Slider label="Sweat coefficient k" value={k} set={setK} min={0.06} max={0.26} step={0.005} unit="" />
            </div>
            <div style={{ height: 1, background: C.blush2, margin: "16px 0 14px" }} />
            <Label>Policy &amp; behaviour</Label>
            <div style={{ display: "grid", gap: 13 }}>
              <Slider label="Prompt effect size" value={promptEffect} set={setPromptEffect} min={0} max={0.16} step={0.005} unit="" />
              <Slider label="Receptivity threshold θ" value={recThreshold} set={setRecThreshold} min={0} max={0.9} step={0.05} unit="" />
              <Slider label="Random seed" value={seed} set={setSeed} min={1} max={40} step={1} unit="" />
            </div>
          </div>

          <div>
            <Label>RQ4 shape — deficit-triggered vs. lapse-contingent</Label>
            <div style={{ display: "flex", gap: 10, flexWrap: "wrap" }}>
              <Stat label="Time in deficit — adaptive" value={adaptive.timeInDeficit} unit="min" tone={C.maroon} />
              <Stat label="Time in deficit — baseline" value={baseline.timeInDeficit} unit="min" tone={C.grey} />
              <Stat label="Difference" value={(delta > 0 ? "−" : "+") + Math.abs(delta)} unit="min"
                tone={delta > 0 ? C.maroon : C.gold} />
              <Stat label="Prompts saved" value={signed(promptDelta)} unit="" />
            </div>
            <div style={{ display: "flex", gap: 10, flexWrap: "wrap", marginTop: 10 }}>
              <Stat label="Peak deficit — adaptive" value={(adaptive.peak / (bodyMass * 10)).toFixed(2)} unit="% BM" />
              <Stat label="Peak deficit — baseline" value={(baseline.peak / (bodyMass * 10)).toFixed(2)} unit="% BM" />
              <Stat label="Prompts delivered" value={adaptive.prompts} unit="" />
              <Stat label="Hard floor fired" value={adaptive.floorFires} unit="×" tone={C.maroonDk} />
            </div>

            <div style={{
              marginTop: 12, padding: "13px 15px", borderRadius: 4,
              background: violations === 0 ? C.maroonDk : C.gold, color: C.white,
              display: "flex", justifyContent: "space-between", alignItems: "center",
              gap: 14, flexWrap: "wrap",
            }}>
              <span style={{ fontSize: 12 }}>
                Low receptivity may <strong>delay</strong> a need-driven prompt. It may never <strong>cancel</strong> one.
              </span>
              <span style={{ fontSize: 11.5, fontWeight: 700, whiteSpace: "nowrap" }}>
                invariant I2: {violations === 0 ? "PASS" : `FAIL (${violations})`}
              </span>
            </div>

            <div style={{ marginTop: 14, fontSize: 11.5, color: C.grey, lineHeight: 1.65 }}>
              <strong style={{ color: C.maroonDk }}>Not yet implemented.</strong>{" "}
              Sweat rate uses a transparent stand-in, not the published heat-balance equation. Receptivity is
              a fixed heuristic with no learning. No real sensor input, signal-quality gating, firmware,
              micro-randomisation, or manual intake entry. Prompt effect size is a free parameter, which is
              why it is a slider rather than a constant.
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
