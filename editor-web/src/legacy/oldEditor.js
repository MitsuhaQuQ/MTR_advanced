/**
 * What is left of the old editor's code. Until 2026-11-13 it was generated from the old page's editor.js; the old page
 * is gone and this is source now. It runs inside the new page as createLegacy(env): one closure with the old page's
 * state (data, draft, state, net, sim …), read and written live through the returned v, its functions through fn.
 *
 * What it still owns: the real-coordinates network map (drawNetwork and its picking, menus, pathfinding borders, the
 * ownership brush's hit test, a depot's way out or back, the dispatcher's trains on the map), the handlers on the old
 * tree and inspector elements the new page draws into (views/Navigator.tsx, Inspector.tsx), the data model's helpers the
 * new views still call (setData, depots, speed limits …) and the running diagram's layout numbers the new page measures
 * with. The rules moved out to src/core/ come in through env (engine.ts); the running diagram, the summary table and the
 * dispatcher's console are drawn by the new page, and this only tells it to draw again.
 *
 * The real map is to be rethought as a whole (MTR-代码说明与流程.md, 全网实际图：保留说明); until then, change it here
 * only for real faults.
 */
/* eslint-disable */
// @ts-nocheck
export function createLegacy(env) {
	const { MTREditor, openMenu, openMenuAt, closeMenu, buildMenu, fetchJson, sendJson, setTreeOpen, routeDiagramDialog, lineDiagramDialog, buildStations, buildServices, plansFor, toPlans, gameSource, findService, timesOf, currentLine, draftOf, draftEntry, tidyDraft, gameTimetable, fromGame, normalize, timetableOf, editTimetable, setStopException, syncExceptions, retimeService, setSuspended, removeService, setServiceOrder, changeTime, laterServices, moveServices, shiftLaterDialog, parseShift, respaceRange, setLink, clearTable, servicesToAdd, renumberTimetable, renumberLinksInto, inRange, rangeServices, chooseRange, toggleChosen, chooseLater, servicesToMove, timesSource, sendTimes, draftTimes, requestedTimes, timesTimer, canonDuties, dutiesOf, routeIsLoop, dutyOn, segmentKey, startOf, newDutyId, dutyIdOf, findDutySlot, dutyNumber, relinkOvernight, poolSidings, editDuties, dayGroup, dutyForEditing, segmentService, lastStopOf, segmentStart, segmentEnd, coverageNow, hasNoTrain, terminalNow, dutyAssignments, dutyProblems, nextSlotOf, dutyEndPlatform, dutyStartPlatform, minReverseMillis, turnTimeOf, stopLabel, stopHangers, renumberDuties, sendStopList, draftSettings, savedSettings, compareRailKeys, logicalLinesShown, usesLogicalLines, railLinesShown, railLogicalLine, railLogicalDrafted, draftSettingsPart, setRailLogicalLine, newLogicalId, deleteLogicalLine, draftOwnerOverrides, savedOwnerOverrides, railOwnersShown, setOwnerOverride, waitThresholdMillis, platformSpeedItems, platformStopItems, platformStationItems, rulesOf, sameVehicle, depotEntryOf, storeDepotEntry, setBoundary, setSimulation, scheduleSimulation, simulate, pollSimulation, setSimResult, simFromNow, simLate, simTooltip, dutyCheckText, loadConsole, toggleConsole, consoleTrains, consoleTrainName, consoleTrainState, consoleCommand, isLoop, loopEntryStop, loopRuns, generateDuties, replanDialog, placeTrainsDialog, deleteTypeDialog, stationWaitDialog, logicalDraftDialog, serviceOrderDialog, logicalLineDialog, ownersDialog, lineTracksDialog, finishPicking, deadheadMenu, stopsDialog, newLineDialog, newTypeDialog, speedLimitDialog, throughDialog, contingencyDialog, tableDialog, depotDialog, depotRulesDialog, vehicleDialog, dutyGroupsDialog, releasePlanDialog, turnOf, toggleTurnPlatform } = env;
	const DAY = 24 * 60 * 60 * 1000;
	// Checked often, so changes made in the game show soon; less often while the page is hidden
	const POLL_MILLIS = 1500, POLL_HIDDEN_MILLIS = 5000;
	// Room below the last station for the labels of trains carrying on to another route
	const LABEL_W = 120, AXIS_H = 28, MIN_ROW_GAP = 22, PAD_BOTTOM = 30;
	const DEPOT_ROW_ROOM = 26;
	// Pixels a minute: from the whole day at a glance to about a pixel a second (2026-11-09)
	const ZOOMS = [0.25, 0.5, 1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64];
	// The same as RouteTimetable.MAX_SERVICES and MAX_TABLES in the game
	const MAX_SERVICES = 4096, MAX_TABLES = 16;
	// RouteTimetable.MAX_LAYOVER_MILLIS and MAX_STAY_MILLIS: how long a train waits for its next lap before going back to the depot
	const MAX_LAYOVER_MILLIS = 30 * 60000;
	// 1 January 1970, the first day of the epoch, was a Thursday
	const FIRST_DAY_WEEKDAY = 3;

	const $ = id => document.getElementById(id);
	const css = name => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
	const escapeHtml = text => String(text).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&#39;" })[c]);
	const colorOf = value => "#" + (value & 0xFFFFFF).toString(16).padStart(6, "0");
	const mod = (a, b) => ((a % b) + b) % b;
	// Names written as "中文|English" show their first part
	const shortName = name => String(name || "").split("||")[0].split("|")[0] || tr("（未命名）");

	// The key from the address, read by the shell (shell.js), which also keeps which workspace shows; while the media
	// library shows, nothing here draws, the game is asked less often and Escape leaves the diagram's selection alone
	const key = MTREditor.key;
	const inTimetable = () => MTREditor.inTimetable();

	let data = null, revision = null, lastLoaded = 0, connection = "connecting";
	/**
	 * Changes not yet applied to the game, saved as they are made so closing the page or the game doesn't lose them. Each
	 * route's entry keeps the revision it was made from, so a change made in the game since is noticed before applying.
	 */
	let coverage = null;
	/** The services paired at their routes' ends on the day shown (terminalNow), until the data, the draft or the day changes. */
	let terminal = null;
	let draft = { routes: {}, created: [], duties: {}, boundaries: {}, depots: {}, depotRules: {}, platformStations: {}, platformSpeeds: {}, platformStops: {}, networkSettings: null, contingencies: {} }, draftLoaded = false, draftTimer = null, applyResult = null, applying = false;
	const state = {
		view: "diagram",
		line: null,
		route: null,
		// The selected service as "routeId@service"
		selected: null,
		// A route all of whose services are selected, by double clicking one, so a drag changes the whole table
		selectedRoute: null,
		// The stop a service leaves from, picked before choosing the stop of another route it joins
		pendingExit: null,
		// The train (库位) whose duty is being edited, as {depot, siding, index}: clicking a service adds it or takes it out
		slot: null,
		weekday: 0,
		split: true,
		treeOpen: true,
		openNodes: new Set(),
		pxPerMin: 2,
		viewStart: 0,
		scrollY: 0,
		// Which way a line's routes go in its table, down its stations (0) or up (1)
		lineWay: 0,
	};

	// ---------- Data ----------

	/** Saves the draft shortly after the last change, so typing doesn't write it on every key. */
	function saveDraft() {
		coverage = null;
		terminal = null;
		clearTimeout(draftTimer);
		draftTimer = setTimeout(() => sendJson("api/draft", "PUT", draft).catch(() => {}), 400);
		renderDraftState();
		scheduleSimulation();
	}

	// A message about the last thing done to duties, shown in the banner
	let dutyNotice = "";
	// The route to show once the game has made a new one
	let pendingOpen = null;
	/** The parts of a route the editor's changes send: the timetable, the stops and the route's settings sent with them. */
	const EDITOR_PARTS = ["timetable_services", "platform_ids", "is_light_rail_route"];
	/** Whether the game changed a part of the route the draft would send since the draft was made. */
	const draftOutOfDate = (entry, r) => entry.parts ? EDITOR_PARTS.some(part => ((r.parts || {})[part] || 0) !== (entry.parts[part] || 0)) : r.revision !== entry.base;
	const isManaged = r => draftOf(r) && "managed" in draftOf(r) ? draftOf(r).managed : r.editorManaged;
	const isConnecting = r => draftOf(r) && "connectingLine" in draftOf(r) ? draftOf(r).connectingLine : Boolean(r.connectingLine);
	/** A route's speed limit in km/h, 0 for none, as drafted or as the game has it (line speed limits). */
	const speedLimitOf = r => draftOf(r) && "speedLimit" in draftOf(r) ? draftOf(r).speedLimit : (r.speedLimit || 0);
	// A type marked for deletion stays in the game until the draft is applied
	const isDeleted = r => Boolean(draftOf(r) && draftOf(r).deleted);

	// Why changes to boundaries the game didn't take failed, kept until they are changed or applied again
	const boundaryErrors = {};
	const railKey = (a, b) => (BigInt(a) < BigInt(b) ? a + "," + b : b + "," + a);
	/** The rails of a connecting line released to other routes, as the draft has them. */
	const releasedOf = r => draftOf(r) && draftOf(r).connectingReleased ? draftOf(r).connectingReleased : (r.connectingReleased || []);

	/** Releases a rail of a connecting line to other routes, or takes it back, in the draft. */
	function toggleReleased(r, a, b) {
		const entry = draftEntry(r), key = railKey(a, b);
		const released = releasedOf(r).filter(rail => railKey(rail[0], rail[1]) !== key);
		if (released.length === releasedOf(r).length) released.push([a, b]);
		const original = new Set((r.connectingReleased || []).map(rail => railKey(rail[0], rail[1])));
		if (released.length === original.size && released.every(rail => original.has(railKey(rail[0], rail[1])))) delete entry.connectingReleased;
		else entry.connectingReleased = released;
		if (!entry.conflict) delete entry.error;
		tidyDraft(r.id);
		saveDraft();
		renderAll();
	}

	/** The menu of a rail in a track diagram: a pathfinding boundary, which only narrows searches, or a connecting line's rail. */
	function railMenu(e, r, rail, rerender, routesHere) {
		const { a, b, status } = rail, key = railKey(a, b);
		const wasBoundary = status === 3;
		const boundary = key in draft.boundaries ? draft.boundaries[key] : wasBoundary;
		const items = [[boundary ? tr("取消寻路边界") : tr("设为寻路边界"), () => { setBoundary(a, b, !boundary, wasBoundary); rerender(); }]];
		if (r && r.connectingLine && (status === 1 || status === 2)) {
			const released = releasedOf(r).some(other => railKey(other[0], other[1]) === key);
			items.push([released ? tr("取消放行") : tr("放行（别的线路可以走）"), () => { toggleReleased(r, a, b); rerender(); }]);
		}
		// On the whole network map: the lines whose paths run on the rail, each made a connecting line or not (2026-11-09)
		(routesHere || []).slice(0, 8).forEach(other => {
			const connecting = isConnecting(other);
			items.push([tr(connecting ? "取消联络线：{0}" : "设为联络线：{0}", routeTitle(other)), () => { setConnecting(other, !connecting); rerender(); }]);
		});
		openMenuAt(e, items);
	}

	/** Whether a planning unit's lines have trains enough, as the server worked out their duties by line. */
	function shortfallText(shortfall) {
		// -1: the duties don't repeat week after week, so what they need isn't known
		// Each group of vehicles short on its own, where there is more than one: trains aren't lent between them, so the totals
		// could show enough trains while one is short
		const pools = (shortfall.pools || []).filter(pool => pool.uncovered > 0);
		const main = (shortfall.pools || []).length > 1 && pools.length ? tr("{0}：", shortfall.lines) + pools.map(pool => pool.needed < 0 ? tr("{0} 算不出需要几列，现有 {1} 列，每天 {2} 班没有车", pool.label, pool.available, pool.uncovered) : tr("{0} 跑满需要 {1} 列，现有 {2} 列，每天 {3} 班没有车", pool.label, pool.needed, pool.available, pool.uncovered)).join(tr("；"))
			: shortfall.needed < 0 ? tr("{0}：运用排不成每周一样，算不出需要几列；现有 {1} 列，每天 {2} 班没有车", shortfall.lines, shortfall.available, shortfall.uncovered) : tr("{0}：跑满需要 {1} 列，现有 {2} 列，每天 {3} 班没有车", shortfall.lines, shortfall.needed, shortfall.available, shortfall.uncovered);
		// Services no number of trains could run aren't trains short: a way out of the sidings or a turn is missing
		// A depot reaching the line at more than one station plans no line with no rule saying which station its trains go by
		// (运用补完 step 2): said as that, not as a way missing
		const unset = (shortfall.entrancesUnset || []).map(key => key.split("|").slice(1).join("|") || key);
		const unrunnable = !shortfall.unrunnable ? "" : unset.length
			? tr("（其中每天 {0} 班没排：车厂接到线路的车站不止一个，{1} 还没有出入库规则指定从哪站进出；在车厂右键「出入库规则…」里加一条）", shortfall.unrunnable, unset.join("、"))
			: tr("（其中每天 {0} 班加多少车都跑不了：存车线出不去、也没有车能折返过去，要查出入库路线或折返）", shortfall.unrunnable);
		return main + unrunnable + (shortfall.suggested ? tr("，建议加到 {0}", shortfall.suggested) : "");
	}

	/**
	 * The settings changed since the auto duties kept were planned (round 4, step 4), as a sentence, or empty: the game keeps
	 * duties that still hold rather than plan them again of itself, and only suggests it.
	 */
	/** How many trains short, each group of vehicles counted on its own (-1 if not known), as the server works it out. */
	function missingTrains(shortfall) {
		if (shortfall.missing !== undefined) return shortfall.missing;
		return shortfall.needed < 0 ? -1 : Math.max(0, shortfall.needed - shortfall.available);
	}

	function replanText(shortfall) {
		if (!shortfall || !shortfall.replan) return "";
		const parts = shortfall.replan.split(";").map(item => {
			const [name, then, now] = item.split(":");
			return name === "minReverse" ? tr("换端最短时间 {0} 秒 → {1} 秒", Math.round(+then / 1000), Math.round(+now / 1000)) : name === "buffers" ? tr("采用的实测余量变了") : name === "ways" ? tr("轨道变了：出入库或站内换站台的路和排运用时不一样，现在没车的班次也许能排上") : name;
		});
		return tr("运用是按原来的设置排的（{0}）。现在的运用照样接得上，所以没有自动重排；按新设置重排可能更省车，需要时在车厂右键菜单里选“重新排运用”。", parts.join("，"));
	}

	/** The shortfalls of the depots running a route, each unit once. */
	function shortfallsOf(r) {
		const texts = new Set();
		(data.depots || []).forEach(depot => {
			if (depot.shortfall && depot.shortfall.uncovered > 0 && (depot.routes || []).some(id => data.routeById.get(id) && data.routeById.get(id).lineKey === r.lineKey)) texts.add(shortfallText(depot.shortfall));
		});
		return [...texts];
	}

	/** Drafts a route's speed limit in km/h, 0 for none (line speed limits). */
	function setSpeedLimit(r, speedLimit) {
		const entry = draftEntry(r);
		if (speedLimit === (r.speedLimit || 0)) delete entry.speedLimit;
		else entry.speedLimit = speedLimit;
		if (!entry.conflict) delete entry.error;
		tidyDraft(r.id);
	}

	/** A connecting line: other lines' paths keep off its rails, while services can run through it with passengers. */
	function setConnecting(r, connecting) {
		const entry = draftEntry(r);
		if (connecting === Boolean(r.connectingLine)) delete entry.connectingLine;
		else entry.connectingLine = connecting;
		if (!entry.conflict) delete entry.error;
		tidyDraft(r.id);
		saveDraft();
		renderAll();
	}

	function setManaged(r, managed) {
		const entry = draftEntry(r);
		if (managed === r.editorManaged) delete entry.managed;
		else entry.managed = managed;
		// A conflict stays until it is settled with its buttons, and keeps its message so the banner shows them
		if (!entry.conflict) delete entry.error;
		tidyDraft(r.id);
		saveDraft();
		renderAll();
	}

	/** Sends the draft to the game. What the game takes leaves the draft; what it doesn't stays, with the reason. */
	/** The draft as the changes to send to the game, and what each change is for, to put errors back on. */
	function draftOperations() {
		const operations = [], owners = [];
		Object.entries(draft.routes).forEach(([id, entry]) => {
			// A route with a conflict stays in the draft and isn't sent; it doesn't hold up the other routes
			if (entry.conflict) return;
			let base = entry.base;
			const parts = entry.parts;
			// Nothing else of a route being deleted is worth sending
			if (entry.deleted) {
				operations.push({ type: "deleteRoute", route: id, base, parts, force: Boolean(entry.force) });
				owners.push(id);
				return;
			}
			if (entry.platforms) {
				operations.push({ type: "platforms", route: id, base, parts, force: Boolean(entry.force), platforms: entry.platforms });
				owners.push(id);
				base = undefined;
			}
			if (entry.timetable) {
				operations.push({ type: "timetable", route: id, base, parts, force: Boolean(entry.force), timetable: entry.timetable });
				owners.push(id);
				// The timetable changes the revision, so what follows for the route doesn't check it again
				base = undefined;
			}
			if ("managed" in entry) {
				operations.push({ type: "managed", route: id, base, parts, force: Boolean(entry.force), managed: entry.managed });
				owners.push(id);
			}
			if ("connectingLine" in entry) {
				operations.push({ type: "connectingLine", route: id, base, parts, force: Boolean(entry.force), connectingLine: entry.connectingLine });
				owners.push(id);
			}
			if ("speedLimit" in entry) {
				operations.push({ type: "speedLimit", route: id, base, parts, force: Boolean(entry.force), speedLimit: entry.speedLimit });
				owners.push(id);
			}
			if (entry.connectingReleased) {
				operations.push({ type: "connectingRelease", route: id, base, parts, force: Boolean(entry.force), rails: entry.connectingReleased });
				owners.push(id);
			}
			if (entry.terminalPlatforms || entry.startPlatforms) {
				const r = data.routeById.get(id) || {};
				operations.push({ type: "turnPlatforms", route: id, base, parts, force: Boolean(entry.force), terminal: entry.terminalPlatforms || r.terminalPlatforms || [], start: entry.startPlatforms || r.startPlatforms || [] });
				owners.push(id);
			}
		});
		// What a depot runs goes first, so its duties are applied to the routes and sidings as drafted
		Object.entries(draft.depots || {}).forEach(([depotId, entry]) => {
			operations.push({ type: "depot", depot: depotId, base: entry.base, force: Boolean(entry.force), routes: entry.routes, cycleRoutes: entry.cycleRoutes, sidings: entry.sidings, poolFleets: entry.poolFleets || null, vehicleSettings: entry.vehicleSettings || null, serviceTypes: entry.serviceTypes || null, clearBuffers: Boolean(entry.clearBuffers), addedBuffers: entry.addedBuffers || null });
			owners.push({ depotOf: depotId });
		});
		// How a depot's trains go out and back (运用补完 step 2), before its duties, which are planned by them
		Object.entries(draft.depotRules || {}).forEach(([depotId, entry]) => {
			operations.push({ type: "depotRules", depot: depotId, rules: entry.rules });
			owners.push({ depotRulesOf: depotId });
		});
		Object.entries(draft.duties).forEach(([depotId, entry]) => {
			operations.push({ type: "duties", depot: depotId, duties: entry.duties });
			owners.push({ dutiesOf: depotId });
		});
		Object.entries(draft.boundaries || {}).forEach(([key, boundary]) => {
			const [a, b] = key.split(",");
			operations.push({ type: "pathBoundary", a, b, boundary });
			owners.push({ boundaryOf: key });
		});
		// A rail system's contingency plans (D2), all of them in place of the system's
		Object.entries(draft.contingencies || {}).forEach(([anchor, plans]) => {
			operations.push({ type: "contingencies", system: anchor, plans });
			owners.push({ contingenciesOf: anchor });
		});
		Object.entries(draft.platformSpeeds || {}).forEach(([platform, speed]) => {
			operations.push({ type: "platformSpeed", platform, speed });
			owners.push({ platformSpeedOf: platform });
		});
		Object.entries(draft.platformStops || {}).forEach(([platform, position]) => {
			operations.push({ type: "platformStop", platform, position });
			owners.push({ platformStopOf: platform });
		});
		if (draft.networkSettings) {
			operations.push(Object.assign({ type: "networkSettings" }, draft.networkSettings));
			owners.push({ networkSettingsOf: true });
		}
		Object.entries(draft.platformStations || {}).forEach(([platform, station]) => {
			operations.push({ type: "platformStation", platform, station });
			owners.push({ platformStationOf: platform });
		});
		draft.created.forEach(made => {
			operations.push({ type: "newRoute", route: made.source, mode: made.mode || "", line: made.lineName || "", name: made.name, serviceType: made.serviceType, color: made.color, stops: made.stops, services: made.services, table: made.table, depot: made.depot || "" });
			owners.push(made);
		});
		return { operations, owners };
	}

	function renderDraftState(...args) { return env.render("renderDraftState", args) }

	async function poll(once) {
		try {
			const result = await fetchJson("api/revision");
			if (result.revision !== revision) {
				const newData = await fetchJson("api/data");
				revision = result.revision;
				setData(newData);
			}
			connection = "ok";
		} catch (e) {
			connection = e.message === "401" ? "key" : e.message === "503" ? "world" : "offline";
		}
		renderBanner();
		renderStatus();
		renderDraftState();
		renderSyncButton();
		if (!once) setTimeout(poll, document.hidden || !inTimetable() ? POLL_HIDDEN_MILLIS : POLL_MILLIS);
	}

	/** The routes of each line, the stations of its diagram, and each route's services on the chosen day. */
	function setData(newData) {
		I18N.setLanguage(newData.language);
		data = newData;
		coverage = null;
		terminal = null;
		lastLoaded = Date.now();
		data.routeById = new Map(data.routes.map(r => [r.id, r]));
		const lines = new Map();
		data.routes.forEach(r => {
			if (!lines.has(r.lineKey)) lines.set(r.lineKey, { key: r.lineKey, name: r.line, color: r.lineColor, routes: [] });
			lines.get(r.lineKey).routes.push(r);
		});
		data.lines = [...lines.values()].sort((a, b) => a.name.localeCompare(b.name));
		data.lines.forEach(buildStations);
		buildTopology();
		// A route changed in the game since its draft was made is a conflict to settle while drafting, before applying
		Object.entries(draft.routes).forEach(([id, entry]) => {
			const r = data.routeById.get(id);
			if (r && entry.base && !entry.force && !entry.conflict && draftOutOfDate(entry, r)) {
				entry.conflict = true;
				entry.error = r.editors ? tr("游戏里的这条线路在草稿之后改过（{0} 正在编辑）", r.editors) : tr("游戏里的这条线路在草稿之后改过");
			}
		});
		if (!state.line || !lines.has(state.line)) state.line = data.lines.length ? data.lines[0].key : null;
		if (state.route && !data.routeById.has(state.route)) state.route = null;
		if (state.selected && !findService(state.selected)) state.selected = null;
		buildServices();
		if (pendingOpen && data.routeById.has(pendingOpen)) {
			const made = data.routeById.get(pendingOpen);
			pendingOpen = null;
			state.line = made.lineKey;
			state.route = made.id;
			state.view = "table";
		}
		renderAll();
	}

	// ---------- Times ----------

	// ---------- Editing ----------

	/** The first and last stops a service carries passengers at, which orders set on the running diagram can narrow. */
	const servedStops = s => [s.deployOrder ? s.deployOrder.stop : 0, s.returnOrder ? Math.min(s.returnOrder.stop, s.route.stops.length - 1) : s.route.stops.length - 1];

	const PATTERN_HINT = tr("填起始时刻、结束时刻和期望间隔（分钟，从起始时刻起每隔这么久一班，到结束时刻为止；这段时间里已有的班次按新间隔调整，挪动、补上或删掉多出的），或者只在起始时刻一栏写 07:10 + 10 * 00:03（07:10 起每 3 分钟一班，再加 10 班）");

	let tableMessage = "";

	// ---------- Menus and dialogs ----------
	let menu = null;
	let subMenu = null;
	document.addEventListener("click", e => { if (menu && !menu.contains(e.target) && !(subMenu && subMenu.contains(e.target))) closeMenu(); });
	// Right-click the same everywhere (2026-11-09): where the page has a menu, the handler of that place opens it and
	// takes the event; text fields and selected text keep the browser's menu, for copying and pasting; anywhere else no
	// menu at all, rather than the browser's sometimes. Registered on the document, it runs after those of the places.
	const browserMenuFields = "input:not([type=checkbox]):not([type=radio]):not([type=button]):not([type=submit]):not([type=range]):not([type=color]), textarea, [contenteditable]:not([contenteditable=false])";
	document.addEventListener("contextmenu", e => {
		if (e.defaultPrevented) return;
		closeMenu();
		const selection = getSelection();
		if (e.target.closest && e.target.closest(browserMenuFields) || selection && !selection.isCollapsed && selection.containsNode(e.target, true)) return;
		e.preventDefault();
	});
	document.addEventListener("keydown", e => { if (e.key === "Escape" && menu) closeMenu(); });
	addEventListener("scroll", e => { if (menu && !menu.contains(e.target) && !(subMenu && subMenu.contains(e.target))) closeMenu(); }, true);
	addEventListener("resize", () => closeMenu());
	addEventListener("blur", () => closeMenu());

	function setWeekday(weekday) {
		state.weekday = weekday;
		buildServices();
		if (state.selected && !findService(state.selected)) state.selected = null;
		renderAll();
	}

	// ---------- What the draft changes ----------

	// A time in the operating day as the local time of day
	const clock = (millis, seconds = false) => {
		const local = mod(millis + data.dayStartMillis + data.offsetMillis, DAY);
		const h = Math.floor(local / 3600000), m = Math.floor(local / 60000) % 60, sec = Math.floor(local / 1000) % 60;
		return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}${seconds && sec ? ":" + String(sec).padStart(2, "0") : ""}`;
	};

	const routeTitle = r => {
		const last = r.stops.length ? shortName(r.stops[r.stops.length - 1].name) : "";
		return shortName(r.name) + (last ? " → " + last : "");
	};

	// ---------- Tree ----------
	function renderTree(...args) { return env.render("renderTree", args) }

	function onTreeClick(target) {
		const el = target.closest(".node");
		if (!el) return;
		const { line, route, view, depot, slot, siding, ofDepot, layer, connecting, section, pool, logical, newLogical, generateLogical } = el.dataset;
		if (slot !== undefined) {
			selectSlot(ofDepot, siding, +slot);
			return;
		}
		// The layers of the whole network map, and a connecting line drawn over its owners
		if (layer) {
			net.focusLogical = null;
			setNetLayer(layer);
			openView("network", state.line, null);
			return;
		}
		if (logical) {
			net.focusLogical = logical;
			setNetLayer("owners");
			openView("network", state.line, null);
			return;
		}
		if (newLogical) {
			logicalLineDialog(null);
			return;
		}
		if (generateLogical) {
			logicalDraftDialog();
			return;
		}
		if (connecting) {
			const r = data.routeById.get(connecting);
			if (!r) return;
			setNetLayer("owners");
			openView("network", r.lineKey, r.id);
			return;
		}
		if (pool !== undefined) return;
		// The operating lines: a line opens its types, a type its stops to edit
		if (section === "lines") {
			if (route) {
				const r = data.routeById.get(route);
				if (!r) return;
				state.line = r.lineKey;
				state.route = r.id;
				renderAll();
				if (data.canEdit && r.stops.length > 1) stopsDialog(r);
			} else if (line) {
				if (!state.openNodes.delete("oline:" + line)) state.openNodes.add("oline:" + line);
				renderTree();
			}
			return;
		}
		if (line && view) {
			openView(view, line, null);
			return;
		}
		if (line) {
			const nodeKey = "line:" + line;
			if (state.line === line && !state.route && state.view === "diagram") {
				if (!state.openNodes.delete(nodeKey)) state.openNodes.add(nodeKey);
			} else {
				state.openNodes.add(nodeKey);
			}
			// The whole network view stays open, with the line's routes drawn over it
			openView(state.view === "network" ? "network" : "diagram", line, null);
		} else if (route && view) {
			openView(view, data.routeById.get(route).lineKey, route);
		} else if (route) {
			const nodeKey = "route:" + route;
			if (!state.openNodes.delete(nodeKey)) state.openNodes.add(nodeKey);
			if (state.route === route) state.route = null;
			renderTree();
		} else if (depot) {
			const nodeKey = "depot:" + depot;
			if (!state.openNodes.delete(nodeKey)) state.openNodes.add(nodeKey);
			renderTree();
		}
	}

	// ---------- Views ----------
	const canSplit = () => innerWidth >= 1100 && innerHeight >= 820;
	const showsDiagram = () => inTimetable() && (state.view === "diagram" || (state.split && canSplit() && state.view !== "network"));
	const showsTable = () => state.view === "table" || (state.split && canSplit() && state.view !== "network");

	function openView(view, lineKey, routeId) {
		if (lineKey !== state.line) state.selected = null;
		state.view = view;
		state.line = lineKey;
		state.route = routeId;
		if (state.selected && routeId && !state.selected.startsWith(routeId + "@")) state.selected = null;
		if (portrait.matches) setTreeOpen(false);
		renderAll();
	}

	// ---------- Whole network track view ----------
	/*
	 * Every rail of one transport mode from the game's map data (EditorNetwork): end to end through the few points along
	 * its curve, with platforms, sidings and the isolation layer, stations named once zoomed in, and the routes of the line
	 * chosen in the tree drawn over it. Right-click a rail to change its isolation, the same as in a route's track diagram.
	 */
	const NET_CELL = 64, NET_HIT_PX = 6, NET_STATION_SCALE = 0.6, NET_POLL_MS = 3000;
	// Zoomed out further than this many pixels a block, the network is drawn as a schematic: each run of track between two
	// junctions simplified to within a few pixels, with its straight runs set along the horizontal, vertical and 45°
	const NET_EXACT_SCALE = 2, NET_SIMPLIFY_PX = 10, NET_ALIGNED = 0.12;
	/**
	 * Whether the map is drawn as the track lies, not as a schematic: zoomed in, or while a way out or back of a depot is
	 * chosen on it, whose route is drawn as it runs and would otherwise leave the schematic's lines (user, 2026-11-12).
	 */
	const netExact = () => net.scale >= NET_EXACT_SCALE || Boolean(wayPick);
	const net = { data: null, version: -1, mode: "", scale: 0, cx: 0, cz: 0, grid: null, hover: -1, error: "", timer: null, drag: null, layer: "physical", focusLogical: null };
	// Which layer the map shows (web layers step 2): the physical track, or which lines own each section
	try { if (localStorage.getItem("mtr-editor-net-layer") === "owners") net.layer = "owners"; } catch (e) {}
	const netCanvas = $("net-canvas"), netWrap = $("net-wrap"), netCtx = netCanvas.getContext("2d");

	// Geometry belongs to immutable point arrays, so replacing network data naturally drops these caches.
	const netGeometryCache = new WeakMap(), netDetailCache = new WeakMap(), netRouteCache = new WeakMap();
	let netFramePending = false;
	function netGeometry(points, withPath = false) {
		let value = netGeometryCache.get(points);
		if (!value) {
			let minX = Infinity, minZ = Infinity, maxX = -Infinity, maxZ = -Infinity;
			for (let i = 0; i + 1 < points.length; i += 2) {
				minX = Math.min(minX, points[i]); maxX = Math.max(maxX, points[i]);
				minZ = Math.min(minZ, points[i + 1]); maxZ = Math.max(maxZ, points[i + 1]);
			}
			value = { minX, minZ, maxX, maxZ, x: points[0], z: points[1], path: null };
			netGeometryCache.set(points, value);
		}
		if (withPath && !value.path && typeof Path2D !== "undefined" && value.maxX - value.minX < 65536 && value.maxZ - value.minZ < 65536) {
			const path = new Path2D();
			path.moveTo(0, 0);
			for (let i = 2; i + 1 < points.length; i += 2) path.lineTo(points[i] - value.x, points[i + 1] - value.z);
			value.path = path;
		}
		return value;
	}
	function netDrawingPoints(points) {
		if (points.length <= 12 || netExact()) return points;
		let levels = netDetailCache.get(points);
		if (!levels) { levels = new Map(); netDetailCache.set(points, levels); }
		const level = netLevel();
		if (!levels.has(level)) {
			levels.set(level, simplifyPoints(points, 0.35 / Math.pow(2, level / 2)));
			if (levels.size > 6) levels.delete(levels.keys().next().value);
		}
		return levels.get(level);
	}
	/** One snapshot per frame and one resolution per section, never a whole-section scan per rail. */
	function netStyles() {
		const lines = logicalLinesShown(), effective = railLinesShown(), draftedLines = draftSettings().railLines || {};
		const draftedOwners = draftOwnerOverrides(), savedOwners = savedOwnerOverrides();
		const hasDraftOwners = Object.keys(draftedOwners).length > 0;
		const sections = new Map();
		const section = index => {
			const entry = net.ownerOf && net.ownerOf.get(index), id = entry ? "s:" + entry.section : "r:" + index;
			if (sections.has(id)) return sections.get(id);
			const keys = sectionOf(index).map(netRailKey);
			let best = null, lineDraft = false;
			if (lines.size) keys.forEach(key => {
				if (key in draftedLines) lineDraft = true;
				if (effective[key] != null && lines.has(effective[key]) && (best === null || compareRailKeys(key, best) < 0)) best = key;
			});
			let owners = null;
			if (hasDraftOwners && keys.some(key => key in draftedOwners)) {
				const selected = new Set();
				let any = false;
				keys.forEach(key => {
					const value = key in draftedOwners ? draftedOwners[key] : savedOwners[key];
					if (value == null) return;
					any = true; value.forEach(owner => selected.add(owner));
				});
				owners = { owners: [...selected], manual: any, drafted: true, auto: !any };
			}
			const value = { line: best === null ? null : lines.get(effective[best]), drafted: lineDraft, owners };
			sections.set(id, value);
			return value;
		};
		return {
			logical: lines.size > 0,
			line: index => section(index),
			owners: index => {
				const entry = net.ownerOf && net.ownerOf.get(index);
				const override = hasDraftOwners ? section(index).owners : null;
				return override || { owners: entry ? entry.owners : [], manual: Boolean(entry && entry.manual), drafted: false, auto: false };
			}
		};
	}

	/** Shows the physical track or the owners of the sections on the whole network map, and remembers it. */
	function setNetLayer(layer) {
		net.layer = layer === "owners" ? "owners" : "physical";
		try { localStorage.setItem("mtr-editor-net-layer", net.layer); } catch (error) {}
		net.hover = -1;
		renderNetworkTools();
	}

	async function loadNetwork(fresh) {
		try {
			const result = await sendJson("api/network", "POST", { mode: net.mode, fresh, version: fresh ? -1 : net.version });
			net.error = "";
			if (!result.same) {
				const modeChanged = !net.data || net.data.mode !== result.mode;
				net.data = result;
				net.version = result.version;
				net.mode = result.mode;
				indexNetwork();
				if (modeChanged || !net.scale) fitNetwork();
			}
		} catch (e) {
			net.error = tr("读取全网配线失败");
		}
		renderNetworkTools();
		drawNetwork();
	}

	/** Polls while the view is open, as the map data from the server comes in a while after it is asked for. */
	function watchNetwork() {
		const open = state.view === "network";
		if (open && !net.timer) {
			loadNetwork(true);
			net.timer = setInterval(() => { if (!document.hidden && inTimetable()) loadNetwork(false); }, NET_POLL_MS);
		} else if (!open && net.timer) {
			clearInterval(net.timer);
			net.timer = null;
		}
	}

	/** Each rail's points in order, its bounding box in a grid to find the rail under the mouse, and rails by node pair. */
	function indexNetwork() {
		const d = net.data;
		net.grid = new Map();
		net.byPair = new Map();
		net.nodeById = new Map();
		(d && d.nodes ? d.nodes : []).forEach((node, index) => net.nodeById.set(node[2], index));
		net.railPoints = [];
		if (!d || !d.rails) return;
		d.rails.forEach((rail, index) => {
			const a = d.nodes[rail[0]], b = d.nodes[rail[1]];
			const points = [a[0], a[1]];
			for (let k = 4; k + 1 < rail.length; k += 2) points.push(rail[k], rail[k + 1]);
			points.push(b[0], b[1]);
			net.railPoints.push(points);
			net.byPair.set(Math.min(rail[0], rail[1]) + "," + Math.max(rail[0], rail[1]), index);
			let minX = Infinity, minZ = Infinity, maxX = -Infinity, maxZ = -Infinity;
			for (let k = 0; k < points.length; k += 2) {
				minX = Math.min(minX, points[k]); maxX = Math.max(maxX, points[k]);
				minZ = Math.min(minZ, points[k + 1]); maxZ = Math.max(maxZ, points[k + 1]);
			}
			for (let gx = Math.floor(minX / NET_CELL); gx <= Math.floor(maxX / NET_CELL); gx++) {
				for (let gz = Math.floor(minZ / NET_CELL); gz <= Math.floor(maxZ / NET_CELL); gz++) {
					const key = gx + "," + gz;
					if (!net.grid.has(key)) net.grid.set(key, []);
					net.grid.get(key).push(index);
				}
			}
		});
		// The owners of each rail whose section has any, as the server worked them out, and the rails of each such section
		net.ownerOf = new Map();
		net.sectionRails = new Map();
		const owned = d.owners || {}, names = owned.names || [];
		(owned.rails || []).forEach(entry => {
			const [index, manual, section, logical, ...owners] = entry;
			net.ownerOf.set(index, { owners: owners.map(owner => names[owner]), manual: manual === 1, section, logical });
			if (!net.sectionRails.has(section)) net.sectionRails.set(section, []);
			net.sectionRails.get(section).push(index);
		});
		// Which connecting line a taken or released rail belongs to, for the release item of the menu
		net.connectingOf = new Map();
		// And every line whose path runs on a rail, for making it a connecting line from the map
		net.routesOf = new Map();
		(d.routes || []).forEach(route => {
			const r = data && data.routeById.get(route.id);
			if (!r) return;
			for (let i = 1; i < route.nodes.length; i++) {
				const index = net.byPair.get(Math.min(route.nodes[i - 1], route.nodes[i]) + "," + Math.max(route.nodes[i - 1], route.nodes[i]));
				if (index === undefined) continue;
				if (r.connectingLine) net.connectingOf.set(index, r);
				const here = net.routesOf.get(index) || [];
				if (!here.includes(r)) here.push(r);
				net.routesOf.set(index, here);
			}
		});
	}

	/**
	 * The runs of track between junctions, for the schematic: rails joined end to end through nodes with two rails, while
	 * they are the same kind and isolation, so platforms, sidings and isolated rails stay their own runs and stations stay
	 * where they are. Built again when the network or the draft's isolation changes.
	 */
	function netChains() {
		const d = net.data;
		const key = net.version + "|" + JSON.stringify(draft.boundaries || {}) + "|" + (d.routes || []).map(route => {
			const r = data && data.routeById.get(route.id);
			return r && r.connectingLine ? JSON.stringify(releasedOf(r)) : "";
		}).join("");
		if (net.chainKey === key && net.chains) return net.chains;
		const adjacent = new Map();
		d.rails.forEach((rail, index) => {
			for (const node of [rail[0], rail[1]]) {
				if (!adjacent.has(node)) adjacent.set(node, []);
				adjacent.get(node).push(index);
			}
		});
		const statuses = d.rails.map((rail, index) => netStatus(index));
		const used = new Uint8Array(d.rails.length);
		const chains = [];
		net.railChain = new Int32Array(d.rails.length);
		// The rail's points from one node to the other
		const along = (index, fromNode) => {
			const points = net.railPoints[index];
			if (d.rails[index][0] === fromNode) return points;
			const reversed = [];
			for (let k = points.length - 2; k >= 0; k -= 2) reversed.push(points[k], points[k + 1]);
			return reversed;
		};
		d.rails.forEach((rail, start) => {
			if (used[start]) return;
			used[start] = 1;
			const kind = rail[2], status = statuses[start];
			const same = index => !used[index] && d.rails[index][2] === kind && statuses[index] === status;
			// Walks on from a node while it joins exactly two rails
			const walk = (node, from) => {
				const run = [];
				for (;;) {
					const next = adjacent.get(node) || [];
					if (next.length !== 2) break;
					const index = next[0] === from ? next[1] : next[0];
					if (!same(index)) break;
					used[index] = 1;
					run.push([index, node]);
					node = d.rails[index][0] === node ? d.rails[index][1] : d.rails[index][0];
					from = index;
				}
				return run;
			};
			const forward = walk(rail[1], start), backward = walk(rail[0], start);
			const rails = [], points = [];
			const add = (index, fromNode) => {
				const p = along(index, fromNode);
				for (let k = points.length ? 2 : 0; k < p.length; k++) points.push(p[k]);
				rails.push(index);
			};
			// Backward runs from the start's first node away from it, so it is added the other way round
			for (let i = backward.length - 1; i >= 0; i--) {
				const [index, node] = backward[i];
				add(index, d.rails[index][0] === node ? d.rails[index][1] : d.rails[index][0]);
			}
			add(start, rail[0]);
			forward.forEach(([index, node]) => add(index, node));
			rails.forEach(index => { net.railChain[index] = chains.length; });
			chains.push({ rails, points, kind, status, drawn: new Map() });
		});
		net.chains = chains;
		net.chainKey = key;
		return chains;
	}

	/** The scale the schematic is worked out at, in steps of half a power of two, so it is kept while zooming a little. */
	const netLevel = () => Math.round(Math.log2(net.scale) * 2);

	/** A run of track as drawn at the scale: exact when zoomed in, otherwise simplified and set along 0°, 45° and 90°. */
	function chainShape(chain) {
		if (netExact()) return chain.points;
		const level = netLevel();
		if (!chain.drawn.has(level)) chain.drawn.set(level, octilinear(simplifyPoints(chain.points, NET_SIMPLIFY_PX / Math.pow(2, level / 2))));
		return chain.drawn.get(level);
	}

	/** Douglas–Peucker on x, z, x, z, ...: the ends stay, and points further than the tolerance from the line. */
	function simplifyPoints(points, tolerance) {
		const count = points.length / 2;
		if (count <= 2) return points;
		const kept = new Uint8Array(count);
		kept[0] = kept[count - 1] = 1;
		const stack = [[0, count - 1]];
		while (stack.length) {
			const [first, last] = stack.pop();
			let furthest = -1, at = -1;
			for (let i = first + 1; i < last; i++) {
				const distance = netSegmentDistance(points[i * 2], points[i * 2 + 1], points[first * 2], points[first * 2 + 1], points[last * 2], points[last * 2 + 1]);
				if (distance > furthest) {
					furthest = distance;
					at = i;
				}
			}
			if (furthest > tolerance) {
				kept[at] = 1;
				stack.push([first, at], [at, last]);
			}
		}
		const simplified = [];
		for (let i = 0; i < count; i++) if (kept[i]) simplified.push(points[i * 2], points[i * 2 + 1]);
		return simplified;
	}

	/**
	 * Each piece between two points set along the horizontal, vertical and 45°, as a line map draws it: a piece already
	 * close to one of them stays; another goes straight for half of what isn't diagonal, then diagonally, then straight.
	 */
	function octilinear(points) {
		const result = [points[0], points[1]];
		for (let k = 2; k + 1 < points.length; k += 2) {
			const x1 = points[k - 2], z1 = points[k - 1], x2 = points[k], z2 = points[k + 1];
			const dx = x2 - x1, dz = z2 - z1, ax = Math.abs(dx), az = Math.abs(dz), longer = Math.max(ax, az), shorter = Math.min(ax, az);
			if (shorter > NET_ALIGNED * longer && longer - shorter > NET_ALIGNED * longer) {
				const straight = (longer - shorter) / 2, sx = Math.sign(dx), sz = Math.sign(dz);
				const firstX = ax > az ? x1 + sx * straight : x1, firstZ = ax > az ? z1 : z1 + sz * straight;
				result.push(firstX, firstZ, firstX + sx * shorter, firstZ + sz * shorter);
			}
			result.push(x2, z2);
		}
		// Pieces one after the other going the same way are one piece, so a bend is one diagonal rather than steps
		const merged = [result[0], result[1]];
		const way = (x1, z1, x2, z2) => Math.round(Math.atan2(z2 - z1, x2 - x1) / (Math.PI / 4));
		for (let k = 2; k + 1 < result.length; k += 2) {
			const n = merged.length;
			if (n >= 4 && way(merged[n - 4], merged[n - 3], merged[n - 2], merged[n - 1]) === way(merged[n - 2], merged[n - 1], result[k], result[k + 1])) {
				merged[n - 2] = result[k];
				merged[n - 1] = result[k + 1];
			} else if (result[k] !== merged[n - 2] || result[k + 1] !== merged[n - 1]) {
				merged.push(result[k], result[k + 1]);
			}
		}
		return cutSteps(merged, way);
	}

	/**
	 * A right-angled step between two pieces, where one piece's straight end meets the next one's turned the other way
	 * (a bend split into two pieces, each set straight, diagonal, straight), cut across diagonally: the two short legs,
	 * close to each other in length and shorter than the pieces either side, become one diagonal joining those pieces.
	 */
	function cutSteps(points, way) {
		const length = (k) => Math.hypot(points[k + 2] - points[k], points[k + 3] - points[k + 1]);
		for (let k = 2; k + 3 < points.length; k += 2) {
			const before = way(points[k - 2], points[k - 1], points[k], points[k + 1]);
			const after = way(points[k], points[k + 1], points[k + 2], points[k + 3]);
			const turn = ((after - before) % 8 + 8) % 8;
			if (turn !== 2 && turn !== 6) continue;
			const leg1 = length(k - 2), leg2 = length(k), shorter = Math.min(leg1, leg2), longer = Math.max(leg1, leg2);
			const outside1 = k >= 4 ? length(k - 4) : 0, outside2 = k + 5 < points.length ? length(k + 2) : 0;
			if (outside1 > longer && outside2 > longer && shorter >= longer / 2) {
				points.splice(k, 2);
				k = Math.max(0, k - 4);
			}
		}
		// A short jog between two pieces going the same way, left where a straight end met a diagonal: gone, the two
		// pieces either side joined
		for (let k = 2; k + 5 < points.length; k += 2) {
			const before = way(points[k - 2], points[k - 1], points[k], points[k + 1]);
			const beyond = way(points[k + 2], points[k + 3], points[k + 4], points[k + 5]);
			if (before === beyond && length(k) * 2 < Math.min(length(k - 2), length(k + 2))) {
				points.splice(k + 2, 2);
				k = Math.max(0, k - 4);
			}
		}
		// The diagonal cut across and the diagonals either side going the same way are one piece
		const merged = [points[0], points[1]];
		for (let k = 2; k + 1 < points.length; k += 2) {
			const n = merged.length;
			if (n >= 4 && way(merged[n - 4], merged[n - 3], merged[n - 2], merged[n - 1]) === way(merged[n - 2], merged[n - 1], points[k], points[k + 1])) {
				merged[n - 2] = points[k];
				merged[n - 1] = points[k + 1];
			} else {
				merged.push(points[k], points[k + 1]);
			}
		}
		return merged;
	}

	function fitNetwork() {
		const d = net.data;
		if (!d || !d.nodes || !d.nodes.length) return;
		let minX = Infinity, minZ = Infinity, maxX = -Infinity, maxZ = -Infinity;
		d.nodes.forEach(n => { minX = Math.min(minX, n[0]); maxX = Math.max(maxX, n[0]); minZ = Math.min(minZ, n[1]); maxZ = Math.max(maxZ, n[1]); });
		const w = netWrap.clientWidth || 800, h = netWrap.clientHeight || 600;
		net.cx = (minX + maxX) / 2;
		net.cz = (minZ + maxZ) / 2;
		net.scale = Math.max(0.005, Math.min(8, Math.min((w - 40) / Math.max(1, maxX - minX), (h - 40) / Math.max(1, maxZ - minZ))));
	}

	const netX = x => (x - net.cx) * net.scale + netWrap.clientWidth / 2;
	const netZ = z => (z - net.cz) * net.scale + netWrap.clientHeight / 2;
	const netWorldX = px => (px - netWrap.clientWidth / 2) / net.scale + net.cx;
	const netWorldZ = pz => (pz - netWrap.clientHeight / 2) / net.scale + net.cz;

	// ---------- Section owners (web layers step 2) ----------
	const netRailKey = index => {
		const d = net.data, rail = d.rails[index];
		return railKey(d.nodes[rail[0]][2], d.nodes[rail[1]][2]);
	};
	/** The rails of a rail's section, as far as the page knows: a section no line owns isn't sent, so it is the rail alone. */
	const sectionOf = index => {
		const entry = net.ownerOf && net.ownerOf.get(index);
		return entry ? net.sectionRails.get(entry.section) : [index];
	};

	/** An owner as a person reads it: a line's name, or a connecting line's. */
	function ownerText(owner) {
		if (owner.startsWith("connecting:")) {
			const r = data && data.routeById.get(owner.slice("connecting:".length));
			return tr("联络线 {0}", r ? routeTitle(r) : owner.slice("connecting:".length));
		}
		const line = data && data.lines.find(l => l.key === owner);
		return line ? shortName(line.name) : owner.split("|").slice(1).join("|") || owner;
	}

	function ownerColor(owner) {
		if (owner.startsWith("connecting:")) return css("--connecting");
		const line = data && data.lines.find(l => l.key === owner);
		return line ? colorOf(line.color) : css("--muted");
	}

	// ---------- Logical lines (2026-11-09) ----------
	/*

	/** The menu of a rail on the ownership layer with logical lines: its section's logical line, and the connecting lines over it. */
	function logicalMenu(e, index) {
		const current = railLogicalLine(index);
		const lines = [...logicalLinesShown().values()].sort((a, b) => a.name.localeCompare(b.name));
		const items = [[tr("划入逻辑线路"), lines.map(line => [`${current && current.id === line.id ? "✓ " : ""}${line.name}${line.connecting ? tr("（联络线）") : ""}`, () => setRailLogicalLine(index, line.id)])],
			[tr("新建逻辑线路并划入…"), () => logicalLineDialog(null, id => setRailLogicalLine(index, id))]];
		if (current) items.push([tr("移出逻辑线路“{0}”", current.name), () => setRailLogicalLine(index, null)]);
		const d = net.data, rail = d.rails[index], a = d.nodes[rail[0]][2], b = d.nodes[rail[1]][2], key = railKey(a, b);
		const r = net.connectingOf.get(index) || null, status = rail[3];
		if (r && r.connectingLine && (status === 1 || status === 2)) {
			const released = releasedOf(r).some(other => railKey(other[0], other[1]) === key);
			items.push([released ? tr("取消放行") : tr("放行（别的线路可以走）"), () => { toggleReleased(r, a, b); drawNetwork(); }]);
		}
		openMenuAt(e, items);
	}

	/** The menu of a rail on the ownership layer: its section's owners, and the connecting lines over it. */
	function ownersMenu(e, index) {
		const d = net.data, rail = d.rails[index];
		const a = d.nodes[rail[0]][2], b = d.nodes[rail[1]][2], key = railKey(a, b);
		const shown = railOwnersShown(index);
		const saved = savedOwnerOverrides();
		const setByHand = shown.manual || sectionOf(index).map(netRailKey).some(other => other in saved || other in draftOwnerOverrides());
		const items = [[tr("归属线路…"), () => ownersDialog(index)], [tr("对所有线路开放"), () => setOwnerOverride(index, [])], [tr("改用逻辑线路：按现在的归属生成初稿…"), () => logicalDraftDialog()]];
		if (setByHand && !shown.auto) items.push([tr("恢复自动归属"), () => setOwnerOverride(index, null)]);
		// Connecting lines belong to this layer: releasing their rails, and making lines connecting lines
		const r = net.connectingOf.get(index) || null, status = rail[3];
		if (r && r.connectingLine && (status === 1 || status === 2)) {
			const released = releasedOf(r).some(other => railKey(other[0], other[1]) === key);
			items.push([released ? tr("取消放行") : tr("放行（别的线路可以走）"), () => { toggleReleased(r, a, b); drawNetwork(); }]);
		}
		(net.routesOf.get(index) || []).slice(0, 8).forEach(other => {
			const connecting = isConnecting(other);
			items.push([tr(connecting ? "取消联络线：{0}" : "设为联络线：{0}", routeTitle(other)), () => { setConnecting(other, !connecting); drawNetwork(); }]);
		});
		openMenuAt(e, items);
	}

	/** A rail's isolation with the draft's boundaries on top: 0, 1 taken by a connecting line, 2 released, 3 a boundary. */
	function netStatus(index) {
		const d = net.data, rail = d.rails[index];
		const key = railKey(d.nodes[rail[0]][2], d.nodes[rail[1]][2]);
		if (draft.boundaries && key in draft.boundaries) return draft.boundaries[key] ? 3 : (rail[3] === 3 ? 0 : rail[3]);
		const owner = net.connectingOf && net.connectingOf.get(index);
		if (owner && (rail[3] === 1 || rail[3] === 2)) return releasedOf(owner).some(other => railKey(other[0], other[1]) === key) ? 2 : 1;
		return rail[3];
	}

	function drawNetwork() {
		if (netFramePending || state.view !== "network" || !inTimetable()) return;
		netFramePending = true;
		requestAnimationFrame(() => { netFramePending = false; drawNetworkFrame(); });
	}

	function drawNetworkFrame() {
		if (state.view !== "network" || !inTimetable()) return;
		const ratio = devicePixelRatio || 1, w = netWrap.clientWidth, h = netWrap.clientHeight;
		if (!w || !h) return;
		if (netCanvas.width !== Math.round(w * ratio) || netCanvas.height !== Math.round(h * ratio)) {
			netCanvas.width = Math.round(w * ratio);
			netCanvas.height = Math.round(h * ratio);
			netCanvas.style.width = w + "px";
			netCanvas.style.height = h + "px";
		}
		const g = netCtx;
		g.setTransform(ratio, 0, 0, ratio, 0, 0);
		g.fillStyle = css("--panel");
		g.fillRect(0, 0, w, h);
		const d = net.data;
		if (!d || !d.rails || !d.rails.length) {
			g.fillStyle = css("--muted");
			g.font = `14px ${css("--font-ui")}`;
			g.fillText(net.error || (d ? tr("游戏里还没有这种运输方式的轨道，或地图数据还没传来") : tr("正在读取…")), 20, 30);
			return;
		}
		const left = netWorldX(-NET_HIT_PX), right = netWorldX(w + NET_HIT_PX), top = netWorldZ(-NET_HIT_PX), bottom = netWorldZ(h + NET_HIT_PX);
		const visible = points => {
			const box = netGeometry(points);
			return !(box.maxX < left || box.minX > right || box.maxZ < top || box.minZ > bottom);
		};
		const pickedRoutes = pickingRouteIds();
		const line = state.line, highlighted = (d.routes || []).filter(route => pickedRoutes ? pickedRoutes.has(route.id) : route.lineKey === line && (!state.route || state.route === route.id));
		const faded = highlighted.length > 0;
		const stroke = (points, color, width, dash) => {
			if (!visible(points)) return;
			points = netDrawingPoints(points);
			const shape = netGeometry(points, true), scale = net.scale;
			g.strokeStyle = color;
			if (shape.path) {
				const dashOffset = g.lineDashOffset;
				g.setTransform(ratio * scale, 0, 0, ratio * scale, ratio * ((shape.x - net.cx) * scale + w / 2), ratio * ((shape.z - net.cz) * scale + h / 2));
				g.lineWidth = width / scale;
				g.setLineDash((dash || []).map(value => value / scale));
				g.lineDashOffset = dashOffset / scale;
				g.stroke(shape.path);
				g.setTransform(ratio, 0, 0, ratio, 0, 0);
				g.lineDashOffset = dashOffset;
			} else {
				g.beginPath();
				g.moveTo((points[0] - net.cx) * scale + w / 2, (points[1] - net.cz) * scale + h / 2);
				for (let k = 2; k + 1 < points.length; k += 2) g.lineTo((points[k] - net.cx) * scale + w / 2, (points[k + 1] - net.cz) * scale + h / 2);
				g.lineWidth = width; g.setLineDash(dash || []); g.stroke();
			}
			g.lineWidth = width; g.setLineDash(dash || []);
		};
		g.lineCap = "round";
		g.lineJoin = "round";
		g.globalAlpha = faded ? 0.45 : 1;
		const plain = css("--grid-strong"), ink = css("--ink");
		const styles = net.layer === "owners" ? netStyles() : null;
		// Runs of track, each one kind and isolation, drawn exactly when zoomed in and as a schematic further out
		const chains = netChains();
		chains.forEach(chain => {
			const points = chainShape(chain);
			if (!visible(points)) return;
			// Stabling blocks (运用补完 step 4) brown and a little heavier, one train each
			stroke(points, chain.kind === 1 ? ink : chain.kind === 2 ? css("--siding") : chain.kind === 3 ? css("--stabling") : plain, chain.kind === 1 ? 3 : chain.kind === 3 ? 2.5 : 1.5);
		});
		g.globalAlpha = 1;
		// The ownership layer (web layers step 2): each rail in its owners' colours, striped where several lines own it;
		// set by hand with a dark edge, dotted while only in the draft; open to every line by hand grey and dashed
		if (net.layer === "owners" && styles.logical) {
			// By logical line (2026-11-09): its colour, connecting lines dashed, drafted sections with a dark dotted edge, one
			// logical line chosen in the tree drawn over the others faded
			d.rails.forEach((rail, index) => {
				const points = net.railPoints[index];
				if (!visible(points)) return;
				const { line, drafted } = styles.line(index);
				if (!line && !drafted) return;
				g.globalAlpha = net.focusLogical && (!line || line.id !== net.focusLogical) ? 0.25 : 1;
				if (drafted) stroke(points, ink, 6.5, [3, 3]);
				if (line) stroke(points, colorOf(line.color), 3.5, line.connecting ? [8, 4] : []);
			});
			g.globalAlpha = 1;
			g.setLineDash([]);
		} else if (net.layer === "owners") {
			const colors = new Map();
			d.rails.forEach((rail, index) => {
				const points = net.railPoints[index];
				if (!visible(points)) return;
				const shown = styles.owners(index);
				if (!shown.owners.length && !shown.manual && !shown.drafted) return;
				if (shown.manual || shown.drafted) stroke(points, ink, 6.5, shown.drafted ? [3, 3] : []);
				if (!shown.owners.length) {
					stroke(points, plain, 3, [5, 4]);
					return;
				}
				const owners = shown.owners.map(owner => {
					if (!colors.has(owner)) colors.set(owner, ownerColor(owner));
					return colors.get(owner);
				});
				owners.forEach((color, i) => {
					g.lineDashOffset = -i * 8;
					stroke(points, color, 3.5, owners.length > 1 ? [8, 8 * (owners.length - 1)] : []);
				});
				g.lineDashOffset = 0;
			});
			g.setLineDash([]);
		}
		// The isolation layer, in the colours of the track diagrams
		if (net.layer !== "owners") chains.forEach(chain => {
			const points = chainShape(chain);
			if (!chain.status || !visible(points)) return;
			// A pathfinding boundary only splits where searches look first, so it is drawn thin and dotted, apart from the
			// connecting lines, which keep trains off
			if (chain.status === 3) stroke(points, css("--boundary"), 1.5, [2, 4]);
			else if (chain.status === 1) stroke(points, css("--connecting"), 2.5, [6, 3]);
			else stroke(points, plain, 2, [3, 3]);
		});
		g.setLineDash([]);
		(d.boundaryNodes || []).forEach(index => {
			const n = d.nodes[index];
			// The boundary's nodes in the boundary's colour, as its rails (they were red, which the legend didn't say)
			g.fillStyle = css("--boundary");
			g.beginPath();
			g.arc(netX(n[0]), netZ(n[1]), 4, 0, Math.PI * 2);
			g.fill();
		});
		// The chosen line's routes along their paths: zoomed out, the runs of track they use as drawn
		if (!netExact()) {
			highlighted.forEach(route => {
				const runs = new Set();
				for (let i = 1; i < route.nodes.length; i++) {
					const index = net.byPair.get(Math.min(route.nodes[i - 1], route.nodes[i]) + "," + Math.max(route.nodes[i - 1], route.nodes[i]));
					if (index !== undefined) runs.add(net.railChain[index]);
				}
				runs.forEach(run => stroke(chainShape(chains[run]), colorOf(route.color), 4));
			});
		} else highlighted.forEach(route => {
			const cached = netRouteCache.get(route);
			if (cached && cached.network === d) { stroke(cached.points, colorOf(route.color), 4); return; }
			const points = [];
			for (let i = 0; i < route.nodes.length; i++) {
				if (i > 0) {
					const a = route.nodes[i - 1], b = route.nodes[i];
					const index = net.byPair.get(Math.min(a, b) + "," + Math.max(a, b));
					if (index !== undefined) {
						const railPoints = net.railPoints[index];
						const forward = d.rails[index][0] === a;
						for (let k = 2; k + 3 < railPoints.length; k += 2) {
							const at = forward ? k : railPoints.length - 2 - k;
							points.push(railPoints[at], railPoints[at + 1]);
						}
					}
				}
				const n = d.nodes[route.nodes[i]];
				points.push(n[0], n[1]);
			}
			netRouteCache.set(route, { network: d, points });
			stroke(points, colorOf(route.color), 4);
		});
		if (net.hover >= 0 && net.hover < d.rails.length) stroke(!netExact() ? chainShape(chains[net.railChain[net.hover]]) : net.railPoints[net.hover], css("--sel"), 4);
		if (net.scale >= NET_STATION_SCALE) {
			g.fillStyle = ink;
			g.font = `12px ${css("--font-ui")}`;
			g.textAlign = "center";
			(d.stations || []).forEach(station => {
				const x = netX(station.x), y = netZ(station.z);
				if (x > -50 && x < w + 50 && y > -20 && y < h + 20) g.fillText(shortName(station.name), x, y - 8);
			});
			g.textAlign = "start";
		}
		drawConsole(g, chains, stroke);
		drawPicking(g);
		drawWayPick(g);
	}

	/**
	 * @return the rail nearest the mouse within a few pixels, or -1; zoomed out, the nearest rail of the run of track drawn
	 * under the mouse
	 */
	function netRailAt(px, pz) {
		if (!net.data || !net.grid) return -1;
		const x = netWorldX(px), z = netWorldZ(pz), reach = NET_HIT_PX / net.scale;
		if (!netExact()) {
			let bestChain = null, bestChainDistance = reach;
			netChains().forEach(chain => {
				const p = chainShape(chain), box = netGeometry(p);
				if (x + reach < box.minX || x - reach > box.maxX || z + reach < box.minZ || z - reach > box.maxZ) return;
				for (let k = 0; k + 3 < p.length; k += 2) {
					const distance = netSegmentDistance(x, z, p[k], p[k + 1], p[k + 2], p[k + 3]);
					if (distance < bestChainDistance) {
						bestChainDistance = distance;
						bestChain = chain;
					}
				}
			});
			if (!bestChain) return -1;
			let best = bestChain.rails[0], bestDistance = Infinity;
			bestChain.rails.forEach(index => {
				const p = net.railPoints[index];
				for (let k = 0; k + 3 < p.length; k += 2) {
					const distance = netSegmentDistance(x, z, p[k], p[k + 1], p[k + 2], p[k + 3]);
					if (distance < bestDistance) {
						bestDistance = distance;
						best = index;
					}
				}
			});
			return best;
		}
		let best = -1, bestDistance = reach;
		const seen = new Set();
		for (let gx = Math.floor((x - reach) / NET_CELL); gx <= Math.floor((x + reach) / NET_CELL); gx++) {
			for (let gz = Math.floor((z - reach) / NET_CELL); gz <= Math.floor((z + reach) / NET_CELL); gz++) {
				(net.grid.get(gx + "," + gz) || []).forEach(index => {
					if (seen.has(index)) return;
					seen.add(index);
					const p = net.railPoints[index];
					for (let k = 0; k + 3 < p.length; k += 2) {
						const distance = netSegmentDistance(x, z, p[k], p[k + 1], p[k + 2], p[k + 3]);
						if (distance < bestDistance) {
							bestDistance = distance;
							best = index;
						}
					}
				});
			}
		}
		return best;
	}

	function netSegmentDistance(x, z, x1, z1, x2, z2) {
		const dx = x2 - x1, dz = z2 - z1, lengthSquared = dx * dx + dz * dz;
		const t = lengthSquared ? Math.max(0, Math.min(1, ((x - x1) * dx + (z - z1) * dz) / lengthSquared)) : 0;
		return Math.hypot(x - x1 - t * dx, z - z1 - t * dz);
	}

	function netRailText(index) {
		const d = net.data, rail = d.rails[index], status = netStatus(index);
		const a = d.nodes[rail[0]], b = d.nodes[rail[1]];
		const kind = rail[2] === 1 ? tr("站台") : rail[2] === 2 ? tr("存车线") : rail[2] === 3 ? tr("留置位（一列车，在游戏里用留置色的信号闭塞分区标出）") : tr("轨道");
		const isolation = status === 3 ? tr("寻路边界") : status === 1 ? tr("联络线独占") : status === 2 ? tr("联络线已放行") : "";
		const run = !netExact() && net.chains ? net.chains[net.railChain[index]].rails.length : 1;
		let owners = "";
		if (net.layer === "owners" && usesLogicalLines()) {
			const line = railLogicalLine(index), entry = net.ownerOf && net.ownerOf.get(index);
			const runs = entry && !railLogicalDrafted(index) && line && !line.connecting ? entry.owners.map(ownerText).join(tr("、")) : "";
			owners = " · " + (line ? tr("逻辑线路：{0}", line.name) + (line.connecting ? tr("（联络线，所有运营线路都能走）") : runs ? tr("；能走：{0}", runs) : entry && !railLogicalDrafted(index) ? tr("；没有运营线路走它") : "") : tr("不属于任何逻辑线路，所有运营线路都能走")) + (railLogicalDrafted(index) ? tr("（草稿）") : "");
		} else if (net.layer === "owners") {
			const shown = railOwnersShown(index);
			const how = shown.auto ? tr("草稿：恢复自动") : shown.drafted ? tr("草稿：手改") : shown.manual ? tr("手改") : tr("自动");
			owners = " · " + (shown.auto ? tr("应用后按边界和站台重新算归属（{0}）", how) : shown.owners.length ? tr("归属：{0}（{1}）", shown.owners.map(ownerText).join(tr("、")), how) : shown.manual || shown.drafted ? tr("所有线路都能走（{0}）", how) : tr("没有归属，所有线路都能走"));
		}
		return `${kind}${isolation ? " · " + isolation : ""}${owners} · ${Math.floor(a[0])}, ${Math.floor(a[1])} → ${Math.floor(b[0])}, ${Math.floor(b[1])}${run > 1 ? tr("（示意图里这一段有 {0} 条轨道，放大后逐条显示）", run) : ""}`;
	}

	/**
	 * The dispatcher's console over the network view (D6): the signal blocks taken and the trains on duties at the block
	 * their heads are in, from the game every two seconds, and commands for each train, sent at once.
	 */
	const consoleState = { open: false, data: null, timer: null, system: "", selected: "" };

	/** The index of a rail of the network view from its two nodes as the console gives them, or -1. */
	function consoleRail(pair) {
		if (!net.nodeById) return -1;
		const a = net.nodeById.get(pair[0]), b = net.nodeById.get(pair[1]);
		if (a === undefined || b === undefined) return -1;
		const index = net.byPair.get(Math.min(a, b) + "," + Math.max(a, b));
		return index === undefined ? -1 : index;
	}

	/** Blocks taken in red, blocks with a train of the system in amber, and each train's name at its head. */
	function drawConsole(g, chains, stroke) {
		if (!consoleState.open || !consoleState.data || !consoleState.data.available || !net.data) return;
		g.globalAlpha = 1;
		const shapeOf = index => !netExact() && chains ? chainShape(chains[net.railChain[index]]) : net.railPoints[index];
		const trains = consoleTrains();
		const trainBlocks = new Set(trains.map(train => train.block).filter(Boolean));
		(consoleState.data.blocks || []).forEach(block => {
			if (!block.occupied && !trainBlocks.has(block.id)) return;
			const color = trainBlocks.has(block.id) ? css("--connecting") : css("--warn");
			block.rails.forEach(pair => {
				const index = consoleRail(pair);
				if (index >= 0) stroke(shapeOf(index), color, 6);
			});
		});
		g.font = `bold 12px ${css("--font-ui")}`;
		g.textAlign = "center";
		trains.forEach(train => {
			const index = consoleRail(train.head);
			if (index < 0) return;
			const points = net.railPoints[index];
			// Halfway along the rail, between the points either side of its middle
			const half = points.length / 2, lower = Math.max(0, Math.floor((half - 1) / 2) * 2), upper = Math.min(points.length - 2, lower + 2);
			const x = netX((points[lower] + points[upper]) / 2), y = netZ((points[lower + 1] + points[upper + 1]) / 2);
			const text = consoleTrainName(train);
			g.fillStyle = train.id === consoleState.selected ? css("--sel") : train.late >= 300 ? css("--late") : css("--ink");
			g.fillText(text, x, y - 10);
		});
		g.textAlign = "start";
	}

	/** The dispatcher's console is the new page's (views/DispatchConsole.tsx): tells it to draw again. */
	function renderConsole() {
		env.render("renderConsole");
	}

	$("net-console-toggle").addEventListener("click", () => toggleConsole(!consoleState.open));

	function renderNetworkTools() {
		const d = net.data, select = $("net-mode");
		const modes = d && d.modes ? d.modes : [];
		const names = { TRAIN: tr("铁路"), BOAT: tr("船"), CABLE_CAR: tr("缆车"), AIRPLANE: tr("飞机") };
		const html = modes.map(mode => `<option value="${mode}"${mode === net.mode ? " selected" : ""}>${escapeHtml(names[mode] || mode)}</option>`).join("");
		if (select.innerHTML !== html) select.innerHTML = html;
		$("net-info").textContent = d && d.rails ? tr("{0} 段轨道，{1} 个车站", d.rails.length, (d.stations || []).length) : "";
		const layers = `<option value="physical"${net.layer === "physical" ? " selected" : ""}>${tr("物理线路")}</option><option value="owners"${net.layer === "owners" ? " selected" : ""}>${tr("区段归属")}</option>`;
		if ($("net-layer").innerHTML !== layers) $("net-layer").innerHTML = layers;
		const ready = !d || !d.owners || d.owners.ready;
		$("net-legend").textContent = net.layer === "owners" && usesLogicalLines()
			? tr("逻辑线路的颜色：属于这条逻辑线路　虚线：联络线　深色虚边：草稿里改过　无色：不属于任何逻辑线路，所有运营线路都能走")
			: net.layer === "owners"
			? tr("线路色：归属这条线路　条纹：几条线路共用　橙：联络线　深色边：手改（虚边：草稿）　灰虚线：手改为所有线路都能走　无色：所有线路都能走") + (ready ? "" : tr("（服务端还没算好归属）"))
			: tr("红：寻路边界　橙虚线：联络线独占　灰虚线：已放行　粗线：站台　黄：存车线　棕：留置位");
	}

	$("net-mode").addEventListener("change", e => { net.mode = e.target.value; net.version = -1; loadNetwork(false); });
	$("net-layer").addEventListener("change", e => {
		setNetLayer(e.target.value);
		renderAll();
	});
	$("net-fit").addEventListener("click", () => { fitNetwork(); drawNetwork(); });
	$("net-zoom-in").addEventListener("click", () => { net.scale = Math.min(32, net.scale * 1.5); drawNetwork(); });
	$("net-zoom-out").addEventListener("click", () => { net.scale = Math.max(0.002, net.scale / 1.5); drawNetwork(); });
	$("net-refresh").addEventListener("click", () => loadNetwork(true));
	netCanvas.addEventListener("wheel", e => {
		e.preventDefault();
		const rect = netCanvas.getBoundingClientRect(), px = e.clientX - rect.left, pz = e.clientY - rect.top;
		const x = netWorldX(px), z = netWorldZ(pz);
		net.scale = Math.max(0.002, Math.min(32, net.scale * (e.deltaY < 0 ? 1.25 : 0.8)));
		// The point under the mouse stays where it is
		net.cx = x - (px - netWrap.clientWidth / 2) / net.scale;
		net.cz = z - (pz - netWrap.clientHeight / 2) / net.scale;
		drawNetwork();
	}, { passive: false });
	netCanvas.addEventListener("mousedown", e => {
		if (e.button !== 0) return;
		net.drag = { x: e.clientX, z: e.clientY, cx: net.cx, cz: net.cz };
	});
	// While picking a new line, a click that didn't pan the map picks a station or a waypoint
	netCanvas.addEventListener("click", e => {
		if (wayPick && !net.dragged) {
			const box = netCanvas.getBoundingClientRect();
			wayPickAt(e, e.clientX - box.left, e.clientY - box.top);
			return;
		}
		if (!picking || net.dragged) return;
		const rect = netCanvas.getBoundingClientRect();
		pickAt(e, e.clientX - rect.left, e.clientY - rect.top);
	});
	addEventListener("mouseup", () => {
		net.drag = null;
		// Cleared after the click that ends a pan has been seen
		setTimeout(() => { net.dragged = false; }, 0);
	});
	netCanvas.addEventListener("mousemove", e => {
		if (net.drag) {
			net.dragged = Math.hypot(e.clientX - net.drag.x, e.clientY - net.drag.z) > 4;
			net.cx = net.drag.cx - (e.clientX - net.drag.x) / net.scale;
			net.cz = net.drag.cz - (e.clientY - net.drag.z) / net.scale;
			drawNetwork();
			return;
		}
		const rect = netCanvas.getBoundingClientRect();
		const hover = netRailAt(e.clientX - rect.left, e.clientY - rect.top);
		if (hover !== net.hover) {
			net.hover = hover;
			netCanvas.title = hover >= 0 ? netRailText(hover) + (data && data.canEdit ? net.layer === "owners" ? tr("；右键改归属和联络线") : tr("；右键设寻路边界") : "") : "";
			drawNetwork();
		}
	});
	netCanvas.addEventListener("contextmenu", e => {
		const rect = netCanvas.getBoundingClientRect();
		const index = netRailAt(e.clientX - rect.left, e.clientY - rect.top);
		const station = data && netStationAt(e.clientX - rect.left, e.clientY - rect.top);
		if (station && (index < 0 || Math.hypot(netX(station.x) - (e.clientX - rect.left), netZ(station.z) - (e.clientY - rect.top)) < 18)) {
			e.preventDefault();
			openMenuAt(e, [[tr("等候分界…"), data.canEdit ? () => stationWaitDialog(station) : null]]);
			return;
		}
		if (index < 0 || !data) return;
		e.preventDefault();
		if (!data.canEdit) {
			openMenuAt(e, [[tr("没有编辑权限"), null]]);
			return;
		}
		if (net.layer === "owners") {
			if (usesLogicalLines()) logicalMenu(e, index);
			else ownersMenu(e, index);
			return;
		}
		// The physical layer has the boundaries only: connecting lines are on the ownership layer (web layers step 2)
		const d = net.data, rail = d.rails[index];
		railMenu(e, null, { a: d.nodes[rail[0]][2], b: d.nodes[rail[1]][2], status: rail[3] }, drawNetwork, []);
	});
	addEventListener("resize", () => drawNetwork());

	// ---------- A new line chosen on the whole network map ----------
	/**
	 * Picking a new line's stops on the whole network map: stops in order (platform, station, whether passed, the nodes
	 * the way to the next goes through), the way of each leg as the server finds it (api/route-preview), and the leg
	 * whose waypoints clicks on nodes set.
	 */
	let picking = null;

	function startPicking() {
		picking = { mode: net.mode || "TRAIN", stops: [], legs: [], activeLeg: -1, color: css("--sel"), requestId: 0 };
		openView("network", state.line, null);
		dutyNotice = tr("在全网配线图上依次点车站；站里有多个站台时选一个。选好后在右侧完成。");
		renderAll();
	}

	function stopPicking() {
		picking = null;
		renderAll();
	}

	/** The platforms of a station of the map's kind of transport, for choosing which one a stop uses. */
	const stationPlatforms = stationId => (topo ? topo.platforms : []).filter(p => p.station === stationId && (!p.mode || p.mode === picking.mode));

	function addPickedStop(platform) {
		dutyNotice = tr("已加第 {0} 站：{1}", picking.stops.length + 1, `${shortName(platform.stationName) || "?"} ${platform.name || ""}`.trim());
		renderBanner();
		picking.stops.push({ platformId: platform.id, station: platform.station, name: `${shortName(platform.stationName) || "?"} ${platform.name || ""}`.trim(), pass: false, waypoints: [] });
		refreshPreview();
	}

	/** Asks the server for the way through the stops chosen, and draws it when it comes. */
	async function refreshPreview() {
		renderInspector();
		drawNetwork();
		if (!picking || picking.stops.length < 2) {
			if (picking) picking.legs = [];
			return;
		}
		const id = ++picking.requestId;
		try {
			const result = await sendJson("api/route-preview", "POST", { platforms: picking.stops.map(stop => stop.platformId), waypoints: picking.stops.map(stop => stop.waypoints) });
			if (!picking || id !== picking.requestId) return;
			picking.legs = result.legs || [];
			picking.error = result.error ? tr("服务端没有回答，稍后再试") : "";
		} catch (e) {
			if (picking) picking.error = tr("发送失败，游戏可能已经关闭");
		}
		renderInspector();
		drawNetwork();
	}

	/**
	 * The station on the map at this point, or null: its name and dot, or one of its platforms' rails, which is the
	 * nearest station to the rail's middle.
	 */
	function netStationAt(px, pz) {
		const stations = (net.data && net.data.stations) || [];
		let best = null, bestDistance = 18;
		stations.forEach(station => {
			const distance = Math.hypot(netX(station.x) - px, netZ(station.z) - pz);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = station;
			}
		});
		if (best) return best;
		const index = netRailAt(px, pz);
		const rail = index >= 0 ? net.data.rails[index] : null;
		if (!rail || rail[2] !== 1) return null;
		const a = net.data.nodes[rail[0]], b = net.data.nodes[rail[1]], mx = (a[0] + b[0]) / 2, mz = (a[1] + b[1]) / 2;
		let nearest = null, nearestDistance = 160;
		stations.forEach(station => {
			const distance = Math.hypot(station.x - mx, station.z - mz);
			if (distance < nearestDistance) {
				nearestDistance = distance;
				nearest = station;
			}
		});
		return nearest;
	}

	/** The rail node on the map at this point, as its id, or null. */
	function netNodeAt(px, pz) {
		let best = null, bestDistance = 8;
		((net.data && net.data.nodes) || []).forEach(node => {
			const distance = Math.hypot(netX(node[0]) - px, netZ(node[1]) - pz);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = node[2];
			}
		});
		return best;
	}

	/** A click on the map while picking: a station adds a stop, a node is a waypoint of the leg chosen. */
	function pickAt(e, px, pz) {
		if (picking.activeLeg >= 0) {
			const node = netNodeAt(px, pz);
			if (node) {
				const waypoints = picking.stops[picking.activeLeg].waypoints;
				const at = waypoints.indexOf(node);
				if (at >= 0) waypoints.splice(at, 1);
				else waypoints.push(node);
				refreshPreview();
				return;
			}
		}
		const station = netStationAt(px, pz);
		if (!station) {
			dutyNotice = tr("这里没有车站：点车站的名字、圆点，或者它的站台轨道");
			renderBanner();
			return;
		}
		const lastStop = picking.stops[picking.stops.length - 1];
		const platforms = stationPlatforms(station.id).filter(p => !lastStop || p.id !== lastStop.platformId);
		if (!platforms.length) {
			dutyNotice = lastStop && lastStop.station === station.id ? tr("{0} 已经是上一站了，点下一个车站", shortName(station.name)) : tr("{0} 没有这种运输方式的站台", shortName(station.name));
			renderBanner();
			return;
		}
		if (platforms.length === 1) {
			addPickedStop(platforms[0]);
			return;
		}
		openMenuAt(e, platforms.map(p => {
			const using = data.routes.filter(r => r.stops.some(stop => stop.platformId === p.id)).map(r => shortName(r.name));
			return [`${trackLabel(p.id, p.name, p.passingTrack)}${using.length ? " · " + using.slice(0, 3).join("、") : ""}`, () => addPickedStop(p)];
		}));
	}

	/** Over the map: the existing routes through the chosen stations stand out, then the new line's legs and stops. */
	function drawPicking(g) {
		if (!picking) return;
		const d = net.data, nodeById = new Map((d.nodes || []).map(node => [node[2], node]));
		const stationById = new Map((d.stations || []).map(station => [station.id, station]));
		g.save();
		picking.stops.forEach((stop, i) => {
			const next = picking.stops[i + 1], leg = picking.legs[i];
			if (next && leg && leg.found) {
				g.strokeStyle = i === picking.activeLeg ? css("--sel") : picking.color;
				g.lineWidth = i === picking.activeLeg ? 6 : 4;
				g.globalAlpha = 0.85;
				// Along the rails' shapes where the map has the rail, otherwise straight from node to node
				const indexOf = new Map(d.nodes.map((node, k) => [node[2], k]));
				g.beginPath();
				leg.nodes.forEach(([x, z, id], k) => {
					if (k > 0) {
						const a = indexOf.get(leg.nodes[k - 1][2]), b = indexOf.get(id);
						const rail = a !== undefined && b !== undefined ? net.byPair.get(Math.min(a, b) + "," + Math.max(a, b)) : undefined;
						if (rail !== undefined) {
							const railPoints = net.railPoints[rail], forward = d.rails[rail][0] === a;
							for (let j = 2; j + 3 < railPoints.length; j += 2) {
								const at = forward ? j : railPoints.length - 2 - j;
								g.lineTo(netX(railPoints[at]), netZ(railPoints[at + 1]));
							}
						}
						g.lineTo(netX(x), netZ(z));
					} else g.moveTo(netX(x), netZ(z));
				});
				g.stroke();
			} else if (next) {
				const a = stationById.get(stop.station), b = stationById.get(next.station);
				if (a && b) {
					g.strokeStyle = css("--warn");
					g.lineWidth = 2;
					g.globalAlpha = 1;
					g.setLineDash([6, 4]);
					g.beginPath();
					g.moveTo(netX(a.x), netZ(a.z));
					g.lineTo(netX(b.x), netZ(b.z));
					g.stroke();
					g.setLineDash([]);
					if (leg && leg.failure) {
						g.fillStyle = css("--warn");
						g.font = `12px ${css("--font-ui")}`;
						g.fillText(leg.failure, (netX(a.x) + netX(b.x)) / 2 + 6, (netZ(a.z) + netZ(b.z)) / 2);
					}
				}
			}
			stop.waypoints.forEach(nodeId => {
				const node = nodeById.get(nodeId);
				if (!node) return;
				g.globalAlpha = 1;
				g.fillStyle = css("--sel");
				g.fillRect(netX(node[0]) - 4, netZ(node[1]) - 4, 8, 8);
			});
		});
		picking.stops.forEach((stop, i) => {
			const station = stationById.get(stop.station);
			if (!station) return;
			const x = netX(station.x), y = netZ(station.z);
			g.globalAlpha = 1;
			g.fillStyle = stop.pass ? css("--panel") : picking.color;
			g.strokeStyle = picking.color;
			g.lineWidth = 2;
			g.beginPath();
			g.arc(x, y, 9, 0, Math.PI * 2);
			g.fill();
			g.stroke();
			g.fillStyle = stop.pass ? picking.color : css("--panel");
			g.font = `bold 11px ${css("--font-ui")}`;
			g.textAlign = "center";
			g.fillText(String(i + 1), x, y + 4);
		});
		g.restore();
	}

	/** The routes stopping at any station chosen so far, which the map shows over the others. */
	const pickingRouteIds = () => {
		if (!picking || !picking.stops.length) return null;
		const stations = new Set(picking.stops.map(stop => stop.station));
		return new Set(data.routes.filter(r => r.stops.some(stop => stations.has(stop.station))).map(r => r.id));
	};

	function onWayPickPanelClick(e) {
		if (!wayPick) return false;
		const targetButton = e.target.closest("[data-waypick-target]");
		if (e.target.closest("[data-waypick-done]")) finishWayPick(true);
		else if (e.target.closest("[data-waypick-cancel]")) finishWayPick(false);
		else if (targetButton) {
			wayPick.platform = targetButton.dataset.waypickTarget;
			refreshWayPick();
		} else if (e.target.closest("[data-waypick-clear]")) {
			wayPick.waypoints = [];
			wayPick.turned = new Set();
			refreshWayPick();
		} else return false;
		return true;
	}

	$("net-waypick").addEventListener("click", onWayPickPanelClick);

	$("inspector").addEventListener("click", e => {
		if (onWayPickPanelClick(e)) return;
		if (!picking) return;
		const button = e.target.closest("[data-pick-up], [data-pick-down], [data-pick-remove], [data-pick-leg], [data-pick-finish], [data-pick-cancel], [data-pick-old]");
		if (!button) return;
		const stops = picking.stops;
		if (button.dataset.pickUp !== undefined) {
			const i = +button.dataset.pickUp;
			if (i > 0) [stops[i - 1], stops[i]] = [stops[i], stops[i - 1]];
		} else if (button.dataset.pickDown !== undefined) {
			const i = +button.dataset.pickDown;
			if (i < stops.length - 1) [stops[i + 1], stops[i]] = [stops[i], stops[i + 1]];
		} else if (button.dataset.pickRemove !== undefined) {
			stops.splice(+button.dataset.pickRemove, 1);
			picking.activeLeg = -1;
		} else if (button.dataset.pickLeg !== undefined) {
			const i = +button.dataset.pickLeg;
			picking.activeLeg = picking.activeLeg === i ? -1 : i;
			renderInspector();
			drawNetwork();
			return;
		} else if (button.dataset.pickFinish !== undefined) {
			finishPicking();
			return;
		} else if (button.dataset.pickCancel !== undefined) {
			stopPicking();
			return;
		} else if (button.dataset.pickOld !== undefined) {
			picking = null;
			newLineDialog();
			return;
		}
		refreshPreview();
	});
	$("inspector").addEventListener("change", e => {
		if (picking && e.target.dataset.pickPass !== undefined) {
			picking.stops[+e.target.dataset.pickPass].pass = e.target.checked;
			drawNetwork();
		}
	});

	// ---------- A depot's rules for going out and back (运用补完 step 2) ----------

	/** The lines a depot runs, by key, with a name to show. */
	function depotLines(depot) {
		const lines = new Map();
		(depot.routes || []).forEach(id => {
			const r = data.routeById.get(id);
			if (r && !lines.has(r.lineKey)) lines.set(r.lineKey, shortName(r.line) || r.lineKey);
		});
		return [...lines.entries()];
	}

	/** The rules being edited in the dialog, kept while the dialog is closed to choose on the map. */
	let rulesEditing = null;
	/**
	 * Choosing a rule's station or a deadhead path's way on the whole network map: the depot and siding, what is chosen,
	 * and the server's answer for it.
	 */
	let wayPick = null;

	/**
	 * The platform a way out ends at, or a way back starts from, for a rule: the first (or last) stop of a route of its
	 * lines the depot runs.
	 */
	function rulePlatform(depot, lines, out) {
		for (const id of depot.routes || []) {
			const r = data.routeById.get(id);
			if (r && r.stops.length > 1 && (!lines.length || lines.includes(r.lineKey))) return (out ? r.stops[0] : r.stops[r.stops.length - 1]).platformId;
		}
		return "0";
	}

	/**
	 * Where the rule's trains go out to, or come back from, as the running diagram has them on the day shown: the first stop
	 * of each duty's first service (the last stop of its last, back), or of every service where the depot has no duties;
	 * by platform, the earliest first (the latest last, back), with how many and when (2026-11-09).
	 */
	function ruleTargets(depot, lines, out) {
		const byPlatform = new Map();
		const add = (stop, at) => {
			const target = byPlatform.get(stop.platformId) || { platform: stop.platformId, name: stop.name, count: 0, first: Infinity, last: -Infinity };
			target.count++;
			target.first = Math.min(target.first, at);
			target.last = Math.max(target.last, at);
			byPlatform.set(stop.platformId, target);
		};
		const runs = r => r && (depot.routes || []).includes(r.id) && (!lines.length || lines.includes(r.lineKey));
		const duties = dutiesOf(depot);
		let fromDuties = false;
		if (duties.mode === "diagram") {
			depot.sidings.forEach(siding => (duties.sidings[siding.id] || []).forEach(slot => {
				const day = dutyOn(slot, state.weekday);
				if (!day || !day.segments.length) return;
				const segment = out ? day.segments[0] : day.segments[day.segments.length - 1];
				const service = segmentService(segment);
				if (!service || !runs(service.route)) return;
				const k = out ? segment.from : lastStopOf(segment, service.route);
				const times = timesOf(service)[k];
				if (!times || !service.route.stops[k]) return;
				add(service.route.stops[k], out ? times[1] : times[0]);
				fromDuties = true;
			}));
		}
		if (!fromDuties) {
			(depot.routes || []).map(id => data.routeById.get(id)).filter(runs).forEach(r => (r.services || []).filter(service => !service.suspended).forEach(service => {
				const k = out ? 0 : r.stops.length - 1;
				const times = timesOf(service)[k];
				if (times && r.stops[k]) add(r.stops[k], out ? times[1] : times[0]);
			}));
		}
		return [...byPlatform.values()].sort((a, b) => out ? a.first - b.first : b.last - a.last);
	}

	function startWayPick(depot, target) {
		const value = rulesEditing.value;
		const siding = depot.sidings[0];
		if (!siding) {
			dutyNotice = tr("这个车厂没有存车线");
			depotRulesDialog(depot, value);
			return;
		}
		const rule = target.rule !== undefined ? value.rules[target.rule] : null;
		const path = target.path !== undefined ? value.paths[target.path] : null;
		// A rule's way: the tracks it goes by, kept as a path of its own; where to, as the running diagram has it
		const rulePath = rule ? value.paths.find(each => each.id === (target.out ? rule.outPath : rule.backPath)) : null;
		const targets = rule ? ruleTargets(depot, rule.lines, target.out) : [];
		wayPick = {
			depot: depot.id, siding: siding.id, target, out: target.out, requestId: 0, result: null, error: "", targets,
			entrance: rule && !rulePath ? (target.out ? rule.outEntrance : rule.backEntrance) : "0",
			platform: path ? (target.out ? path.to : path.from) : targets.length ? targets[0].platform : rulePlatform(depot, rule ? rule.lines : [], target.out),
			waypoints: path ? path.waypoints.slice() : rulePath ? rulePath.waypoints.slice() : [],
		};
		if (wayPick.platform === "0") wayPick.platform = rulePlatform(depot, [], target.out);
		openView("network", state.line, null);
		dutyNotice = path ? tr("在全网配线图上依次点回送路径途经的节点；点车站换终点。右侧显示路线和用时。") : tr("按行车顺序在全网配线图上点{0}要经过的轨道；再点同一段换方向，点第三次去掉。右上角选运行图上从哪一站{1}，并显示路线和用时。", target.out ? tr("出库") : tr("回库"), target.out ? tr("发车") : tr("回来"));
		refreshWayPick();
	}

	/** Asks the server for the stations the siding reaches and the way as chosen, and draws them when they come. */
	async function refreshWayPick() {
		if (!wayPick) return;
		const id = ++wayPick.requestId;
		try {
			const result = await sendJson("api/depot-way-preview", "POST", { depot: wayPick.depot, siding: wayPick.siding, platform: wayPick.platform, out: wayPick.out, entrance: wayPick.entrance, waypoints: wayPick.waypoints });
			if (!wayPick || id !== wayPick.requestId) return;
			wayPick.result = result;
			wayPick.error = result.error ? tr("服务端没有回答，稍后再试") : "";
		} catch (e) {
			if (wayPick) wayPick.error = tr("发送失败，游戏可能已经关闭");
		}
		renderInspector();
		drawNetwork();
	}

	/** A click on the map while choosing: a station the siding reaches, a node of the path, or another end. */
	function wayPickAt(e, px, pz) {
		const choosingPath = wayPick.target.path !== undefined;
		if (!choosingPath) {
			toggleWayRail(netRailAt(px, pz));
			return;
		}
		if (choosingPath) {
			const node = netNodeAt(px, pz);
			if (node) {
				const at = wayPick.waypoints.indexOf(node);
				if (at >= 0) wayPick.waypoints.splice(at, 1);
				else if (wayPick.waypoints.length < 64) wayPick.waypoints.push(node);
				refreshWayPick();
				return;
			}
		}
		const station = netStationAt(px, pz);
		if (!station) return;
		if (!choosingPath) {
			const entrances = wayPick.result && wayPick.result.entrances || [];
			if (!entrances.includes(station.id)) {
				dutyNotice = tr("{0} 不是这个车厂的出入口；点高亮的车站", shortName(station.name));
				renderBanner();
				return;
			}
			wayPick.entrance = station.id;
			refreshWayPick();
			return;
		}
		// Another end of the path: the station's platform of the depot's routes
		const platforms = (topo ? topo.platforms : []).filter(p => p.station === station.id);
		if (platforms.length) {
			wayPick.platform = platforms[0].id;
			refreshWayPick();
		}
	}

	/**
	 * A rule's way goes by a track clicked (2026-11-09): its two nodes in the order the train runs them, the way shown now
	 * if it runs there; a second click turns it round, a third takes it away.
	 */
	function toggleWayRail(index) {
		if (index < 0 || !net.data) return;
		const rail = net.data.rails[index];
		const a = net.data.nodes[rail[0]][2], b = net.data.nodes[rail[1]][2];
		const points = wayPick.waypoints;
		let at = -1;
		for (let k = 0; k + 1 < points.length; k += 2) {
			if (points[k] === a && points[k + 1] === b || points[k] === b && points[k + 1] === a) at = k;
		}
		wayPick.turned = wayPick.turned || new Set();
		const key = Math.min(rail[0], rail[1]) + "," + Math.max(rail[0], rail[1]);
		if (at >= 0 && !wayPick.turned.has(key)) {
			points.splice(at, 2, points[at + 1], points[at]);
			wayPick.turned.add(key);
		} else if (at >= 0) {
			points.splice(at, 2);
			wayPick.turned.delete(key);
		} else if (points.length < 64) {
			// As the way shown runs it, where it does
			const shown = (wayPick.result && wayPick.result.nodes) || [];
			const nodeA = net.data.nodes[rail[0]], nodeB = net.data.nodes[rail[1]];
			const indexOf = node => shown.findIndex(([x, z]) => Math.abs(x - node[0]) < 1 && Math.abs(z - node[1]) < 1);
			const ia = indexOf(nodeA), ib = indexOf(nodeB);
			points.push(...(ia >= 0 && ib >= 0 && ib < ia ? [b, a] : [a, b]));
		}
		refreshWayPick();
	}

	/** Over the map: the stations the siding reaches, ringed, and the way as chosen. */
	function drawWayPick(g) {
		if (!wayPick || !net.data) return;
		const result = wayPick.result || {};
		const stationById = new Map((net.data.stations || []).map(station => [station.id, station]));
		g.save();
		(result.entrances || []).forEach(id => {
			const station = stationById.get(id);
			if (!station) return;
			g.strokeStyle = id === wayPick.entrance ? css("--sel") : css("--warn");
			g.lineWidth = 3;
			g.beginPath();
			g.arc(netX(station.x), netZ(station.z), 14, 0, Math.PI * 2);
			g.stroke();
		});
		if (result.nodes && result.nodes.length > 1) {
			g.strokeStyle = css("--sel");
			g.lineWidth = 5;
			g.globalAlpha = 0.85;
			g.beginPath();
			result.nodes.forEach(([x, z], k) => k ? g.lineTo(netX(x), netZ(z)) : g.moveTo(netX(x), netZ(z)));
			g.stroke();
		}
		const nodeById = new Map((net.data.nodes || []).map(node => [node[2], node]));
		g.globalAlpha = 1;
		wayPick.waypoints.forEach((id, k) => {
			const node = nodeById.get(id);
			if (!node) return;
			g.fillStyle = css("--sel");
			g.beginPath();
			g.arc(netX(node[0]), netZ(node[1]), 6, 0, Math.PI * 2);
			g.fill();
			g.fillStyle = css("--panel");
			g.font = `10px ${css("--font-num")}`;
			g.textAlign = "center";
			g.fillText(String(k + 1), netX(node[0]), netZ(node[1]) + 3);
		});
		g.restore();
	}

	/** Puts what was chosen into the rules being edited, and goes back to the dialog. */
	function finishWayPick(keep) {
		const depot = depotById(wayPick.depot);
		const value = rulesEditing.value;
		if (keep) {
			const target = wayPick.target;
			if (target.rule !== undefined) {
				const rule = value.rules[target.rule];
				const pathKey = wayPick.out ? "outPath" : "backPath";
				let path = value.paths.find(each => each.id === rule[pathKey]);
				if (wayPick.waypoints.length) {
					// The tracks clicked, as the rule's own path, which says where its trains reach the line too
					if (!path) {
						path = { id: String(Date.now()), name: tr("规则 {0} {1}", target.rule + 1, wayPick.out ? tr("出库") : tr("回库")), kind: wayPick.out ? "out" : "back", from: "0", to: "0", waypoints: [] };
						value.paths.push(path);
						rule[pathKey] = path.id;
					}
					path.waypoints = wayPick.waypoints.slice();
					path.from = wayPick.out ? wayPick.siding : wayPick.platform;
					path.to = wayPick.out ? wayPick.platform : wayPick.siding;
					if (wayPick.out) rule.outEntrance = "0";
					else rule.backEntrance = "0";
				} else {
					rule[pathKey] = "0";
				}
			} else {
				const path = value.paths[target.path];
				path.waypoints = wayPick.waypoints.slice();
				if (wayPick.out) {
					path.from = wayPick.siding;
					path.to = wayPick.platform;
				} else {
					path.from = wayPick.platform;
					path.to = wayPick.siding;
				}
			}
		}
		wayPick = null;
		renderAll();
		if (depot) depotRulesDialog(depot, value);
	}

	// ---------- Running diagram ----------
	// The box the new running diagram lies over: the old functions measure the diagram by it
	const wrap = $("canvas-wrap");
	let layout = null, drawn = [], handles = [];

	// ---------- Other lines on this line's track (web layers step 3) ----------
	/**
	 * Services of other lines drawn on a line's running diagram where they run on its track: a leg between two of their
	 * stops, both stations of this line's diagram next to each other, whose rails between the two (their platforms when
	 * there are none between) are rails this line's routes run on too. Read only: they are changed in their own line.
	 */
	let showAccess = true;
	try { showAccess = localStorage.getItem("mtr-editor-show-access") !== "0"; } catch (e) {}
	const access = { key: "", legs: [], askedMillis: 0 };

	/** Has the whole network map's data, which has the routes' paths, read for a kind of transport, once in a while. */
	function ensureNetworkFor(mode) {
		const has = net.data && net.data.mode === mode && net.data.routes && net.data.routes.length;
		if (has || net.loadingFor || Date.now() - access.askedMillis < 5000) return;
		access.askedMillis = Date.now();
		net.loadingFor = mode;
		net.mode = mode;
		loadNetwork(!net.data).finally(() => {
			net.loadingFor = "";
			access.key = "";
			draw();
		});
	}

	function accessLegs(line) {
		const mode = line.key.split("|")[0] || "TRAIN";
		ensureNetworkFor(mode);
		const d = net.data;
		if (!d || d.mode !== mode || !d.routes || !net.byPair) return [];
		const key = [line.key, net.version, line.rows.length, data.routes.length].join("/");
		if (access.key !== key) {
			access.key = key;
			access.legs = findAccessLegs(line, d);
		}
		return access.legs;
	}

	function findAccessLegs(line, d) {
		const stationOf = stop => stop.station || "?" + stop.name;
		const railOf = (nodes, k) => net.byPair.get(Math.min(nodes[k - 1], nodes[k]) + "," + Math.max(nodes[k - 1], nodes[k]));
		// The rails this line's routes run on
		const own = new Set();
		d.routes.filter(route => route.lineKey === line.key).forEach(route => {
			for (let k = 1; k < route.nodes.length; k++) {
				const rail = railOf(route.nodes, k);
				if (rail !== undefined) own.add(rail);
			}
		});
		const rowsOf = new Map();
		line.rows.forEach((row, i) => rowsOf.set(row.id, (rowsOf.get(row.id) || []).concat(i)));
		const nextTo = (from, to) => {
			for (const a of from) for (const b of to) if (Math.abs(a - b) === 1 && line.rows[a].section === line.rows[b].section) return [a, b];
			return null;
		};
		const result = [];
		d.routes.forEach(route => {
			const r = data.routeById.get(route.id);
			if (!r || r.lineKey === line.key || !route.stopNodes) return;
			const legs = [];
			for (let i = 0; i + 1 < r.stops.length; i++) {
				const a = route.stopNodes[i], b = route.stopNodes[i + 1];
				if (a === undefined || b === undefined || a < 0 || b <= a || b >= route.nodes.length) continue;
				const rows = nextTo(rowsOf.get(stationOf(r.stops[i])) || [], rowsOf.get(stationOf(r.stops[i + 1])) || []);
				if (!rows) continue;
				// The rails between the two platforms, or the platforms themselves when one leads straight into the other
				const from = b - a > 1 ? a + 1 : a, to = b - a > 1 ? b - 1 : b;
				let shared = false;
				for (let k = from; k <= to && !shared; k++) shared = own.has(railOf(route.nodes, k));
				if (shared) legs.push({ i, rowA: rows[0], rowB: rows[1] });
			}
			if (legs.length) result.push({ r, legs });
		});
		return result;
	}

	function computeLayout() {
		const line = currentLine();
		if (!line) return null;
		const height = wrap.clientHeight;
		const totalGap = line.rows.reduce((a, row) => a + row.gap, 0) || 1;
		// Room above the first station and below the last for the depots' rows beside them (drawDepotLegs)
		const depotRoom = showDepots && data.depots.some(depot => dutiesOf(depot).mode === "diagram" && depot.sidings.some(siding => siding.legs)) ? DEPOT_ROW_ROOM : 0;
		const unit = Math.max(MIN_ROW_GAP, (height - AXIS_H - PAD_BOTTOM - 12 - depotRoom * 2) / totalGap);
		let y = AXIS_H + 12 + depotRoom;
		const ys = line.rows.map(row => (y += row.gap * unit));
		return { line, ys, contentHeight: y + PAD_BOTTOM + depotRoom };
	}

	const xOf = millis => LABEL_W + (millis - state.viewStart) / 60000 * state.pxPerMin;
	const millisAt = x => state.viewStart + (x - LABEL_W) / state.pxPerMin * 60000;

	function clampView() {
		const visible = (wrap.clientWidth - LABEL_W) / state.pxPerMin * 60000;
		state.viewStart = Math.min(Math.max(state.viewStart, -3600000), DAY + 6 * 3600000 - visible);
		const maxScroll = layout ? Math.max(0, layout.contentHeight - wrap.clientHeight) : 0;
		state.scrollY = Math.min(Math.max(state.scrollY, 0), maxScroll);
	}

	
	/**
	 * The running diagram is the new page's (views/RunningDiagram.tsx): this only works out the layout the old functions
	 * still measure with (centring on a service, zooming, scrolling), then tells the page to draw again.
	 */
	function draw() {
		if (showsDiagram()) {
			layout = computeLayout();
			clampView();
		}
		env.render("draw");
	}

	function stopsHtml(s) {
		const times = timesOf(s);
		return `<div class="stops">${s.route.stops.map((stop, i) => {
			const pass = s.plans[i].passes;
			const arrival = i === 0 ? "" : clock(times[i][0], true), departure = i === times.length - 1 ? "" : pass ? "↓" : clock(times[i][1], true);
			return `<span class="name${pass ? " pass" : ""}">${escapeHtml(shortName(stop.name))}</span><span class="${pass ? "pass" : ""}">${arrival}</span><span class="${pass ? "pass" : ""}">${departure}</span>`;
		}).join("")}</div>`;
	}

	function renderInspector(...args) { return env.render("renderInspector", args) }

	const tooltip = $("tooltip");
	function showTooltip(s, clientX, clientY, accessed) {
		if (!s) {
			tooltip.hidden = true;
			return;
		}
		tooltip.innerHTML = accessed
			? `<b>${tr("接入")} · ${escapeHtml(routeTitle(s.route))}</b> ${clock(s.start)}<div class="hint">${tr("别的线路在这条线路的轨道上跑的班次，只读；在{0}里改", escapeHtml(shortName(s.route.line)))}</div>${stopsHtml(s)}`
			: `<b>${escapeHtml(routeTitle(s.route))}</b> ${clock(s.start)}${stopsHtml(s)}${simTooltip(s)}`;
		tooltip.hidden = false;
		const box = tooltip.getBoundingClientRect();
		tooltip.style.left = Math.min(clientX + 14, innerWidth - box.width - 8) + "px";
		tooltip.style.top = Math.min(clientY + 14, innerHeight - box.height - 8) + "px";
	}

	/** How far a drag moves services, beside the pointer, in the tooltip's place. */
	function showDragTip(text, clientX, clientY) {
		if (!text) {
			tooltip.hidden = true;
			return;
		}
		tooltip.textContent = text;
		tooltip.hidden = false;
		const box = tooltip.getBoundingClientRect();
		tooltip.style.left = Math.min(clientX + 14, innerWidth - box.width - 8) + "px";
		tooltip.style.top = Math.min(clientY + 14, innerHeight - box.height - 8) + "px";
	}

	function centerOn(s) {
		const times = timesOf(s);
		const middle = (times[0][0] + times[times.length - 1][1]) / 2;
		const visible = (wrap.clientWidth - LABEL_W) / state.pxPerMin * 60000;
		const x = xOf(middle);
		if (x < LABEL_W + 40 || x > wrap.clientWidth - 40) state.viewStart = middle - visible / 2;
		// Up or down too, as the diagram is short above the table and the service may be off it
		if (layout && layout.line === currentLine()) {
			const ys = s.route.rows.map(row => layout.ys[row]).filter(y => y !== undefined);
			if (ys.length) {
				const top = Math.min(...ys), bottom = Math.max(...ys), height = wrap.clientHeight - AXIS_H;
				if (top - state.scrollY < AXIS_H || bottom - state.scrollY > wrap.clientHeight) {
					state.scrollY = bottom - top <= height - 40 ? (top + bottom) / 2 - AXIS_H - height / 2 : top - AXIS_H - 20;
				}
			}
		}
	}

	function zoom(direction, atX) {
		const index = ZOOMS.indexOf(state.pxPerMin);
		const next = ZOOMS[Math.max(0, Math.min(ZOOMS.length - 1, index + direction))];
		if (next === state.pxPerMin) return;
		const anchor = millisAt(atX === undefined ? (LABEL_W + wrap.clientWidth) / 2 : atX);
		const anchorX = atX === undefined ? (LABEL_W + wrap.clientWidth) / 2 : atX;
		state.pxPerMin = next;
		state.viewStart = anchor - (anchorX - LABEL_W) / next * 60000;
		draw();
	}

	// ---------- Simulated day ----------
	// The chosen day as the game's server simulates it with the draft (S5): what each service did and what went wrong
	const SIM_LATE_MILLIS = 30000, SIM_MAX_LISTED = 300;
	// checkDepot is the depot whose generated duties the simulated day checks, whose routes are reported on as well
	const sim = { on: false, request: 0, state: "idle", result: null, trips: new Map(), notRun: new Set(), listed: [], madeAt: 0, line: null, weekday: null, timer: null, polling: false, notSimulated: 0, ignored: 0, missingPaths: 0, scopeDepots: 0, allDepots: 0, checkDepot: null, fromNow: false };

	function renderSimStatus() {
		const texts = {
			running: tr("模拟中…"),
			done: tr("按 {0} 的草稿模拟", new Date(sim.madeAt).toTimeString().slice(0, 5)),
			busy: sim.allDepots ? tr("一分钟内没有模拟完（{0} / {1} 个车厂），稍后再试", sim.scopeDepots, sim.allDepots) : tr("服务端忙，稍后再试"),
			permission: tr("没有权限模拟"),
			error: tr("模拟失败"),
			disabled: tr("服务端没有开启模拟（调试功能）"),
		};
		$("sim-status").textContent = sim.on ? texts[sim.state] || "" : "";
	}

	const formatDuration = millis => millis >= 60000 ? tr("{0} 分 {1} 秒", Math.floor(millis / 60000), Math.round(millis % 60000 / 1000)) : tr("{0} 秒", Math.round(millis / 1000));

	// ---------- Summary timetable ----------
	/** The head of a service's column: its time, which starts editing it, and notes. */
	/**
	 * Whether the timetable shows the deadheads of the duties (运用补完 step 3), by the services they join and leave.
	 */
	let showDeadheads = false;
	try { showDeadheads = localStorage.getItem("mtr-editor-show-deadheads") === "1"; } catch (e) {}

	/**
	 * @return service → the deadhead out of a depot to it and back after it, as {out, back, depot}, on the day shown
	 */
	function deadheadsByService() {
		const index = new Map();
		if (!data) return index;
		data.depots.forEach(depot => {
			const duties = dutiesOf(depot);
			if (duties.mode !== "diagram") return;
			depot.sidings.forEach(siding => (duties.sidings[siding.id] || []).forEach(slot => {
				const day = dutyOn(slot, state.weekday);
				if (!day || !day.segments.length) return;
				const times = deadheadTimes(siding, day);
				const first = segmentService(day.segments[0]), last = segmentService(day.segments[day.segments.length - 1]);
				const name = shortName(depot.name) || tr("车厂");
				if (first && times.out) index.set(first.id, Object.assign(index.get(first.id) || {}, { out: times.out, depot: name }));
				if (last && times.back) index.set(last.id, Object.assign(index.get(last.id) || {}, { back: times.back, depot: name }));
			}));
		});
		return index;
	}
	let deadheadIndex = new Map();

	/**
	 * The summary table is the new page's (views/SummaryTable.tsx): this only works out the runs out of and back to a depot
	 * its heads show.
	 */
	function renderTable() {
		deadheadIndex = showDeadheads ? deadheadsByService() : new Map();
	}

	// A run of services chosen together: one route and table, one after another with none skipped (TimetableBatch)
	const Batch = window.TimetableBatch;
	const tableRun = s => s.route.services.filter(o => o.tableIndex === s.tableIndex);

	/** A length of time as minutes and seconds, as 1 分 30 秒, 45 秒 or 5 分钟. */
	function durationText(millis) {
		const seconds = Math.round(millis / 1000), minutes = Math.floor(seconds / 60), rest = seconds % 60;
		return !minutes ? tr("{0} 秒", rest) : rest ? tr("{0} 分 {1} 秒", minutes, rest) : tr("{0} 分钟", minutes);
	}

	/** Puts a new service beside one, its time worked out from the services either side (TimetableBatch.besideTime). */
	function addBeside(s, side) {
		const run = tableRun(s), at = run.indexOf(s);
		const before = at > 0 ? run[at - 1].service : null, after = at < run.length - 1 ? run[at + 1].service : null;
		const time = Batch.besideTime(s.service, side, before, after);
		editTimetable(s.route, timetable => {
			const table = timetable.tables[s.tableIndex];
			if (table.services.includes(time)) return tr("{0} 已经有一班", clock(mod(time - data.dayStartMillis, DAY)));
			if (table.services.length >= MAX_SERVICES) return `${tr("一张表最多 {0} 个班次", MAX_SERVICES)}`;
			table.services.push(time);
			return tr("在 {0} 加了一班", clock(mod(time - data.dayStartMillis, DAY)));
		});
	}

	/**
	 * The services a train can run next after this one's last stop, set by hand: leaving the same station after it
	 * arrives, the same platform first, the nearest eight; one already run next by another service says so.
	 */
	function nextServiceItems(s) {
		const n = s.route.stops.length, last = s.route.stops[n - 1], arrival = timesOf(s)[n - 1][1];
		const pairs = terminalNow(), current = s.link && s.link.fromStop < 0 && s.link.toStop === 0 ? s.link.route + "@" + s.link.toService : null;
		const candidates = data.routes.flatMap(r => r.services || []).filter(o => {
			if (o === s || o.suspended || servedStops(o)[0] !== 0) return false;
			const first = o.route.stops[0], times = timesOf(o)[0];
			return (first.platformId === last.platformId || last.station && first.station === last.station) && times[0] >= arrival + minReverseMillis() && times[1] - arrival <= TerminalPairs.MAX_WAIT;
		}).sort((a, b) => (a.route.stops[0].platformId !== last.platformId) - (b.route.stops[0].platformId !== last.platformId) || timesOf(a)[0][1] - timesOf(b)[0][1]).slice(0, 8);
		const items = candidates.map(o => {
			const before = pairs.previous.get(o.id), takenBy = before && before.from.id !== s.id && before.source !== "nearest" ? findService(before.from.id) : null;
			const label = `${current === o.id ? "✓ " : ""}${clock(timesOf(o)[0][1])} ${shortName(o.route.name)} ${platformText(o.route.stops[0].platformId)} ${tr("停 {0}", formatDuration(timesOf(o)[0][1] - timesOf(s)[n - 1][0]))}${takenBy ? tr("（现在由 {0} 接）", clock(takenBy.start)) : ""}`;
			return [label, () => {
				// The service it ran next before, if set by hand, lets it go
				if (takenBy && takenBy.link && takenBy.link.fromStop < 0) editTimetable(takenBy.route, timetable => { const table = timetable.tables[takenBy.tableIndex]; table.links = table.links.filter(link => link.service !== takenBy.service); });
				setLink(s, -1, o, 0);
			}];
		});
		if (current) items.push([tr("取消指定接续"), () => editTimetable(s.route, timetable => { const table = timetable.tables[s.tableIndex]; table.links = table.links.filter(link => link.service !== s.service); })]);
		return items.length ? items : [[tr("没有赶得上的班次"), null]];
	}

	/** The name of a station by its ID, or the automatic choice for "0". */
	function stationNameOf(stationId) {
		if (!stationId || stationId === "0") return tr("自动（按车站范围和最近的站台）");
		const station = (topo ? topo.stations : []).find(each => each.id === stationId);
		return station ? shortName(station.name) || tr("（未命名车站）") : tr("已删除的车站");
	}

	/** How a platform's stop position reads (round 4). */
	function stopPositionText(position) {
		return position === 0 ? tr("停在出站端") : position < 0 ? tr("按车长居中停车") : tr("离出站端 {0} 格停车", position);
	}

	function cellMenu(e, cell) {
		const s = findService(cell.dataset.svc), stop = +cell.dataset.stop;
		if (!s) {
			openMenuAt(e, []);
			return;
		}
		// The departure from the last stop follows the next service: choosing which one is all there is to do with it
		if (cell.dataset.kind === "derived") {
			openMenuAt(e, stop === s.route.stops.length - 1 ? [[tr("接下一班"), nextServiceItems(s)]] : []);
			return;
		}
		const n = s.route.stops.length, middle = stop > 0 && stop < n - 1, plan = s.plans[stop];
		const exception = s.exceptions && s.exceptions.get(stop);
		const later = laterServices(s);
		const items = [];
		const edit = change => editTimetable(s.route, timetable => change(timetable.tables[s.tableIndex], timetable));
		if (cell.tagName === "TH") {
			if (inRange(s) && state.range.ids.length > 1) {
				items.push([tr("调整选中 {0} 班的间隔…", state.range.ids.length), () => respaceRange()]);
				items.push([tr("删除选中的 {0} 班", state.range.ids.length), () => {
					const run = rangeServices();
					edit(table => run.forEach(o => removeService(table, o.service)));
					state.range = null;
				}]);
				// Not running, kept to run again: conflicts are only told once they run (setSuspended)
				const run = rangeServices();
				if (run.some(o => !o.suspended)) items.push([tr("停运选中的 {0} 班", run.length), () => edit(table => setSuspended(table, run.map(o => o.service), true))]);
				if (run.some(o => o.suspended)) items.push([tr("恢复选中的 {0} 班", run.length), () => edit(table => setSuspended(table, run.map(o => o.service), false))]);
			}
			items.push([tr("在左边新建一列"), () => addBeside(s, -1)]);
			items.push([tr("在右边新建一列"), () => addBeside(s, 1)]);
			items.push([tr("删除这一班"), () => edit(table => removeService(table, s.service))]);
			items.push([s.suspended ? tr("恢复这一班") : tr("停运这一班"), () => edit(table => setSuspended(table, [s.service], !s.suspended))]);
			if (!s.returnOrder && !s.returns) items.push([tr("接下一班"), nextServiceItems(s)]);
			if (!s.link) items.push([s.returns ? tr("跑完这一班不回库") : tr("跑完这一班回库"), () => edit(table => { table.returns = s.returns ? table.returns.filter(x => x !== s.service) : [...table.returns, s.service]; })]);
			if (later.length) items.push([`${tr("这一班和之后的 {0} 班一起平移…", later.length)}`, () => shiftLaterDialog(s)]);
			if (later.length) items.push([`${tr("选中这一班和之后的 {0} 班", later.length)}`, () => chooseLater(s)]);
			if (s.exceptions) items.push([tr("清除这一班的全部例外"), () => edit(table => { table.exceptions = table.exceptions.filter(x => x.service !== s.service); })]);
			// This service's changes at every stop, in order, to every later one: each runs as this one does from its own start
			if (later.length && (s.exceptions || later.some(o => o.exceptions))) items.push([tr("把这一班各站的时刻改动同步到之后的 {0} 班", later.length), () => edit(table => syncExceptions(table, s.service, later.map(o => o.service)))]);
			const chosenLater = inRange(s) ? rangeServices().filter(o => o !== s) : [];
			if (chosenLater.length && chosenLater.length !== later.length && (s.exceptions || chosenLater.some(o => o.exceptions))) items.push([tr("把这一班各站的时刻改动同步到选中的另外 {0} 班", chosenLater.length), () => edit(table => syncExceptions(table, s.service, chosenLater.map(o => o.service)))]);
			if (s.link) items.push([tr("清除这一班的直通"), () => edit(table => { table.links = table.links.filter(link => link.service !== s.service); })]);
			items.push([tr("批量新建直通关系…"), () => throughDialog(s.route)]);
		} else {
			if (middle) {
				const routePasses = timetableOf(s.route).pass[stop];
				items.push([plan.passes ? tr("改为在这一站停车（只改这一班）") : tr("设为通过这一站（只改这一班）"), () => edit(table => setStopException(table, s.service, stop, { pass: plan.passes === routePasses ? (plan.passes ? 0 : 1) : -1, dwell: -1 }))]);
				if (!plan.passes) items.push([plan.overtake ? tr("取消待避（只改这一班）") : tr("设为待避（只改这一班，在这一站等后车通过）"), () => edit(table => {
					const tableOvertake = table.stops[stop].overtake;
					setStopException(table, s.service, stop, { overtake: !plan.overtake === tableOvertake ? -1 : plan.overtake ? 0 : 1 });
				})]);
			}
			if (exception && later.length) items.push([`${tr("把这一格的例外推到之后的 {0} 班", later.length)}`, () => edit(table => later.forEach(o => setStopException(table, o.service, stop, { run: exception.run, dwell: exception.dwell, pass: exception.pass, overtake: exception.overtake })))]);
			if (exception && (exception.dwell >= 0 || exception.run >= 0)) items.push([tr("把这一班的这个时刻改成整张表的"), () => edit(table => {
				// Set times count from the first stop, which is where the table's plan starts
				if (exception.run >= 0) table.stops[stop].arrival = Math.round((s.plans[stop - 1].departure + exception.run * 1000) / 1000);
				if (exception.dwell >= 0) table.stops[stop].departure = Math.round((s.plans[stop].arrival + exception.dwell * 1000) / 1000);
				setStopException(table, s.service, stop, { run: -1, dwell: -1 });
			})]);
			if (exception) items.push([tr("恢复按表的时刻"), () => edit(table => setStopException(table, s.service, stop, { run: -1, dwell: -1, pass: -1, overtake: -1 }))]);
			// Sending a train out or back part of the way is a dispatcher's command now, given to a running train in the console
			// (运用补完 step 3, user: 时刻表里拿掉); orders already set are still shown and can be taken back here
			if (s.deployOrder && s.deployOrder.stop === stop) items.push([tr("改出车的车厂…"), () => serviceOrderDialog(s, "deploy", stop)]);
			if (s.deployOrder) items.push([tr("取消出车指令"), () => edit(table => setServiceOrder(table, s.service, "deploy", 0, null))]);
			if (s.returnOrder) items.push([tr("取消回送"), () => edit(table => setServiceOrder(table, s.service, "return", 0, null))]);
			if (isLoop(s.route) && middle && !plan.passes && loopEntryStop(s.route) !== stop) items.push([`${tr("设为出库列车接入这条环线的站")}`, () => editTimetable(s.route, timetable => { timetable.entryPlatform = s.route.stops[stop].platformId; })]);
			if (isLoop(s.route) && loopEntryStop(s.route) === stop && stop > 0) items.push([tr("出库列车改回从首站接入"), () => editTimetable(s.route, timetable => { timetable.entryPlatform = "0"; })]);
			// Through running: leave at the last stop or a boundary, join at the first stop or a boundary
			if (stop === n - 1 || isBoundary(s.route, stop) && !plan.passes) items.push([`${tr("设为接出点（从{0}直通到别的线路）", stopName(s.route, stop))}`, () => {
				state.pendingExit = { id: s.id, stop: stop === n - 1 ? -1 : stop, index: stop };
				renderAll();
			}]);
			const pending = state.pendingExit && findService(state.pendingExit.id);
			if (pending && pending.route !== s.route && (stop === 0 || isBoundary(s.route, stop))) items.push([`${tr("设为接入点（{0} 班从{1}接到这里）", clock(pending.start), stopName(pending.route, state.pendingExit.index))}`, () => {
				setLink(pending, state.pendingExit.stop, s, stop);
				state.pendingExit = null;
				renderAll();
			}]);
		}
		// Which station the stop's platform belongs to, wherever it is (R6)
		const stationItems = s.route.stops[stop] ? platformStationItems(s.route.stops[stop].platformId) : null;
		if (stationItems) items.push([tr("站台 {0} 归属车站", platformText(s.route.stops[stop].platformId)), stationItems]);
		// Trains longer than the platform allowed to stop here (round 3b)
		if (s.route.stops[stop] && data.canEdit) {
			const allowed = (timetableOf(s.route).allowLonger || [])[stop];
			items.push([`${allowed ? "✓ " : ""}${tr("允许比站台长的车停靠")}`, () => {
				editTimetable(s.route, timetable => {
					if (!timetable.allowLonger || timetable.allowLonger.length !== s.route.stops.length) timetable.allowLonger = s.route.stops.map(each => Boolean(each.allowLonger));
					timetable.allowLonger[stop] = !allowed;
				});
			}]);
		}
		const speedItems = s.route.stops[stop] ? platformSpeedItems(s.route.stops[stop].platformId) : null;
		if (speedItems) items.push([tr("站台 {0} 过站限速", platformText(s.route.stops[stop].platformId)), speedItems]);
		const stopItems = s.route.stops[stop] ? platformStopItems(s.route.stops[stop].platformId) : null;
		if (stopItems) items.push([tr("站台 {0} 停车位置", platformText(s.route.stops[stop].platformId)), stopItems]);
		openMenuAt(e, items);
	}

	// ---------- Loops ----------

	/** The platform a stop uses and the other tracks of its station on the same rails. */
	function trackTitle(stop) {
		const trackName = c => trackLabel(c.id, c.name, c.passingTrack);
		const others = (stop.candidates || []).filter(c => c.id !== stop.platformId);
		return ` ${tr("站台 {0}{1}", trackName({ id: stop.platformId, name: stop.platform, passingTrack: stop.passingTrack }), others.length ? `${tr("；还可停 {0}", others.map(trackName).join("、"))}` : "")}`;
	}

	/** @return true if a service can leave this route here, or join it, apart from the last and first stops */
	const isBoundary = (r, stop) => Boolean(r.stops[stop] && r.stops[stop].boundary);
	const stopName = (r, stop) => shortName(r.stops[stop].name);

	/**
	 * Through links in bulk: each service of one route in a time range carries on as the first service of another route it
	 * can catch, leaving at its last stop or a boundary and joining at the first stop or a boundary.
	 */
	// ---------- Duties (运用) ----------
	const depotById = id => data.depots.find(depot => depot.id === id);

	const startText = (slot, siding) => {
		const name = shortName(siding.name) || tr("存车线");
		const start = startOf(slot);
		return start === "any" ? tr("出车：不指定") : start === "only" ? tr("只从 {0} 出车", name) : tr("出车：{0}", name);
	};

	/** A platform as the whole network's topology has it (id, name, station, stationName), or null. */
	const platformById = platformId => topo && topo.index.has(String(platformId)) ? topo.platforms[topo.index.get(String(platformId))] : null;
	const platformText = platformId => {
		const platform = platformById(platformId);
		return platform ? `${shortName(platform.stationName) || ""} ${platform.name || ""}`.trim() : String(platformId);
	};
	/** How a train goes on at the end of a route, as a short sentence. */
	function terminalText(pairing) {
		if (pairing.overlap) return tr("下一圈已发车");
		const wait = Math.max(0, pairing.to.times[0][1] - pairing.from.times[pairing.from.last][0]);
		const where = pairing.kind === "hold" ? tr("停在 {0} 后面的折返线上等 {1}", platformText(pairing.platform), formatDuration(pairing.railEnd - pairing.railStart))
			: pairing.kind === "change" ? tr("换到 {0}，共 {1}", platformText(pairing.to.platforms[0]), formatDuration(wait))
			: pairing.loop ? tr("环线续跑，在 {0} 停 {1}", platformText(pairing.platform), formatDuration(wait))
			: tr("在 {0} 折返，停 {1}", platformText(pairing.platform), formatDuration(wait));
		const how = pairing.source === "manual" ? tr("（指定）") : pairing.source === "nearest" ? tr("（推测）") : "";
		return where + how + (pairing.moved ? tr("，改用这个站台") : "") + (pairing.conflict ? tr("，站台被占") : "");
	}

	let showNoTrain = false;
	try { showNoTrain = localStorage.getItem("mtr-editor-show-no-train") === "1"; } catch (e) {}
	/** Whether the diagram shows the depots' rows and the deadheads out and back. */
	let showDepots = true;
	try { showDepots = localStorage.getItem("mtr-editor-show-depots") !== "0"; } catch (e) {}

	function selectSlot(depotId, sidingId, index) {
		const depot = depotById(depotId);
		if (!depot) return;
		state.slot = state.slot && state.slot.depot === depotId && state.slot.siding === sidingId && state.slot.index === index ? null : { depot: depotId, siding: sidingId, index };
		// Show the line of the slot's first service
		const day = state.slot && (dutiesOf(depot).sidings[sidingId] || [])[index] && dutyOn(dutiesOf(depot).sidings[sidingId][index], state.weekday);
		const first = day && day.segments.length && data.routeById.get(day.segments[0].route);
		if (first) {
			state.line = first.lineKey;
			state.view = showsTable() && !showsDiagram() ? "diagram" : state.view;
		}
		renderAll();
	}

	let lastSlotService = null;

	/**
	 * The services a train can run one after another from one service to another on the chosen day: from where each ends,
	 * the first one leaving that station after it arrives, until the second service. Empty if it can't get there.
	 */
	function chainBetween(a, b) {
		const [first, last] = a.start <= b.start ? [a, b] : [b, a];
		const depot = depotById(state.slot.depot);
		const pool = data.routes.filter(r => depot.routes.includes(r.id)).flatMap(r => r.services).filter(o => o.start >= first.start && o.start <= last.start);
		const stationAt = (o, i) => (o.route.stops[i] || {}).station;
		const chain = [first];
		let current = first;
		for (let guard = 0; guard < 4096 && current !== last; guard++) {
			const times = timesOf(current), endStation = stationAt(current, current.route.stops.length - 1), arrival = times[times.length - 1][0];
			const next = pool.filter(o => o !== current && !chain.includes(o) && stationAt(o, 0) === endStation && timesOf(o)[0][1] >= arrival + minReverseMillis()).sort((x, y) => timesOf(x)[0][1] - timesOf(y)[0][1])[0];
			if (!next || next.start > last.start) return [];
			chain.push(next);
			current = next;
		}
		return current === last ? chain : [];
	}

	/** Adds the services between two to the selected train's duty, or takes them out if both are in it already. */
	function addRangeToSlot(a, b) {
		const depot = depotById(state.slot.depot);
		if (!depot) return;
		const chain = chainBetween(a, b);
		if (!chain.length) {
			dutyNotice = tr("从 {0} 到 {1} 接不上：中间没有一班从上一班终点站发车、赶得上的班次。", clock(a.start), clock(b.start));
			renderBanner();
			return;
		}
		editDuties(depot, duties => {
			const slots = duties.sidings[state.slot.siding] || (duties.sidings[state.slot.siding] = []);
			while (slots.length <= state.slot.index) slots.push([]);
			const day = dutyForEditing(depot, slots[state.slot.index], state.weekday);
			const keys = chain.map(o => o.route.id + "@" + o.service);
			const has = key => day.segments.some(other => segmentKey(other) === key);
			if (has(keys[0]) && has(keys[keys.length - 1])) {
				day.segments = day.segments.filter(other => !keys.includes(segmentKey(other)));
				return tr("{0} 班已从车 {1} 移出。", chain.length, dutyNumber(depot, state.slot.siding, state.slot.index));
			}
			// Taken from the other trains that ran them
			Object.entries(duties.sidings).forEach(([siding, otherSlots]) => otherSlots.forEach((slot, index) => {
				const other = dutyOn(slot, state.weekday);
				if (!other || siding === state.slot.siding && index === state.slot.index) return;
				if (other.segments.some(o => keys.includes(segmentKey(o)))) dutyForEditing(depot, slot, state.weekday).segments = other.segments.filter(o => !keys.includes(segmentKey(o)));
			}));
			chain.forEach(o => {
				if (!has(o.route.id + "@" + o.service)) day.segments.push({ route: o.route.id, service: o.service, from: 0, to: o.link ? o.link.fromStop : -1 });
			});
			day.segments.sort((x, y) => segmentStart(x) - segmentStart(y));
			return tr("{0} 班加进车 {1}。", chain.length, dutyNumber(depot, state.slot.siding, state.slot.index));
		});
	}

	/**
	 * Adds a service to the selected train's duty for the chosen day, or takes it out. A service another train of the
	 * depot runs moves to this one. Joining or leaving part of the way along follows the service's through links.
	 */
	function toggleInSlot(s) {
		const depot = depotById(state.slot.depot);
		if (!depot) return;
		if (!depot.routes.includes(s.route.id)) {
			dutyNotice = `${tr("{0} 不在车厂“{1}”里，先右键车厂选“编辑车厂…”把线路加进去。", routeTitle(s.route), shortName(depot.name))}`;
			renderBanner();
			return;
		}
		const joining = data.routes.flatMap(r => r.services).find(o => o.link && o.link.route === s.route.id && o.link.toService === s.service);
		const segment = { route: s.route.id, service: s.service, from: joining ? joining.link.toStop : 0, to: s.link ? s.link.fromStop : -1 };
		editDuties(depot, duties => {
			const slots = duties.sidings[state.slot.siding] || (duties.sidings[state.slot.siding] = []);
			while (slots.length <= state.slot.index) slots.push([]);
			const day = dutyForEditing(depot, slots[state.slot.index], state.weekday);
			const at = day.segments.findIndex(other => segmentKey(other) === segmentKey(segment));
			if (at >= 0) {
				day.segments.splice(at, 1);
				return `${tr("{0} {1} 已从车 {2} 移出，按运用图时这一班不运行。", clock(s.start), routeTitle(s.route), dutyNumber(depot, state.slot.siding, state.slot.index))}`;
			}
			let moved = "";
			Object.entries(duties.sidings).forEach(([siding, otherSlots]) => otherSlots.forEach((slot, index) => {
				const other = dutyOn(slot, state.weekday);
				if (!other || siding === state.slot.siding && index === state.slot.index) return;
				const found = other.segments.findIndex(o => segmentKey(o) === segmentKey(segment));
				if (found >= 0) {
					dutyForEditing(depot, slot, state.weekday).segments.splice(found, 1);
					moved = `${tr("，从车 {0} 移过来", dutyNumber(depot, siding, index))}`;
				}
			}));
			day.segments.push(segment);
			return `${tr("{0} {1} 加进车 {2}{3}。", clock(s.start), routeTitle(s.route), dutyNumber(depot, state.slot.siding, state.slot.index), moved)}`;
		});
	}

	/** Where the generated duty's train is forecast to go back, when not to its own siding. */
	function plannedReturnText(depot, sidingId, index, weekday) {
		const to = (plannedReturns.get(depot.id) || {})[`${sidingId}/${index}/${weekday}`];
		if (!to) return "";
		const siding = data.depots.flatMap(other => other.sidings).find(other => other.id === to);
		return `<dt>${tr("回库")}</dt><dd>${escapeHtml(tr("预计回 {0}（车池里那时最早要用车的存车线；游戏运行时自己选）", siding ? shortName(siding.name) || tr("存车线") : to))}</dd>`;
	}

	/** A first draft of a depot's duties from its timetables, put in the draft to look over before applying. */
	/**
	 * When each of the depot's trains goes out and comes back on the day shown, by its duty: the first service it runs and
	 * from where, the last and to where, and whether it stays on the line overnight. A depot running by a timetable with
	 * no duties set shows the duties the game worked out for it. The depot sends a train out its run from the siding
	 * before the first service; one sent out too late joins its duty at the first stop it can still keep to.
	 */
	/**
	 * When a duty's train leaves its siding and gets back, by how long its runs out and back take (DepotLegs), as the
	 * running diagram draws them; "--:--" where not known yet.
	 */
	function legTimes(siding, day) {
		const times = deadheadTimes(siding, day);
		const mark = exception => exception ? " ✎" : "";
		return {
			out: times.out ? `${times.out.number} ${clock(times.out.leave, true)}${mark(times.out.exception)}` : "--:--",
			home: day.stay ? tr("留线") : times.back ? `${times.back.number} ${clock(times.back.home, true)}${mark(times.back.exception)}` : "--:--",
		};
	}

	// The exceptions a duty may have on its own (运用补完 step 2, DepotDuties.DayDuty.EXCEPTION_*)
	const EXCEPTION = { OUT_LEAD: 1, OUT_PATH: 2, BACK_SIDING: 4, BACK_PATH: 8, WAITS: 16, ENTRANCES: 32 };
	const hhmm = millis => clock(millis).replace(":", "");

	/**
	 * A duty's deadheads (回送, 运用补完 step 3): out of the siding to its first service and back after its last, each with
	 * its number (出 or 回 and the time of the service it joins or leaves), when it leaves and gets there as planned (how
	 * long before the first service the duty has the train leave, never sooner than its way out takes; the way out and 15
	 * seconds for a duty planned before), and whether it was set for the duty alone.
	 */
	function deadheadTimes(siding, day) {
		const legs = siding.legs, first = day.segments[0], last = day.segments[day.segments.length - 1];
		const firstService = first && segmentService(first), lastService = last && segmentService(last);
		const result = { out: null, back: null };
		const exceptions = day.exceptions || 0;
		if (legs && firstService && !day.fromOvernight) {
			const millis = legs.out[firstService.route.stops[first.from].platformId];
			if (millis !== undefined) {
				const times = timesOf(firstService)[first.from];
				const lead = day.outLead >= 0 ? Math.max(day.outLead, millis) : millis + 15000;
				result.out = { number: tr("出{0}", hhmm(times[1])), leave: times[1] - lead, arrive: times[0], lead, wayOut: millis, row: firstService.route.rows[first.from], exception: (exceptions & (EXCEPTION.OUT_LEAD | EXCEPTION.OUT_PATH | EXCEPTION.ENTRANCES)) !== 0, path: day.outPath };
			}
		}
		if (legs && lastService && !day.stay) {
			const lastStop = lastStopOf(last, lastService.route), millis = legs.back[lastService.route.stops[lastStop].platformId];
			if (millis !== undefined) {
				const times = timesOf(lastService)[lastStop], leave = Math.max(times[0], times[1]);
				result.back = { number: tr("回{0}", hhmm(times[0])), leave, home: leave + millis, row: lastService.route.rows[lastStop], exception: (exceptions & (EXCEPTION.BACK_SIDING | EXCEPTION.BACK_PATH)) !== 0, siding: day.returnSiding, path: day.backPath };
			}
		}
		return result;
	}

	/**
	 * Changes a duty's own deadhead or wait as an exception (运用补完 step 3), in the draft of its depot's duties.
	 */
	function editDutyException(depot, sidingId, index, change) {
		editDuties(depot, duties => {
			const slot = duties.sidings[sidingId] && duties.sidings[sidingId][index];
			if (!slot) return;
			const day = dutyForEditing(depot, slot, state.weekday);
			return change(day);
		});
	}

	const isOrderIssue = issue => issue.startsWith("DEPLOY_DEPOT ") || issue.startsWith("RETURN_DEPOT ");
	/**
	 * The planner's "DEPLOY_DEPOT <depot> has no siding for <route>@<service>" (or RETURN_DEPOT) as a sentence: a service set
	 * to come out of, or go back to, a depot with no siding planned here, which has no train rather than one from elsewhere.
	 */
	function orderIssueText(issue) {
		const parts = issue.split(" "), [routeId, service] = (parts[parts.length - 1] || "").split("@");
		const depot = depotById(parts[1]), r = data.routeById.get(routeId);
		const what = `${r ? routeTitle(r) : routeId} ${service ? clock(mod(+service - data.dayStartMillis, DAY)) : ""}`.trim();
		const depotName = depot ? shortName(depot.name) : parts[1];
		return issue.startsWith("DEPLOY_DEPOT ") ? tr("{0} 这一班设了由车厂 {1} 出车，但 {1} 没有能跑它的存车线一起排，这一班没有车。", what, depotName) : tr("{0} 这一班设了跑完回送车厂 {1}，但 {1} 没有能收它的存车线一起排，这一班没有车。", what, depotName);
	}

	/** The planner's "SHORT_TURNAROUND <platform> <millis had> <millis needed>" as a sentence, with the station's name. */
	function shortTurnaroundText(issue) {
		const [, platformId, had, needed] = issue.split(" ");
		const platform = platformById(platformId);
		const where = platform ? `${shortName(platform.stationName) || tr("不属于车站")} ${platform.name || ""}`.trim() : platformId;
		return tr("在 {0} 折返时间不够：车到站后 {1} 秒就要发下一班，至少要 {2} 秒，所以每列车只能跑一趟就回库。把回程的发车往后挪，或者加一个站台。", where, Math.round(+had / 1000), Math.round(+needed / 1000));
	}

	/** What a pool of a depot's sidings has: as given, or its sidings' fleets added up when not (null). */
	function poolFleetText(fleet, sum) {
		if (fleet === null || fleet === undefined) return sum < 0 ? tr("配属按需要（存车线相加）") : tr("配属 {0} 列（存车线相加）", sum);
		return fleet < 0 ? tr("配属按需要") : tr("配属 {0} 列", fleet);
	}

	/** A vehicle's settings in a line: as set for its pool, or as its first siding has them, and the types it runs. */
	function vehicleSummary(pool, settings, serviceTypes) {
		const shown = settings || pool.vehicleSettings || {};
		const parts = [tr("加速 {0} m/s²", Math.round((shown.acceleration || 0) * 400 * 100) / 100)];
		if (shown.maxSpeed > 0) parts.push(tr("最高 {0} km/h", shown.maxSpeed));
		if (shown.manual) parts.push(tr("可手动驾驶"));
		if (serviceTypes) parts.push(tr("种别 {0}", serviceTypes));
		return (settings ? "" : tr("（按第一条存车线）")) + parts.join(tr("，")) + tr("；在左侧车厂下的车型右键“车型设置…”里改");
	}

	/**
	 * By depot, where the duties last generated here send their trains back when not to their own siding, as the game
	 * would choose it running: siding/slot/weekday → siding. A forecast; the game chooses as it runs.
	 */
	const plannedReturns = new Map();

	function setDutyMode(depot, mode) {
		editDuties(depot, duties => {
			duties.mode = mode;
		});
	}

	function changeSlots(depot, sidingId, change) {
		editDuties(depot, duties => {
			const slots = duties.sidings[sidingId] || (duties.sidings[sidingId] = []);
			change(slots);
		});
		if (state.slot && state.slot.siding === sidingId && state.slot.index >= ((dutiesOf(depot).sidings[sidingId] || []).length)) state.slot = null;
		renderAll();
	}

	// ---------- New types ----------
	/** The route of the same line running the other way, with the stations of this one in reverse. */
	// ---------- Topology: how trains run between platforms ----------
	/*
	 * The server's platform graph (PlatformGraph): each platform in two states, one for each way along it (state = index *
	 * 2 + way), with edges to the first platforms a train meets running on without turning back. A network is rails joined
	 * together; platforms with no rail between them are never offered for each other.
	 */
	let topo = null;

	function buildTopology() {
		const source = data.topology || { platforms: [], networkCount: 0 };
		const byStation = new Map();
		source.platforms.forEach((p, i) => {
			if (!p.station) return;
			const key = p.station + "|" + p.network;
			if (!byStation.has(key)) byStation.set(key, []);
			byStation.get(key).push(i);
		});
		topo = { platforms: source.platforms, index: new Map(source.platforms.map((p, i) => [p.id, i])), byStation, reach: new Map(), problems: new Map(), networkCount: source.networkCount || 0, stations: source.stations || [], overlapping: source.overlapping || [] };
	}

	/*
	 * A station's tracks as the game works them out from the routes (L2, StationTracks): up or down by where the routes'
	 * first and last stations lie (north or east is up), and main line or passing track, the hand-set flag first. Every
	 * place naming a track says it the same way.
	 */
	const TRACK_DIRECTIONS = { up: "上行", down: "下行", both: "双向" };
	function trackInfo(id, passingTrack) {
		const index = topo && topo.index.get(id);
		const track = index !== undefined && topo.platforms[index] ? topo.platforms[index].track : null;
		const kind = passingTrack ? "passing" : track && track.kind ? track.kind : "";
		return { direction: track && track.direction ? track.direction : "", kind };
	}
	/** As "上行 3道 正线". */
	function trackLabel(id, name, passingTrack) {
		const { direction, kind } = trackInfo(id, passingTrack);
		return [direction ? tr(TRACK_DIRECTIONS[direction]) : "", tr("{0}道", name || "?"), kind === "main" ? tr("正线") : kind === "passing" ? tr("待避线") : ""].filter(Boolean).join(" ");
	}

	/**
	 * The track diagram of a station: its rails as the game has them, curves included, turned so the train runs from left to
	 * right along the track picked, with the station's tracks on the same rails to click like the cells of the picker.
	 */
	/**
	 * A stop's throat as the game works it out from the route's path on the map (L2): the turnouts it passes coming in
	 * and going out, and the other routes at the station whose way through one of them differs, which can't use it at
	 * the same time. The route as it is in the game; track changes in the draft show once applied.
	 */
	/** Why a depot's trains don't lock station throats, or nothing: it doesn't run by duties, or shares track with one that doesn't. */
	function lockOffText(depot) {
		if (!depot || !depot.throatLocksOffBy && !depot.oldLogic) return "";
		const names = (depot.throatLocksOffBy || []).map(id => { const old = depotById(id); return old ? shortName(old.name) : "?"; }).join(tr("、"));
		return depot.oldLogic
			? tr("这个车厂不按运用跑（例如按频率发车），属于旧逻辑：它的车不锁咽喉进路，和它共用轨道的按运用的车厂也不锁。给它设时刻表，或用寻路边界把它隔开后，新逻辑才启用。")
			: tr("和不按运用跑的车厂（{0}）共用轨道，咽喉进路锁不启用；把它们改成按时刻表，或用寻路边界隔开后启用。", names);
	}

	/**
	 * Why a depot sends no trains, as far as the page can tell (2026-11-09): its lines' timetables all switched off, or duties
	 * set but not run by the duty diagram; or nothing set at all.
	 */
	const notConfiguredText = depot => {
		const routes = (depot.routes || []).map(id => data.routeById.get(id)).filter(Boolean);
		const reasons = [];
		const set = depot.duties || { mode: "online", sidings: {} };
		if (set.mode !== "diagram" && Object.values(set.sidings || {}).some(slots => slots.length)) reasons.push(tr("设好的运用没有用上：车厂右键切到“按运用图”才按它们发车"));
		return reasons.length ? tr("这个车厂不发车。{0}", reasons.join(tr("；"))) : tr("这个车厂还没有时刻表，也没有按频率发车的设置，不发车。");
	};
	const lockMark = depot => depot.notConfigured ? `<span class="count warn" title="${escapeHtml(notConfiguredText(depot))}"> · ${tr("不发车")}</span>` : depot.throatLocksOffBy || depot.oldLogic ? `<span class="count warn" title="${escapeHtml(lockOffText(depot))}"> · ${depot.oldLogic ? tr("旧逻辑") : tr("咽喉锁未启用")}</span>` : "";

	function counterpartOf(r) {
		const line = data.lines.find(l => l.key === r.lineKey);
		if (!line || r.stops.length < 2) return null;
		const first = r.stops[0].station, last = r.stops[r.stops.length - 1].station;
		const others = line.routes.filter(o => o !== r && o.stops.length === r.stops.length && o.stops[0].station === last && o.stops[o.stops.length - 1].station === first);
		return others.find(o => o.serviceType === r.serviceType) || others[0] || null;
	}

	/** A name with words added to the first language of it, before the {@code |} other languages follow. */
	const withSuffix = (name, suffix) => name.includes("|") ? name.slice(0, name.indexOf("|")) + suffix + name.slice(name.indexOf("|")) : name + suffix;

	/** A name for the new type: the route's name with its type word swapped, or the type added after it. */
	function typedName(r, type) {
		const word = r.serviceType;
		return word && r.name.toLowerCase().includes(word) ? r.name.slice(0, r.name.toLowerCase().indexOf(word)) + type + r.name.slice(r.name.toLowerCase().indexOf(word) + word.length) : `${r.name} ${type}`;
	}

	/** The platform each stop uses, with a track change waiting in the draft. */
	const platformsOf = r => draftOf(r) && draftOf(r).platforms ? draftOf(r).platforms : r.stops.map(stop => stop.platformId);

	function keepType(r) {
		const entry = draftOf(r);
		if (!entry) return;
		delete entry.deleted;
		if (!entry.conflict) delete entry.error;
		tidyDraft(r.id);
		saveDraft();
		renderAll();
	}

	function removeCreated(key) {
		draft.created = draft.created.filter(made => made.key !== key);
		saveDraft();
		renderAll();
	}

	// What was typed to add services, kept after adding and across visits (per viewer)
	const addInputs = (() => {
		try {
			return Object.assign({ start: "", end: "", count: "" }, JSON.parse(localStorage.getItem("mtr-editor-add-services") || "{}"));
		} catch (e) {
			return { start: "", end: "", count: "" };
		}
	})();

	function selectService(id, fromTable) {
		state.selected = id;
		const s = id && findService(id);
		if (s && !fromTable) {
			state.route = state.route && state.route !== s.route.id ? s.route.id : state.route || s.route.id;
		}
		if (s) centerOn(s);
		renderAll();
	}

	// ---------- Banner and status ----------
	function renderBanner(...args) { return env.render("renderBanner", args) }

	function renderSyncButton(...args) { return env.render("renderSyncButton", args) }

	const systemOfRoute = routeId => (data.contingencySystems || []).find(system => system.routes.includes(routeId)) || null;
	const systemOfLine = lineKey => {
		const line = data.lines.find(each => each.key === lineKey);
		return line ? line.routes.map(r => systemOfRoute(r.id)).find(Boolean) || null : null;
	};

	function renderStatus(...args) { return env.render("renderStatus", args) }

	function renderAll(...args) { return env.render("renderAll", args) }

	// ---------- Tree folding ----------
	const portrait = matchMedia("(orientation: portrait) and (max-width: 1100px)");

	// ---------- Events ----------
	$("tree").addEventListener("click", e => {
		if (e.target.closest("[data-new-line]")) {
			newLineDialog();
			return;
		}
		onTreeClick(e.target);
	});
	// Right-click a line for a new type, a route for a new table or a type copied from it, a new type for removing it
	$("tree").addEventListener("contextmenu", e => {
		const el = e.target.closest(".node");
		if (!el || !data) return;
		const { line, route, created, depot: depotId, siding: sidingId, slot, ofDepot, pool: poolKey, connecting } = el.dataset;
		const r = route && data.routeById.get(route);
		const depot = depotById(depotId || ofDepot);
		const sidingSlots = () => (dutiesOf(depot).sidings[sidingId] || []);
		const siding = depot && sidingId && depot.sidings.find(o => o.id === sidingId);
		const vehiclePool = poolKey !== undefined && depot ? (depot.pools || []).find(each => each.key === poolKey) : null;
		const connectingRoute = connecting && data.routeById.get(connecting);
		const logicalLine = el.dataset.logical && logicalLinesShown().get(el.dataset.logical);
		const items = logicalLine ? [[tr("改名和颜色…"), data.canEdit ? () => logicalLineDialog(logicalLine) : null],
			[logicalLine.connecting ? tr("改为普通逻辑线路") : tr("设为联络线（所有运营线路都能走）"), data.canEdit ? () => draftSettingsPart("logicalLines", drafted => { drafted[logicalLine.id] = { name: logicalLine.name, color: logicalLine.color, connecting: !logicalLine.connecting }; }) : null],
			[tr("删除这条逻辑线路…"), data.canEdit ? () => deleteLogicalLine(logicalLine) : null]]
			: vehiclePool ? [[tr("车型设置…"), data.canEdit ? () => vehicleDialog(depot, vehiclePool) : null]]
			: connectingRoute ? [[tr("取消联络线：{0}", routeTitle(connectingRoute)), data.canEdit ? () => setConnecting(connectingRoute, false) : null]]
			: slot !== undefined && depot ? [
			[tr("清空这列车当天的运用"), () => editDuties(depot, duties => { const day = dutyOn(duties.sidings[sidingId][+slot], state.weekday); if (day) dutyForEditing(depot, duties.sidings[sidingId][+slot], state.weekday).segments = []; })],
			[tr("删除这个库位"), () => changeSlots(depot, sidingId, slots => slots.splice(+slot, 1))],
		] : siding ? [
			[tr("添加运用"), sidingSlots().length < 256 ? () => changeSlots(depot, sidingId, slots => slots.push([])) : null],
			[tr("删除最后一个库位"), sidingSlots().length ? () => changeSlots(depot, sidingId, slots => slots.pop()) : null],
		] : depotId && depot ? [
			[tr("编辑车厂…"), () => depotDialog(depot)],
			[tr("出入库规则…"), () => depotRulesDialog(depot)],
			[tr("一键生成运用…"), data.canEdit ? () => generateDuties(depot) : null],
			[tr("按时刻表把列车摆到现在的位置…"), data.canEdit ? () => placeTrainsDialog(depot) : null],
			[tr("重新排运用…"), data.canEdit && dutiesOf(depot).auto ? () => replanDialog(depot) : null],
			[tr("运用组…"), () => dutyGroupsDialog(depot)],
			[tr("出入库计划…"), () => releasePlanDialog(depot)],
			[dutiesOf(depot).mode === "diagram" ? tr("改为自动排班") : tr("改为按运用图"), () => setDutyMode(depot, dutiesOf(depot).mode === "diagram" ? "online" : "diagram")],
			[tr("丢掉运用的草稿"), draft.duties[depot.id] ? () => { delete draft.duties[depot.id]; saveDraft(); renderAll(); } : null],
		] : line ? [[tr("新建种别…"), data.canEdit ? () => newTypeDialog(line, null) : null], [tr("线路组配线图…"), (data.lines.find(each => each.key === line) || { routes: [] }).routes.length > 1 ? () => lineDiagramDialog(data.lines.find(each => each.key === line)) : null], [tr("能走的逻辑线路…"), data.canEdit && usesLogicalLines() ? () => lineTracksDialog(line) : null], [tr("路网故障预案…"), systemOfLine(line) ? () => contingencyDialog(systemOfLine(line)) : null],
			[tr("线路限速…"), data.canEdit && (data.lines.find(each => each.key === line) || {}).routes ? () => speedLimitDialog(data.lines.find(each => each.key === line).routes) : null]]
			: r ? [[tr("编辑停站…"), data.canEdit && r.stops.length > 1 ? () => stopsDialog(r) : null], [tr("路网故障预案…"), systemOfRoute(r.id) ? () => contingencyDialog(systemOfRoute(r.id)) : null], [tr("线路限速…"), data.canEdit ? () => speedLimitDialog([r]) : null], [tr("新建时刻表…"), () => tableDialog(r, -1)], [tr("照着它新建种别…"), data.canEdit ? () => newTypeDialog(r.lineKey, r) : null], [tr("线路配线图…"), () => routeDiagramDialog(r)],
				isDeleted(r) ? [tr("取消删除"), () => keepType(r)] : [tr("删除这个种别…"), data.canEdit ? () => deleteTypeDialog(r) : null]]
				: created ? [[tr("删除这个新建的种别"), () => removeCreated(created)]] : [];
		e.preventDefault();
		openMenuAt(e, items);
	});
	$("tree").addEventListener("keydown", e => {
		if (e.key === "Enter" || e.key === " ") {
			e.preventDefault();
			onTreeClick(e.target);
		}
	});
	$("inspector").addEventListener("click", e => {
		const problemButton = e.target.closest("[data-sim-problem]");
		if (problemButton) {
			const problem = sim.listed[Number(problemButton.dataset.simProblem)];
			const s = problem && findService(problem.route + "@" + problem.service);
			if (s) {
				state.selected = s.id;
				centerOn(s);
				renderAll();
			}
			return;
		}
		const remove = e.target.closest("[data-remove-segment]");
		const depot = state.slot && depotById(state.slot.depot);
		if (remove && depot) editDuties(depot, duties => { dutyForEditing(depot, duties.sidings[state.slot.siding][state.slot.index], state.weekday).segments.splice(+remove.dataset.removeSegment, 1); });

		if (e.target.closest("[data-remove-deleted]") && depot) editDuties(depot, duties => {
			const day = dutyForEditing(depot, duties.sidings[state.slot.siding][state.slot.index], state.weekday);
			day.segments = day.segments.filter(segment => data.routeById.get(segment.route));
		});
	});
	$("inspector").addEventListener("change", e => {
		const depot = state.slot && depotById(state.slot.depot);
		if (e.target.matches("[data-duty-next]") && depot) {
			const [kind, value] = e.target.value.split(":");
			editDuties(depot, duties => {
				const day = dutyForEditing(depot, duties.sidings[state.slot.siding][state.slot.index], state.weekday);
				// Another siding's duty by its number, or one of this siding by its place (P2-D)
				day.nextDuty = kind === "duty" ? value : "0";
				day.next = kind === "duty" || Number(value) === state.slot.index ? -1 : Number(value);
			});
		}
		// Where the duty's train comes from (S8): how, and from which siding of the pool, the duty moved there keeping its
		// number; its siding's references by place become ones by number first, so they still find it (P2-D)
		if (e.target.matches("[data-duty-start]") && depot) {
			const start = e.target.value;
			editDuties(depot, duties => { (duties.sidings[state.slot.siding][state.slot.index] || []).forEach(day => { day.start = start; }); });
		}
		if (e.target.matches("[data-duty-siding]") && depot && e.target.value !== state.slot.siding) {
			const from = state.slot.siding, to = e.target.value, other = depot.sidings.find(each => each.id === to);
			let moved = null;
			editDuties(depot, duties => {
				const slots = duties.sidings[from] || [];
				slots.forEach(slot => { if (!dutyIdOf(slot)) { const id = newDutyId(); slot.forEach(day => { day.duty = id; }); } });
				slots.forEach(slot => slot.forEach(day => {
					if (day.stay && day.next >= 0 && slots[day.next] && (!day.nextDuty || day.nextDuty === "0")) {
						day.nextDuty = dutyIdOf(slots[day.next]);
						day.next = -1;
					}
				}));
				const [slot] = slots.splice(state.slot.index, 1);
				if (!slots.length) delete duties.sidings[from];
				const target = duties.sidings[to] || (duties.sidings[to] = []);
				target.push(slot);
				moved = { depot: depot.id, siding: to, index: target.length - 1 };
				return tr("运用移到了 {0}，编号不变", shortName(other ? other.name : "") || tr("存车线"));
			});
			if (moved) {
				state.slot = moved;
				renderAll();
			}
		}
		if (e.target.matches("[data-duty-return]") && depot) {
			const returnSiding = e.target.value;
			editDuties(depot, duties => {
				const day = dutyForEditing(depot, duties.sidings[state.slot.siding][state.slot.index], state.weekday);
				day.returnSiding = returnSiding;
				// Set by hand: kept when the duties are planned again (运用补完 step 2)
				day.exceptions = returnSiding === "0" ? (day.exceptions || 0) & ~EXCEPTION.BACK_SIDING : (day.exceptions || 0) | EXCEPTION.BACK_SIDING;
			});
		}
		if (e.target.matches("[data-duty-wait]") && depot) {
			const i = +e.target.dataset.dutyWait, wait = +e.target.value;
			editDuties(depot, duties => {
				const day = dutyForEditing(depot, duties.sidings[state.slot.siding][state.slot.index], state.weekday);
				const waits = Array.isArray(day.waits) && day.waits.length === day.segments.length - 1 ? day.waits : new Array(Math.max(0, day.segments.length - 1)).fill(0);
				waits[i] = wait;
				day.waits = waits;
				day.exceptions = (day.exceptions || 0) | EXCEPTION.WAITS;
			});
		}
		if (e.target.matches("[data-duty-stay]") && depot) {
			const stay = e.target.checked;
			editDuties(depot, duties => { dutyForEditing(depot, duties.sidings[state.slot.siding][state.slot.index], state.weekday).stay = stay; });
		}
	});
	/**
	 * A table's least performance as shown: acceleration and deceleration in m/s², highest speed in km/h, or empty if unset.
	 */
	function perfText(table, key) {
		const value = key === "a" ? table.minAcceleration : key === "d" ? table.minDeceleration : table.minMaxSpeed;
		if (!(value > 0)) return "";
		return key === "v" ? String(Math.round(value * 72)) : (value * 400).toFixed(2);
	}

	let drag = null, lastClick = null;

	document.addEventListener("keydown", e => {
		if (!inTimetable()) return;
		if (e.key === "Escape" && state.slot && e.target.tagName !== "INPUT") {
			state.slot = null;
			renderAll();
			return;
		}
		if (e.key === "Escape" && (state.selected || state.selectedRoute || state.range) && e.target.tagName !== "INPUT") {
			state.selectedRoute = null;
			state.range = null;
			selectService(null, false);
		}
	});
	new ResizeObserver(() => draw()).observe(wrap);
	// The line of the time now moves on
	setInterval(() => { if (data) draw(); }, 30000);
	addEventListener("resize", () => { if (inTimetable()) renderAll(); });
	matchMedia("(prefers-color-scheme: dark)").addEventListener("change", draw);

	const nowDay = () => data ? Math.floor((Date.now() - data.dayStartMillis) / DAY) : Math.floor(Date.now() / DAY);

	return {
		fn: { saveDraft, toggleReleased, railMenu, shortfallText, missingTrains, replanText, shortfallsOf, setSpeedLimit, setConnecting, setManaged, draftOperations, renderDraftState, poll, setData, setWeekday, renderTree, onTreeClick, openView, netGeometry, netDrawingPoints, netStyles, setNetLayer, loadNetwork, watchNetwork, indexNetwork, netChains, chainShape, simplifyPoints, octilinear, cutSteps, fitNetwork, ownerText, ownerColor, logicalMenu, ownersMenu, netStatus, drawNetwork, drawNetworkFrame, netRailAt, netSegmentDistance, netRailText, consoleRail, drawConsole, renderConsole, renderNetworkTools, startPicking, stopPicking, addPickedStop, refreshPreview, netStationAt, netNodeAt, pickAt, drawPicking, onWayPickPanelClick, depotLines, rulePlatform, ruleTargets, startWayPick, refreshWayPick, wayPickAt, toggleWayRail, drawWayPick, finishWayPick, ensureNetworkFor, accessLegs, findAccessLegs, computeLayout, clampView, draw, stopsHtml, renderInspector, showTooltip, showDragTip, centerOn, zoom, renderSimStatus, deadheadsByService, renderTable, durationText, addBeside, nextServiceItems, stationNameOf, stopPositionText, cellMenu, trackTitle, terminalText, selectSlot, chainBetween, addRangeToSlot, toggleInSlot, plannedReturnText, legTimes, deadheadTimes, editDutyException, orderIssueText, shortTurnaroundText, poolFleetText, vehicleSummary, setDutyMode, changeSlots, buildTopology, trackInfo, trackLabel, lockOffText, counterpartOf, typedName, keepType, removeCreated, selectService, renderBanner, renderSyncButton, renderStatus, renderAll, perfText },
		v: {
			get DAY() { return DAY },
			get POLL_MILLIS() { return POLL_MILLIS },
			get POLL_HIDDEN_MILLIS() { return POLL_HIDDEN_MILLIS },
			get LABEL_W() { return LABEL_W },
			get AXIS_H() { return AXIS_H },
			get MIN_ROW_GAP() { return MIN_ROW_GAP },
			get PAD_BOTTOM() { return PAD_BOTTOM },
			get DEPOT_ROW_ROOM() { return DEPOT_ROW_ROOM },
			get ZOOMS() { return ZOOMS },
			get MAX_SERVICES() { return MAX_SERVICES },
			get MAX_TABLES() { return MAX_TABLES },
			get MAX_LAYOVER_MILLIS() { return MAX_LAYOVER_MILLIS },
			get FIRST_DAY_WEEKDAY() { return FIRST_DAY_WEEKDAY },
			get $() { return $ },
			get css() { return css },
			get escapeHtml() { return escapeHtml },
			get colorOf() { return colorOf },
			get mod() { return mod },
			get shortName() { return shortName },
			get key() { return key },
			get inTimetable() { return inTimetable },
			get data() { return data }, set data(value) { data = value },
			get revision() { return revision }, set revision(value) { revision = value },
			get lastLoaded() { return lastLoaded }, set lastLoaded(value) { lastLoaded = value },
			get connection() { return connection }, set connection(value) { connection = value },
			get coverage() { return coverage }, set coverage(value) { coverage = value },
			get terminal() { return terminal }, set terminal(value) { terminal = value },
			get draft() { return draft }, set draft(value) { draft = value },
			get draftLoaded() { return draftLoaded }, set draftLoaded(value) { draftLoaded = value },
			get draftTimer() { return draftTimer }, set draftTimer(value) { draftTimer = value },
			get applyResult() { return applyResult }, set applyResult(value) { applyResult = value },
			get applying() { return applying }, set applying(value) { applying = value },
			get state() { return state },
			get dutyNotice() { return dutyNotice }, set dutyNotice(value) { dutyNotice = value },
			get pendingOpen() { return pendingOpen }, set pendingOpen(value) { pendingOpen = value },
			get EDITOR_PARTS() { return EDITOR_PARTS },
			get draftOutOfDate() { return draftOutOfDate },
			get isManaged() { return isManaged },
			get isConnecting() { return isConnecting },
			get speedLimitOf() { return speedLimitOf },
			get isDeleted() { return isDeleted },
			get boundaryErrors() { return boundaryErrors },
			get railKey() { return railKey },
			get releasedOf() { return releasedOf },
			get servedStops() { return servedStops },
			get PATTERN_HINT() { return PATTERN_HINT },
			get tableMessage() { return tableMessage }, set tableMessage(value) { tableMessage = value },
			get menu() { return menu }, set menu(value) { menu = value },
			get subMenu() { return subMenu }, set subMenu(value) { subMenu = value },
			get browserMenuFields() { return browserMenuFields },
			get clock() { return clock },
			get routeTitle() { return routeTitle },
			get canSplit() { return canSplit },
			get showsDiagram() { return showsDiagram },
			get showsTable() { return showsTable },
			get NET_CELL() { return NET_CELL },
			get NET_HIT_PX() { return NET_HIT_PX },
			get NET_STATION_SCALE() { return NET_STATION_SCALE },
			get NET_POLL_MS() { return NET_POLL_MS },
			get NET_EXACT_SCALE() { return NET_EXACT_SCALE },
			get NET_SIMPLIFY_PX() { return NET_SIMPLIFY_PX },
			get NET_ALIGNED() { return NET_ALIGNED },
			get netExact() { return netExact },
			get net() { return net },
			get netCanvas() { return netCanvas },
			get netWrap() { return netWrap },
			get netCtx() { return netCtx },
			get netGeometryCache() { return netGeometryCache },
			get netDetailCache() { return netDetailCache },
			get netRouteCache() { return netRouteCache },
			get netFramePending() { return netFramePending }, set netFramePending(value) { netFramePending = value },
			get netLevel() { return netLevel },
			get netX() { return netX },
			get netZ() { return netZ },
			get netWorldX() { return netWorldX },
			get netWorldZ() { return netWorldZ },
			get netRailKey() { return netRailKey },
			get sectionOf() { return sectionOf },
			get consoleState() { return consoleState },
			get picking() { return picking }, set picking(value) { picking = value },
			get stationPlatforms() { return stationPlatforms },
			get pickingRouteIds() { return pickingRouteIds },
			get rulesEditing() { return rulesEditing }, set rulesEditing(value) { rulesEditing = value },
			get wayPick() { return wayPick }, set wayPick(value) { wayPick = value },
			get wrap() { return wrap },
			get layout() { return layout }, set layout(value) { layout = value },
			get drawn() { return drawn }, set drawn(value) { drawn = value },
			get handles() { return handles }, set handles(value) { handles = value },
			get showAccess() { return showAccess }, set showAccess(value) { showAccess = value },
			get access() { return access },
			get xOf() { return xOf },
			get millisAt() { return millisAt },
			get tooltip() { return tooltip },
			get SIM_LATE_MILLIS() { return SIM_LATE_MILLIS },
			get SIM_MAX_LISTED() { return SIM_MAX_LISTED },
			get sim() { return sim },
			get formatDuration() { return formatDuration },
			get showDeadheads() { return showDeadheads }, set showDeadheads(value) { showDeadheads = value },
			get deadheadIndex() { return deadheadIndex }, set deadheadIndex(value) { deadheadIndex = value },
			get Batch() { return Batch },
			get tableRun() { return tableRun },
			get isBoundary() { return isBoundary },
			get stopName() { return stopName },
			get depotById() { return depotById },
			get startText() { return startText },
			get platformById() { return platformById },
			get platformText() { return platformText },
			get showNoTrain() { return showNoTrain }, set showNoTrain(value) { showNoTrain = value },
			get showDepots() { return showDepots }, set showDepots(value) { showDepots = value },
			get lastSlotService() { return lastSlotService }, set lastSlotService(value) { lastSlotService = value },
			get EXCEPTION() { return EXCEPTION },
			get hhmm() { return hhmm },
			get isOrderIssue() { return isOrderIssue },
			get plannedReturns() { return plannedReturns },
			get topo() { return topo }, set topo(value) { topo = value },
			get TRACK_DIRECTIONS() { return TRACK_DIRECTIONS },
			get notConfiguredText() { return notConfiguredText },
			get lockMark() { return lockMark },
			get withSuffix() { return withSuffix },
			get platformsOf() { return platformsOf },
			get addInputs() { return addInputs },
			get systemOfRoute() { return systemOfRoute },
			get systemOfLine() { return systemOfLine },
			get portrait() { return portrait },
			get drag() { return drag }, set drag(value) { drag = value },
			get lastClick() { return lastClick }, set lastClick(value) { lastClick = value },
			get nowDay() { return nowDay },
		},
	};
}
