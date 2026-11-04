# MTR Advanced

An improved fork of [Minecraft Transit Railway](https://github.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway) 3.x. It is based on `3.2.2-hotfix-1`, the current version is `3.2.2-hotfix-2`, and Minecraft 1.20.1 is the main target.

The goal is to take MTR from running trains to operating a real network: routes you can control, timetables you can trust and trains that behave predictably.

This is not an official release. In the mod list it shows as MTR-MTM_Enhanced, version `3.2.2`, so that add-on mods that depend on MTR 3.2.x keep working; this fork's own version is shown as a second entry, MTM_Logic (`0.1.0` and up). The released jars are named like `MTR3-MTM-0.1.0-fabric-1.20.1.jar`.

## Completed improvements

**Rails and nodes**

- Rail directions go from 16 fixed directions to 0.1° precision, with long-distance geometry fixes and two-arc connections.
- Right-click a node with the brush to enter an exact angle from -180° to 180°. Rails stay on the side of the node they leave from, and connected rails are rebuilt immediately, so there is no need to remove them first.
- Holding a rail tool shows node direction lines. Holding the brush shows the angle and the slope of connected rails.
- Node rotation rejects angles that would make a rail double back on itself, and reports the error in chat.
- Node angles are readable: they count from east, clockwise seen from above, and the node shows the angle as a compass reading (east 37.5° toward south) with a dial at the node. The brush screen has a map of the node, north up, showing its rails as they are and at the angle typed in; dragging on it sets the angle, in 22.5° steps with shift.

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
- Depots running a route in common share its services, so each service is run by one train, whichever depot sends it.
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
- New service types are made in the page along the rails: pick where one starts and ends and stations to go through, and its stations are those the shortest way between them passes. Tracks are picked on a graph of how trains can run between platforms without turning back, kept apart by network (rails joined together), where fixing one station's track moves the others to tracks that still run. Clicking a station's name opens its track diagram, with the rails along their curves as the server sends them, turned so the train runs left to right, where tracks can be picked too. An existing route's tracks and stops are changed the same way, and routes whose tracks can't run along the rails, turn back part of the way or take a detour are marked. The order of stations always comes from the route's path. A service type can be deleted from the page too, after it lists the depots, vehicle workings and through links that still name it.
- Depots keep vehicle working: which services each train of each siding runs, by groups of weekdays. A first draft is worked out in one click, and services are added to a train by clicking them on the diagram, with checks for missed connections and double assignment. Depots set to run by vehicle working send out a train for each slot when its first service is due, and the train runs exactly the services of its duty, so services nobody is given don't run. Each shape of duty gets a path of its own, so a single service's passing or stopping is real there. Trains booking single-track stretches with their direction keep two of them from meeting head on, and a train staying on the line overnight takes over the duty chosen for it the next day.
- Simulated running: the running diagram can overlay how the draft would actually run, simulated by the component on the server, with late services, services that don't run and deadlocks listed. Generated vehicle workings are checked the same way.
- Right-clicking a depot run by the component puts its trains where its timetable has them now, worked out from the start of the operating day away from the server thread, instead of sending them out one by one. Depots sharing its routes are placed with it, and trains a player drives stay where they are.
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

**Simulation component**

- The running rules are in `transit-sim`, a component that doesn't depend on Minecraft: the rail network and pathfinding, train motion and speed curves, timetables, vehicle working, single-track reservations and a whole-network simulator, with unit tests for each.
- The component runs the trains of every depot on speed curves in the game by default. The game only shows its trains, doing what needs the world: cars, riders, doors, signals and the rails they take. Each tick it moves on to the real time, so trains keep to time on a slow server. A button in the dashboard's settings switches it between drive, off, and compare, which runs it beside the game and logs how far apart the two come.
- Clients follow each train along the run segment the server sends, by the server's clock, instead of stepping it frame by frame. Sensors check every block the train passed since the last tick, so a slow server doesn't skip them.
- Written and tested in the component but not yet in the game: working out vehicle workings for a timetable depot that has none, and a train keeping the duty it left with when the depot's workings change.

**Multiplayer editing**

- The server keeps a revision for each part of each station, platform, siding, route and depot. A change made from an older copy isn't written: the player is told who changed it and is sent the data as it is now. Two players changing different parts don't hold each other up.
- The web editor sends its changes as one batch, made all or not at all. Edit screens and the editor show who else is editing the same thing.

**Announcement audio**

- Players upload their own announcement recordings to the server from the advanced editor's audio page, which lists them, plays them in the browser and deletes them. `/mtr deleteaudio` deletes them in game. Only Ogg Vorbis is taken, checked on both sides, and each recording is stored and sent once under its SHA-256, however many names point to it.
- The server keeps them in one database in the save (`mtr/announce.db`). Clients download what they don't have in the background and cache it in `mtr_audio_cache`, shared by every world and server.
- Announcer blocks play an uploaded file (`steve:next_station.ogg`), a game or resource pack sound as before, or a web address, which only plays from sites allowed in `config/mtr.json`.
- Who may upload or delete others' files, file size, total size, length and uploads per hour are set in `config/mtr_security.json`.

**Rendering**

- Fixes the precision problem that made trains and displays shake with the camera far from the world origin.
- Resource packs can give a train two custom bogies (model, texture, position), as in MTR 4. Its cars are then placed on their bogies, and near the camera the bogies turn along the track. Built-in bogies turn along the track too, with a client setting that is on by default.

**Storage and reliability**

- Railway data is kept in a single SQLite database, with recovery from corruption and migration from the old format.
- Edits are saved at the end of the tick in one transaction, and commits survive a power cut. An autosave takes everything but the rails at one moment.
- Backups come in batches: a whole base, then backups of only what changed since it. A backup is restored automatically on corruption, or when an admin names it in `backups/restore.txt` before starting.
- Nodes the world lost while their rails were saved, as after a crash, are put back once their chunk loads.
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
| 0.5 | Basic definitions and simulation: route timetables, trips, vehicle working, restart recovery, new data format | Done; the simulator is a component of its own and runs speed curve depots in game |
| 1 | Pathfinding base: state graph, deterministic A*, required nodes, keeping trains in place on refresh, and path boundaries with regions that limit pathfinding and pick each route's platforms at shared stations are done; section caching is not | In progress |
| 2 | Route operation: stop or pass, door-open time, turn-back dwell, multiple station areas, passing trains on PIDS and platform time lists are done; passing speed limits are not | In progress |
| 3 | Block fixes: occupancy registration, depot exit checks, braking distance lookahead, whole-section highlight | Done |
| 4 | Rail geometry: node slopes, vertical curves, transition curves | Not started |
| 5 | Speed curves: TPS compensation and a first version of speed curves (braking ahead of slower rails and stops, separate acceleration and braking, cruising slower to keep time and catching up when late) are done; curvature, vertical curves and passing speed limits are not | In progress |
| 6 | Block sections: virtual sections, signal binding, single-track direction reservation, priority at conflict points | Single-track direction reservation done; the rest not started |
| 7 | Timetabled operation: first versions of route timetables, timed departures, vehicle working, turn-back services, vehicle service type groups, platform and turn-back conflict checks, multiple timetables by weekday, loops joined part way round, through services, aligning after a restart and choosing between reversing sidings after a terminal are done; choosing between platforms to turn back on is not | In progress |
| 8 | Interlocking: a first version of waiting to be overtaken is done; full interlocking and timed connections between local and express trains are not | In progress |
| 9 | Timetable boundaries at platforms are done; splitting through services, deadheading, train priority and boundaries on turn-back rails are not | In progress |
| 9.5 | Custom announcements: audio added by hand for automatic announcements, uploaded to the server, then synced to clients and cached locally | Upload, sync, local cache and announcer blocks playing the audio are done; routes, stations and timetable events using it are not |
| 10 | Final verification | Not started |

### Current priorities

The stages above describe the work as a whole. This is the order it is being done in:

1. Test everything together on a multiplayer server (test list items U75 to U98): the component running trains in game, clients following run segments, edit conflicts, saving and backups, and what was written earlier but not yet tested, such as announcement audio (U47 to U54). Only bugs in this base work are fixed during this round; no new features are mixed in. [MTR-多人服务器测试指南.md](MTR-多人服务器测试指南.md) (Chinese) explains how to run it.
2. Then, written in the component first and reaching the game the same way: speed limits when passing stations, block sections, turning back at a platform, interlocking, timed connections, splitting through services and deadheading.
3. Fares by distance, which needs new save data and packets of its own, done in one go once the base is stable.
4. Things not tied to running, which can be done at any time: lifts, and uploaded audio played by routes, stations and timetable events, faster resource loading, and building the other four Minecraft versions, which haven't been built since these changes.
5. Running the security tests again at the end, to cover the new packets.

Done so far: the simulation component and its move into the game (steps S0 to S6 and G0 to G4 in [MTR-模拟组件与解耦.md](MTR-模拟组件与解耦.md), Chinese, which also records the architecture); run logic part 1 (service decisions in `TrainService`, tested in game); choosing between reversing sidings after a terminal; the running diagram design; and the advanced editor up to vehicle working and simulated running. Path boundaries, new service types and vehicle working have had a first quick test in game; their behaviour is still being watched.

The first round of load work is done: with 69 resource packs a full reload takes about 110 seconds and fits in a 16 GB heap.

The full design, rules and verification records are in [MTR-3.x改造记录与寻路方案.md](MTR-3.x改造记录与寻路方案.md) (Chinese). Where the code lives and how the main flows run (server tick, train sync, rendering, resource loading, saving, editing, path generation, ANTE scripts) are in [MTR-代码说明与流程.md](MTR-代码说明与流程.md) (Chinese). The behaviour to check in large-scale use is listed in [MTR-测试项目清单.md](MTR-测试项目清单.md) (Chinese).

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

Both versions need [Architectury API](https://modrinth.com/mod/architectury-api) for their loader, and the Fabric version also needs [Fabric API](https://modrinth.com/mod/fabric-api). Clients and servers must use jars with the same protocol version, which is currently 44. Jars with ANTE are about 136 MB.

The simulation component in `transit-sim` is a plain Java project without Minecraft (Java 8 by default, raised when needed), built into the Fabric and Forge jars. `./gradlew :transit-sim:test` runs its tests, and `./gradlew -p transit-sim test --offline` builds and tests it on its own, without Minecraft or a network once its dependencies are cached.

Each build also makes a `-server.jar` of about 20 MB for dedicated servers. It is the same build without fonts, sounds, textures, models and the script engine of ANTE, which only clients use, so it always matches the full jar of the same build. Clients still need the full jar.

## License and credits

This project uses the MIT License. See [LICENSE](LICENSE).

- Minecraft Transit Railway: Copyright (c) 2022 Jonathan Ho
- Changes in this fork: MitsuhaQuQ
