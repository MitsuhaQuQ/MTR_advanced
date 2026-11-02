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
	let draft = { routes: {} }, draftLoaded = false, draftTimer = null, applyResult = null, applying = false;
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
		draftLoaded = true;
	}

	/** Saves the draft shortly after the last change, so typing doesn't write it on every key. */
	function saveDraft() {
		clearTimeout(draftTimer);
		draftTimer = setTimeout(() => sendJson("api/draft", "PUT", draft).catch(() => {}), 400);
		renderDraftState();
	}

	const draftCount = () => Object.keys(draft.routes).length;
	const draftOf = r => draft.routes[r.id];
	const draftEntry = r => draft.routes[r.id] || (draft.routes[r.id] = { base: r.revision });
	/** A route's entry goes once nothing is left in it but where it came from. */
	function tidyDraft(id) {
		const entry = draft.routes[id];
		if (entry && Object.keys(entry).every(k => k === "base" || k === "force" || k === "error" || k === "conflict")) delete draft.routes[id];
	}
	const isManaged = r => draftOf(r) && "managed" in draftOf(r) ? draftOf(r).managed : r.editorManaged;

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
		applying = true;
		renderDraftState();
		try {
			const result = await sendJson("api/apply", "POST", { operations });
			const failed = new Set();
			(result.errors || []).forEach(error => {
				if (error.index < 0) return;
				const id = owners[error.index];
				failed.add(id);
				const entry = draft.routes[id];
				if (!entry) return;
				entry.conflict = error.message === "CONFLICT";
				entry.error = entry.conflict ? "游戏里的这条线路在草稿之后改过" : error.message;
			});
			owners.forEach(id => { if (!failed.has(id)) delete draft.routes[id]; });
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
		tag.textContent = applying ? "正在应用…" : count ? `草稿 ${count} 条线路` : "没有草稿";
		tag.classList.toggle("has-draft", count > 0);
		$("btn-apply").disabled = applying || !count || !(data && data.canEdit);
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
		if (!state.line || !lines.has(state.line)) state.line = data.lines.length ? data.lines[0].key : null;
		if (state.route && !data.routeById.has(state.route)) state.route = null;
		if (state.selected && !findService(state.selected)) state.selected = null;
		buildServices();
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
					parts.push(`<li>${node(`data-route="${r.id}"`, routeOpen ? "▾" : "▸", `<span class="swatch" style="background:${colorOf(r.color)}"></span><span class="label">${escapeHtml(routeTitle(r))}</span>${draftOf(r) ? `<span class="draft-dot" title="有没应用的草稿">•</span>` : ""}<span class="count">${r.services.length}</span>`, false)}`);
					if (routeOpen) {
						parts.push(`<ul>`);
						parts.push(`<li>${node(`data-route="${r.id}" data-view="table"`, "", `<span class="label leaf">汇总时刻表</span>`, state.route === r.id && state.view === "table")}</li>`);
						parts.push(`<li>${node(`data-route="${r.id}" data-view="diagram"`, "", `<span class="label leaf">运行图</span>`, state.route === r.id && state.view === "diagram")}</li>`);
						parts.push(`</ul>`);
					}
					parts.push(`</li>`);
				});
				parts.push(`</ul>`);
			}
			parts.push(`</li>`);
		});
		parts.push(`</ul><div class="section">车厂</div><ul>`);
		if (!data.depots.length) parts.push(`<li class="empty">没有车厂</li>`);
		data.depots.forEach(depot => {
			const open = state.openNodes.has("depot:" + depot.id);
			parts.push(`<li>${node(`data-depot="${depot.id}"`, open ? "▾" : "▸", `<span class="label group-label">${escapeHtml(shortName(depot.name))}</span>`, false)}`);
			if (open) {
				parts.push(`<ul>`);
				if (!depot.sidings.length) parts.push(`<li class="empty">没有存车线</li>`);
				depot.sidings.forEach(siding => parts.push(`<li>${node("", "", `<span class="label leaf">${escapeHtml(shortName(siding.name) || "存车线")}</span><span class="count">${siding.maxTrains < 0 ? "不限" : siding.maxTrains + " 列"}</span>`, false)}</li>`));
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
		const { line, route, view, depot } = el.dataset;
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
			ctx.strokeStyle = colorOf(s.route.color);
			ctx.lineWidth = isSelected ? 2.5 : 1.5;
			ctx.setLineDash(s.route.hasTimes ? [] : [4, 3]);
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
				rows.push(`<tr class="${k === 0 ? "first-of-station" : ""}">${k === 0 ? `<th class="st" rowspan="${kinds.length}" title="${escapeHtml(stop.name)}${stop.boundary ? "（时刻边界，可以在这里接出或接入）" : ""}">${escapeHtml(shortName(stop.name))}${stop.boundary ? `<span class="boundary"> 界</span>` : ""}${isLoop(r) && i > 0 && i < r.stops.length - 1 && loopEntryStop(r) === i ? `<span class="entry" title="车厂的列车从这里接入环线"> 入</span>` : ""}</th>` : ""}<th class="kind">${label}</th>${cells}</tr>`);
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
		let html = "";
		if (problem) {
			html = escapeHtml(problem);
		} else if (state.pendingExit && findService(state.pendingExit.id)) {
			const s = findService(state.pendingExit.id);
			html = `已设接出点：<b>${clock(s.start)} ${escapeHtml(routeTitle(s.route))}</b> 在 <b>${escapeHtml(stopName(s.route, state.pendingExit.index))}</b>。打开目标线路的时刻表，右键首站或时刻边界站的一格，选“设为接入点”。<button data-cancel-exit>取消</button>`;
		} else if (data && !data.canEdit) {
			html = "没有编辑权限：需要创造模式，或服务端设置的权限等级。草稿照常保存，有权限后再应用。";
		} else if (applyResult && (!applyResult.ok || failed.length)) {
			const name = id => data && data.routeById.get(id) ? escapeHtml(routeTitle(data.routeById.get(id))) : "已删除的线路";
			html = `应用了 ${applyResult.applied} 条线路，下面的没有应用，留在草稿里：<ul>`
				+ applyResult.general.map(message => `<li>${escapeHtml(message)}</li>`).join("")
				+ failed.map(([id, entry]) => `<li><a data-open-route="${id}">${name(id)}</a>：${escapeHtml(entry.error)}${entry.conflict ? `<button data-keep="${id}">保留草稿</button><button data-drop="${id}">用游戏里的</button>` : ""}</li>`).join("")
				+ "</ul>";
		} else if (applyResult && applyResult.ok) {
			html = `已应用 ${applyResult.applied} 条线路。`;
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
	$("btn-new").addEventListener("click", e => {
		e.stopPropagation();
		const box = e.currentTarget.getBoundingClientRect();
		const r = data && (state.route ? data.routeById.get(state.route) : currentLine() && currentLine().routes[0]);
		openMenu(box.left, box.bottom + 4, [
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
