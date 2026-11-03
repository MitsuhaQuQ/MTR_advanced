"use strict";

/*
 * The advanced editor: the routes of each line on a running diagram, and each route's timetable laid out like a printed
 * one, both editable. Changes are kept as a draft until applied to the game. The game serves this page to this computer
 * only, and the data needs the key in the address.
 */
(() => {
	const DAY = 24 * 60 * 60 * 1000;
	const POLL_MILLIS = 3000;
	// Room below the last station for the labels of trains carrying on to another route
	const LABEL_W = 120, AXIS_H = 28, MIN_ROW_GAP = 22, PAD_BOTTOM = 30;
	const ZOOMS = [0.5, 1, 2, 3, 4, 6, 8, 12, 16];
	const WEEKDAY_NAMES = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"];
	const WEEKDAY_SHORT = ["一", "二", "三", "四", "五", "六", "日"];
	// The same as ServiceExceptions.ADDED_STOP_MILLIS and RouteTimetable.MAX_SERVICES in the game
	const ADDED_STOP_MILLIS = 30000, MAX_SERVICES = 4096, MAX_TABLES = 16;
	// RouteTimetable.MAX_LAYOVER_MILLIS and MAX_STAY_MILLIS: how long a train waits for its next lap before going back to the depot
	const MAX_LAYOVER_MILLIS = 30 * 60000, MAX_STAY_MILLIS = 8 * DAY;
	// 1 January 1970, the first day of the epoch, was a Thursday
	const FIRST_DAY_WEEKDAY = 3;

	const $ = id => document.getElementById(id);
	const css = name => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
	const escapeHtml = text => String(text).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&#39;" })[c]);
	const colorOf = value => "#" + (value & 0xFFFFFF).toString(16).padStart(6, "0");
	const mod = (a, b) => ((a % b) + b) % b;
	// Names written as "中文|English" show their first part
	const shortName = name => String(name || "").split("||")[0].split("|")[0] || "（未命名）";

	const key = (() => {
		const match = location.hash.match(/key=([0-9a-f]+)/);
		try {
			if (match) sessionStorage.setItem("mtr-editor-key", match[1]);
			return match ? match[1] : sessionStorage.getItem("mtr-editor-key") || "";
		} catch (e) {
			return match ? match[1] : "";
		}
	})();
	if (location.hash) history.replaceState(null, "", location.pathname);

	let data = null, revision = null, lastLoaded = 0, connection = "connecting";
	/**
	 * Changes not yet applied to the game, saved as they are made so closing the page or the game doesn't lose them. Each
	 * route's entry keeps the revision it was made from, so a change made in the game since is noticed before applying.
	 */
	let draft = { routes: {}, created: [], duties: {} }, draftLoaded = false, draftTimer = null, applyResult = null, applying = false;
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
	};

	// ---------- Data ----------
	async function fetchJson(path) {
		const response = await fetch(path, { headers: { "X-MTR-Editor-Key": key }, cache: "no-store" });
		if (!response.ok) throw new Error(String(response.status));
		return response.json();
	}

	async function sendJson(path, method, body) {
		const response = await fetch(path, { method, headers: { "X-MTR-Editor-Key": key, "Content-Type": "application/json" }, body: JSON.stringify(body), cache: "no-store" });
		if (!response.ok) throw new Error(String(response.status));
		return response.json();
	}

	async function loadDraft() {
		try {
			const saved = await fetchJson("api/draft");
			draft = saved && saved.routes ? saved : { routes: {} };
		} catch (e) {
			draft = { routes: {} };
		}
		if (!Array.isArray(draft.created)) draft.created = [];
		if (!draft.duties || typeof draft.duties !== "object") draft.duties = {};
		draftLoaded = true;
	}

	/** Saves the draft shortly after the last change, so typing doesn't write it on every key. */
	function saveDraft() {
		clearTimeout(draftTimer);
		draftTimer = setTimeout(() => sendJson("api/draft", "PUT", draft).catch(() => {}), 400);
		renderDraftState();
	}

	// New types wait in the draft too, as what to make, until they are applied
	const draftCount = () => Object.keys(draft.routes).length + draft.created.length + Object.keys(draft.duties).length;
	// A message about the last thing done to duties, shown in the banner
	let dutyNotice = "";
	// The route to show once the game has made a new one
	let pendingOpen = null;
	const draftOf = r => draft.routes[r.id];
	const draftEntry = r => draft.routes[r.id] || (draft.routes[r.id] = { base: r.revision });
	/** A route's entry goes once nothing is left in it but where it came from. */
	function tidyDraft(id) {
		const entry = draft.routes[id];
		if (entry && Object.keys(entry).every(k => k === "base" || k === "force" || k === "error" || k === "conflict")) delete draft.routes[id];
	}
	const isManaged = r => draftOf(r) && "managed" in draftOf(r) ? draftOf(r).managed : r.editorManaged;
	// A type marked for deletion stays in the game until the draft is applied
	const isDeleted = r => Boolean(draftOf(r) && draftOf(r).deleted);

	function setManaged(r, managed) {
		const entry = draftEntry(r);
		if (managed === r.editorManaged) delete entry.managed;
		else entry.managed = managed;
		delete entry.error;
		tidyDraft(r.id);
		saveDraft();
		renderAll();
	}

	/** Sends the draft to the game. What the game takes leaves the draft; what it doesn't stays, with the reason. */
	async function applyDraft() {
		if (applying || !draftCount()) return;
		const operations = [], owners = [];
		Object.entries(draft.routes).forEach(([id, entry]) => {
			let base = entry.base;
			// Nothing else of a route being deleted is worth sending
			if (entry.deleted) {
				operations.push({ type: "deleteRoute", route: id, base, force: Boolean(entry.force) });
				owners.push(id);
				return;
			}
			if (entry.platforms) {
				operations.push({ type: "platforms", route: id, base, force: Boolean(entry.force), platforms: entry.platforms });
				owners.push(id);
				base = undefined;
			}
			if (entry.timetable) {
				operations.push({ type: "timetable", route: id, base, force: Boolean(entry.force), timetable: entry.timetable });
				owners.push(id);
				// The timetable changes the revision, so what follows for the route doesn't check it again
				base = undefined;
			}
			if ("managed" in entry) {
				operations.push({ type: "managed", route: id, base, force: Boolean(entry.force), managed: entry.managed });
				owners.push(id);
			}
		});
		Object.entries(draft.duties).forEach(([depotId, entry]) => {
			operations.push({ type: "duties", depot: depotId, duties: entry.duties });
			owners.push({ dutiesOf: depotId });
		});
		draft.created.forEach(made => {
			operations.push({ type: "newRoute", route: made.source, name: made.name, serviceType: made.serviceType, color: made.color, stops: made.stops, services: made.services, table: made.table, depot: made.depot || "" });
			owners.push(made);
		});
		applying = true;
		renderDraftState();
		try {
			const result = await sendJson("api/apply", "POST", { operations });
			(result.created || []).forEach(made => { if (!pendingOpen) pendingOpen = made.id; });
			const failed = new Set();
			(result.errors || []).forEach(error => {
				if (error.index < 0) return;
				const id = owners[error.index];
				failed.add(id);
				if (typeof id === "object") {
					if (id.dutiesOf) {
						if (draft.duties[id.dutiesOf]) draft.duties[id.dutiesOf].error = error.message;
					} else {
						id.error = error.message;
					}
					return;
				}
				const entry = draft.routes[id];
				if (!entry) return;
				entry.conflict = error.message === "CONFLICT";
				entry.error = entry.conflict ? "游戏里的这条线路在草稿之后改过" : error.message;
			});
			owners.forEach(id => { if (!failed.has(id) && typeof id === "string") delete draft.routes[id]; });
			draft.created = draft.created.filter(made => failed.has(made));
			owners.filter(owner => owner && owner.dutiesOf && !failed.has(owner)).forEach(owner => delete draft.duties[owner.dutiesOf]);
			applyResult = { ok: result.ok, general: (result.errors || []).filter(error => error.index < 0).map(error => error.message), applied: owners.filter(id => !failed.has(id)).length };
		} catch (e) {
			applyResult = { ok: false, general: [e.message === "401" ? "页面的链接已经失效" : "发送失败，游戏可能已经关闭"], applied: 0 };
		}
		applying = false;
		saveDraft();
		revision = null;
		poll(true);
		renderAll();
	}

	/** Keeps the draft over what changed in the game, or drops the draft for the game's version. */
	function resolveConflict(id, keep) {
		const entry = draft.routes[id], r = data && data.routeById.get(id);
		if (!entry) return;
		if (keep) {
			entry.force = true;
			if (r) entry.base = r.revision;
			delete entry.conflict;
			delete entry.error;
		} else {
			delete draft.routes[id];
		}
		saveDraft();
		renderAll();
	}

	function renderDraftState() {
		const count = draftCount();
		const tag = $("draft-tag");
		tag.textContent = applying ? "正在应用…" : count ? `草稿 ${count} 项` : "没有草稿";
		tag.classList.toggle("has-draft", count > 0);
		$("btn-apply").disabled = applying || !count || !(data && data.canEdit);
		const selected = data && state.route && data.routeById.get(state.route);
		const button = $("btn-delete");
		button.textContent = selected && isDeleted(selected) ? "取消删除" : "删除种别";
		button.disabled = !selected || !data.canEdit;
		button.title = selected ? (isDeleted(selected) ? "不删除" : "删除") + "“" + routeTitle(selected) + "”" : "先选中一个种别";
	}

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
		if (!once) setTimeout(poll, POLL_MILLIS);
	}

	/** The routes of each line, the stations of its diagram, and each route's services on the chosen day. */
	function setData(newData) {
		data = newData;
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

	/**
	 * One row for each station of a line, in the order of its longest route. A station only other routes stop at goes
	 * after the station they stop at before it. A route running the other way meets the same rows from the bottom up, and a
	 * loop's first station, met again at the end, gets a second row there.
	 */
	function buildStations(line) {
		const rows = [];
		const indexOf = () => new Map(rows.map((row, i) => [row.id, i]));
		const stopIds = r => {
			const seen = new Set();
			return r.stops.map(s => {
				const id = s.station || "?" + s.name;
				const rowId = seen.has(id) ? id + "#2" : id;
				seen.add(id);
				return rowId;
			});
		};
		[...line.routes].sort((a, b) => b.stops.length - a.stops.length).forEach(r => {
			const ids = stopIds(r), names = r.stops.map(s => s.name);
			let index = indexOf();
			const known = ids.filter(id => index.has(id)).map(id => index.get(id));
			const reversed = known.length > 1 && known[0] > known[known.length - 1];
			const order = ids.map((id, i) => i);
			if (reversed) order.reverse();
			let previous = -1;
			order.forEach(i => {
				if (index.has(ids[i])) {
					previous = index.get(ids[i]);
				} else {
					rows.splice(previous + 1, 0, { id: ids[i], name: names[i], gap: 0 });
					index = indexOf();
					previous++;
				}
			});
		});
		const index = indexOf();
		line.routes.forEach(r => r.rows = stopIds(r).map(id => index.get(id)));

		// The space above each row follows the fastest running time to it, half and half with even spacing, so close
		// stations still have room
		const gaps = rows.map(() => []);
		line.routes.forEach(r => {
			const plans = r.tables.length ? r.tables[0].plans : [];
			for (let i = 1; i < r.rows.length; i++) {
				const a = r.rows[i - 1], b = r.rows[i];
				if (Math.abs(a - b) === 1 && plans[i] && r.hasTimes) gaps[Math.max(a, b)].push(Math.max(0, plans[i][0] - plans[i - 1][1]));
			}
		});
		const typical = gaps.flat().length ? gaps.flat().reduce((a, b) => a + b, 0) / gaps.flat().length : 1;
		rows.forEach((row, i) => row.gap = i === 0 ? 0 : 0.5 + 0.5 * (gaps[i].length ? Math.min(...gaps[i]) / (typical || 1) : 1));
		line.rows = rows;
	}

	/**
	 * The planned arrival and departure at each stop in milliseconds from the first stop, worked out the same way as
	 * RouteTimetable.getStopPlans and applyPlan in the game, so a draft shows the times the game will use once applied.
	 *
	 * @param stopExceptions where one service runs differently, by stop, or null
	 */
	function computePlans(r, table, pass, stopExceptions) {
		const n = r.stops.length, times = r.hasTimes ? r.fastest : null;
		const stops = [];
		let last = 0;
		for (let i = 0; i < n; i++) {
			const hasTime = Boolean(times) && times[i][0] >= 0;
			const isPass = pass[i] && i > 0 && i < n - 1;
			const arrival = hasTime ? times[i][0] : last;
			const departure = !hasTime ? last : isPass ? arrival : times[i][1];
			last = departure;
			const set = table.stops[i] || { arrival: -1, departure: -1, overtake: false };
			const exception = stopExceptions ? stopExceptions.get(i) : null;
			stops.push({
				fastestArrival: arrival, fastestDeparture: departure, setArrival: set.arrival, setDeparture: set.departure,
				run: exception && exception.run >= 0 ? exception.run * 1000 : -1,
				dwell: exception && exception.dwell >= 0 ? exception.dwell * 1000 : -1,
				pass: exception ? exception.pass : -1,
				overtake: exception && exception.overtake >= 0 ? exception.overtake === 1 : set.overtake,
			});
		}
		const addMargin = millis => Math.trunc(millis * (100 + table.marginPercent) / 100);
		let lastFastestDeparture = 0, lastDeparture = 0;
		return stops.map((p, i) => {
			const automatic = i === 0 ? 0 : lastDeparture + addMargin(p.fastestArrival - lastFastestDeparture);
			const arrival = i > 0 && p.run >= 0 ? lastDeparture + p.run : i > 0 && p.setArrival >= 0 ? p.setArrival * 1000 : automatic;
			const automaticDwell = p.pass === 0 && p.fastestDeparture === p.fastestArrival ? ADDED_STOP_MILLIS : p.fastestDeparture - p.fastestArrival;
			const middle = i > 0 && i < n - 1;
			const departure = p.dwell >= 0 ? arrival + p.dwell : p.pass === 1 && middle ? arrival : p.setDeparture >= 0 ? Math.max(p.setDeparture * 1000, arrival) : arrival + automaticDwell;
			// Faster than the train can run, which the game can't keep to
			const tooFast = i > 0 && Boolean(times) && arrival < lastDeparture + p.fastestArrival - lastFastestDeparture - 500;
			lastFastestDeparture = p.fastestDeparture;
			lastDeparture = departure;
			return { arrival, departure, overtake: p.overtake && middle, passes: middle && (p.pass === 1 || p.pass !== 0 && pass[i]), tooFast };
		});
	}

	/** The table a route runs on a day for each of its services, which is the first of its tables that has it that day. */
	function buildServices() {
		const weekday = state.weekday;
		data.routes.forEach(r => {
			const timetable = timetableOf(r);
			const services = new Map();
			timetable.tables.forEach((table, tableIndex) => {
				if ((table.weekdays >> weekday & 1) === 0) return;
				const tablePlans = computePlans(r, table, timetable.pass, null);
				const exceptions = new Map(table.exceptions.map(exception => [exception.service, new Map(exception.stops.map(stop => [stop.stop, stop]))]));
				table.services.forEach(service => {
					if (services.has(service)) return;
					const stopExceptions = exceptions.get(service) || null;
					services.set(service, {
						id: r.id + "@" + service, route: r, service, table, tableIndex,
						link: table.links.find(l => l.service === service),
						returns: table.returns.includes(service),
						// The time in the operating day, so the last trains after midnight come after the first ones
						start: mod(service - data.dayStartMillis, DAY),
						plans: stopExceptions ? computePlans(r, table, timetable.pass, stopExceptions) : tablePlans,
						tablePlans, exceptions: stopExceptions,
					});
				});
			});
			r.services = [...services.values()].sort((a, b) => a.start - b.start);
		});
	}

	const findService = id => {
		const [routeId, service] = id.split("@");
		const r = data.routeById.get(routeId);
		return r && r.services ? r.services.find(s => s.service === Number(service)) : null;
	};

	/** Arrival and departure at each stop, in milliseconds into the operating day. */
	const timesOf = s => s.plans.map(plan => [s.start + plan.arrival, s.start + plan.departure]);

	// ---------- Editing ----------
	const daysText = mask => mask ? WEEKDAY_SHORT.filter((_, i) => mask >> i & 1).join("") : "不运行";
	const tableName = (table, index) => table.name || "表 " + (index + 1);

	/** Sorted the same way however it was made, so a draft changed back to the game's matches it. */
	function normalize(timetable) {
		timetable.tables.forEach(table => {
			table.services.sort((a, b) => a - b);
			table.returns.sort((a, b) => a - b);
			table.links.sort((a, b) => a.service - b.service);
			table.exceptions.sort((a, b) => a.service - b.service);
			table.exceptions.forEach(exception => exception.stops.sort((a, b) => a.stop - b.stop));
		});
		return timetable;
	}

	/** A route's timetable from the game, in the form the editor sends it back. */
	function fromGame(r) {
		return normalize({
			enabled: r.timetable, entryPlatform: r.entryPlatform, pass: r.stops.map(stop => stop.pass),
			tables: r.tables.map(table => ({
				name: table.name, weekdays: table.weekdays, marginPercent: table.marginPercent,
				services: [...table.services], returns: [...table.returns],
				links: table.links.map(link => ({ ...link })),
				stops: table.stops.map(stop => ({ ...stop })),
				exceptions: (table.exceptions || []).map(exception => ({ service: exception.service, stops: exception.stops.map(stop => ({ stop: stop.stop, run: stop.run, dwell: stop.dwell, pass: stop.pass, overtake: stop.overtake })) })),
			})),
		});
	}

	const gameTimetable = r => r.gameTimetable || (r.gameTimetable = fromGame(r));
	const timetableOf = r => draftOf(r) && draftOf(r).timetable ? draftOf(r).timetable : gameTimetable(r);

	/**
	 * Changes a route's timetable in the draft, starting from a copy of the game's. A draft changed back to the game's is
	 * dropped, so it doesn't sit there waiting to be applied.
	 */
	function editTimetable(r, change) {
		const entry = draftEntry(r);
		if (!entry.timetable) entry.timetable = JSON.parse(JSON.stringify(gameTimetable(r)));
		const message = change(entry.timetable);
		normalize(entry.timetable);
		if (JSON.stringify(entry.timetable) === JSON.stringify(gameTimetable(r))) delete entry.timetable;
		delete entry.error;
		tidyDraft(r.id);
		saveDraft();
		buildServices();
		if (state.selected && !findService(state.selected)) state.selected = null;
		tableMessage = typeof message === "string" ? message : "";
		renderAll();
	}

	/** Changes what one service does at one stop. A value of -1 follows the table again. */
	function setStopException(table, service, stop, changes) {
		let exception = table.exceptions.find(e => e.service === service);
		if (!exception) table.exceptions.push(exception = { service, stops: [] });
		let stopException = exception.stops.find(e => e.stop === stop);
		if (!stopException) exception.stops.push(stopException = { stop, run: -1, dwell: -1, pass: -1, overtake: -1 });
		Object.assign(stopException, changes);
		if (stopException.run < 0 && stopException.dwell < 0 && stopException.pass < 0 && stopException.overtake < 0) exception.stops.splice(exception.stops.indexOf(stopException), 1);
		if (!exception.stops.length) table.exceptions.splice(table.exceptions.indexOf(exception), 1);
	}

	/** Moves a service to another time, with what belongs to it. @return why it can't, or nothing */
	function retimeService(table, oldService, newService) {
		if (oldService === newService) return;
		if (table.services.includes(newService)) return "这张表已经有这个时刻的班次";
		table.services[table.services.indexOf(oldService)] = newService;
		table.links.forEach(link => { if (link.service === oldService) link.service = newService; });
		table.returns = table.returns.map(service => service === oldService ? newService : service);
		table.exceptions.forEach(exception => { if (exception.service === oldService) exception.service = newService; });
	}

	function removeService(table, service) {
		table.services = table.services.filter(s => s !== service);
		table.links = table.links.filter(link => link.service !== service);
		table.returns = table.returns.filter(s => s !== service);
		table.exceptions = table.exceptions.filter(exception => exception.service !== service);
	}

	// Times are typed as 7:10, 07:10, 07:10:30, 0710 or 071030, with full-width digits and colons from an input method too
	function parseClock(text) {
		const clean = text.replace(/[０-９]/g, d => String.fromCharCode(d.charCodeAt(0) - 0xFF10 + 48)).replace(/[：．。.]/g, ":").replace(/\s+/g, "");
		const m = /^(\d{1,2}):(\d{2})(?::(\d{2}))?$/.exec(clean) || /^(\d{2})(\d{2})(\d{2})?$/.exec(clean);
		if (!m || +m[1] > 29 || +m[2] > 59 || +(m[3] || 0) > 59) return null;
		return ((+m[1] * 60 + +m[2]) * 60 + +(m[3] || 0)) * 1000;
	}

	/** A typed local time as a time in the operating day, on the side of midnight nearest to an old time. */
	function typedTime(text, near) {
		const local = parseClock(text);
		if (local === null) return null;
		const oldLocal = mod(near + data.dayStartMillis + data.offsetMillis, DAY);
		return near + mod(local - oldLocal + DAY / 2, DAY) - DAY / 2;
	}

	const PATTERN_HINT = "写成 07:10，或 07:10 + 10 * 00:03（07:10 起每 3 分钟一班，再加 10 班）";
	/** @return the services, in UTC milliseconds of the day, that a pattern like "07:10 + 10 * 00:03" stands for */
	function parsePattern(value) {
		const text = value.replace(/[０-９]/g, d => String.fromCharCode(d.charCodeAt(0) - 0xFF10 + 48)).replace(/[：]/g, ":").replace(/＋/g, "+").replace(/[＊×]/g, "*");
		const m = /^\s*([\d:]+)\s*(?:\+\s*(\d+)\s*\*\s*([\d:]+))?\s*$/.exec(text);
		const first = m && parseClock(m[1]), step = m && m[3] ? parseClock(m[3]) : 0, count = m && m[2] ? +m[2] : 0;
		if (first === null || first === undefined || (m[3] && !step) || count > 1000) return null;
		return Array.from({ length: count + 1 }, (_, k) => mod(first + k * step - data.offsetMillis, DAY));
	}

	let tableMessage = "";

	// ---------- Menus and dialogs ----------
	let menu = null;
	function openMenu(x, y, items) {
		closeMenu();
		menu = document.createElement("div");
		menu.className = "menu";
		items.forEach(([label, action]) => {
			const button = document.createElement("button");
			button.textContent = label;
			if (action) button.onclick = () => { closeMenu(); action(); };
			else button.disabled = true;
			menu.appendChild(button);
		});
		document.body.appendChild(menu);
		menu.style.left = Math.min(x, innerWidth - menu.offsetWidth - 8) + "px";
		menu.style.top = Math.min(y, innerHeight - menu.offsetHeight - 8) + "px";
	}
	function closeMenu() {
		if (menu) menu.remove();
		menu = null;
	}
	document.addEventListener("click", e => { if (menu && !menu.contains(e.target)) closeMenu(); });

	const dialog = $("dialog");
	/** A form whose OK button runs onOk, which returns an error to show and keep the form open, or nothing to close it. */
	function openDialog(title, body, okLabel, onOk, onInput) {
		closeMenu();
		dialog.innerHTML = `<form method="dialog"><h2>${title}</h2>${body}<p class="err" id="dialog-error"></p>
			<div class="actions"><button value="cancel" formnovalidate>取消</button><button class="primary" value="ok">${okLabel}</button></div></form>`;
		const form = dialog.querySelector("form");
		form.addEventListener("submit", e => {
			if (!e.submitter || e.submitter.value !== "ok") return;
			const error = onOk(form);
			if (error) {
				e.preventDefault();
				$("dialog-error").textContent = error;
			}
		});
		if (onInput) {
			form.addEventListener("input", () => onInput(form));
			onInput(form);
		}
		dialog.showModal();
	}

	/**
	 * A new table, or the name and days of one. A day has one table, so the days ticked here leave the route's other tables.
	 *
	 * @param tableIndex the table to change, or -1 for a new one
	 */
	function tableDialog(r, tableIndex) {
		const timetable = timetableOf(r), isNew = tableIndex < 0, table = isNew ? null : timetable.tables[tableIndex];
		const line = data.lines.find(l => l.key === r.lineKey);
		openDialog(isNew ? "新建时刻表" : "时刻表设置", `
			<label>表名 <input name="name" maxlength="32" value="${escapeHtml(table ? table.name : "")}" placeholder="休日"></label>
			<fieldset id="dialog-days"><legend>运行的星期</legend></fieldset>
			${isNew ? `<label>班次 <select name="copy"><option value="-1">空表</option>${timetable.tables.map((t, i) => `<option value="${i}">复制“${escapeHtml(tableName(t, i))}”的班次</option>`).join("")}</select></label>
			<label><input type="checkbox" name="all" ${line && line.routes.length > 1 ? "checked" : "disabled"}> ${escapeHtml(shortName(r.line))}的全部线路一起建</label>` : ""}
			<p class="hint">一天只用一张表，勾选的星期从原来的表移到这张表。复制的班次连同只改这一班的例外一起复制。</p>`, isNew ? "新建" : "确定", form => {
			const mask = [...form.querySelectorAll("#dialog-days input:checked")].reduce((m, input) => m | 1 << +input.value, 0);
			const name = form.name.value.trim();
			const routes = isNew && form.all && form.all.checked ? line.routes : [r];
			if (isNew && routes.some(route => timetableOf(route).tables.length >= MAX_TABLES)) return `一条线路最多 ${MAX_TABLES} 张表`;
			routes.forEach(route => editTimetable(route, edited => {
				edited.tables.forEach((t, i) => { if (i !== tableIndex) t.weekdays &= ~mask; });
				if (isNew) {
					const copied = +form.copy.value >= 0 && edited.tables[+form.copy.value];
					edited.tables.push({
						name, weekdays: mask, marginPercent: copied ? copied.marginPercent : edited.tables[0].marginPercent,
						services: copied ? [...copied.services] : [], returns: copied ? [...copied.returns] : [],
						links: copied ? copied.links.map(link => ({ ...link })) : [],
						stops: (copied || edited.tables[0]).stops.map(stop => ({ ...stop })),
						exceptions: copied ? JSON.parse(JSON.stringify(copied.exceptions)) : [],
					});
				} else {
					edited.tables[tableIndex].name = name;
					edited.tables[tableIndex].weekdays = mask;
				}
			}));
			if (mask) setWeekday(WEEKDAY_SHORT.findIndex((_, i) => mask >> i & 1));
		}, form => {
			const ticked = new Set([...form.querySelectorAll("#dialog-days input:checked")].map(input => input.value));
			const first = !form.querySelector("#dialog-days input");
			form.querySelector("#dialog-days").innerHTML = `<legend>运行的星期</legend>` + WEEKDAY_SHORT.map((day, i) => {
				const owner = timetable.tables.findIndex(t => t.weekdays >> i & 1);
				const checked = first ? table && table.weekdays >> i & 1 : ticked.has(String(i));
				return `<label><input type="checkbox" value="${i}" ${checked ? "checked" : ""}>周${day}<span class="owner">${owner < 0 ? "无" : escapeHtml(tableName(timetable.tables[owner], owner))}</span></label>`;
			}).join("");
		});
	}

	function setWeekday(weekday) {
		state.weekday = weekday;
		$("day").value = String(weekday);
		buildServices();
		if (state.selected && !findService(state.selected)) state.selected = null;
		renderAll();
	}

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
	function renderTree() {
		const tree = $("tree");
		if (!data) {
			tree.innerHTML = `<div class="empty">正在读取…</div>`;
			return;
		}
		const node = (attrs, caret, content, active) => `<div class="node${active ? " active" : ""}" tabindex="0" ${attrs}><span class="caret">${caret}</span>${content}</div>`;
		const parts = [`<div class="section">线路</div><ul>`];
		if (!data.lines.length) parts.push(`<li class="empty">没有线路</li>`);
		data.lines.forEach(line => {
			const open = state.openNodes.has("line:" + line.key) || line.key === state.line;
			const count = line.routes.reduce((a, r) => a + r.services.length, 0);
			parts.push(`<li>${node(`data-line="${escapeHtml(line.key)}"`, open ? "▾" : "▸", `<span class="swatch" style="background:${colorOf(line.color)}"></span><span class="label group-label">${escapeHtml(shortName(line.name))}</span><span class="count">${count}</span>`, state.line === line.key && !state.route && state.view === "diagram")}`);
			if (open) {
				parts.push(`<ul>`);
				line.routes.forEach(r => {
					const routeOpen = state.openNodes.has("route:" + r.id) || state.route === r.id;
					parts.push(`<li>${node(`data-route="${r.id}"`, routeOpen ? "▾" : "▸", `<span class="swatch" style="background:${colorOf(r.color)}"></span><span class="label${isDeleted(r) ? " deleted" : ""}">${escapeHtml(routeTitle(r))}</span>${isDeleted(r) ? `<span class="draft-dot" title="${escapeHtml(draftOf(r).error || "将删除：按“应用”后从游戏里删掉，右键取消")}">${draftOf(r).error ? "!" : "−"}</span>` : draftOf(r) ? `<span class="draft-dot" title="有没应用的草稿">•</span>` : ""}${problemDot(r)}<span class="count">${r.services.length}</span>`, false)}`);
					if (routeOpen) {
						parts.push(`<ul>`);
						parts.push(`<li>${node(`data-route="${r.id}" data-view="table"`, "", `<span class="label leaf">汇总时刻表</span>`, state.route === r.id && state.view === "table")}</li>`);
						parts.push(`<li>${node(`data-route="${r.id}" data-view="diagram"`, "", `<span class="label leaf">运行图</span>`, state.route === r.id && state.view === "diagram")}</li>`);
						parts.push(`</ul>`);
					}
					parts.push(`</li>`);
				});
				draft.created.filter(made => made.lineKey === line.key).forEach(made => {
					parts.push(`<li>${node(`data-created="${made.key}"`, "", `<span class="swatch" style="background:${colorOf(made.color)}"></span><span class="label">＋ ${escapeHtml(shortName(made.name))}</span><span class="draft-dot" title="${escapeHtml(made.error || "新建的种别，应用后才在游戏里出现；右键删除")}">${made.error ? "!" : "•"}</span>`, false)}</li>`);
				});
				parts.push(`</ul>`);
			}
			parts.push(`</li>`);
		});
		parts.push(`</ul><div class="section">车厂</div><ul>`);
		if (!data.depots.length) parts.push(`<li class="empty">没有车厂</li>`);
		data.depots.forEach(depot => {
			const open = state.openNodes.has("depot:" + depot.id);
			const duties = dutiesOf(depot);
			parts.push(`<li>${node(`data-depot="${depot.id}"`, open ? "▾" : "▸", `<span class="label group-label">${escapeHtml(shortName(depot.name))}</span>${draft.duties[depot.id] ? `<span class="draft-dot" title="${escapeHtml(draft.duties[depot.id].error || "运用有没应用的草稿")}">${draft.duties[depot.id].error ? "!" : "•"}</span>` : ""}<span class="count">${duties.mode === "diagram" ? "按运用图" : "在线预约"}</span>`, false)}`);
			if (open) {
				parts.push(`<ul>`);
				if (!depot.sidings.length) parts.push(`<li class="empty">没有存车线</li>`);
				depot.sidings.forEach(siding => {
					const slots = duties.sidings[siding.id] || [];
					const over = siding.maxTrains >= 0 && slots.length > siding.maxTrains;
					parts.push(`<li>${node(`data-siding="${siding.id}" data-of-depot="${depot.id}"`, slots.length ? "▾" : "", `<span class="label leaf">${escapeHtml(shortName(siding.name) || "存车线")}</span><span class="count${over ? " warn" : ""}">${slots.length}/${siding.maxTrains < 0 ? "不限" : siding.maxTrains}</span>`, false)}`);
					if (slots.length) {
						parts.push(`<ul>`);
						slots.forEach((slot, index) => {
							const day = dutyOn(slot, state.weekday);
							const active = state.slot && state.slot.depot === depot.id && state.slot.siding === siding.id && state.slot.index === index;
							const problems = day ? dutyProblems(depot, siding.id, index, state.weekday).length : 0;
							parts.push(`<li>${node(`data-slot="${index}" data-siding="${siding.id}" data-of-depot="${depot.id}"`, "", `<span class="label leaf">车 ${index + 1}</span>${problems ? `<span class="draft-dot" title="运用有问题">!</span>` : ""}<span class="count">${day ? day.segments.length + " 班" + (day.stay ? " 留" : "") : "休"}</span>`, active)}</li>`);
						});
						parts.push(`</ul>`);
					}
					parts.push(`</li>`);
				});
				parts.push(`</ul>`);
			}
			parts.push(`</li>`);
		});
		parts.push(`</ul>`);
		tree.innerHTML = parts.join("");
	}

	function onTreeClick(target) {
		const el = target.closest(".node");
		if (!el) return;
		const { line, route, view, depot, slot, siding, ofDepot } = el.dataset;
		if (slot !== undefined) {
			selectSlot(ofDepot, siding, +slot);
			return;
		}
		if (line) {
			const nodeKey = "line:" + line;
			if (state.line === line && !state.route && state.view === "diagram") {
				if (!state.openNodes.delete(nodeKey)) state.openNodes.add(nodeKey);
			} else {
				state.openNodes.add(nodeKey);
			}
			openView("diagram", line, null);
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
	const showsDiagram = () => state.view === "diagram" || (state.split && canSplit());
	const showsTable = () => state.view === "table" || (state.split && canSplit());

	function openView(view, lineKey, routeId) {
		if (lineKey !== state.line) state.selected = null;
		state.view = view;
		state.line = lineKey;
		state.route = routeId;
		if (state.selected && routeId && !state.selected.startsWith(routeId + "@")) state.selected = null;
		if (portrait.matches) setTreeOpen(false);
		renderAll();
	}

	function renderViews() {
		const split = state.split && canSplit();
		document.body.classList.toggle("split", split);
		$("btn-split").hidden = !canSplit();
		$("btn-split").setAttribute("aria-pressed", String(split));
		$("view-diagram").hidden = !showsDiagram();
		$("view-table").hidden = !showsTable();
		$("tab-diagram").setAttribute("aria-selected", String(state.view === "diagram"));
		$("tab-table").setAttribute("aria-selected", String(state.view === "table"));
		const line = currentLine(), r = state.route ? data && data.routeById.get(state.route) : null;
		$("crumb").textContent = line ? shortName(line.name) + (r ? " › " + routeTitle(r) : "") : "";
	}

	const currentLine = () => data && data.lines.find(l => l.key === state.line);

	// ---------- Running diagram ----------
	const canvas = $("canvas"), wrap = $("canvas-wrap"), ctx = canvas.getContext("2d");
	let layout = null, drawn = [], handles = [];

	function computeLayout() {
		const line = currentLine();
		if (!line) return null;
		const height = wrap.clientHeight;
		const totalGap = line.rows.reduce((a, row) => a + row.gap, 0) || 1;
		const unit = Math.max(MIN_ROW_GAP, (height - AXIS_H - PAD_BOTTOM - 12) / totalGap);
		let y = AXIS_H + 12;
		const ys = line.rows.map(row => (y += row.gap * unit));
		return { line, ys, contentHeight: y + PAD_BOTTOM };
	}

	const xOf = millis => LABEL_W + (millis - state.viewStart) / 60000 * state.pxPerMin;
	const millisAt = x => state.viewStart + (x - LABEL_W) / state.pxPerMin * 60000;

	function resizeCanvas() {
		const ratio = devicePixelRatio || 1;
		canvas.width = Math.max(1, wrap.clientWidth * ratio);
		canvas.height = Math.max(1, wrap.clientHeight * ratio);
		canvas.style.width = wrap.clientWidth + "px";
		canvas.style.height = wrap.clientHeight + "px";
		ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
	}

	function clampView() {
		const visible = (wrap.clientWidth - LABEL_W) / state.pxPerMin * 60000;
		state.viewStart = Math.min(Math.max(state.viewStart, -3600000), DAY + 6 * 3600000 - visible);
		const maxScroll = layout ? Math.max(0, layout.contentHeight - wrap.clientHeight) : 0;
		state.scrollY = Math.min(Math.max(state.scrollY, 0), maxScroll);
	}

	function draw() {
		if (!showsDiagram()) return;
		resizeCanvas();
		layout = computeLayout();
		clampView();
		const w = wrap.clientWidth, h = wrap.clientHeight;
		const c = { panel: css("--panel"), panel2: css("--panel-2"), ink: css("--ink"), muted: css("--muted"), grid: css("--grid"), strong: css("--grid-strong"), sel: css("--sel"), warn: css("--warn") };
		ctx.fillStyle = c.panel;
		ctx.fillRect(0, 0, w, h);
		drawn = [];
		handles = [];
		if (!layout) {
			ctx.fillStyle = c.muted;
			ctx.font = `13px ${css("--font-ui")}`;
			ctx.fillText(data ? "没有线路" : "正在读取…", 16, 32);
			return;
		}
		const { line, ys } = layout;
		const yOf = row => ys[row] - state.scrollY;

		// Hour and ten minute lines
		const step = state.pxPerMin >= 4 ? 10 : state.pxPerMin >= 1 ? 30 : 60;
		const first = Math.floor(state.viewStart / 60000 / step) * step;
		ctx.font = `11px ${css("--font-num")}`;
		ctx.textAlign = "center";
		for (let m = first; xOf(m * 60000) < w; m += step) {
			const x = xOf(m * 60000);
			if (x < LABEL_W) continue;
			const hour = mod(m * 60000 + data.dayStartMillis + data.offsetMillis, DAY) % 3600000 === 0;
			ctx.strokeStyle = hour ? c.strong : c.grid;
			ctx.lineWidth = 1;
			ctx.beginPath();
			ctx.moveTo(Math.round(x) + 0.5, AXIS_H);
			ctx.lineTo(Math.round(x) + 0.5, h);
			ctx.stroke();
			if (hour || state.pxPerMin >= 4) {
				ctx.fillStyle = hour ? c.ink : c.muted;
				ctx.fillText(clock(m * 60000), x, AXIS_H - 10);
			}
		}

		// Station lines
		ctx.save();
		ctx.beginPath();
		ctx.rect(0, AXIS_H, w, h - AXIS_H);
		ctx.clip();
		line.rows.forEach((row, i) => {
			const y = Math.round(yOf(i)) + 0.5;
			ctx.strokeStyle = c.grid;
			ctx.beginPath();
			ctx.moveTo(LABEL_W, y);
			ctx.lineTo(w, y);
			ctx.stroke();
		});

		// Services, the selected one last so it's on top
		ctx.beginPath();
		ctx.rect(LABEL_W, AXIS_H, w - LABEL_W, h - AXIS_H);
		ctx.clip();
		const services = line.routes.flatMap(r => r.services);
		const selectedRoute = state.route;
		const loopNext = new Map();
		line.routes.filter(isLoop).forEach(r => loopRuns(r).next.forEach((following, id) => loopNext.set(id, following)));
		services.sort((a, b) => (a.id === state.selected) - (b.id === state.selected));
		// Services of the selected train, and services nobody runs in depots that run by their duties
		const assigned = dutyAssignments();
		const slotDepot = state.slot && depotById(state.slot.depot);
		const slotDay = slotDepot && (dutiesOf(slotDepot).sidings[state.slot.siding] || [])[state.slot.index] ? dutyOn(dutiesOf(slotDepot).sidings[state.slot.siding][state.slot.index], state.weekday) : null;
		const inSlot = new Set(slotDay ? slotDay.segments.map(segmentKey) : []);
		const diagramRoutes = new Set(data.depots.filter(depot => dutiesOf(depot).mode === "diagram").flatMap(depot => depot.routes));
		const slotPoints = [];
		services.forEach(s => {
			const times = timesOf(s);
			if (xOf(times[times.length - 1][1]) < LABEL_W - 40 || xOf(times[0][0]) > w + 40) return;
			const points = [];
			s.route.rows.forEach((row, i) => {
				points.push([xOf(times[i][0]), yOf(row)]);
				if (times[i][1] !== times[i][0]) points.push([xOf(times[i][1]), yOf(row)]);
			});
			drawn.push({ s, points });
			const isSelected = s.id === state.selected || s.route.id === state.selectedRoute;
			const dim = selectedRoute && s.route.id !== selectedRoute;
			ctx.globalAlpha = dim ? 0.25 : 1;
			if (isSelected) {
				ctx.strokeStyle = c.sel;
				ctx.lineWidth = 5;
				ctx.globalAlpha = 0.35;
				polyline(points);
				ctx.globalAlpha = 1;
			}
			const key = s.route.id + "@" + s.service;
			if (inSlot.has(key)) {
				ctx.strokeStyle = c.warn;
				ctx.lineWidth = 7;
				ctx.globalAlpha = 0.3;
				polyline(points);
				ctx.globalAlpha = dim ? 0.25 : 1;
				slotPoints.push({ start: points[0], end: points[points.length - 1], time: timesOf(s)[0][0] });
			}
			ctx.strokeStyle = colorOf(s.route.color);
			ctx.lineWidth = isSelected ? 2.5 : 1.5;
			// Dashed: no times yet, or a depot running by its duties has no train for it
			ctx.setLineDash(!s.route.hasTimes ? [4, 3] : diagramRoutes.has(s.route.id) && !assigned.has(key) ? [2, 4] : []);
			polyline(points);
			ctx.setLineDash([]);
			// Where the train carries on onto another route, or goes back to the depot
			const [ex, ey] = points[points.length - 1];
			if (s.link || s.returns && !loopNext.has(s.id)) {
				const next = s.link && data.routeById.get(s.link.route);
				ctx.fillStyle = s.link ? c.ink : c.muted;
				ctx.font = `11px ${css("--font-ui")}`;
				ctx.textAlign = "left";
				ctx.fillText(s.link ? "→ " + (next ? shortName(next.name) : "?") : "回库", ex + 4, ey + (s.route.rows[0] < s.route.rows[s.route.rows.length - 1] ? 12 : -4));
			}
			ctx.globalAlpha = 1;
			// On a loop, the wait at the first stop for the next lap, along the bottom row where the lap ends
			const nextLap = loopNext.get(s.id);
			if (nextLap) {
				const endX = points[points.length - 1][0], endY = points[points.length - 1][1], startX = xOf(timesOf(nextLap)[0][1]);
				ctx.globalAlpha = dim ? 0.25 : 0.8;
				ctx.strokeStyle = colorOf(s.route.color);
				ctx.lineWidth = isSelected ? 2 : 1;
				ctx.setLineDash([3, 3]);
				ctx.beginPath();
				ctx.moveTo(endX, endY);
				ctx.lineTo(startX, endY);
				ctx.stroke();
				ctx.setLineDash([]);
				if (state.pxPerMin >= 4) {
					ctx.fillStyle = c.muted;
					ctx.font = `10px ${css("--font-num")}`;
					ctx.textAlign = "left";
					ctx.fillText("↻" + clock(nextLap.start), startX + 2, endY - 3);
				}
				ctx.globalAlpha = 1;
			}
			// A square at the end of each stop of a selected service, dragged to change how long it stops there
			if (isSelected) {
				timesOf(s).forEach((t, i) => {
					if (i === 0 || i === times.length - 1 || s.plans[i].passes) return;
					const x = xOf(t[1]), y = yOf(s.route.rows[i]);
					ctx.fillStyle = c.panel;
					ctx.strokeStyle = c.sel;
					ctx.lineWidth = 2;
					ctx.beginPath();
					ctx.rect(x - 3.5, y - 3.5, 7, 7);
					ctx.fill();
					ctx.stroke();
					handles.push({ x, y, s, i });
				});
			}
		});
		// The selected train's moves between its services
		slotPoints.sort((a, b) => a.time - b.time);
		ctx.strokeStyle = c.warn;
		ctx.lineWidth = 1;
		ctx.setLineDash([2, 3]);
		for (let i = 1; i < slotPoints.length; i++) {
			ctx.beginPath();
			ctx.moveTo(...slotPoints[i - 1].end);
			ctx.lineTo(...slotPoints[i].start);
			ctx.stroke();
		}
		ctx.setLineDash([]);
		if (slotPoints.length) {
			ctx.fillStyle = c.warn;
			ctx.font = `bold 11px ${css("--font-ui")}`;
			ctx.textAlign = "right";
			ctx.fillText(`车 ${state.slot.index + 1}`, slotPoints[0].start[0] - 4, slotPoints[0].start[1] - 4);
		}
		ctx.restore();

		// Station names over everything at the left
		ctx.fillStyle = c.panel;
		ctx.fillRect(0, 0, LABEL_W, h);
		ctx.fillRect(0, 0, w, AXIS_H);
		ctx.strokeStyle = c.strong;
		ctx.beginPath();
		ctx.moveTo(LABEL_W + 0.5, 0);
		ctx.lineTo(LABEL_W + 0.5, h);
		ctx.moveTo(0, AXIS_H + 0.5);
		ctx.lineTo(w, AXIS_H + 0.5);
		ctx.stroke();
		ctx.font = `12px ${css("--font-ui")}`;
		ctx.textAlign = "right";
		ctx.textBaseline = "middle";
		line.rows.forEach((row, i) => {
			const y = yOf(i);
			if (y < AXIS_H + 4 || y > h) return;
			ctx.fillStyle = c.ink;
			ctx.fillText(fitText(shortName(row.name), LABEL_W - 16), LABEL_W - 8, y);
		});
		ctx.textBaseline = "alphabetic";
		// Redo the time labels the station column covered
		ctx.font = `11px ${css("--font-num")}`;
		ctx.textAlign = "center";
		for (let m = first; xOf(m * 60000) < w; m += step) {
			const x = xOf(m * 60000);
			const hour = mod(m * 60000 + data.dayStartMillis + data.offsetMillis, DAY) % 3600000 === 0;
			if (x < LABEL_W || !(hour || state.pxPerMin >= 4)) continue;
			ctx.fillStyle = hour ? c.ink : c.muted;
			ctx.fillText(clock(m * 60000), x, AXIS_H - 10);
		}
	}

	function polyline(points) {
		ctx.beginPath();
		points.forEach(([x, y], i) => i ? ctx.lineTo(x, y) : ctx.moveTo(x, y));
		ctx.stroke();
	}

	function fitText(text, width) {
		if (ctx.measureText(text).width <= width) return text;
		let t = text;
		while (t.length > 1 && ctx.measureText(t + "…").width > width) t = t.slice(0, -1);
		return t + "…";
	}

	/** The service whose line passes closest to a point, within a few pixels. */
	function hitService(x, y) {
		let best = null, bestDistance = 6;
		drawn.forEach(d => {
			for (let i = 1; i < d.points.length; i++) {
				const distance = segmentDistance(x, y, d.points[i - 1], d.points[i]);
				if (distance < bestDistance) {
					bestDistance = distance;
					best = d.s;
				}
			}
		});
		return best;
	}

	function segmentDistance(x, y, [x1, y1], [x2, y2]) {
		const dx = x2 - x1, dy = y2 - y1, length = dx * dx + dy * dy;
		const t = length ? Math.max(0, Math.min(1, ((x - x1) * dx + (y - y1) * dy) / length)) : 0;
		return Math.hypot(x - x1 - t * dx, y - y1 - t * dy);
	}

	function stopsHtml(s) {
		const times = timesOf(s);
		return `<div class="stops">${s.route.stops.map((stop, i) => {
			const pass = s.plans[i].passes;
			const arrival = i === 0 ? "" : clock(times[i][0], true), departure = i === times.length - 1 ? "" : pass ? "↓" : clock(times[i][1], true);
			return `<span class="name${pass ? " pass" : ""}">${escapeHtml(shortName(stop.name))}</span><span class="${pass ? "pass" : ""}">${arrival}</span><span class="${pass ? "pass" : ""}">${departure}</span>`;
		}).join("")}</div>`;
	}

	function renderInspector() {
		const inspector = $("inspector");
		const s = state.selected && data ? findService(state.selected) : null;
		const parts = [];
		if (state.slot && data) parts.push(dutyPanel());
		if (s) {
			const next = s.link && data.routeById.get(s.link.route);
			parts.push(`<h2>选中的班次</h2><div class="card"><div class="title"><span class="swatch" style="display:inline-block;width:12px;height:4px;background:${colorOf(s.route.color)}"></span>${escapeHtml(routeTitle(s.route))}</div>
				<dl class="kv"><dt>班次</dt><dd>${clock(s.start)}</dd><dt>表</dt><dd>${escapeHtml(s.table.name || "表 " + (s.tableIndex + 1))}</dd>
				${s.link ? `<dt>接续</dt><dd>${escapeHtml(next ? routeTitle(next) : "?")} ${clock(mod(s.link.toService - data.dayStartMillis, DAY))}</dd>` : ""}
				${s.returns ? `<dt>之后</dt><dd>回库</dd>` : ""}</dl>${stopsHtml(s)}</div>`);
		}
		const loopRoute = s && isLoop(s.route) ? s.route : state.route && data && data.routeById.get(state.route) && isLoop(data.routeById.get(state.route)) ? data.routeById.get(state.route) : null;
		if (loopRoute) parts.push(loopPanel(loopRoute));
		const line = currentLine();
		if (line) {
			parts.push(`<h2>线路</h2><div class="card"><div class="title">${escapeHtml(shortName(line.name))}</div><dl class="kv">${line.routes.map(r => `<dt>${escapeHtml(routeTitle(r))}</dt><dd>${r.services.length} 班</dd>`).join("")}</dl></div>`);
			// Tracks that don't run along the rails, which make the diagram jump about, are listed until they are put right
			const troubled = line.routes.map(r => [r, routeProblems(r)]).filter(([, problems]) => problems.length);
			if (troubled.length) parts.push(`<h2>股道问题</h2><div class="card">${troubled.map(([r, problems]) => `<div class="title">${escapeHtml(routeTitle(r))}</div><ul class="warn">${problems.map(p => `<li class="${p.level}">${escapeHtml(p.text)}</li>`).join("")}</ul>`).join("")}<p class="hint">右键线路“编辑停站…”在连通图上改股道。</p></div>`);
		}
		parts.push(`<p class="hint">滚轮缩放时间轴，拖动平移，Shift + 滚轮上下移动。<br>单击一条线选中这一班，拖它停站末端的方块只改这一班（例外，蓝色）。双击选中同一种别同一方向的全部班次，拖方块改整张表的停站时间。<br>时刻表里点格子直接输入，右键可以设通过、待避、推到之后的班次。改动先存为草稿，点顶栏的“应用”才发给游戏。</p>`);
		inspector.innerHTML = parts.join("");
	}

	const tooltip = $("tooltip");
	function showTooltip(s, clientX, clientY) {
		if (!s) {
			tooltip.hidden = true;
			return;
		}
		tooltip.innerHTML = `<b>${escapeHtml(routeTitle(s.route))}</b> ${clock(s.start)}${stopsHtml(s)}`;
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

	// ---------- Summary timetable ----------
	let tableRoute = null;
	function renderTable() {
		const view = $("view-table");
		if (!showsTable()) return;
		const line = currentLine();
		const r = state.route ? data.routeById.get(state.route) : line && line.routes[0];
		if (!r) {
			view.innerHTML = `<div class="table-empty">在左边选一条线路的汇总时刻表</div>`;
			return;
		}
		const services = r.services;
		const timetable = timetableOf(r), today = timetable.tables.findIndex(table => table.weekdays >> state.weekday & 1);
		const managed = isManaged(r), pending = draftOf(r) && "managed" in draftOf(r), drafted = Boolean(draftOf(r) && draftOf(r).timetable);
		const tools = `<div class="tt-tools"><b>${escapeHtml(routeTitle(r))}</b><span class="hint">${WEEKDAY_NAMES[state.weekday]} · ${services.length} 班${r.hasTimes ? "" : " · 正在向服务端取各站时刻"}${drafted ? " · 有没应用的草稿" : ""}</span>
			<span class="pill${managed ? " managed" : ""}" title="由高级编辑器管理的线路，游戏里的时刻表和停站只能查看">${managed ? "由高级编辑器管理" : "游戏内可编辑"}${pending ? "（草稿）" : ""}</span>
			<button data-managed="${!managed}">${managed ? "交回游戏内编辑" : "改由高级编辑器管理"}</button></div>
			<div class="tt-tools"><span class="hint" style="flex:none">表</span>${timetable.tables.map((table, i) => `<button class="table-chip${i === today ? " on" : ""}" data-table="${i}" title="切到这张表运行的第一天；右键改名、改星期或删除">${escapeHtml(tableName(table, i))} · ${daysText(table.weekdays)}</button>`).join("")}<button class="table-chip" data-new-table>＋ 新建表</button>
			${today >= 0 ? `<label class="hint">余裕 <input class="margin" data-margin value="${timetable.tables[today].marginPercent}" inputmode="numeric"> %</label>` : ""}
			<label class="hint"><input type="checkbox" data-enabled ${timetable.enabled ? "checked" : ""}> 启用时刻表</label></div>
			${today >= 0 ? `<div class="tt-tools"><label for="add-service">添加班次</label><input id="add-service" placeholder="07:10 + 10 * 00:03" autocomplete="off"><button data-add-service>添加</button><span class="err">${escapeHtml(tableMessage)}</span></div>` : `<div class="tt-tools"><span class="err">${WEEKDAY_NAMES[state.weekday]}没有表，这一天不运行。${escapeHtml(tableMessage)}</span></div>`}`;
		if (!services.length) {
			view.innerHTML = tools + `<div class="table-empty">这条线路${WEEKDAY_NAMES[state.weekday]}没有班次</div>`;
			return;
		}
		const multipleTables = timetable.tables.length > 1;
		const isSelected = s => s.id === state.selected || s.route.id === state.selectedRoute;
		const head = `<thead><tr><th class="st">车站</th><th class="kind"></th>${services.map(s => `<th class="svc-head${isSelected(s) ? " hl" : ""}" data-svc="${s.id}" data-stop="0" data-kind="dep" title="点这里改班次时刻；右键更多"><span class="time">${clock(s.start)}${s.exceptions ? `<span class="exc-mark" title="有只改这一班的例外"> *</span>` : ""}</span>${multipleTables ? `<span class="note">${escapeHtml(tableName(s.table, s.tableIndex))}</span>` : ""}${s.link ? `<span class="note">直通</span>` : s.returns ? `<span class="note">回库</span>` : ""}</th>`).join("")}</tr></thead>`;
		const allTimes = services.map(timesOf);
		const rows = [];
		r.stops.forEach((stop, i) => {
			const last = i === r.stops.length - 1;
			const allPass = i > 0 && !last && services.every(s => s.plans[i].passes);
			const kinds = i === 0 ? ["dep"] : last ? ["arr"] : allPass ? ["pass"] : ["arr", "dep"];
			kinds.forEach((kind, k) => {
				const cells = services.map((s, j) => {
					const t = allTimes[j][i], plan = s.plans[i], exception = s.exceptions && s.exceptions.get(i);
					const passes = kind !== "pass" && plan.passes;
					const changed = exception && (kind === "dep" ? exception.dwell >= 0 : exception.run >= 0) || exception && exception.pass >= 0;
					const leaves = s.link && kind !== "dep" && (s.link.fromStop < 0 ? i === r.stops.length - 1 : s.link.fromStop === i);
					const picked = state.pendingExit && state.pendingExit.id === s.id && state.pendingExit.index === i && kind !== "dep";
					const classes = [isSelected(s) ? "hl" : "", kind === "pass" || passes ? "pass" : "", changed ? "exc" : "", kind !== "dep" && plan.tooFast ? "fast" : "", kind === "dep" && plan.overtake ? "wait" : "", exception && exception.pass >= 0 ? "pending-run" : "", leaves ? "out" : "", picked ? "pending" : ""].filter(Boolean).join(" ");
					const title = exception && exception.pass >= 0 ? "只改了这一班的时刻：列车实际仍按线路的停站运行，按班次生成路径后（E2）才真正通过或停车" : plan.tooFast && kind !== "dep" ? "快过最快时刻，列车跑不到" : "";
					const text = passes ? (kind === "arr" ? "↓ " + clock(t[0], true) : "レ") : clock(kind === "dep" ? t[1] : t[0], true);
					return `<td class="${classes}" data-svc="${s.id}" data-stop="${i}" data-kind="${passes ? "pass" : kind}" title="${title}" tabindex="0">${text}</td>`;
				}).join("");
				const label = { dep: "发", arr: "到", pass: "通过" }[kind];
				rows.push(`<tr class="${k === 0 ? "first-of-station" : ""}">${k === 0 ? `<th class="st" rowspan="${kinds.length}" title="${escapeHtml(stop.name)}${escapeHtml(trackTitle(stop))}${stop.boundary ? "（时刻边界，可以在这里接出或接入）" : ""}">${escapeHtml(shortName(stop.name))}${stop.boundary ? `<span class="boundary"> 界</span>` : ""}${isLoop(r) && i > 0 && i < r.stops.length - 1 && loopEntryStop(r) === i ? `<span class="entry" title="车厂的列车从这里接入环线"> 入</span>` : ""}</th>` : ""}<th class="kind">${label}</th>${cells}</tr>`);
			});
		});
		view.innerHTML = tools + `<table class="tt">${head}<tbody>${rows.join("")}</tbody></table>`;
		// Another route's table starts from its first service
		if (tableRoute !== r.id) {
			view.scrollLeft = 0;
			tableRoute = r.id;
		}
		const selectedHead = view.querySelector("th.svc-head.hl");
		if (selectedHead && !state.selectedRoute) selectedHead.scrollIntoView({ block: "nearest", inline: "center" });
	}

	const tableRouteOf = () => {
		const line = currentLine();
		return state.route ? data.routeById.get(state.route) : line && line.routes[0];
	};

	/** Types into a cell of the summary timetable, with Enter to keep it, Escape to drop it and Tab to go on to the next cell. */
	function editCell(cell) {
		const s = findService(cell.dataset.svc), stop = +cell.dataset.stop, kind = cell.dataset.kind;
		if (!s || kind === "pass" && cell.textContent === "レ" || cell.querySelector("input")) return;
		const t = timesOf(s)[stop], old = kind === "dep" ? t[1] : t[0];
		const target = cell.tagName === "TH" ? cell.querySelector(".time") : cell;
		target.innerHTML = `<input value="${clock(old, true)}" aria-label="时刻">`;
		const input = target.querySelector("input");
		input.focus();
		input.select();
		let done = false;
		const commit = move => {
			if (done) return;
			const time = typedTime(input.value, old);
			if (time === null) {
				input.style.borderColor = "var(--warn)";
				return;
			}
			done = true;
			const cells = [...$("view-table").querySelectorAll("td")], index = cells.indexOf(cell);
			if (Math.abs(time - old) >= 1000) changeTime(s, stop, kind, time);
			else renderTable();
			if (move) {
				const next = $("view-table").querySelectorAll("td")[index + move];
				if (next) editCell(next);
			}
		};
		input.addEventListener("keydown", e => {
			if (e.key === "Enter") { e.preventDefault(); commit(0); }
			else if (e.key === "Tab") { e.preventDefault(); commit(e.shiftKey ? -1 : 1); }
			else if (e.key === "Escape") { done = true; renderTable(); }
		});
		input.addEventListener("blur", () => commit(0));
	}

	/**
	 * A time typed for one service. The first stop's departure moves the whole service; an arrival or passing time is its
	 * own running time from the stop before, and a departure its own stop time, kept as exceptions of this service.
	 */
	function changeTime(s, stop, kind, time) {
		const times = timesOf(s);
		editTimetable(s.route, timetable => {
			const table = timetable.tables[s.tableIndex];
			if (stop === 0) {
				return retimeService(table, s.service, mod(s.service + time - times[0][1], DAY));
			} else if (kind === "dep") {
				setStopException(table, s.service, stop, { dwell: Math.max(0, Math.round((time - times[stop][0]) / 1000)) });
			} else {
				setStopException(table, s.service, stop, { run: Math.max(1, Math.round((time - times[stop - 1][1]) / 1000)) });
			}
		});
		const moved = stop === 0 ? data.routeById.get(s.route.id).services.find(o => o.tableIndex === s.tableIndex && o.service === mod(s.service + time - times[0][1], DAY)) : null;
		if (moved) state.selected = moved.id;
	}

	/** Services of the same table that run after this one, in the operating day. */
	const laterServices = s => s.route.services.filter(o => o.tableIndex === s.tableIndex && o.start > s.start);

	function cellMenu(e, cell) {
		const s = findService(cell.dataset.svc), stop = +cell.dataset.stop;
		if (!s) return;
		const n = s.route.stops.length, middle = stop > 0 && stop < n - 1, plan = s.plans[stop];
		const exception = s.exceptions && s.exceptions.get(stop);
		const later = laterServices(s);
		const items = [];
		const edit = change => editTimetable(s.route, timetable => change(timetable.tables[s.tableIndex], timetable));
		if (cell.tagName === "TH") {
			items.push(["删除这一班", () => edit(table => removeService(table, s.service))]);
			if (!s.link) items.push([s.returns ? "跑完这一班不回库" : "跑完这一班回库", () => edit(table => { table.returns = s.returns ? table.returns.filter(x => x !== s.service) : [...table.returns, s.service]; })]);
			if (later.length) items.push([`这一班和之后的 ${later.length} 班一起平移…`, () => {
				const minutes = Number(prompt("平移多少分钟？负数是提前", "5"));
				if (!minutes) return;
				edit(table => {
					const shift = Math.round(minutes * 60000);
					const moving = [s, ...later].map(o => o.service).sort((a, b) => shift > 0 ? b - a : a - b);
					for (const service of moving) {
						const error = retimeService(table, service, mod(service + shift, DAY));
						if (error) return error;
					}
				});
			}]);
			if (s.exceptions) items.push(["清除这一班的全部例外", () => edit(table => { table.exceptions = table.exceptions.filter(x => x.service !== s.service); })]);
			if (s.link) items.push(["清除这一班的直通", () => edit(table => { table.links = table.links.filter(link => link.service !== s.service); })]);
			items.push(["批量新建直通关系…", () => throughDialog(s.route)]);
		} else {
			if (middle) {
				const routePasses = timetableOf(s.route).pass[stop];
				items.push([plan.passes ? "改为在这一站停车（只改这一班）" : "设为通过这一站（只改这一班）", () => edit(table => setStopException(table, s.service, stop, { pass: plan.passes === routePasses ? (plan.passes ? 0 : 1) : -1, dwell: -1 }))]);
				if (!plan.passes) items.push([plan.overtake ? "取消待避（只改这一班）" : "设为待避（只改这一班，在这一站等后车通过）", () => edit(table => {
					const tableOvertake = table.stops[stop].overtake;
					setStopException(table, s.service, stop, { overtake: !plan.overtake === tableOvertake ? -1 : plan.overtake ? 0 : 1 });
				})]);
			}
			if (exception && later.length) items.push([`把这一格的例外推到之后的 ${later.length} 班`, () => edit(table => later.forEach(o => setStopException(table, o.service, stop, { run: exception.run, dwell: exception.dwell, pass: exception.pass, overtake: exception.overtake })))]);
			if (exception && (exception.dwell >= 0 || exception.run >= 0)) items.push(["把这一班的这个时刻改成整张表的", () => edit(table => {
				// Set times count from the first stop, which is where the table's plan starts
				if (exception.run >= 0) table.stops[stop].arrival = Math.round((s.plans[stop - 1].departure + exception.run * 1000) / 1000);
				if (exception.dwell >= 0) table.stops[stop].departure = Math.round((s.plans[stop].arrival + exception.dwell * 1000) / 1000);
				setStopException(table, s.service, stop, { run: -1, dwell: -1 });
			})]);
			if (exception) items.push(["恢复按表的时刻", () => edit(table => setStopException(table, s.service, stop, { run: -1, dwell: -1, pass: -1, overtake: -1 }))]);
			if (isLoop(s.route) && middle && !plan.passes && loopEntryStop(s.route) !== stop) items.push([`设为出库列车接入这条环线的站`, () => editTimetable(s.route, timetable => { timetable.entryPlatform = s.route.stops[stop].platformId; })]);
			if (isLoop(s.route) && loopEntryStop(s.route) === stop && stop > 0) items.push(["出库列车改回从首站接入", () => editTimetable(s.route, timetable => { timetable.entryPlatform = "0"; })]);
			// Through running: leave at the last stop or a boundary, join at the first stop or a boundary
			if (stop === n - 1 || isBoundary(s.route, stop) && !plan.passes) items.push([`设为接出点（从${stopName(s.route, stop)}直通到别的线路）`, () => {
				state.pendingExit = { id: s.id, stop: stop === n - 1 ? -1 : stop, index: stop };
				renderAll();
			}]);
			const pending = state.pendingExit && findService(state.pendingExit.id);
			if (pending && pending.route !== s.route && (stop === 0 || isBoundary(s.route, stop))) items.push([`设为接入点（${clock(pending.start)} 班从${stopName(pending.route, state.pendingExit.index)}接到这里）`, () => {
				setLink(pending, state.pendingExit.stop, s, stop);
				state.pendingExit = null;
				renderAll();
			}]);
		}
		if (items.length) openMenu(e.clientX, e.clientY, items);
	}

	// ---------- Loops ----------
	const isLoop = r => r.stops.length > 2 && r.stops[0].platformId === r.stops[r.stops.length - 1].platformId;

	/** The platform a stop uses and the other tracks of its station on the same rails. */
	function trackTitle(stop) {
		const trackName = c => `${c.name || "?"}${c.passingTrack ? "（待避线）" : ""}`;
		const others = (stop.candidates || []).filter(c => c.id !== stop.platformId);
		return ` 站台 ${trackName({ name: stop.platform, passingTrack: stop.passingTrack })}${others.length ? `；还可停 ${others.map(trackName).join("、")}` : ""}`;
	}

	/** The stop where trains from the depot join a loop, which is its first stop unless another is set. */
	function loopEntryStop(r) {
		const platformId = timetableOf(r).entryPlatform;
		const stop = r.stops.findIndex((s, i) => i > 0 && i < r.stops.length - 1 && s.platformId === platformId);
		return stop > 0 && !timetableOf(r).pass[stop] ? stop : 0;
	}

	/**
	 * How the trains of a loop go round on the chosen day, the way the game runs them: when a lap ends at the first stop,
	 * the train takes the first free service from then, waiting at most the layover (longer when its depot keeps trains on
	 * the line), and otherwise goes back to the depot. A service no lap ends in time for takes a train from the depot.
	 * Services running through to another route or going back to the depot end the train's laps.
	 *
	 * @return the service each lap carries on into, and the trains as lists of services
	 */
	function loopRuns(r) {
		const n = r.stops.length, services = r.services;
		const keepsOnLine = data.depots.some(depot => depot.cycleRoutes && depot.routes.includes(r.id));
		const maxWait = keepsOnLine ? MAX_STAY_MILLIS : MAX_LAYOVER_MILLIS;
		const next = new Map(), taken = new Set();
		services.filter(s => !s.link && !s.returns).map(s => ({ s, end: timesOf(s)[n - 1][0] })).sort((a, b) => a.end - b.end).forEach(({ s, end }) => {
			const following = services.find(o => o !== s && !taken.has(o.id) && o.start >= end && o.start - end <= maxWait);
			if (following) {
				taken.add(following.id);
				next.set(s.id, following);
			}
		});
		const trains = services.filter(s => !taken.has(s.id)).map(first => {
			const laps = [first];
			while (next.has(laps[laps.length - 1].id)) laps.push(next.get(laps[laps.length - 1].id));
			return laps;
		});
		return { next, trains, maxWait };
	}

	function loopPanel(r) {
		const runs = loopRuns(r), entry = loopEntryStop(r);
		const ending = s => s.link ? `→ ${escapeHtml(routeTitle(data.routeById.get(s.link.route) || r))}` : runs.maxWait > MAX_LAYOVER_MILLIS ? "留在线上" : "回库";
		const lines = runs.trains.slice(0, 40).map((laps, i) => `<div><span class="train">车 ${i + 1}：</span>${laps.map(s => clock(s.start)).join(" → ")} <span class="end">${ending(laps[laps.length - 1])}</span></div>`).join("");
		return `<h2>环线运行（${WEEKDAY_NAMES[state.weekday]}）</h2><div class="card"><div class="title">${escapeHtml(routeTitle(r))}</div>
			<dl class="kv"><dt>列车</dt><dd>${runs.trains.length} 列跑 ${r.services.length} 班</dd><dt>出库接入</dt><dd>${escapeHtml(stopName(r, entry))}${entry ? "" : "（首站）"}</dd>
			<dt>等下一圈</dt><dd>最多 ${runs.maxWait > MAX_LAYOVER_MILLIS ? "8 天（车厂不回库）" : "30 分钟"}</dd></dl>
			<div class="laps">${lines}${runs.trains.length > 40 ? "<div>…</div>" : ""}</div>
			<p class="hint">每跑完一圈，列车接首站能赶上的第一个空班次；赶不上的班次由车厂出车。运行图上最下一行的虚线是在首站等下一圈。</p></div>`;
	}

	/** @return true if a service can leave this route here, or join it, apart from the last and first stops */
	const isBoundary = (r, stop) => Boolean(r.stops[stop] && r.stops[stop].boundary);
	const stopName = (r, stop) => shortName(r.stops[stop].name);

	/** Sets where a service runs through to, replacing any link it had. @param fromStop the stop it leaves, or -1 for the last */
	function setLink(s, fromStop, target, toStop) {
		editTimetable(s.route, timetable => {
			const table = timetable.tables[s.tableIndex];
			table.links = table.links.filter(link => link.service !== s.service);
			table.links.push({ service: s.service, route: target.route.id, toService: target.service, fromStop, toStop });
			// A service running through doesn't go back to the depot
			table.returns = table.returns.filter(service => service !== s.service);
		});
	}

	/**
	 * Through links in bulk: each service of one route in a time range carries on as the first service of another route it
	 * can catch, leaving at its last stop or a boundary and joining at the first stop or a boundary.
	 */
	// ---------- Duties (运用) ----------
	const sortedObject = object => Object.fromEntries(Object.keys(object).sort().map(k => [k, object[k]]));
	/** A depot's duties in a fixed order, so a draft changed back to the game's matches it. */
	const canonDuties = duties => JSON.stringify({ mode: duties.mode, sidings: sortedObject(Object.fromEntries(Object.entries(duties.sidings).filter(([, slots]) => slots.length))) });
	const dutiesOf = depot => draft.duties[depot.id] ? draft.duties[depot.id].duties : depot.duties || { mode: "online", sidings: {} };
	const dutyOn = (slot, weekday) => slot.find(day => day.days >> weekday & 1) || null;
	const depotById = id => data.depots.find(depot => depot.id === id);
	const segmentKey = segment => segment.route + "@" + segment.service;

	/** Changes a depot's duties in the draft, dropping the draft when it ends up the same as the game's. */
	function editDuties(depot, change) {
		if (!draft.duties[depot.id]) draft.duties[depot.id] = { duties: JSON.parse(JSON.stringify(depot.duties || { mode: "online", sidings: {} })) };
		const entry = draft.duties[depot.id];
		const message = change(entry.duties);
		Object.values(entry.duties.sidings).forEach(slots => slots.forEach(slot => slot.forEach(day => day.segments.sort((a, b) => segmentStart(a) - segmentStart(b)))));
		delete entry.error;
		if (canonDuties(entry.duties) === canonDuties(depot.duties || { mode: "online", sidings: {} })) delete draft.duties[depot.id];
		dutyNotice = typeof message === "string" ? message : "";
		saveDraft();
		renderAll();
	}

	/**
	 * The weekdays that run the same tables of a depot's routes as the given one (运行日类型). Editing a train's duty on a
	 * day changes it for all of them.
	 */
	function dayGroup(depot, weekday) {
		const signature = day => depot.routes.map(id => {
			const r = data.routeById.get(id);
			return r ? timetableOf(r).tables.findIndex(table => table.weekdays >> day & 1) : -2;
		}).join(",");
		const own = signature(weekday);
		return WEEKDAY_SHORT.reduce((mask, _, day) => signature(day) === own ? mask | 1 << day : mask, 0);
	}

	/** A slot's duty for the day group of a weekday, split off from the days it shared with others first. */
	function dutyForEditing(depot, slot, weekday) {
		const group = dayGroup(depot, weekday), current = dutyOn(slot, weekday);
		if (current && current.days === group) return current;
		const made = { days: group, stay: current ? current.stay : false, next: current && current.next !== undefined ? current.next : -1, segments: current ? current.segments.map(segment => ({ ...segment })) : [] };
		slot.forEach(day => { day.days &= ~group; });
		for (let i = slot.length - 1; i >= 0; i--) if (!slot[i].days) slot.splice(i, 1);
		slot.push(made);
		return made;
	}

	/** The service a segment runs on the chosen day, or null when it doesn't run that day. */
	const segmentService = segment => {
		const r = data.routeById.get(segment.route);
		return r && r.services ? r.services.find(s => s.service === segment.service) || null : null;
	};
	const lastStopOf = (segment, r) => segment.to < 0 ? r.stops.length - 1 : segment.to;
	function segmentStart(segment) {
		const s = segmentService(segment);
		return s ? timesOf(s)[Math.min(segment.from, s.plans.length - 1)][0] : Infinity;
	}
	function segmentEnd(segment) {
		const s = segmentService(segment);
		return s ? timesOf(s)[lastStopOf(segment, s.route)][1] : -Infinity;
	}

	/** Where each service is run on the chosen day: route@service → {depot, siding, index}. */
	function dutyAssignments() {
		const assigned = new Map();
		if (!data) return assigned;
		data.depots.forEach(depot => Object.entries(dutiesOf(depot).sidings).forEach(([siding, slots]) => slots.forEach((slot, index) => {
			const day = dutyOn(slot, state.weekday);
			if (day) day.segments.forEach(segment => {
				const key = segmentKey(segment);
				if (!assigned.has(key)) assigned.set(key, []);
				assigned.get(key).push({ depot: depot.id, siding, index });
			});
		})));
		return assigned;
	}

	/** What is wrong with a train's duty on a day: services it can't reach in time or from where it is, or that don't run. */
	function dutyProblems(depot, sidingId, index, weekday) {
		const slots = dutiesOf(depot).sidings[sidingId] || [], day = slots[index] ? dutyOn(slots[index], weekday) : null;
		if (!day) return [];
		const problems = [], assigned = dutyAssignments();
		day.segments.forEach((segment, i) => {
			const s = segmentService(segment);
			if (!s) {
				problems.push({ i, text: "这一班当天不运行，可能时刻表改过" });
				return;
			}
			if (!depot.routes.includes(segment.route)) problems.push({ i, text: "线路不在这个车厂" });
			if ((assigned.get(segmentKey(segment)) || []).length > 1) problems.push({ i, text: "这一班也分给了别的车" });
			const before = day.segments[i - 1], beforeService = before && segmentService(before);
			if (beforeService) {
				const endStop = beforeService.route.stops[lastStopOf(before, beforeService.route)], startStop = s.route.stops[segment.from];
				if (segmentEnd(before) > segmentStart(segment)) problems.push({ i, text: "衔接时间不够，上一班还没到" });
				else if (endStop && startStop && endStop.station !== startStop.station) problems.push({ i, text: `站台对不上：上一班在${shortName(endStop.name)}结束` });
			}
		});
		if (day.stay) {
			const target = nextSlotOf(day, index);
			const next = slots[target] ? dutyOn(slots[target], (weekday + 1) % 7) : null;
			if (!next || !next.segments.length) problems.push({ i: -1, text: `留在线上过夜，但第二天接的车 ${target + 1} 没有运用` });
			else if (dutyEndStation(day) !== dutyStartStation(next)) problems.push({ i: -1, text: `第二天接的车 ${target + 1} 的运用不从这里出发` });
			if (slots.some((slot, other) => other !== index && dutyOn(slot, weekday) && dutyOn(slot, weekday).stay && nextSlotOf(dutyOn(slot, weekday), other) === target)) problems.push({ i: -1, text: `还有一列过夜的车第二天也接车 ${target + 1}` });
		}
		return problems;
	}

	/** The slot a train staying overnight runs the next day: the one chosen, or its own. */
	const nextSlotOf = (day, index) => day.next !== undefined && day.next >= 0 ? day.next : index;
	function dutyEndStation(day) {
		const segment = day.segments[day.segments.length - 1], r = segment && data.routeById.get(segment.route);
		return r ? (r.stops[lastStopOf(segment, r)] || {}).station : null;
	}
	function dutyStartStation(day) {
		const segment = day.segments[0], r = segment && data.routeById.get(segment.route);
		return r ? (r.stops[segment.from] || {}).station : null;
	}

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

	/**
	 * Adds a service to the selected train's duty for the chosen day, or takes it out. A service another train of the
	 * depot runs moves to this one. Joining or leaving part of the way along follows the service's through links.
	 */
	function toggleInSlot(s) {
		const depot = depotById(state.slot.depot);
		if (!depot) return;
		if (!depot.routes.includes(s.route.id)) {
			dutyNotice = `${routeTitle(s.route)} 不在车厂“${shortName(depot.name)}”里，先在游戏里把线路加进车厂。`;
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
				return `${clock(s.start)} ${routeTitle(s.route)} 已从车 ${state.slot.index + 1} 移出，按运用图时这一班不运行。`;
			}
			let moved = "";
			Object.entries(duties.sidings).forEach(([siding, otherSlots]) => otherSlots.forEach((slot, index) => {
				const other = dutyOn(slot, state.weekday);
				if (!other || siding === state.slot.siding && index === state.slot.index) return;
				const found = other.segments.findIndex(o => segmentKey(o) === segmentKey(segment));
				if (found >= 0) {
					dutyForEditing(depot, slot, state.weekday).segments.splice(found, 1);
					moved = `，从车 ${index + 1} 移过来`;
				}
			}));
			day.segments.push(segment);
			return `${clock(s.start)} ${routeTitle(s.route)} 加进车 ${state.slot.index + 1}${moved}。`;
		});
	}

	function dutyPanel() {
		const depot = depotById(state.slot.depot);
		const siding = depot && depot.sidings.find(o => o.id === state.slot.siding);
		if (!siding) return "";
		const slots = dutiesOf(depot).sidings[siding.id] || [], slot = slots[state.slot.index] || [];
		const day = dutyOn(slot, state.weekday), group = dayGroup(depot, state.weekday);
		const problems = dutyProblems(depot, siding.id, state.slot.index, state.weekday);
		const problemAt = i => problems.filter(p => p.i === i).map(p => `<div class="err">${escapeHtml(p.text)}</div>`).join("");
		const rows = day ? day.segments.map((segment, i) => {
			const s = segmentService(segment), r = data.routeById.get(segment.route);
			const where = r ? `${shortName(r.stops[segment.from] ? r.stops[segment.from].name : "?")} → ${shortName(r.stops[lastStopOf(segment, r)] ? r.stops[lastStopOf(segment, r)].name : "?")}` : "已删除的线路";
			return `<li><span class="num">${s ? clock(segmentStart(segment)) + "–" + clock(segmentEnd(segment)) : "--:--"}</span> ${escapeHtml(r ? shortName(r.name) : "?")} <span class="muted">${escapeHtml(where)}</span><button class="link" data-remove-segment="${i}" title="移出这一班">×</button>${problemAt(i)}</li>`;
		}).join("") : "";
		return `<h2>运用：车 ${state.slot.index + 1}</h2><div class="card"><div class="title">${escapeHtml(shortName(depot.name))} · ${escapeHtml(shortName(siding.name) || "存车线")}</div>
			<dl class="kv"><dt>运行日</dt><dd>${daysText(group)}${day && day.days !== group ? `（现在和 ${daysText(day.days & ~group)} 共用，改了会分开）` : ""}</dd>
			<dt>方式</dt><dd>${dutiesOf(depot).mode === "diagram" ? "按运用图" : "在线预约（运用先存着，按运用运行要等以后）"}</dd></dl>
			${rows ? `<ol class="duty">${rows}</ol>` : `<p class="hint">这天没有运用，在运行图上点班次加进来。</p>`}
			${problemAt(-1)}
			${day && day.segments.length ? `<label><input type="checkbox" data-duty-stay ${day.stay ? "checked" : ""}> 跑完最后一班留在线上过夜</label>` : ""}
			${day && day.stay ? `<label>第二天接着跑 <select data-duty-next>${slots.map((slot, i) => {
				const next = dutyOn(slot, (state.weekday + 1) % 7);
				const fits = next && next.segments.length && dutyStartStation(next) === dutyEndStation(day);
				return `<option value="${i}"${nextSlotOf(day, state.slot.index) === i ? " selected" : ""}>车 ${i + 1}${i === state.slot.index ? "（自己）" : ""}${fits ? "" : next && next.segments.length ? "（不从这里出发）" : "（没有运用）"}</option>`;
			}).join("")}</select></label>` : ""}
			<p class="hint">选着这列车时，在运行图上点一班把它加进来，再点一次移出；别的车跑的班次会移过来。改的是 ${daysText(group)} 这几天（表的组合相同）。按 Esc 取消选择。按运用图的车厂按这里的运用出车，应用后要刷新车厂路径。</p></div>`;
	}

	/** A first draft of a depot's duties from its timetables, put in the draft to look over before applying. */
	async function generateDuties(depot) {
		if (draft.duties[depot.id] && !confirm(`用自动生成的运用替换“${shortName(depot.name)}”草稿里的运用？`)) return;
		if (!draft.duties[depot.id] && Object.values((depot.duties || { sidings: {} }).sidings).some(slots => slots.length) && !confirm(`用自动生成的运用替换“${shortName(depot.name)}”现在的运用？应用前都还在草稿里。`)) return;
		dutyNotice = "正在生成运用…";
		renderBanner();
		try {
			const result = await sendJson("api/plan-duties", "POST", { depot: depot.id });
			if (result.noSidings) {
				dutyNotice = "这个车厂没有存车线，生成不了运用。";
			} else {
				editDuties(depot, duties => {
					duties.sidings = result.duties.sidings;
				});
				dutyNotice = `已生成：最多同时 ${result.trains} 列车在线上。` + (result.uncovered ? `有 ${result.uncovered} 班没有车可派，存车线放的车不够。` : "")
					+ (result.missingTimes.length ? `${result.missingTimes.join("、")} 还没有各站时刻，没有算进去，打开它的时刻表或刷新车厂路径后再生成。` : "")
					+ (result.issues.length ? `其他问题：${result.issues.slice(0, 3).join("；")}` : "") + "先检查一遍，再按“应用”。";
			}
		} catch (e) {
			dutyNotice = "生成失败：" + (e.message === "401" ? "页面的链接已经失效" : "游戏可能已经关闭");
		}
		renderAll();
	}

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

	/** A mark in the tree for a route whose tracks don't run along the rails. */
	function problemDot(r) {
		const problems = routeProblems(r);
		if (!problems.length) return "";
		const level = problems.some(p => p.level === "error") ? "error" : "warn";
		return `<span class="problem-dot ${level}" title="${escapeHtml(problems.map(p => p.text).join("；"))}">!</span>`;
	}

	function buildTopology() {
		const source = data.topology || { platforms: [], networkCount: 0 };
		const byStation = new Map();
		source.platforms.forEach((p, i) => {
			if (!p.station) return;
			const key = p.station + "|" + p.network;
			if (!byStation.has(key)) byStation.set(key, []);
			byStation.get(key).push(i);
		});
		topo = { platforms: source.platforms, index: new Map(source.platforms.map((p, i) => [p.id, i])), byStation, reach: new Map(), problems: new Map(), networkCount: source.networkCount || 0 };
	}

	const isUsable = s => Boolean(topo.platforms[s >> 1] && topo.platforms[s >> 1].ways[s & 1].usable);
	const platformLabel = i => topo.platforms[i].name || "?";
	const stationLabel = i => shortName(topo.platforms[i].stationName) || "（不属于车站）";
	/** The tracks of a platform's station on the same rails, by name so they keep their rows whichever is picked. */
	function stationTracks(i) {
		const p = topo.platforms[i];
		const tracks = p.station ? topo.byStation.get(p.station + "|" + p.network) || [i] : [i];
		return (tracks.includes(i) ? tracks : [i, ...tracks]).slice().sort((a, b) => platformLabel(a).localeCompare(platformLabel(b), undefined, { numeric: true }) || a - b);
	}

	/** Costs from a state to every state reachable from it without turning back, passing platforms on the way. */
	function reachFrom(start) {
		const cached = topo.reach.get(start);
		if (cached) return cached;
		const dist = new Map([[start, 0]]), prev = new Map(), done = new Set();
		const heap = [[0, start]];
		const push = item => {
			heap.push(item);
			for (let i = heap.length - 1; i > 0;) {
				const parent = (i - 1) >> 1;
				if (heap[parent][0] <= heap[i][0]) break;
				[heap[parent], heap[i]] = [heap[i], heap[parent]];
				i = parent;
			}
		};
		const pop = () => {
			const top = heap[0], last = heap.pop();
			if (heap.length) {
				heap[0] = last;
				for (let i = 0; ;) {
					const l = i * 2 + 1, r = l + 1;
					let m = i;
					if (l < heap.length && heap[l][0] < heap[m][0]) m = l;
					if (r < heap.length && heap[r][0] < heap[m][0]) m = r;
					if (m === i) break;
					[heap[m], heap[i]] = [heap[i], heap[m]];
					i = m;
				}
			}
			return top;
		};
		while (heap.length && done.size < 20000) {
			const [d, s] = pop();
			if (done.has(s)) continue;
			done.add(s);
			const edges = isUsable(s) ? topo.platforms[s >> 1].ways[s & 1].edges : [];
			for (let k = 0; k < edges.length; k += 2) {
				const t = edges[k], nd = d + edges[k + 1];
				if (t !== start && nd < (dist.has(t) ? dist.get(t) : Infinity)) {
					dist.set(t, nd);
					prev.set(t, s);
					push([nd, t]);
				}
			}
		}
		dist.delete(start);
		const result = { dist, prev };
		topo.reach.set(start, result);
		return result;
	}

	/** The cheapest way from any state of one platform to any state of another, or Infinity. */
	function legCost(from, to) {
		let best = Infinity;
		[from * 2, from * 2 + 1].filter(isUsable).forEach(s => [to * 2, to * 2 + 1].forEach(t => {
			const d = reachFrom(s).dist.get(t);
			if (d !== undefined && d < best) best = d;
		}));
		return best;
	}

	const compareTuples = (a, b) => a[0] - b[0] || a[1] - b[1] || a[2] - b[2];

	/**
	 * The best tracks for a run of stations, by layers: each stop's tracks, a state being a track and the way the train
	 * runs along it. Fixed stops must keep their track; then as few tracks as possible differ from {@code current}, then as
	 * few turn backs as possible, then the cheapest. Turning back is leaving a stop the other way from how it came.
	 *
	 * @return {ok, picks, reversals, legs, feasible: Set of tracks for each stop}
	 */
	function solveStops(layers, current, pins) {
		const n = layers.length;
		const allowed = layers.map((tracks, i) => pins && pins.has(i) ? tracks.filter(p => p === pins.get(i)) : tracks);
		const change = (i, p) => current && current[i] !== undefined && current[i] !== p ? 1 : 0;
		const departures = [new Map()], arrivals = [null];
		allowed[0].forEach(p => [p * 2, p * 2 + 1].filter(isUsable).forEach(s => departures[0].set(s, { t: [change(0, p), 0, 0], from: null })));
		for (let i = 1; i < n; i++) {
			const arrive = new Map(), targets = allowed[i].flatMap(p => [p * 2, p * 2 + 1]);
			departures[i - 1].forEach((v, s) => {
				const { dist } = reachFrom(s);
				targets.forEach(t => {
					const d = dist.get(t);
					if (d === undefined) return;
					const tuple = [v.t[0] + change(i, t >> 1), v.t[1], v.t[2] + d];
					const old = arrive.get(t);
					if (!old || compareTuples(tuple, old.t) < 0) arrive.set(t, { t: tuple, from: s });
				});
			});
			arrivals.push(arrive);
			const depart = new Map();
			if (i < n - 1) arrive.forEach((v, t) => [t, t ^ 1].filter(isUsable).forEach(s => {
				const tuple = s === t ? v.t : [v.t[0], v.t[1] + 1, v.t[2]];
				const old = depart.get(s);
				if (!old || compareTuples(tuple, old.t) < 0) depart.set(s, { t: tuple, from: t });
			}));
			departures.push(depart);
		}

		// Which tracks some way through all the stops can use, from both ends
		const feasible = layers.map(() => new Set());
		let good = n > 1 ? new Set(arrivals[n - 1].keys()) : new Set(departures[0].keys());
		good.forEach(t => feasible[n - 1].add(t >> 1));
		for (let i = n - 2; i >= 0; i--) {
			const goodDepartures = new Set([...departures[i].keys()].filter(s => [...good].some(t => reachFrom(s).dist.has(t))));
			good = i === 0 ? goodDepartures : new Set([...arrivals[i].keys()].filter(t => goodDepartures.has(t) || goodDepartures.has(t ^ 1)));
			good.forEach(s => feasible[i].add(s >> 1));
		}

		const last = n > 1 ? arrivals[n - 1] : departures[0];
		let best = null;
		last.forEach((v, t) => { if (!best || compareTuples(v.t, best[1].t) < 0) best = [t, v]; });
		if (!best) return { ok: false, feasible };
		const picks = new Array(n), arrive = new Array(n), depart = new Array(n), legs = new Array(n).fill(0);
		let t = best[0];
		for (let i = n - 1; i >= 1; i--) {
			const entry = arrivals[i].get(t);
			arrive[i] = t;
			picks[i] = t >> 1;
			const s = entry.from;
			legs[i - 1] = reachFrom(s).dist.get(t);
			depart[i - 1] = s;
			t = i - 1 > 0 ? departures[i - 1].get(s).from : s;
		}
		picks[0] = (n > 1 ? depart[0] : best[0]) >> 1;
		const reversals = [];
		for (let i = 1; i < n - 1; i++) if (depart[i] !== arrive[i]) reversals.push(i);
		return { ok: true, picks, reversals, legs, feasible, arrive, depart };
	}

	/**
	 * The way along the rails from one station to another, through stations in between in order: the shortest at each
	 * part, turning back at a station on the way only when there is no other way.
	 *
	 * @param stages lists of tracks: where to start, each station to go through, and where to end
	 * @return the tracks of every station passed, in order, or null if there is no way
	 */
	function findCorridor(stages) {
		let sources = new Map();
		stages[0].forEach(p => [p * 2, p * 2 + 1].filter(isUsable).forEach(s => sources.set(s, { cost: 0, states: [s] })));
		for (let k = 1; k < stages.length; k++) {
			const targets = stages[k].flatMap(p => [p * 2, p * 2 + 1]);
			let found = null;
			sources.forEach((v, s) => {
				const { dist, prev } = reachFrom(s);
				targets.forEach(t => {
					const d = dist.get(t);
					if (d === undefined || found && v.cost + d >= found.cost) return;
					const between = [];
					for (let x = t; x !== s && x !== undefined; x = prev.get(x)) between.unshift(x);
					found = { cost: v.cost + d, states: [...v.states, ...between], end: t };
				});
			});
			if (!found) return null;
			sources = new Map([[found.end, found]]);
			// Turning back at a station on the way costs a lot, so it is only taken when nothing else gets there
			if (k < stages.length - 1 && isUsable(found.end ^ 1)) sources.set(found.end ^ 1, { cost: found.cost + 1e7, states: found.states });
		}
		const way = [...sources.values()][0].states;
		const tracks = [];
		way.forEach(s => {
			const p = s >> 1;
			if (!topo.platforms[p].station) return;
			if (tracks.length && topo.platforms[tracks[tracks.length - 1]].station === topo.platforms[p].station) return;
			tracks.push(p);
		});
		return tracks;
	}

	/**
	 * What is wrong with a route's tracks against the rails, without changing anything: stops that can't be reached at all,
	 * turning back part of the way, and long detours.
	 *
	 * @return [{stop, level: "error" | "warn", text}]
	 */
	function routeProblems(r) {
		if (!topo) return [];
		const ids = platformsOf(r);
		const key = ids.join(",");
		const cached = topo.problems.get(r.id);
		if (cached && cached.key === key) return cached.problems;
		const problems = [];
		const tracks = ids.map(id => topo.index.has(id) ? topo.index.get(id) : -1);
		const name = i => shortName(r.stops[i].name) || `第 ${i + 1} 站`;
		tracks.forEach((p, i) => { if (p < 0) problems.push({ stop: i, level: "error", text: `${name(i)}的站台没有轨道` }); });
		if (!problems.length && tracks.length > 1) {
			const network = topo.platforms[tracks[0]].network;
			tracks.forEach((p, i) => { if (topo.platforms[p].network !== network) problems.push({ stop: i, level: "error", text: `${name(i)}的站台和首站不在同一个线网里（轨道没有连起来）` }); });
		}
		if (!problems.length && tracks.length > 1) {
			const solution = solveStops(tracks.map(p => [p]), null, null);
			if (!solution.ok) {
				for (let i = 0; i + 1 < tracks.length; i++) {
					if (legCost(tracks[i], tracks[i + 1]) === Infinity) problems.push({ stop: i + 1, level: "error", text: `${name(i)}到${name(i + 1)}沿轨道走不通` });
				}
				if (!problems.length) problems.push({ stop: 0, level: "error", text: "各站单独走得通，连起来要在中途反复掉头才行" });
			} else {
				solution.reversals.forEach(i => problems.push({ stop: i, level: "warn", text: `在${name(i)}掉头` }));
				for (let i = 0; i + 1 < tracks.length; i++) {
					const best = Math.min(...stationTracks(tracks[i]).flatMap(p => stationTracks(tracks[i + 1]).map(q => legCost(p, q))));
					if (isFinite(best) && solution.legs[i] > best * 1.5 + 50) problems.push({ stop: i + 1, level: "warn", text: `${name(i)}到${name(i + 1)}绕路：换股可以近得多` });
				}
			}
		}
		topo.problems.set(r.id, { key, problems });
		return problems;
	}

	/**
	 * The track picker on the platform graph: a column for each station with its tracks on the same rails, lines between
	 * neighbouring columns where a train can run without turning back, and the way picked drawn bold. A click on a track
	 * fixes it, and the other stations move to the best tracks that still run, keeping what they had where they can.
	 *
	 * @param model {layers: [[track]], picks: [track], stop: [bool], pins: Map, color, conflict}
	 */
	function topologyHtml(model) {
		const n = model.layers.length;
		if (!n) return `<p class="hint">没有车站。</p>`;
		const solution = solveStops(model.layers, model.picks, model.pins);
		const reversals = new Set();
		if (solution.ok && solution.picks.every((p, i) => p === model.picks[i])) solution.reversals.forEach(i => reversals.add(i));
		const colW = 112, rowH = 26, head = 38, pad = 8, cellW = 92;
		const rows = Math.max(...model.layers.map(l => l.length));
		const width = pad * 2 + n * colW - (colW - cellW), height = head + rows * rowH + 28;
		const x = i => pad + i * colW, y = k => head + k * rowH;
		const parts = [`<svg class="topo" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="group" aria-label="站台连通图">`];
		for (let i = 0; i + 1 < n; i++) {
			model.layers[i].forEach((p, a) => model.layers[i + 1].forEach((q, b) => {
				if (legCost(p, q) === Infinity) return;
				const on = model.picks[i] === p && model.picks[i + 1] === q;
				parts.push(`<line x1="${x(i) + cellW}" y1="${y(a) + 10}" x2="${x(i + 1)}" y2="${y(b) + 10}" class="${on ? "link on" : "link"}"${on ? ` style="stroke:${model.color}"` : ""}/>`);
			}));
		}
		model.layers.forEach((tracks, i) => {
			const end = i === 0 || i === n - 1;
			const open = model.diagram && model.diagram.stop === i;
			parts.push(`<g class="station-head${open ? " open" : ""}" data-station-col="${i}" tabindex="0"><title>${open ? "收起配线图" : "看这一站的配线图"}</title><text x="${x(i) + cellW / 2}" y="14" class="station">${escapeHtml(stationLabel(tracks[0]))} ${open ? "▴" : "▾"}</text></g>`);
			if (reversals.has(i)) parts.push(`<text x="${x(i) + cellW / 2}" y="30" class="turn">↶ 掉头</text>`);
			tracks.forEach((p, k) => {
				const on = model.picks[i] === p, pinned = model.pins.has(i) && model.pins.get(i) === p, ok = solution.feasible[i].has(p);
				const classes = ["track", on ? "on" : "", pinned ? "pinned" : "", ok ? "" : "dead"].filter(Boolean).join(" ");
				const title = `${platformLabel(p)}${topo.platforms[p].passingTrack ? "（待避线）" : ""}：${pinned ? "已固定，点一下取消" : ok ? "点一下固定走这一股" : "和其他站连不起来，点一下仍可固定"}`;
				parts.push(`<g class="${classes}" data-stop="${i}" data-track="${p}" tabindex="0"><title>${escapeHtml(title)}</title><rect x="${x(i)}" y="${y(k)}" width="${cellW}" height="20" rx="4"${on ? ` style="fill:${model.color};stroke:${model.color}"` : ""}/><text x="${x(i) + cellW / 2}" y="${y(k) + 14}">${pinned ? "● " : ""}${escapeHtml(platformLabel(p))}${topo.platforms[p].passingTrack ? " 待避" : ""}</text></g>`);
			});
			const stops = model.stop[i] || end;
			parts.push(`<g class="stop-toggle${end ? " fixed" : ""}" data-toggle="${i}"${end ? "" : ` tabindex="0"`}><title>${end ? "首末站，停车" : stops ? "停车，点一下改为通过" : "通过，点一下改为停车"}</title><text x="${x(i) + cellW / 2}" y="${height - 8}">${stops ? "停" : "过"}</text></g>`);
		});
		parts.push(`</svg>`);
		model.solution = solution;
		return `<div class="topo-wrap">${parts.join("")}</div>${model.conflict ? `<p class="err">${escapeHtml(model.conflict)}</p>` : ""}${model.diagram ? stationDiagramHtml(model) : ""}`;
	}

	/**
	 * The rails around a station (EditorLayout), asked for again a few times while the game is still reading the map.
	 */
	async function loadDiagram(model, diagram, tries) {
		diagram.error = "";
		try {
			const result = await sendJson("api/layout", "POST", { station: diagram.station, margin: diagram.margin, tracks: model.layers[diagram.stop].map(p => topo.platforms[p].id) });
			if (model.diagram !== diagram) return;
			if (!result.ready) {
				diagram.error = "正在读取轨道…";
				if (tries < 8) setTimeout(() => loadDiagram(model, diagram, tries + 1), 600);
				else diagram.error = "服务端没有回应，稍后再点一次站名";
			} else {
				diagram.data = result;
			}
		} catch (e) {
			if (model.diagram === diagram) diagram.error = "读取配线图失败";
		}
		if (model.onChange && model.diagram === diagram) model.onChange();
	}

	/**
	 * The track diagram of a station: its rails as the game has them, curves included, turned so the train runs from left to
	 * right along the track picked, with the station's tracks on the same rails to click like the cells of the picker.
	 */
	function stationDiagramHtml(model) {
		const diagram = model.diagram, i = diagram.stop, n = model.layers.length;
		const head = `<div class="diagram-head"><b>${escapeHtml(stationLabel(model.layers[i][0]))} 配线图</b><span class="tools"><button type="button" data-zoom="in" title="放大">＋</button><button type="button" data-zoom="out" title="缩小">−</button></span></div>`;
		if (!diagram.data) return `<div class="layout">${head}<p class="hint">${escapeHtml(diagram.error || "正在读取…")}</p></div>`;
		const data = diagram.data;
		const station = topo.platforms[model.layers[i][0]].station;
		const tracks = new Set(model.layers[i].map(p => topo.platforms[p].id));
		const solution = model.solution;
		// The way the train runs here: along the track picked, the way it arrives (or leaves, at the first stop)
		const picked = data.platforms.find(p => p.id === topo.platforms[model.picks[i]].id);
		const state = solution && solution.ok ? i > 0 ? solution.arrive[i] : solution.depart[0] : undefined;
		let dx = 1, dz = 0;
		const own = data.platforms.filter(p => p.station === station);
		if (picked) {
			const forwards = state === undefined || (state & 1) === 0;
			dx = (picked.line[2] - picked.line[0]) * (forwards ? 1 : -1);
			dz = (picked.line[3] - picked.line[1]) * (forwards ? 1 : -1);
		} else if (own.length) {
			dx = own[0].line[2] - own[0].line[0];
			dz = own[0].line[3] - own[0].line[1];
		}
		const length = Math.hypot(dx, dz) || 1, cos = dx / length, sin = dz / length;
		const cx = (data.box[0] + data.box[2]) / 2, cz = (data.box[1] + data.box[3]) / 2;
		const turn = (x, z) => [(x - cx) * cos + (z - cz) * sin, -(x - cx) * sin + (z - cz) * cos];
		// The station's own tracks set the view, so the rails around it don't push it into a corner
		const ownPoints = own.flatMap(p => [turn(p.line[0], p.line[1]), turn(p.line[2], p.line[3])]);
		const fallback = [[data.box[0], data.box[1]], [data.box[2], data.box[3]]].map(([x, z]) => turn(x, z));
		const points = ownPoints.length ? ownPoints : fallback;
		const margin = diagram.margin;
		const minX = Math.min(...points.map(p => p[0])) - margin + diagram.pan[0], maxX = Math.max(...points.map(p => p[0])) + margin + diagram.pan[0];
		const minY = Math.min(...points.map(p => p[1])) - margin / 2 + diagram.pan[1], maxY = Math.max(...points.map(p => p[1])) + margin / 2 + diagram.pan[1];
		const viewW = maxX - minX, viewH = Math.max(maxY - minY, viewW * 0.3);
		const width = 560, height = Math.round(Math.min(360, Math.max(160, width * viewH / viewW)));
		const unit = Math.max(viewW / width, viewH / height);
		const parts = [`<svg class="layout-svg" viewBox="${minX} ${minY} ${viewW} ${viewH}" width="100%" style="aspect-ratio:${width} / ${height}" preserveAspectRatio="xMidYMid meet" data-unit="${unit}" role="img" aria-label="配线图">`];
		// Each rail along its curve, as the server sampled it
		data.rails.forEach(r => {
			const points = [];
			for (let k = 0; k + 1 < r.length; k += 2) points.push(turn(r[k], r[k + 1]).join(","));
			parts.push(`<polyline points="${points.join(" ")}" class="rail" stroke-width="${unit * 1.5}"/>`);
		});
		data.turnouts.forEach(t => {
			const a = turn(t[0], t[1]);
			parts.push(`<circle cx="${a[0]}" cy="${a[1]}" r="${unit * 3}" class="turnout"/>`);
		});
		data.platforms.forEach(p => {
			const a = turn(p.line[0], p.line[1]), b = turn(p.line[2], p.line[3]);
			const index = topo.index.get(p.id);
			const ours = tracks.has(p.id) && index !== undefined;
			const on = ours && model.picks[i] === index, pinned = ours && model.pins.get(i) === index;
			const dead = ours && solution && !solution.feasible[i].has(index);
			const classes = ["platform", ours ? "ours" : "other", on ? "on" : "", pinned ? "pinned" : "", dead ? "dead" : ""].filter(Boolean).join(" ");
			const label = `${pinned ? "● " : ""}${p.name || "?"}${p.passingTrack ? " 待避" : ""}${!ours && p.stationName && p.station !== station ? " · " + shortName(p.stationName) : ""}`;
			const title = ours ? `${p.name || "?"}：${pinned ? "已固定，点一下取消" : dead ? "和其他站连不起来，点一下仍可固定" : "点一下固定走这一股"}` : `${shortName(p.stationName) || "不属于车站"} ${p.name || "?"}${p.station === station ? "：和这条线的轨道不连" : ""}`;
			parts.push(`<g class="${classes}"${ours ? ` data-stop="${i}" data-track="${index}" tabindex="0"` : ""}><title>${escapeHtml(title)}</title><line x1="${a[0]}" y1="${a[1]}" x2="${b[0]}" y2="${b[1]}" stroke-width="${unit * (on ? 7 : 5)}"${on ? ` style="stroke:${model.color}"` : ""}${dead ? ` stroke-dasharray="${unit * 4} ${unit * 3}"` : ""}/><text x="${(a[0] + b[0]) / 2}" y="${(a[1] + b[1]) / 2 - unit * 6}" font-size="${unit * 11}">${escapeHtml(label)}</text></g>`);
		});
		parts.push(`</svg>`);
		const before = i > 0 ? `← ${escapeHtml(stationLabel(model.layers[i - 1][0]))}` : "";
		const after = i + 1 < n ? `${escapeHtml(stationLabel(model.layers[i + 1][0]))} →` : "";
		return `<div class="layout">${head}<div class="layout-ends"><span>${before}</span><span>${after}</span></div><div class="layout-wrap">${parts.join("")}</div>${data.truncated ? `<p class="hint">轨道太多，只画了一部分。</p>` : ""}<p class="hint">列车从左往右开。灰色的是别的车站或轨道不连的站台；拖动平移，＋/− 缩放。</p></div>`;
	}

	/** Dragging a track diagram moves it, kept in the picker's model so it stays where it was left. */
	function enableDiagramPanning(form, getModel) {
		let drag = null;
		form.addEventListener("pointerdown", e => {
			const svg = e.target.closest(".layout-svg");
			const model = getModel();
			if (!svg || !model || !model.diagram || e.target.closest("[data-track]")) return;
			const view = svg.getAttribute("viewBox").split(" ").map(Number);
			// The drawing is scaled to fit its box, so a pixel is as many blocks as the box makes it
			const unit = Math.max(view[2] / (svg.getBoundingClientRect().width || 1), view[3] / (svg.getBoundingClientRect().height || 1));
			drag = { svg, x: e.clientX, y: e.clientY, unit, view, model, pan: model.diagram.pan.slice() };
			svg.setPointerCapture(e.pointerId);
		});
		form.addEventListener("pointermove", e => {
			if (!drag) return;
			const moveX = (drag.x - e.clientX) * drag.unit, moveY = (drag.y - e.clientY) * drag.unit;
			drag.svg.setAttribute("viewBox", `${drag.view[0] + moveX} ${drag.view[1] + moveY} ${drag.view[2]} ${drag.view[3]}`);
			drag.moved = [moveX, moveY];
		});
		const end = () => {
			if (drag && drag.moved && drag.model.diagram) drag.model.diagram.pan = [drag.pan[0] + drag.moved[0], drag.pan[1] + drag.moved[1]];
			drag = null;
		};
		form.addEventListener("pointerup", end);
		form.addEventListener("pointercancel", end);
	}

	/** A click in the picker: fixing or freeing a track, or switching between stopping and passing. */
	function topologyClick(model, target) {
		const head = target.closest("[data-station-col]");
		if (head) {
			const i = +head.dataset.stationCol;
			if (model.diagram && model.diagram.stop === i) {
				model.diagram = null;
			} else {
				model.diagram = { stop: i, station: topo.platforms[model.layers[i][0]].station, margin: 48, pan: [0, 0], data: null, error: "" };
				loadDiagram(model, model.diagram, 0);
			}
			return true;
		}
		const zoom = target.closest("[data-zoom]");
		if (zoom && model.diagram) {
			model.diagram.margin = Math.max(16, Math.min(256, Math.round(model.diagram.margin * (zoom.dataset.zoom === "in" ? 0.6 : 1.6))));
			model.diagram.pan = [0, 0];
			loadDiagram(model, model.diagram, 0);
			return true;
		}
		const toggle = target.closest("[data-toggle]");
		if (toggle) {
			const i = +toggle.dataset.toggle;
			if (i > 0 && i < model.layers.length - 1) model.stop[i] = !model.stop[i];
			return true;
		}
		const cell = target.closest("[data-track]");
		if (!cell) return false;
		const i = +cell.dataset.stop, p = +cell.dataset.track;
		if (model.pins.has(i) && model.pins.get(i) === p) model.pins.delete(i);
		else model.pins.set(i, p);
		const solution = solveStops(model.layers, model.picks, model.pins);
		if (solution.ok) {
			model.picks = solution.picks;
			model.conflict = "";
		} else {
			model.picks[i] = p;
			const names = [...model.pins.keys()].sort((a, b) => a - b).map(k => stationLabel(model.layers[k][0]));
			model.conflict = `固定的站台连不起来：${names.join("、")}。取消其中一个再试。`;
		}
		return true;
	}

	/** A picker model for stations given as tracks, offering the other tracks of each station on the same rails. */
	function topologyModel(tracks, stop, color) {
		return { layers: tracks.map(stationTracks), picks: tracks.slice(), stop: stop.slice(), pins: new Map(), color, conflict: "" };
	}

	function stopsSummaryOf(model) {
		const n = model.layers.length;
		const passed = model.layers.filter((tracks, i) => !model.stop[i] && i > 0 && i < n - 1).map(tracks => stationLabel(tracks[0]));
		return `${n} 站，停 ${n - passed.length} 站${passed.length ? `，通过 ${passed.length} 站：${passed.join("、")}` : "，全部停车"}`;
	}

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

	/**
	 * A new type of a line, made along the rails (design document 6.12, block D): pick where it starts and ends and
	 * stations to go through, and its stations are those the shortest way between them passes, in order. A route of the
	 * line gives the colour, tables and depot, and fills in the ends to start with; its stations aren't copied.
	 */
	function newTypeDialog(lineKey, baseRoute) {
		const line = data.lines.find(l => l.key === lineKey) || currentLine();
		if (!line) return;
		const base0 = baseRoute && baseRoute.lineKey === line.key ? baseRoute : line.routes.find(r => r.stops.length > 1);
		if (!base0) return;
		const trackOf = id => topo.index.has(id) ? topo.index.get(id) : -1;
		// The stations on the same rails as the base route, by the first track of each
		const stationsOn = base => {
			const first = trackOf(base.stops[0].platformId);
			if (first < 0) return [];
			const network = topo.platforms[first].network, seen = new Map();
			topo.platforms.forEach((p, i) => { if (p.network === network && p.station && !seen.has(p.station)) seen.set(p.station, i); });
			return [...seen.entries()].map(([station, i]) => ({ station, track: i, name: shortName(topo.platforms[i].stationName) })).sort((a, b) => a.name.localeCompare(b.name, "zh-Hans-CN"));
		};
		let stations = [], vias = [], models = [], lastBase = null, lastKey = null, pickDir = 0, routeError = "";
		const tracksOf = station => {
			const s = stations.find(o => o.station === station);
			return s ? stationTracks(s.track) : [];
		};
		const plan = form => {
			const base = data.routeById.get(form.base.value);
			const from = form.from.value, to = form.to.value, startTrack = form.fromTrack.value;
			const both = form.both.checked;
			const key = [base.id, from, to, startTrack, both, vias.join(">")].join("|");
			if (key === lastKey) return;
			lastKey = key;
			models = [];
			routeError = "";
			if (!from || !to || from === to) {
				routeError = "选好不同的起点站和终点站";
				return;
			}
			const color = form.color.value;
			/*
			 * The stations come from the shortest way along the rails. Ways along different tracks of a station often cost the
			 * same, so each station then takes the track the line's route the same way uses there, where that still runs.
			 */
			const make = (start, ends, stops, hint, pinnedStart) => {
				const tracks = findCorridor([start, ...stops.map(tracksOf), ends]);
				if (!tracks || tracks.length < 2) return null;
				const model = topologyModel(tracks, tracks.map(() => true), color);
				if (pinnedStart >= 0) model.pins.set(0, pinnedStart);
				const hinted = new Map((hint ? hint.stops : []).filter(stop => topo.index.has(stop.platformId)).map(stop => [stop.station, topo.index.get(stop.platformId)]));
				const preferred = model.layers.map((layer, i) => {
					const track = hinted.get(topo.platforms[layer[0]].station);
					return track !== undefined && layer.includes(track) ? track : model.picks[i];
				});
				const solution = solveStops(model.layers, preferred, model.pins);
				if (solution.ok) model.picks = solution.picks;
				return model;
			};
			const startTracks = startTrack ? [+startTrack] : tracksOf(from);
			const forward = make(startTracks, tracksOf(to), vias, base, startTrack ? +startTrack : -1);
			if (!forward) {
				routeError = `从${shortName(stations.find(o => o.station === from).name)}${vias.length ? "经过选的途经站" : ""}到${shortName(stations.find(o => o.station === to).name)}沿轨道走不通`;
				return;
			}
			models.push(forward);
			if (both) {
				const backward = make(tracksOf(to), tracksOf(from), vias.slice().reverse(), counterpartOf(base), -1);
				if (backward) models.push(backward);
				else routeError = "反方向沿轨道走不通，只建这个方向";
			}
		};
		const renderPicker = form => {
			plan(form);
			models.forEach(m => {
				m.color = form.color.value;
				m.onChange = () => renderPicker(form);
			});
			const stationOptions = selected => stations.map(s => `<option value="${s.station}"${s.station === selected ? " selected" : ""}>${escapeHtml(s.name)}</option>`).join("");
			form.querySelector("#dlg-vias").innerHTML = vias.map((station, k) => `<button type="button" class="table-chip" data-via="${k}" title="点一下去掉">${escapeHtml((stations.find(o => o.station === station) || { name: "?" }).name)} ✕</button>`).join("") + `<select name="addVia"><option value="">＋ 途经站…</option>${stationOptions("")}</select>`;
			const model = models[Math.min(pickDir, models.length - 1)];
			form.querySelector(".dir-tabs").innerHTML = models.length > 1 ? models.map((m, i) => `<button type="button" class="table-chip${i === pickDir ? " on" : ""}" data-dir="${i}">${escapeHtml(stationLabel(m.picks[0]))} → ${escapeHtml(stationLabel(m.picks[m.picks.length - 1]))}</button>`).join("") : "";
			form.querySelector("#dlg-pick").innerHTML = model ? topologyHtml(model) : "";
			form.querySelector("#dlg-pick-summary").textContent = model ? stopsSummaryOf(model) : "";
			form.querySelector("#dlg-route-error").textContent = routeError;
		};
		openDialog("新建种别", `
			<div class="row"><label>线路组 <select name="base">${line.routes.filter(r => r.stops.length > 1).map(r => `<option value="${r.id}"${r === base0 ? " selected" : ""}>${escapeHtml(routeTitle(r))}</option>`).join("")}</select></label></div>
			<div class="row"><label>起点 <select name="from"></select></label><label>站台 <select name="fromTrack"></select></label><label>终点 <select name="to"></select></label></div>
			<div class="row vias" id="dlg-vias"></div>
			<label><input type="checkbox" name="both" checked> 反方向一起建</label>
			<div class="row"><label>种别 <input name="type" required placeholder="急行" maxlength="16" size="8" autocomplete="off"></label>
			<label>颜色 <input type="color" name="color" value="#2f9e55"></label></div>
			<label>线路名 <input name="name" maxlength="128" size="28" autocomplete="off" placeholder="按线路组和种别自动起名"></label>
			<div class="picker">
				<div class="picker-head"><b>车站和股道</b><span class="dir-tabs"></span></div>
				<p class="hint">车站是沿轨道从起点到终点（依次经过途经站）最短的走法经过的车站，不照抄现有线路。只列和起点轨道连在一起的车站。连线表示列车不掉头能开过去；点一股固定它，其他站自动换成走得通的股道；最下一行点一下在停车和通过之间切换。</p>
				<p class="err" id="dlg-route-error"></p>
				<div id="dlg-pick"></div>
				<div class="hint" id="dlg-pick-summary"></div>
			</div>
			<label>班次 <input name="pattern" placeholder="可以留空，例如 08:05 + 12 * 00:30" autocomplete="off"></label>
			<label>加入车厂 <select name="depot"></select></label>
			<p class="hint">线路组的这条线路给出颜色、时刻表和车厂。新种别先放在草稿里，按“应用”才在游戏里建出来，并由高级编辑器管理；加入车厂会刷新车厂路径，路径刷新后才有最快时刻。</p>`, "新建", form => {
			const type = form.type.value.trim();
			if (!type) return "写上种别名，例如 急行";
			if (!models.length) return routeError || "选好起点和终点";
			if (models.some(m => m.conflict)) return "有固定的股道连不起来";
			const times = form.pattern.value.trim() ? parsePattern(form.pattern.value) : [];
			if (!times) return PATTERN_HINT;
			const color = parseInt(form.color.value.slice(1), 16);
			const base = data.routeById.get(form.base.value);
			models.forEach((model, i) => {
				const source = i === 0 ? base : counterpartOf(base) || base;
				// The other way is named after the line's route that way, or marked as the way back when there is none
				const name = i === 0 && form.name.value.trim() ? form.name.value.trim() : i > 0 && source === base ? withSuffix(typedName(base, type), " 返") : typedName(source, type);
				const table = Math.max(0, timetableOf(source).tables.findIndex(t => t.weekdays >> state.weekday & 1));
				draft.created.push({
					key: "n" + Date.now().toString(36) + i, source: source.id, lineKey: source.lineKey, name, serviceType: type, color,
					stops: model.picks.map((p, k) => ({ platformId: topo.platforms[p].id, pass: !model.stop[k] && k > 0 && k < model.picks.length - 1 })),
					services: times, table, depot: form.depot.value,
				});
			});
			state.openNodes.add("line:" + line.key);
			saveDraft();
			renderAll();
		}, form => {
			if (form.base.value !== lastBase) {
				lastBase = form.base.value;
				const base = data.routeById.get(lastBase);
				stations = stationsOn(base);
				vias = [];
				const station = i => base.stops[i].station;
				form.from.innerHTML = stations.map(s => `<option value="${s.station}"${s.station === station(0) ? " selected" : ""}>${escapeHtml(s.name)}</option>`).join("");
				form.to.innerHTML = stations.map(s => `<option value="${s.station}"${s.station === station(base.stops.length - 1) ? " selected" : ""}>${escapeHtml(s.name)}</option>`).join("");
				form.fromTrack.dataset.station = "";
				const list = data.depots.filter(depot => depot.routes.includes(base.id));
				form.depot.innerHTML = list.map(depot => `<option value="${depot.id}">${escapeHtml(shortName(depot.name))}</option>`).join("") + `<option value="">不加入，之后在游戏里加</option>`;
			}
			if (form.fromTrack.dataset.station !== form.from.value) {
				form.fromTrack.dataset.station = form.from.value;
				form.fromTrack.innerHTML = `<option value="">任一股</option>` + tracksOf(form.from.value).map(p => `<option value="${p}">${escapeHtml(platformLabel(p))}${topo.platforms[p].passingTrack ? " 待避" : ""}</option>`).join("");
			}
			if (form.addVia && form.addVia.value) {
				vias.push(form.addVia.value);
				form.addVia.value = "";
			}
			form.name.placeholder = form.type.value.trim() ? typedName(data.routeById.get(form.base.value), form.type.value.trim()) : "按线路组和种别自动起名";
			renderPicker(form);
		});
		const form = dialog.querySelector("form");
		enableDiagramPanning(form, () => models[Math.min(pickDir, models.length - 1)]);
		form.addEventListener("click", e => {
			const tab = e.target.closest("[data-dir]");
			if (tab) {
				pickDir = +tab.dataset.dir;
				renderPicker(form);
				return;
			}
			const via = e.target.closest("[data-via]");
			if (via) {
				vias.splice(+via.dataset.via, 1);
				renderPicker(form);
				return;
			}
			const model = models[Math.min(pickDir, models.length - 1)];
			if (model && topologyClick(model, e.target)) renderPicker(form);
		});
	}

	/** The platform each stop uses, with a track change waiting in the draft. */
	const platformsOf = r => draftOf(r) && draftOf(r).platforms ? draftOf(r).platforms : r.stops.map(stop => stop.platformId);

	/**
	 * Changes which track a route uses at each station, and where it stops or passes, on the platform graph. The stations
	 * and their order stay as they are in the game, where the dashboard changes them. Problems with the tracks as they are
	 * are listed, and nothing is changed until a track is clicked.
	 */
	function stopsDialog(r) {
		const ids = platformsOf(r), pass = timetableOf(r).pass;
		const tracks = ids.map(id => topo.index.has(id) ? topo.index.get(id) : -1);
		if (tracks.some(p => p < 0)) {
			openDialog(`停站：${escapeHtml(routeTitle(r))}`, `<p class="err">有车站的站台没有轨道，没法按轨道选股道。先在游戏里把站台放回轨道上。</p>`, "关闭", () => {});
			return;
		}
		const model = topologyModel(tracks, r.stops.map((stop, i) => !pass[i]), colorOf(r.color));
		const problemsHtml = () => {
			const problems = routeProblems(r);
			return problems.length ? `<ul class="warn">${problems.map(p => `<li class="${p.level}">${escapeHtml(p.text)}</li>`).join("")}</ul>` : `<p class="hint">现在的股道沿轨道走得通，没有掉头和绕路。</p>`;
		};
		const render = form => {
			form.querySelector("#dlg-problems").innerHTML = problemsHtml();
			form.querySelector("#dlg-pick").innerHTML = topologyHtml(model);
			form.querySelector("#dlg-pick-summary").textContent = stopsSummaryOf(model);
		};
		openDialog(`停站：${escapeHtml(routeTitle(r))}`, `
			<div id="dlg-problems"></div>
			<div class="picker">
				<p class="hint">连线表示列车不掉头能开过去。点一股固定它，其他站自动换成走得通、改动最少的股道；最下一行点一下在停车和通过之间切换。车站的先后和增删在游戏的仪表盘里改。</p>
				<div id="dlg-pick"></div>
				<div class="hint" id="dlg-pick-summary"></div>
			</div>
			<p class="hint">改股道会清掉线路手画的路径，应用后要刷新车厂路径。</p>`, "确定", () => {
			if (model.conflict) return "有固定的股道连不起来";
			const newPlatforms = model.picks.map(p => topo.platforms[p].id);
			const entry = draftEntry(r);
			if (newPlatforms.every((id, i) => id === r.stops[i].platformId)) delete entry.platforms;
			else entry.platforms = newPlatforms;
			tidyDraft(r.id);
			editTimetable(r, timetable => {
				timetable.pass = model.stop.map((stops, i) => !stops && i > 0 && i < r.stops.length - 1);
			});
		}, render);
		const form = dialog.querySelector("form");
		model.onChange = () => render(form);
		enableDiagramPanning(form, () => model);
		form.addEventListener("click", e => {
			if (topologyClick(model, e.target)) render(form);
		});
	}

	/**
	 * Marks a type for deletion, after saying what still names it: those stay as they are, the same as deleting it on the
	 * dashboard, and stop working.
	 */
	function deleteTypeDialog(r) {
		const depots = data.depots.filter(depot => depot.routes.includes(r.id)).map(depot => "“" + shortName(depot.name) + "”");
		let segments = 0;
		data.depots.forEach(depot => Object.values(dutiesOf(depot).sidings).forEach(slots => slots.forEach(slot => slot.forEach(day => {
			segments += day.segments.filter(segment => segment.route === r.id).length;
		}))));
		const links = data.routes.filter(other => other !== r).reduce((count, other) => count + other.services.filter(s => s.link && s.link.route === r.id).length, 0);
		const notes = [];
		if (depots.length) notes.push(`车厂 ${depots.map(escapeHtml).join("、")} 跑这个种别，删除后不再跑它，要刷新车厂路径`);
		if (segments) notes.push(`运用里有 ${segments} 班是它的，这些运用会走不通，要改运用`);
		if (links) notes.push(`别的种别有 ${links} 班直通到它，这些直通会失效`);
		openDialog("删除种别", `
			<p>删除 <b>${escapeHtml(routeTitle(r))}</b>：它的车站、停站设置和全部时刻表都从游戏里删掉，不能恢复。</p>
			${notes.length ? `<ul class="warn">${notes.map(note => `<li>${note}</li>`).join("")}</ul>` : ""}
			<p class="hint">先放在草稿里，按“应用”才删除。应用之前可以右键取消。这个种别别的没应用的改动也一起丢掉。</p>`, "删除", () => {
			const entry = draftEntry(r);
			entry.deleted = true;
			delete entry.error;
			saveDraft();
			renderAll();
		});
	}

	function keepType(r) {
		const entry = draftOf(r);
		if (!entry) return;
		delete entry.deleted;
		delete entry.error;
		tidyDraft(r.id);
		saveDraft();
		renderAll();
	}

	function removeCreated(key) {
		draft.created = draft.created.filter(made => made.key !== key);
		saveDraft();
		renderAll();
	}

	function throughDialog(fromRoute) {
		const routes = data.routes.filter(r => r.stops.length > 1);
		if (routes.length < 2) return;
		const a0 = fromRoute || (state.route && data.routeById.get(state.route)) || routes[0];
		const lastName = r => r.stops[r.stops.length - 1].station;
		const b0 = routes.find(r => r !== a0 && r.stops[0].station === lastName(a0)) || routes.find(r => r !== a0);
		const routeOptions = (list, selected) => list.map(r => `<option value="${r.id}"${r.id === selected ? " selected" : ""}>${escapeHtml(routeTitle(r))}</option>`).join("");
		// The last stop and boundaries to leave from; the first stop and boundaries to join at
		const exitOptions = r => [`<option value="-1">${escapeHtml(stopName(r, r.stops.length - 1))}（末站）</option>`, ...r.stops.map((_, i) => i).filter(i => isBoundary(r, i)).map(i => `<option value="${i}">${escapeHtml(stopName(r, i))}（时刻边界）</option>`)].join("");
		const entryOptions = r => [`<option value="0">${escapeHtml(stopName(r, 0))}（首站）</option>`, ...r.stops.map((_, i) => i).filter(i => isBoundary(r, i)).map(i => `<option value="${i}">${escapeHtml(stopName(r, i))}（时刻边界）</option>`)].join("");
		let lastFrom = null, lastTo = null;
		const pairs = form => {
			const a = data.routeById.get(form.from.value), b = data.routeById.get(form.to.value);
			if (!a || !b) return null;
			const fromStop = +form.fromStop.value, toStop = +form.toStop.value;
			const exitIndex = fromStop < 0 ? a.stops.length - 1 : fromStop;
			const t1 = parseClock(form.t1.value), t2 = parseClock(form.t2.value), gap = Math.max(0, +form.gap.value || 0) * 60000, wait = Math.max(+form.wait.value || 0, 0) * 60000 + gap;
			if (t1 === null || t2 === null) return null;
			// Times of day as the clock shows them, compared within the operating day
			const local = s => mod(s.start + data.dayStartMillis + data.offsetMillis, DAY);
			const used = new Set(b.services.map(s => s.service).filter(service => a.services.some(s => !form.replace.checked && s.link && s.link.route === b.id && s.link.toService === service)));
			const result = [];
			a.services.filter(s => local(s) >= t1 && local(s) <= t2 && (form.replace.checked || !s.link)).forEach(s => {
				const out = timesOf(s)[exitIndex][0];
				const next = b.services.filter(o => !used.has(o.service)).map(o => ({ o, time: timesOf(o)[toStop][1] }))
					.filter(x => x.time >= out + gap && x.time <= out + wait).sort((x, y) => x.time - y.time)[0];
				if (!next) return;
				used.add(next.o.service);
				result.push({ s, out, to: next.o, into: next.time });
			});
			return { result, fromStop, toStop, a, b, exitIndex };
		};
		openDialog("新建直通关系", `
			<div class="row"><label>从 <select name="from">${routeOptions(routes, a0.id)}</select></label><label>在 <select name="fromStop"></select> 接出</label></div>
			<div class="row"><label>到 <select name="to">${routeOptions(routes.filter(r => r !== a0), b0.id)}</select></label><label>在 <select name="toStop"></select> 接入</label></div>
			<div class="row"><label>首站发车在 <input name="t1" value="05:00" size="6"> 到 <input name="t2" value="26:00" size="6"> 之间（${WEEKDAY_NAMES[state.weekday]}的表）</label></div>
			<div class="row"><label>衔接至少 <input name="gap" type="number" value="3" min="0" max="60" style="width:4.5em"> 分钟，最多再等 <input name="wait" type="number" value="12" min="0" max="120" style="width:4.5em"> 分钟</label></div>
			<label><input type="checkbox" name="replace"> 替换这些班次原有的直通</label>
			<p class="preview" id="dialog-preview"></p>
			<p class="hint">中途接出和接入只能在游戏里标了时刻边界的站台（站台界面里设置）；没有边界的线路只能末站接首站。每一班接上对方能赶上的第一班，同一班不接两次，等太久的不接。</p>`, "新建", form => {
			const found = pairs(form);
			if (!found) return "时刻写成 07:10";
			if (!found.result.length) return "没有能接上的班次";
			found.result.forEach(x => setLink(x.s, found.fromStop, x.to, found.toStop));
		}, form => {
			if (form.from.value !== lastFrom) {
				lastFrom = form.from.value;
				const a = data.routeById.get(lastFrom);
				form.fromStop.innerHTML = exitOptions(a);
				const to = form.to.value;
				form.to.innerHTML = routeOptions(routes.filter(r => r !== a), to && to !== a.id ? to : routes.find(r => r !== a).id);
				lastTo = null;
			}
			if (form.to.value !== lastTo) {
				lastTo = form.to.value;
				form.toStop.innerHTML = entryOptions(data.routeById.get(lastTo));
			}
			const found = pairs(form), preview = $("dialog-preview");
			if (!found) {
				preview.textContent = "时刻写成 07:10";
				return;
			}
			preview.textContent = found.result.length
				? `${found.result.length} 对：\n` + found.result.slice(0, 8).map(x => `${clock(x.s.start)} 班 ${stopName(found.a, found.exitIndex)} ${clock(x.out)} 到 → ${clock(x.to.start)} 班 ${stopName(found.b, found.toStop)} ${clock(x.into)} 发`).join("\n") + (found.result.length > 8 ? "\n…" : "")
				: "没有能接上的班次";
		});
	}

	function addServices() {
		const r = tableRouteOf(), input = $("add-service");
		if (!r || !input) return;
		const services = parsePattern(input.value);
		if (!services) {
			tableMessage = PATTERN_HINT;
			renderTable();
			return;
		}
		const today = timetableOf(r).tables.findIndex(table => table.weekdays >> state.weekday & 1);
		editTimetable(r, timetable => {
			const table = timetable.tables[today];
			const added = services.filter(service => !table.services.includes(service));
			if (table.services.length + added.length > MAX_SERVICES) return `一张表最多 ${MAX_SERVICES} 个班次`;
			table.services.push(...added);
			return added.length < services.length ? `已有的 ${services.length - added.length} 班没有重复添加` : "";
		});
		const next = $("add-service");
		if (next) next.focus();
	}

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
	function renderBanner() {
		const banner = $("banner");
		const messages = {
			key: "这个页面的链接已经失效。请在游戏的仪表盘里重新点“高级编辑器”。",
			world: "游戏现在不在世界里，进入世界后会自动刷新。",
			offline: "连不上游戏。游戏关了，或者离开了这个世界。请在游戏的仪表盘里重新点“高级编辑器”。",
		};
		const missingTimes = data && data.routes.some(r => !r.hasTimes && r.timetable);
		const problem = !key ? messages.key : messages[connection];
		const failed = Object.entries(draft.routes).filter(([, entry]) => entry.error);
		const otherFailed = Object.values(draft.duties).some(entry => entry.error) || draft.created.some(made => made.error);
		let html = "";
		if (problem) {
			html = escapeHtml(problem);
		} else if (state.pendingExit && findService(state.pendingExit.id)) {
			const s = findService(state.pendingExit.id);
			html = `已设接出点：<b>${clock(s.start)} ${escapeHtml(routeTitle(s.route))}</b> 在 <b>${escapeHtml(stopName(s.route, state.pendingExit.index))}</b>。打开目标线路的时刻表，右键首站或时刻边界站的一格，选“设为接入点”。<button data-cancel-exit>取消</button>`;
		} else if (data && !data.canEdit) {
			html = "没有编辑权限：需要创造模式，或服务端设置的权限等级。草稿照常保存，有权限后再应用。";
		} else if (applyResult && (!applyResult.ok || failed.length || otherFailed)) {
			const name = id => data && data.routeById.get(id) ? escapeHtml(routeTitle(data.routeById.get(id))) : "已删除的线路";
			html = `应用了 ${applyResult.applied} 项，下面的没有应用，留在草稿里：<ul>`
				+ applyResult.general.map(message => `<li>${escapeHtml(message)}</li>`).join("")
				+ Object.entries(draft.duties).filter(([, entry]) => entry.error).map(([id, entry]) => `<li>车厂${escapeHtml(shortName(depotById(id) ? depotById(id).name : "?"))}的运用：${escapeHtml(entry.error)}</li>`).join("")
				+ draft.created.filter(made => made.error).map(made => `<li>新种别 ${escapeHtml(shortName(made.name))}：${escapeHtml(made.error)}</li>`).join("")
				+ failed.map(([id, entry]) => `<li><a data-open-route="${id}">${name(id)}</a>：${escapeHtml(entry.error)}${entry.conflict ? `<button data-keep="${id}">保留草稿</button><button data-drop="${id}">用游戏里的</button>` : ""}</li>`).join("")
				+ "</ul>";
		} else if (applyResult && applyResult.ok) {
			html = `已应用 ${applyResult.applied} 项。`;
		} else if (dutyNotice) {
			html = escapeHtml(dutyNotice);
		} else if (missingTimes) {
			html = "部分线路还没有各站时刻，正在向服务端取，取到之前按虚线画。";
		}
		banner.hidden = !html;
		banner.innerHTML = html;
		banner.classList.toggle("warn", Boolean(problem) || Boolean(data && !data.canEdit) || Boolean(applyResult && (!applyResult.ok || failed.length)));
	}

	function renderStatus() {
		const line = currentLine();
		const count = line ? line.routes.reduce((a, r) => a + r.services.length, 0) : 0;
		const s = state.selected && data ? findService(state.selected) : null;
		const parts = [];
		if (s) parts.push(`<span>选中 <b>${clock(s.start)}</b> ${escapeHtml(routeTitle(s.route))}</span>`);
		parts.push(`<span>线路 <b>${data ? data.lines.length : 0}</b></span>`, `<span>本线班次 <b>${count}</b></span>`);
		parts.push(connection === "ok" ? `<span>已同步 <b>${new Date(lastLoaded || Date.now()).toLocaleTimeString()}</b></span>` : `<span class="warn">未连接</span>`);
		$("status").innerHTML = parts.join("");
	}

	function renderAll() {
		renderTree();
		renderViews();
		draw();
		renderInspector();
		renderTable();
		renderBanner();
		renderStatus();
		// The delete button follows the type that is open
		renderDraftState();
	}

	// ---------- Tree folding ----------
	const portrait = matchMedia("(orientation: portrait) and (max-width: 1100px)");
	function setTreeOpen(open) {
		state.treeOpen = open;
		document.body.classList.toggle("tree-closed", !open);
		$("btn-tree").setAttribute("aria-expanded", String(open));
		requestAnimationFrame(draw);
	}

	// ---------- Events ----------
	$("tree").addEventListener("click", e => onTreeClick(e.target));
	// Right-click a line for a new type, a route for a new table or a type copied from it, a new type for removing it
	$("tree").addEventListener("contextmenu", e => {
		const el = e.target.closest(".node");
		if (!el || !data) return;
		const { line, route, created, depot: depotId, siding: sidingId, slot, ofDepot } = el.dataset;
		const r = route && data.routeById.get(route);
		const depot = depotById(depotId || ofDepot);
		const sidingSlots = () => (dutiesOf(depot).sidings[sidingId] || []);
		const siding = depot && sidingId && depot.sidings.find(o => o.id === sidingId);
		const items = slot !== undefined && depot ? [
			["清空这列车当天的运用", () => editDuties(depot, duties => { const day = dutyOn(duties.sidings[sidingId][+slot], state.weekday); if (day) dutyForEditing(depot, duties.sidings[sidingId][+slot], state.weekday).segments = []; })],
			["删除这个库位", () => changeSlots(depot, sidingId, slots => slots.splice(+slot, 1))],
		] : siding ? [
			["添加库位", siding.maxTrains < 0 || sidingSlots().length < siding.maxTrains ? () => changeSlots(depot, sidingId, slots => slots.push([])) : null],
			["删除最后一个库位", sidingSlots().length ? () => changeSlots(depot, sidingId, slots => slots.pop()) : null],
		] : depotId && depot ? [
			["一键生成运用…", data.canEdit ? () => generateDuties(depot) : null],
			[dutiesOf(depot).mode === "diagram" ? "改为在线预约" : "改为按运用图", () => setDutyMode(depot, dutiesOf(depot).mode === "diagram" ? "online" : "diagram")],
			["丢掉运用的草稿", draft.duties[depot.id] ? () => { delete draft.duties[depot.id]; saveDraft(); renderAll(); } : null],
		] : line ? [["新建种别…", data.canEdit ? () => newTypeDialog(line, null) : null]]
			: r ? [["编辑停站…", data.canEdit && r.stops.length > 1 ? () => stopsDialog(r) : null], ["新建时刻表…", () => tableDialog(r, -1)], ["照着它新建种别…", data.canEdit ? () => newTypeDialog(r.lineKey, r) : null],
				isDeleted(r) ? ["取消删除", () => keepType(r)] : ["删除这个种别…", data.canEdit ? () => deleteTypeDialog(r) : null]]
				: created ? [["删除这个新建的种别", () => removeCreated(created)]] : null;
		if (!items) return;
		e.preventDefault();
		openMenu(e.clientX, e.clientY, items);
	});
	$("tree").addEventListener("keydown", e => {
		if (e.key === "Enter" || e.key === " ") {
			e.preventDefault();
			onTreeClick(e.target);
		}
	});
	$("btn-tree").addEventListener("click", () => setTreeOpen(!state.treeOpen));
	$("scrim").addEventListener("click", () => setTreeOpen(false));
	$("tab-diagram").addEventListener("click", () => { state.view = "diagram"; renderAll(); });
	$("tab-table").addEventListener("click", () => { state.view = "table"; renderAll(); });
	$("btn-split").addEventListener("click", () => { state.split = !state.split; renderAll(); });
	$("btn-apply").addEventListener("click", applyDraft);
	$("btn-delete").addEventListener("click", () => {
		const r = data && state.route && data.routeById.get(state.route);
		if (r) isDeleted(r) ? keepType(r) : deleteTypeDialog(r);
	});
	$("btn-new").addEventListener("click", e => {
		e.stopPropagation();
		const box = e.currentTarget.getBoundingClientRect();
		const r = data && (state.route ? data.routeById.get(state.route) : currentLine() && currentLine().routes[0]);
		openMenu(box.left, box.bottom + 4, [
			["种别…", r && data.canEdit ? () => newTypeDialog(r.lineKey, r) : null],
			["时刻表…", r ? () => tableDialog(r, -1) : null],
			["直通关系…", data && data.routes.length > 1 ? () => throughDialog(r) : null],
		]);
	});
	$("banner").addEventListener("click", e => {
		if (e.target.closest("[data-cancel-exit]")) {
			state.pendingExit = null;
			renderAll();
			return;
		}
		const target = e.target.closest("[data-open-route], [data-keep], [data-drop]");
		if (!target) return;
		if (target.dataset.keep) resolveConflict(target.dataset.keep, true);
		else if (target.dataset.drop) resolveConflict(target.dataset.drop, false);
		else if (data && data.routeById.get(target.dataset.openRoute)) openView("table", data.routeById.get(target.dataset.openRoute).lineKey, target.dataset.openRoute);
	});
	$("inspector").addEventListener("click", e => {
		const remove = e.target.closest("[data-remove-segment]");
		const depot = state.slot && depotById(state.slot.depot);
		if (remove && depot) editDuties(depot, duties => { dutyForEditing(depot, duties.sidings[state.slot.siding][state.slot.index], state.weekday).segments.splice(+remove.dataset.removeSegment, 1); });
	});
	$("inspector").addEventListener("change", e => {
		const depot = state.slot && depotById(state.slot.depot);
		if (e.target.matches("[data-duty-next]") && depot) {
			const next = Number(e.target.value);
			editDuties(depot, duties => { dutyForEditing(depot, duties.sidings[state.slot.siding][state.slot.index], state.weekday).next = next === state.slot.index ? -1 : next; });
		}
		if (e.target.matches("[data-duty-stay]") && depot) {
			const stay = e.target.checked;
			editDuties(depot, duties => { dutyForEditing(depot, duties.sidings[state.slot.siding][state.slot.index], state.weekday).stay = stay; });
		}
	});
	$("btn-zoom-in").addEventListener("click", () => zoom(1));
	$("btn-zoom-out").addEventListener("click", () => zoom(-1));
	$("day").addEventListener("change", e => {
		if (data) setWeekday(Number(e.target.value));
	});
	$("view-table").addEventListener("click", e => {
		const r = tableRouteOf();
		const managedButton = e.target.closest("[data-managed]");
		if (managedButton) {
			if (r) setManaged(r, managedButton.dataset.managed === "true");
			return;
		}
		const tableButton = e.target.closest("[data-table]");
		if (tableButton && r) {
			const mask = timetableOf(r).tables[+tableButton.dataset.table].weekdays;
			if (mask) setWeekday(WEEKDAY_SHORT.findIndex((_, i) => mask >> i & 1));
			return;
		}
		if (e.target.closest("[data-new-table]") && r) {
			tableDialog(r, -1);
			return;
		}
		if (e.target.closest("[data-add-service]")) {
			addServices();
			return;
		}
		const cell = e.target.closest("td[data-svc], th[data-svc]");
		if (cell) {
			if (state.selected !== cell.dataset.svc || state.selectedRoute) {
				state.selected = cell.dataset.svc;
				state.selectedRoute = null;
				const s = findService(cell.dataset.svc);
				if (s) centerOn(s);
				draw();
				renderInspector();
				renderStatus();
				$("view-table").querySelectorAll(".hl").forEach(el => el.classList.remove("hl"));
				$("view-table").querySelectorAll(`[data-svc="${cell.dataset.svc}"]`).forEach(el => el.classList.add("hl"));
			}
			editCell(cell);
		}
	});
	$("view-table").addEventListener("contextmenu", e => {
		const r = tableRouteOf();
		const tableButton = e.target.closest("[data-table]");
		if (tableButton && r) {
			e.preventDefault();
			const index = +tableButton.dataset.table, timetable = timetableOf(r);
			openMenu(e.clientX, e.clientY, [
				["改名和运行的星期…", () => tableDialog(r, index)],
				[timetable.tables.length > 1 ? "删除这张表" : "删除这张表（至少要留一张）", timetable.tables.length > 1 ? () => {
					if (confirm(`删除“${tableName(timetable.tables[index], index)}”和它的 ${timetable.tables[index].services.length} 个班次？`)) editTimetable(r, edited => { edited.tables.splice(index, 1); });
				} : null],
			]);
			return;
		}
		const cell = e.target.closest("td[data-svc], th[data-svc]");
		if (cell) {
			e.preventDefault();
			cellMenu(e, cell);
		}
	});
	$("view-table").addEventListener("change", e => {
		const r = tableRouteOf();
		if (!r) return;
		if (e.target.matches("[data-margin]")) {
			const value = Math.max(0, Math.min(100, Math.round(Number(e.target.value)) || 0));
			const today = timetableOf(r).tables.findIndex(table => table.weekdays >> state.weekday & 1);
			editTimetable(r, timetable => { timetable.tables[today].marginPercent = value; });
		} else if (e.target.matches("[data-enabled]")) {
			const enabled = e.target.checked;
			editTimetable(r, timetable => { timetable.enabled = enabled; });
		}
	});
	$("view-table").addEventListener("keydown", e => {
		if (e.target.id === "add-service" && e.key === "Enter") {
			addServices();
			return;
		}
		const cell = e.target.closest("td[data-svc]");
		if (cell && !cell.querySelector("input") && (e.key === "Enter" || /^\d$/.test(e.key))) {
			e.preventDefault();
			editCell(cell);
		}
	});

	let drag = null, lastClick = null;
	const hitHandle = (x, y) => handles.find(h => Math.abs(h.x - x) < 7 && Math.abs(h.y - y) < 7);

	/**
	 * Drags the end of a stop: one selected service keeps its own stop time as an exception, and with the whole route
	 * selected the table's departure time changes for all of them.
	 */
	function dragDwell(handle, x) {
		const s = handle.s, i = handle.i, arrival = timesOf(s)[i][0];
		const dwell = Math.max(0, Math.round((millisAt(x) - arrival) / 5000) * 5);
		editTimetable(s.route, timetable => {
			const table = timetable.tables[s.tableIndex];
			if (state.selectedRoute === s.route.id) {
				// Set times count from the first stop, which is where the table's plan starts
				table.stops[i].departure = Math.round(s.tablePlans[i].arrival / 1000) + dwell;
			} else {
				setStopException(table, s.service, i, { dwell });
			}
		});
	}

	canvas.addEventListener("pointerdown", e => {
		const box = canvas.getBoundingClientRect();
		const handle = hitHandle(e.clientX - box.left, e.clientY - box.top);
		drag = handle ? { handle, moved: false } : { x: e.clientX, y: e.clientY, viewStart: state.viewStart, scrollY: state.scrollY, moved: false };
		canvas.setPointerCapture(e.pointerId);
	});
	canvas.addEventListener("pointermove", e => {
		const box = canvas.getBoundingClientRect();
		if (drag && drag.handle) {
			drag.moved = true;
			canvas.classList.add("dragging");
			dragDwell(drag.handle, e.clientX - box.left);
			return;
		}
		if (drag) {
			const dx = e.clientX - drag.x, dy = e.clientY - drag.y;
			if (Math.abs(dx) + Math.abs(dy) > 3) drag.moved = true;
			if (drag.moved) {
				canvas.classList.add("dragging");
				state.viewStart = drag.viewStart - dx / state.pxPerMin * 60000;
				state.scrollY = drag.scrollY - dy;
				showTooltip(null);
				draw();
			}
			return;
		}
		const s = hitService(e.clientX - box.left, e.clientY - box.top);
		canvas.classList.toggle("pointer", Boolean(s) || Boolean(hitHandle(e.clientX - box.left, e.clientY - box.top)));
		showTooltip(s, e.clientX, e.clientY);
	});
	canvas.addEventListener("pointerup", e => {
		const box = canvas.getBoundingClientRect();
		if (drag && !drag.moved && !drag.handle) {
			const x = e.clientX - box.left, y = e.clientY - box.top;
			const s = hitService(x, y);
			if (state.slot && s) {
				toggleInSlot(s);
				selectService(s.id, false);
				drag = null;
				canvas.classList.remove("dragging");
				return;
			}
			// A second click on the same service soon after selects every service of its route
			const now = performance.now(), again = s && lastClick && lastClick.id === s.id && now - lastClick.time < 350;
			lastClick = again ? null : { id: s && s.id, time: now };
			state.selectedRoute = again ? s.route.id : null;
			selectService(s ? s.id : null, false);
		}
		drag = null;
		canvas.classList.remove("dragging");
	});
	canvas.addEventListener("pointerleave", () => showTooltip(null));
	canvas.addEventListener("wheel", e => {
		e.preventDefault();
		if (e.shiftKey) {
			state.scrollY += e.deltaY;
			draw();
		} else if (Math.abs(e.deltaX) > Math.abs(e.deltaY)) {
			state.viewStart += e.deltaX / state.pxPerMin * 60000;
			draw();
		} else {
			zoom(e.deltaY < 0 ? 1 : -1, e.clientX - canvas.getBoundingClientRect().left);
		}
	}, { passive: false });
	document.addEventListener("keydown", e => {
		if (e.key === "Escape" && state.slot && !dialog.open && e.target.tagName !== "INPUT") {
			state.slot = null;
			renderAll();
			return;
		}
		if (e.key === "Escape" && (state.selected || state.selectedRoute) && !dialog.open && e.target.tagName !== "INPUT") {
			state.selectedRoute = null;
			selectService(null, false);
		}
	});
	new ResizeObserver(() => draw()).observe(wrap);
	addEventListener("resize", renderAll);
	portrait.addEventListener("change", () => setTreeOpen(!portrait.matches));
	matchMedia("(prefers-color-scheme: dark)").addEventListener("change", draw);

	// ---------- Start ----------
	// Today in the operating day, and the view starting just before the first trains
	const nowDay = () => data ? Math.floor((Date.now() - data.dayStartMillis) / DAY) : Math.floor(Date.now() / DAY);
	setTreeOpen(!portrait.matches);
	state.viewStart = 2 * 3600000;
	renderAll();
	(async () => {
		await loadDraft();
		await poll();
	})();
	// Pick today once the first data arrives, which tells when the operating day starts
	const pickToday = setInterval(() => {
		if (!data) return;
		clearInterval(pickToday);
		state.weekday = mod(nowDay() + FIRST_DAY_WEEKDAY, 7);
		$("day").value = String(state.weekday);
		buildServices();
		const firsts = data.routes.flatMap(r => r.services.map(s => s.start));
		state.viewStart = firsts.length ? Math.max(-1800000, Math.min(...firsts) - 1800000) : 2 * 3600000;
		renderAll();
	}, 100);
})();
