# Git Workflow & Repository Governance Rules

This document outlines the mandatory Git workflow, author identity verification, branch protection, and pull request protocol for the **WhereAmI** repository (`JanuszHatala/WhereAmI`).

---

## 1. Zero Direct Commits to `master` / `main`
- Direct commits or direct pushes to `master` (or `main`) are strictly forbidden.
- All code changes, refactors, new features, bug fixes, chore tasks, and experiments MUST be developed on dedicated, semantic branches:
  - `feat/<descriptive-name>`: For new features or capability enhancements.
  - `fix/<descriptive-name>`: For bug fixes, kinematic corrections, and telemetry patches.
  - `chore/<descriptive-name>`: For dependency upgrades, CI workflows, and documentation maintenance.

---

## 2. Mandatory Identity Enforcement
- All commits in this repository MUST strictly and exclusively be authored and committed by:
  - **Name**: `Janusz Hatala`
  - **Email**: `janusz.hatala@gmail.com`
- **Zero Company Account Leakage**: Under NO circumstances may the company account (`januszhatala-tb`) or company email (`janusz.hatala@timebook.net`) appear in commit author or committer history, pull requests, or repository participants.
- Local repository configuration is permanently pinned via:
  ```bash
  git config user.name "Janusz Hatala"
  git config user.email "janusz.hatala@gmail.com"
  ```
- Before pushing any branch, verify that the top commit matches this identity:
  ```bash
  git log -1 --format="%an <%ae> | %cn <%ce>"
  ```

---

## 3. Local Verification Prior to Push
- No code may be pushed to GitHub without passing the pre-completion quality gates locally:
  ```bash
  ./gradlew testDebugUnitTest assembleDebug
  ```
- Both commands must finish with `BUILD SUCCESSFUL` (0 test failures, 0 compilation errors).
- Monotonically increment `versionCode` by 1 and increment `versionName` in `app/build.gradle.kts` for every change session before building and opening a PR.

---

## 4. Pull Request & CI Protocol
1. **Branch Creation**:
   ```bash
   git checkout master
   git pull origin master
   git checkout -b <branch-type>/<branch-name>
   ```
2. **Push & PR Creation**:
   Push feature branch to origin and open a PR targeting `master`:
   ```bash
   git push -u origin <branch-name>
   gh pr create --fill
   ```
3. **CI Status Gate**:
   - Ensure the automated GitHub Actions CI workflow (`.github/workflows/ci.yml`) passes all unit tests and debug APK compilation checks.
4. **Merge Protocol**:
   - Merge the PR cleanly via GitHub CLI or PR interface:
   ```bash
   gh pr merge <pr-number> --merge --delete-branch
   ```
   - Update local master:
   ```bash
   git checkout master
   git pull origin master
   ```

---

## 5. Branch Protection Requirements
- The default branch (`master`) has branch protection active:
  - Required passing status check from CI (`Unit Tests & Build Verification`).
  - Force pushes blocked (`allow_force_pushes: false`).
  - Branch deletions blocked (`allow_deletions: false`).
  - Strict branch up-to-date checks enabled before merging.
