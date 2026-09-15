# GEMINI.md - WhereAmI Project Rules

See [AGENTS.md](file:///c:/DevWorkspaces/jh/ProjektyIT/Android/WhereIAm/AGENTS.md) for the authoritative project guidelines, architecture priorities, testing gates, and Git branching rules.

### Core Quick Reference:
1. **Core Purpose**: Ultra-fast, reliable spatial awareness displaying the **Current Place Name** with complete **Structural Administrative Hierarchy** (`gm.`, `pow.`, `woj.`, Country) and **Canonical Road Designation** (`DK52`, `DW946`, `A4`, `S7`, etc.). Never compromise or obstruct this display.
2. **Battery Optimization**: Highest priority. Adaptive GPS sampling (10–12s live on battery, 6s charging), stationary jitter gating ($< 0.35\text{ m/s}$), and persistent multi-tier disk caching.
3. **Mandatory Unit Tests**: Every feature and algorithmic rule must have unit tests in `app/src/test/java/`. Build gates require `.\gradlew.bat testDebugUnitTest` and `.\gradlew.bat assembleDebug` to pass before completion.
4. **Git Branching**: From now on, never push directly to `master`. Create `feat/<name>` or `fix/<name>`, push the branch to GitHub, and merge to `master` only after verification.
5. **Safeguards**: Never push to GitHub or install APK on phone without explicit user instruction.
