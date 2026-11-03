# MTR Advanced

An improved fork of [Minecraft Transit Railway](https://github.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway) 3.x. It is based on `3.2.2-hotfix-1`, the current version is `3.2.2-hotfix-2`, and Minecraft 1.20.1 is the main target.

The goal is to take MTR from running trains to operating a real network: routes you can control, timetables you can trust and trains that behave predictably.

This is not an official release. The version stays at `3.2.2` so that add-on mods that depend on it keep working, and later changes only raise the hotfix number.

## Completed improvements

**Rails and nodes**

- Rail directions go from 16 fixed directions to 0.1° precision, with long-distance geometry fixes and two-arc connections.
- Right-click a node with the brush to enter an exact angle from -180° to 180°. Rails stay on the side of the node they leave from, and connected rails are rebuilt immediately, so there is no need to remove them first.
- Holding a rail tool shows node direction lines. Holding the brush shows the angle and the slope of connected rails.
- Node rotation rejects angles that would make a rail double back on itself, and reports the error in chat.

**Pathfinding and routes**

- New deterministic pathfinding: the same network and routes always give the same path. New depots use it by default, existing depots keep the old algorithm, and the depot screen can switch between them.
- Nodes can be marked as key nodes and picked on the dashboard map as nodes a route must pass through.
- The dashboard map shows the whole rail network, route paths, depots and turn-back paths. Map data is cached in a local SQLite file and only changes are transferred.
- The map remembers terrain. Each time the dashboard opens, the loaded chunks update what was stored, so the map isn't limited to the area around the player.
- Running trains stay where they are when paths are refreshed, rather than all jumping away.
- When pathfinding fails, the old path is kept and the depot screen shows the reason. Changing the rails shows that a path is out of date.

**Route operation**

- A route can pass through a station without stopping, and can set its own door-open time per station.
- A depot can keep its trains on the line: after the last route they turn back onto the first one, and without a next service soon they wait on the line for it. A service can be marked to end at the depot, and the train then goes back after turning back as usual.
- A route can list several reversing sidings after its last stop. A train about to leave the terminal takes the first one that is free and keeps it until it is through, or waits at the platform if all of them are taken.
- Passed stations don't open doors and don't appear in PIDS or announcements, and times update automatically.
- The minimum dwell time is now 8.5 seconds. Doors never opened on very short dwells in the original, so older settings below the minimum are raised to 8.5 seconds when paths are refreshed.
- Timetable sensors now fire when the remaining time first reaches the set value, and only once for each arrival of a train.

**Timetables**

- Routes can have a timetable, in a screen modelled on Japanese train running sheets, with arrival and departure times for each stop. The fastest times come from the path, speed limits and vehicle, and also work for routes that aren't in a depot's path. Blank times are worked out automatically.
- Stops with a set departure time leave on time. Other stops can vary. Late trains shorten their dwell, but not below 8.5 seconds.
- Trains take the next service by themselves, and the timetable decides how many trains run. Loop lines with the same first and last stop stay out of the depot until the end of service.
- After turning back, trains follow the services of the opposite route, waiting on the turn-back track or at the platform until it is time to leave.
- Vehicles can be limited to certain service types, for example local and express in one group and rapid and limited express in another. The timetable picks services by group.
- A stop can be set to wait for a following train to overtake, with a limit on the wait. PIDS shows how many minutes a train is late.
- The running sheet checks for services that need the same platform or turn-back rail at the same time, including trains turning back on the platform they arrived at.
- A service can carry on onto a service of another route, such as a loop's 07:00 running through to another line at 07:32, and that service can carry on again, up to seven times. A train can leave a loop at any stop, and join a loop at a chosen stop, either from the depot or at the end of a through service, where it stays on the loop until service ends.
- Platforms can be marked as timetable boundaries. A service can leave its route or join another one part of the way along only at a boundary it stops at; joining a route part of the way along keeps to that route's service times, worked out from its first stop. Without boundaries, only the last stop runs on to the first.
- A single service can run differently from its table at some stops: its own running time, stop time, waiting to be overtaken, or passing or stopping. Passing or stopping only changes the times for now; the train still stops where the depot's path does.
- After a restart, trains don't try to catch up. Each one goes straight to the stop the timetable has it at by then.
- A route can have up to 16 tables, each with its own days of the week, services and stop times. Times are saved in UTC. The time zone and the hour a new operating day starts are in `config/mtr_timetable.json`, and in multiplayer the server's file is used. Last trains after midnight count as the day before.

**Line groups**

- The two directions and different service types of a line form one group, which folds up in the main list and switches direction in the running sheet. Their names don't have to match exactly.
- Signs, PIDS, route maps and add-on mods all read the group colour, which is the colour of the first route in the group by name. The colours of the other routes are kept for drawing train diagrams automatically in the future.
- The words that identify directions and service types are in `config/mtr_line_words.json` and can be changed. In multiplayer, the file on the server is used. A route's group can also be filled in directly.
- Routes can be duplicated in one click, which makes it easy to set up different timetables.

**Advanced editor**

- The dashboard's Advanced Editor button opens a page in the system browser, served by the client to this computer only, with a key for the session. It is separate from the map web server.
- A tree of lines, routes and depots, a running diagram of each line, and each route's timetable laid out like a printed one, for any day of the week.
- Timetables are edited in the page: typing times in cells, right-click menus for passing, waiting and pushing a change to later services, dragging stop ends on the diagram, adding and copying tables and services, and making through links in bulk. Times are worked out in the page the same way as in the game.
- Changes are saved as a draft as they are made and only sent to the game with Apply, through the game's own packets. A route changed in the game since the draft is reported as a conflict. Routes managed in the editor can only be viewed in the game's timetable screen.
- For loops, it shows how the trains go round lap after lap, the way the game runs them.
- New service types are made in the page from an existing route, picking each stop's track from the platforms the game finds for it, and an existing route's tracks and stops can be changed the same way. The order of stations always comes from the route's path.
- Depots keep vehicle working: which services each train of each siding runs, by groups of weekdays. A first draft is worked out in one click, and services are added to a train by clicking them on the diagram, with checks for missed connections and double assignment. Depots set to run by vehicle working send out a train for each slot when its first service is due, and the train runs exactly the services of its duty, so services nobody is given don't run. Each shape of duty gets a path of its own, so a single service's passing or stopping is real there. Trains booking single-track stretches with their direction keep two of them from meeting head on, and a train staying on the line overnight takes over the duty chosen for it the next day.
- The server keeps the compressed rail network (sections between junctions and stations, with their length at each speed limit) and the sections each path runs through in tables of its own in the save's SQLite database.

**Speed curves**

- Depots can run their trains on speed curves, which new depots do by default. Trains slow down before a slower rail or a stop instead of after, keep to the lowest limit of every rail they are on, and speed up and brake at separate rates set for each siding.
- Running times for timetables come from the same curves along each route's own path, so they match how trains run and don't wait for the depot's path to be refreshed. With time to spare, trains cruise slower to arrive on time; when late, they run as fast as they can to catch up.

**Path boundaries**

- A node, or one rail from a node, can be marked as a path boundary in the node screen. At a turnout, mark the rail rather than the node. The server splits the network into regions at the boundaries and warns about boundaries that don't cut anything.
- Route searches stay in the regions of each stretch's ends, so they can cross into the next region but not wander through unrelated ones. Without boundaries, pathfinding is unchanged.
- The dashboard can filter stations, routes and depots to the regions of a picked station. Platforms can be set as main line or passing tracks.

**Dashboard**

- Lists scroll by row, have a scrollbar and can be searched with several keywords.
- Click a route, a station area or a depot area on the map to find it in the list on the left.
- Lists for choosing routes, such as a depot's instructions, fold each line like the routes page and show where each route goes, so the two directions of a line can be told apart.

**Simulation**

- A vehicle scheduling and timetable simulator that doesn't depend on Minecraft checks the rules for vehicle working, timing and delay recovery in unit tests. It isn't connected to the game yet. The game uses simpler online booking of services for now.

**Rendering**

- Fixes the precision problem that made trains and displays shake with the camera far from the world origin.

**Storage and reliability**

- Railway data is kept in a single SQLite database, with rotating snapshots, recovery from corruption and migration from the old format.
- Fixes a crash when loading saves with macOS metadata files, and empty names no longer stop data from being saved.

**Performance**

- Optimises train pathfinding, data sync and the save loop. In a synthetic benchmark, pathfinding along a 20,000-segment single line went from 468 ms to 32 ms, and unreachable targets no longer cause long searches.

**Security**

- Hardens the network packet entry points, server permissions, value checks and the built-in web server. The configuration file is `config/mtr_security.json`.

**Platforms**

- Supports Fabric and Forge on Minecraft 1.20.1, with Architectury updated to 9.2.14.
- Supported versions are 1.16.5, 1.17.1, 1.18.2, 1.19.4 and 1.20.1. Of the 1.19 versions, only 1.19.4 remains.
- ANTE is merged into the core, so one jar contains both MTR and ANTE. This applies to 1.17.1 and later. 1.16.5 lacks the shaders ANTE needs, so it only has MTR.
- Adds a network protocol version, so incompatible clients and servers get a message when connecting.
- New depots use real-world time for departures by default.

## Roadmap

| Stage | Content | Status |
|---|---|---|
| 0 | Add-on compatibility, merging ANTE into the core | Packaging and running logic done, `Rail` left for stage 4 |
| 0.5 | Basic definitions and simulation: route timetables, trips, vehicle working, restart recovery, new data format | Simulator done |
| 1 | Pathfinding base: state graph, deterministic A*, required nodes, keeping trains in place on refresh, and path boundaries with regions that limit pathfinding and pick each route's platforms at shared stations are done; section caching is not | In progress |
| 2 | Route operation: stop or pass, door-open time, turn-back dwell, multiple station areas, passing trains on PIDS and platform time lists are done; passing speed limits are not | In progress |
| 3 | Block fixes: occupancy registration, depot exit checks, braking distance lookahead, whole-section highlight | Done |
| 4 | Rail geometry: node slopes, vertical curves, transition curves | Not started |
| 5 | Speed curves: TPS compensation and a first version of speed curves (braking ahead of slower rails and stops, separate acceleration and braking, cruising slower to keep time and catching up when late) are done; curvature, vertical curves and passing speed limits are not | In progress |
| 6 | Block sections: virtual sections, signal binding, single-track direction reservation, priority at conflict points | Not started |
| 7 | Timetabled operation: first versions of route timetables, timed departures, vehicle working, turn-back services, vehicle service type groups, platform and turn-back conflict checks, multiple timetables by weekday, loops joined part way round, through services, aligning after a restart and choosing between reversing sidings after a terminal are done; choosing between platforms to turn back on is not | In progress |
| 8 | Interlocking: a first version of waiting to be overtaken is done; full interlocking and timed connections between local and express trains are not | In progress |
| 9 | Timetable boundaries at platforms are done; splitting through services, deadheading, train priority and boundaries on turn-back rails are not | In progress |
| 9.5 | Custom announcements: audio added by hand for automatic announcements, uploaded to the server, then synced to clients and cached locally | Not started |
| 10 | Final verification | Not started |

### Current priorities

The stages above describe the work as a whole. This is the order it is being done in:

1. Test in game what is written but untested: the advanced editor, single-service exceptions, timetable boundaries, choosing between reversing sidings, depots keeping trains on the line and the server jar. Then build the other four Minecraft versions, which haven't been built since these changes.
2. Fix two safety problems an architecture review found in running by vehicle working: a train held between stations, or with its tail still on a rail, can lose its booking of a single-track stretch. Then test in game the speed curves and running by vehicle working. Until what is written is tested, no new running rules are added to the current code; they wait for the component below.
3. Move the simulation into a component of its own that runs timetables, vehicle workings and the rail network without Minecraft, so it can be reused by the web editor, a command line tool and tests. `MTR-模拟组件与解耦.md` records what is already separated and the target architecture. MTR becomes one host of it: it turns its rails, routes and depots into the component's data, asks where each train should be at the current time, and draws the trains, opens the doors and carries the passengers.
4. Simulated running in the web editor, with lateness, held trains and conflicts shown against the timetable.
5. Trains in game placed by the component from the real time instead of moving tick by tick.
6. Then, written in the component first: speed limits when passing stations, block sections, turning back at a platform, interlocking, splitting through services and deadheading.
7. Faster OBJ parsing or caching parsed models on disk, faster scripts and custom announcements, which can move earlier if needed.

Run logic part 1 (service decisions in `TrainService`, tested in game), choosing between reversing sidings after a terminal, the running diagram design, and the advanced editor's parts up to vehicle working (writing to the game, timetable editing with single-service exceptions, timetable boundaries, path boundaries and regions, new service types, vehicle working data and generation) are done. Path boundaries, new service types and vehicle working have had a first quick test in game; their behaviour is still being watched.

The first round of load work is done: with 69 resource packs a full reload takes about 110 seconds and fits in a 16 GB heap.

The full design, rules and verification records are in [MTR-3.x改造记录与寻路方案.md](MTR-3.x改造记录与寻路方案.md) (Chinese). The behaviour to check in large-scale use is listed in [MTR-测试项目清单.md](MTR-测试项目清单.md) (Chinese).

## Project complexity

With ANTE merged, the common module has about 120,000 lines of Java, of which ANTE is about 34,000. The main difficulty isn't the amount of code, but how closely these systems depend on each other:

- **Geometry, paths, timing and blocks form a chain**: changing the shape of a rail changes its length, length changes timing, timing changes block use, and blocks in turn affect how trains actually run.
- **Both server and client simulate trains**: the running logic has to match on both sides, or trains shake or jump.
- **Several versions and loaders**: the same code has to work on different Minecraft versions and on both Fabric and Forge, and some APIs have breaking changes in 1.20.
- **Save and network compatibility**: real saves have to migrate, and a format mistake can damage a player's network.
- **Add-on mods**: parts of ANTE still change internal classes through mixins, and add-ons like MSD and YJCM call internal methods and fields directly, so changing one method signature can cause a crash on load.
- **Operating rules are complex in themselves**: timetables, vehicle working, overtaking, timed connections and through services are specialist railway problems, and the rules constrain each other.

## AI-assisted development

This project is developed with an AI coding assistant, Claude Code. For a personal project of this size, AI assistance is a practical necessity:

- **Understanding cost**: a change often needs geometry, network, rendering and save code to be understood at once, which takes one person a long time to trace.
- **Changes across files**: the 1.20.1 port alone touched dozens of files and the version compatibility layer, where manual work easily misses something.
- **Working through designs**: gaps in timetable and block rules can be checked against the real code before writing anything, which reduces rework.
- **Verification**: geometry tests, performance benchmarks and multi-version builds can run often, which lowers the risk of regressions.

The project author decides the requirements, operating rules and final trade-offs, and does the in-game testing. The AI reads code, implements, builds and keeps records. AI output isn't automatically correct, so the documents separate what was tested in game, what only builds and what hasn't been verified, and anything not tested in game is clearly marked.

## Building

JDK 17 is required.

```bash
./gradlew build -PbuildVersion=1.20.1 -x test
```

The output is in `build/release/`. Without `-PbuildVersion`, Gradle builds 1.16.5. Before committing, run Gradle once with the default version so that the generated mappings return to their default content.

The Fabric version needs [Fabric API](https://modrinth.com/mod/fabric-api), and the Forge version needs the Forge version of [Architectury API](https://modrinth.com/mod/architectury-api). Clients and servers must use jars with the same protocol version, which is currently 23. Jars with ANTE are about 136 MB.

The simulation component in `transit-sim` is a plain Java 8 project without Minecraft, built into the Fabric and Forge jars; `./gradlew :transit-sim:test` runs its tests.

Each build also makes a `-server.jar` of about 20 MB for dedicated servers. It is the same build without fonts, sounds, textures, models and the script engine of ANTE, which only clients use, so it always matches the full jar of the same build. Clients still need the full jar.

## License and credits

This project uses the MIT License. See [LICENSE](LICENSE).

- Minecraft Transit Railway: Copyright (c) 2022 Jonathan Ho
- Changes in this fork: MitsuhaQuQ
