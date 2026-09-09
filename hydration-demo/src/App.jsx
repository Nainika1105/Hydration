import React, { useState, useMemo } from "react";

/* ============================================================================
   VERTICAL SLICE — Physiologically-Triggered, Receptivity-Aware Hydration Prompting
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

const clock = (m) => `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;

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

/* ---------- simulation over one policy ----------------------------------- */
function simulate(day, cfg, policy, seed) {
  const rng = makeRng(seed);                    // common random numbers across policies
  const coef = { base: cfg.base, k: cfg.k, a: cfg.a, h: cfg.h };

  let deficit = 0;                              // mL, positive = behind
  let lastPrompt = -999;
  let lastDrink = T0;
  let intakeTotal = 0;
  let prompts = 0, answered = 0, floorFires = 0;
  const trace = [];
  const events = [];
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
    let fire = false, viaFloor = false;
    const decisionPoint = (row.t - T0) % 30 === 0;

    if (policy === "adaptive") {
      // L2 need: project deficit 30 min forward at current sweat rate
      const projected = deficit + ((sr + INSENSIBLE_LH) * 1000 / 60) * 30;

      if (projected >= hardFloorML && minsSincePrompt >= cfg.floorCooldown) {
        fire = true; viaFloor = true;                      // INVARIANT: unconditional
      } else if (decisionPoint && projected >= cfg.needThresholdML && minsSincePrompt >= cfg.cooldown) {
        if (rec >= cfg.recThreshold) fire = true;
        else if (minsSincePrompt >= cfg.maxDefer) fire = true;   // deferred too long
      }
    } else {
      // BASELINE: hourly volume-lapse, the sipIT-style rule
      const hourIntake = recent.reduce((a, b) => a + b, 0);
      if (decisionPoint && row.t - lastPrompt >= 60 && hourIntake < cfg.hourlyTargetML) {
        fire = true;
      }
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
      lastDrink = row.t;
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

  const thresholdML = cfg.bodyMass * 1000 * 0.01;    // "in deficit" = >1% body mass
  const timeInDeficit = trace.filter((r) => r.deficit > thresholdML).length;
  const peak = Math.max(...trace.map((r) => r.deficit));

  return {
    trace, events, prompts, answered, floorFires,
    intakeTotal, timeInDeficit, peak,
    responseRate: prompts ? answered / prompts : 0,
  };
}

/* ---------- invariant check (this is your C2 claim, as a live test) ------- */
function checkInvariant(res, cfg) {
  const floorML = cfg.bodyMass * 1000 * 0.02;
  // find minutes where projected deficit crossed the floor
  const violations = res.trace.filter((r, i) => {
    if (r.deficit < floorML) return false;
    // was a prompt fired within the following 15 min?
    const near = res.events.some((e) => e.t >= r.t && e.t <= r.t + 15 && e.kind !== "drink");
    return !near;
  });
  return violations.length;
}

/* ============================== UI ======================================= */

const C = {
  ground: "#EDF0F2",
  panel: "#FFFFFF",
  ink: "#16222E",
  muted: "#65757F",
  hair: "#D5DCE1",
  deficit: "#2C6E8F",
  floor: "#A83A2C",
  adaptive: "#2E7D5B",
  baseline: "#B8892B",
  drink: "#7FA8BF",
};

function Slider({ label, value, set, min, max, step, unit }) {
  return (
    <div style={{ marginBottom: 14 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", marginBottom: 4 }}>
        <span style={{ fontSize: 12.5, color: C.ink }}>{label}</span>
        <span style={{ fontFamily: "'IBM Plex Mono', monospace", fontSize: 12, color: C.muted }}>
          {typeof value === "number" && value % 1 !== 0 ? value.toFixed(3) : value}{unit}
        </span>
      </div>
      <input
        type="range" min={min} max={max} step={step} value={value}
        onChange={(e) => set(parseFloat(e.target.value))}
        style={{ width: "100%", accentColor: C.deficit }}
      />
    </div>
  );
}

function Metric({ label, value, unit, tone }) {
  return (
    <div style={{ flex: 1, minWidth: 96 }}>
      <div style={{ fontSize: 11.5, color: C.muted, marginBottom: 3 }}>{label}</div>
      <div style={{ fontFamily: "'IBM Plex Mono', monospace", fontSize: 22, color: tone || C.ink, lineHeight: 1.1 }}>
        {value}<span style={{ fontSize: 12, color: C.muted, marginLeft: 2 }}>{unit}</span>
      </div>
    </div>
  );
}

/* deficit trace with prompt / drink markers */
function Trace({ res, cfg, color, title, subtitle }) {
  const W = 760, H = 190, PADL = 46, PADB = 26, PADT = 14, PADR = 10;
  const maxY = Math.max(cfg.bodyMass * 1000 * 0.025, res.peak * 1.1);
  const x = (t) => PADL + ((t - T0) / (T1 - T0)) * (W - PADL - PADR);
  const y = (v) => PADT + (1 - v / maxY) * (H - PADT - PADB);

  const path = res.trace.map((r, i) => `${i ? "L" : "M"}${x(r.t).toFixed(1)},${y(r.deficit).toFixed(1)}`).join(" ");
  const area = `${path} L${x(T1)},${y(0)} L${x(T0)},${y(0)} Z`;
  const floorY = y(cfg.bodyMass * 1000 * 0.02);
  const warnY = y(cfg.bodyMass * 1000 * 0.01);

  return (
    <div style={{ background: C.panel, border: `1px solid ${C.hair}`, padding: "14px 16px 8px", marginBottom: 14 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", marginBottom: 6 }}>
        <div>
          <span style={{ fontSize: 14.5, fontWeight: 600, color: color }}>{title}</span>
          <span style={{ fontSize: 12, color: C.muted, marginLeft: 10 }}>{subtitle}</span>
        </div>
        <div style={{ fontFamily: "'IBM Plex Mono', monospace", fontSize: 11.5, color: C.muted }}>
          {res.prompts} prompts · {(res.responseRate * 100).toFixed(0)}% answered
        </div>
      </div>

      <svg viewBox={`0 0 ${W} ${H}`} style={{ width: "100%", height: "auto", display: "block" }}>
        {/* threshold bands */}
        <rect x={PADL} y={PADT} width={W - PADL - PADR} height={Math.max(0, floorY - PADT)} fill={C.floor} opacity={0.055} />
        <line x1={PADL} x2={W - PADR} y1={floorY} y2={floorY} stroke={C.floor} strokeWidth="1" strokeDasharray="4 3" />
        <line x1={PADL} x2={W - PADR} y1={warnY} y2={warnY} stroke={C.muted} strokeWidth="0.7" strokeDasharray="2 4" />
        <text x={W - PADR} y={floorY - 4} textAnchor="end" fontSize="9.5" fill={C.floor} fontFamily="'IBM Plex Mono', monospace">2% BM — hard floor</text>
        <text x={W - PADR} y={warnY - 4} textAnchor="end" fontSize="9.5" fill={C.muted} fontFamily="'IBM Plex Mono', monospace">1% BM</text>

        {/* deficit */}
        <path d={area} fill={color} opacity={0.13} />
        <path d={path} fill="none" stroke={color} strokeWidth="1.7" />

        {/* events */}
        {res.events.map((e, i) =>
          e.kind === "drink" ? (
            <circle key={i} cx={x(e.t)} cy={H - PADB + 6} r="2" fill={C.drink} />
          ) : (
            <line key={i} x1={x(e.t)} x2={x(e.t)} y1={PADT} y2={H - PADB}
              stroke={e.kind === "floor" ? C.floor : color}
              strokeWidth={e.kind === "floor" ? 1.6 : 0.9}
              opacity={e.kind === "floor" ? 0.85 : 0.42} />
          )
        )}

        {/* axes */}
        <line x1={PADL} x2={W - PADR} y1={H - PADB} y2={H - PADB} stroke={C.hair} />
        {[6, 9, 12, 15, 18, 21].map((h) => (
          <g key={h}>
            <line x1={x(h * 60)} x2={x(h * 60)} y1={H - PADB} y2={H - PADB + 3} stroke={C.hair} />
            <text x={x(h * 60)} y={H - PADB + 15} textAnchor="middle" fontSize="10" fill={C.muted} fontFamily="'IBM Plex Mono', monospace">{h}:00</text>
          </g>
        ))}
        {[0, 0.5, 1].map((f) => (
          <text key={f} x={PADL - 6} y={y(maxY * f) + 3} textAnchor="end" fontSize="10" fill={C.muted} fontFamily="'IBM Plex Mono', monospace">
            {Math.round(maxY * f)}
          </text>
        ))}
        <text x={PADL - 6} y={PADT - 3} textAnchor="end" fontSize="9" fill={C.muted}>mL</text>
      </svg>
    </div>
  );
}

export default function HydrationSliceDemo() {
  const [bodyMass, setBodyMass] = useState(70);
  const [peakTemp, setPeakTemp] = useState(34);
  const [humidity, setHumidity] = useState(70);
  const [k, setK] = useState(0.14);
  const [promptEffect, setPromptEffect] = useState(0.05);
  const [recThreshold, setRecThreshold] = useState(0.45);
  const [seed, setSeed] = useState(7);

  const cfg = {
    bodyMass, base: 0.10, k, a: 0.022, h: 0.5,
    needThresholdML: bodyMass * 1000 * 0.008,
    cooldown: 40, maxDefer: 90, floorCooldown: 20,
    recThreshold,
    hourlyTargetML: 240,
    baseHazard: 0.006, promptEffect, sipML: 190, trainingDrinkMult: 1.4,
  };

  const { adaptive, baseline, day, violations } = useMemo(() => {
    const rng = makeRng(seed * 31 + 5);
    const d = buildDay({ trainStart: 17 * 60, trainLen: 75, peakTemp, humidity, rng });
    const a = simulate(d, cfg, "adaptive", seed);
    const b = simulate(d, cfg, "baseline", seed);
    return { adaptive: a, baseline: b, day: d, violations: checkInvariant(a, cfg) };
  }, [bodyMass, peakTemp, humidity, k, promptEffect, recThreshold, seed]);

  const delta = baseline.timeInDeficit - adaptive.timeInDeficit;
  const promptDelta = baseline.prompts - adaptive.prompts;

  return (
    <div style={{ background: C.ground, minHeight: "100%", padding: "22px 20px 30px", color: C.ink,
                  fontFamily: "'IBM Plex Sans', system-ui, sans-serif" }}>
      <style>{`@import url('https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500&family=IBM+Plex+Sans:wght@400;500;600&display=swap');
        input[type=range]{height:3px;background:${C.hair};border-radius:2px;outline:none;-webkit-appearance:none}
        input[type=range]::-webkit-slider-thumb{-webkit-appearance:none;width:13px;height:13px;border-radius:50%;background:${C.deficit};cursor:pointer}
        input[type=range]:focus-visible{outline:2px solid ${C.deficit};outline-offset:3px}`}</style>

      <div style={{ maxWidth: 1180, margin: "0 auto" }}>
        <div style={{ marginBottom: 4, fontSize: 21, fontWeight: 600, letterSpacing: "-0.01em" }}>
          Deficit-triggered vs. volume-lapse prompting
        </div>
        <div style={{ fontSize: 13.5, color: C.muted, marginBottom: 20, maxWidth: 720, lineHeight: 1.5 }}>
          One simulated day, one subject, identical drinking behaviour under both policies (common random numbers).
          The only difference is what decides when to prompt.
        </div>

        <div style={{ display: "flex", gap: 18, alignItems: "flex-start", flexWrap: "wrap" }}>
          {/* controls */}
          <div style={{ width: 250, background: C.panel, border: `1px solid ${C.hair}`, padding: "16px 16px 8px" }}>
            <div style={{ fontSize: 12.5, fontWeight: 600, marginBottom: 12 }}>Subject &amp; conditions</div>
            <Slider label="Body mass" value={bodyMass} set={setBodyMass} min={50} max={95} step={1} unit=" kg" />
            <Slider label="Peak ambient temp" value={peakTemp} set={setPeakTemp} min={24} max={42} step={0.5} unit=" °C" />
            <Slider label="Relative humidity" value={humidity} set={setHumidity} min={25} max={90} step={1} unit=" %" />
            <Slider label="Sweat coefficient k" value={k} set={setK} min={0.06} max={0.26} step={0.005} unit="" />

            <div style={{ borderTop: `1px solid ${C.hair}`, margin: "14px 0 13px" }} />
            <div style={{ fontSize: 12.5, fontWeight: 600, marginBottom: 12 }}>Policy &amp; behaviour</div>
            <Slider label="Prompt effect size" value={promptEffect} set={setPromptEffect} min={0} max={0.16} step={0.005} unit="" />
            <Slider label="Receptivity threshold θ" value={recThreshold} set={setRecThreshold} min={0} max={0.9} step={0.05} unit="" />
            <Slider label="Random seed" value={seed} set={setSeed} min={1} max={40} step={1} unit="" />
          </div>

          {/* traces */}
          <div style={{ flex: 1, minWidth: 480 }}>
            <Trace res={adaptive} cfg={cfg} color={C.adaptive}
              title="Adaptive" subtitle="need gate + receptivity timing + hard floor" />
            <Trace res={baseline} cfg={cfg} color={C.baseline}
              title="Baseline" subtitle="hourly volume-lapse (sipIT-style rule)" />

            {/* comparison */}
            <div style={{ background: C.panel, border: `1px solid ${C.hair}`, padding: "16px 18px" }}>
              <div style={{ display: "flex", gap: 20, flexWrap: "wrap", marginBottom: 16 }}>
                <Metric label="Time in deficit — adaptive" value={adaptive.timeInDeficit} unit=" min" tone={C.adaptive} />
                <Metric label="Time in deficit — baseline" value={baseline.timeInDeficit} unit=" min" tone={C.baseline} />
                <Metric label="Difference" value={(delta > 0 ? "−" : "+") + Math.abs(delta)} unit=" min"
                        tone={delta > 0 ? C.adaptive : C.floor} />
                <Metric label="Prompts saved" value={(promptDelta >= 0 ? "" : "+") + promptDelta} unit="" />
                <Metric label="Peak deficit — adaptive" value={(adaptive.peak / (bodyMass * 10)).toFixed(2)} unit=" % BM" />
                <Metric label="Peak deficit — baseline" value={(baseline.peak / (bodyMass * 10)).toFixed(2)} unit=" % BM" />
              </div>

              <div style={{ borderTop: `1px solid ${C.hair}`, paddingTop: 13, display: "flex",
                            justifyContent: "space-between", alignItems: "center", gap: 16, flexWrap: "wrap" }}>
                <div style={{ fontSize: 12.5, color: C.muted, maxWidth: 560, lineHeight: 1.5 }}>
                  Set prompt effect to 0 and the two policies converge — the difference is entirely in <em>when</em> a
                  prompt lands, not in the drinking model. Raise humidity to widen the gap.
                </div>
                <div style={{ fontFamily: "'IBM Plex Mono', monospace", fontSize: 11.5,
                              color: violations === 0 ? C.adaptive : C.floor, whiteSpace: "nowrap" }}>
                  invariant: {violations === 0 ? "PASS" : `FAIL (${violations})`}
                  <span style={{ color: C.muted }}> · floor fired {adaptive.floorFires}×</span>
                </div>
              </div>
            </div>

            <div style={{ marginTop: 14, fontSize: 11.5, color: C.muted, lineHeight: 1.6 }}>
              <strong style={{ color: C.ink, fontWeight: 600 }}>Not yet implemented.</strong>{" "}
              Sweat rate uses a transparent stand-in, not the published heat-balance equation. Receptivity is a
              fixed heuristic with no learning. No real sensor input, signal-quality gating, firmware, or
              micro-randomisation. Prompt effect size is a free parameter, which is why it is a slider rather
              than a constant.
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
