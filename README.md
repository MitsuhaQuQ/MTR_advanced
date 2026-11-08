# MTR Advanced

An improved fork of [Minecraft Transit Railway](https://github.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway) 3.x. It is based on `3.2.2-hotfix-1`, the current version is `3.2.2-hotfix-2`, and Minecraft 1.20.1 is the main target.

The goal is to take MTR from running trains to operating a real network: routes you can control, timetables you can trust and trains that behave predictably.

This is not an official release. In the mod list it shows as MTR-MTM_Enhanced, version `3.2.2`, so that add-on mods that depend on MTR 3.2.x keep working; this fork's own version is shown as a second entry, MTM_Logic (`0.1.0` and up). The released jars are named like `MTR3-MTM-0.1.0-fabric-1.20.1.jar`.

## Completed improvements

**Languages**

- MTR's own translations load again from the jar: the language files are named in lower case, as Minecraft looks them up, so text no longer falls back to English in Chinese and the other languages.
- Chinese added for MTR text the translation pack lacks (resource pack creator, depot path messages, some settings), and ANTE screens use language keys instead of text written into the code.

**Rails and nodes**

- Rail directions go from 16 fixed directions to 0.1° precision, with long-distance geometry fixes and two-arc connections.
- Right-click a node with the brush to enter an exact angle from -180° to 180°. Rails stay on the side of the node they leave from, and connected rails are rebuilt immediately, so there is no need to remove them first.
- Holding a rail tool shows node direction lines. Holding the brush shows the angle and the slope of connected rails.
- Node rotation rejects angles that would make a rail double back on itself, and reports the error in chat.
- Node angles are readable: they count from east, clockwise seen from above, and the node shows the angle as a compass reading (east 37.5° toward south) with a dial at the node. The brush screen has a map of the node, north up, showing its rails as they are and at the angle typed in; dragging on it sets the angle, in 22.5° steps with shift.

**Pathfinding and routes**

- New deterministic pathfinding: the same network and routes always give the same path. Train, boat and other depots that run by timetables always use it, as their routes, fastest times and duties do; the depot screen no longer offers the old algorithm for them.
- Nodes can be marked as key nodes and picked on the dashboard map as nodes a route must pass through.
- The dashboard map shows the whole rail network, route paths, depots and turn-back paths. Map data is cached in a local SQLite file and only changes are transferred.
- The map remembers terrain. Each time the dashboard opens, the loaded chunks update what was stored, so the map isn't limited to the area around the player.
- Running trains stay where they are when paths are refreshed, rather than all jumping away.
- When pathfinding fails, the old path is kept and the depot screen shows the reason. Changing the rails shows that a path is out of date.
- Each route has its own path, found once from its platforms and waypoints, which depots running by a timetable build their paths and vehicle workings from instead of searching every route again.
- A route's fastest times come only from its own path, for the slowest vehicle running it, so the editor, the conflict check, automatic vehicle working and the simulator use the same times. A route no depot runs is timed with a default vehicle (8 cars, 0.8 m/s²).
- A route can be made a connecting line, in game or in the editor. It still carries passengers and through services, but other routes' paths keep off its own track, and a route that can only be found over it says so and where. Its own track is the part between its first and last key node, or without key nodes from the first fork after its first stop to the last before its last, so a connecting line leaving a station along the main line doesn't take the main line too.
- Pathfinding boundaries and connecting lines are shown as one layer of isolation on the dashboard map and the editor's track diagrams. Right-clicking a rail in a track diagram makes it a boundary or clears it, and releases a connecting line's rail it shares with another line.
- The map and the track diagrams draw a route along the path its trains run.
- Trains running by duties lock their way through station throats as a home signal would: the way out with the run they ask for to leave, the way in when they come close, waiting outside the throat until it is theirs and letting each turnout go once their tail is past. `throatLocks` in `config/mtr_security.json` turns it off. The locks only work when every train on the track takes part, so on track shared with a depot not running by duties (sending trains by frequency, for instance) they are off, with a warning in the server log and the editor; path boundaries keep the two apart.
- The routes trains take through station throats are worked out from the routes' paths: the turnouts passed coming in and going out, and which ways through them cross. The stop editor shows them, and the timetable check reports services crossing at a turnout within half a minute.
- Each station's tracks are worked out from the routes using them: up or down by where the routes' ends lie (north or east is up), main line or passing track (the hand-set flag first, otherwise the track services pass on is the main line), and their order across the station. The editor and the platform screen name tracks as "上行 3道 正线".
- A new line can be made from nothing in the web editor: choose the transport mode, name the line and type, and pick its stations and tracks along the rails, both ways at once, named up and down. It starts with its stations only; services and depots are added afterwards.
- Vehicle working is planned by operating line (route group) rather than by depot: every depot running a line by its timetable shares its services, a depot listing only one direction also runs the other, the nearest siding's trains go first, and when trains run short the depot screen and the editor say how many are needed and where to add them. After its duty a train goes back to whichever siding of the line's depots with the same vehicle needs a train next, not only its own depot's.
- On the running diagram's summary timetable a service can be set to be joined part of the way by a train sent out of a chosen depot, or to end part of the way and run empty to a chosen depot; vehicle working plans those services as the start or the end of a duty.
- The editor's one-click vehicle working plans a depot together with the other depots of its lines, as the game does, and drafts the depot's own part.
- The dashboard map draws long curved rails along their curve, from a few points per curve the server sends with the map data.
- The advanced editor has a whole-network track view: every rail of a transport mode with platforms, sidings, the isolation layer and the chosen line's routes, with pan, zoom and the same right-click isolation menu. Zoomed out it is drawn as a schematic of horizontal, vertical and 45° lines, each run of track between junctions simplified; zoomed in it follows the real track.

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
- How trains turn back is set at a route's last stop, in the web editor's station diagram. After the station (站后): the turn back rails listed in the last stop's waypoints, as before. At the platform (站前): besides the route's own last platform, other platforms of the station can be ticked to take turns at, and a train chooses one as it leaves the stop before. A route can tick the platforms of its first station it would rather start from (by service type, 种别优先), which comes first when choosing where to turn back for it. With both set, the platforms come first and the rails after the station only when every platform is taken or would make the train miss its next service.
- Interlocking can be set by hand with MTR's own signal block tool: a signal block of the interlocking colour (magenta unless `interlockColor` in `config/mtr_security.json` says otherwise) in a station throat is an interlocking zone. A train running by duties takes it as a whole with its way through the throat and lets it go once its tail has left it, so two trains never use it at once whichever way, which covers rails crossing without a node. It adds to the turnouts worked out from the routes' paths, and stays a signal block as before. The timetable check and the editor's throat notes count it.
- Long track is cut into block sections, each the service braking distance at the rail's speed limit (`blockDeceleration`, default 1 m/s², and at least `blockMinLength`, 400 blocks, in `config/mtr_security.json`). Trains take and are held by sections rather than whole rails, so a train follows the one ahead along a long line and stops short of the section it is in. Platforms, sidings and turn back rails are never cut, and signal blocks the players paint stay whole however long, as cutting them let trains hold each other up. Signals count their aspects in sections ahead, see every section's state wherever it is, and show a restrictive aspect for track the client doesn't have. The rail inspector says how a long rail is cut and which sections are taken.
- Of the ways it can turn back, a train running by duties takes the first in order with which it still makes its next service and whose way no other train is on or holds; if none does, the one it can leave soonest by. Trains running by duties now use every turn back rail, not only the first. The timetable check counts the platforms taken in turn as that many platforms. Trains sent out by frequency still take the first free turn back rail and their own platform.
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
- Operations are set in the web editor: the game's siding screen sets only a siding's number, colour and vehicle, and shows the rest (the trains it holds, speeding up and braking, highest speed, driving by hand and its highest speed, how soon it goes back to automatic, service types), which the depot dialog sets.
- ANTE's mixins that change how trains run are isolated for now (`anteRunHooks` in `config/mtr_security.json`, off by default): trains open doors only at MTR's own platforms, screen doors and gates, not at ANTE's eye candy or Metropolis's platforms, and manual driving speeds go only as far as MTR's own rails. ANTE's models, scripts, P-type rails, vertical curves and saved data stay; turning it on brings back ANTE's behaviour.
- Simulated running, a debugging tool off by default (`editorSimulation` in `config/mtr_security.json`): the running diagram can overlay how the draft would actually run, simulated by the component on the server, with late services, services that don't run and deadlocks listed. Generated vehicle workings are checked the same way. Only the part of the world that concerns the routes is simulated: the depots running them, and the sidings whose trains pass the same track.
- Right-clicking a depot run by the component puts its trains where its timetable has them now, worked out from the start of the operating day away from the server thread, instead of sending them out one by one. Depots sharing its routes are placed with it, and trains a player drives stay where they are.
- The server keeps the compressed rail network (sections between junctions and stations, with their length at each speed limit) and the sections each path runs through in tables of its own in the save's SQLite database.

**Speed curves**

- Depots can run their trains on speed curves, which new depots do by default. Trains slow down before a slower rail or a stop instead of after, keep to the lowest limit of every rail they are on, and speed up and brake at separate rates set for each siding.
- Running times for timetables come from the same curves along each route's own path, so they match how trains run and don't wait for the depot's path to be refreshed. With time to spare, trains cruise slower to arrive on time; when late, they run as fast as they can to catch up.

**Path boundaries**

- A node, or one rail from a node, can be marked as a path boundary in the node screen. At a turnout, mark the rail rather than the node. The server splits the network into regions at the boundaries and warns about boundaries that don't cut anything.
- Boundaries only narrow the search: each stretch of a route is looked for in the regions of its ends first, and only if there is no way there, in the whole network. A boundary never stops a route that has a way; keeping trains off rails is what connecting lines are for. Without boundaries, pathfinding is unchanged.
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
- Each uploaded file is also a sound of the game, `mtr_audio:<uploader>/<sound ID>`, played through the game's sound system at its volume. `/playsound mtr_audio:steve/next_station block @a` plays it, with Tab completion. The sound ID is given on upload (made from the file name where it can be) and can be changed on the audio page. `audio_download_all` in `config/mtr.json` (on by default) fetches every file up to `audio_prefetch_max_kilobytes` (1 MB by default) as soon as the server lists it; bigger files, or every file with it off, are fetched the first time something plays them, and play the next time. Uploading a file under a name you already have updates it: it keeps its sound ID, and clients that had the old recording fetch the new one straight away. Files can be up to 2 MB.
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

- Measured with Java Flight Recorder on a large test world, the server thread spends about 4.1 ms a tick on MTR instead of 6.7 ms: platform and siding positions, a time segment's place on its path and a depot's list of sidings are worked out once instead of every tick, and the background route search for the website map's density runs one tick in ten.
- Optimises train pathfinding, data sync and the save loop. In a synthetic benchmark, pathfinding along a 20,000-segment single line went from 468 ms to 32 ms, and unreachable targets no longer cause long searches.

**Security**

- Hardens the network packet entry points, server permissions, value checks and the built-in web server. The configuration file is `config/mtr_security.json`.

**Platforms**

- Supports Fabric and Forge on Minecraft 1.20.1, with Architectury updated to 9.2.14.
- Supported versions are 1.16.5, 1.17.1, 1.18.2, 1.19.4 and 1.20.1. Of the 1.19 versions, only 1.19.4 remains.
- ANTE is merged into the core, so one jar contains both MTR and ANTE. This applies to 1.17.1 and later. 1.16.5 lacks the shaders ANTE needs, so it only has MTR.
- Where ANTE did what MTR now does itself, MTR's own is used: rail shapes, lengths and angles, connecting rails, the client's train tick and the server's train positions. ANTE's all-direction node and Bézier rails are gone; a world that has them keeps its nodes as ordinary MTR nodes and its Bézier rails become MTR's shape for the same ends. ANTE's own vehicles, scripts, decorations, 3D rail models, roll, vertical curves and P speed rails stay.
- Clients and servers check they have the same MTM_Logic version, so incompatible ones get a message when connecting.
- New depots use real-world time for departures by default.

## Roadmap

| Stage | Content | Status |
|---|---|---|
| 0 | Add-on compatibility, merging ANTE into the core | Packaging and running logic done, `Rail` left for stage 4 |
| 0.5 | Basic definitions and simulation: route timetables, trips, vehicle working, restart recovery, new data format | Done; the simulator is a component of its own and runs speed curve depots in game |
| 1 | Pathfinding base: state graph, deterministic A*, required nodes, keeping trains in place on refresh, and path boundaries with regions that narrow pathfinding (never stopping a route that has a way) are done; section caching is not | In progress |
| 2 | Route operation: stop or pass, door-open time, turn-back dwell, multiple station areas, passing trains on PIDS and platform time lists are done; passing speed limits are not | In progress |
| 3 | Block fixes: occupancy registration, depot exit checks, braking distance lookahead, whole-section highlight | Done |
| 4 | Rail geometry: node slopes, vertical curves, transition curves | Not started |
| 5 | Speed curves: TPS compensation and a first version of speed curves (braking ahead of slower rails and stops, separate acceleration and braking, cruising slower to keep time and catching up when late) are done; curvature, vertical curves and passing speed limits are not | In progress |
| 6 | Block sections: virtual sections, signal binding, single-track direction reservation, priority at conflict points | Single-track direction reservation and turnouts at station throats as conflict points (trains running by duties) done; the rest not started |
| 7 | Timetabled operation: route timetables, timed departures, vehicle working, turn-back services, vehicle service type groups, platform and turn-back conflict checks, multiple timetables by weekday, loops joined part way round, through services, aligning after a restart, choosing between reversing sidings after a terminal and between platforms to turn back on are done; handling trains running late is done too; trains of a vehicle pool shared across depots are being finished | In progress |
| 8 | Interlocking: a first version of waiting to be overtaken, routes through station throats worked out from the routes' paths, checked in timetables and locked by trains running by duties are done; routes for other trains, turning back and timed connections between local and express trains are not | In progress |
| 9 | Timetable boundaries at platforms and orders to send a train out at, or back from, a stop of a service are done; splitting through services, deadheading as services of their own, train priority and boundaries on turn-back rails are not | In progress |
| 9.5 | Custom announcements: audio added by hand for automatic announcements, uploaded to the server, then synced to clients and cached locally | Upload, sync, local cache and announcer blocks playing the audio are done; routes, stations and timetable events using it are not |
| 10 | Final verification | Not started |

### Current priorities

The stages above describe the work as a whole. This is the order it is being done in now (2026-11-07):

1. Handling trains running late or stuck: measured buffers, contingency plans with alarms, passing stations, turning back early, a spare from the depot taking over and a dispatcher's console are done.
2. Moving operating settings out of the game's screens into the web editor, so the game only handles the physical layer (station and depot areas, names, platform numbers), and finishing the split of depots and sidings: duties stored by vehicle pool rather than by siding, and planning by the pool as a whole.
3. Smaller leftovers: fixed turn-back routes, PIDS showing departures at terminals, suspending services by weekday, the conflict check using the measured times the planner uses, release intervals by depot exit.
4. Testing in game: nothing since test list item U223 has been tested yet. Then the security tests again at the end, to cover the new packets.

Done so far: the simulation component and its move into the game (steps S0 to S6 and G0 to G4 in [MTR-模拟组件与解耦.md](MTR-模拟组件与解耦.md), Chinese, which also records the architecture); run logic part 1 (service decisions in `TrainService`, tested in game); choosing between reversing sidings after a terminal; the running diagram design; the advanced editor up to vehicle working and simulated running (now a debugging tool, off by default); and the logic steps L1 to L4: routes' own paths and fastest times, platform tracks by direction, station throat routes and their locks, a whole-network track view, vehicle working by operating line, and new routes from scratch in the editor. Path boundaries, new service types and vehicle working have had a first quick test in game; their behaviour is still being watched.

The first round of load work is done: with 69 resource packs a full reload takes about 110 seconds and fits in a 16 GB heap.

The full design, rules and verification records are in [MTR-3.x改造记录与寻路方案.md](MTR-3.x改造记录与寻路方案.md) (Chinese). Where the code lives and how the main flows run (server tick, train sync, rendering, resource loading, saving, editing, path generation, ANTE scripts) are in [MTR-代码说明与流程.md](MTR-代码说明与流程.md) (Chinese). The behaviour to check in large-scale use is listed in [MTR-测试项目清单.md](MTR-测试项目清单.md) (Chinese). Performance measured on a stress test network, the logic problems it showed and the agreed architecture of the saved network layers (rails, track sections, legs between stops, run times, assembled paths, duties) are in [MTR-性能与逻辑优化.md](MTR-性能与逻辑优化.md) (Chinese). How it is carried out round by round is in [MTR-优化实施阶段.md](MTR-优化实施阶段.md) (Chinese).

## Project complexity

With ANTE merged, the common module has about 120,000 lines of Java, of which ANTE is about 34,000. The main difficulty isn't the amount of code, but how closely these systems depend on each other:

- **Geometry, paths, timing and blocks form a chain**: changing the shape of a rail changes its length, length changes timing, timing changes block use, and blocks in turn affect how trains actually run.
- **Both server and client simulate trains**: the running logic has to match on both sides, or trains shake or jump.
- **Several versions and loaders**: the same code has to work on different Minecraft versions and on both Fabric and Forge, and some APIs have breaking changes in 1.20.
- **Save and network compatibility**: real saves have to migrate, and a format mistake can damage a player's network.
- **Turning back**: between two services a duty turns back on a turn back rail near the station when that's shorter, even where the route lists none, and a way between services far longer than its platforms are apart is pointed out in the web editor. Duties that don't repeat week to week are planned week by week, so every day has them.
- **Duties belong to the pool**: each duty says where its train comes from — its siding first, its siding alone, or whichever siding of the pool has a train and the quickest way out — and the web editor lists duties by vehicle, sidings being only the places trains stand.
- **Contingency plans**: each rail system, a network of rails joined together, has plans for trains running late, waiting too long for their way or deadlocked, for some or all of its routes whichever depot runs them, which start by themselves or wait for a dispatcher; rails joining two rail systems keep both sets of plans, both holding until merged in the web editor, and removing the platform a system is known by moves it to another; with no plan a train is only reported. The web editor's sync status lists trains reported, where a plan is started, or what to do with one train set there and then, and ended. A plan can pass stations, turn trains back early and have a spare take a working over.
- **Passing stations**: a train a plan or a dispatcher's setting is on for passes the stations chosen without stopping, from its next stop on, and stops again from the next stop once it is less than a minute late or the plan is ended; the first and last stop of each part of its working are always stops, and the platforms show it passing. This version also fixes saved trains being counted one value short, which could lose or mix up trains of a siding after a restart.
- **Turning back early**: a train a plan or a dispatcher's setting is on for turns back at the nearest station it can, or at the station chosen, short of the end of its run: everyone gets off there, it runs empty round to the platform the other way, and either goes on with its working from there, waiting for its time, or goes back to its depot. Which stations a train can turn back at is found when the depot's paths are worked out, from the ways round between the platforms of a station that are no longer than 1000 blocks.
- **A spare taking over**: a late train hands the rest of its working to a spare: it runs to the end of a part of its working, everyone gets off, and it goes back to its depot empty, while a spare drives out of the depot in time for the next part. No spare is sent whose way out runs where the late train is held up or still has to run.
- **Dispatcher's console**: in the web editor's network view, the trains on duties shown at the signal block they are in, with the blocks taken; a dispatcher can hold a train or let it go, have it stand longer at a stop, turn it back early at a station, clear it out at its next stop and take it back empty, or send it back after this part of its working.
- **Speed limits**: wooden rails at 25 km/h instead of 20, a 35 km/h sandstone rail between wooden and stone, and a 45 km/h rail between stone and emerald; a speed limit for a line, set by right-clicking it in the web editor and no higher than its fastest rail, which its trains keep to between its stops; and a highest speed for each vehicle, set in the depot or the siding. Timetables are timed by them too.
- **Trains don't appear or vanish**: a train sent out much later than its working needed drives out of the depot to the first part it can still keep to, rather than being put on the line part of the way along.
- **Lighter reports**: reports of trains running late are only sent to players when something about them changes, and each check groups sidings by depot and looks up each route's rail system once. A route with no way at all is no longer searched for again over the whole network.
- **One place for each setting**: a siding's own speeding up, braking and driving by hand are hidden where the depot sets them for its vehicle, and a siding's capacity is called "trains it holds" in the game as in the web editor.
- **Measured buffers**: how much longer than planned trains actually take out of a siding, round at a terminal and back is recorded as they run (the 85th percentile of the last 20, from 15 s up), and generating duties adds it, so the next plan absorbs what made trains late; the timetables stay as they are. The depot dialog in the web editor lists them and can clear them.
- **Pool rules in one place**: how sidings of a pool make trains, keep them and take them back is written once in the simulation component, and the game and the component's runner both go by it.
- **Platforms in any station, stations in three dimensions**: a platform can be put in any station, wherever it is; where station areas overlap, as for an underground line under a main line, platforms and station name signs go by heights set for the areas with the station area tool, else by the nearest platform.
- **The original behaviour replaced**: for trains, sending out, making trains and running them go only by timetables, duties, pools and the component; the original logic is kept for cable cars and aircraft alone.
- **One set of motion rules**: trains run by the component's rules with speed curves, its simulator always running them; the old rules are kept only for the tests to compare against.
- **Sidings only by duties**: a depot of trains has its sidings run by duties alone, even before it has any, so it never falls back on the old logic, and the station throat locks stay on next to it; planning keeps to the trains each vehicle is given.
- **Filling a span of the timetable again fits what is there**: from one time to another every so often, the services already in that span move to the new times, keeping their links and exceptions, and are added or taken away as needed.
- **Every depot of trains runs by a timetable**: a depot sending trains out by frequency or by a list of departures has them made its routes' services once, by the real clock: every so many minutes in each real hour, an old depot's frequencies as close as whole minutes go, and then runs by duties like the others; the depot screen fills the timetable by frequency when asked. Cable cars and aircraft go on as before.
- **A train stays the same train**: its riders, cargo and ANTE settings belong to the train itself, so they stay with it whichever siding of its pool it goes back to, and how a vehicle speeds up, brakes and is driven by hand is set once for it in the depot.
- **Trains given to the depot by vehicle**: running by duties, a depot's sidings of the same vehicle share the trains the depot is given for it, and a train going back to another siding, or to one with a train already standing, is kept there as the same train with its number and ANTE settings; a siding's number is how many it holds.
- **Duties with numbers of their own**: a duty keeps its number wherever it is moved, so it can be moved to another siding of the same vehicle in the web editor, a train staying out overnight can go on with another siding's duty, and a duty can be set to go back to a given siding of its pool (out of A, back to B).
- **Ways out and back known, missing or guessed**: each siding's way out to a first stop and back from a last one is measured, found missing, or not known yet. Duties aren't planned out of a siding with no way to the stop, nor back to one with no way back, and the sync panel tells both; ways measured along rails since changed are measured again, and those not measured yet are pointed out as planned by distance.
- **Terminal times and turning back**: a route's first stop has an arrival and its last a departure, following the train's previous or next service: the one set by hand (the timetable's context menu, "run next", shown in the column's head), the next in its duty, or else the nearest it can make. The running diagram draws the train standing at the platform, changing platform or waiting on a turn back rail, and the sync panel tells a platform held so long that later trains move to another one, or have none. A train with a long wait (five minutes or more), or whose platform is taken, waits on a free turn back rail instead and comes out in time for its next service, so one platform takes more trains; duties are planned the same way, and the timetable check pairs services by the set and duty pairs first.
- **Services not running**: a timetable's services can be set not to run and run again later from the web editor's context menu, one or a chosen run of them; they keep their times, exceptions, links and orders, no train is sent for them, nothing is planned for them and they don't count in the timetable's clash check until they run again.
- **Depot orders kept to**: a service set to come out of or go back to a depot with no siding planned for it has no train, rather than one from anywhere; duties are planned to go back to the siding of their pool the game would send them to, shown as a forecast in the web editor; a train only goes back to a siding it has a way to, and an order to go back to a depot that can't be kept is pointed out. A train staying on the line overnight with no duty to go on with from where it stands waits there, shown in the sync panel with a button to take it straight back, instead of vanishing into its siding. The timetable's context menu sets the depot to go back to from a second-level menu.
- **Planned by what the rails measure**: duties change platform within a station only where the rails allow and take as long as measured along the way the train runs (in to where it turns back and out again, plus the least time to change ends, 30 s unless the network is set otherwise in the web editor); trains are back in their siding as long after their last stop as measured, out as measured, and a train staying out overnight goes on from the platform it stopped at. A train with nothing to do goes back once it has waited half an hour beyond the first trip it could run, so a line with more trips one way than the other doesn't pile trains up at one end; duties that don't settle into the same week each week say the trains they need aren't known rather than giving a number. Ways out of a siding and back are found on the track sections when a depot finds its paths, running the first and last platforms the way the line runs them, and the duties are planned with them at once; the duties' paths take the same ways, and a train goes back to the nearest siding of its pool in the depot. Duties already planned are kept while they still hold with new times, checked by the planner's own rules (every train turns round in time, waits only where it may, has a way out and back), so a restart or a small change in times leaves them as they are; one rule broken, by however little, has them planned again, and the log says which. A changed setting, such as the least time to change ends, doesn't change duties that still hold: the web editor suggests planning them again, and its depot menu's Plan duties again does it for the depot's whole planning unit. A train with time in hand runs at full speed and spends it standing at its next stop, unless it would be more than the network's early threshold early (30 s unless set otherwise in the web editor); only then does it cruise slower, to arrive that much early rather than on time, so it keeps some time in hand for a delay ahead. How much longer than planned trains were measured to take out, round and back is only a suggestion now: the web editor's depot dialog lists it, and only the times a player adopts there are added when the duties are planned. Several depots find their paths at once, as many as the machine has cores to spare (up to 8), sharing one copy of the rails, and the depots of an operating line found again together plan its duties once. The check for what changed every five seconds reads the world once for every depot rather than once per depot, and the station throat lock networks are worked out again only when depot paths change, so large networks no longer stall the server every few seconds. The rails are kept in the save as track sections, cut where rails branch, end, stop or are marked and named by a hash of their content; they follow rail edits piece by piece, are written as versions published by a root record, and are checked against the rails on loading. Route paths are made of legs, the way from one stop to the next found on those sections and kept in the save; a leg searches only as far as could change it, so legs to a terminus or along connecting lines no longer search the whole network, and a large network's legs load back at once; a leg is found again only when a section it touched changes or new track joins where it searched, so a new shortcut is taken at once. Pathfinding boundaries mark which line owns which track, and a route keeps to its own line's track; a connecting line owns its own part. A speed label stands for two speeds: trains may run 5 km/h above labels of 60 km/h and more, and timetables are planned 5 km/h below labels under 60, so a late train has room to catch up without a timetable margin; each timetable keeps the least performance it is timed for, set from the line's slowest vehicle, so changing vehicles doesn't change the timetable. Platforms, sidings (25 km/h) and turn back rails (65 km/h) run by their own speed limits rather than the rail's before them, so a train passing a platform slows to the platform's pass speed, 80 km/h unless the platform is set faster in its screen or the web editor. Stops at platforms shorter than the route's longest train are pointed out in the web editor and the server log, unless allowed for that stop. A platform can have trains stop centred on it or a number of blocks short of its exit end rather than at the end, in its screen or the web editor; trains turning back there set off from where their tail was, and the timetable times follow at once. The web editor's duty check, depot run lines and sync panel go by the same times and point out changes of platform with no way.
- **Depot rows, map picking, batch timetables**: the running diagram shows each depot as a row by the station it joins the line at, with each duty's run out and back drawn at the times the depot sends trains; a new line can be picked station by station on the whole network map, with the way the server finds between them and waypoints clicked on the map; timetables take Shift-chosen runs of services to space or delete, new services beside one, and services added every interval from a start to an end, chosen services dragged along the running diagram as a whole, and a table cleared.
- **Depots by duties**: a depot's routes are the lines it runs, in no order; the routes aren't joined into one path, so two routes that don't meet no longer stop the depot's path. Each siding can be given only some of the routes and a fleet, how many trains it sends out whatever its rails hold. Both are set in the web editor's "Edit depot…", which also shows a sync panel of whatever didn't take in the game. In the game's dashboard only the physical layer is meant to stay.
- **Route pickers**: in the depot's (and other) route lists, a line folded into one row opens and closes again when clicked.
- **Duty limits**: a duty may run up to 4096 services a day (was 128), a siding may have more duties than trains, and a turnaround too short at a terminal is pointed out when duties are generated.
- **Too few trains**: the duties then run every k-th service of each route, evenly through the day, instead of bunching the trains there are.
- **Services without a train**: on the web diagram a service no train of its depot's duties runs is drawn thin and grey (red with the "Services without a train" switch), and each route shows how many services have a train and how many don't.
- **Not configured depots**: the web editor marks a depot with no timetable and nothing to send trains for as not configured, not as the old logic.
- **Mixin check**: a test in the build scans ANTE's mixins for code that quietly replaces MTR's own (shadow fields with initialisers, methods overwriting the target's, injections that always cancel); the ones looked at are listed with why, and a new one fails the build.
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

Both versions need [Architectury API](https://modrinth.com/mod/architectury-api) for their loader, and the Fabric version also needs [Fabric API](https://modrinth.com/mod/fabric-api). Clients and servers must use jars with the same MTM_Logic version (shown in the mod list, and in the jar name `MTR3-MTM-<version>-...`), which is currently 0.8.1. It goes up whenever packets or saved data change. Jars with ANTE are about 136 MB.

The simulation component in `transit-sim` is a plain Java project without Minecraft (Java 8 by default, raised when needed), built into the Fabric and Forge jars. `./gradlew :transit-sim:test` runs its tests, and `./gradlew -p transit-sim test --offline` builds and tests it on its own, without Minecraft or a network once its dependencies are cached.

Trains on rails run by the project's own run layer (`transitsim.run`) instead of the rules taken over from MTR: one arbiter grants each train its movement authority section by section, single track stretches and station throats whole, and a platform a train turns back at both ways at once, so trains bunched after a delay don't lock up at a terminal; a train waits where its authority ends, at its stop if the way ahead is taken. A train driven by hand asks for its authority through the same arbiter and is braked short of where it ends. Clients move these trains only along the run segments the server sends, and keep them standing, doors as the server has them, when there is none.

The end-to-end check runs the whole chain without the game: `transitsim.sim.WholeDayTest` (a day of a line run by duties with every way of handling delays, `-Dbenchmark=true`), common `GameChainTest` (the game finds a depot's duty paths, the component runs them) and `SimulateDay --check` on a saved world's database, all checked for trains appearing or vanishing away from sidings, jumping, overlapping or being stuck.

Each build also makes a `-server.jar` of about 20 MB for dedicated servers. It is the same build without fonts, sounds, textures, models and the script engine of ANTE, which only clients use, so it always matches the full jar of the same build. Clients still need the full jar.

## License and credits

This project uses the MIT License. See [LICENSE](LICENSE).

- Minecraft Transit Railway: Copyright (c) 2022 Jonathan Ho
- Changes in this fork: MitsuhaQuQ
