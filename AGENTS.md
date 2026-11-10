# Repository Guidelines

## Project Structure & Module Organization

MTR Advanced is an Architectury fork built for Minecraft 1.20.1. `common/` contains shared code and tests; `fabric/` and `forge/` contain loader entry points. Put new operating rules and tests in the Java 8, Minecraft/Netty-independent `transit-sim/` first, then connect them through `common/src/main/java/mtr/advanced/`. `editor-web/` is the React/TypeScript editor; its committed build is bundled under `common/src/main/resources/assets/mtr/editor/next/`. Root `MTR-*.md` files record decisions and verification.

## Build, Test, and Development Commands

- `./gradlew build :common:test -PbuildVersion=1.20.1` builds both loaders and runs common tests (JDK 17).
- `./gradlew -p transit-sim test --offline` tests the simulator with cached dependencies.
- `./gradlew -p transit-sim test --tests 'transitsim.sim.ThroatLocksTest'` runs one JUnit class.
- `cd editor-web && npm ci && npm run build` generates bindings, checks types/tests, and builds the editor.
- `cd editor-web && npm run dev` starts Vite locally.

Gradle initially needs network access. It regenerates mappings, `Keys.java`, and resource trees; never hand-edit generated output. Before committing, run Gradle without `-PbuildVersion` to restore tracked mappings to their default 1.16.5 form. Artifacts go to `build/release/`.

## Coding Style & Naming Conventions

Match neighboring code: tabs and same-line braces in Java/Groovy; two spaces and no semicolons in TypeScript. Use `PascalCase` for classes/components and `camelCase` for methods/variables. TypeScript is strict. Prefer compatibility wrappers in `mtr/mappings/` and existing Manifold guards. Keep code, identifiers, comments, and commits in English; design documentation is Chinese.

## Resources, Data, and Compatibility

Put game translations in `common/src/main/lang/{en_us,zh_CN,zh_TW,zh_HK}.json`, editor translations in its i18n sources, and new textures, sounds, recipes, or loot tables under `resources/common/normal/`. Build preparation overwrites other locations. For shared-data changes, update msgpack persistence, `FriendlyByteBuf` serialization, and `update(key, packet)` together; call `DataCache.sync()`. Preserve existing field meanings and raise `mtm_version` in `gradle.properties` for packet or save changes. Check mixins and add-on ABI impact before changing internal signatures.

## Testing and Documentation

JUnit 5 tests use `*Test.java` under each module's `src/test/java`; Vitest uses `editor-web/test/*.test.ts`. Add regression coverage for pathfinding, persistence, timetables, movement, and deterministic resume/skip behavior. Distinguish automated success from in-game verification; record outstanding manual checks in `MTR-待验证.md` and results in `MTR-测试项目清单.md`. Code changes must also update relevant architecture/process docs and `README.md`.

## Commits & Pull Requests

Use focused, sentence-style subjects with a subsystem prefix, such as `New web editor: depot panel` or `Docs: ...`. PRs should describe behavior, affected modules/loaders, checks run, compatibility or save-format effects, linked issues, and screenshots for UI/rendering changes. Commit or push only when requested.
