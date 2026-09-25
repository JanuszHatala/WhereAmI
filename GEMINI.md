# GEMINI.md - WhereAmI Project Rules

See [AGENTS.md](AGENTS.md) and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the authoritative project guidelines, architecture priorities, testing gates, and Git branching rules.

### Core Quick Reference:
1. **Core Purpose**: Ultra-fast, reliable spatial awareness displaying the **Current Place Name** with complete **Structural Administrative Hierarchy** (`gm.`, `pow.`, `woj.`, Country) and **Canonical Road Designation** (`DK52`, `DW946`, `A4`, `S7`, etc.). Never compromise or obstruct this display.
2. **Battery Optimization & Zero-Idle-Drain**: Highest priority. Complete GPS shutdown in `IDLE` state, low-power hardware motion detection via `MotionWakeManager` (`Sensor.TYPE_SIGNIFICANT_MOTION`), strict wake lock scoping (never acquire in `onCreate` or hold in idle), and Screen-off MANUAL mode complete shutdown (Option A).
3. **Smooth Heading & Position Interpolation**: Decouple kinematics from async geocoding (`LocationFix` with `.distinctUntilChanged`), drive 60fps tracking on VSYNC via `PositionInterpolator`, and freeze bearing when speed $< 1.2\text{ m/s}$.
4. **Mandatory Unit Tests & Build Gates**: Every algorithmic rule and feature must have unit tests in `app/src/test/java/`. Build gates require `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` to pass before completion.
5. **Git Branching**: Never push directly to `master`. Create `feat/<name>` or `fix/<name>`, test locally, push only on instruction, and merge to `master` with `--no-ff`.
6. **Safeguards**: Never push to GitHub or install APK on phone without explicit user instruction.
