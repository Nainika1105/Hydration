# Hydration

Physiologically-triggered, receptivity-aware hydration prompting.

Software for the project described in `Hydration_Project_Document-20260904154228.pdf` (v1.0,
Sept 2026). Architecture: [Hydration Prompting Architecture](https://claude.ai/code/artifact/2d4b9f5f-4546-4ede-8a8b-28d301c9fd7a).

References of the form **PD §n** point to the project document; **Arch §n** to the architecture.

## What exists

| Module | State | Contents |
|---|---|---|
| `core/` | **built, 35 tests passing** | L1–L4, the randomizer, the invariants. Pure Kotlin/JVM: no Android, no I/O, no clock. |
| `replay/` | **built, runnable** | The replay CLI. Runs the stack against a synthetic subject until real logs exist. |
| `firmware/` | empty | Wearable (ESP32-WROOM) and bottle base (ESP32-C3). Weeks 2–4. |
| `app/` | empty | Android, Kotlin, foreground service. After the week-11 gate. |
| `analysis/` | empty | Python: coefficient fitting, GEE, Bland–Altman, off-policy evaluation. Weeks 7–10. |
| `protocol/` | empty | Config schema, pre-registration, consent, seed commitments. **Week 1.** |

Build order follows Arch §12: nothing MRT-specific is built before the week-11 kill gate.

## Run it

```bash
./gradlew test
```

```bash
./gradlew :replay:run --args="--days 14 --trace-day 1"
```

Options: `--variant A|B|C|D` as `CONTEXT_ONLY|PHYSIOLOGY_ONLY|FULL|FIXED_RATE`,
`--live-receptivity` (turns off shadow mode), `--sweep` (window-threshold sensitivity),
`--no-trace`, `--days N`, `--salt N`.

The build pins JDK 21 in `gradle.properties`; the machine also has JDK 26, which Gradle 8.14
cannot run on.

## The four layers

All in `core/`, all pure. One implementation serves both the app and the replay CLI, so a
deployed decision and a replayed decision are the same computation (Arch §7).

- **L1 `deficit/`** — `deficit = ∫(sweat + insensible) − ∫intake`. `HeatBalance.kt` is real
  partitional calorimetry (required evaporative heat loss, Lewis relation, DuBois area, Buck
  vapour pressure), not a stand-in. Per-user scale and intercept sit on top, which is where
  PD §4.3's contribution lives. Four ablation variants share one code path and differ only
  in an input mask.
- **L2 `need/`** — forward projection to a need score and a window flag.
- **L3 `receptivity/`** — logistic scoring from a versioned model card. **Fitting happens in
  Python and never here.**
- **L4 `policy/`** — the ordered decision function. Plus `LapseContingentPolicy`, the
  baseline RQ4 compares against.

### Not yet real

`HeatBalance` uses standard textbook coefficients. The published piecewise coefficients of
Gonzalez et al. [2] and the JAPPL 2024 equations [3][4] must be pulled from the papers and
checked against it — PD §12.2 covers exactly this. Heat storage `S` is not modelled, so the
model over-predicts at exercise onset.

The synthetic subject in `core/src/testFixtures/` exercises the stack. It validates nothing.
Only the scale method against real subjects can do that (PD §7.2, weeks 8–11).

## The invariants

C2's claim is structural, so the structure has to be checkable. Six properties, each with a
test that fails the build:

| | Invariant | Test |
|---|---|---|
| I1 | L2 never sees behaviour | `NeedContextIsolationTest` — field allowlist **and** a source scan for the import edge |
| I2 | The hard floor is unconditional | `HardFloorInvariantTest` — 50,000 random states, receptivity pinned at zero |
| I3 | Receptivity's deferral is bounded | `PolicyStructureTest` |
| I4 | Learned parameters can't reach policy constants | `PolicyStructureTest` — 14 simulated refits |
| I5 | Override prompts are not trial data | `PolicyStructureTest` |
| I6 | Every slot writes a row, with a reason | `PolicyStructureTest` |

`IneligibleReason.blocksOverride` is the load-bearing flag: it is true **only** for reasons
meaning the deficit estimate itself cannot be trusted (`DEVICE_DOWN`, `LOW_COVERAGE`,
`INTAKE_UNOBSERVED`, `PHONE_DOWN`). Burden, timing and protocol convenience — cooldown,
budget, quiet hours, training hours — never suppress the override (Arch §10).

## Decisions taken while building

- **Shadow mode (Arch §5, Q1).** During the MRT, L3 scores every decision point and the score
  is logged, but it does not gate delivery. Otherwise treatment is confounded with the
  receptivity score and RQ5 has no unconfounded support. `shadowReceptivity = false` makes it
  live. **Needs protocol sign-off before week 11.**
- **`MrtMode.RANDOMIZED` vs `DEPLOYED`.** The trial arm withholds roughly half of all needed
  prompts by design. Comparing that against an always-on baseline measures the randomization,
  not the policy, so RQ4 simulations run `DEPLOYED`.
- **Deferral is bounded, windows are not.** I3 counts minutes *deferred by receptivity*, not
  minutes a window has been open. A window held at control by the randomizer is the trial
  working; escalating it would destroy the control condition.
- **Surplus is excreted, not banked (new, unnumbered).** PD §6.2 writes the balance as
  `∫loss − ∫intake` with no renal term, so as specified the integral runs arbitrarily negative
  and a subject who over-drank at lunch carries that credit into evening training and never
  opens a window. `surplusFloorPctBm` floors it (default 0) and tracks the excreted volume so
  the daily balance still closes. Flooring at zero discards the genuine short buffer a
  pre-session drink provides; set a small negative value to allow one. **Same family as Q2 —
  both are about what the integral does outside the deficit regime.**
- **Overnight re-anchoring (Q2).** The integral resets at `wakeAnchorMinute` and overnight
  accrual is reported separately. PD §6.2 does not specify this. **Physiology call.**
- **`windowOpenPctBm = 0.5`.** PD §6.2 fixes the 2% hard floor but leaves this open. It is
  the parameter with the most leverage over the trial — see below.

## Open finding: eligible-point yield

PD §7.3 builds its power calculation on ~14 eligible decision points per subject-day. Against
a synthetic subject in a plausible water balance (≈2.9 L/day loss, ≈2.5 L/day intake), the
stack yields **≈4/day**, and `--sweep` shows this barely moves with the window threshold:

```
window%BM   eligible/day
      0.2            4.2
      0.5            3.7
      1.0            1.7
```

Yield is capped by the prompt → drink → window-closes → re-accrue cycle, not by slot
arithmetic. This is a synthetic subject, so the number is not a prediction — but the
mechanism is real, and PD §7.3 says adequacy is not to be asserted. Worth checking against
the calibration-session data in weeks 8–10, before the MRT is committed to.

## License / data

`data/` is gitignored. No participant data belongs in this repository.
