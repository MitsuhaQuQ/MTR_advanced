# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

MTR Advanced: a fork of Minecraft Transit Railway 3.2.2 (Fabric + Forge via Architectury, merged with ANTE/mtrsteamloco) that turns it into a network-operations simulator: deterministic pathfinding, timetables, vehicle workings (运用/duties), single-track reservations, station-throat locks and a browser-based editor. It shows in the mod list as `MTR-MTM_Enhanced 3.2.2` plus a second entry `MTM_Logic` whose version is `mtm_version` in `gradle.properties`. Only Minecraft **1.20.1** is built for now; the other versions are not compiled until the user says otherwise.

The user writes in Chinese; reply in Chinese. Keep the session's language constant: every reply, plan, question, recap and insight stays in Chinese for the whole session, including after a context summary, over Remote Control, and when tool output or the files being read are in English. Code, identifiers, commit messages and code comments stay in English as they are now. The design docs (`MTR-*.md` at the repo root) are in Chinese.

## Build and test

Canonical Gradle (JDK 17):

```bash
./gradlew build :common:test -PbuildVersion=1.20.1      # mod jars + common tests
./gradlew -p transit-sim test --offline                 # component on its own
./gradlew -p transit-sim test --tests 'transitsim.sim.ThroatLocksTest'
```

On this machine use the warm-daemon scripts instead (they mirror the repo into `C:/gtmp/par/1.20.1` and build there):

- `bash C:/gtmp/fast/round.sh fabric/src/main/java/me` — full round: build, both modules' tests, add-on ABI scan, starts both test servers, copies jars to `P:/mtr-ante/test-jars/merged` and the servers' mods. The argument puts that path back as at HEAD (it belongs to another session; never edit it).
- `bash C:/gtmp/fast/build.sh …` — build and tests only.
- `bash C:/gtmp/fast/simtest.sh 'transitsim.*'` — run matching transit-sim tests only.
- Day simulator over a world's database: `./gradlew -p transit-sim simulateDay --args="path/to/mtr.db [yyyy-mm-dd] [tz] [day start hour] [--events]"`, with `-Pmtr.throatLocks=false`, `-Pmtr.simHours=N`, `-Pmtr.simRoutes=id,id|scopes`, `-Pmtr.noLinePools=true` to compare (`C:/gtmp/fast/simday.sh` wraps it).

Never kill all java processes; stop a specific PID only (test servers and the Gradle daemon share the machine).

## Architecture

- `transit-sim/` — the **simulation component**, plain Java 8, no Minecraft/netty dependency (enforced by its own `build.gradle`; it also builds standalone). All *new running rules* live here and are tested here first: `network` (`RailGraph`, `RailRouter`, `LockNetworks`, throat routes), `motion` (`TrainMotion`, legacy rules for comparison), `plan`/`planning` (timetables, duties, `DutyPlanner`, `PlanCheck`), `sim` (`TickRunner` headless runner, `RunReservations`/`Arbiter`, `ThroatLocks`, `SimDataset`, `SimScope`), `io` (binary formats). `src/tools/java` holds `SimulateDay` (separate source set, not shipped).
- `common/src/main/java/mtr/` — MTR core plus this fork's changes. `data/` is the server core (`RailwayData` per dimension, `Depot`, `Siding`, `Route`, `TrainServer`, `DataCache` indexes). `advanced/` holds adapters into the component (`LiveSimulation`, `SandboxRuns`, `EditorSimulation`, …). `client/editor/` is the browser editor: `EditorServer` serves `assets/mtr/editor/editor.js` on localhost; edits still go through game packets to the server.
- `common/src/main/java/cn/zbx1425/mtrsteamloco/` — merged ANTE (models, JS scripting, mixins). Unexplained behaviour: check mixins first.
- `fabric/`, `forge/` — loader entry points. `resources/common|fabric|forge` are copied into the jars by the build.

Two hosts, one rule set: the game's `TrainServer`/`Siding`/`Depot` and the component's `TickRunner` implement the same rules (e.g. both call `ThroatLocks`, both build `LockNetworks`), and tests compare them. Change the component and its test first, then the game adapter, then the editor.

Cross-cutting invariants:
- Data classes exist on both server and client and are written three ways: msgpack save, `FriendlyByteBuf` packet, and `update(key, packet)`. Change all three together; call `DataCache.sync()` after structural changes.
- Saves may only gain content; existing fields keep their meaning. Raise `mtm_version` in `gradle.properties` on any packet or save change (the client/server version check compares it).
- Component guarantees that tests rely on: skipping ticks gives the same result as not skipping, and resuming from a save equals running straight through.
- Multi-version code uses Manifold preprocessor directives (`#if MC_VERSION >= "12000"`); prefer the wrappers in `mtr/mappings/`.

Resource gotchas (the build pre-step overwrites parts of `common/src/main/resources`):
- New game text goes in `common/src/main/lang/{en_us,zh_CN,zh_TW,zh_HK}.json` — not `assets/mtr/lang`, which Crowdin overwrites. No `"` inside values.
- Web editor strings go in `assets/mtr/editor/i18n.js` (Chinese key → en_us).
- New textures, sounds, recipes, loot tables go under `resources/common/normal`, or they are deleted from the jar.

## Working rules

- Commit or push only when the user says. Run git as `git -c safe.directory='*' …`. Branch `ante-merge` is pushed as both itself and `main`: `git -c safe.directory='*' push origin ante-merge ante-merge:main`.
- Before each larger step: write a plan and get confirmation, tag a breakpoint (`git tag pre-<name>`) and keep baseline jars in `P:/mtr-ante/test-jars/baseline-<name>`.
- Every code change also updates, in the same commit: `MTR-代码说明与流程.md` (where code lives and call order), `MTR-模拟组件与解耦.md` (component split; code comments like L2, F1, R03 refer to it and to `MTR-设计与架构审查评估-2026-11-03.md`), `MTR-测试项目清单.md` (in-game test items U1…), and `README.md`.
- Don't touch the untracked security docs (`MTR-安全审计报告-3.2.2.md`, `MTR-安全条件组合分析.md`) or `fabric/src/main/java/me`.
- Server-side switches live in `config/mtr_security.json` (`SecurityConfig`): e.g. `throatLocks`, `editorSimulation` (debug-only web simulation, default off).
