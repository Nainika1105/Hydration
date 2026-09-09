# Protocol artifacts — week 1

Arch §9 and §13: three of the seven open questions land in the week-1 ethics submission,
and PD §7.6 rates ethics delay as the one risk with fatal impact.

Needed before parts are ordered:

- [ ] **Published dataset schema** (Q7). The data-sharing clause must name what will be
      released. Retrofitting consent is impossible (PD §8.3). Draft from Arch §9; the
      Kotlin contracts in `core/contracts/` are the current source of truth.
- [ ] **Android-only screening criterion** (Q5). Recruitment materials and inclusion criteria.
- [ ] **Hard-floor suspension under unobserved intake** (Q4). Needs participant-safety
      sign-off; see `IneligibleReason.blocksOverride` and Arch §10.
- [ ] Seed-commitment procedure: draw, seal, publish `SHA-256(seed ‖ subjectId)` before each
      subject's day 1 (Arch §8). `SealedSeed.commitment()` implements it.
- [ ] Study config schema, signed, one per subject (Arch §11).

Later, before deployment:

- [ ] Pre-register RQ3 and RQ4 (PD §7.4), including the shadow-mode decision (Q1), the
      receptivity model structure (Q6), and the intake-timing rule (Q3).
