"use strict";

/*
 * The advanced editor: the routes of each line on a running diagram, and each route's timetable laid out like a printed
 * one, both editable. Changes are kept as a draft until applied to the game. The game serves this page to this computer
 * only, and the data needs the key in the address.
 */
(() => {
	const DAY = 24 * 60 * 60 * 1000;
	// Checked often, so changes made in the game show soon; less often while the page is hidden
	const POLL_MILLIS = 1500, POLL_HIDDEN_MILLIS = 5000;
	// Room below the last station for the labels of trains carrying on to another route
	const LABEL_W = 120, AXIS_H = 28, MIN_ROW_GAP = 22, PAD_BOTTOM = 30;
	const DEPOT_ROW_ROOM = 26;
	// The space above a section of a line's stations other than the first, as a share of a row's
	const SECTION_GAP = 1.5;
	// How far to each side of a line's path its track diagram shows, in blocks
	const LINE_DIAGRAM_HALF_WIDTH = 16;
	// In the line track layout: how far apart rows of station names are, and how far a rail may wander from where it
	// usually runs beside the route and still be drawn straight
	const LABEL_ROW_PX = 14, RAIL_WOBBLE_BLOCKS = 2;
	// Pixels a minute: from the whole day at a glance to about a pixel a second (2026-11-09)
	const ZOOMS = [0.25, 0.5, 1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64];
	const WEEKDAY_NAMES = translatedList(["周一", "周二", "周三", "周四", "周五", "周六", "周日"]);
	const WEEKDAY_SHORT = translatedList(["一", "二", "三", "四", "五", "六", "日"]);
	// The same as RouteTimetable.MAX_SERVICES and MAX_TABLES in the game
	const MAX_SERVICES = 4096, MAX_TABLES = 16;
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
		Object.values(draft.routes).forEach(entry => {
			if (entry.conflict && !entry.error) entry.error = tr("游戏里的这条线路在草稿之后改过");
		});
		if (!Array.isArray(draft.created)) draft.created = [];
		if (!draft.duties || typeof draft.duties !== "object") draft.duties = {};
		if (!draft.boundaries || typeof draft.boundaries !== "object") draft.boundaries = {};
		if (!draft.depots || typeof draft.depots !== "object") draft.depots = {};
		if (!draft.depotRules || typeof draft.depotRules !== "object") draft.depotRules = {};
		if (!draft.platformStations || typeof draft.platformStations !== "object") draft.platformStations = {};
		if (!draft.platformSpeeds || typeof draft.platformSpeeds !== "object") draft.platformSpeeds = {};
		if (!draft.platformStops || typeof draft.platformStops !== "object") draft.platformStops = {};
		if (!draft.networkSettings || typeof draft.networkSettings !== "object") draft.networkSettings = null;
		if (!draft.contingencies || typeof draft.contingencies !== "object") draft.contingencies = {};
		draftLoaded = true;
	}

	/** Drops the whole draft: the game's data is what shows again, and the empty draft is saved. */
	function clearDraft() {
		draft = { routes: {}, created: [], duties: {}, boundaries: {}, depots: {}, depotRules: {}, platformStations: {}, platformSpeeds: {}, platformStops: {}, networkSettings: null, contingencies: {} };
		plannedReturns.clear();
		dutyNotice = tr("草稿已清空。");
		saveDraft();
		buildServices();
		renderAll();
	}

	/** Saves the draft shortly after the last change, so typing doesn't write it on every key. */
	function saveDraft() {
		coverage = null;
		terminal = null;
		clearTimeout(draftTimer);
		draftTimer = setTimeout(() => sendJson("api/draft", "PUT", draft).catch(() => {}), 400);
		renderDraftState();
		scheduleSimulation();
	}

	// New types wait in the draft too, as what to make, until they are applied
	const draftCount = () => Object.keys(draft.routes).length + draft.created.length + Object.keys(draft.duties).length + Object.keys(draft.boundaries || {}).length + Object.keys(draft.depots || {}).length + Object.keys(draft.depotRules || {}).length + Object.keys(draft.platformStations || {}).length + Object.keys(draft.platformSpeeds || {}).length + Object.keys(draft.platformStops || {}).length + (draft.networkSettings ? 1 : 0) + Object.keys(draft.contingencies || {}).length;
	// A message about the last thing done to duties, shown in the banner
	let dutyNotice = "";
	// The route to show once the game has made a new one
	let pendingOpen = null;
	const draftOf = r => draft.routes[r.id];
	// Each draft keeps the revision of each part of the route it was made from, which the game checks part by part
	const draftEntry = r => draft.routes[r.id] || (draft.routes[r.id] = { base: r.revision, parts: Object.assign({}, r.parts || {}) });
	/** The parts of a route the editor's changes send: the timetable, the stops and the route's settings sent with them. */
	const EDITOR_PARTS = ["timetable_services", "platform_ids", "is_light_rail_route"];
	/** Whether the game changed a part of the route the draft would send since the draft was made. */
	const draftOutOfDate = (entry, r) => entry.parts ? EDITOR_PARTS.some(part => ((r.parts || {})[part] || 0) !== (entry.parts[part] || 0)) : r.revision !== entry.base;
	/** A route's entry goes once nothing is left in it but where it came from. */
	function tidyDraft(id) {
		const entry = draft.routes[id];
		if (entry && Object.keys(entry).every(k => k === "base" || k === "force" || k === "error" || k === "conflict")) delete draft.routes[id];
	}
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

	/** The platforms a route turns back at (站前折返) or would rather start from (种别优先), as the draft has them. */
	const turnOf = (r, kind) => draftOf(r) && draftOf(r)[kind] ? draftOf(r)[kind] : (r[kind] || []);

	/** Adds a platform to the end of one of these lists, or takes it out, in the draft. */
	function toggleTurnPlatform(r, kind, platformId) {
		const entry = draftEntry(r);
		const list = turnOf(r, kind).filter(id => id !== platformId);
		if (list.length === turnOf(r, kind).length) list.push(platformId);
		const original = r[kind] || [];
		if (list.length === original.length && list.every((id, k) => id === original[k])) delete entry[kind];
		else entry[kind] = list;
		if (!entry.conflict) delete entry.error;
		tidyDraft(r.id);
		saveDraft();
	}

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

	/**
	 * How a rail of a route's track diagram is drawn: a rail a connecting line takes from other routes, one released, or
	 * an ordinary one. A connecting line's own diagram shows its draft.
	 */
	function railMarker(result, r, readOnly) {
		const keys = result.railKeys || [], canEdit = data.canEdit && !readOnly;
		const released = r && r.connectingLine ? new Set(releasedOf(r).map(rail => railKey(rail[0], rail[1]))) : null;
		return index => {
			const entry = keys[index];
			if (!entry) return { cls: "", attrs: "", title: "" };
			const [a, b, status] = entry, key = railKey(a, b);
			const drafted = draft.boundaries && key in draft.boundaries;
			const boundary = drafted ? draft.boundaries[key] : status === 3;
			// Right-click any rail to change its isolation; a connecting line's own rails also switch with a click
			// Marked without the right to edit too, so a right-click there says so rather than showing nothing
			const attrs = ` data-rail="${a},${b}" data-rail-status="${status}"`;
			if (boundary) return { cls: " boundary" + (canEdit ? " clickable" : ""), attrs, title: `<title>${escapeHtml(tr("寻路边界：只划分先在哪里找路，区域内找不到会越过，不挡车") + (drafted ? tr("（草稿）") : ""))}</title>` };
			if (status !== 1 && status !== 2) return { cls: drafted ? " unbounded" : "", attrs, title: drafted ? `<title>${escapeHtml(tr("取消寻路边界（草稿）"))}</title>` : "" };
			const isReleased = released ? released.has(key) : status === 2;
			const editable = released && canEdit;
			const title = isReleased ? tr("联络线的这段已放行，别的线路可以走") : tr("联络线独占，别的线路不走");
			return {
				cls: (isReleased ? " released" : " connecting") + (canEdit ? " clickable" : ""),
				attrs: attrs + (editable ? ` data-release="${a},${b}"` : ""),
				title: `<title>${escapeHtml(title + (editable ? tr("；点一下切换") : ""))}</title>`
			};
		};
	}

	/** Sets or clears a pathfinding boundary on a rail in the draft, sent with the in-game boundary packet when applied. */
	function setBoundary(a, b, boundary, wasBoundary) {
		const key = railKey(a, b);
		if (boundary === wasBoundary) delete draft.boundaries[key];
		else draft.boundaries[key] = boundary;
		delete boundaryErrors[key];
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

	function replanDialog(depot) {
		const hint = replanText(depot.shortfall);
		openDialog(tr("重新排运用"), `<p>${tr("按现在的时刻表、存车线和设置，把“{0}”所在规划单元（同一运营线路上自动排运用的车厂）的运用全部重新排一遍。", escapeHtml(shortName(depot.name)))}</p>
			${hint ? `<p>${escapeHtml(hint)}</p>` : ""}
			<p class="hint">${tr("现有运用接得上时游戏不会自己重排，免得运用跟着用时的小变化换来换去。重排后哪份运用跑哪几班、车回哪条存车线都可能变。结果在服务端日志和这里的运用里。")}</p>`, tr("重新排"), () => {
			sendJson("api/replan-duties", "POST", { depot: depot.id }).then(result => {
				dutyNotice = result.ok ? tr("已请求重新排“{0}”的运用。", shortName(depot.name)) : tr("没有权限，或者游戏里没有这个车厂。");
				renderBanner();
			}).catch(() => {
				dutyNotice = tr("发送失败，游戏可能已经关闭");
				renderBanner();
			});
		});
	}

	/**
	 * The duty groups of a depot's duties as the web works them out (duty groups, step 4): its duties of each vehicle on
	 * each set of weekdays, by "vehicle|days".
	 */
	function dutyGroupsOf(depot) {
		const duties = dutiesOf(depot);
		const groups = new Map();
		if (duties.mode !== "diagram") return groups;
		const vehicleOf = new Map((depot.sidings || []).map(siding => [String(siding.id), siding.vehicle || ""]));
		Object.entries(duties.sidings).forEach(([sidingId, slots]) => slots.forEach(slot => slot.forEach(day => {
			const key = (vehicleOf.get(sidingId) || "") + "|" + day.days;
			groups.set(key, (groups.get(key) || 0) + 1);
		})));
		return groups;
	}

	/**
	 * The duty groups of a depot's planning unit as the server planned them, each to be planned again on its own, and the
	 * duty groups of the other depots, each to be put into this depot's draft as a template (duty groups, step 4).
	 */
	function dutyGroupsDialog(depot) {
		const vehicleName = vehicle => String(vehicle || "").replace(/^mtr_custom_train_/, "") || tr("（无车型）");
		const planned = (depot.shortfall && depot.shortfall.groups) || [];
		const plannedRows = planned.map((group, index) => `<tr><td>${escapeHtml(group.label)}</td><td>${escapeHtml(daysText(group.days))}</td>
			<td>${group.needed < 0 ? tr("最多同时 {0} 列，需要几列算不出", group.trains) : tr("最多同时 {0} 列，跑满需要 {1} 列", group.trains, group.needed)}${group.uncovered ? tr("，每天 {0} 班没有车", group.uncovered) : ""}</td>
			<td>${group.kept ? tr("沿用") : tr("这次排的")}</td>
			<td>${data.canEdit && dutiesOf(depot).auto ? `<button type="button" data-replan-group="${index}">${tr("只重排这组")}</button>` : ""}</td></tr>`).join("");
		const others = [];
		data.depots.filter(other => other.id !== depot.id).forEach(other => {
			[...dutyGroupsOf(other).entries()].sort().forEach(([key, count]) => {
				const [vehicle, days] = key.split("|");
				if ((depot.sidings || []).some(siding => (siding.vehicle || "") === vehicle)) others.push({ other, vehicle, days: Number(days), count });
			});
		});
		const otherRows = others.map((entry, index) => `<tr><td>${escapeHtml(shortName(entry.other.name))}</td><td>${escapeHtml(vehicleName(entry.vehicle))}</td><td>${escapeHtml(daysText(entry.days))}</td><td>${tr("{0} 份运用", entry.count)}</td>
			<td>${data.canEdit ? `<button type="button" data-template="${index}">${tr("套用到本车厂")}</button>` : ""}</td></tr>`).join("");
		openDialog(tr("运用组：{0}", escapeHtml(shortName(depot.name))), `
			<p class="hint">${tr("运用按“车型 × 时刻表的星期”分组排：每张时刻表的日子各排一组，只改一张表时只重排它那一组，其他组沿用。")}</p>
			<h3>${tr("本规划单元的运用组")}</h3>
			${plannedRows ? `<table class="duty-groups"><tbody>${plannedRows}</tbody></table>
			<p class="hint">${tr("“只重排这组”：下次寻路时只把这一组重新排一遍，其他组接得上就照旧；排在它后面、同一天跑同样线路的组会跟着重排。")}</p>` : `<p class="hint">${tr("还没有按组排过（自动运用重排一次后会列出来）")}</p>`}
			<h3>${tr("套用其他车厂的运用组")}</h3>
			${otherRows ? `<table class="duty-groups"><tbody>${otherRows}</tbody></table>
			<p class="hint">${tr("把那个车厂这组运用（同车型、同样的星期）按存车线顺序放进本车厂的运用草稿，本车厂改为按运用图；那几天本车厂原来的运用换掉。原车厂不变：两边排了同样的班次时，要改掉一边再应用。")}</p>` : `<p class="hint">${tr("其他车厂没有同车型的运用")}</p>`}`, tr("关闭"), () => null);
		dialog.querySelectorAll("[data-replan-group]").forEach(button => button.addEventListener("click", () => {
			const group = planned[Number(button.dataset.replanGroup)];
			dialog.close();
			sendJson("api/replan-duties", "POST", { depot: depot.id, group: group.key }).then(result => {
				dutyNotice = result.ok ? tr("已请求只重排“{0}”{1} {2} 这一组运用。", shortName(depot.name), group.label, daysText(group.days)) : tr("没有权限，或者游戏里没有这个车厂。");
				renderBanner();
			}).catch(() => {
				dutyNotice = tr("发送失败，游戏可能已经关闭");
				renderBanner();
			});
		}));
		dialog.querySelectorAll("[data-template]").forEach(button => button.addEventListener("click", () => {
			const entry = others[Number(button.dataset.template)];
			dialog.close();
			applyDutyGroup(depot, entry.other, entry.vehicle, entry.days);
		}));
	}

	/**
	 * Puts another depot's duties of one vehicle on some weekdays into this depot's draft (duty groups, step 4): its sidings
	 * of the vehicle in order take the other's in order, the last taking the rest; this depot's duties on those days are
	 * replaced, and it runs by its duties.
	 */
	function applyDutyGroup(depot, source, vehicle, mask) {
		const targets = (depot.sidings || []).filter(siding => (siding.vehicle || "") === vehicle);
		if (!targets.length) return;
		const from = dutiesOf(source);
		const sourceSidings = (source.sidings || []).filter(siding => (siding.vehicle || "") === vehicle);
		editDuties(depot, duties => {
			duties.mode = "diagram";
			targets.forEach(target => (duties.sidings[target.id] || []).forEach(slot => {
				for (let i = slot.length - 1; i >= 0; i--) {
					slot[i].days &= ~mask;
					if (!slot[i].days) slot.splice(i, 1);
				}
			}));
			let count = 0;
			sourceSidings.forEach((sourceSiding, k) => {
				const target = targets[Math.min(k, targets.length - 1)];
				const slots = duties.sidings[target.id] || (duties.sidings[target.id] = []);
				(from.sidings[sourceSiding.id] || []).forEach(slot => slot.filter(day => day.days === mask).forEach(day => {
					let index = slots.findIndex(each => !each.some(other => other.days & day.days));
					if (index < 0) {
						index = slots.length;
						slots.push([]);
					}
					const id = dutyIdOf(slots[index]) || newDutyId();
					slots[index].push({ days: day.days, stay: day.stay, next: -1, duty: id, start: slots[index].length ? startOf(slots[index]) : "prefer", nextDuty: "0", returnSiding: "0", fromOvernight: false, segments: JSON.parse(JSON.stringify(day.segments)) });
					count++;
				}));
			});
			return tr("已把“{0}”{1} {2} 的 {3} 份运用放进“{4}”的运用草稿（按运用图），检查后再应用；原车厂的运用没变。", shortName(source.name), vehicle.replace(/^mtr_custom_train_/, ""), daysText(mask), count, shortName(depot.name));
		});
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

	/**
	 * The speed limits of routes, each no higher than the fastest rail along it: trains run its rails between its stops no
	 * faster than either (line speed limits).
	 */
	function speedLimitDialog(routes) {
		const rows = routes.map(r => {
			const fastest = r.fastestRail || 0;
			const limit = speedLimitOf(r);
			return `<label data-speed-route="${r.id}">${escapeHtml(routeTitle(r))} <input type="number" min="0" max="${fastest || 300}" step="1" name="speed" value="${limit > 0 ? limit : ""}" placeholder="${tr("不限")}"> km/h
				<span class="hint">${fastest ? tr("轨道限速最高 {0} km/h", fastest) : tr("还不知道沿途轨道的限速")}</span></label>`;
		}).join("");
		openDialog(tr("线路限速"), `<p class="hint">${tr("列车在这条线路两站之间的轨道上不超过这个速度，也不超过轨道自己的限速；出入库和折返不受影响。空着为不限。")}</p>${rows}`, tr("放进草稿"), form => {
			const values = [];
			for (const row of form.querySelectorAll("[data-speed-route]")) {
				const r = data.routeById.get(row.dataset.speedRoute);
				const text = row.querySelector("input[name=speed]").value.trim();
				const value = text === "" ? 0 : Math.round(Number(text));
				if (!(value >= 0) || value > 300) return tr("限速要在 0 到 300 km/h 之间");
				if (r.fastestRail && value > r.fastestRail) return tr("{0}：不能高于轨道限速 {1} km/h", routeTitle(r), r.fastestRail);
				values.push([r, value]);
			}
			values.forEach(([r, value]) => setSpeedLimit(r, value));
			saveDraft();
			renderAll();
			return null;
		});
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

	async function applyDraft() {
		if (applying || !draftCount()) return;
		const { operations, owners } = draftOperations();
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
					if (id.depotOf) {
						if (draft.depots[id.depotOf]) draft.depots[id.depotOf].error = error.message;
					} else if (id.dutiesOf) {
						if (draft.duties[id.dutiesOf]) draft.duties[id.dutiesOf].error = error.message;
					} else if (id.boundaryOf) {
						boundaryErrors[id.boundaryOf] = error.message;
					} else if (id.platformStationOf) {
						boundaryErrors["station:" + id.platformStationOf] = error.message;
					} else {
						// Changed in the game since the draft was made: said as that, not as the server's code (2026-11-09)
						id.error = error.message === "CONFLICT" ? tr("游戏里的这一项在草稿之后改过（可能是自动排了新的运用），草稿没有写入；看一下游戏里现在的，不需要就放弃这项草稿") : error.message;
					}
					return;
				}
				const entry = draft.routes[id];
				if (!entry) return;
				entry.conflict = error.message === "CONFLICT";
				entry.error = entry.conflict ? tr("游戏里的这条线路在草稿之后改过") : error.message;
			});
			owners.forEach(id => { if (!failed.has(id) && typeof id === "string") delete draft.routes[id]; });
			draft.created = draft.created.filter(made => failed.has(made));
			owners.filter(owner => owner && owner.dutiesOf && !failed.has(owner)).forEach(owner => delete draft.duties[owner.dutiesOf]);
			owners.filter(owner => owner && owner.depotOf && !failed.has(owner)).forEach(owner => delete draft.depots[owner.depotOf]);
			owners.filter(owner => owner && owner.depotRulesOf && !failed.has(owner)).forEach(owner => delete draft.depotRules[owner.depotRulesOf]);
			owners.filter(owner => owner && owner.platformStationOf && !failed.has(owner)).forEach(owner => delete draft.platformStations[owner.platformStationOf]);
			owners.filter(owner => owner && owner.platformSpeedOf && !failed.has(owner)).forEach(owner => delete draft.platformSpeeds[owner.platformSpeedOf]);
			owners.filter(owner => owner && owner.platformStopOf && !failed.has(owner)).forEach(owner => delete draft.platformStops[owner.platformStopOf]);
			if (owners.some(owner => owner && owner.networkSettingsOf && !failed.has(owner))) draft.networkSettings = null;
			owners.filter(owner => owner && owner.contingenciesOf && !failed.has(owner)).forEach(owner => delete draft.contingencies[owner.contingenciesOf]);
			owners.filter(owner => owner && owner.boundaryOf && !failed.has(owner)).forEach(owner => {
				delete draft.boundaries[owner.boundaryOf];
				delete boundaryErrors[owner.boundaryOf];
			});
			applyResult = { ok: result.ok, general: (result.errors || []).filter(error => error.index < 0).map(error => error.message), applied: owners.filter(id => !failed.has(id)).length };
		} catch (e) {
			applyResult = { ok: false, general: [e.message === "401" ? tr("页面的链接已经失效") : tr("发送失败，游戏可能已经关闭")], applied: 0 };
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
			if (r) {
				entry.base = r.revision;
				entry.parts = Object.assign({}, r.parts || {});
			}
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
		tag.textContent = applying ? tr("正在应用…") : count ? `${tr("草稿 {0} 项", count)}` : tr("没有草稿");
		tag.classList.toggle("has-draft", count > 0);
		tag.title = count ? tr("点这里看草稿改了哪些地方") : "";
		// Seen from the media library too, on the timetable's workspace tab
		$("ws-draft-dot").hidden = !count;
		// Each route is applied as a whole; one changed in the game meanwhile is kept in the draft and left out until settled
		const conflicts = Object.values(draft.routes).filter(entry => entry.conflict).length;
		$("btn-apply").disabled = applying || !count || count <= conflicts || !(data && data.canEdit);
		$("btn-apply").title = conflicts ? tr("有 {0} 条线路在游戏里被改过，应用时先不发，处理后再应用", conflicts) : "";
		const selected = data && state.route && data.routeById.get(state.route);
		const button = $("btn-delete");
		button.textContent = selected && isDeleted(selected) ? tr("取消删除") : tr("删除种别");
		button.disabled = !selected || !data.canEdit;
		button.title = selected ? (isDeleted(selected) ? tr("不删除") : tr("删除")) + "“" + routeTitle(selected) + "”" : tr("先选中一个种别");
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

	/**
	 * Where a route comes back to a station it has stopped at: going back the way it came (retrace), or coming round to it
	 * from elsewhere, like the end of a loop or of the loop of a Q, on the platform it stopped at before or on another.
	 *
	 * @return for each stop, null the first time at its station, or {earlier, retrace, samePlatform}
	 */
	function revisits(stations, platforms) {
		const first = new Map();
		return stations.map((id, i) => {
			if (!first.has(id)) {
				first.set(id, i);
				return null;
			}
			const earlier = first.get(id);
			return { earlier, retrace: i > 0 && (stations[earlier - 1] === stations[i - 1] || stations[earlier + 1] === stations[i - 1]), samePlatform: platforms[earlier] === platforms[i] };
		});
	}

	/**
	 * The stations of a line down the side of its diagram, in sections. The longest route goes down the first section. A
	 * run of stations that no section has side by side yet carries on a section it leaves from the end of, and otherwise
	 * starts a section of its own below, with the station it leaves from repeated at the top:
	 * - a branch (Y) gets a section starting at the station it branches off at;
	 * - another way between two stations further along the line gets a section that repeats both of them;
	 * - a loop repeats its first station at the end;
	 * - a Q coming back to the platform it branched off at is drawn as a loop, with the stretch leading to it below;
	 * - a Q coming back to that station on another platform goes on down the same section, back along the stretch.
	 * A route running the other way meets the same rows from the bottom up. Each stop of a route has the row it arrives on
	 * (rows) and the row it leaves from (leaveRows), which are two rows of the same station where the route goes from one
	 * section to another.
	 */
	function buildStations(line) {
		const stationOf = stop => stop.station || "?" + stop.name;
		const sections = [];
		const rowsOf = id => sections.flatMap((section, k) => section.flatMap((row, j) => row.id === id ? [[k, j]] : []));
		const newRow = (id, name, base) => {
			const row = { id, name };
			row.base = base || row;
			return row;
		};
		[...line.routes].sort((a, b) => b.stops.length - a.stops.length || a.id.localeCompare(b.id)).forEach(r => {
			let at = null, unfolded = false;
			const visited = new Map(), shape = revisits(r.stops.map(stationOf), r.stops.map(stop => stop.platformId));
			r.stops.forEach((stop, i) => {
				const id = stationOf(stop), again = shape[i];
				// Back at the station a Q branches off, on another platform: the rest of the route goes on down in rows of its own
				if (again && !again.retrace && !again.samePlatform) unfolded = true;
				let row = newRow(id, stop.name);
				if (again && !again.retrace && again.samePlatform) {
					row = newRow(id, stop.name, visited.get(id).base);
				} else if (!unfolded && rowsOf(id).length) {
					// Rejoining another route's station
					const [k0, j0] = rowsOf(id)[0];
					row = newRow(id, stop.name, sections[k0][j0].base);
				}
				if (!at) {
					at = rowsOf(id)[0];
					if (!at) {
						sections.push([row]);
						at = [sections.length - 1, 0];
					}
					visited.set(id, sections[at[0]][at[1]]);
					return;
				}
				const [k, j] = at, section = sections[k], from = section[j];
				if (from.id === id) return;
				// Already side by side somewhere, preferably next to the row the route is on. Unfolded, only next to that row.
				const starts = unfolded ? [at] : rowsOf(from.id).sort((a, b) => (b[0] === k && b[1] === j) - (a[0] === k && a[1] === j));
				const side = starts.flatMap(([k2, j2]) => [[k2, j2 + 1], [k2, j2 - 1]]).find(([k2, j2]) => sections[k2][j2] && sections[k2][j2].id === id);
				if (side) {
					at = side;
				} else if (j === section.length - 1) {
					section.push(row);
					at = [k, j + 1];
				} else if (j === 0) {
					section.unshift(row);
					at = [k, 0];
				} else {
					sections.splice(k + 1, 0, [newRow(from.id, from.name, from.base), row]);
					at = [k + 1, 1];
				}
				if (!visited.has(id)) visited.set(id, sections[at[0]][at[1]]);
			});
		});

		// A Q coming back to the platform it branched off at: the loop on top, as a loop line is drawn, and the stretch
		// leading to it in a section of its own below, starting from the station again
		for (let k = 0; k < sections.length; k++) {
			const section = sections[k], last = section[section.length - 1], j = section.indexOf(last.base);
			if (last.base === last || j <= 0) continue;
			sections.splice(k, 1, section.slice(j), [newRow(last.id, last.name, last.base), ...section.slice(0, j).reverse()]);
			k++;
		}

		// A section with one end free and the other at the end of another section carries that one on. One left hanging
		// off the middle of another goes down from the station it hangs off. A section coming back to itself, like the loop
		// of a Q, stays as it is, and so does a loop another section hangs off.
		const elsewhere = (k, id) => sections.some((section, k2) => k2 !== k && section.some(row => row.id === id));
		for (let merged = true; merged;) {
			merged = false;
			for (let k = 0; k < sections.length && !merged; k++) {
				const section = sections[k], first = section[0], last = section[section.length - 1];
				if (section.length < 2 || elsewhere(k, first.id) === elsewhere(k, last.id)) continue;
				// Turned so the end shared with another section is at the top
				const ordered = elsewhere(k, first.id) ? section : section.slice().reverse();
				const t = sections.findIndex((other, k2) => k2 !== k && other[0].id !== other[other.length - 1].id && (other[0].id === ordered[0].id || other[other.length - 1].id === ordered[0].id));
				if (t >= 0) {
					const other = sections[t], atEnd = other[other.length - 1].id === ordered[0].id;
					// The station they share keeps the row that isn't a repeat
					const end = atEnd ? other[other.length - 1] : other[0], shared = end.base === end ? end : ordered[0];
					sections[t] = atEnd ? other.slice(0, -1).concat(shared, ordered.slice(1)) : ordered.slice(1).reverse().concat(shared, other.slice(1));
					sections.splice(k, 1);
					merged = true;
				} else if (k > 0) {
					sections[k] = ordered;
				}
			}
		}

		// A station repeated where a section starts or a loop closes is greyed; one a Q comes back to on another platform,
		// and the stations it goes back along, are rows of their own
		const rows = [], made = sections.flat();
		sections.forEach((section, k) => section.forEach((row, j) => rows.push({ id: row.id, name: row.name, section: k, sectionStart: k > 0 && j === 0, repeat: row.base !== row, gap: 0 })));
		rows.forEach((row, i) => row.group = made.indexOf(made[i].base));
		const rowsById = new Map();
		rows.forEach((row, i) => rowsById.set(row.id, (rowsById.get(row.id) || []).concat(i)));
		const adjacent = (a, b) => a === b || Math.abs(a - b) === 1 && rows[a].section === rows[b].section;
		line.routes.forEach(r => {
			// The rows with the fewest changes from one section to another, by the cost of leaving each stop from each row
			const options = r.stops.map(stop => rowsById.get(stationOf(stop)));
			let cost = new Map(options.length ? options[0].map(d => [d, 0]) : []);
			const back = [];
			for (let i = 1; i < options.length; i++) {
				const next = new Map(), choice = new Map();
				options[i].forEach(d => options[i].forEach(a => {
					let best = Infinity, before = -1;
					cost.forEach((c, p) => {
						const total = c + (adjacent(p, a) ? 0 : 100);
						if (total < best) {
							best = total;
							before = p;
						}
					});
					// Over to the station's row in another section, or to a row of its own, which is a way the route doesn't go
					const total = best + (a === d ? 0 : rows[a].group === rows[d].group ? 1 : 150);
					if (!next.has(d) || total < next.get(d)) {
						next.set(d, total);
						choice.set(d, [a, before]);
					}
				}));
				back.push(choice);
				cost = next;
			}
			r.rows = [];
			r.leaveRows = [];
			if (!options.length) return;
			let d = [...cost.entries()].reduce((best, entry) => entry[1] < best[1] ? entry : best)[0];
			for (let i = options.length - 1; i > 0; i--) {
				const [a, before] = back[i - 1].get(d);
				r.leaveRows[i] = d;
				r.rows[i] = a;
				d = before;
			}
			r.rows[0] = r.leaveRows[0] = d;
		});

		// The space above each row follows the fastest running time to it, half and half with even spacing, so close
		// stations still have room. A section starts a little further down.
		const gaps = rows.map(() => []);
		line.routes.forEach(r => {
			const plans = r.tables.length ? r.tables[0].plans : [];
			for (let i = 1; i < r.rows.length; i++) {
				const a = r.leaveRows[i - 1], b = r.rows[i];
				if (a !== b && adjacent(a, b) && plans[i] && r.hasTimes) gaps[Math.max(a, b)].push(Math.max(0, plans[i][0] - plans[i - 1][1]));
			}
		});
		const typical = gaps.flat().length ? gaps.flat().reduce((a, b) => a + b, 0) / gaps.flat().length : 1;
		rows.forEach((row, i) => row.gap = i === 0 ? 0 : row.sectionStart ? SECTION_GAP : 0.5 + 0.5 * (gaps[i].length ? Math.min(...gaps[i]) / (typical || 1) : 1));
		line.rows = rows;
	}

	// ---------- Times ----------
	// The page doesn't work out times itself: the game does, by the same rules trains run by (StopRules), and sends each
	// stop as [arrival, departure, overtake, passes, tooFast], the times in milliseconds from the first stop
	const toPlans = times => times.map(t => ({ arrival: t[0], departure: t[1], overtake: t[2] === 1, passes: t[3] === 1, tooFast: t[4] === 1 }));
	const gameSource = r => r.gameSource || (r.gameSource = r.tables.map(table => ({ times: table.times, exceptions: new Map((table.exceptions || []).map(e => [e.service, e.times])) })));
	// The times of each route's draft as the game worked them out, with the draft they are for
	const draftTimes = new Map();
	const requestedTimes = new Map();
	let timesTimer = 0;

	/** What a route's times are taken from: the game's for its own timetable, or the game's answer for the draft. */
	function timesSource(r) {
		const draftTimetable = draftOf(r) && draftOf(r).timetable;
		if (!draftTimetable) return gameSource(r);
		const key = JSON.stringify([draftTimetable, r.hasTimes ? r.fastest : null]);
		const cached = draftTimes.get(r.id);
		if ((!cached || cached.key !== key) && requestedTimes.get(r.id) !== key) {
			requestedTimes.set(r.id, key);
			clearTimeout(timesTimer);
			timesTimer = setTimeout(sendTimes, 150);
		}
		// Until the game answers, the times it last gave are shown
		return cached ? cached.tables : gameSource(r);
	}

	/** Sends the drafts waiting for times to the game, and shows them once it answers. */
	async function sendTimes() {
		const sent = [...requestedTimes.entries()].filter(([id, key]) => !draftTimes.has(id) || draftTimes.get(id).key !== key);
		const routes = sent.map(([id]) => data.routeById.get(id)).filter(r => r && draftOf(r) && draftOf(r).timetable);
		if (!routes.length) return;
		const keys = new Map(sent);
		try {
			const result = await sendJson("api/times", "POST", { routes: routes.map(r => ({ id: r.id, timetable: draftOf(r).timetable })) });
			result.routes.forEach(route => draftTimes.set(route.id, {
				key: keys.get(route.id),
				tables: route.tables.map(table => ({ times: table.times, exceptions: new Map(table.exceptions.map(e => [e.service, e.times])) })),
			}));
		} catch (e) {
			// Asked again with the next change
			sent.forEach(([id]) => requestedTimes.delete(id));
			return;
		}
		buildServices();
		if (state.selected && !findService(state.selected)) state.selected = null;
		renderAll();
	}

	/**
	 * The planned times at each stop of a table's services, or of one service with its own exceptions. A table or service
	 * the game hasn't answered for yet takes the table's times, or none.
	 */
	function plansFor(r, source, tableIndex, service) {
		const table = source[tableIndex];
		const times = table ? service !== null && table.exceptions.get(service) || table.times : null;
		if (times && times.length === r.stops.length) return toPlans(times);
		return r.stops.map(() => ({ arrival: 0, departure: 0, overtake: false, passes: false, tooFast: false }));
	}

	/** The table a route runs on a day for each of its services, which is the first of its tables that has it that day. */
	function buildServices() {
		const weekday = state.weekday;
		data.routes.forEach(r => {
			const timetable = timetableOf(r);
			const source = timesSource(r);
			const services = new Map();
			timetable.tables.forEach((table, tableIndex) => {
				if ((table.weekdays >> weekday & 1) === 0) return;
				const tablePlans = plansFor(r, source, tableIndex, null);
				const exceptions = new Map(table.exceptions.map(exception => [exception.service, new Map(exception.stops.map(stop => [stop.stop, stop]))]));
				table.services.forEach(service => {
					if (services.has(service)) return;
					const stopExceptions = exceptions.get(service) || null;
					services.set(service, {
						id: r.id + "@" + service, route: r, service, table, tableIndex,
						link: table.links.find(l => l.service === service),
						returns: table.returns.includes(service),
						// Kept in the table, but not running: no train, no conflicts, nothing planned
						suspended: (table.suspended || []).includes(service),
						// Set on the running diagram: joined part of the way by a train from a depot, or left part of the way back to one
						deployOrder: (table.orders || []).find(order => order.service === service && order.kind === "deploy") || null,
						returnOrder: (table.orders || []).find(order => order.service === service && order.kind === "return") || null,
						// The time in the operating day, so the last trains after midnight come after the first ones
						start: mod(service - data.dayStartMillis, DAY),
						plans: stopExceptions ? plansFor(r, source, tableIndex, service) : tablePlans,
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
	const daysText = mask => mask ? WEEKDAY_SHORT.filter((_, i) => mask >> i & 1).join(trc("weekday separator", "")) : tr("不运行");
	const tableName = (table, index) => table.name || tr("表 ") + (index + 1);

	/** Sorted the same way however it was made, so a draft changed back to the game's matches it. */
	function normalize(timetable) {
		timetable.tables.forEach(table => {
			table.services.sort((a, b) => a - b);
			table.returns.sort((a, b) => a - b);
			table.suspended = (table.suspended || []).slice().sort((a, b) => a - b);
			table.orders = table.orders || [];
			table.orders.sort((a, b) => a.service - b.service || (a.kind < b.kind ? -1 : a.kind > b.kind ? 1 : 0));
			table.links.sort((a, b) => a.service - b.service);
			table.exceptions.sort((a, b) => a.service - b.service);
			table.exceptions.forEach(exception => exception.stops.sort((a, b) => a.stop - b.stop));
		});
		return timetable;
	}

	/** A route's timetable from the game, in the form the editor sends it back. */
	function fromGame(r) {
		return normalize({
			enabled: r.timetable, entryPlatform: r.entryPlatform, pass: r.stops.map(stop => stop.pass), allowLonger: r.stops.map(stop => Boolean(stop.allowLonger)),
			tables: r.tables.map(table => ({
				name: table.name, weekdays: table.weekdays, marginPercent: table.marginPercent,
				minAcceleration: table.minAcceleration || 0, minDeceleration: table.minDeceleration || 0, minMaxSpeed: table.minMaxSpeed || 0,
				services: [...table.services], returns: [...table.returns], suspended: [...(table.suspended || [])],
				orders: (table.orders || []).map(order => ({ ...order })),
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
		if (!entry.conflict) delete entry.error;
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

	/**
	 * Makes the services the same as one service in all it changes at its stops, stop by stop in order (2026-11-09): its
	 * running and stop times, passing and waiting kept as its own are copied to each of them, and what they had changed
	 * themselves is replaced, so each runs as that one does, from its own start.
	 *
	 * @return what was done, to show
	 */
	function syncExceptions(table, from, services) {
		const source = table.exceptions.find(e => e.service === from);
		const targets = new Set(services.filter(service => service !== from));
		table.exceptions = table.exceptions.filter(e => !targets.has(e.service));
		if (source && source.stops.length) targets.forEach(service => table.exceptions.push({ service, stops: source.stops.map(stop => ({ ...stop })) }));
		return tr("{0} 班已经和这一班的各站时刻改动一样", targets.size);
	}

	/** Moves a service to another time, with what belongs to it. @return why it can't, or nothing */
	function retimeService(table, oldService, newService) {
		if (oldService === newService) return;
		if (table.services.includes(newService)) return tr("这张表已经有这个时刻的班次");
		table.services[table.services.indexOf(oldService)] = newService;
		table.links.forEach(link => { if (link.service === oldService) link.service = newService; });
		table.returns = table.returns.map(service => service === oldService ? newService : service);
		table.suspended = (table.suspended || []).map(service => service === oldService ? newService : service);
		(table.orders || []).forEach(order => { if (order.service === oldService) order.service = newService; });
		table.exceptions.forEach(exception => { if (exception.service === oldService) exception.service = newService; });
	}

	/** Sets services of a table not to run, or to run again; their times and everything else stay. */
	function setSuspended(table, services, suspended) {
		const set = new Set(table.suspended || []);
		services.forEach(service => suspended ? set.add(service) : set.delete(service));
		table.suspended = [...set].filter(service => table.services.includes(service)).sort((a, b) => a - b);
		return suspended ? tr("{0} 班停运，留在表里，右键可以恢复", services.length) : tr("{0} 班恢复运行，和别的班次冲突的话同步后会提示", services.length);
	}

	function removeService(table, service) {
		table.services = table.services.filter(s => s !== service);
		table.links = table.links.filter(link => link.service !== service);
		table.returns = table.returns.filter(s => s !== service);
		table.suspended = (table.suspended || []).filter(s => s !== service);
		table.orders = (table.orders || []).filter(order => order.service !== service);
		table.exceptions = table.exceptions.filter(exception => exception.service !== service);
	}

	/** The first and last stops a service carries passengers at, which orders set on the running diagram can narrow. */
	const servedStops = s => [s.deployOrder ? s.deployOrder.stop : 0, s.returnOrder ? Math.min(s.returnOrder.stop, s.route.stops.length - 1) : s.route.stops.length - 1];
	const orderDepotName = order => {
		const depot = order && order.depot !== "0" ? depotById(order.depot) : null;
		return depot ? shortName(depot.name) : tr("自动选的车厂");
	};

	/** Sets or clears where a service is joined by a train from a depot, or left for one. @param depot the depot's ID, "0" for any, or null to clear */
	function setServiceOrder(table, service, kind, stop, depot) {
		table.orders = (table.orders || []).filter(order => !(order.service === service && order.kind === kind));
		if (depot !== null) table.orders.push({ service, kind, stop, depot: String(depot) });
	}

	/** Picks the depot for a service's order: the depots running the line's routes first, then the others of its transport mode. */
	/** The depots a service can be set to go back to (or come out of) at a stop, as menu items, the one set ticked. */
	function orderDepotItems(s, kind, stop) {
		const lineRoutes = new Set((currentLine() ? currentLine().routes : [s.route]).map(r => r.id));
		const depots = data.depots.filter(depot => !depot.transportMode || !s.route.transportMode || depot.transportMode === s.route.transportMode);
		const near = depots.filter(depot => depot.routes.some(id => lineRoutes.has(id))), far = depots.filter(depot => !near.includes(depot));
		const current = kind === "deploy" ? s.deployOrder : s.returnOrder;
		const set = depotId => () => editTimetable(s.route, timetable => setServiceOrder(timetable.tables[s.tableIndex], s.service, kind, stop, depotId));
		const mark = depotId => current && current.stop === stop && current.depot === depotId ? "✓ " : "";
		const items = [[mark("0") + tr("自动选的车厂"), set("0")]];
		near.forEach(depot => items.push([mark(depot.id) + shortName(depot.name), set(depot.id)]));
		if (far.length) items.push([tr("其他车厂"), far.map(depot => [mark(depot.id) + shortName(depot.name), set(depot.id)])]);
		return items;
	}

	function serviceOrderDialog(s, kind, stop) {
		const lineRoutes = new Set((currentLine() ? currentLine().routes : [s.route]).map(r => r.id));
		const depots = data.depots.filter(depot => !depot.transportMode || !s.route.transportMode || depot.transportMode === s.route.transportMode);
		const near = depots.filter(depot => depot.routes.some(id => lineRoutes.has(id))), far = depots.filter(depot => !near.includes(depot));
		const option = depot => `<option value="${depot.id}">${escapeHtml(shortName(depot.name))}</option>`;
		const current = kind === "deploy" ? s.deployOrder : s.returnOrder;
		const title = kind === "deploy" ? tr("{0} 班从{1}出车", clock(s.start), stopName(s.route, stop)) : tr("{0} 班在{1}转回送", clock(s.start), stopName(s.route, stop));
		const hint = kind === "deploy" ? tr("这一班从这一站起载客：车厂出一列车空跑到这里接上，之前的站不停。") : tr("这一班到这一站为止载客：之后的站不停，车从这里空跑回所选车厂。");
		openDialog(escapeHtml(title), `<p class="hint">${hint}</p><label>${tr("车厂")} <select name="depot"><option value="0">${tr("自动选的车厂")}</option>${near.length ? `<optgroup label="${tr("跑这条线路的")}">${near.map(option).join("")}</optgroup>` : ""}${far.length ? `<optgroup label="${tr("其他车厂")}">${far.map(option).join("")}</optgroup>` : ""}</select></label>`, tr("确定"), form => {
			editTimetable(s.route, timetable => setServiceOrder(timetable.tables[s.tableIndex], s.service, kind, stop, form.depot.value));
		});
		if (current) dialog.querySelector("select").value = current.depot;
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

	const PATTERN_HINT = tr("填起始时刻、结束时刻和期望间隔（分钟，从起始时刻起每隔这么久一班，到结束时刻为止；这段时间里已有的班次按新间隔调整，挪动、补上或删掉多出的），或者只在起始时刻一栏写 07:10 + 10 * 00:03（07:10 起每 3 分钟一班，再加 10 班）");
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
	/**
	 * A menu of [label, action] items at a point. An item whose action is a list of items opens them beside it as a
	 * second-level menu, on hover or click; an item with no action is shown greyed out.
	 */
	function openMenu(x, y, items) {
		closeMenu();
		// A place with a menu always shows it, saying so when there is nothing to do there just now (right-click 2026-11-09)
		menu = buildMenu(items.length ? items : [[tr("这里没有可用的操作"), null]], 0);
		menuHost().appendChild(menu);
		menu.style.left = Math.min(x, innerWidth - menu.offsetWidth - 8) + "px";
		menu.style.top = Math.min(y, innerHeight - menu.offsetHeight - 8) + "px";
	}
	/**
	 * A menu at where a right-click was, or beside the element when the menu key or Shift+F10 asked for it.
	 */
	function openMenuAt(e, items) {
		if (e.clientX || e.clientY) {
			openMenu(e.clientX, e.clientY, items);
		} else {
			const box = e.target.getBoundingClientRect();
			openMenu(box.left + 8, box.bottom + 2, items);
		}
	}
	/**
	 * Where a menu goes: into the dialog open on top, which the page beneath can't show over or be clicked through, or the
	 * page (a rail's menu in the route's track diagram was hidden behind its dialog before)
	 */
	const menuHost = () => document.querySelector("dialog[open]") || document.body;
	let subMenu = null;
	function buildMenu(items, level) {
		const element = document.createElement("div");
		element.className = level ? "menu sub" : "menu";
		items.forEach(([label, action]) => {
			const button = document.createElement("button");
			button.type = "button";
			if (Array.isArray(action)) {
				button.textContent = label + " ▸";
				button.className = "has-sub";
				const open = () => {
					if (subMenu && subMenu.owner === button) return;
					if (subMenu) subMenu.remove();
					subMenu = buildMenu(action, level + 1);
					subMenu.owner = button;
					menuHost().appendChild(subMenu);
					const box = button.getBoundingClientRect();
					const left = box.right + subMenu.offsetWidth + 4 > innerWidth ? box.left - subMenu.offsetWidth - 2 : box.right + 2;
					subMenu.style.left = Math.max(8, left) + "px";
					subMenu.style.top = Math.max(8, Math.min(box.top - 4, innerHeight - subMenu.offsetHeight - 8)) + "px";
				};
				button.addEventListener("mouseenter", open);
				button.addEventListener("click", e => {
					e.stopPropagation();
					open();
				});
			} else {
				button.textContent = label;
				if (action) button.onclick = () => { closeMenu(); action(); };
				else button.disabled = true;
				if (!level) button.addEventListener("mouseenter", () => {
					if (subMenu) subMenu.remove();
					subMenu = null;
				});
			}
			element.appendChild(button);
		});
		return element;
	}
	function closeMenu() {
		if (subMenu) subMenu.remove();
		subMenu = null;
		if (menu) menu.remove();
		menu = null;
	}
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

	const dialog = $("dialog");
	/** A form whose OK button runs onOk, which returns an error to show and keep the form open, or nothing to close it. */
	function openDialog(title, body, okLabel, onOk, onInput) {
		closeMenu();
		dialog.innerHTML = `<form method="dialog"><h2>${title}</h2>${body}<p class="err" id="dialog-error"></p>
			<div class="actions"><button value="cancel" formnovalidate>${tr("取消")}</button><button class="primary" value="ok">${okLabel}</button></div></form>`;
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
		openDialog(isNew ? tr("新建时刻表") : tr("时刻表设置"), `
			<label>${tr("表名")} <input name="name" maxlength="32" value="${escapeHtml(table ? table.name : "")}" placeholder="${tr("休日")}"></label>
			<fieldset id="dialog-days"><legend>${tr("运行的星期")}</legend></fieldset>
			${isNew ? `<label>${tr("班次")} <select name="copy"><option value="-1">${tr("空表")}</option>${timetable.tables.map((t, i) => `<option value="${i}">${tr("复制“{0}”的班次", escapeHtml(tableName(t, i)))}</option>`).join("")}</select></label>
			<label><input type="checkbox" name="all" ${line && line.routes.length > 1 ? "checked" : "disabled"}> ${tr("{0}的全部线路一起建", escapeHtml(shortName(r.line)))}</label>` : ""}
			<p class="hint">${tr("一天只用一张表，勾选的星期从原来的表移到这张表。复制的班次连同只改这一班的例外一起复制。")}</p>`, isNew ? tr("新建") : tr("确定"), form => {
			const mask = [...form.querySelectorAll("#dialog-days input:checked")].reduce((m, input) => m | 1 << +input.value, 0);
			const name = form.name.value.trim();
			const routes = isNew && form.all && form.all.checked ? line.routes : [r];
			if (isNew && routes.some(route => timetableOf(route).tables.length >= MAX_TABLES)) return `${tr("一条线路最多 {0} 张表", MAX_TABLES)}`;
			routes.forEach(route => editTimetable(route, edited => {
				edited.tables.forEach((t, i) => { if (i !== tableIndex) t.weekdays &= ~mask; });
				if (isNew) {
					const copied = +form.copy.value >= 0 && edited.tables[+form.copy.value];
					edited.tables.push({
						name, weekdays: mask, marginPercent: copied ? copied.marginPercent : edited.tables[0].marginPercent,
						services: copied ? [...copied.services] : [], returns: copied ? [...copied.returns] : [], suspended: copied ? [...(copied.suspended || [])] : [], orders: copied ? (copied.orders || []).map(order => ({ ...order })) : [],
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
			form.querySelector("#dialog-days").innerHTML = `<legend>${tr("运行的星期")}</legend>` + WEEKDAY_SHORT.map((day, i) => {
				const owner = timetable.tables.findIndex(t => t.weekdays >> i & 1);
				const checked = first ? table && table.weekdays >> i & 1 : ticked.has(String(i));
				return `<label><input type="checkbox" value="${i}" ${checked ? "checked" : ""}>${tr("周{0}", day)}<span class="owner">${owner < 0 ? tr("无") : escapeHtml(tableName(timetable.tables[owner], owner))}</span></label>`;
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

	// ---------- What the draft changes ----------
	/** A time set at a stop, in seconds from the first stop, or -1 to follow the stop before. */
	const setTimeText = seconds => seconds < 0 ? tr("跟前一站") : `+${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
	const serviceClock = service => clock(mod(service - data.dayStartMillis, DAY), true);
	/** A few times, and how many more. */
	const someServices = services => {
		const shown = services.slice(0, 12).map(serviceClock).join(tr("、"));
		return services.length > 12 ? tr("{0} 等 {1} 班", shown, services.length) : shown;
	};

	/** What a route's draft changes from the game's version, as lines to read. */
	function routeChanges(r, entry) {
		const items = [];
		if (entry.deleted) items.push(tr("删除这个种别"));
		if ("managed" in entry) items.push(entry.managed ? tr("改由高级编辑器管理") : tr("交回游戏内编辑"));
		if ("connectingLine" in entry) items.push(entry.connectingLine ? tr("设为联络线") : tr("取消联络线"));
		if ("speedLimit" in entry) items.push(entry.speedLimit > 0 ? tr("限速 {0} km/h", entry.speedLimit) : tr("取消限速"));
		if (entry.connectingReleased) items.push(tr("联络线放行 {0} 段", entry.connectingReleased.length));
		if (entry.terminalPlatforms) items.push(tr("轮用站台 {0} 个", entry.terminalPlatforms.length));
		if (entry.startPlatforms) items.push(tr("优先股道 {0} 个", entry.startPlatforms.length));
		if (entry.platforms) {
			// Each platform by its station and number, from the stops that use it or could
			const names = new Map();
			const platformName = (stop, platform) => `${shortName(stop.name)} ${tr("站台 {0}", platform || "?")}`;
			data.routes.forEach(route => route.stops.forEach(stop => {
				(stop.candidates || []).forEach(candidate => names.set(candidate.id, platformName(stop, candidate.name)));
				if (stop.platform || !names.has(stop.platformId)) names.set(stop.platformId, platformName(stop, stop.platform));
			}));
			entry.platforms.forEach((id, i) => {
				const old = r.stops[i];
				if (old && old.platformId !== id) items.push(tr("第 {0} 站换股道：{1} → {2}", i + 1, escapeHtml(names.get(old.platformId) || "?"), escapeHtml(names.get(id) || "?")));
			});
		}
		if (!entry.timetable) return items;
		const before = gameTimetable(r), after = entry.timetable;
		if (before.enabled !== after.enabled) items.push(after.enabled ? tr("启用时刻表") : tr("停用时刻表"));
		const passed = (after.pass || []).map((pass, i) => pass !== Boolean((before.pass || [])[i]) ? tr("{0}改为{1}", escapeHtml(stopName(r, i)), pass ? tr("通过") : tr("停车")) : null).filter(Boolean);
		if (passed.length) items.push(tr("停站：{0}", passed.join(tr("；"))));
		if (before.entryPlatform !== after.entryPlatform) items.push(tr("出库接入站改了"));
		const count = Math.max(before.tables.length, after.tables.length);
		for (let t = 0; t < count; t++) {
			const old = before.tables[t], now = after.tables[t];
			if (!now) {
				items.push(tr("删掉{0}（{1}，{2} 班）", escapeHtml(tableName(old, t)), daysText(old.weekdays), old.services.length));
				continue;
			}
			const name = escapeHtml(tableName(now, t));
			if (!old) {
				items.push(tr("新建{0}（{1}，{2} 班）", name, daysText(now.weekdays), now.services.length));
				continue;
			}
			const lines = [];
			if (old.name !== now.name) lines.push(tr("改名为“{0}”", escapeHtml(now.name || tableName(now, t))));
			if (old.weekdays !== now.weekdays) lines.push(tr("星期 {0} → {1}", daysText(old.weekdays), daysText(now.weekdays)));
			if (old.marginPercent !== now.marginPercent) lines.push(tr("余裕 {0}% → {1}%", old.marginPercent, now.marginPercent));
			const oldServices = new Set(old.services), newServices = new Set(now.services);
			const added = now.services.filter(service => !oldServices.has(service)), removed = old.services.filter(service => !newServices.has(service));
			if (added.length) lines.push(tr("增加 {0} 班：{1}", added.length, someServices(added)));
			if (removed.length) lines.push(tr("删掉 {0} 班：{1}", removed.length, someServices(removed)));
			now.stops.forEach((stop, i) => {
				const was = old.stops[i] || { arrival: -1, departure: -1, overtake: false };
				const parts = [];
				if (was.arrival !== stop.arrival) parts.push(tr("到 {0} → {1}", setTimeText(was.arrival), setTimeText(stop.arrival)));
				if (was.departure !== stop.departure) parts.push(tr("发 {0} → {1}", setTimeText(was.departure), setTimeText(stop.departure)));
				if (Boolean(was.overtake) !== Boolean(stop.overtake)) parts.push(stop.overtake ? tr("待避") : tr("不待避"));
				if (parts.length && i < r.stops.length) lines.push(tr("{0}：{1}", escapeHtml(stopName(r, i)), parts.join(tr("，"))));
			});
			const linkText = link => { const target = data.routeById.get(link.route); return tr("{0} 直通 {1}", serviceClock(link.service), escapeHtml(target ? shortName(target.name) : "?")); };
			const oldLinks = new Map(old.links.map(link => [link.service, JSON.stringify(link)])), newLinks = new Map(now.links.map(link => [link.service, JSON.stringify(link)]));
			now.links.filter(link => oldLinks.get(link.service) !== JSON.stringify(link)).forEach(link => lines.push(tr("设直通：{0}", linkText(link))));
			old.links.filter(link => !newLinks.has(link.service)).forEach(link => lines.push(tr("取消直通：{0}", linkText(link))));
			const oldReturns = new Set(old.returns), newReturns = new Set(now.returns);
			const returned = now.returns.filter(service => !oldReturns.has(service)), unreturned = old.returns.filter(service => !newReturns.has(service));
			if (returned.length) lines.push(tr("回库：{0}", someServices(returned)));
			if (unreturned.length) lines.push(tr("不再回库：{0}", someServices(unreturned)));
			const oldSuspended = new Set(old.suspended || []), newSuspended = new Set(now.suspended || []);
			const suspended = (now.suspended || []).filter(service => !oldSuspended.has(service)), resumed = (old.suspended || []).filter(service => !newSuspended.has(service));
			if (suspended.length) lines.push(tr("停运：{0}", someServices(suspended)));
			if (resumed.length) lines.push(tr("恢复运行：{0}", someServices(resumed)));
			const orderText = order => tr(order.kind === "deploy" ? "{0} 从{1}出车（{2}）" : "{0} 在{1}转回送（{2}）", serviceClock(order.service), escapeHtml(stopName(r, order.stop)), escapeHtml(orderDepotName(order)));
			const oldOrders = new Map((old.orders || []).map(order => [order.service + order.kind, JSON.stringify(order)])), newOrders = new Map((now.orders || []).map(order => [order.service + order.kind, JSON.stringify(order)]));
			(now.orders || []).filter(order => oldOrders.get(order.service + order.kind) !== JSON.stringify(order)).forEach(order => lines.push(tr("设{0}", orderText(order))));
			(old.orders || []).filter(order => !newOrders.has(order.service + order.kind)).forEach(order => lines.push(tr("取消{0}", orderText(order))));
			const oldExceptions = new Map(old.exceptions.map(exception => [exception.service, JSON.stringify(exception)]));
			const newExceptions = new Map(now.exceptions.map(exception => [exception.service, JSON.stringify(exception)]));
			now.exceptions.filter(exception => oldExceptions.get(exception.service) !== JSON.stringify(exception)).forEach(exception => {
				const stops = exception.stops.map(stop => {
					const parts = [];
					if (stop.run >= 0) parts.push(tr("运行 {0} 秒", stop.run));
					if (stop.dwell >= 0) parts.push(tr("停 {0} 秒", stop.dwell));
					if (stop.pass >= 0) parts.push(stop.pass ? tr("通过") : tr("停车"));
					if (stop.overtake) parts.push(tr("待避"));
					return parts.length && stop.stop < r.stops.length ? `${escapeHtml(stopName(r, stop.stop))} ${parts.join(tr("，"))}` : null;
				}).filter(Boolean);
				lines.push(tr("{0} 这一班：{1}", serviceClock(exception.service), stops.length ? stops.join(tr("；")) : tr("例外")));
			});
			old.exceptions.filter(exception => !newExceptions.has(exception.service)).forEach(exception => lines.push(tr("{0} 这一班的例外去掉", serviceClock(exception.service))));
			if (lines.length) items.push(`<b>${name}</b><ul>${lines.map(line => `<li>${line}</li>`).join("")}</ul>`);
		}
		return items;
	}

	/** The draft's changes, route by route, with a way to each and to drop it. */
	function draftChangesDialog() {
		if (!data || !draftCount()) return;
		const sections = [];
		Object.entries(draft.routes).forEach(([id, entry]) => {
			const r = data.routeById.get(id);
			if (!r) {
				sections.push(`<section class="draft-change"><h3>${tr("已删除的线路")}</h3><div class="draft-actions"><button type="button" data-diff-drop="${id}">${tr("放弃这条的草稿")}</button></div></section>`);
				return;
			}
			const items = routeChanges(r, entry);
			const conflict = entry.conflict ? `<p class="err">${escapeHtml(entry.error || tr("游戏里的这条线路在草稿之后改过"))}</p><div class="draft-actions"><button type="button" data-diff-keep="${id}">${tr("以最新版本为基础，覆盖对方的修改")}</button><button type="button" data-diff-drop="${id}">${tr("放弃草稿，用游戏里的")}</button></div>` : entry.error ? `<p class="err">${escapeHtml(entry.error)}</p>` : "";
			sections.push(`<section class="draft-change"><h3>${escapeHtml(routeTitle(r))}</h3>${conflict}<ul>${items.length ? items.map(item => `<li>${item}</li>`).join("") : `<li>${tr("没有和游戏不同的地方")}</li>`}</ul>
				<div class="draft-actions"><button type="button" data-diff-open="${id}">${tr("跳到这里")}</button>${entry.conflict ? "" : `<button type="button" data-diff-drop="${id}">${tr("放弃这条的草稿")}</button>`}</div></section>`);
		});
		draft.created.forEach((made, index) => sections.push(`<section class="draft-change"><h3>＋ ${escapeHtml(shortName(made.name))}</h3>${made.error ? `<p class="err">${escapeHtml(made.error)}</p>` : ""}<ul><li>${made.source ? tr("新建种别：{0} 站，{1} 班", made.stops.length, (made.services || []).length) : tr("新建线路：{0} 站", made.stops.length)}</li></ul><div class="draft-actions"><button type="button" data-diff-part="created" data-diff-key="${index}">${tr("放弃这项草稿")}</button></div></section>`));
		// A rail system's contingency plans, drafted in "路网故障预案…" (D2)
		Object.entries(draft.contingencies || {}).forEach(([anchor, plans]) => {
			const owner = plansByAnchor(anchor);
			sections.push(`<section class="draft-change"><h3>${escapeHtml(tr("路网{0}的故障预案", owner ? systemTitle(owner.system) : anchor) + (owner && owner.system.anchor !== anchor ? tr("（原来接进来的一套）") : ""))}</h3><ul>${plans.length ? plans.map(plan => `<li>${escapeHtml(tr("预案 {0}「{1}」：{2}", plan.id, plan.name, plan.manual ? tr("手动启用") : tr("自动启用")))}</li>`).join("") : `<li>${tr("删除全部预案")}</li>`}</ul><div class="draft-actions"><button type="button" data-diff-part="contingencies" data-diff-key="${escapeHtml(anchor)}">${tr("放弃这项草稿")}</button></div></section>`);
		});
		// What a depot runs, drafted in "编辑车厂…"
		Object.entries(draft.depots || {}).forEach(([depotId, entry]) => {
			const depot = depotById(depotId);
			const routeName = id => data.routeById.get(id) ? routeTitle(data.routeById.get(id)) : tr("已删除的线路");
			const lines = [tr("负责的线路：{0}", entry.routes.map(routeName).join("、") || tr("无")), entry.cycleRoutes ? tr("允许长时间停在线上") : tr("不在线上久等")];
			(depot ? depot.sidings : []).forEach(siding => {
				const value = entry.sidings[siding.id];
				if (value) lines.push(tr("{0}：{1}，{2}", shortName(siding.name) || tr("存车线"), value.fleet < 0 ? tr("停车不限") : tr("最多停 {0} 列", value.fleet), value.routes.length ? tr("只跑：{0}", value.routes.map(routeName).join("、")) : tr("跑车厂的全部线路")));
			});
			(depot ? depot.pools || [] : []).forEach(pool => {
				const fleet = entry.poolFleets ? entry.poolFleets[pool.key] : undefined;
				if (fleet !== undefined) lines.push(tr("车型 {0}：{1}", pool.vehicle || pool.key, poolFleetText(fleet, pool.sum)));
			});
			Object.entries(entry.serviceTypes || {}).forEach(([vehicle, text]) => lines.push(tr("车型 {0} 的种别：{1}", vehicle, text || tr("不限"))));
			sections.push(`<section class="draft-change"><h3>${escapeHtml(shortName(depot ? depot.name : "?"))}</h3>${entry.error ? `<p class="err">${escapeHtml(entry.error)}</p>` : ""}<ul>${lines.map(line => `<li>${escapeHtml(line)}</li>`).join("")}</ul><div class="draft-actions"><button type="button" data-diff-part="depots" data-diff-key="${escapeHtml(depotId)}">${tr("放弃这项草稿")}</button></div></section>`);
		});
		Object.entries(draft.duties).forEach(([depotId, entry]) => {
			const depot = depotById(depotId);
			const slots = Object.values(entry.duties.sidings || {}).reduce((a, list) => a + list.length, 0);
			sections.push(`<section class="draft-change"><h3>${escapeHtml(shortName(depot ? depot.name : "?"))}</h3>${entry.error ? `<p class="err">${escapeHtml(entry.error)}</p>` : ""}<ul><li>${tr("运用改了：{0}，{1} 个运用", entry.duties.mode === "diagram" ? tr("按运用图") : tr("自动排班"), slots)}</li></ul><div class="draft-actions"><button type="button" data-diff-part="duties" data-diff-key="${escapeHtml(depotId)}">${tr("放弃这项草稿")}</button></div></section>`);
		});
		const boundaryKeys = Object.keys(draft.boundaries || {});
		if (boundaryKeys.length) {
			const added = boundaryKeys.filter(key => draft.boundaries[key]).length;
			const errors = boundaryKeys.map(key => boundaryErrors[key]).filter(Boolean);
			sections.push(`<section class="draft-change"><h3>${tr("隔离")}</h3>${errors.length ? `<p class="err">${escapeHtml(errors[0])}</p>` : ""}<ul>${added ? `<li>${tr("设为寻路边界 {0} 段", added)}</li>` : ""}${boundaryKeys.length - added ? `<li>${tr("取消寻路边界 {0} 段", boundaryKeys.length - added)}</li>` : ""}</ul>
				<div class="draft-actions"><button type="button" data-diff-boundaries>${tr("放弃隔离的草稿")}</button></div></section>`);
		}
		const platformSpeedKeys = Object.keys(draft.platformSpeeds || {});
		if (platformSpeedKeys.length) {
			sections.push(`<section class="draft-change"><h3>${tr("过站限速")}</h3><ul>${platformSpeedKeys.map(platformId => `<li>${escapeHtml(tr("{0} 过站限速 {1} km/h", platformText(platformId), draft.platformSpeeds[platformId]))}</li>`).join("")}</ul><div class="draft-actions"><button type="button" data-diff-part="platformSpeeds" data-diff-key="${""}">${tr("放弃这项草稿")}</button></div></section>`);
		}
		if (draft.networkSettings) {
			const settingItems = [];
			if (Number.isFinite(draft.networkSettings.minReverseMillis)) settingItems.push(tr("换端最短时间 {0} 秒", Math.round(draft.networkSettings.minReverseMillis / 1000)));
			if (Number.isFinite(draft.networkSettings.waitThresholdMillis)) settingItems.push(tr("等候分界 {0} 秒", Math.round(draft.networkSettings.waitThresholdMillis / 1000)));
			Object.entries(draft.networkSettings.stationWaitThresholds || {}).forEach(([station, millis]) => settingItems.push(millis === null ? tr("{0}：等候分界改回全网的", stationNameOf(station)) : tr("{0}：等候分界 {1} 秒", stationNameOf(station), Math.round(millis / 1000))));
			// Logical lines (2026-11-09)
			Object.entries(draft.networkSettings.logicalLines || {}).forEach(([id, line]) => {
				const was = (savedSettings().logicalLines || []).find(each => each.id === id);
				settingItems.push(line === null ? tr("删除逻辑线路 {0}", was ? was.name : id) : was ? tr("逻辑线路 {0}：改为 {1}{2}", was.name, line.name, line.connecting ? tr("（联络线）") : "") : tr("新建逻辑线路 {0}{1}", line.name, line.connecting ? tr("（联络线）") : ""));
			});
			const railLineCount = Object.keys(draft.networkSettings.railLines || {}).length;
			if (railLineCount) settingItems.push(tr("{0} 段轨道改了所属的逻辑线路", railLineCount));
			Object.entries(draft.networkSettings.lineTracks || {}).forEach(([lineKey, ids]) => {
				const line = data.lines.find(each => each.key === lineKey);
				const name = line ? shortName(line.name) : lineKey;
				settingItems.push(ids === null ? tr("{0}：按站台所在的逻辑线路走", name) : tr("{0}：走逻辑线路 {1}", name, ids.map(id => (logicalLinesShown().get(id) || { name: id }).name).join(tr("、"))));
			});
			// Section owners set by hand (web layers step 2), by the rail chosen
			Object.entries(draft.networkSettings.ownerOverrides || {}).forEach(([key, lines]) => settingItems.push(lines === null ? tr("轨道 {0}：区段归属恢复自动", key) : lines.length ? tr("轨道 {0}：区段归属 {1}", key, lines.map(ownerText).join(tr("、"))) : tr("轨道 {0}：区段对所有线路开放", key)));
			sections.push(`<section class="draft-change"><h3>${tr("全网设置")}</h3><ul>${settingItems.map(item => `<li>${escapeHtml(item)}</li>`).join("")}</ul><div class="draft-actions"><button type="button" data-diff-part="networkSettings" data-diff-key="${""}">${tr("放弃这项草稿")}</button></div></section>`);
		}
		const platformStopKeys = Object.keys(draft.platformStops || {});
		if (platformStopKeys.length) {
			sections.push(`<section class="draft-change"><h3>${tr("停车位置")}</h3><ul>${platformStopKeys.map(platformId => `<li>${escapeHtml(platformText(platformId) + " " + stopPositionText(draft.platformStops[platformId]))}</li>`).join("")}</ul><div class="draft-actions"><button type="button" data-diff-part="platformStops" data-diff-key="${""}">${tr("放弃这项草稿")}</button></div></section>`);
		}
		const platformStationKeys = Object.keys(draft.platformStations || {});
		if (platformStationKeys.length) {
			sections.push(`<section class="draft-change"><h3>${tr("站台归属")}</h3><ul>${platformStationKeys.map(platformId => `<li>${escapeHtml(tr("{0} 归到 {1}", platformText(platformId), stationNameOf(draft.platformStations[platformId])))}</li>`).join("")}</ul><div class="draft-actions"><button type="button" data-diff-part="platformStations" data-diff-key="${""}">${tr("放弃这项草稿")}</button></div></section>`);
		}
		openDialog(tr("草稿的改动"), `<p class="hint">${tr("和游戏里现在的版本比。按“应用”才发给游戏。")}</p>
			<div class="draft-actions"><button type="button" data-diff-clear>${tr("清空全部草稿…")}</button></div><div class="draft-changes">${sections.join("")}</div>`, tr("关闭"), () => {});
	}

	$("draft-tag").addEventListener("click", draftChangesDialog);
	dialog.addEventListener("click", e => {
		const open = e.target.closest("[data-diff-open]"), drop = e.target.closest("[data-diff-drop]"), keep = e.target.closest("[data-diff-keep]");
		if (e.target.closest("[data-diff-boundaries]")) {
			e.preventDefault();
			draft.boundaries = {};
			saveDraft();
			renderAll();
			dialog.close();
			return;
		}
		// Everything in the draft dropped at once, after asking
		if (e.target.closest("[data-diff-clear]")) {
			e.preventDefault();
			if (!confirm(tr("清空全部草稿？草稿里的 {0} 项改动都丢掉，不能撤销；游戏里的不受影响。", draftCount()))) return;
			clearDraft();
			dialog.close();
			return;
		}
		// One part of the draft that has no way of its own to drop it
		const part = e.target.closest("[data-diff-part]");
		if (part) {
			e.preventDefault();
			const kind = part.dataset.diffPart, partKey = part.dataset.diffKey;
			if (kind === "created") draft.created.splice(Number(partKey), 1);
			else if (kind === "networkSettings") draft.networkSettings = null;
			else if (partKey === "") draft[kind] = {};
			else delete draft[kind][partKey];
			saveDraft();
			buildServices();
			renderAll();
			if (draftCount()) draftChangesDialog();
			else dialog.close();
			return;
		}
		if (!open && !drop && !keep) return;
		e.preventDefault();
		if (open) {
			const r = data.routeById.get(open.dataset.diffOpen);
			dialog.close();
			if (r) openView("table", r.lineKey, r.id);
			return;
		}
		if (drop) {
			delete draft.routes[drop.dataset.diffDrop];
			saveDraft();
			buildServices();
			renderAll();
		} else {
			resolveConflict(keep.dataset.diffKeep, true);
		}
		if (draftCount()) draftChangesDialog();
		else dialog.close();
	});

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
			tree.innerHTML = `<div class="empty">${tr("正在读取…")}</div>`;
			return;
		}
		const node = (attrs, caret, content, active) => `<div class="node${active ? " active" : ""}" tabindex="0" ${attrs}><span class="caret">${caret}</span>${content}</div>`;
		// The tree by the layers of the save (web layers step 4): the physical track, who owns each section, the lines and their
		// stops, their timetables and diagrams, and the depots with their vehicles
		const lineLabel = line => `<span class="swatch" style="background:${colorOf(line.color)}"></span><span class="label group-label">${escapeHtml(shortName(line.name))}</span>`;
		const routeLabel = r => `<span class="swatch" style="background:${colorOf(r.color)}"></span><span class="label${isDeleted(r) ? " deleted" : ""}">${escapeHtml(routeTitle(r))}</span>${isDeleted(r) ? `<span class="draft-dot" title="${escapeHtml(draftOf(r).error || tr("将删除：按“应用”后从游戏里删掉，右键取消"))}">${draftOf(r).error ? "!" : "−"}</span>` : draftOf(r) ? `<span class="draft-dot" title="${tr("有没应用的草稿")}">•</span>` : ""}${problemDot(r)}`;
		const createdNode = (made, title) => `<li>${node(`data-created="${made.key}"`, "", `<span class="swatch" style="background:${colorOf(made.color)}"></span><span class="label">＋ ${escapeHtml(shortName(made.name))}</span><span class="draft-dot" title="${escapeHtml(made.error || title)}">${made.error ? "!" : "•"}</span>`, false)}</li>`;
		const onNetwork = layer => state.view === "network" && net.layer === layer;
		const parts = [];

		// 1. The physical track
		parts.push(`<div class="section">${tr("物理线路")}</div><ul>`);
		parts.push(`<li>${node(`data-layer="physical" title="${tr("轨道、寻路边界、留置位；右键轨道设寻路边界")}"`, "", `<span class="label leaf">${tr("全网配线")}</span>`, onNetwork("physical"))}</li>`);
		parts.push(`</ul>`);

		// 2. The logical lines of the track, apart from the operating lines (2026-11-09); before there are any, the owners worked
		// out from boundaries and platforms, with the connecting lines
		const logicalLines = [...logicalLinesShown().values()].sort((a, b) => a.name.localeCompare(b.name));
		parts.push(`<div class="section">${tr("逻辑归属")}</div><ul>`);
		parts.push(`<li>${node(`data-layer="owners" title="${tr("每段轨道属于哪条逻辑线路；右键轨道划入或移出")}"`, "", `<span class="label leaf">${tr("归属图")}</span>`, onNetwork("owners") && !state.route && !net.focusLogical)}`);
		parts.push(`<ul>`);
		if (logicalLines.length) {
			const drafted = draftSettings().logicalLines || {};
			logicalLines.forEach(line => parts.push(`<li>${node(`data-logical="${line.id}" title="${tr("点开在归属图上看它；右键改名、改颜色、设为联络线或删除")}"`, "", `<span class="swatch" style="background:${colorOf(line.color)}"></span><span class="label">${escapeHtml(line.name)}</span>${line.connecting ? `<span class="count">${tr("联络线")}</span>` : ""}${line.id in drafted ? `<span class="draft-dot" title="${tr("有没应用的草稿")}">•</span>` : ""}`, onNetwork("owners") && net.focusLogical === line.id)}</li>`));
		} else {
			parts.push(`<li class="hint">${tr("还没有逻辑线路：现在按寻路边界和站台自动算归属")}</li>`);
			if (data.canEdit) parts.push(`<li>${node(`data-generate-logical="1"`, "", `<span class="label leaf">${tr("按现在的归属生成初稿…")}</span>`, false)}</li>`);
			const connectingLines = data.routes.filter(isConnecting).sort((a, b) => routeTitle(a).localeCompare(routeTitle(b)));
			if (connectingLines.length) {
				parts.push(`<li class="hint">${tr("联络线")}</li>`);
				connectingLines.forEach(r => parts.push(`<li>${node(`data-connecting="${r.id}"`, "", routeLabel(r), onNetwork("owners") && state.route === r.id)}</li>`));
			}
		}
		if (data.canEdit) parts.push(`<li>${node(`data-new-logical="1"`, "", `<span class="label leaf">${tr("＋ 新建逻辑线路…")}</span>`, false)}</li>`);
		parts.push(`</ul></li></ul>`);

		// 3. The operating lines: their types and stops
		parts.push(`<div class="section">${tr("运营线路")}</div><ul>`);
		if (!data.lines.length) parts.push(`<li class="empty">${tr("没有线路")}${data.canEdit ? ` <button type="button" data-new-line>${tr("＋ 新建线路")}</button>` : ""}</li>`);
		data.lines.forEach(line => {
			const open = state.openNodes.has("oline:" + line.key);
			parts.push(`<li>${node(`data-line="${escapeHtml(line.key)}" data-section="lines"`, open ? "▾" : "▸", `${lineLabel(line)}<span class="count">${tr("{0} 个种别", line.routes.length)}</span>`, false)}`);
			if (open) {
				parts.push(`<ul>`);
				line.routes.forEach(r => parts.push(`<li>${node(`data-route="${r.id}" data-section="lines" title="${tr("点开编辑停站；右键更多")}"`, "", `${routeLabel(r)}<span class="count">${tr("{0} 站", r.stops.length)}</span>`, false)}</li>`));
				draft.created.filter(made => made.lineKey === line.key).forEach(made => parts.push(createdNode(made, tr("新建的种别，应用后才在游戏里出现；右键删除"))));
				parts.push(`</ul>`);
			}
			parts.push(`</li>`);
		});
		// New lines waiting in the draft, which aren't lines of the game yet
		const draftLines = new Map();
		draft.created.filter(made => !made.source && !data.lines.some(l => l.key === made.lineKey)).forEach(made => {
			if (!draftLines.has(made.lineKey)) draftLines.set(made.lineKey, []);
			draftLines.get(made.lineKey).push(made);
		});
		draftLines.forEach(list => {
			parts.push(`<li>${node("", "▾", `<span class="swatch" style="background:${colorOf(list[0].color)}"></span><span class="label group-label">${tr("（草稿）{0}", escapeHtml(list[0].lineName))}</span>`, false)}<ul>`);
			list.forEach(made => parts.push(createdNode(made, tr("新建的线路，应用后才在游戏里出现；右键删除"))));
			parts.push(`</ul></li>`);
		});
		parts.push(`</ul>`);

		// 4. Timetables and diagrams, of a line and of each of its types
		parts.push(`<div class="section">${tr("时刻表和交路")}</div><ul>`);
		data.lines.forEach(line => {
			const open = state.openNodes.has("line:" + line.key) || line.key === state.line;
			const count = line.routes.reduce((a, r) => a + r.services.length, 0);
			parts.push(`<li>${node(`data-line="${escapeHtml(line.key)}"`, open ? "▾" : "▸", `${lineLabel(line)}<span class="count">${count}</span>`, false)}`);
			if (open) {
				parts.push(`<ul>`);
				parts.push(`<li>${node(`data-line="${escapeHtml(line.key)}" data-view="table"`, "", `<span class="label leaf">${tr("汇总时刻表")}</span>`, state.line === line.key && !state.route && state.view === "table")}</li>`);
				parts.push(`<li>${node(`data-line="${escapeHtml(line.key)}" data-view="diagram"`, "", `<span class="label leaf">${tr("运行图")}</span>`, state.line === line.key && !state.route && state.view === "diagram")}</li>`);
				line.routes.forEach(r => {
					const routeOpen = state.openNodes.has("route:" + r.id) || state.route === r.id;
					parts.push(`<li>${node(`data-route="${r.id}"`, routeOpen ? "▾" : "▸", `${routeLabel(r)}<span class="count">${r.services.length}</span>`, false)}`);
					if (routeOpen) {
						parts.push(`<ul>`);
						parts.push(`<li>${node(`data-route="${r.id}" data-view="table"`, "", `<span class="label leaf">${tr("汇总时刻表")}</span>`, state.route === r.id && state.view === "table")}</li>`);
						parts.push(`<li>${node(`data-route="${r.id}" data-view="diagram"`, "", `<span class="label leaf">${tr("运行图")}</span>`, state.route === r.id && state.view === "diagram")}</li>`);
						parts.push(`</ul>`);
					}
					parts.push(`</li>`);
				});
				parts.push(`</ul>`);
			}
			parts.push(`</li>`);
		});
		parts.push(`</ul><div class="section">${tr("车厂")}</div><ul>`);
		if (!data.depots.length) parts.push(`<li class="empty">${tr("没有车厂")}</li>`);
		data.depots.forEach(depot => {
			const open = state.openNodes.has("depot:" + depot.id);
			const duties = dutiesOf(depot);
			parts.push(`<li>${node(`data-depot="${depot.id}"`, open ? "▾" : "▸", `<span class="label group-label">${escapeHtml(shortName(depot.name))}</span>${lockMark(depot)}${draft.duties[depot.id] ? `<span class="draft-dot" title="${escapeHtml(draft.duties[depot.id].error || tr("运用有没应用的草稿"))}">${draft.duties[depot.id].error ? "!" : "•"}</span>` : ""}<span class="count">${duties.auto ? tr("自动运用") : duties.mode === "diagram" ? tr("按运用图") : tr("自动排班")}${duties.auto && depot.shortfall && depot.shortfall.uncovered > 0 ? `<span class="err" title="${escapeHtml(shortfallText(depot.shortfall))}"> · ${missingTrains(depot.shortfall) < 0 ? tr("缺车") : tr("缺 {0} 列", missingTrains(depot.shortfall))}</span>` : ""}${duties.auto && depot.shortfall && depot.shortfall.replan ? `<span class="hint" title="${escapeHtml(replanText(depot.shortfall))}"> · ${tr("建议重排")}</span>` : ""}</span>`, false)}`);
			if (open) {
				parts.push(`<ul>`);
				// Duties are the pool's (S8): by vehicle, each with the siding its train comes from; the sidings apart, as places
				const dutyNode = (siding, slot, index, number) => {
					const day = dutyOn(slot, state.weekday);
					const active = state.slot && state.slot.depot === depot.id && state.slot.siding === siding.id && state.slot.index === index;
					const problems = day ? dutyProblems(depot, siding.id, index, state.weekday).length : 0;
					return `<li>${node(`data-slot="${index}" data-siding="${siding.id}" data-of-depot="${depot.id}"`, "", `<span class="label leaf">${tr("运用 {0}", number)} <span class="hint">${escapeHtml(startText(slot, siding))}</span></span>${problems ? `<span class="draft-dot" title="${tr("运用有问题")}">!</span>` : ""}<span class="count">${day ? day.segments.length + tr(" 班") + (day.stay ? tr(" 留") : "") : tr("休")}</span>`, active)}</li>`;
				};
				const pools = (depot.pools || []).length ? depot.pools : [{ key: "", vehicle: "", fleet: null, sum: -1, sidings: depot.sidings.map(siding => siding.id) }];
				pools.forEach(pool => {
					const draftedFleet = draft.depots && draft.depots[depot.id] && draft.depots[depot.id].poolFleets ? draft.depots[depot.id].poolFleets[pool.key] : undefined;
					const poolSidings = (pool.sidings || []).map(id => depot.sidings.find(siding => siding.id === id)).filter(Boolean);
					const count = poolSidings.reduce((sum, siding) => sum + (duties.sidings[siding.id] || []).length, 0);
					const vehicleDrafted = draft.depots && draft.depots[depot.id] && (draft.depots[depot.id].vehicleSettings && draft.depots[depot.id].vehicleSettings[pool.key] !== undefined && !sameVehicle(draft.depots[depot.id].vehicleSettings[pool.key], pool.vehicleSettings) || draft.depots[depot.id].serviceTypes && draft.depots[depot.id].serviceTypes[pool.vehicle] !== undefined);
					parts.push(`<li>${node(`data-pool="${escapeHtml(pool.key)}" data-of-depot="${depot.id}" title="${tr("右键“车型设置…”改加减速、最高速度、手动驾驶和能跑的种别")}"`, "", `<span class="label group-label">${escapeHtml(tr("车型 {0}", pool.vehicle || pool.key || "?"))}</span>${vehicleDrafted ? `<span class="draft-dot" title="${tr("有没应用的草稿")}">•</span>` : ""}<span class="count">${escapeHtml(poolFleetText(draftedFleet !== undefined ? draftedFleet : pool.fleet, pool.sum))} · ${tr("{0} 份运用", count)}</span>`, false)}<ul>`);
					// Numbered on through the pool, as the duties are the pool's (运用补完 step 5)
					let number = 0;
					poolSidings.forEach(siding => (duties.sidings[siding.id] || []).forEach((slot, index) => parts.push(dutyNode(siding, slot, index, ++number))));
					if (!count) parts.push(`<li class="empty">${tr("没有运用")}</li>`);
					parts.push(`</ul></li>`);
				});
				parts.push(`<li class="hint">${tr("存车线")}</li>`);
				if (!depot.sidings.length) parts.push(`<li class="empty">${tr("没有存车线")}</li>`);
				depot.sidings.forEach(siding => {
					// A place trains stand in: how many it holds, and the routes its trains are given
					const drafted = draft.depots && draft.depots[depot.id] && draft.depots[depot.id].sidings && draft.depots[depot.id].sidings[siding.id];
					const fleet = drafted ? drafted.fleet : siding.fleet;
					const given = drafted ? drafted.routes : siding.routes || [];
					const fleetText = fleet < 0 ? tr("停车不限") : tr("最多停 {0} 列", fleet);
					const givenText = given.length ? tr("只跑：{0}", given.map(id => data.routeById.get(id) ? routeTitle(data.routeById.get(id)) : "?").join("、")) : tr("跑车厂的全部线路");
					parts.push(`<li>${node(`data-siding="${siding.id}" data-of-depot="${depot.id}"`, "", `<span class="label leaf">${escapeHtml(shortName(siding.name) || tr("存车线"))}</span><span class="count" title="${escapeHtml(givenText)}">${fleetText}${given.length ? " · " + tr("{0} 条线路", given.length) : ""}</span>`, false)}</li>`);
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

	function renderViews() {
		const split = state.split && canSplit();
		document.body.classList.toggle("split", split);
		$("btn-split").hidden = !canSplit();
		$("btn-split").setAttribute("aria-pressed", String(split));
		$("view-diagram").hidden = !showsDiagram();
		$("view-table").hidden = !showsTable();
		$("view-network").hidden = state.view !== "network";
		document.body.classList.toggle("network-view", state.view === "network");
		$("tab-network").setAttribute("aria-selected", String(state.view === "network"));
		$("tab-diagram").setAttribute("aria-selected", String(state.view === "diagram"));
		$("tab-table").setAttribute("aria-selected", String(state.view === "table"));
		const line = currentLine(), r = state.route ? data && data.routeById.get(state.route) : null;
		$("crumb").textContent = line ? shortName(line.name) + (r ? " › " + routeTitle(r) : "") : "";
	}

	const currentLine = () => data && data.lines.find(l => l.key === state.line);


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
	const net = { data: null, version: -1, mode: "", scale: 0, cx: 0, cz: 0, grid: null, hover: -1, error: "", timer: null, drag: null, layer: "physical", focusLogical: null };
	// Which layer the map shows (web layers step 2): the physical track, or which lines own each section
	try { if (localStorage.getItem("mtr-editor-net-layer") === "owners") net.layer = "owners"; } catch (e) {}
	const netCanvas = $("net-canvas"), netWrap = $("net-wrap"), netCtx = netCanvas.getContext("2d");

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
		if (net.scale >= NET_EXACT_SCALE) return chain.points;
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
	const draftOwnerOverrides = () => draft.networkSettings && draft.networkSettings.ownerOverrides || {};
	const savedOwnerOverrides = () => data && data.networkSettings && data.networkSettings.ownerOverrides || {};
	const netRailKey = index => {
		const d = net.data, rail = d.rails[index];
		return railKey(d.nodes[rail[0]][2], d.nodes[rail[1]][2]);
	};
	/** The rails of a rail's section, as far as the page knows: a section no line owns isn't sent, so it is the rail alone. */
	const sectionOf = index => {
		const entry = net.ownerOf && net.ownerOf.get(index);
		return entry ? net.sectionRails.get(entry.section) : [index];
	};

	/**
	 * Who a rail is shown as owned by: as the server worked it out, or as the draft sets its section by hand. Owners set by
	 * hand on several rails of a section are all of theirs, as the server takes them.
	 *
	 * @return {owners: [owner], manual, drafted, auto} where auto is a section the draft gives back to being worked out
	 */
	function railOwnersShown(index) {
		const entry = net.ownerOf && net.ownerOf.get(index);
		const drafted = draftOwnerOverrides(), saved = savedOwnerOverrides();
		const keys = sectionOf(index).map(netRailKey).filter(key => key in drafted);
		if (!keys.length) return { owners: entry ? entry.owners : [], manual: Boolean(entry && entry.manual), drafted: false, auto: false };
		// What the section will be: the drafted rails as drafted, its other rails set by hand as saved
		const lines = new Set();
		let anySet = false;
		sectionOf(index).map(netRailKey).forEach(key => {
			const value = key in drafted ? drafted[key] : saved[key];
			if (value === undefined || value === null) return;
			anySet = true;
			value.forEach(line => lines.add(line));
		});
		if (!anySet) return { owners: [], manual: false, drafted: true, auto: true };
		return { owners: [...lines], manual: true, drafted: true, auto: false };
	}

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
		if (owner.startsWith("connecting:")) return "#e8891a";
		const line = data && data.lines.find(l => l.key === owner);
		return line ? colorOf(line.color) : "#888";
	}

	/**
	 * Sets the owners of a rail's section by hand in the draft: the lines, none to open it to every line, or null to have
	 * them worked out again. Kept on the rail chosen, and taken off the section's other rails.
	 */
	function setOwnerOverride(index, lines) {
		const saved = savedOwnerOverrides();
		const overrides = Object.assign({}, draftOwnerOverrides());
		const key = netRailKey(index);
		sectionOf(index).map(netRailKey).forEach(other => {
			if (other === key && lines !== null) return;
			if (other in saved) overrides[other] = null;
			else delete overrides[other];
		});
		if (lines !== null) overrides[key] = lines;
		// What only takes back a change not made yet is no change
		Object.keys(overrides).forEach(other => {
			if (overrides[other] === null && !(other in saved)) delete overrides[other];
			else if (overrides[other] !== null && JSON.stringify(overrides[other]) === JSON.stringify(saved[other])) delete overrides[other];
		});
		const settings = Object.assign({}, draft.networkSettings || {});
		if (Object.keys(overrides).length) settings.ownerOverrides = overrides;
		else delete settings.ownerOverrides;
		draft.networkSettings = Object.keys(settings).length ? settings : null;
		saveDraft();
		renderAll();
		drawNetwork();
	}

	/** Choosing the lines that own a rail's section, from the lines of the map's kind of transport. */
	function ownersDialog(index) {
		const shown = railOwnersShown(index);
		const lines = data.lines.filter(line => line.key.startsWith((net.mode || "TRAIN") + "|"));
		const boxes = lines.map(line => `<label class="owner-choice"><input type="checkbox" name="owner" value="${escapeHtml(line.key)}"${shown.owners.includes(line.key) ? " checked" : ""}><span class="swatch" style="background:${colorOf(line.color)}"></span>${escapeHtml(shortName(line.name))}</label>`).join("");
		openDialog(tr("区段归属"), `<p>${tr("这一段轨道（到下一个道岔或边界为止的整个区段）只让勾选的线路走。联络线和没有站台的线路不受限制。")}</p>
			<div class="owner-choices">${boxes || `<p class="hint">${tr("没有这种运输方式的线路")}</p>`}</div>
			<p class="hint">${tr("改动先存为草稿，点“应用”后服务端重新算归属，走不了的线路会重新寻路。")}</p>`, tr("确定"), form => {
			const ticked = [...form.querySelectorAll("input[name=owner]:checked")].map(input => input.value);
			if (!ticked.length) return tr("至少勾一条线路；要让所有线路都能走，请用右键“对所有线路开放”");
			setOwnerOverride(index, ticked);
		});
	}

	// ---------- Logical lines (2026-11-09) ----------
	/*
	 * Lines of the track itself, apart from the operating lines: each section in at most one, kept by a rail of it; an
	 * operating line runs on the logical lines chosen for it, or those of its platforms (transitsim LogicalLines). Once there
	 * are any, they decide where operating lines may run instead of the owners worked out from boundaries and platforms.
	 */
	const draftSettings = () => draft.networkSettings || {};
	const savedSettings = () => data && data.networkSettings || {};
	const compareRailKeys = (a, b) => {
		const [a1, a2] = a.split(",").map(BigInt), [b1, b2] = b.split(",").map(BigInt);
		return a1 < b1 ? -1 : a1 > b1 ? 1 : a2 < b2 ? -1 : a2 > b2 ? 1 : 0;
	};

	/** The logical lines as the draft has them, by ID: {id, name, color, connecting}. */
	function logicalLinesShown() {
		const lines = new Map((savedSettings().logicalLines || []).map(line => [line.id, Object.assign({}, line)]));
		Object.entries(draftSettings().logicalLines || {}).forEach(([id, line]) => {
			if (line === null) lines.delete(id);
			else lines.set(id, Object.assign({ id }, line));
		});
		return lines;
	}
	const usesLogicalLines = () => logicalLinesShown().size > 0;

	/** Each section's logical line by a rail of it, as the draft has them. */
	const railLinesShown = () => Object.assign({}, savedSettings().railLines || {}, draftSettings().railLines || {});

	/** The logical line of a rail's section as the draft has it, or null; of several rails set, the lowest key counts, as on the server. */
	function railLogicalLine(index) {
		const effective = railLinesShown(), lines = logicalLinesShown();
		const keys = sectionOf(index).map(netRailKey).filter(key => effective[key] !== null && effective[key] !== undefined && lines.has(effective[key])).sort(compareRailKeys);
		return keys.length ? lines.get(effective[keys[0]]) : null;
	}
	const railLogicalDrafted = index => sectionOf(index).map(netRailKey).some(key => key in (draftSettings().railLines || {}));

	/** Changes one part of the drafted network settings, taking out what is as the game has it, and the settings when nothing is left. */
	function draftSettingsPart(part, change) {
		const saved = part === "logicalLines" ? Object.fromEntries((savedSettings().logicalLines || []).map(line => [line.id, { name: line.name, color: line.color, connecting: line.connecting }])) : savedSettings()[part] || {};
		const drafted = Object.assign({}, draftSettings()[part] || {});
		change(drafted, saved);
		Object.keys(drafted).forEach(key => {
			if (drafted[key] === null ? !(key in saved) : JSON.stringify(drafted[key]) === JSON.stringify(saved[key])) delete drafted[key];
		});
		const settings = Object.assign({}, draft.networkSettings || {});
		if (Object.keys(drafted).length) settings[part] = drafted;
		else delete settings[part];
		draft.networkSettings = Object.keys(settings).length ? settings : null;
		saveDraft();
		renderAll();
		renderNetworkTools();
		drawNetwork();
	}

	/** Puts a rail's section in a logical line, or out of any with null, by the rail chosen. */
	function setRailLogicalLine(index, id) {
		const key = netRailKey(index);
		draftSettingsPart("railLines", (drafted, saved) => {
			sectionOf(index).map(netRailKey).forEach(other => {
				if (other === key && id !== null) return;
				if (other in saved) drafted[other] = null;
				else delete drafted[other];
			});
			if (id !== null) drafted[key] = id;
		});
	}

	const newLogicalId = () => String(Date.now() * 1000 + Math.floor(Math.random() * 1000));

	/** A new logical line, or one changed: name, colour, connecting line; then what is done with its ID. */
	function logicalLineDialog(line, then) {
		const isNew = !line;
		openDialog(isNew ? tr("新建逻辑线路") : tr("逻辑线路：{0}", escapeHtml(line.name)), `
			<label>${tr("名称")} <input name="name" maxlength="64" value="${escapeHtml(line ? line.name : "")}" placeholder="${tr("例如 京沪线")}" required></label>
			<label>${tr("颜色")} <input type="color" name="color" value="${colorOf(line ? line.color : 0x3b82f6)}"></label>
			<label><input type="checkbox" name="connecting"${line && line.connecting ? " checked" : ""}> ${tr("联络线：所有运营线路都能走")}</label>
			<p class="hint">${tr("逻辑线路是轨道本身的线路，和运营线路分开：每段轨道最多属于一条；运营线路走它勾选的逻辑线路，没勾时走它停靠的站台所在的逻辑线路。")}</p>`, isNew ? tr("新建") : tr("确定"), form => {
			const name = form.name.value.trim();
			if (!name) return tr("写上名称");
			const id = isNew ? newLogicalId() : line.id;
			const value = { name, color: parseInt(form.color.value.slice(1), 16), connecting: form.connecting.checked };
			draftSettingsPart("logicalLines", drafted => { drafted[id] = value; });
			if (then) then(id);
		});
	}

	/** Takes a logical line away, with its sections and the operating lines' choices of it. */
	function deleteLogicalLine(line) {
		if (!confirm(tr("删除逻辑线路“{0}”？它的轨道不再属于任何逻辑线路，运营线路对它的勾选也去掉。应用前都在草稿里。", line.name))) return;
		draftSettingsPart("railLines", (drafted, saved) => {
			Object.entries(railLinesShown()).forEach(([key, id]) => {
				if (id !== line.id) return;
				if (key in saved) drafted[key] = null;
				else delete drafted[key];
			});
		});
		draftSettingsPart("lineTracks", (drafted, saved) => {
			Object.entries(Object.assign({}, saved, drafted)).forEach(([lineKey, ids]) => {
				if (ids && ids.includes(line.id)) drafted[lineKey] = ids.filter(id => id !== line.id);
			});
		});
		draftSettingsPart("logicalLines", drafted => { drafted[line.id] = null; });
	}

	/** The logical lines an operating line runs on: ticked, or none for those of its platforms. */
	function lineTracksDialog(lineKey) {
		const line = data.lines.find(each => each.key === lineKey);
		const tracks = Object.assign({}, savedSettings().lineTracks || {}, draftSettings().lineTracks || {});
		const chosen = tracks[lineKey] || [];
		const boxes = [...logicalLinesShown().values()].filter(each => !each.connecting).map(each => `<label class="owner-choice"><input type="checkbox" name="logical" value="${each.id}"${chosen.includes(each.id) ? " checked" : ""}><span class="swatch" style="background:${colorOf(each.color)}"></span>${escapeHtml(each.name)}</label>`).join("");
		openDialog(tr("能走的逻辑线路：{0}", escapeHtml(line ? shortName(line.name) : lineKey)), `
			<p>${tr("这条运营线路的各种别只走勾选的逻辑线路（和联络线、不属于任何逻辑线路的轨道）。都不勾时，走它停靠的站台所在的逻辑线路。")}</p>
			<div class="owner-choices">${boxes || `<p class="hint">${tr("还没有逻辑线路")}</p>`}</div>
			<p class="hint">${tr("改动先存为草稿，点“应用”后服务端重新算，走不了的线路会重新寻路。")}</p>`, tr("确定"), form => {
			const ticked = [...form.querySelectorAll("input[name=logical]:checked")].map(input => input.value);
			draftSettingsPart("lineTracks", drafted => { drafted[lineKey] = ticked.length ? ticked : null; });
		});
	}

	/**
	 * A first draft of logical lines from the owners worked out now: the sections owned by the same operating lines one
	 * logical line, named after them, a connecting line's own sections a connecting logical line; each operating line given
	 * the logical lines it owns, so it runs where it does now.
	 */
	function logicalDraftDialog() {
		if (!net.data || !net.data.owners || !net.data.owners.ready || !net.sectionRails) {
			ensureNetworkFor(net.mode || "TRAIN");
			alert(tr("全网配线的归属还没从服务端读来，稍等几秒再试。"));
			return;
		}
		const groups = new Map();
		net.sectionRails.forEach(rails => {
			const entry = net.ownerOf.get(rails[0]);
			if (!entry || !entry.owners.length) return;
			const key = entry.owners.slice().sort().join("\n");
			if (!groups.has(key)) groups.set(key, { owners: entry.owners.slice().sort(), rails: [] });
			groups.get(key).rails.push(rails[0]);
		});
		if (!groups.size) {
			alert(tr("现在没有任何轨道有归属，没有可以生成的初稿。"));
			return;
		}
		openDialog(tr("按现在的归属生成逻辑线路初稿"), `<p>${tr("按现在算出的归属，把归同一组运营线路的区段放进同一条逻辑线路（共 {0} 条），联络线自己的区段成为标了联络线的逻辑线路；每条运营线路勾上它现在归属的逻辑线路，寻路和现在一样。", groups.size)}</p>
			<p class="hint">${tr("放进草稿，应用后改按逻辑线路寻路。之后可以改名、合并、重新划分。")}</p>`, tr("生成"), () => {
			const lineIdsOf = new Map();
			const created = {}, railLines = {};
			let serial = 0;
			groups.forEach(group => {
				const id = String(Date.now() * 1000 + serial++);
				const connecting = group.owners.length === 1 && group.owners[0].startsWith("connecting:");
				const first = data.lines.find(each => each.key === group.owners[0]);
				created[id] = { name: group.owners.map(ownerText).join(tr("、")), color: connecting ? 0xE8891A : first ? first.color & 0xFFFFFF : 0x888888, connecting };
				group.rails.forEach(index => { railLines[netRailKey(index)] = id; });
				if (!connecting) group.owners.forEach(owner => {
					if (!lineIdsOf.has(owner)) lineIdsOf.set(owner, []);
					lineIdsOf.get(owner).push(id);
				});
			});
			draftSettingsPart("logicalLines", drafted => Object.assign(drafted, created));
			draftSettingsPart("railLines", drafted => Object.assign(drafted, railLines));
			draftSettingsPart("lineTracks", drafted => lineIdsOf.forEach((ids, lineKey) => { drafted[lineKey] = ids; }));
		});
	}

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
		if (state.view !== "network" || !inTimetable()) return;
		const ratio = devicePixelRatio || 1, w = netWrap.clientWidth, h = netWrap.clientHeight;
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
			g.font = "14px sans-serif";
			g.fillText(net.error || (d ? tr("游戏里还没有这种运输方式的轨道，或地图数据还没传来") : tr("正在读取…")), 20, 30);
			return;
		}
		const left = netWorldX(-NET_HIT_PX), right = netWorldX(w + NET_HIT_PX), top = netWorldZ(-NET_HIT_PX), bottom = netWorldZ(h + NET_HIT_PX);
		const visible = points => {
			for (let k = 0; k < points.length; k += 2) if (points[k] >= left && points[k] <= right && points[k + 1] >= top && points[k + 1] <= bottom) return true;
			// A long rail can cross the view with both ends outside it
			return points.length >= 4 && !(Math.max(points[0], points[points.length - 2]) < left || Math.min(points[0], points[points.length - 2]) > right || Math.max(points[1], points[points.length - 1]) < top || Math.min(points[1], points[points.length - 1]) > bottom);
		};
		const pickedRoutes = pickingRouteIds();
		const line = state.line, highlighted = (d.routes || []).filter(route => pickedRoutes ? pickedRoutes.has(route.id) : route.lineKey === line && (!state.route || state.route === route.id));
		const faded = highlighted.length > 0;
		const stroke = (points, color, width, dash) => {
			g.beginPath();
			g.moveTo(netX(points[0]), netZ(points[1]));
			for (let k = 2; k + 1 < points.length; k += 2) g.lineTo(netX(points[k]), netZ(points[k + 1]));
			g.strokeStyle = color;
			g.lineWidth = width;
			g.setLineDash(dash || []);
			g.stroke();
		};
		g.lineCap = "round";
		g.lineJoin = "round";
		g.globalAlpha = faded ? 0.45 : 1;
		const plain = css("--grid-strong"), ink = css("--ink");
		// Runs of track, each one kind and isolation, drawn exactly when zoomed in and as a schematic further out
		const chains = netChains();
		chains.forEach(chain => {
			const points = chainShape(chain);
			if (!visible(points)) return;
			// Stabling blocks (运用补完 step 4) brown and a little heavier, one train each
			stroke(points, chain.kind === 1 ? ink : chain.kind === 2 ? "#d9a520" : chain.kind === 3 ? "#9a5b2c" : plain, chain.kind === 1 ? 3 : chain.kind === 3 ? 2.5 : 1.5);
		});
		g.globalAlpha = 1;
		// The ownership layer (web layers step 2): each rail in its owners' colours, striped where several lines own it;
		// set by hand with a dark edge, dotted while only in the draft; open to every line by hand grey and dashed
		if (net.layer === "owners" && usesLogicalLines()) {
			// By logical line (2026-11-09): its colour, connecting lines dashed, drafted sections with a dark dotted edge, one
			// logical line chosen in the tree drawn over the others faded
			d.rails.forEach((rail, index) => {
				const points = net.railPoints[index];
				if (!visible(points)) return;
				const line = railLogicalLine(index), drafted = railLogicalDrafted(index);
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
				const shown = railOwnersShown(index);
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
			if (chain.status === 3) stroke(points, "#7a5fd0", 1.5, [2, 4]);
			else if (chain.status === 1) stroke(points, "#e8891a", 2.5, [6, 3]);
			else stroke(points, plain, 2, [3, 3]);
		});
		g.setLineDash([]);
		(d.boundaryNodes || []).forEach(index => {
			const n = d.nodes[index];
			g.fillStyle = "#d33";
			g.beginPath();
			g.arc(netX(n[0]), netZ(n[1]), 4, 0, Math.PI * 2);
			g.fill();
		});
		// The chosen line's routes along their paths: zoomed out, the runs of track they use as drawn
		if (net.scale < NET_EXACT_SCALE) {
			highlighted.forEach(route => {
				const runs = new Set();
				for (let i = 1; i < route.nodes.length; i++) {
					const index = net.byPair.get(Math.min(route.nodes[i - 1], route.nodes[i]) + "," + Math.max(route.nodes[i - 1], route.nodes[i]));
					if (index !== undefined) runs.add(net.railChain[index]);
				}
				runs.forEach(run => stroke(chainShape(chains[run]), colorOf(route.color), 4));
			});
		} else highlighted.forEach(route => {
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
			stroke(points, colorOf(route.color), 4);
		});
		if (net.hover >= 0 && net.hover < d.rails.length) stroke(net.scale < NET_EXACT_SCALE ? chainShape(chains[net.railChain[net.hover]]) : net.railPoints[net.hover], css("--sel"), 4);
		if (net.scale >= NET_STATION_SCALE) {
			g.fillStyle = ink;
			g.font = "12px sans-serif";
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
		if (net.scale < NET_EXACT_SCALE) {
			let bestChain = null, bestChainDistance = reach;
			netChains().forEach(chain => {
				const p = chainShape(chain);
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
		const run = net.scale < NET_EXACT_SCALE && net.chains ? net.chains[net.railChain[index]].rails.length : 1;
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
	const CONSOLE_POLL_MS = 2000;

	async function loadConsole() {
		try {
			consoleState.data = await fetchJson("api/console");
		} catch (e) {
			consoleState.data = null;
		}
		renderConsole();
		drawNetwork();
	}

	function toggleConsole(open) {
		consoleState.open = open;
		$("net-console-toggle").setAttribute("aria-pressed", String(open));
		$("net-console").hidden = !open;
		if (open && !consoleState.timer) {
			loadConsole();
			consoleState.timer = setInterval(() => { if (!document.hidden && state.view === "network" && inTimetable()) loadConsole(); }, CONSOLE_POLL_MS);
		} else if (!open && consoleState.timer) {
			clearInterval(consoleState.timer);
			consoleState.timer = null;
		}
		renderConsole();
		drawNetwork();
	}

	/** The trains of the rail system chosen, or all. */
	function consoleTrains() {
		const trains = consoleState.data && consoleState.data.trains || [];
		const system = consoleState.system ? systemByAnchor(consoleState.system) : null;
		return system ? trains.filter(train => system.routes.includes(train.route)) : trains;
	}

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
		const shapeOf = index => net.scale < NET_EXACT_SCALE && chains ? chainShape(chains[net.railChain[index]]) : net.railPoints[index];
		const trains = consoleTrains();
		const trainBlocks = new Set(trains.map(train => train.block).filter(Boolean));
		(consoleState.data.blocks || []).forEach(block => {
			if (!block.occupied && !trainBlocks.has(block.id)) return;
			const color = trainBlocks.has(block.id) ? "#e8a33a" : "#d9534f";
			block.rails.forEach(pair => {
				const index = consoleRail(pair);
				if (index >= 0) stroke(shapeOf(index), color, 6);
			});
		});
		g.font = "bold 12px sans-serif";
		g.textAlign = "center";
		trains.forEach(train => {
			const index = consoleRail(train.head);
			if (index < 0) return;
			const points = net.railPoints[index];
			// Halfway along the rail, between the points either side of its middle
			const half = points.length / 2, lower = Math.max(0, Math.floor((half - 1) / 2) * 2), upper = Math.min(points.length - 2, lower + 2);
			const x = netX((points[lower] + points[upper]) / 2), y = netZ((points[lower + 1] + points[upper + 1]) / 2);
			const text = consoleTrainName(train);
			g.fillStyle = train.id === consoleState.selected ? css("--sel") : train.late >= 300 ? "#d9534f" : css("--ink");
			g.fillText(text, x, y - 10);
		});
		g.textAlign = "start";
	}

	const consoleTrainName = train => train.slot < 0 ? tr("{0} 的车", shortName(train.sidingName) || tr("存车线")) : tr("{0} 车 {1}", shortName(train.sidingName) || tr("存车线"), train.slot + 1);

	/** What is going on with a train, as the console lists it. */
	function consoleTrainState(train) {
		const parts = [];
		// Out on the line with no duty, or a duty no longer there (2026-11-09): it takes track but runs no service
		if (train.noDuty) parts.push(tr("不在运用中（没有运用，或者运用已经改了）"));
		if (train.late >= 60) parts.push(tr("晚点 {0} 分钟", Math.floor(train.late / 60)));
		if (train.waiting >= 60) parts.push(tr("等进路已 {0} 分钟", Math.floor(train.waiting / 60)));
		if (train.dispatch & 1) parts.push(tr("扣停"));
		if (train.dispatch & 8) parts.push(tr("延长停站"));
		if (train.dispatch & 16) parts.push(tr("将在指定站折返"));
		if (train.dispatch & 2) parts.push(tr("下一站清客"));
		if (train.dispatch & 4) parts.push(tr("本段跑完回库"));
		if (train.skipping) parts.push(tr("跳站中"));
		if (train.turn === 2) parts.push(tr("已提前折返"));
		if (train.takeover === 2) parts.push(tr("已交给备车"));
		if (train.outOfService) parts.push(tr("不载客"));
		return parts.join("，");
	}

	function renderConsole() {
		const panel = $("net-console");
		if (!consoleState.open) return;
		const d = consoleState.data;
		const systems = (data && data.contingencySystems || []).filter(system => system.routes.length);
		const systemOptions = `<option value="">${tr("全部路网")}</option>` + systems.map(system => `<option value="${system.anchor}"${system.anchor === consoleState.system ? " selected" : ""}>${escapeHtml(systemTitle(system))}</option>`).join("");
		if (!d || !d.available) {
			panel.innerHTML = `<p><select id="console-system">${systemOptions}</select></p><p class="hint">${tr("正在向游戏要各列车的位置…")}</p>`;
		} else {
			const trains = consoleTrains().slice().sort((a, b) => (b.late - a.late) || a.id.localeCompare(b.id));
			const canEdit = data && data.canEdit;
			const rows = trains.map(train => {
				const held = (train.dispatch & 1) !== 0;
				const cmds = canEdit && !train.noDuty ? `<div class="cmds">
					<button data-console-cmd="${held ? "release" : "hold"}" data-train="${train.id}">${held ? tr("放行") : tr("扣停")}</button>
					<button data-console-extend="${train.id}">${tr("延长停站…")}</button>
					<button data-console-turn="${train.id}" data-route="${train.route}">${tr("提前折返…")}</button>
					<button data-console-cmd="clearOut" data-train="${train.id}">${tr("下一站清客")}</button>
					<button data-console-cmd="return" data-train="${train.id}">${tr("指定回库")}</button>
					<button data-console-clear-return="${train.id}">${tr("下一站清客后回库")}</button>
					${train.dispatch ? `<button data-console-cmd="cancel" data-train="${train.id}">${tr("撤销")}</button>` : ""}
				</div>` : "";
				return `<tr class="${train.id === consoleState.selected ? "sel" : ""}" data-console-train="${train.id}"><td><b>${escapeHtml(consoleTrainName(train))}</b> ${escapeHtml(shortName(train.routeName))}<br>
					<span class="hint">${train.next ? tr("下一站 {0}", escapeHtml(shortName(train.next))) : ""}${train.block ? tr("，区段 {0}", train.block.slice(-4)) : ""}</span><br>${escapeHtml(consoleTrainState(train))}${cmds}</td></tr>`;
			}).join("");
			panel.innerHTML = `<p><select id="console-system">${systemOptions}</select> <span class="hint">${tr("{0} 列车在线上{1}，{2} 秒前", trains.length, trains.some(train => train.noDuty) ? tr("（{0} 列不在运用中）", trains.filter(train => train.noDuty).length) : "", d.age || 0)}</span></p>
				<p class="hint">${tr("红：占用的信号区段　橙：有列车的区段")}${canEdit ? "" : tr("。没有编辑权限，只能看")}</p>
				<table>${rows || `<tr><td class="hint">${tr("没有车在线上")}</td></tr>`}</table>`;
		}
		$("console-system").addEventListener("change", e => { consoleState.system = e.target.value; renderConsole(); drawNetwork(); });
		panel.querySelectorAll("[data-console-train]").forEach(row => row.addEventListener("click", e => {
			if (e.target.closest("button")) return;
			consoleState.selected = row.dataset.consoleTrain;
			renderConsole();
			drawNetwork();
		}));
		panel.querySelectorAll("[data-console-cmd]").forEach(button => button.addEventListener("click", () => consoleCommand(button, { train: button.dataset.train, command: button.dataset.consoleCmd })));
		// Cleared at the next station, then back to the depot: the two commands at once (运用补完 step 3)
		panel.querySelectorAll("[data-console-clear-return]").forEach(button => button.addEventListener("click", async () => {
			await consoleCommand(null, { train: button.dataset.consoleClearReturn, command: "clearOut" });
			await consoleCommand(button, { train: button.dataset.consoleClearReturn, command: "return" });
		}));
		panel.querySelectorAll("[data-console-extend]").forEach(button => button.addEventListener("click", () => {
			openDialog(tr("延长停站"), `<label>${tr("多停")} <input type="number" name="seconds" min="1" max="1800" value="60"> ${tr("秒")}</label><p class="hint">${tr("正停在站上的就在这一站，正在跑的在下一站。")}</p>`, tr("发送"), form => {
				const seconds = Math.round(Number(form.seconds.value));
				if (!(seconds > 0) || seconds > 1800) return tr("要在 1 到 1800 秒之间");
				consoleCommand(null, { train: button.dataset.consoleExtend, command: "extend", seconds });
				return null;
			});
		}));
		panel.querySelectorAll("[data-console-turn]").forEach(button => button.addEventListener("click", () => {
			const r = data.routeById.get(button.dataset.route);
			const system = r ? systemOfRoute(r.id) : null;
			const stations = system ? system.stations || [] : (r ? r.stops.map(stop => ({ id: stop.station, name: stop.name })) : []);
			const options = stations.filter(station => station.id).map(station => `<option value="${station.id}">${escapeHtml(shortName(station.name))}</option>`).join("");
			openDialog(tr("提前折返"), `<label>${tr("在")} <select name="station">${options}</select> ${tr("折返")}</label><p class="hint">${tr("在那一站清客后空车折到对向站台，接着跑运用的下一段。同时下了“下一站清客”的，折返后空车回库。")}</p>`, tr("发送"), form => {
				if (!form.station.value) return tr("选一个车站");
				consoleCommand(null, { train: button.dataset.consoleTurn, command: "turn", station: form.station.value });
				return null;
			});
		}));
	}

	async function consoleCommand(button, operation) {
		if (button) button.disabled = true;
		try {
			await sendJson("api/apply", "POST", { operations: [Object.assign({ type: "dispatch" }, operation)] });
			if (button) button.textContent = tr("已发送");
			setTimeout(loadConsole, 600);
		} catch (e) {
			if (button) button.disabled = false;
		}
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
		picking = { mode: net.mode || "TRAIN", stops: [], legs: [], activeLeg: -1, color: "#3b82f6", requestId: 0 };
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
						g.font = "12px sans-serif";
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
			g.fillStyle = stop.pass ? picking.color : "#fff";
			g.font = "bold 11px sans-serif";
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

	/** The panel beside the map while picking: the stops in order with their legs, and finishing. */
	function pickingPanel() {
		const rows = picking.stops.map((stop, i) => {
			const leg = picking.legs[i], next = picking.stops[i + 1];
			const legText = !next ? "" : !leg ? tr("正在找路…") : leg.found ? tr("经 {0} 个节点{1}", leg.nodes.length, stop.waypoints.length ? tr("，途经 {0} 个点", stop.waypoints.length) : "") : tr("走不通：{0}", leg.failure);
			return `<li><b>${i + 1}</b> ${escapeHtml(stop.name)}${i > 0 && i < picking.stops.length - 1 ? ` <label class="hint"><input type="checkbox" data-pick-pass="${i}"${stop.pass ? " checked" : ""}> ${tr("通过")}</label>` : ""}
				<button class="link" data-pick-up="${i}" title="${tr("上移")}">↑</button><button class="link" data-pick-down="${i}" title="${tr("下移")}">↓</button><button class="link" data-pick-remove="${i}" title="${tr("删掉这一站")}">×</button>
				${next ? `<div class="${leg && !leg.found ? "err" : "hint"}">→ ${escapeHtml(legText)} <button class="link" data-pick-leg="${i}">${picking.activeLeg === i ? tr("选途经点中，点这里结束") : tr("选这一段的途经点")}</button></div>` : ""}</li>`;
		}).join("");
		const allFound = picking.stops.length >= 2 && picking.legs.length === picking.stops.length - 1 && picking.legs.every(leg => leg.found);
		return `<h2>${tr("新建线路")}</h2><div class="card">
			<p class="hint">${tr("在图上依次点车站；站里有多个站台时选一个。经过已选车站的线路会高亮。两站之间的路是服务端按游戏的寻路找的；要走别的路，选一段后在图上点节点作为途经点，再点一次去掉。")}</p>
			${rows ? `<ol class="pick-stops">${rows}</ol>` : `<p class="hint">${tr("还没选车站")}</p>`}
			${picking.error ? `<p class="err">${escapeHtml(picking.error)}</p>` : ""}
			<p><button data-pick-finish ${allFound ? "" : "disabled"}>${tr("填线名和种别…")}</button> <button data-pick-cancel>${tr("取消")}</button> <button class="link" data-pick-old>${tr("改用停站图新建…")}</button></p></div>`;
	}

	/** The last step: the line's name, its type and colour, then the route goes into the draft as a new line. */
	function finishPicking() {
		const stops = picking.stops;
		openDialog(tr("新建线路"), `
			<label>${tr("线路组名")} <input name="line" placeholder="${tr("例如 1号线")}"></label>
			<label>${tr("种别名")} <input name="type" placeholder="${tr("例如 普通")}"></label>
			<label>${tr("颜色")} <input type="color" name="color" value="${picking.color}"></label>
			<p class="hint">${escapeHtml(stops.map(stop => stop.name).join(" → "))}</p>`, tr("放进草稿"), form => {
			const lineName = form.line.value.trim(), type = form.type.value.trim();
			if (!lineName) return tr("写上线路组名，例如 1号线");
			if (data.lines.some(l => l.key === picking.mode + "|" + lineName.toLowerCase())) return tr("已经有这个线路组了，请用“新建 ▾ → 种别…”");
			if (!type) return tr("写上种别名，例如 普通");
			draft.created.push({
				key: "n" + Date.now().toString(36), source: "", mode: picking.mode, lineName, lineKey: picking.mode + "|" + lineName.toLowerCase(),
				name: `${shortName(stops[0].name)} → ${shortName(stops[stops.length - 1].name)}`, serviceType: type, color: parseInt(form.color.value.slice(1), 16),
				stops: stops.map((stop, k) => ({ platformId: stop.platformId, pass: stop.pass && k > 0 && k < stops.length - 1, waypoints: stop.waypoints.slice() })),
				services: [], table: 0, depot: "",
			});
			picking = null;
			saveDraft();
			renderAll();
		});
	}

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
	/**
	 * A depot's rules and named deadhead paths as drafted, or as the game has them: {rules: [...], paths: [...]}.
	 */
	function rulesOf(depot) {
		const drafted = draft.depotRules && draft.depotRules[depot.id];
		const value = drafted ? drafted.rules : depot.rules;
		return JSON.parse(JSON.stringify(value || { rules: [], paths: [] }));
	}

	/** The lines a depot runs, by key, with a name to show. */
	function depotLines(depot) {
		const lines = new Map();
		(depot.routes || []).forEach(id => {
			const r = data.routeById.get(id);
			if (r && !lines.has(r.lineKey)) lines.set(r.lineKey, shortName(r.line) || r.lineKey);
		});
		return [...lines.entries()];
	}

	/** Seconds as written in a field, or the empty string for none. */
	const secondsText = millis => millis >= 0 ? String(Math.round(millis / 1000)) : "";

	/** The rules being edited in the dialog, kept while the dialog is closed to choose on the map. */
	let rulesEditing = null;
	/**
	 * Choosing a rule's station or a deadhead path's way on the whole network map: the depot and siding, what is chosen,
	 * and the server's answer for it.
	 */
	let wayPick = null;

	function depotRulesDialog(depot, editing) {
		const value = editing || rulesOf(depot);
		rulesEditing = { depot: depot.id, value };
		const lines = depotLines(depot);
		const unset = depot.shortfall && depot.shortfall.entrancesUnset || [];
		const stationText = id => id && id !== "0" ? escapeHtml(stationNameOf(id)) : tr("（自动：只有一个出入口时）");
		// The tracks a rule's way goes by, as a path of its own, or the station of older rules (2026-11-09)
		const wayText = (pathId, entrance) => {
			const path = pathId && pathId !== "0" ? value.paths.find(each => each.id === pathId) : null;
			return path ? escapeHtml(tr("经 {0} 段轨道（{1}）", Math.floor(path.waypoints.length / 2), path.name || path.id)) : stationText(entrance);
		};
		const sidingOptions = selected => `<option value="0">${tr("车池里下一班最早要车的")}</option>` + depot.sidings.map(siding => `<option value="${siding.id}"${siding.id === selected ? " selected" : ""}>${escapeHtml(siding.name || siding.id)}</option>`).join("");
		const pathOptions = (selected, kind) => `<option value="0">${tr("最近车站后顺线路方向（默认）")}</option>` + value.paths.filter(path => path.kind === kind).map(path => `<option value="${path.id}"${path.id === selected ? " selected" : ""}>${escapeHtml(path.name || path.id)}</option>`).join("");
		const ruleRows = value.rules.map((rule, i) => `<fieldset data-rule="${i}"><legend>${tr("规则 {0}", i + 1)}</legend>
			<div>${tr("线路")}：${lines.map(([key, name]) => `<label><input type="checkbox" name="line" value="${escapeHtml(key)}"${rule.lines.includes(key) ? " checked" : ""}> ${escapeHtml(name)}</label>`).join("")} <span class="hint">${tr("都不勾 = 全部线路")}</span></div>
			<div>${tr("星期")}：${WEEKDAY_SHORT.map((day, d) => `<label><input type="checkbox" name="day" value="${d}"${rule.weekdays >> d & 1 ? " checked" : ""}> ${day}</label>`).join("")} <span class="hint">${tr("都不勾 = 每天")}</span></div>
			<div>${tr("出库")}：<b>${wayText(rule.outPath, rule.outEntrance)}</b> <button type="button" data-rule-pick="${i}" data-out="1">${tr("在图上点轨道…")}</button>
				${tr("回库")}：<b>${wayText(rule.backPath, rule.backEntrance)}</b> <button type="button" data-rule-pick="${i}" data-out="0">${tr("在图上点轨道…")}</button></div>
			<label>${tr("比出库路线用时再早发（秒）")} <input type="number" name="extraLead" min="0" max="3600" value="${secondsText(rule.extraLead)}"></label>
			<label>${tr("回哪条存车线")} <select name="backSiding">${sidingOptions(rule.backSiding)}</select></label>
			<label>${tr("出库路径")} <select name="outPath">${pathOptions(rule.outPath, "out")}</select></label>
			<label>${tr("回库路径")} <select name="backPath">${pathOptions(rule.backPath, "back")}</select></label>
			<label>${tr("等候分界（秒，留空用车站或全网的）")} <input type="number" name="waitThreshold" min="0" max="3600" value="${secondsText(rule.waitThreshold)}"></label>
			<button type="button" data-rule-remove="${i}">${tr("删除这条规则")}</button></fieldset>`).join("");
		const pathRows = value.paths.map((path, i) => `<fieldset data-path="${i}"><legend>${escapeHtml(path.name || tr("回送路径 {0}", i + 1))}</legend>
			<label>${tr("名字")} <input name="pathName" maxlength="32" value="${escapeHtml(path.name)}"></label>
			<label>${tr("种类")} <select name="pathKind"><option value="out"${path.kind === "out" ? " selected" : ""}>${tr("出库")}</option><option value="back"${path.kind === "back" ? " selected" : ""}>${tr("回库")}</option></select></label>
			<span class="hint">${tr("途经 {0} 个点", path.waypoints.length)}</span>
			<button type="button" data-path-pick="${i}">${tr("在图上画…")}</button> <button type="button" data-path-remove="${i}">${tr("删除")}</button></fieldset>`).join("");
		openDialog(tr("出入库规则：{0}", escapeHtml(shortName(depot.name))), `
			<p class="hint">${tr("按线路和星期定列车怎么出库、回库：从哪个车站出入（车厂接到线路上不止一个车站时必须指定）、比出库路线用时再早发多少、回哪条存车线、走哪条回送路径、两班之间等多久就离开站台去折返线。同一班次按“线路和星期都指定 > 只指定线路 > 只指定星期 > 都不指定”取一条规则。出口、回口和回送路径都在全网配线图上点选，点了就画出路线和用时。")}</p>
			${unset.length ? `<p class="err">${tr("这个车厂接到线路上不止一个车站，这些线路还没指定出入口，不给排运用：{0}", escapeHtml(unset.map(key => (lines.find(([k]) => k === key) || [key, key])[1]).join("、")))}</p>` : ""}
			<h3>${tr("规则")}</h3>${ruleRows || `<p class="hint">${tr("还没有规则：都按默认（最近车站后顺线路方向出入库）")}</p>`}
			<button type="button" data-rule-add>${tr("新增规则")}</button>
			<h3>${tr("回送路径")}</h3>${pathRows || `<p class="hint">${tr("还没有命名的回送路径")}</p>`}
			<button type="button" data-path-add="out">${tr("新增出库路径")}</button> <button type="button" data-path-add="back">${tr("新增回库路径")}</button>`, tr("放进草稿"), form => {
			readRulesForm(form, value);
			draft.depotRules = draft.depotRules || {};
			if (JSON.stringify(value) === JSON.stringify(depot.rules || { rules: [], paths: [] })) delete draft.depotRules[depot.id];
			else draft.depotRules[depot.id] = { rules: value };
			rulesEditing = null;
			saveDraft();
			renderAll();
		});
		const form = dialog.querySelector("form");
		form.addEventListener("click", e => {
			const button = e.target.closest("[data-rule-add], [data-rule-remove], [data-rule-pick], [data-path-add], [data-path-remove], [data-path-pick]");
			if (!button) return;
			readRulesForm(form, value);
			if (button.dataset.ruleAdd !== undefined) {
				value.rules.push({ id: String(Date.now()), lines: [], weekdays: 0, outEntrance: "0", backEntrance: "0", extraLead: 0, backSiding: "0", outPath: "0", backPath: "0", waitThreshold: -1 });
			} else if (button.dataset.ruleRemove !== undefined) {
				value.rules.splice(+button.dataset.ruleRemove, 1);
			} else if (button.dataset.pathAdd !== undefined) {
				value.paths.push({ id: String(Date.now()), name: "", kind: button.dataset.pathAdd, from: "0", to: "0", waypoints: [] });
			} else if (button.dataset.pathRemove !== undefined) {
				const removed = value.paths.splice(+button.dataset.pathRemove, 1)[0];
				value.rules.forEach(rule => {
					if (rule.outPath === removed.id) rule.outPath = "0";
					if (rule.backPath === removed.id) rule.backPath = "0";
				});
			} else if (button.dataset.rulePick !== undefined) {
				dialog.close();
				startWayPick(depot, { rule: +button.dataset.rulePick, out: button.dataset.out === "1" });
				return;
			} else if (button.dataset.pathPick !== undefined) {
				dialog.close();
				const path = value.paths[+button.dataset.pathPick];
				startWayPick(depot, { path: +button.dataset.pathPick, out: path.kind !== "back" });
				return;
			}
			dialog.close();
			depotRulesDialog(depot, value);
		});
	}

	/** What the dialog's fields say, into the rules being edited. */
	function readRulesForm(form, value) {
		form.querySelectorAll("[data-rule]").forEach(fieldset => {
			const rule = value.rules[+fieldset.dataset.rule];
			if (!rule) return;
			rule.lines = [...fieldset.querySelectorAll("input[name=line]:checked")].map(box => box.value);
			rule.weekdays = [...fieldset.querySelectorAll("input[name=day]:checked")].reduce((mask, box) => mask | 1 << +box.value, 0);
			const lead = fieldset.querySelector("input[name=extraLead]").value.trim();
			rule.extraLead = lead === "" ? 0 : Math.max(0, Math.min(3600, Math.round(Number(lead)) || 0)) * 1000;
			rule.backSiding = fieldset.querySelector("select[name=backSiding]").value;
			rule.outPath = fieldset.querySelector("select[name=outPath]").value;
			rule.backPath = fieldset.querySelector("select[name=backPath]").value;
			const wait = fieldset.querySelector("input[name=waitThreshold]").value.trim();
			rule.waitThreshold = wait === "" ? -1 : Math.max(0, Math.min(3600, Math.round(Number(wait)) || 0)) * 1000;
		});
		form.querySelectorAll("[data-path]").forEach(fieldset => {
			const path = value.paths[+fieldset.dataset.path];
			if (!path) return;
			path.name = fieldset.querySelector("input[name=pathName]").value.trim();
			path.kind = fieldset.querySelector("select[name=pathKind]").value;
		});
	}

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
			g.fillStyle = "#fff";
			g.font = `10px ${css("--font-num")}`;
			g.textAlign = "center";
			g.fillText(String(k + 1), netX(node[0]), netZ(node[1]) + 3);
		});
		g.restore();
	}

	/** The panel beside the map while choosing: what is chosen, the way found and its time, and finishing. */
	function wayPickPanel() {
		const depot = depotById(wayPick.depot);
		const result = wayPick.result;
		const choosingPath = wayPick.target.path !== undefined;
		const found = result && result.found;
		const entrances = result && result.entrances || [];
		const time = found && result.millis >= 0 ? durationText(result.millis) : "";
		return `<h2>${choosingPath ? tr("画回送路径") : wayPick.out ? tr("选出口") : tr("选回口")}</h2><div class="card">
			<p>${escapeHtml(shortName(depot ? depot.name : ""))} · ${tr("存车线")} ${escapeHtml((depot && depot.sidings.find(s => s.id === wayPick.siding) || {}).name || "")}</p>
			<p>${tr("这条存车线的出入口")}：${entrances.length ? entrances.map(id => escapeHtml(stationNameOf(id))).join("、") : tr("（还没算出）")}</p>
			${choosingPath ? `<p>${tr("途经 {0} 个点；点节点加入或去掉，点车站换终点。", wayPick.waypoints.length)}</p>` : `<p>${wayPick.out ? tr("运行图上从这些站发车（出库开到哪）") : tr("运行图上在这些站收车（从哪回库）")}：</p>
			<div>${(wayPick.targets || []).length ? wayPick.targets.map(each => `<button type="button" data-waypick-target="${escapeHtml(each.platform)}" aria-pressed="${each.platform === wayPick.platform}">${escapeHtml(shortName(each.name))} · ${wayPick.out ? tr("最早 {0}", clock(each.first)) : tr("最晚 {0}", clock(each.last))} · ${tr("{0} 班", each.count)}</button>`).join(" ") : `<span class="hint">${tr("这天运行图上没有这些线路的班次")}</span>`}</div>
			<p>${tr("经过的轨道：{0} 段（按点的顺序）", Math.floor(wayPick.waypoints.length / 2))} ${wayPick.waypoints.length ? `<button type="button" data-waypick-clear>${tr("清空")}</button>` : ""}</p>`}
			<p class="${result && !found ? "err" : ""}">${!result ? tr("正在计算…") : found ? tr("{0}用时 {1}", wayPick.out ? tr("出库") : tr("回库"), time) : tr("这样走不通")}</p>
			${wayPick.error ? `<p class="err">${escapeHtml(wayPick.error)}</p>` : ""}
			<button class="primary" data-waypick-done>${tr("用这个")}</button> <button data-waypick-cancel>${tr("取消")}</button></div>`;
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
	const canvas = $("canvas"), wrap = $("canvas-wrap"), ctx = canvas.getContext("2d");
	let layout = null, drawn = [], handles = [];

	// ---------- Other lines on this line's track (web layers step 3) ----------
	/**
	 * Services of other lines drawn on a line's running diagram where they run on its track: a leg between two of their
	 * stops, both stations of this line's diagram next to each other, whose rails between the two (their platforms when
	 * there are none between) are rails this line's routes run on too. Read only: they are changed in their own line.
	 */
	let showAccess = true;
	try { showAccess = localStorage.getItem("mtr-editor-show-access") !== "0"; } catch (e) {}
	let accessDrawn = [];
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

	/** Draws the other lines' services on this line's track, under its own, and keeps where for the tooltip. */
	function drawAccess(line, yOf, w) {
		accessDrawn = [];
		if (!showAccess) return;
		accessLegs(line).forEach(({ r, legs }) => {
			ctx.strokeStyle = colorOf(r.color);
			ctx.lineWidth = 1.2;
			ctx.setLineDash([5, 3]);
			ctx.globalAlpha = state.route ? 0.2 : 0.6;
			r.services.forEach(s => {
				if (s.suspended) return;
				const times = timesOf(s);
				let points = [];
				const flush = () => {
					if (points.length > 1 && points.some(([x]) => x >= LABEL_W - 40 && x <= w + 40)) {
						accessDrawn.push({ s, points });
						polyline(points);
					}
					points = [];
				};
				legs.forEach((leg, k) => {
					const before = legs[k - 1];
					if (!before || before.i !== leg.i - 1 || before.rowB !== leg.rowA) flush();
					if (!points.length) points.push([xOf(times[leg.i][1]), yOf(leg.rowA)]);
					points.push([xOf(times[leg.i + 1][0]), yOf(leg.rowB)]);
					const next = legs[k + 1];
					if (next && next.i === leg.i + 1 && times[leg.i + 1][1] !== times[leg.i + 1][0]) points.push([xOf(times[leg.i + 1][1]), yOf(leg.rowB)]);
				});
				flush();
			});
			ctx.setLineDash([]);
			ctx.globalAlpha = 1;
		});
	}

	function hitAccess(x, y) {
		let best = null, bestDistance = 5;
		accessDrawn.forEach(d => {
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
	/**
	 * The time axis at the zoom: lines as close as ten pixels apart, times at least 44 pixels apart, both on round times.
	 */
	function axisSteps() {
		const px = millis => millis / 60000 * state.pxPerMin;
		const grid = [10000, 30000, 60000, 300000, 600000, 1800000, 3600000].find(step => px(step) >= 10) || 3600000;
		const label = [10000, 30000, 60000, 300000, 600000, 1800000, 3600000, 7200000, 10800000].find(step => step >= grid && px(step) >= 44) || 10800000;
		return { grid, label };
	}
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
			ctx.fillText(data ? tr("没有线路") : tr("正在读取…"), 16, 32);
			return;
		}
		const { line, ys } = layout;
		const yOf = row => ys[row] - state.scrollY;

		// Hour lines, and finer ones the further the view is zoomed in, down to ten seconds; times on some of them
		const axis = axisSteps();
		const isHour = millis => mod(millis + data.dayStartMillis + data.offsetMillis, DAY) % 3600000 === 0;
		const first = Math.floor(state.viewStart / axis.grid) * axis.grid;
		ctx.font = `11px ${css("--font-num")}`;
		ctx.textAlign = "center";
		for (let millis = first; xOf(millis) < w; millis += axis.grid) {
			const x = xOf(millis);
			if (x < LABEL_W) continue;
			const hour = isHour(millis), labelled = mod(millis + data.dayStartMillis + data.offsetMillis, axis.label) === 0;
			ctx.strokeStyle = hour ? c.strong : c.grid;
			ctx.globalAlpha = hour || labelled ? 1 : 0.45;
			ctx.lineWidth = 1;
			ctx.beginPath();
			ctx.moveTo(Math.round(x) + 0.5, AXIS_H);
			ctx.lineTo(Math.round(x) + 0.5, h);
			ctx.stroke();
			ctx.globalAlpha = 1;
			if (labelled) {
				ctx.fillStyle = hour ? c.ink : c.muted;
				ctx.fillText(clock(millis, axis.label < 60000), x, AXIS_H - 10);
			}
		}

		// Now, as a dashed line, on the day shown when it is today
		const nowMillis = mod(Date.now() - data.dayStartMillis, DAY);
		const nowX = xOf(nowMillis);
		if (state.weekday === mod(nowDay() + FIRST_DAY_WEEKDAY, 7) && nowX >= LABEL_W && nowX <= w) {
			ctx.strokeStyle = c.warn;
			ctx.lineWidth = 1.5;
			ctx.setLineDash([6, 4]);
			ctx.beginPath();
			ctx.moveTo(Math.round(nowX) + 0.5, AXIS_H);
			ctx.lineTo(Math.round(nowX) + 0.5, h);
			ctx.stroke();
			ctx.setLineDash([]);
			ctx.fillStyle = c.warn;
			ctx.textAlign = "left";
			ctx.fillText(tr("现在 {0}", clock(nowMillis)), nowX + 4, AXIS_H + 12);
			ctx.textAlign = "center";
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
			if (row.sectionStart) {
				const between = Math.round((yOf(i - 1) + yOf(i)) / 2) + 0.5;
				ctx.strokeStyle = c.strong;
				ctx.setLineDash([6, 4]);
				ctx.beginPath();
				ctx.moveTo(0, between);
				ctx.lineTo(w, between);
				ctx.stroke();
				ctx.setLineDash([]);
			}
		});

		// Services, the selected one last so it's on top
		ctx.beginPath();
		ctx.rect(LABEL_W, AXIS_H, w - LABEL_W, h - AXIS_H);
		ctx.clip();
		// Other lines' services on this line's track, under its own (web layers step 3)
		drawAccess(line, yOf, w);
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
			// Being dragged along the time axis: drawn where it would go
			const moving = state.move && state.move.ids.includes(s.id) ? state.move.shift : 0;
			const times = moving ? timesOf(s).map(([a, b]) => [a + moving, b + moving]) : timesOf(s);
			if (xOf(times[times.length - 1][1]) < LABEL_W - 40 || xOf(times[0][0]) > w + 40) return;
			const points = [];
			const [firstServed, lastServed] = servedStops(s);
			s.route.rows.forEach((row, i) => {
				if (i < firstServed || i > lastServed) return;
				const leaveRow = s.route.leaveRows[i];
				points.push([xOf(times[i][0]), yOf(row)]);
				// Going on in another section: straight across to the station's row there as it arrives
				if (leaveRow !== row) points.push([xOf(times[i][0]), yOf(leaveRow), true]);
				if (times[i][1] !== times[i][0] && i < lastServed) points.push([xOf(times[i][1]), yOf(leaveRow)]);
			});
			if (!points.length) return;
			drawn.push({ s, points });
			const isSelected = s.id === state.selected || s.route.id === state.selectedRoute || inRange(s);
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
			// A service no train of the depots running it by duties runs: thin and grey, or red when those are picked out;
			// one with a train in the route's colour, a little wider. Colour, not dashes, so dense diagrams still show it
			const noTrain = !s.suspended && s.route.hasTimes && diagramRoutes.has(s.route.id) && !assigned.has(key);
			const covered = !s.suspended && !noTrain && s.route.hasTimes && diagramRoutes.has(s.route.id);
			// Grey for a service no train ran in the simulated day
			ctx.strokeStyle = sim.on && sim.notRun.has(key) ? c.muted : noTrain ? (showNoTrain ? c.warn : c.muted) : colorOf(s.route.color);
			ctx.lineWidth = isSelected ? 2.5 : noTrain ? 1 : covered ? 2 : 1.5;
			// Dashed: no times yet, or not running (faint too)
			ctx.setLineDash(!s.route.hasTimes ? [4, 3] : s.suspended ? [2, 4] : []);
			if (s.suspended) {
				ctx.strokeStyle = c.muted;
				ctx.globalAlpha = Math.min(ctx.globalAlpha, 0.45);
			}
			if (noTrain && !showNoTrain) ctx.globalAlpha = Math.min(ctx.globalAlpha, 0.35);
			if (covered && showNoTrain) ctx.globalAlpha = Math.min(ctx.globalAlpha, 0.25);
			polyline(points);
			ctx.setLineDash([]);
			ctx.globalAlpha = dim ? 0.25 : 1;
			// Set on the running diagram: a short dashed run from or to the depot, with its name
			[[s.deployOrder, points[0], -1], [s.returnOrder, points[points.length - 1], 1]].forEach(([order, [px, py], side]) => {
				if (!order) return;
				ctx.strokeStyle = colorOf(s.route.color);
				ctx.lineWidth = 1;
				ctx.setLineDash([3, 3]);
				ctx.beginPath();
				ctx.moveTo(px, py);
				ctx.lineTo(px + side * 14, py + 10);
				ctx.stroke();
				ctx.setLineDash([]);
				ctx.fillStyle = c.muted;
				ctx.font = `11px ${css("--font-ui")}`;
				ctx.textAlign = side < 0 ? "right" : "left";
				ctx.fillText(side < 0 ? orderDepotName(order) + " →" : "→ " + orderDepotName(order), px + side * 16, py + 20);
			});
			// Where the train carries on onto another route, or goes back to the depot
			const [ex, ey] = points[points.length - 1];
			if (!s.returnOrder && (s.link || s.returns && !loopNext.has(s.id))) {
				const next = s.link && data.routeById.get(s.link.route);
				ctx.fillStyle = s.link ? c.ink : c.muted;
				ctx.font = `11px ${css("--font-ui")}`;
				ctx.textAlign = "left";
				ctx.fillText(s.link ? "→ " + (next ? shortName(next.name) : "?") : tr("回库"), ex + 4, ey + (s.route.rows[0] < s.route.rows[s.route.rows.length - 1] ? 12 : -4));
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
					const x = xOf(t[1]), y = yOf(s.route.leaveRows[i]);
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
		drawTerminalLinks(line, yOf, c, w);
		// The depots' rows and the deadheads out and back, unless hidden (2026-11-09)
		if (showDepots) drawDepotLegs(line, yOf, c, w, layout.ys.length > 1 ? layout.ys[1] - layout.ys[0] : MIN_ROW_GAP);
		else legsDrawn = [];
		drawSimulated(line, yOf, c, w);
		if (slotPoints.length) {
			ctx.fillStyle = c.warn;
			ctx.font = `bold 11px ${css("--font-ui")}`;
			ctx.textAlign = "right";
			ctx.fillText(`${tr("车 {0}", dutyNumber(depotById(state.slot.depot), state.slot.siding, state.slot.index))}`, slotPoints[0].start[0] - 4, slotPoints[0].start[1] - 4);
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
			ctx.fillStyle = row.repeat ? c.muted : c.ink;
			ctx.fillText(fitText((row.repeat ? "↔ " : "") + shortName(row.name), LABEL_W - 16), LABEL_W - 8, y);
		});
		ctx.textBaseline = "alphabetic";
		// Redo the time labels the station column covered
		ctx.font = `11px ${css("--font-num")}`;
		ctx.textAlign = "center";
		for (let millis = first; xOf(millis) < w; millis += axis.grid) {
			const x = xOf(millis);
			if (x < LABEL_W || mod(millis + data.dayStartMillis + data.offsetMillis, axis.label) !== 0) continue;
			ctx.fillStyle = isHour(millis) ? c.ink : c.muted;
			ctx.fillText(clock(millis, axis.label < 60000), x, AXIS_H - 10);
		}
	}

	/**
	 * A line through points. Where a route goes on in another section of the stations, the step between the station's
	 * two rows is a dashed vertical line.
	 */
	function polyline(points) {
		ctx.beginPath();
		points.forEach(([x, y, jump], i) => i && !jump ? ctx.lineTo(x, y) : ctx.moveTo(x, y));
		ctx.stroke();
		if (!points.some(point => point[2])) return;
		ctx.save();
		ctx.lineWidth = Math.min(ctx.lineWidth, 1.5);
		ctx.setLineDash([5, 4]);
		ctx.beginPath();
		points.forEach(([x, y, jump], i) => {
			if (!i || !jump) return;
			ctx.moveTo(points[i - 1][0], points[i - 1][1]);
			ctx.lineTo(x, y);
		});
		ctx.stroke();
		ctx.restore();
	}

	function fitText(text, width) {
		if (ctx.measureText(text).width <= width) return text;
		let t = text;
		while (t.length > 1 && ctx.measureText(t + "…").width > width) t = t.slice(0, -1);
		return t + "…";
	}

	/** The service whose line passes closest to a point, within a few pixels. */
	// The trains' runs out of their depots and back drawn last, to find the duty clicked
	let legsDrawn = [];

	/**
	 * Each depot running by duties as a row beside the station it joins the line at, and each duty of the day shown
	 * out from it to its first stop and back from its last (decoupling document 5d, first step): out leaving
	 * the first stop's time less the time from the siding and 15 seconds, as the depot sends trains out; back from the
	 * last stop's arrival plus the time to the siding (DepotLegs from the server). Only drawn; nothing runs by it.
	 */
	/**
	 * How each train goes on at the end of a route: a line along the station's row while it stands at the platform, dashed
	 * to another platform it changes to, or out to a turn back rail beside the row and back while it waits there; the
	 * player's choice thicker, the nearest (inferred) fainter, red where the platform is taken.
	 */
	function drawTerminalLinks(line, yOf, c, w) {
		const pairs = terminalNow(), inLine = new Set(line.routes.map(r => r.id));
		ctx.save();
		pairs.next.forEach(pairing => {
			const from = findService(pairing.from.id), to = findService(pairing.to.id);
			if (!from || !to || !inLine.has(from.route.id) || from.suspended) return;
			const fromRow = from.route.rows[pairing.from.last], toRow = inLine.has(to.route.id) ? to.route.rows[0] : fromRow;
			const arrive = pairing.from.times[pairing.from.last][0], leave = pairing.to.times[0][1];
			if (xOf(leave) < LABEL_W || xOf(arrive) > w) return;
			const y1 = yOf(fromRow), y2 = yOf(toRow);
			ctx.globalAlpha = pairing.source === "nearest" ? 0.45 : 1;
			ctx.strokeStyle = pairing.conflict ? c.warn : colorOf(from.route.color);
			ctx.lineWidth = pairing.source === "manual" ? 3 : 2;
			ctx.beginPath();
			if (pairing.kind === "same" && pairing.loop && inLine.has(to.route.id)) {
				// A loop going round again (2026-11-09): its reference station is a row at either end of the diagram, the train
				// standing at the last row and going on from the first, so the stand is drawn on both, as one line wrapping round
				const yFirst = yOf(to.route.rows[0]);
				ctx.moveTo(xOf(arrive), y1);
				ctx.lineTo(xOf(leave), y1);
				ctx.moveTo(xOf(arrive), yFirst);
				ctx.lineTo(xOf(leave), yFirst);
				ctx.stroke();
			} else if (pairing.kind === "same") {
				ctx.moveTo(xOf(arrive), y1);
				ctx.lineTo(xOf(leave), y1);
				ctx.stroke();
			} else if (pairing.kind === "change") {
				ctx.setLineDash([5, 4]);
				ctx.moveTo(xOf(pairing.from.times[pairing.from.last][1]), y1);
				ctx.lineTo(xOf(pairing.to.times[0][0]), y2);
				ctx.stroke();
				ctx.setLineDash([]);
			} else {
				// Out to the rail beside the row, waiting there, and back to the platform
				const yRail = y1 + (y1 > AXIS_H + 20 ? -7 : 7);
				ctx.setLineDash([3, 3]);
				ctx.moveTo(xOf(pairing.from.times[pairing.from.last][1]), y1);
				ctx.lineTo(xOf(pairing.railStart), yRail);
				ctx.moveTo(xOf(pairing.railEnd), yRail);
				ctx.lineTo(xOf(pairing.to.times[0][0]), y2);
				ctx.stroke();
				ctx.setLineDash([]);
				ctx.beginPath();
				ctx.moveTo(xOf(pairing.railStart), yRail);
				ctx.lineTo(xOf(pairing.railEnd), yRail);
				ctx.stroke();
			}
			if (state.pxPerMin >= 3 && (pairing.moved || pairing.kind !== "same")) {
				ctx.fillStyle = ctx.strokeStyle;
				ctx.font = `10px ${css("--font-num")}`;
				ctx.textAlign = "left";
				ctx.fillText(pairing.kind === "hold" ? tr("折返线") : platformText(pairing.kind === "same" ? pairing.platform : pairing.to.platforms[0]), xOf(arrive) + 2, y1 - 3);
			}
		});
		ctx.restore();
	}

	function drawDepotLegs(line, yOf, c, w, unit) {
		legsDrawn = [];
		const lineRoutes = new Set(line.routes.map(r => r.id));
		const rowOfStation = id => line.rows.findIndex(row => row.id === id && !row.repeat);
		const depotRows = new Map();
		ctx.save();
		ctx.font = `10px ${css("--font-num")}`;
		data.depots.forEach(depot => {
			const duties = dutiesOf(depot);
			if (duties.mode !== "diagram") return;
			depot.sidings.forEach(siding => {
				const legs = siding.legs, slots = duties.sidings[siding.id] || [];
				if (!legs || !slots.length) return;
				const entranceRow = rowOfStation(legs.entrance);
				slots.forEach((slot, index) => {
					const day = dutyOn(slot, state.weekday);
					if (!day || !day.segments.length) return;
					const first = day.segments[0], firstService = segmentService(first);
					const last = day.segments[day.segments.length - 1], lastService = segmentService(last);
					if (!firstService || !lineRoutes.has(firstService.route.id)) return;
					// The depot's row: beside the station it joins the line at, or the first stop's station
					const baseRow = entranceRow >= 0 ? entranceRow : firstService.route.rows[first.from];
					if (baseRow === undefined || baseRow < 0) return;
					const side = baseRow < line.rows.length / 2 ? -1 : 1;
					const depotY = Math.max(AXIS_H + 4, yOf(baseRow) + side * Math.min(DEPOT_ROW_ROOM - 8, Math.max(12, unit * 0.45)));
					const key = depot.id + ":" + baseRow;
					if (!depotRows.has(key)) depotRows.set(key, { y: depotY, name: shortName(depot.name) || tr("车厂") });
					const active = state.slot && state.slot.depot === depot.id && state.slot.siding === siding.id && state.slot.index === index;
					const sidingName = shortName(siding.name) || tr("存车线");
					const deadheads = deadheadTimes(siding, day);
					// A deadhead as planned, blue where set for the duty alone (运用补完 step 3), moved while being dragged
					const style = exception => {
						ctx.strokeStyle = active ? c.warn : exception ? c.sel : c.muted;
						ctx.lineWidth = active || exception ? 2 : 1;
						ctx.setLineDash([4, 3]);
					};
					// Out to the first stop
					if (deadheads.out) {
						const dragged = drag && drag.deadhead && drag.deadhead.depot === depot.id && drag.deadhead.siding === siding.id && drag.deadhead.index === index ? drag.deadhead.shift || 0 : 0;
						const leave = deadheads.out.leave + dragged;
						const a = [xOf(leave), depotY], b = [xOf(deadheads.out.arrive), yOf(deadheads.out.row)];
						style(deadheads.out.exception || dragged !== 0);
						ctx.beginPath();
						ctx.moveTo(...a);
						ctx.lineTo(...b);
						ctx.stroke();
						legsDrawn.push({ a, b, kind: "out", depot: depot.id, siding: siding.id, index, text: `${deadheads.out.number} ${tr("出库 {0} ← {1} {2}", clock(leave, true), shortName(depot.name), sidingName)}${deadheads.out.exception ? tr("（这份运用的例外）") : ""}` });
						if (state.pxPerMin >= 3) {
							ctx.fillStyle = ctx.strokeStyle;
							ctx.textAlign = "right";
							ctx.fillText(`${deadheads.out.number} ${clock(leave)}`, a[0] - 2, depotY + (side < 0 ? -2 : 9));
						}
					}
					// Back from the last stop, unless the train stays on the line
					if (deadheads.back && lineRoutes.has(lastService.route.id)) {
						const a = [xOf(deadheads.back.leave), yOf(deadheads.back.row)], b = [xOf(deadheads.back.home), depotY];
						style(deadheads.back.exception);
						ctx.beginPath();
						ctx.moveTo(...a);
						ctx.lineTo(...b);
						ctx.stroke();
						legsDrawn.push({ a, b, kind: "back", depot: depot.id, siding: siding.id, index, text: `${deadheads.back.number} ${tr("回库 {0} → {1} {2}", clock(deadheads.back.home, true), shortName(depot.name), sidingName)}${deadheads.back.exception ? tr("（这份运用的例外）") : ""}` });
						if (state.pxPerMin >= 3) {
							ctx.fillStyle = ctx.strokeStyle;
							ctx.textAlign = "left";
							ctx.fillText(`${deadheads.back.number} ${clock(deadheads.back.home)}`, b[0] + 2, depotY + (side < 0 ? -2 : 9));
						}
					}
					ctx.setLineDash([]);
				});
			});
		});
		// The depots' rows: a faint line along the diagram and the depot's name by the station names
		depotRows.forEach(({ y, name }) => {
			ctx.strokeStyle = c.grid;
			ctx.lineWidth = 1;
			ctx.setLineDash([1, 4]);
			ctx.beginPath();
			ctx.moveTo(LABEL_W, y);
			ctx.lineTo(w, y);
			ctx.stroke();
			ctx.setLineDash([]);
			ctx.fillStyle = c.muted;
			ctx.textAlign = "right";
			ctx.fillText("▤ " + name, LABEL_W - 6, y + 3);
		});
		ctx.restore();
	}

	/** The duty whose run out of its depot or back is at this point, or null. */
	function hitDepotLeg(x, y) {
		let best = null, bestDistance = 6;
		legsDrawn.forEach(leg => {
			const distance = segmentDistance(x, y, leg.a, leg.b);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = leg;
			}
		});
		return best;
	}

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
		if (picking && data) parts.push(pickingPanel());
		if (wayPick && data) parts.push(wayPickPanel());
		// The network view has no inspector: the panel goes over its map, with the buttons to keep or drop the choice
		const netPanel = $("net-waypick");
		if (netPanel) {
			const show = Boolean(wayPick && data && state.view === "network");
			netPanel.hidden = !show;
			netPanel.innerHTML = show ? wayPickPanel() : "";
		}
		if (state.slot && data) parts.push(dutyPanel());
		if (s) {
			const next = s.link && data.routeById.get(s.link.route);
			parts.push(`<h2>${tr("选中的班次")}</h2><div class="card"><div class="title"><span class="swatch" style="display:inline-block;width:12px;height:4px;background:${colorOf(s.route.color)}"></span>${escapeHtml(routeTitle(s.route))}</div>
				<dl class="kv"><dt>${tr("班次")}</dt><dd>${clock(s.start)}</dd><dt>${tr("表")}</dt><dd>${escapeHtml(s.table.name || tr("表 ") + (s.tableIndex + 1))}</dd>
				${s.link ? `<dt>${tr("接续")}</dt><dd>${escapeHtml(next ? routeTitle(next) : "?")} ${clock(mod(s.link.toService - data.dayStartMillis, DAY))}</dd>` : ""}
				${s.returns ? `<dt>${tr("之后")}</dt><dd>${tr("回库")}</dd>` : ""}</dl>${stopsHtml(s)}</div>`);
		}
		if (sim.on && data) parts.push(simPanel(currentLine()));
		const loopRoute = s && isLoop(s.route) ? s.route : state.route && data && data.routeById.get(state.route) && isLoop(data.routeById.get(state.route)) ? data.routeById.get(state.route) : null;
		if (loopRoute) parts.push(loopPanel(loopRoute));
		const line = currentLine();
		if (line) {
			parts.push(`<h2>${tr("线路")}</h2><div class="card"><div class="title">${escapeHtml(shortName(line.name))}${line.routes.length > 1 ? ` <button type="button" class="link" data-line-diagram title="${tr("整个线路组的配线图，分支画在下面")}">${tr("线路组配线图")}</button>` : ""}</div><dl class="kv">${line.routes.map(r => `<dt>${escapeHtml(routeTitle(r))}</dt><dd>${tr("{0} 班", r.services.length)}${coverageText(r)} <button type="button" class="link" data-route-diagram="${r.id}" title="${tr("整条线路的配线图")}">${tr("配线图")}</button></dd>`).join("")}</dl></div>`);
			// Tracks that don't run along the rails, which make the diagram jump about, are listed until they are put right
			const troubled = line.routes.map(r => [r, routeProblems(r)]).filter(([, problems]) => problems.length);
			if (troubled.length) parts.push(`<h2>${tr("股道问题")}</h2><div class="card">${troubled.map(([r, problems]) => `<div class="title">${escapeHtml(routeTitle(r))}</div><ul class="warn">${problems.map(p => `<li class="${p.level}">${escapeHtml(p.text)}</li>`).join("")}</ul>`).join("")}<p class="hint">${tr("右键线路“编辑停站…”在连通图上改股道。")}</p></div>`);
		}
		parts.push(`<p class="hint">${tr("滚轮缩放时间轴，拖空白处平移，Shift + 滚轮上下移动。")}<br>${tr("按住一条线左右拖，平移这一班；Shift 点选一段、Ctrl 点选加入或剔除一班（Ctrl+Shift 加入或剔除一段），拖其中任一条一起平移；按住 Alt 拖按秒取整。右键一条线可以选中它和之后的全部班次。")}<br>${tr("单击一条线选中这一班，拖它停站末端的方块只改这一班（例外，蓝色）。双击选中同一种别同一方向的全部班次，拖方块改整张表的停站时间。")}<br>${tr("按运用跑的车厂：线路色较粗的线有车跑，灰色细线没有车；顶栏“没车的班次”把没车的标成红色。")}<br>${tr("时刻表里点格子直接输入，回车只改这一班，Shift+回车连之后的全部班次一起改；右键可以设通过、待避、推到之后的班次；右键班次表头可以把这一班各站的时刻改动整列同步到之后的每一班。改动先存为草稿，点顶栏的“应用”才发给游戏。")}</p>`);
		inspector.innerHTML = parts.join("");
	}

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
	const SIM_KINDS = { LATE: "晚点", WAITING_FOR_PLATFORM: "等站台", HELD_ON_LINE: "区间扣停", LONG_DWELL: "站内久停", DEADLOCK: "锁死", NOT_RUN: "未开行" };
	// checkDepot is the depot whose generated duties the simulated day checks, whose routes are reported on as well
	const sim = { on: false, request: 0, state: "idle", result: null, trips: new Map(), notRun: new Set(), listed: [], madeAt: 0, line: null, weekday: null, timer: null, polling: false, notSimulated: 0, ignored: 0, missingPaths: 0, scopeDepots: 0, allDepots: 0, checkDepot: null, fromNow: false };
	// Only today can carry on from the trains as they are in the game now (G0)
	const simFromNow = () => sim.fromNow && state.weekday === mod(nowDay() + FIRST_DAY_WEEKDAY, 7);

	function setSimulation(on) {
		sim.on = on;
		$("btn-simulate").setAttribute("aria-pressed", String(on));
		$("btn-resimulate").hidden = !on;
		clearTimeout(sim.timer);
		sim.timer = null;
		if (on) {
			simulate();
		} else {
			Object.assign(sim, { state: "idle", result: null, trips: new Map(), notRun: new Set(), listed: [], checkDepot: null });
		}
		renderSimStatus();
		draw();
		renderInspector();
	}

	/** Simulates again a moment after the draft, day or line changes, so a run of changes is only simulated once. */
	function scheduleSimulation() {
		if (!sim.on) return;
		clearTimeout(sim.timer);
		sim.timer = setTimeout(simulate, 2000);
	}

	async function simulate() {
		clearTimeout(sim.timer);
		sim.timer = null;
		const line = currentLine(), checkDepot = sim.checkDepot && depotById(sim.checkDepot);
		if (!sim.on || !line && !checkDepot) return;
		sim.line = state.line;
		sim.weekday = state.weekday;
		const routes = new Set(line ? line.routes.map(r => r.id) : []);
		if (checkDepot) checkDepot.routes.forEach(id => routes.add(id));
		try {
			const response = await sendJson("api/simulate", "POST", { weekday: state.weekday, fromNow: simFromNow(), routes: [...routes], operations: draftOperations().operations });
			if (response.error) {
				sim.state = response.error;
			} else {
				sim.request = response.request;
				sim.state = "running";
			}
		} catch (e) {
			sim.state = "error";
		}
		renderSimStatus();
		pollSimulation();
	}

	async function pollSimulation() {
		if (sim.polling) return;
		sim.polling = true;
		try {
			while (sim.on && sim.state === "running") {
				await new Promise(resolve => setTimeout(resolve, 500));
				const status = await fetchJson("api/simulate");
				// Another page asked since, or this request's answer hasn't come yet
				if (status.request > sim.request) sim.state = "idle";
				if (status.request !== sim.request || status.state === "running") continue;
				Object.assign(sim, { state: status.state, notSimulated: status.notSimulated, ignored: status.ignored, missingPaths: status.missingPaths || 0, scopeDepots: status.scopeDepots || 0, allDepots: status.allDepots || 0 });
				if (status.state === "done" && status.result) setSimResult(status.result);
			}
		} catch (e) {
			sim.state = "error";
		}
		sim.polling = false;
		renderSimStatus();
		draw();
		renderInspector();
		renderBanner();
	}

	function setSimResult(result) {
		sim.result = result;
		sim.madeAt = Date.now();
		sim.trips = new Map(result.trips.map(trip => [trip.route + "@" + trip.service, trip]));
		sim.notRun = new Set(result.problems.filter(problem => problem.kind === "NOT_RUN").map(problem => problem.route + "@" + problem.service));
	}

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
		$("btn-sim-now").hidden = !sim.on || state.weekday !== mod(nowDay() + FIRST_DAY_WEEKDAY, 7);
		$("btn-sim-now").setAttribute("aria-pressed", String(sim.fromNow));
	}

	// A stop as [stop, arrival, departure, planned arrival, planned departure, passed], times in the operating day or -1
	const simLate = stop => Math.max(stop[1] >= 0 && stop[3] >= 0 ? stop[1] - stop[3] : 0, stop[2] >= 0 && stop[4] >= 0 ? stop[2] - stop[4] : 0);
	const formatDuration = millis => millis >= 60000 ? tr("{0} 分 {1} 秒", Math.floor(millis / 60000), Math.round(millis % 60000 / 1000)) : tr("{0} 秒", Math.round(millis / 1000));

	/** Where each service of the line ran in the simulated day, dashed over the plan, red where it was late. */
	function drawSimulated(line, yOf, c, w) {
		if (!sim.on || !sim.result) return;
		const routes = new Map(line.routes.map(r => [r.id, r]));
		ctx.lineWidth = 1.5;
		ctx.setLineDash([5, 3]);
		sim.trips.forEach(trip => {
			const r = routes.get(trip.route);
			if (!r) return;
			const points = [];
			trip.stops.forEach(stop => {
				if (stop[0] < 0 || stop[0] >= r.rows.length) return;
				const y = yOf(r.rows[stop[0]]), leaveY = yOf(r.leaveRows[stop[0]]), late = simLate(stop) > SIM_LATE_MILLIS;
				if (stop[1] >= 0) points.push([xOf(stop[1]), y, late]);
				if (stop[1] >= 0 && leaveY !== y) points.push([xOf(stop[1]), leaveY, late]);
				if (stop[2] >= 0 && stop[2] !== stop[1]) points.push([xOf(stop[2]), leaveY, late]);
			});
			if (points.length < 2 || points[points.length - 1][0] < LABEL_W - 40 || points[0][0] > w + 40) return;
			ctx.globalAlpha = state.route && r.id !== state.route ? 0.25 : 0.9;
			for (let i = 1; i < points.length; i++) {
				ctx.strokeStyle = points[i][2] ? c.warn : c.ink;
				ctx.beginPath();
				ctx.moveTo(points[i - 1][0], points[i - 1][1]);
				ctx.lineTo(points[i][0], points[i][1]);
				ctx.stroke();
			}
		});
		ctx.setLineDash([]);
		ctx.globalAlpha = 1;
	}

	function simTooltip(s) {
		if (!sim.on || !sim.result) return "";
		const key = s.route.id + "@" + s.service, trip = sim.trips.get(key);
		if (!trip) return `<div class="hint">${sim.notRun.has(key) ? tr("模拟：没有车跑这一班") : ""}</div>`;
		const late = trip.stops.reduce((most, stop) => Math.max(most, simLate(stop)), 0);
		return `<div class="hint">${late > SIM_LATE_MILLIS ? tr("模拟：最多晚 {0}", formatDuration(late)) : tr("模拟：准点")}</div>`;
	}

	/** How the depot's generated duties ran in the simulated day, in a line, or nothing if they aren't being checked. */
	function dutyCheckText(depot) {
		if (!sim.on || !depot || sim.checkDepot !== depot.id) return "";
		if (sim.state === "running") return tr("内核验证中…");
		if (!sim.result) return "";
		const routes = new Set(depot.routes);
		const count = kind => sim.result.problems.filter(problem => problem.kind === kind && routes.has(problem.route)).length;
		const parts = [];
		if (sim.missingPaths) parts.push(tr("{0} 种运用找不到路径", sim.missingPaths));
		if (count("NOT_RUN")) parts.push(tr("未开行 {0} 班", count("NOT_RUN")));
		if (count("LATE")) parts.push(tr("晚点 {0} 班", count("LATE")));
		const deadlocks = sim.result.problems.filter(problem => problem.kind === "DEADLOCK").length;
		if (deadlocks) parts.push(tr("锁死 {0} 次", deadlocks));
		return parts.length ? tr("内核验证：{0}，见右侧“模拟问题”。", parts.join(trc("list separator", "、"))) : tr("内核验证：全部开行、准点。");
	}

	/** What went wrong on the line in the simulated day, each going to its service when clicked. */
	function simPanel(line) {
		const routes = new Map(line ? line.routes.map(r => [r.id, r]) : []);
		const checkDepot = sim.checkDepot && depotById(sim.checkDepot);
		if (checkDepot) checkDepot.routes.forEach(id => { if (!routes.has(id) && data.routeById.get(id)) routes.set(id, data.routeById.get(id)); });
		const problems = sim.result ? sim.result.problems.filter(problem => problem.kind === "DEADLOCK" || routes.has(problem.route)) : [];
		sim.listed = problems;
		const items = problems.slice(0, SIM_MAX_LISTED).map((problem, i) => {
			const r = routes.get(problem.route);
			const stop = r && problem.stop >= 0 && problem.stop < r.stops.length ? shortName(r.stops[problem.stop].name) : "";
			const service = r ? `${escapeHtml(routeTitle(r))} ${clock(mod(problem.service - data.dayStartMillis, DAY))}` : escapeHtml(problem.detail);
			const duration = problem.duration > 0 ? " · " + formatDuration(problem.duration) : "";
			return `<li><button type="button" class="link" data-sim-problem="${i}">${clock(problem.time)} <b>${tr(SIM_KINDS[problem.kind] || problem.kind)}</b> ${service}${stop ? " · " + escapeHtml(stop) : ""}${duration}</button></li>`;
		});
		const notes = [];
		if (sim.notSimulated) notes.push(tr("改站序、新建或删除种别的修改没有模拟（{0} 项）", sim.notSimulated));
		if (sim.ignored) notes.push(tr("{0} 项草稿和服务端的线路对不上，没有用上", sim.ignored));
		if (sim.missingPaths) notes.push(tr("{0} 种运用在轨道上找不到路径，这些车不会发车", sim.missingPaths));
		if (sim.result && sim.allDepots && sim.scopeDepots < sim.allDepots) notes.push(tr("只模拟了有关的 {0} / {1} 个车厂：跑这些线路的，和车经过同样轨道的；其余的车没有模拟", sim.scopeDepots, sim.allDepots));
		const body = items.length ? `<ul class="sim-problems">${items.join("")}</ul>` : sim.result ? `<p class="hint">${tr("这一天没有发现问题。")}</p>` : "";
		return `<h2>${tr("模拟问题")}</h2><div class="card">${body}${problems.length > SIM_MAX_LISTED ? `<p class="hint">${tr("只列出前 {0} 条。", SIM_MAX_LISTED)}</p>` : ""}${notes.map(note => `<p class="hint">${escapeHtml(note)}</p>`).join("")}<p class="hint">${sim.result && sim.result.from > 0 ? tr("从 {0} 起接着游戏里列车当时的位置模拟，之前的班次不计。", clock(sim.result.from)) : tr("所有车从车厂出发；过夜留在线上的车按出库算。")}</p></div>`;
	}

	// ---------- Summary timetable ----------
	let tableRoute = null;
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

	function deadheadNotes(s) {
		const deadheads = showDeadheads && deadheadIndex.get(s.id);
		if (!deadheads) return "";
		const out = deadheads.out ? `<span class="note${deadheads.out.exception ? " exception" : ""}" title="${tr("出库回送")}">${escapeHtml(deadheads.out.number)} ${clock(deadheads.out.leave, true)} ← ${escapeHtml(deadheads.depot)}</span>` : "";
		const back = deadheads.back ? `<span class="note${deadheads.back.exception ? " exception" : ""}" title="${tr("回库回送")}">${escapeHtml(deadheads.back.number)} → ${escapeHtml(deadheads.depot)} ${clock(deadheads.back.home, true)}</span>` : "";
		return out + back;
	}

	function serviceHead(s, selected, notes) {
		const noTrain = !s.suspended && hasNoTrain(s);
		return `<th class="svc-head${selected ? " hl" : ""}${noTrain ? " no-train" : ""}${s.suspended ? " suspended" : ""}" data-svc="${s.id}" data-stop="0" data-kind="dep" title="${noTrain ? tr("没有车跑这一班") + "\n" : ""}${s.suspended ? tr("停运：留在表里，不出车、不排运用，右键恢复") + "\n" : ""}${tr("点这里改班次时刻；右键更多")}"><span class="time">${clock(s.start)}${s.exceptions ? `<span class="exc-mark" title="${tr("有只改这一班的例外")}"> *</span>` : ""}</span>${notes}${deadheadNotes(s)}${s.suspended ? `<span class="note suspended-note">${tr("停运")}</span>` : ""}${s.deployOrder ? `<span class="note">${tr("出车 ← {0}", escapeHtml(orderDepotName(s.deployOrder)))}</span>` : ""}${s.returnOrder ? `<span class="note">${tr("回送 → {0}", escapeHtml(orderDepotName(s.returnOrder)))}</span>` : s.link && s.link.fromStop < 0 && s.link.toStop === 0 ? `<span class="note">${tr("接 {0}", clock(mod(s.link.toService - data.dayStartMillis, DAY)))}</span>` : s.link ? `<span class="note">${tr("直通")}</span>` : s.returns ? `<span class="note">${tr("回库")}</span>` : ""}</th>`;
	}

	/** A service's arrival, departure or passing time at a stop, as a cell to click and type into. @param t its times there */
	function timeCell(s, i, kind, t, selected) {
		if (kind === "arr" && i === 0 || kind === "dep" && i === s.route.stops.length - 1) return terminalCell(s, i, kind, t, selected);
		const plan = s.plans[i], exception = s.exceptions && s.exceptions.get(i);
		// Not served: before the stop a train from a depot joins it, or after the one it leaves for a depot
		const [firstServed, lastServed] = servedStops(s);
		if (i < firstServed || i > lastServed || i === firstServed && kind === "arr" && i > 0 || i === lastServed && kind === "dep" && i < s.route.stops.length - 1) {
			const before = i < firstServed || i === firstServed && kind === "arr";
			const title = before ? tr("出车 ← {0}", orderDepotName(s.deployOrder)) : tr("回送 → {0}", orderDepotName(s.returnOrder));
			return `<td class="${["pass", "unserved", selected ? "hl" : ""].filter(Boolean).join(" ")}" data-svc="${s.id}" data-stop="${i}" data-kind="${kind}" title="${escapeHtml(title)}" tabindex="0">—</td>`;
		}
		const passes = kind !== "pass" && plan.passes;
		const changed = exception && (kind === "dep" ? exception.dwell >= 0 : exception.run >= 0) || exception && exception.pass >= 0;
		const leaves = s.link && kind !== "dep" && (s.link.fromStop < 0 ? i === s.route.stops.length - 1 : s.link.fromStop === i);
		const picked = state.pendingExit && state.pendingExit.id === s.id && state.pendingExit.index === i && kind !== "dep";
		const classes = [selected ? "hl" : "", kind === "pass" || passes ? "pass" : "", changed ? "exc" : "", kind !== "dep" && plan.tooFast ? "fast" : "", kind === "dep" && plan.overtake ? "wait" : "", exception && exception.pass >= 0 ? "pending-run" : "", leaves ? "out" : "", picked ? "pending" : ""].filter(Boolean).join(" ");
		const title = exception && exception.pass >= 0 ? tr("只改了这一班的时刻：列车实际仍按线路的停站运行，按班次生成路径后（E2）才真正通过或停车") : plan.tooFast && kind !== "dep" ? tr("快过最快时刻，列车跑不到") : "";
		const text = passes ? (kind === "arr" ? "↓ " + clock(t[0], true) : "レ") : clock(kind === "dep" ? t[1] : t[0], true);
		return `<td class="${classes}" data-svc="${s.id}" data-stop="${i}" data-kind="${passes ? "pass" : kind}" title="${title}" tabindex="0">${text}</td>`;
	}

	/**
	 * The arrival at a route's first stop or the departure from its last, which follow the train's previous or next
	 * service when it turns back at the platform (terminalNow); not typed into, as they follow the other service.
	 */
	function terminalCell(s, i, kind, t, selected) {
		const pairs = terminalNow(), pairing = kind === "arr" ? pairs.previous.get(s.id) : pairs.next.get(s.id);
		const [firstServed, lastServed] = servedStops(s);
		if (kind === "arr" && firstServed > 0 || kind === "dep" && lastServed < s.route.stops.length - 1) return `<td class="pass unserved${selected ? " hl" : ""}">—</td>`;
		const time = pairing ? kind === "arr" ? pairing.toArrival : pairing.fromDeparture : kind === "arr" ? t[0] : t[1];
		const title = pairing ? terminalText(pairing) + "\n" + (kind === "arr" ? tr("跟着上一班，改那一班的时刻") : tr("跟着下一班，改那一班的时刻")) : tr("没有接续的班次：只算本班的停站时间");
		const classes = ["derived", selected ? "hl" : "", pairing && pairing.source === "nearest" ? "inferred" : "", pairing && pairing.conflict ? "fast" : "", pairing && pairing.kind !== "same" ? "out" : ""].filter(Boolean).join(" ");
		return `<td class="${classes}" data-svc="${s.id}" data-stop="${i}" data-kind="derived" title="${escapeHtml(title)}" tabindex="0">${clock(time, true)}</td>`;
	}

	/**
	 * The timetable of a line's routes going one way, all together, with the stations down the side in the sections of the
	 * line's diagram. A service going on in another section has ↳ at the first station it doesn't go to after leaving
	 * this one, and ‖ at the other stations it doesn't go by.
	 */
	function renderLineTable(line) {
		const view = $("view-table");
		// Which way a route goes: down the stations or up, by its first move
		const wayOf = r => {
			for (let i = 1; i < r.rows.length; i++) if (r.rows[i] !== r.leaveRows[i - 1]) return r.rows[i] > r.leaveRows[i - 1] ? 0 : 1;
			return 0;
		};
		const ways = [0, 1].map(w => line.routes.filter(r => wayOf(r) === w));
		const way = ways[state.lineWay] && ways[state.lineWay].length ? state.lineWay : ways[0].length ? 0 : 1;
		const order = line.rows.map((row, i) => i);
		if (way === 1) order.reverse();
		const position = new Map(order.map((row, t) => [row, t]));
		const ends = w => [...new Set(ways[w].map(r => shortName(r.stops[r.stops.length - 1].name)))].join(tr("、"));
		const services = ways[way].flatMap(r => r.services).sort((a, b) => a.start - b.start);
		const tools = `<div class="tt-tools"><b>${escapeHtml(shortName(line.name))}</b><span class="hint">${tr("{0} · {1} 班 · 在左边选一个种别可以改表和添加班次", WEEKDAY_NAMES[state.weekday], services.length)}</span><label class="hint"><input type="checkbox" data-show-deadheads${showDeadheads ? " checked" : ""}> ${tr("显示回送")}</label></div>
			<div class="tt-tools"><span class="hint" style="flex:none">${tr("方向")}</span>${[0, 1].filter(w => ways[w].length).map(w => `<button class="table-chip${w === way ? " on" : ""}" data-way="${w}">${tr("往{0}", escapeHtml(ends(w)))}</button>`).join("")}</div>`;
		if (!services.length) {
			view.innerHTML = tools + `<div class="table-empty">${tr("这条线路{0}没有班次", WEEKDAY_NAMES[state.weekday])}</div>`;
			return;
		}
		const isSelected = s => s.id === state.selected || s.route.id === state.selectedRoute || inRange(s);
		const allTimes = services.map(timesOf);
		// For each service, the stop it arrives at and leaves from on each row, and the rows it goes by
		const visits = services.map(s => {
			const r = s.route, at = new Map(), seen = new Set();
			r.rows.forEach((row, i) => {
				if (!at.has(row + "arr")) at.set(row + "arr", i);
				if (!at.has(r.leaveRows[i] + "dep")) at.set(r.leaveRows[i] + "dep", i);
				seen.add(position.get(row));
				seen.add(position.get(r.leaveRows[i]));
			});
			const sections = new Set([...seen].map(t => line.rows[order[t]].section));
			return { at, seen, sections, first: Math.min(...seen), last: Math.max(...seen) };
		});
		const head = `<thead><tr><th class="st">${tr("车站")}</th><th class="kind"></th>${services.map(s => serviceHead(s, isSelected(s), `<span class="note" style="color:${colorOf(s.route.color)}">${escapeHtml(shortName(s.route.name))}</span>`)).join("")}</tr></thead>`;
		const rows = [];
		order.forEach((rowIndex, t) => {
			const row = line.rows[rowIndex];
			const arrives = visits.some(v => v.at.has(rowIndex + "arr")), leaves = visits.some(v => v.at.has(rowIndex + "dep"));
			const kinds = arrives && leaves ? ["arr", "dep"] : arrives ? ["arr"] : ["dep"];
			const sectionStart = t > 0 && line.rows[order[t - 1]].section !== row.section;
			kinds.forEach((kind, k) => {
				const cells = services.map((s, j) => {
					const v = visits[j], i = v.at.get(rowIndex + kind), selected = isSelected(s);
					if (i !== undefined) return timeCell(s, i, kind, allTimes[j][i], selected);
					let mark = "";
					if (!v.seen.has(t)) {
						if (!v.sections.has(row.section)) mark = "‖";
						else if (t > v.first && t < v.last) mark = v.seen.has(t - 1) ? "↳" : "‖";
					}
					if (mark === "↳" && k > 0) mark = "";
					return `<td class="${["pass", mark === "↳" ? "through" : "", selected ? "hl" : ""].filter(Boolean).join(" ")}">${mark}</td>`;
				}).join("");
				const label = { dep: tr("发"), arr: trc("arrival", "到") }[kind];
				rows.push(`<tr class="${[k === 0 ? "first-of-station" : "", k === 0 && sectionStart ? "section-start" : ""].filter(Boolean).join(" ")}">${k === 0 ? `<th class="st${row.repeat ? " repeat" : ""}" rowspan="${kinds.length}" title="${escapeHtml(row.name)}">${row.repeat ? "↔ " : ""}${escapeHtml(shortName(row.name))}</th>` : ""}<th class="kind">${label}</th>${cells}</tr>`);
			});
		});
		view.innerHTML = tools + `<table class="tt">${head}<tbody>${rows.join("")}</tbody></table>`;
		if (tableRoute !== "line:" + line.key) {
			view.scrollLeft = 0;
			tableRoute = "line:" + line.key;
		}
		const selectedHead = view.querySelector("th.svc-head.hl");
		if (selectedHead && !state.selectedRoute) selectedHead.scrollIntoView({ block: "nearest", inline: "center" });
	}

	function renderTable() {
		deadheadIndex = showDeadheads ? deadheadsByService() : new Map();
		const view = $("view-table");
		if (!showsTable()) return;
		const line = currentLine();
		// A line with more than one route shows them together until one is chosen
		if (!state.route && line && line.routes.length > 1) return renderLineTable(line);
		const r = state.route ? data.routeById.get(state.route) : line && line.routes[0];
		if (!r) {
			view.innerHTML = `<div class="table-empty">${tr("在左边选一条线路的汇总时刻表")}</div>`;
			return;
		}
		const services = r.services;
		const timetable = timetableOf(r), today = timetable.tables.findIndex(table => table.weekdays >> state.weekday & 1);
		const managed = isManaged(r), pending = draftOf(r) && "managed" in draftOf(r), drafted = Boolean(draftOf(r) && draftOf(r).timetable);
		const connecting = isConnecting(r), connectingPending = draftOf(r) && "connectingLine" in draftOf(r);
		const tools = `<div class="tt-tools"><b>${escapeHtml(routeTitle(r))}</b><span class="hint">${tr("{0} · {1} 班{2}{3}", WEEKDAY_NAMES[state.weekday], services.length, r.hasTimes || r.pathFailure ? "" : tr(" · 正在向服务端取各站时刻"), drafted ? tr(" · 有没应用的草稿") : "")}</span>${r.pathFailure ? `<span class="err">${escapeHtml(r.pathFailure)}</span>` : ""}${shortfallsOf(r).map(text => `<span class="err">${escapeHtml(text)}</span>`).join("")}
			<span class="pill${managed ? " managed" : ""}" title="${tr("由高级编辑器管理的线路，游戏里的时刻表和停站只能查看")}">${managed ? tr("由高级编辑器管理") : tr("游戏内可编辑")}${pending ? tr("（草稿）") : ""}</span>
			<button data-managed="${!managed}">${managed ? tr("交回游戏内编辑") : tr("改由高级编辑器管理")}</button>
			${speedLimitOf(r) > 0 ? `<span class="pill" title="${tr("列车在这条线路两站之间的轨道上不超过这个速度，也不超过轨道自己的限速")}">${tr("限速 {0} km/h", speedLimitOf(r))}${draftOf(r) && "speedLimit" in draftOf(r) ? tr("（草稿）") : ""}</span>` : ""}</div>
			<div class="tt-tools"><span class="hint" style="flex:none">${tr("表")}</span>${timetable.tables.map((table, i) => `<button class="table-chip${i === today ? " on" : ""}" data-table="${i}" title="${tr("切到这张表运行的第一天；右键改名、改星期或删除")}">${escapeHtml(tableName(table, i))} · ${daysText(table.weekdays)}</button>`).join("")}<button class="table-chip" data-new-table>${tr("＋ 新建表")}</button>
			${today >= 0 ? `<label class="hint" title="${tr("运行时分按这组性能算，换车型时刻表不变；跑这条线的车比它慢时会提示")}">${tr("最低性能")} ${tr("加速")} <input class="perf" data-perf="a" value="${perfText(timetable.tables[today], "a")}" inputmode="decimal" style="width:3.5em"> m/s² ${tr("减速")} <input class="perf" data-perf="d" value="${perfText(timetable.tables[today], "d")}" inputmode="decimal" style="width:3.5em"> m/s² ${tr("最高")} <input class="perf" data-perf="v" value="${perfText(timetable.tables[today], "v")}" inputmode="numeric" style="width:3.5em"> km/h</label>` : ""}
			</div>
			${today >= 0 ? `<div class="tt-tools"><label for="add-service">${tr("添加班次")}</label><input id="add-service" placeholder="${tr("起始 07:10")}" title="${tr("起始时刻；也可以写 07:10 + 10 * 00:03")}" autocomplete="off" value="${escapeHtml(addInputs.start)}"><input id="add-end" placeholder="${tr("结束 09:10")}" autocomplete="off" value="${escapeHtml(addInputs.end)}" style="width:7em"><input id="add-count" placeholder="${tr("间隔 5")}" title="${tr("期望间隔，分钟（也可以写 04:30 表示 4 分 30 秒）")}" autocomplete="off" value="${escapeHtml(addInputs.count)}" style="width:5em"><button data-add-service>${tr("添加")}</button><label class="hint"><input type="checkbox" data-show-deadheads${showDeadheads ? " checked" : ""}> ${tr("显示回送")}</label><button data-clear-table title="${tr("删掉这张表的全部班次，连同直通、回库、出入库指令和例外；表本身、停站时分和运行的星期保留")}">${tr("清空")}</button><span class="err">${escapeHtml(tableMessage)}</span></div>` : `<div class="tt-tools"><span class="err">${tr("{0}没有表，这一天不运行。{1}", WEEKDAY_NAMES[state.weekday], escapeHtml(tableMessage))}</span></div>`}`;
		if (!services.length) {
			view.innerHTML = tools + `<div class="table-empty">${tr("这条线路{0}没有班次", WEEKDAY_NAMES[state.weekday])}</div>`;
			return;
		}
		const multipleTables = timetable.tables.length > 1;
		const isSelected = s => s.id === state.selected || s.route.id === state.selectedRoute || inRange(s);
		const head = `<thead><tr><th class="st">${tr("车站")}</th><th class="kind"></th>${services.map(s => serviceHead(s, isSelected(s), multipleTables ? `<span class="note">${escapeHtml(tableName(s.table, s.tableIndex))}</span>` : "")).join("")}</tr></thead>`;
		const allTimes = services.map(timesOf);
		const rows = [];
		r.stops.forEach((stop, i) => {
			const last = i === r.stops.length - 1;
			const allPass = i > 0 && !last && services.every(s => s.plans[i].passes);
			// The first and the last stop have both: the train's arrival and its departure as it goes on (terminalNow)
			const kinds = i === 0 || last ? ["arr", "dep"] : allPass ? ["pass"] : ["arr", "dep"];
			kinds.forEach((kind, k) => {
				const cells = services.map((s, j) => timeCell(s, i, kind, allTimes[j][i], isSelected(s))).join("");
				const label = { dep: tr("发"), arr: trc("arrival", "到"), pass: tr("通过") }[kind];
				rows.push(`<tr class="${k === 0 ? "first-of-station" : ""}">${k === 0 ? `<th class="st" rowspan="${kinds.length}" title="${escapeHtml(stop.name)}${escapeHtml(trackTitle(stop))}${stop.boundary ? tr("（时刻边界，可以在这里接出或接入）") : ""}">${escapeHtml(shortName(stop.name))}${stop.boundary ? `<span class="boundary"> ${tr("界")}</span>` : ""}${stop.tooShort ? `<span class="err" title="${tr("车比站台长（站台 {0} 格）", stop.platformLength)}"> ⚠</span>` : ""}${isLoop(r) && i > 0 && i < r.stops.length - 1 && loopEntryStop(r) === i ? `<span class="entry" title="${tr("车厂的列车从这里接入环线")}"> ${tr("入")}</span>` : ""}</th>` : ""}<th class="kind">${label}</th>${cells}</tr>`);
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
		if (!s || kind === "derived" || kind === "pass" && cell.textContent === "レ" || cell.querySelector("input")) return;
		const t = timesOf(s)[stop], old = kind === "dep" ? t[1] : t[0];
		const target = cell.tagName === "TH" ? cell.querySelector(".time") : cell;
		target.innerHTML = `<input value="${clock(old, true)}" aria-label="${tr("时刻")}">`;
		const input = target.querySelector("input");
		input.focus();
		input.select();
		input.title = tr("回车只改这一班，Shift+回车把同样的改动用到这一班和之后的全部班次");
		let done = false;
		let toLater = false;
		const commit = move => {
			if (done) return;
			const time = typedTime(input.value, old);
			if (time === null) {
				input.style.borderColor = "var(--warn)";
				return;
			}
			done = true;
			const cells = [...$("view-table").querySelectorAll("td")], index = cells.indexOf(cell);
			if (Math.abs(time - old) >= 1000) changeTime(s, stop, kind, time, toLater);
			else renderTable();
			if (move) {
				const next = $("view-table").querySelectorAll("td")[index + move];
				if (next) editCell(next);
			}
		};
		input.addEventListener("keydown", e => {
			if (e.key === "Enter") { e.preventDefault(); toLater = e.shiftKey; commit(0); }
			else if (e.key === "Tab") { e.preventDefault(); commit(e.shiftKey ? -1 : 1); }
			else if (e.key === "Escape") { done = true; renderTable(); }
		});
		input.addEventListener("blur", () => commit(0));
	}

	/**
	 * A time typed for one service. The first stop's departure moves the whole service; an arrival or passing time is its
	 * own running time from the stop before, and a departure its own stop time, kept as exceptions of this service.
	 */
	function changeTime(s, stop, kind, time, toLater) {
		const times = timesOf(s);
		// Shift+Enter: the same change, to the second, for this service and every later one of its table (2026-11-09)
		const services = toLater ? [s, ...laterServices(s)] : [s];
		editTimetable(s.route, timetable => {
			const table = timetable.tables[s.tableIndex];
			if (stop === 0) {
				const shift = time - times[0][1];
				const moving = services.map(o => o.service).sort((a, b) => shift > 0 ? b - a : a - b);
				for (const service of moving) {
					const error = retimeService(table, service, mod(service + shift, DAY));
					if (error) return error;
				}
			} else if (kind === "dep") {
				const dwell = Math.max(0, Math.round((time - times[stop][0]) / 1000));
				services.forEach(o => setStopException(table, o.service, stop, { dwell }));
			} else {
				const run = Math.max(1, Math.round((time - times[stop - 1][1]) / 1000));
				services.forEach(o => setStopException(table, o.service, stop, { run }));
			}
			if (services.length > 1) return tr("这一班和之后的 {0} 班一起改了", services.length - 1);
		});
		const moved = stop === 0 ? data.routeById.get(s.route.id).services.find(o => o.tableIndex === s.tableIndex && o.service === mod(s.service + time - times[0][1], DAY)) : null;
		if (moved) state.selected = moved.id;
	}

	/** Services of the same table that run after this one, in the operating day. */
	const laterServices = s => s.route.services.filter(o => o.tableIndex === s.tableIndex && o.start > s.start);

	// A run of services chosen together: one route and table, one after another with none skipped (TimetableBatch)
	const Batch = window.TimetableBatch;
	const tableRun = s => s.route.services.filter(o => o.tableIndex === s.tableIndex);
	const inRange = s => Boolean(state.range && state.range.ids.includes(s.id));
	const rangeServices = () => state.range ? state.range.ids.map(findService).filter(Boolean) : [];

	/** Chooses the services from the one chosen before to this one, if they're of one route and table. */
	function chooseRange(s) {
		const anchor = state.range ? findService(state.range.anchor) : state.selected && findService(state.selected);
		if (!anchor || anchor.route !== s.route || anchor.tableIndex !== s.tableIndex) {
			state.range = null;
			return false;
		}
		state.range = { anchor: anchor.id, ids: Batch.runBetween(tableRun(s).map(o => o.id), anchor.id, s.id) };
		return true;
	}

	/**
	 * Ctrl-click (⌘ on a Mac): a service put in or taken out of the chosen ones; with Shift as well, every service from the
	 * one Ctrl-clicked last (or the start of the run) to this one, taken out if this one was chosen, else put in. The
	 * chosen ones start as the run, the route chosen with a double click, or the selected service (2026-11-09).
	 *
	 * @return whether the choice changed
	 */
	function toggleChosen(s, span) {
		const run = tableRun(s).map(o => o.id);
		let ids = state.range ? state.range.ids.slice() : state.selectedRoute === s.route.id ? run.slice() : state.selected ? [state.selected] : [];
		const first = ids.length ? findService(ids[0]) : null;
		if (first && (first.route !== s.route || first.tableIndex !== s.tableIndex)) ids = [];
		const fromId = state.range && (state.range.toggled || state.range.anchor) || state.selected;
		const affected = span && fromId && run.includes(fromId) ? Batch.runBetween(run, fromId, s.id) : [s.id];
		const chosen = new Set(ids), remove = chosen.has(s.id);
		affected.forEach(id => remove ? chosen.delete(id) : chosen.add(id));
		const order = run.filter(id => chosen.has(id));
		state.selectedRoute = null;
		state.range = order.length ? { anchor: state.range && order.includes(state.range.anchor) ? state.range.anchor : order[0], ids: order, toggled: s.id } : null;
		state.selected = order.length ? (order.includes(s.id) ? s.id : order[0]) : null;
		return true;
	}

	/** Chooses a service and every later one of its table, to move or change together. */
	function chooseLater(s) {
		const ids = [s, ...laterServices(s)].map(o => o.id);
		state.selectedRoute = null;
		state.selected = s.id;
		state.range = { anchor: s.id, ids };
		tableMessage = tr("选中 {0} 班，右键表头可以调整间隔", ids.length);
		renderAll();
	}

	/**
	 * Moves a service and every later one of its table by a time asked for: minutes, or seconds as 90s, 1:30 or -0:45.
	 */
	function shiftLaterDialog(s) {
		const later = laterServices(s);
		const shift = parseShift(prompt(tr("这一班和之后的 {0} 班平移多少？写分钟，或写秒：90s、1:30、-0:45；负数是提前", later.length), "5"));
		if (!shift) return;
		editTimetable(s.route, timetable => {
			const table = timetable.tables[s.tableIndex];
			const moving = [s, ...later].map(o => o.service).sort((a, b) => shift > 0 ? b - a : a - b);
			for (const service of moving) {
				const error = retimeService(table, service, mod(service + shift, DAY));
				if (error) return error;
			}
			return tr("{0} 班{1}了 {2}", moving.length, shift > 0 ? tr("推后") : tr("提前"), durationText(Math.abs(shift)));
		});
	}

	/**
	 * @return a time typed as a shift in milliseconds: minutes (5, -2.5, 5m, 5分), seconds (90s, 90秒), m:ss (1:30,
	 * -0:45) or minutes and seconds (1m30s, 1分30秒); null if it isn't one
	 */
	function parseShift(text) {
		if (text === null || text === undefined) return null;
		const typed = String(text).trim().replace(/\s+/g, "").replace(/^−/, "-");
		const sign = typed.startsWith("-") ? -1 : 1, body = typed.replace(/^[-+]/, "");
		let m;
		if ((m = body.match(/^(\d+):(\d{1,2})$/))) return sign * (Number(m[1]) * 60 + Number(m[2])) * 1000;
		if ((m = body.match(/^(\d+(?:\.\d+)?)(?:s|秒)$/i))) return sign * Math.round(Number(m[1]) * 1000);
		if ((m = body.match(/^(\d+)(?:m|min|分|分钟)(\d+)(?:s|秒)?$/i))) return sign * (Number(m[1]) * 60 + Number(m[2])) * 1000;
		if ((m = body.match(/^(\d+(?:\.\d+)?)(?:m|min|分|分钟)?$/i))) return sign * Math.round(Number(m[1]) * 60000);
		return null;
	}

	/** A length of time as minutes and seconds, as 1 分 30 秒, 45 秒 or 5 分钟. */
	function durationText(millis) {
		const seconds = Math.round(millis / 1000), minutes = Math.floor(seconds / 60), rest = seconds % 60;
		return !minutes ? tr("{0} 秒", rest) : rest ? tr("{0} 分 {1} 秒", minutes, rest) : tr("{0} 分钟", minutes);
	}

	/** Spaces the chosen services evenly from the first, keeping each one's stops and run times. */
	function respaceRange() {
		const run = rangeServices();
		if (run.length < 2) return;
		const minutes = Number(prompt(tr("这 {0} 班相邻两班之间隔多少分钟？", run.length), String(Math.round((run[1].start - run[0].start) / 60000) || 5)));
		if (!(minutes > 0)) return;
		const targets = Batch.respace(run.map(o => o.service), Math.round(minutes * 60000));
		editTimetable(run[0].route, timetable => {
			const table = timetable.tables[run[0].tableIndex];
			// Those moving later go first from the end, those moving earlier from the start, so none lands on another
			const moves = run.map((o, i) => [o.service, targets[i]]).filter(([from, to]) => from !== to);
			const later = moves.filter(([from, to]) => mod(to - from, DAY) < DAY / 2).reverse(), earlier = moves.filter(([from, to]) => mod(to - from, DAY) >= DAY / 2);
			for (const [from, to] of [...later, ...earlier]) {
				const error = retimeService(table, from, to);
				if (error) return error;
			}
			state.range = null;
			return tr("{0} 班已按 {1} 分钟间隔排开", run.length, minutes);
		});
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

	/**
	 * The menu choosing how fast trains may pass over a platform (round 3b), into the draft.
	 */
	function platformSpeedItems(platformId) {
		const p = platformById(platformId);
		if (!p || !data.canEdit) return null;
		const set = p.passSpeed || 80;
		const current = draft.platformSpeeds && platformId in draft.platformSpeeds ? draft.platformSpeeds[platformId] : set;
		return [80, 100, 120, 160, 200, 300].map(speed => [`${current === speed ? "✓ " : ""}${speed} km/h`, () => {
			if (speed === set) delete draft.platformSpeeds[platformId];
			else draft.platformSpeeds[platformId] = speed;
			saveDraft();
			renderAll();
		}]);
	}

	/** How a platform's stop position reads (round 4). */
	function stopPositionText(position) {
		return position === 0 ? tr("停在出站端") : position < 0 ? tr("按车长居中停车") : tr("离出站端 {0} 格停车", position);
	}

	/**
	 * The menu choosing where trains stop on a platform (round 4), into the draft.
	 */
	function platformStopItems(platformId) {
		const p = platformById(platformId);
		if (!p || !data.canEdit) return null;
		const set = p.stopPosition || 0;
		const current = draft.platformStops && platformId in draft.platformStops ? draft.platformStops[platformId] : set;
		return [0, -1, 2, 5, 10, 20].map(position => [`${current === position ? "✓ " : ""}${stopPositionText(position)}`, () => {
			if (position === set) delete draft.platformStops[platformId];
			else draft.platformStops[platformId] = position;
			saveDraft();
			renderAll();
		}]);
	}

	/**
	 * The menu choosing the station a platform belongs to (R6): automatic, by the areas and the nearest platform, or any of
	 * the nearest stations, wherever the platform is; into the draft.
	 */
	function platformStationItems(platformId) {
		const p = platformById(platformId);
		if (!p || !data.canEdit) return null;
		const current = draft.platformStations && platformId in draft.platformStations ? draft.platformStations[platformId] : p.stationSet || "0";
		const choose = stationId => () => {
			if (stationId === (p.stationSet || "0")) delete draft.platformStations[platformId];
			else draft.platformStations[platformId] = stationId;
			saveDraft();
			renderAll();
		};
		const near = (topo ? topo.stations : []).slice().sort((a, b) => Math.hypot(a.x - p.x, a.z - p.z) - Math.hypot(b.x - p.x, b.z - p.z)).slice(0, 8);
		if (current !== "0" && !near.some(station => station.id === current)) {
			const set = (topo ? topo.stations : []).find(station => station.id === current);
			if (set) near.push(set);
		}
		return [[`${current === "0" ? "✓ " : ""}${stationNameOf("0")}`, choose("0")], ...near.map(station => [`${current === station.id ? "✓ " : ""}${shortName(station.name) || tr("（未命名车站）")}`, choose(station.id)])];
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
	const isLoop = r => r.stops.length > 2 && r.stops[0].platformId === r.stops[r.stops.length - 1].platformId;

	/** The platform a stop uses and the other tracks of its station on the same rails. */
	function trackTitle(stop) {
		const trackName = c => trackLabel(c.id, c.name, c.passingTrack);
		const others = (stop.candidates || []).filter(c => c.id !== stop.platformId);
		return ` ${tr("站台 {0}{1}", trackName({ id: stop.platformId, name: stop.platform, passingTrack: stop.passingTrack }), others.length ? `${tr("；还可停 {0}", others.map(trackName).join("、"))}` : "")}`;
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
		const ending = s => s.link ? `→ ${escapeHtml(routeTitle(data.routeById.get(s.link.route) || r))}` : runs.maxWait > MAX_LAYOVER_MILLIS ? tr("留在线上") : tr("回库");
		const lines = runs.trains.slice(0, 40).map((laps, i) => `<div><span class="train">${tr("车 {0}：", i + 1)}</span>${laps.map(s => clock(s.start)).join(" → ")} <span class="end">${ending(laps[laps.length - 1])}</span></div>`).join("");
		return `<h2>${tr("环线运行（{0}）", WEEKDAY_NAMES[state.weekday])}</h2><div class="card"><div class="title">${escapeHtml(routeTitle(r))}</div>
			<dl class="kv"><dt>${tr("列车")}</dt><dd>${tr("{0} 列跑 {1} 班", runs.trains.length, r.services.length)}</dd><dt>${tr("出库接入")}</dt><dd>${escapeHtml(stopName(r, entry))}${entry ? "" : tr("（首站）")}</dd>
			<dt>${tr("等下一圈")}</dt><dd>${tr("最多 {0}", runs.maxWait > MAX_LAYOVER_MILLIS ? tr("8 天（车厂不回库）") : tr("30 分钟"))}</dd></dl>
			<div class="laps">${lines}${runs.trains.length > 40 ? "<div>…</div>" : ""}</div>
			<p class="hint">${tr("每跑完一圈，列车接首站能赶上的第一个空班次；赶不上的班次由车厂出车。运行图上最下一行的虚线是在首站等下一圈。")}</p></div>`;
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
	/**
	 * The duties a depot runs by: the draft's, the ones set for it, or for a depot running by a timetable with none set the
	 * ones the game worked out from its timetables, marked auto.
	 */
	const dutiesOf = depot => {
		if (draft.duties[depot.id]) return draft.duties[depot.id].duties;
		const set = depot.duties || { mode: "online", sidings: {} };
		const auto = depot.autoDuties;
		return set.mode !== "diagram" && auto && auto.mode === "diagram" && Object.keys(auto.sidings).length ? Object.assign({}, auto, { auto: true }) : set;
	};
	/** A loop: marked clockwise or anticlockwise, or ending at the platform it starts from (2026-11-09). */
	const routeIsLoop = r => r.stops.length > 2 && (Boolean(r.circular) || r.stops[0].platformId === r.stops[r.stops.length - 1].platformId);
	const dutyOn = (slot, weekday) => slot.find(day => day.days >> weekday & 1) || null;
	const depotById = id => data.depots.find(depot => depot.id === id);
	const segmentKey = segment => segment.route + "@" + segment.service;

	/** Changes a depot's duties in the draft, dropping the draft when it ends up the same as the game's. */
	/** Where a duty's train comes from (S8), the same on each of its days: "prefer", "only" or "any". */
	const startOf = slot => (slot.find(day => day.start) || {}).start || "prefer";
	const startText = (slot, siding) => {
		const name = shortName(siding.name) || tr("存车线");
		const start = startOf(slot);
		return start === "any" ? tr("出车：不指定") : start === "only" ? tr("只从 {0} 出车", name) : tr("出车：{0}", name);
	};

	/** A new duty number, as a decimal string of a long like the game's. */
	const newDutyId = () => String(1e15 + Math.floor(Math.random() * 8e15));
	/** A slot's own number, from any of its days, or null if it has none yet. */
	const dutyIdOf = slot => (slot.find(day => day.duty && day.duty !== "0") || {}).duty || null;
	/** The slot with a duty number among a depot's duties: its siding, index and the slot, or null. */
	/**
	 * A duty's number as the player sees it: on through its pool, siding after siding in the pool's order, as the duties are
	 * the pool's (运用补完 step 5).
	 */
	function dutyNumber(depot, sidingId, index) {
		if (!depot) return index + 1;
		const pool = (depot.pools || []).find(each => (each.sidings || []).some(id => String(id) === String(sidingId)));
		const duties = dutiesOf(depot);
		let before = 0;
		for (const id of pool ? pool.sidings : [sidingId]) {
			if (String(id) === String(sidingId)) break;
			before += (duties.sidings[id] || []).length;
		}
		return before + index + 1;
	}

	function findDutySlot(duties, id) {
		if (!id || id === "0") return null;
		for (const [siding, slots] of Object.entries(duties.sidings)) {
			const index = slots.findIndex(slot => dutyIdOf(slot) === id);
			if (index >= 0) return { siding, index, slot: slots[index] };
		}
		return null;
	}
	/**
	 * Marks the days a train staying overnight from another siding's duty takes over (fromOvernight), so no train leaves the
	 * siding for them and they start on the line, as the game reads them.
	 */
	function relinkOvernight(duties) {
		const all = Object.values(duties.sidings).flatMap(slots => slots.flatMap(slot => slot));
		all.forEach(day => { day.fromOvernight = false; });
		all.filter(day => day.stay && day.nextDuty && day.nextDuty !== "0").forEach(day => {
			const nextDays = (day.days << 1 | day.days >> 6) & 0x7f;
			all.filter(other => other.duty === day.nextDuty && (other.days & nextDays)).forEach(other => { other.fromOvernight = true; });
		});
	}
	/** The sidings of a depot a siding's trains can go back to, or take duties of: the same vehicle. */
	const poolSidings = (depot, sidingId) => {
		const own = depot.sidings.find(siding => siding.id === sidingId);
		return depot.sidings.filter(siding => !own || siding.vehicle === own.vehicle);
	};

	function editDuties(depot, change) {
		if (!draft.duties[depot.id]) {
			const start = JSON.parse(JSON.stringify(dutiesOf(depot)));
			delete start.auto;
			draft.duties[depot.id] = { duties: start };
		}
		const entry = draft.duties[depot.id];
		const message = change(entry.duties);
		Object.values(entry.duties.sidings).forEach(slots => slots.forEach(slot => slot.forEach(day => day.segments.sort((a, b) => segmentStart(a) - segmentStart(b)))));
		relinkOvernight(entry.duties);
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
		const made = {
			days: group, stay: current ? current.stay : false, next: current && current.next !== undefined ? current.next : -1, segments: current ? current.segments.map(segment => ({ ...segment })) : [],
			// The duty's own number, the same on all its days (P2-D), and where it goes on to and back to
			duty: dutyIdOf(slot) || newDutyId(), nextDuty: current && current.nextDuty || "0", returnSiding: current && current.returnSiding || "0", fromOvernight: false, start: startOf(slot),
		};
		// Where it waits, when it leaves, its deadhead paths and its own exceptions, as the day it was split from (运用补完)
		if (current) ["waits", "outLead", "outEntrance", "backEntrance", "outPath", "backPath", "exceptions"].forEach(key => { if (current[key] !== undefined) made[key] = JSON.parse(JSON.stringify(current[key])); });
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

	/**
	 * On the day shown, the routes a depot running by its duties runs and the services no train of their duties runs;
	 * worked out once until the data, the draft or the day changes ({@link coverage}, declared at the top).
	 */
	function coverageNow() {
		if (coverage && coverage.weekday === state.weekday) return coverage;
		const assigned = dutyAssignments();
		const routes = new Set(data ? data.depots.filter(depot => dutiesOf(depot).mode === "diagram").flatMap(depot => depot.routes || []) : []);
		const noTrain = new Set();
		if (data) data.routes.forEach(r => {
			if (routes.has(r.id) && r.hasTimes) (r.services || []).forEach(s => { if (!s.suspended && !assigned.has(s.id)) noTrain.add(s.id); });
		});
		coverage = { weekday: state.weekday, routes, noTrain };
		return coverage;
	}
	const hasNoTrain = s => coverageNow().noTrain.has(s.id);

	/**
	 * On the day shown, which service each train runs next after the end of a route and where it waits meanwhile
	 * (TerminalPairs, terminal.js): set by the player, in a duty, or the nearest; with the platforms it may turn back at in
	 * turn and the turn back rails beyond, as the depots' path generation measured them.
	 */
	function terminalNow() {
		if (terminal && terminal.weekday === state.weekday) return terminal;
		const runs = [], manual = {}, turnTimes = {}, holdIns = {}, holdCounts = {}, duties = [];
		if (data) {
			data.routes.forEach(r => (r.services || []).forEach(s => {
				if (!r.hasTimes) return;
				const [first, last] = servedStops(s);
				runs.push({
					id: s.id, platforms: r.stops.map(stop => stop.platformId), stations: r.stops.map(stop => stop.station || "p" + stop.platformId),
					times: timesOf(s), first, last, suspended: !!s.suspended, returns: !!(s.returns || s.returnOrder), terminalPlatforms: r.terminalPlatforms || [],
					// Marked clockwise or anticlockwise: a loop, whose trains go round again (2026-11-09)
					circular: Boolean(r.circular),
				});
				if (s.link && s.link.fromStop < 0 && s.link.toStop === 0) manual[s.id] = s.link.route + "@" + s.link.toService;
			}));
			data.depots.forEach(depot => {
				Object.entries((depot.path || {}).turnTimes || {}).forEach(([from, times]) => { turnTimes[from] = Object.assign(turnTimes[from] || {}, times); });
				Object.assign(holdIns, (depot.path || {}).holdIns || {});
				Object.assign(holdCounts, (depot.path || {}).holdCounts || {});
				Object.values(dutiesOf(depot).sidings).forEach(slots => slots.forEach(slot => {
					const day = dutyOn(slot, state.weekday);
					if (day && day.segments.length) duties.push(day.segments.map(segmentKey));
				}));
			});
		}
		terminal = Object.assign(TerminalPairs.pair({ runs, manual, duties, turnTimes, holdIns, holdCounts, minTurn: minReverseMillis() }), { weekday: state.weekday });
		return terminal;
	}
	/** A platform as the whole network's topology has it (id, name, station, stationName), or null. */
	const platformById = platformId => topo && topo.index.has(String(platformId)) ? topo.platforms[topo.index.get(String(platformId))] : null;
	const platformText = platformId => {
		const platform = platformById(platformId);
		return platform ? `${shortName(platform.stationName) || ""} ${platform.name || ""}`.trim() : String(platformId);
	};
	/** How a train goes on at the end of a route, as a short sentence. */
	function terminalText(pairing) {
		const wait = Math.max(0, pairing.to.times[0][1] - pairing.from.times[pairing.from.last][0]);
		const where = pairing.kind === "hold" ? tr("停在 {0} 后面的折返线上等 {1}", platformText(pairing.platform), formatDuration(pairing.railEnd - pairing.railStart))
			: pairing.kind === "change" ? tr("换到 {0}，共 {1}", platformText(pairing.to.platforms[0]), formatDuration(wait))
			: pairing.loop ? tr("环线续跑，在 {0} 停 {1}", platformText(pairing.platform), formatDuration(wait))
			: tr("在 {0} 折返，停 {1}", platformText(pairing.platform), formatDuration(wait));
		const how = pairing.source === "manual" ? tr("（指定）") : pairing.source === "nearest" ? tr("（推测）") : "";
		return where + how + (pairing.moved ? tr("，改用这个站台") : "") + (pairing.conflict ? tr("，站台被占") : "");
	}

	/** A route's services on the day shown, with a train and without, or why that isn't known. */
	function coverageText(r) {
		if (!coverageNow().routes.has(r.id)) return `<span class="hint" title="${escapeHtml(tr("这条线路还没有车厂的运用（车厂没选这条线路，或自动排班还没生成），看不出哪班有车"))}"> · ${tr("看不出哪班有车")}</span>`;
		const all = (r.services || []).filter(s => s.table.weekdays >> state.weekday & 1), today = all.filter(s => !s.suspended);
		const without = today.filter(hasNoTrain).length, suspendedCount = all.length - today.length;
		const depots = data.depots.filter(depot => (depot.routes || []).includes(r.id) && depot.shortfall && depot.shortfall.uncovered > 0);
		const title = depots.map(depot => shortName(depot.name) + "：" + shortfallText(depot.shortfall)).join("\n");
		return `<span${without ? ` class="err" title="${escapeHtml(title)}"` : ""}> · ${tr("有车 {0} · 没车 {1}", today.length - without, without)}${suspendedCount ? tr("（停运 {0}）", suspendedCount) : ""}</span>`;
	}

	let showNoTrain = false;
	try { showNoTrain = localStorage.getItem("mtr-editor-show-no-train") === "1"; } catch (e) {}
	/** Whether the diagram shows the depots' rows and the deadheads out and back. */
	let showDepots = true;
	try { showDepots = localStorage.getItem("mtr-editor-show-depots") !== "0"; } catch (e) {}

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
				// A deleted route is told once for the whole duty (dutyPanel); a route still there doesn't have this service
				if (data.routeById.get(segment.route)) problems.push({ i, text: tr("{0}的时刻表里没有 {1} 这一班，可能时刻表改过", WEEKDAY_NAMES[weekday], clock(mod(segment.service - data.dayStartMillis, DAY))) });
				return;
			}
			if (!depot.routes.includes(segment.route)) problems.push({ i, text: tr("线路不在这个车厂") });
			if (s.suspended) problems.push({ i, text: tr("这一班停运了：自动排班不会排它，这份运用里请移出，或在时刻表里恢复") });
			// A service set to come out of a depot starts a train's duty, from a siding of that depot
			if (s.deployOrder && i > 0 && segment.from === s.deployOrder.stop) problems.push({ i, text: tr("这一班设了从车厂出车，不能接在上一班后面，要放在一份运用的开头") });
			else if (s.deployOrder && i === 0 && s.deployOrder.depot !== "0" && s.deployOrder.depot !== depot.id) problems.push({ i, text: tr("这一班设了由车厂 {0} 出车，这份运用却在车厂 {1}", orderDepotName(s.deployOrder), shortName(depot.name)) });
			if ((assigned.get(segmentKey(segment)) || []).length > 1) problems.push({ i, text: tr("这一班也分给了别的车") });
			const before = day.segments[i - 1], beforeService = before && segmentService(before);
			if (beforeService) {
				const endStop = beforeService.route.stops[lastStopOf(before, beforeService.route)], startStop = s.route.stops[segment.from];
				const gap = segmentStart(segment) - segmentEnd(before);
				if (gap < 0) problems.push({ i, text: tr("衔接时间不够，上一班还没到") });
				else if (endStop && startStop && endStop.station !== startStop.station) problems.push({ i, text: `${tr("站台对不上：上一班在{0}结束", shortName(endStop.name))}` });
				else if (endStop && startStop && endStop.platformId === startStop.platformId) {
					// A loop going round again from where it came round doesn't change ends (2026-11-09)
					const goesRound = routeIsLoop(beforeService.route) && routeIsLoop(s.route) && lastStopOf(before, beforeService.route) === beforeService.route.stops.length - 1 && segment.from === 0;
					if (!goesRound && gap < minReverseMillis()) problems.push({ i, text: tr("折返时间不够：同一站台折返至少要 {0}，这里只有 {1}", formatDuration(minReverseMillis()), formatDuration(gap)) });
				} else if (endStop && startStop) {
					// A change of platform takes as long as the server measured on the rails, or has no way at all
					const turn = turnTimeOf(depot, endStop.platformId, startStop.platformId);
					if (turn === null) problems.push({ i, text: tr("这两个站台之间没有路：{0} 到 {1} 换不过去，刷新路径后还是这样就要加折返线或渡线", stopLabel(endStop), stopLabel(startStop)) });
					// As the planner: the least turnaround at the platform arrived at, then the way to the other one
					else if (turn !== undefined && gap < minReverseMillis() + turn) problems.push({ i, text: tr("衔接时间不够：从 {0} 换到 {1} 实际要 {2}，这里只有 {3}", stopLabel(endStop), stopLabel(startStop), formatDuration(minReverseMillis() + turn), formatDuration(gap)) });
				}
			}
		});
		if (day.stay && day.nextDuty && day.nextDuty !== "0") {
			// Another siding's duty, by its number (P2-D)
			const found = findDutySlot(dutiesOf(depot), day.nextDuty);
			const next = found ? dutyOn(found.slot, (weekday + 1) % 7) : null;
			if (!found) problems.push({ i: -1, text: tr("留在线上过夜，但第二天接的运用已经不在了") });
			else if (!next || !next.segments.length) problems.push({ i: -1, text: `${tr("留在线上过夜，但第二天接的车 {0} 没有运用", dutyNumber(depot, found.siding, found.index))}` });
			else if (dutyEndPlatform(day) !== dutyStartPlatform(next)) problems.push({ i: -1, text: `${tr("第二天接的车 {0} 的运用不从这个站台出发（过夜的车第二天从停着的站台接着跑）", dutyNumber(depot, found.siding, found.index))}` });
		} else if (day.stay) {
			const target = nextSlotOf(day, index);
			const next = slots[target] ? dutyOn(slots[target], (weekday + 1) % 7) : null;
			if (!next || !next.segments.length) problems.push({ i: -1, text: `${tr("留在线上过夜，但第二天接的车 {0} 没有运用", target + 1)}` });
			else if (dutyEndPlatform(day) !== dutyStartPlatform(next)) problems.push({ i: -1, text: `${tr("第二天接的车 {0} 的运用不从这个站台出发（过夜的车第二天从停着的站台接着跑）", target + 1)}` });
			if (slots.some((slot, other) => other !== index && dutyOn(slot, weekday) && dutyOn(slot, weekday).stay && nextSlotOf(dutyOn(slot, weekday), other) === target)) problems.push({ i: -1, text: `${tr("还有一列过夜的车第二天也接车 {0}", target + 1)}` });
		}
		return problems;
	}

	/** The slot a train staying overnight runs the next day: the one chosen, or its own. */
	const nextSlotOf = (day, index) => day.next !== undefined && day.next >= 0 ? day.next : index;
	function dutyEndPlatform(day) {
		const segment = day.segments[day.segments.length - 1], r = segment && data.routeById.get(segment.route);
		return r ? (r.stops[lastStopOf(segment, r)] || {}).platformId : null;
	}
	function dutyStartPlatform(day) {
		const segment = day.segments[0], r = segment && data.routeById.get(segment.route);
		return r ? (r.stops[segment.from] || {}).platformId : null;
	}

	/** The least time a train changes ends where it turns back, as the planner takes it: the network's setting (round 4). */
	/** The network's wait threshold as drafted, or as the game has it (运用补完 step 2). */
	function waitThresholdMillis() {
		const pending = draft.networkSettings && Number.isFinite(draft.networkSettings.waitThresholdMillis) ? draft.networkSettings.waitThresholdMillis : undefined;
		const settings = data && data.networkSettings || {};
		return pending !== undefined ? pending : Number.isFinite(settings.waitThresholdMillis) ? settings.waitThresholdMillis : 300000;
	}

	/** A station's own wait threshold, set or taken back to the network's, into the draft. */
	function stationWaitDialog(station) {
		const settings = data && data.networkSettings || {};
		const drafted = draft.networkSettings && draft.networkSettings.stationWaitThresholds || {};
		const current = station.id in drafted ? drafted[station.id] : (settings.stationWaitThresholds || {})[station.id];
		openDialog(tr("等候分界：{0}", escapeHtml(shortName(station.name))), `
			<label>${tr("秒（留空用全网的 {0} 秒）", Math.round(waitThresholdMillis() / 1000))} <input type="number" name="seconds" min="0" max="3600" value="${current === null || current === undefined ? "" : Math.round(current / 1000)}"></label>
			<p class="hint">${tr("在这个车站两班之间等得比这长，列车就离开站台去折返线等。出入库规则里还可以按线路再改。")}</p>`, tr("放进草稿"), form => {
			const text = form.seconds.value.trim();
			if (text !== "" && !(Number(text) >= 0 && Number(text) <= 3600)) return tr("写 0 到 3600 秒，或者留空");
			draft.networkSettings = draft.networkSettings || {};
			draft.networkSettings.stationWaitThresholds = Object.assign({}, draft.networkSettings.stationWaitThresholds || {});
			draft.networkSettings.stationWaitThresholds[station.id] = text === "" ? null : Math.round(Number(text)) * 1000;
			saveDraft();
			renderAll();
		});
	}

	function minReverseMillis() {
		const settings = (data && data.networkSettings) || {};
		const pending = draft.networkSettings && Number.isFinite(draft.networkSettings.minReverseMillis) ? draft.networkSettings.minReverseMillis : undefined;
		return pending !== undefined ? pending : Number.isFinite(settings.minReverseMillis) ? settings.minReverseMillis : 30000;
	}
	/**
	 * How long a change of platform within a station takes as the server measured it on the rails: the millis, null with
	 * no way between, or undefined when not measured yet.
	 */
	function turnTimeOf(depot, from, to) {
		const path = depot.path || {};
		if (!path.turnTimesMeasured) return undefined;
		const millis = ((path.turnTimes || {})[from] || {})[to];
		return millis === undefined ? null : millis;
	}
	const stopLabel = stop => `${shortName(stop.name)} ${stop.platform}`.trim();

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

	function dutyPanel() {
		const depot = depotById(state.slot.depot);
		const siding = depot && depot.sidings.find(o => o.id === state.slot.siding);
		if (!siding) return "";
		const slots = dutiesOf(depot).sidings[siding.id] || [], slot = slots[state.slot.index] || [];
		const day = dutyOn(slot, state.weekday), group = dayGroup(depot, state.weekday);
		const problems = dutyProblems(depot, siding.id, state.slot.index, state.weekday);
		const problemAt = i => problems.filter(p => p.i === i).map(p => `<div class="err">${escapeHtml(p.text)}</div>`).join("");
		// Services of deleted routes: one line for all of them, not a row each with "?"
		const deleted = day ? day.segments.filter(segment => !data.routeById.get(segment.route)).length : 0;
		const deletedNote = deleted ? `<p class="err">${escapeHtml(dutiesOf(depot).auto
			? tr("这份运用里有 {0} 班属于已经删除的线路。它是游戏按时刻表自动排的，车厂重新寻路后会按现在的线路重排（服务端换了新版后会自动做）。", deleted)
			: tr("这份运用里有 {0} 班属于已经删除的线路，这些班不会再跑。右键车厂重新生成运用，或在这里把它们移出。", deleted))}${dutiesOf(depot).auto ? "" : ` <button type="button" class="link" data-remove-deleted>${tr("全部移出")}</button>`}</p>` : "";
		const rows = day ? day.segments.map((segment, i) => {
			if (!data.routeById.get(segment.route)) return "";
			const s = segmentService(segment), r = data.routeById.get(segment.route);
			const where = r ? `${shortName(r.stops[segment.from] ? r.stops[segment.from].name : "?")} → ${shortName(r.stops[lastStopOf(segment, r)] ? r.stops[lastStopOf(segment, r)].name : "?")}` : tr("已删除的线路");
			const goesOn = i + 1 < day.segments.length && terminalNow().next.get(segmentKey(segment));
			// Where the train waits for the next service (运用补完 step 3): as planned, or set for the duty alone
			const wait = day.waits && i < day.waits.length ? day.waits[i] : 0;
			const waitOwn = (day.exceptions || 0) & EXCEPTION.WAITS;
			const waitSelect = i + 1 < day.segments.length && data.canEdit ? `<div class="hint">${tr("等候")} <select data-duty-wait="${i}"${waitOwn ? ' class="exception"' : ""}>${[[0, tr("运行时定")], [1, tr("在站台等")], [2, tr("去折返线等")], [3, tr("换站台再发")]].map(([value, text]) => `<option value="${value}"${value === wait ? " selected" : ""}>${text}</option>`).join("")}</select>${waitOwn ? ` <span class="exception">${tr("例外")}</span>` : ""}</div>` : "";
			return `<li><span class="num">${s ? clock(segmentStart(segment)) + "–" + clock(segmentEnd(segment)) : "--:--"}</span> ${escapeHtml(r ? shortName(r.name) : "?")} <span class="muted">${escapeHtml(where)}</span><button class="link" data-remove-segment="${i}" title="${tr("移出这一班")}">×</button>${goesOn ? `<div class="hint">${escapeHtml(terminalText(goesOn))}</div>` : ""}${waitSelect}${problemAt(i)}</li>`;
		}).join("") : "";
		return `<h2>${tr("运用：车 {0}", dutyNumber(depot, state.slot.siding, state.slot.index))}</h2><div class="card"><div class="title">${escapeHtml(shortName(depot.name))} · ${escapeHtml(shortName(siding.name) || tr("存车线"))}</div>
			<dl class="kv"><dt>${tr("运行日")}</dt><dd>${daysText(group)}${day && day.days !== group ? `${tr("（现在和 {0} 共用，改了会分开）", daysText(day.days & ~group))}` : ""}</dd>
			<dt>${tr("方式")}</dt><dd>${dutiesOf(depot).mode === "diagram" ? tr("按运用图") : tr("自动排班（游戏按时刻表自动生成运用；这里设的运用先存着，改为按运用图才用）")}</dd>
			${plannedReturnText(depot, siding.id, state.slot.index, state.weekday)}</dl>
			${deletedNote}
			${rows ? `<ol class="duty">${rows}</ol>` : deleted ? "" : `<p class="hint">${tr("这天没有运用，在运行图上点班次加进来。")}</p>`}
			${problemAt(-1)}
			${dutyCheckText(depot) ? `<p class="hint">${escapeHtml(dutyCheckText(depot))}</p>` : ""}
			${day && day.segments.length ? `<label><input type="checkbox" data-duty-stay ${day.stay ? "checked" : ""}> ${tr("跑完最后一班留在线上过夜")}</label>` : ""}
			${day && day.stay ? `<label>${tr("第二天接着跑")} <select data-duty-next>${poolSidings(depot, siding.id).flatMap(other => (dutiesOf(depot).sidings[other.id] || []).map((slot, i) => {
				const next = dutyOn(slot, (state.weekday + 1) % 7);
				const fits = next && next.segments.length && dutyStartPlatform(next) === dutyEndPlatform(day);
				const own = other.id === siding.id, id = dutyIdOf(slot);
				if (!own && !id) return "";
				const chosen = day.nextDuty && day.nextDuty !== "0" ? id === day.nextDuty : own && nextSlotOf(day, state.slot.index) === i;
				const label = `${own ? "" : (shortName(other.name) || tr("存车线")) + " "}${tr("车 {0}{1}{2}", i + 1, own && i === state.slot.index ? tr("（自己）") : "", fits ? "" : next && next.segments.length ? tr("（不从这个站台出发）") : tr("（没有运用）"))}`;
				return `<option value="${own ? "same:" + i : "duty:" + id}"${chosen ? " selected" : ""}>${escapeHtml(label)}</option>`;
			})).join("")}</select></label>` : ""}
			${day && day.segments.length && !day.stay ? `<label>${tr("回库存车线")} <select data-duty-return><option value="0">${tr("自动（车池里那时最早要用车的）")}</option>${poolSidings(depot, siding.id).map(other => `<option value="${other.id}"${day.returnSiding === other.id ? " selected" : ""}>${escapeHtml(shortName(other.name) || tr("存车线"))}</option>`).join("")}</select></label>` : ""}
			<label>${tr("出车方式")} <select data-duty-start>${[["prefer", tr("优先从这条存车线出")], ["only", tr("只从这条存车线出")], ["any", tr("不指定（车池里有车、出库最快的）")]].map(([value, text]) => `<option value="${value}"${startOf(slots[state.slot.index] || []) === value ? " selected" : ""}>${text}</option>`).join("")}</select></label>
			${poolSidings(depot, siding.id).length > 1 ? `<label>${tr("出车存车线")} <select data-duty-siding>${poolSidings(depot, siding.id).map(other => `<option value="${other.id}"${other.id === siding.id ? " selected" : ""}>${escapeHtml(shortName(other.name) || tr("存车线"))}</option>`).join("")}</select></label>` : ""}
			${startOf(slots[state.slot.index] || []) === "only" && siding.fleet === 0 ? `<p class="warn">${tr("只从这条存车线出车，但它最多停 0 列，这份运用不会有车")}</p>` : ""}
			<p class="hint">${tr("重新生成运用时，出车方式和出车存车线按生成结果重来。")}</p>
			<p class="hint">${tr("选着这列车时，在运行图上点一班把它加进来，再点一次移出；别的车跑的班次会移过来。改的是 {0} 这几天（表的组合相同）。按 Esc 取消选择。按运用图的车厂按这里的运用出车，应用后要刷新车厂路径。", daysText(group))}</p></div>`;
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

	/** The menu of a duty's deadhead on the diagram: its time, path or siding, or back to the rules. */
	function deadheadMenu(e, leg) {
		const depot = depotById(leg.depot);
		const siding = depot && depot.sidings.find(o => o.id === leg.siding);
		const slot = siding && (dutiesOf(depot).sidings[siding.id] || [])[leg.index];
		const day = slot && dutyOn(slot, state.weekday);
		if (!day) {
			openMenuAt(e, []);
			return;
		}
		const canEdit = data.canEdit;
		const paths = (rulesOf(depot).paths || []).filter(path => path.kind === (leg.kind === "out" ? "out" : "back"));
		const pathItems = [[`${!day[leg.kind === "out" ? "outPath" : "backPath"] || day[leg.kind === "out" ? "outPath" : "backPath"] === "0" ? "✓ " : ""}${tr("最近车站后顺线路方向（默认）")}`, () => editDutyException(depot, siding.id, leg.index, edited => {
			edited[leg.kind === "out" ? "outPath" : "backPath"] = "0";
			edited.exceptions = (edited.exceptions || 0) | (leg.kind === "out" ? EXCEPTION.OUT_PATH : EXCEPTION.BACK_PATH);
		})], ...paths.map(path => [`${day[leg.kind === "out" ? "outPath" : "backPath"] === path.id ? "✓ " : ""}${path.name || path.id}`, () => editDutyException(depot, siding.id, leg.index, edited => {
			edited[leg.kind === "out" ? "outPath" : "backPath"] = path.id;
			edited.exceptions = (edited.exceptions || 0) | (leg.kind === "out" ? EXCEPTION.OUT_PATH : EXCEPTION.BACK_PATH);
		})])];
		const items = [];
		if (leg.kind === "out") {
			items.push([tr("出库提前量…"), canEdit ? () => {
				const times = deadheadTimes(siding, day).out;
				const text = prompt(tr("比首班早多少出库（秒，不少于出库路线用时 {0} 秒）", Math.round(times.wayOut / 1000)), String(Math.round(times.lead / 1000)));
				if (text === null) return;
				const seconds = Math.round(Number(text));
				if (!(seconds >= 0)) return;
				editDutyException(depot, siding.id, leg.index, edited => {
					edited.outLead = Math.max(times.wayOut, seconds * 1000);
					edited.exceptions = (edited.exceptions || 0) | EXCEPTION.OUT_LEAD;
					return tr("{0} 改为首班前 {1} 出库（这份运用的例外）", times.number, durationText(edited.outLead));
				});
			} : null]);
			items.push([tr("出库路径"), canEdit ? pathItems : null]);
			items.push([tr("按规则恢复"), canEdit && (day.exceptions & (EXCEPTION.OUT_LEAD | EXCEPTION.OUT_PATH | EXCEPTION.ENTRANCES)) ? () => editDutyException(depot, siding.id, leg.index, edited => {
				edited.exceptions = (edited.exceptions || 0) & ~(EXCEPTION.OUT_LEAD | EXCEPTION.OUT_PATH | EXCEPTION.ENTRANCES);
				return tr("出库按规则，重新排运用时按规则重算");
			}) : null]);
		} else {
			items.push([tr("回库存车线"), canEdit ? poolSidings(depot, siding.id).map(other => [`${day.returnSiding === other.id ? "✓ " : ""}${shortName(other.name) || tr("存车线")}`, () => editDutyException(depot, siding.id, leg.index, edited => {
				edited.returnSiding = other.id;
				edited.exceptions = (edited.exceptions || 0) | EXCEPTION.BACK_SIDING;
			})]) : null]);
			items.push([tr("回库路径"), canEdit ? pathItems : null]);
			items.push([tr("按规则恢复"), canEdit && (day.exceptions & (EXCEPTION.BACK_SIDING | EXCEPTION.BACK_PATH)) ? () => editDutyException(depot, siding.id, leg.index, edited => {
				edited.exceptions = (edited.exceptions || 0) & ~(EXCEPTION.BACK_SIDING | EXCEPTION.BACK_PATH);
				return tr("回库按规则，重新排运用时按规则重算");
			}) : null]);
		}
		items.push([tr("选中这份运用"), () => {
			state.slot = { depot: depot.id, siding: siding.id, index: leg.index };
			renderAll();
		}]);
		openMenuAt(e, items);
	}

	function releasePlanDialog(depot) {
		const duties = dutiesOf(depot);
		const rows = [];
		let inDepot = 0;
		depot.sidings.forEach(siding => {
			const slots = duties.sidings[siding.id] || [];
			let used = 0;
			slots.forEach((slot, index) => {
				const day = dutyOn(slot, state.weekday);
				if (!day || !day.segments.length) return;
				used++;
				const first = day.segments[0], last = day.segments[day.segments.length - 1];
				const firstRoute = data.routeById.get(first.route), lastRoute = data.routeById.get(last.route);
				rows.push({
					start: segmentStart(first), siding, index, stay: day.stay,
					first: `${segmentService(first) ? clock(timesOf(segmentService(first))[Math.min(first.from, segmentService(first).plans.length - 1)][1]) : "--:--"} ${firstRoute ? escapeHtml(shortName(firstRoute.name)) + " " + escapeHtml(shortName(firstRoute.stops[first.from] ? firstRoute.stops[first.from].name : "?")) : tr("已删除的线路")}`,
					last: `${segmentService(last) ? clock(timesOf(segmentService(last))[lastStopOf(last, segmentService(last).route)][0]) : "--:--"} ${lastRoute ? escapeHtml(shortName(lastRoute.name)) + " " + escapeHtml(shortName(lastRoute.stops[lastStopOf(last, lastRoute)] ? lastRoute.stops[lastStopOf(last, lastRoute)].name : "?")) : tr("已删除的线路")}`,
					count: day.segments.length,
					...legTimes(siding, day),
				});
			});
			if (siding.maxTrains >= 0) inDepot += Math.max(0, siding.maxTrains - used);
		});
		rows.sort((a, b) => a.start - b.start);
		const table = rows.length ? `<table class="tt release-plan" style="cursor: default"><thead><tr><th>${tr("车")}</th><th>${tr("存车线")}</th><th>${tr("出库")}</th><th>${tr("首班（出库后）")}</th><th>${tr("末班到达")}</th><th>${tr("到库")}</th><th>${tr("班次")}</th><th>${tr("之后")}</th></tr></thead><tbody>${rows.map(row => `<tr><td>${tr("车 {0}", dutyNumber(depot, row.siding.id, row.index))}</td><td>${escapeHtml(shortName(row.siding.name) || tr("存车线"))}</td><td class="num">${row.out}</td><td>${row.first}</td><td>${row.last}</td><td class="num">${row.home}</td><td class="num">${row.count}</td><td>${row.stay ? tr("留在线上过夜") : tr("回库")}</td></tr>`).join("")}</tbody></table>` : `<p class="hint">${tr("这天没有运用，车厂不放车。")}</p>`;
		openDialog(`${tr("出入库计划：{0}（{1}）", escapeHtml(shortName(depot.name)), WEEKDAY_NAMES[state.weekday])}`, `
			<p class="hint">${duties.auto ? tr("这个车厂没设运用，按游戏根据时刻表自动生成的运用出车。") : ""}${tr("车厂在首班前按出库用时放车；出库太晚（超过 10 分钟）的车直接放到它的运用里还赶得上的第一站。放车前要先拿到出库线到首站的进路，正线的方向预约在内，拿不到就在库里等。")}</p>
			${table}
			<p class="hint">${tr("今天出车 {0} 列，在库 {1} 列（存车线不限列车数的不算）。", rows.length, inDepot)}</p>
			<p class="hint">${depot.releaseInterval > 0 ? tr("两列车出库至少间隔 {0} 秒（在游戏的车厂界面里设）。", depot.releaseInterval) : tr("没设出库间隔（在游戏的车厂界面里设）。")}${tr("同一车厂车型相同的存车线共用列车：某条存车线没车时由另一条替补出车，回库时进下一班最早要车、还有空位的那条。")}</p>`, tr("关闭"), () => {});
	}

	/** Has the game put the depot's trains where the timetable has them now, instead of sending them out one by one. */
	function placeTrainsDialog(depot) {
		openDialog(tr("按时刻表摆车"), `<p>${tr("把“{0}”的列车直接放到时刻表现在要求的位置：从今天运营日开始按游戏的规则算到现在，这时候应该在线上的车摆上去，已经发过的班次不再发；原来的车换成算出来的车，乘客和货物不保留，玩家驾驶中的车不动。", escapeHtml(shortName(depot.name)))}</p>
			<p class="hint">${tr("只对开了速度曲线、由组件驱动的车厂有效。用的是游戏里已应用的时刻表，草稿要先应用。结果在游戏聊天栏。")}</p>`, tr("摆车"), () => {
			sendJson("api/place-trains", "POST", { depot: depot.id }).then(result => {
				dutyNotice = result.ok ? tr("已请求“{0}”按时刻表摆车，结果见游戏聊天栏。", shortName(depot.name)) : tr("没有权限，或者游戏里没有这个车厂。");
				renderBanner();
			}).catch(() => {
				dutyNotice = tr("发送失败，游戏可能已经关闭");
				renderBanner();
			});
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

	/**
	 * What a depot runs, drafted: the routes it's responsible for (in no order for one running by a timetable), whether its
	 * trains may wait on the line, and each siding's fleet and the routes its trains run (none ticked for all of them).
	 */
	/** Whether the settings drafted for a pool's vehicle are what the game has (null for none set). */
	function sameVehicle(drafted, game) {
		const set = game && game.set ? game : null;
		if (!drafted || !set) return !drafted && !set;
		return ["acceleration", "deceleration", "manual", "maxSpeed", "maxManualSpeed", "manualToAutomaticTime"].every(key => key === "manual" ? drafted.manual === set.manual : Math.abs((drafted[key] || 0) - (set[key] || 0)) < 1e-6);
	}

	/** What a pool of a depot's sidings has: as given, or its sidings' fleets added up when not (null). */
	function poolFleetText(fleet, sum) {
		if (fleet === null || fleet === undefined) return sum < 0 ? tr("配属按需要（存车线相加）") : tr("配属 {0} 列（存车线相加）", sum);
		return fleet < 0 ? tr("配属按需要") : tr("配属 {0} 列", fleet);
	}

	function depotDialog(depot) {
		const drafted = (draft.depots || {})[depot.id];
		const current = drafted || { routes: depot.routes.slice(), cycleRoutes: depot.cycleRoutes, sidings: {} };
		const poolFleetOf = pool => current.poolFleets && current.poolFleets[pool.key] !== undefined ? current.poolFleets[pool.key] : pool.fleet;
		// How the trains of a pool run (R1), m/tick² shown as m/s²
		const vehicleOf = pool => current.vehicleSettings && current.vehicleSettings[pool.key] !== undefined ? current.vehicleSettings[pool.key] : pool.vehicleSettings && pool.vehicleSettings.set ? pool.vehicleSettings : null;
		// The service types of a vehicle, shared by its pools (5c)
		const serviceTypesOf = pool => current.serviceTypes && current.serviceTypes[pool.vehicle] !== undefined ? current.serviceTypes[pool.vehicle] : pool.serviceTypes || "";
		const poolRows = (depot.pools || []).map(pool => {
			const fleet = poolFleetOf(pool);
			const shown = vehicleOf(pool) || pool.vehicleSettings || {};
			const set = Boolean(vehicleOf(pool));
			// The vehicle's own settings are set on the vehicle under the depot in the tree (web layers step 4)
			return `<fieldset data-pool-row="${escapeHtml(pool.key)}"><legend>${escapeHtml(tr("车型 {0}", pool.vehicle || pool.key))}</legend>
				<label>${tr("配属车数")} <input type="number" min="0" max="999" name="poolFleet" placeholder="${escapeHtml(pool.sum < 0 ? tr("存车线相加：按需要") : tr("存车线相加：{0}", pool.sum))}" value="${fleet === null || fleet === undefined || fleet < 0 ? "" : fleet}"></label>
				<span class="hint">${escapeHtml(vehicleSummary(pool, set ? shown : null, serviceTypesOf(pool)))}</span>
			</fieldset>`;
		}).join("");
		const sidingOf = siding => current.sidings[siding.id] || { routes: (siding.routes || []).slice(), fleet: siding.fleet };
		const lines = data.lines.filter(line => line.routes.length);
		const routeBoxes = lines.map(line => `<fieldset><legend>${escapeHtml(shortName(line.name))}</legend>${line.routes.map(r => `<label><input type="checkbox" name="route" value="${r.id}"${current.routes.includes(r.id) ? " checked" : ""}> ${escapeHtml(routeTitle(r))}</label>`).join("")}</fieldset>`).join("");
		const chosen = lines.flatMap(line => line.routes).filter(r => current.routes.includes(r.id));
		const sidingRows = depot.sidings.map(siding => {
			const value = sidingOf(siding);
			const boxes = chosen.map(r => `<label><input type="checkbox" name="sidingRoute" value="${r.id}"${value.routes.includes(r.id) ? " checked" : ""}> ${escapeHtml(routeTitle(r))}</label>`).join("");
			return `<fieldset data-siding-row="${siding.id}"><legend>${escapeHtml(shortName(siding.name) || tr("存车线"))}${siding.vehicle ? ` <span class="hint">${escapeHtml(siding.vehicle)}</span>` : ""}</legend>
				<label>${tr("最多停几列")} <input type="number" min="0" max="999" name="fleet" placeholder="${tr("不限")}" value="${value.fleet < 0 ? "" : value.fleet}"></label>
				<div class="hint">${tr("跑的线路（都不勾就跑车厂的全部线路）：")}</div>
				${boxes || `<span class="hint">${tr("先在上面选车厂负责的线路，放进草稿后再打开这里分给存车线")}</span>`}
			</fieldset>`;
		}).join("");
		// How much longer than planned its trains were measured to take out, round and back (D1), as suggestions, and the time
		// adopted, which the duties are planned with (round 4, step 6)
		const bufferKinds = { out: tr("出库"), turn: tr("折返"), back: tr("回库") };
		const addedOf = buffer => current.addedBuffers ? current.addedBuffers[buffer.key] : buffer.added;
		const bufferRows = (depot.buffers || []).map(buffer => {
			const added = addedOf(buffer);
			return `<tr data-buffer-row="${escapeHtml(buffer.key)}"><td>${escapeHtml(bufferKinds[buffer.kind] || buffer.kind)}</td><td>${escapeHtml(buffer.from)} → ${escapeHtml(buffer.to)}</td><td>${buffer.seconds ? tr("实测 +{0} 秒", buffer.seconds) : tr("没有实测")}</td>
				<td><label><input type="checkbox" name="adoptBuffer"${added ? " checked" : ""}> ${tr("采用")}</label> <input type="number" name="addedSeconds" min="1" max="1200" value="${added || buffer.seconds || ""}"> ${trc("seconds unit", "秒")}</td></tr>`;
		}).join("");
		const bufferSection = `<h3>${tr("实测余量")}</h3>
			<p class="hint">${tr("列车实际比计划多用的时间（最近 20 次的 85% 分位，多 15 秒以上才记），只作建议。勾上“采用”的才加在出入库和折返上排运用，时刻表不变；采用的变了，现有运用接得上就不自动重排，在车厂右键菜单里“重新排运用”。")}</p>
			${bufferRows ? `<table class="buffers"><tbody>${bufferRows}</tbody></table>
			<label><input type="checkbox" name="clearBuffers"${current.clearBuffers ? " checked" : ""}> ${tr("清空实测余量（采用的不变）")}</label>` : `<p class="hint">${tr("还没有记下")}</p>`}
			<h3>${tr("全网设置")}</h3>
			<label>${tr("换端最短时间（全网，秒）")} <input type="number" min="0" max="600" name="minReverse" value="${Math.round(minReverseMillis() / 1000)}"></label>
			<label>${tr("等候分界（全网，秒：两班之间等得比这长就离开站台去折返线）")} <input type="number" min="0" max="3600" name="waitThreshold" value="${Math.round(waitThresholdMillis() / 1000)}"></label>`;
		const cycleLabel = depot.byTimetable ? tr("允许长时间停在线上（两班之间不回库，运用跑完才回）") : tr("列车不回车厂，跑完最后一条线路后折返回第一条");
		openDialog(tr("编辑车厂：{0}", escapeHtml(shortName(depot.name))), `
			<p class="hint">${tr("车厂负责的线路没有先后。哪列车跑哪班由运用决定；车按车型配属给车厂，同车型的存车线共用这些车，车回到哪条存车线都还是同一列。存车线可以只跑其中几条线路，最多停几列是它能停的车数。配属车数留空时按各存车线相加。")}</p>
			<h3>${tr("负责的线路")}</h3>${routeBoxes || `<p class="hint">${tr("还没有线路")}</p>`}
			<label><input type="checkbox" name="cycle"${current.cycleRoutes ? " checked" : ""}> ${cycleLabel}</label>
			${poolRows ? `<h3>${tr("配属")}</h3>${poolRows}` : ""}
			<h3>${tr("存车线")}</h3>${sidingRows || `<p class="hint">${tr("没有存车线")}</p>`}
			${bufferSection}`, tr("放进草稿"), form => {
			const routes = [...form.querySelectorAll("input[name=route]:checked")].map(box => box.value);
			const sidings = {};
			let bad = false;
			form.querySelectorAll("[data-siding-row]").forEach(row => {
				const fleetText = row.querySelector("input[name=fleet]").value.trim();
				if (fleetText !== "" && !Number.isFinite(Number(fleetText))) bad = true;
				const fleet = fleetText === "" ? -1 : Math.max(0, Math.min(999, Math.round(Number(fleetText))));
				sidings[row.dataset.sidingRow] = { fleet, routes: [...row.querySelectorAll("input[name=sidingRoute]:checked")].map(box => box.value).filter(id => routes.includes(id)) };
			});
			const poolFleets = {};
			const vehicleSettings = {};
			const serviceTypes = {};
			// The vehicles' settings and service types as the vehicle dialog left them in the draft, or as the game has them
			Object.assign(serviceTypes, current.serviceTypes || {});
			form.querySelectorAll("[data-pool-row]").forEach(row => {
				const text = row.querySelector("input[name=poolFleet]").value.trim();
				if (text !== "" && !Number.isFinite(Number(text))) bad = true;
				poolFleets[row.dataset.poolRow] = text === "" ? null : Math.max(0, Math.min(999, Math.round(Number(text))));
				const pool = (depot.pools || []).find(each => each.key === row.dataset.poolRow);
				vehicleSettings[row.dataset.poolRow] = pool ? vehicleOf(pool) : null;
			});
			// The network's least time to change ends (round 4), drafted apart from the depot
			const reverseText = form.querySelector("input[name=minReverse]").value.trim();
			const reverseSeconds = Number(reverseText);
			if (reverseText === "" || !(reverseSeconds >= 0) || reverseSeconds > 600) bad = true;
			if (bad) return tr("车数写成数字，或者留空");
			const reverseMillis = Math.round(reverseSeconds) * 1000;
			const appliedReverse = (data.networkSettings || {}).minReverseMillis;
			const waitSeconds = Number(form.querySelector("input[name=waitThreshold]").value.trim());
			if (!(waitSeconds >= 0) || waitSeconds > 3600) return tr("等候分界写 0 到 3600 秒");
			const waitMillis = Math.round(waitSeconds) * 1000;
			const appliedWait = (data.networkSettings || {}).waitThresholdMillis;
			const changes = {};
			if (reverseMillis !== appliedReverse) changes.minReverseMillis = reverseMillis;
			if (waitMillis !== appliedWait) changes.waitThresholdMillis = waitMillis;
			const stationWaits = draft.networkSettings && draft.networkSettings.stationWaitThresholds;
			if (stationWaits) changes.stationWaitThresholds = stationWaits;
			const ownerOverrides = draft.networkSettings && draft.networkSettings.ownerOverrides;
			if (ownerOverrides) changes.ownerOverrides = ownerOverrides;
			draft.networkSettings = Object.keys(changes).length ? changes : null;
			const clearBox = form.querySelector("input[name=clearBuffers]");
			// The time adopted, by key in seconds, all of them (round 4, step 6)
			const addedBuffers = {};
			form.querySelectorAll("[data-buffer-row]").forEach(row => {
				if (!row.querySelector("input[name=adoptBuffer]").checked) return;
				const seconds = Math.round(Number(row.querySelector("input[name=addedSeconds]").value));
				if (!(seconds > 0) || seconds > 1200) bad = true;
				else addedBuffers[row.dataset.bufferRow] = seconds;
			});
			if (bad) return tr("车数写成数字，或者留空；采用的余量写 1 到 1200 秒");
			const appliedAdded = {};
			(depot.buffers || []).forEach(buffer => { if (buffer.added) appliedAdded[buffer.key] = buffer.added; });
			const sameAdded = JSON.stringify(Object.entries(addedBuffers).sort()) === JSON.stringify(Object.entries(appliedAdded).sort());
			const entry = { base: drafted ? drafted.base : depot.revision, routes, cycleRoutes: form.querySelector("input[name=cycle]").checked, sidings, poolFleets, vehicleSettings, serviceTypes, clearBuffers: Boolean(clearBox && clearBox.checked), addedBuffers: sameAdded ? null : addedBuffers };
			storeDepotEntry(depot, entry, sameAdded);
		});
	}

	/**
	 * A depot's changes as the draft has them, or as the game has the depot when there are none, to change and store again
	 * with {@link storeDepotEntry}: the depot dialog and the vehicle dialog change parts of the same entry.
	 */
	function depotEntryOf(depot) {
		const drafted = (draft.depots || {})[depot.id];
		if (drafted) {
			const copy = JSON.parse(JSON.stringify(drafted));
			copy.vehicleSettings = copy.vehicleSettings || {};
			copy.serviceTypes = copy.serviceTypes || {};
			copy.poolFleets = copy.poolFleets || {};
			(depot.pools || []).forEach(pool => {
				if (!(pool.key in copy.vehicleSettings)) copy.vehicleSettings[pool.key] = pool.vehicleSettings && pool.vehicleSettings.set ? Object.assign({}, pool.vehicleSettings) : null;
				if (!(pool.key in copy.poolFleets)) copy.poolFleets[pool.key] = pool.fleet;
			});
			return copy;
		}
		const sidings = {};
		depot.sidings.forEach(siding => sidings[siding.id] = { fleet: siding.fleet, routes: (siding.routes || []).slice() });
		const poolFleets = {}, vehicleSettings = {};
		(depot.pools || []).forEach(pool => {
			poolFleets[pool.key] = pool.fleet;
			vehicleSettings[pool.key] = pool.vehicleSettings && pool.vehicleSettings.set ? Object.assign({}, pool.vehicleSettings) : null;
		});
		return { base: depot.revision, routes: depot.routes.slice(), cycleRoutes: depot.cycleRoutes, sidings, poolFleets, vehicleSettings, serviceTypes: {}, clearBuffers: false, addedBuffers: null };
	}

	/** Puts a depot's changes in the draft, or takes them out when they change nothing. */
	function storeDepotEntry(depot, entry, sameAdded) {
		const sorted = list => JSON.stringify(list.slice().sort());
		const sidings = entry.sidings || {};
		const same = sorted(entry.routes) === sorted(depot.routes) && entry.cycleRoutes === depot.cycleRoutes
			&& depot.sidings.every(siding => sidings[siding.id] && sidings[siding.id].fleet === siding.fleet && sorted(sidings[siding.id].routes) === sorted(siding.routes || []))
			&& (depot.pools || []).every(pool => entry.poolFleets[pool.key] === pool.fleet && sameVehicle(entry.vehicleSettings[pool.key], pool.vehicleSettings)) && !Object.keys(entry.serviceTypes || {}).length && !entry.clearBuffers && (sameAdded === undefined ? !entry.addedBuffers : sameAdded);
		if (same) delete draft.depots[depot.id];
		else draft.depots[depot.id] = entry;
		saveDraft();
		renderAll();
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
	 * A vehicle's settings in a depot (web layers step 4): how its trains accelerate, brake and how fast they may run, driving
	 * by hand, and the service types it runs. Kept for the depot's pool of the vehicle, in the depot's draft.
	 */
	function vehicleDialog(depot, pool) {
		const entry = depotEntryOf(depot);
		const settings = entry.vehicleSettings[pool.key];
		const shown = settings || pool.vehicleSettings || {};
		const perSecond = value => Math.round(value * 400 * 100) / 100;
		const typesOf = text => String(text || "").split(/[,，、]/).map(part => part.trim()).filter(Boolean).join(",");
		const gameTypes = ((depot.pools || []).find(each => each.vehicle === pool.vehicle) || {}).serviceTypes || "";
		const types = entry.serviceTypes[pool.vehicle] !== undefined ? entry.serviceTypes[pool.vehicle] : gameTypes;
		const shared = (depot.pools || []).filter(each => each.vehicle === pool.vehicle).length;
		const manualSpeeds = depot.manualSpeeds || [];
		const value = shown.maxManualSpeed || 0;
		const speedOptions = manualSpeeds.map((speed, index) => `<option value="${index}"${index === value ? " selected" : ""}>${speed} km/h</option>`).join("")
			+ (value >= manualSpeeds.length ? `<option value="${value}" selected>${escapeHtml(tr("其他（{0}）", value))}</option>` : "");
		openDialog(tr("车型设置：{0}", escapeHtml(pool.vehicle || pool.key)), `
			<p class="hint">${tr("车厂“{0}”里这种车型（{1}）的列车怎么跑。同车型的存车线共用这些设置；别的车厂的同车型在那个车厂下设。", escapeHtml(shortName(depot.name)), escapeHtml(pool.key || pool.vehicle))}</p>
			<label><input type="checkbox" name="vehicleSet"${settings ? " checked" : ""}> ${tr("统一设置这个车型的加减速、最高速度和手动驾驶（不勾就按第一条存车线原来的）")}</label>
			<label>${tr("加速度 m/s²")} <input type="number" step="0.01" min="0.4" max="20" name="acceleration" value="${perSecond(shown.acceleration || 0.01)}"></label>
			<label>${tr("减速度 m/s²（空着同加速度）")} <input type="number" step="0.01" min="0" max="20" name="deceleration" value="${shown.deceleration > 0 ? perSecond(shown.deceleration) : ""}"></label>
			<label>${tr("最高速度 km/h（空着不限）")} <input type="number" step="1" min="0" max="300" name="maxSpeed" value="${shown.maxSpeed > 0 ? shown.maxSpeed : ""}"></label>
			<label><input type="checkbox" name="manual"${shown.manual ? " checked" : ""}> ${tr("允许手动驾驶")}</label>
			<label>${tr("手动驾驶最高速度（允许手动驾驶时才有用）")} <select name="maxManualSpeed">${speedOptions}</select></label>
			<label>${tr("多久不操作转回自动 秒")} <input type="number" step="0.5" min="0" max="3600" name="manualToAutomaticTime" value="${(shown.manualToAutomaticTime || 0) / 2}"></label>
			<h3>${tr("能跑的种别")}</h3>
			<label>${tr("种别（逗号隔开，空着不限）")} <input type="text" name="serviceTypes" value="${escapeHtml(types)}"></label>
			${shared > 1 ? `<span class="hint">${tr("同一车型的 {0} 种编组共用", shared)}</span>` : ""}
			<p class="hint">${tr("改动先放进这个车厂的草稿，点“应用”才发给游戏。")}</p>`, tr("放进草稿"), form => {
			if (form.vehicleSet.checked) {
				const acceleration = Number(form.acceleration.value) / 400;
				const decelerationText = form.deceleration.value.trim();
				const switchText = form.manualToAutomaticTime.value.trim();
				const manualToAutomaticTime = switchText === "" ? 0 : Math.round(Number(switchText) * 2);
				const maxSpeedText = form.maxSpeed.value.trim();
				const maxSpeed = maxSpeedText === "" ? 0 : Math.round(Number(maxSpeedText));
				if (!(acceleration > 0) || decelerationText !== "" && !(Number(decelerationText) >= 0)) return tr("加速度写正数，减速度写数字或者留空");
				if (!(manualToAutomaticTime >= 0) || manualToAutomaticTime > 7200) return tr("转回自动写 0 到 3600 秒");
				if (!(maxSpeed >= 0) || maxSpeed > 300) return tr("最高速度写 0 到 300，或者留空");
				entry.vehicleSettings[pool.key] = { acceleration, deceleration: decelerationText === "" ? 0 : Number(decelerationText) / 400, manual: form.manual.checked, maxManualSpeed: Number(form.maxManualSpeed.value) || 0, manualToAutomaticTime, maxSpeed };
			} else {
				entry.vehicleSettings[pool.key] = null;
			}
			const text = typesOf(form.serviceTypes.value);
			if (text !== typesOf(gameTypes)) entry.serviceTypes[pool.vehicle] = text;
			else delete entry.serviceTypes[pool.vehicle];
			storeDepotEntry(depot, entry);
		});
	}

	/**
	 * By depot, where the duties last generated here send their trains back when not to their own siding, as the game
	 * would choose it running: siding/slot/weekday → siding. A forecast; the game chooses as it runs.
	 */
	const plannedReturns = new Map();

	async function generateDuties(depot) {
		if (draft.duties[depot.id] && !confirm(`${tr("用自动生成的运用替换“{0}”草稿里的运用？", shortName(depot.name))}`)) return;
		if (!draft.duties[depot.id] && Object.values((depot.duties || { sidings: {} }).sidings).some(slots => slots.length) && !confirm(`${tr("用自动生成的运用替换“{0}”现在的运用？应用前都还在草稿里。", shortName(depot.name))}`)) return;
		dutyNotice = tr("正在生成运用…");
		renderBanner();
		try {
			const result = await sendJson("api/plan-duties", "POST", { depot: depot.id });
			if (result.noSidings) {
				dutyNotice = tr("这个车厂没有存车线，生成不了运用。");
			} else {
				editDuties(depot, duties => {
					duties.sidings = result.duties.sidings;
				});
				plannedReturns.set(depot.id, result.returns || {});
				// The running kernel checks the duties in a simulated day with the draft (S6), if the server has it on
				if (data.simulation) {
					sim.checkDepot = depot.id;
					if (sim.on) simulate(); else setSimulation(true);
				}
				// Planned with the depot's operating lines, as the game plans them; the draft is this depot's part
				const byLine = result.byLine ? tr("按运营线路 {0} 和 {1} 个车厂一起排，这里是本车厂的部分；应用后线路上其他自动排的车厂会把剩下的班次重排。", result.lineNames, result.lineDepots) + (result.uncovered && result.needed > result.available ? tr("跑满需要 {0} 列，现有 {1} 列{2}。", result.needed, result.available, result.suggestedSiding ? tr("，加到 {0}", result.suggestedSiding) : "") : "") : "";
				dutyNotice = byLine + `${tr("已生成：最多同时 {0} 列车在线上。", result.trains)}` + (result.uncovered ? `${tr("有 {0} 班没有车可派，存车线放的车不够。", result.uncovered)}` : "")
					+ (result.missingTimes.length ? `${tr("{0} 还没有各站时刻，没有算进去，打开它的时刻表或刷新车厂路径后再生成。", result.missingTimes.join("、"))}` : "")
					+ result.issues.filter(issue => issue.startsWith("SHORT_TURNAROUND ")).map(shortTurnaroundText).join("")
					+ result.issues.filter(isOrderIssue).slice(0, 5).map(orderIssueText).join("")
					+ (result.issues.some(issue => !issue.startsWith("SHORT_TURNAROUND ") && !isOrderIssue(issue)) ? `${tr("其他问题：{0}", result.issues.filter(issue => !issue.startsWith("SHORT_TURNAROUND ") && !isOrderIssue(issue)).slice(0, 3).join("；"))}` : "") + tr("先检查一遍，再按“应用”。");
			}
		} catch (e) {
			dutyNotice = tr("生成失败：") + (e.message === "401" ? tr("页面的链接已经失效") : tr("游戏可能已经关闭"));
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
		topo = { platforms: source.platforms, index: new Map(source.platforms.map((p, i) => [p.id, i])), byStation, reach: new Map(), problems: new Map(), networkCount: source.networkCount || 0, stations: source.stations || [], overlapping: source.overlapping || [] };
	}

	const isUsable = s => Boolean(topo.platforms[s >> 1] && topo.platforms[s >> 1].ways[s & 1].usable);
	/*
	 * A station's tracks as the game works them out from the routes (L2, StationTracks): up or down by where the routes'
	 * first and last stations lie (north or east is up), and main line or passing track, the hand-set flag first. Every
	 * place naming a track says it the same way.
	 */
	const TRACK_DIRECTIONS = { up: "上行", down: "下行", both: "双向" };
	const TRACK_SHORT = { up: "上", down: "下", both: "双" };
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
	/** As "上 3 待避", for the narrow cells of the stop picker. */
	function trackShort(id, name, passingTrack) {
		const { direction, kind } = trackInfo(id, passingTrack);
		return [direction ? tr(TRACK_SHORT[direction]) : "", name || "?", kind === "main" ? tr("正") : kind === "passing" ? tr("待避") : ""].filter(Boolean).join(" ");
	}
	const platformLabel = i => topo.platforms[i].name || "?";
	const stationLabel = i => shortName(topo.platforms[i].stationName) || tr("（不属于车站）");
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
		const name = i => shortName(r.stops[i].name) || `${tr("第 {0} 站", i + 1)}`;
		tracks.forEach((p, i) => { if (p < 0) problems.push({ stop: i, level: "error", text: `${tr("{0}的站台没有轨道", name(i))}` }); });
		if (!problems.length && tracks.length > 1) {
			const network = topo.platforms[tracks[0]].network;
			tracks.forEach((p, i) => { if (topo.platforms[p].network !== network) problems.push({ stop: i, level: "error", text: `${tr("{0}的站台和首站不在同一个线网里（轨道没有连起来）", name(i))}` }); });
		}
		if (!problems.length && tracks.length > 1) {
			const solution = solveStops(tracks.map(p => [p]), null, null);
			if (!solution.ok) {
				for (let i = 0; i + 1 < tracks.length; i++) {
					if (legCost(tracks[i], tracks[i + 1]) === Infinity) problems.push({ stop: i + 1, level: "error", text: `${tr("{0}到{1}沿轨道走不通", name(i), name(i + 1))}` });
				}
				if (!problems.length) problems.push({ stop: 0, level: "error", text: tr("各站单独走得通，连起来要在中途反复掉头才行") });
			} else {
				solution.reversals.forEach(i => problems.push({ stop: i, level: "warn", text: `${tr("在{0}掉头", name(i))}` }));
				for (let i = 0; i + 1 < tracks.length; i++) {
					const best = Math.min(...stationTracks(tracks[i]).flatMap(p => stationTracks(tracks[i + 1]).map(q => legCost(p, q))));
					if (isFinite(best) && solution.legs[i] > best * 1.5 + 50) problems.push({ stop: i + 1, level: "warn", text: `${tr("{0}到{1}绕路：换股可以近得多", name(i), name(i + 1))}` });
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
		if (!n) return `<p class="hint">${tr("没有车站。")}</p>`;
		const solution = solveStops(model.layers, model.picks, model.pins);
		const reversals = new Set();
		if (solution.ok && solution.picks.every((p, i) => p === model.picks[i])) solution.reversals.forEach(i => reversals.add(i));
		const colW = 112, rowH = 26, head = 38, pad = 8, cellW = 92;
		const rows = Math.max(...model.layers.map(l => l.length));
		const place = foldOf(model), lines = Math.max(...place.map(p => p.line)) + 1, blockH = head + rows * rowH + 28;
		const width = pad * 2 + (Math.max(...place.map(p => p.col)) + 1) * colW - (colW - cellW) + (lines > 1 ? 24 : 0), height = blockH * lines;
		const x = i => pad + place[i].col * colW, top = i => place[i].line * blockH, y = (i, k) => top(i) + head + k * rowH;
		const parts = [`<svg class="topo" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="group" aria-label="${tr("站台连通图")}">`];
		for (let i = 0; i + 1 < n; i++) {
			model.layers[i].forEach((p, a) => model.layers[i + 1].forEach((q, b) => {
				if (legCost(p, q) === Infinity) return;
				const on = model.picks[i] === p && model.picks[i + 1] === q;
				const y1 = y(i, a) + 10, y2 = y(i + 1, b) + 10, back = place[i + 1].col < place[i].col;
				// Folding back: round the right of the graph and down to the row below, then leftwards along it
				const points = place[i].line !== place[i + 1].line ? [[x(i) + cellW, y1], [width - 8, y1], [width - 8, y2], [x(i + 1) + cellW, y2]]
					: back ? [[x(i), y1], [x(i + 1) + cellW, y2]] : [[x(i) + cellW, y1], [x(i + 1), y2]];
				parts.push(`<polyline points="${points.map(point => point.join(",")).join(" ")}" fill="none" class="${on ? "link on" : "link"}"${on ? ` style="stroke:${model.color}"` : ""}/>`);
			}));
		}
		// The station a Q comes back to, the same in both rows
		place.forEach((p, i) => {
			if (p.line === 0 || place[i - 1].line === 1) return;
			const earlier = place.findIndex(o => o.line === 0 && o.col === p.col);
			if (earlier >= 0) parts.push(`<line x1="${x(i) + 6}" y1="${y(earlier, model.layers[earlier].length)}" x2="${x(i) + 6}" y2="${top(i) + 4}" class="same-station"/>`);
		});
		model.layers.forEach((tracks, i) => {
			const end = i === 0 || i === n - 1;
			const open = model.diagram && model.diagram.stop === i;
			parts.push(`<g class="station-head${open ? " open" : ""}" data-station-col="${i}" tabindex="0"><title>${open ? tr("收起配线图") : tr("看这一站的配线图")}</title><text x="${x(i) + cellW / 2}" y="${top(i) + 14}" class="station">${escapeHtml(stationLabel(tracks[0]))} ${open ? "▴" : "▾"}</text></g>`);
			if (reversals.has(i)) parts.push(`<text x="${x(i) + cellW / 2}" y="${top(i) + 30}" class="turn">${tr("↶ 掉头")}</text>`);
			tracks.forEach((p, k) => {
				const on = model.picks[i] === p, pinned = model.pins.has(i) && model.pins.get(i) === p, ok = solution.feasible[i].has(p);
				const classes = ["track", on ? "on" : "", pinned ? "pinned" : "", ok ? "" : "dead"].filter(Boolean).join(" ");
				const title = `${trackLabel(topo.platforms[p].id, platformLabel(p), topo.platforms[p].passingTrack)}：${pinned ? tr("已固定，点一下取消") : ok ? tr("点一下固定走这一股") : tr("和其他站连不起来，点一下仍可固定")}`;
				parts.push(`<g class="${classes}" data-stop="${i}" data-track="${p}" tabindex="0"><title>${escapeHtml(title)}</title><rect x="${x(i)}" y="${y(i, k)}" width="${cellW}" height="20" rx="4"${on ? ` style="fill:${model.color};stroke:${model.color}"` : ""}/><text x="${x(i) + cellW / 2}" y="${y(i, k) + 14}">${pinned ? "● " : ""}${escapeHtml(trackShort(topo.platforms[p].id, platformLabel(p), topo.platforms[p].passingTrack))}</text></g>`);
			});
			const stops = model.stop[i] || end;
			parts.push(`<g class="stop-toggle${end ? " fixed" : ""}" data-toggle="${i}"${end ? "" : ` tabindex="0"`}><title>${end ? tr("首末站，停车") : stops ? tr("停车，点一下改为通过") : tr("通过，点一下改为停车")}</title><text x="${x(i) + cellW / 2}" y="${top(i) + blockH - 8}">${stops ? tr("停") : tr("过")}</text></g>`);
		});
		parts.push(`</svg>`);
		model.solution = solution;
		return `<div class="topo-wrap">${parts.join("")}</div>${model.conflict ? `<p class="err">${escapeHtml(model.conflict)}</p>` : ""}${model.diagram ? stationDiagramHtml(model) : ""}`;
	}

	/**
	 * Where each stop goes in the connection graph: in one row, or, for a Q, the stops from where it comes back to the
	 * station it branched off at in a second row below, going leftwards from under that station.
	 *
	 * @return {col, line} for each stop
	 */
	function foldOf(model) {
		const n = model.layers.length;
		const stations = model.layers.map(tracks => topo.platforms[tracks[0]].station || "#" + tracks[0]);
		const again = revisits(stations, model.picks);
		const k = again.findIndex((v, i) => v && !v.retrace && i < n - 1);
		if (k < 0 || again[k].earlier - (n - 1 - k) < 0) return model.layers.map((tracks, i) => ({ col: i, line: 0 }));
		return model.layers.map((tracks, i) => i < k ? { col: i, line: 0 } : { col: again[k].earlier - (i - k), line: 1 });
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
				diagram.error = tr("正在读取轨道…");
				if (tries < 8) setTimeout(() => loadDiagram(model, diagram, tries + 1), 600);
				else diagram.error = tr("服务端没有回应，稍后再点一次站名");
			} else {
				diagram.data = result;
			}
		} catch (e) {
			if (model.diagram === diagram) diagram.error = tr("读取配线图失败");
		}
		if (model.onChange && model.diagram === diagram) model.onChange();
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

	function throatHtml(r, i) {
		const stop = r && r.stops[i];
		if (!stop || !stop.throat) return "";
		const offDepot = data.depots.find(depot => depot.throatLocksOffBy && depot.routes.includes(r.id));
		const throat = stop.throat;
		const side = entry => entry ? tr("进站") : tr("出站");
		const crossings = throat.crossings.map(c => {
			const other = data.routeById.get(c.route);
			return c.zone
				? tr("{0}和 {1} 的{2}在联锁区（{3}, {4}）", side(c.entry), escapeHtml(other ? routeTitle(other) : "?"), side(c.otherEntry), c.x, c.z)
				: tr("{0}和 {1} 的{2}在道岔（{3}, {4}）", side(c.entry), escapeHtml(other ? routeTitle(other) : "?"), side(c.otherEntry), c.x, c.z);
		});
		return `<p class="hint">${tr("咽喉：进站经过 {0} 组道岔，出站经过 {1} 组（按游戏里的线路路径）。", throat.entry, throat.exit)}${throat.zones ? tr("还经过 {0} 个联锁区（联锁色的信号闭塞分区），整块锁住，车尾离开才放开。", throat.zones) : ""}</p>`
			+ (offDepot ? `<p class="hint warn">${escapeHtml(lockOffText(offDepot))}</p>` : "")
			+ (crossings.length ? `<p class="hint">${tr("和这些进路交叉，不能同时走：")}${crossings.join(tr("；"))}${tr("。时刻表检查会找出时间上撞在一起的班次。")}</p>` : "");
	}

	/**
	 * How trains turn back at the last stop (站前 or 站后) and the platforms they take turns at, or at the first stop the
	 * platforms this route would rather start from (种别优先), each a box to tick; the order ticked is the order tried.
	 */
	function turnHtml(model, i) {
		const r = model.route;
		const n = model.layers.length;
		const stop = r && r.stops[i];
		if (!stop || n < 2 || (i !== 0 && i !== n - 1)) return "";
		const kind = i === 0 ? "startPlatforms" : "terminalPlatforms";
		const chosen = turnOf(r, kind);
		const ownId = topo.platforms[model.picks[i]] ? topo.platforms[model.picks[i]].id : stop.platformId;
		// A route's own last platform is always where it turns back usually, so only its other platforms are offered there
		const options = (stop.candidates || []).filter(c => i === 0 || c.id !== ownId);
		const boxes = options.map(c => {
			const at = chosen.indexOf(c.id);
			return `<label class="turn-option"><input type="checkbox" data-turn="${kind}" data-platform="${c.id}"${at >= 0 ? " checked" : ""}> ${escapeHtml(trackLabel(c.id, c.name, c.passingTrack))}${at >= 0 ? ` <span class="count">${tr("第 {0} 个", at + 1)}</span>` : ""}</label>`;
		}).join(" ");
		if (i === 0) {
			return `<div class="turn-back"><b>${tr("优先股道（种别优先）")}</b><p class="hint">${tr("在本站掉头接这条线的车，先进勾选的站台，按勾选的先后；只在上一条线路设了轮用站台时起作用。")}</p>${boxes || `<p class="hint">${tr("本站没有和这股连通的其他站台。")}</p>`}</div>`;
		}
		const rear = stop.waypoints > 0;
		const mode = rear
			? (chosen.length ? tr("站前加站后：先进勾选的站台掉头，站台都占着或赶不上下一班时，才走末站途经点里列的站后折返线。") : tr("站后折返：走末站途经点里列的折返线（{0} 个节点），按顺序取能赶上下一班、进路没被别的车占着的那股。", stop.waypoints))
			: tr("站前折返：车在站台上掉头，下一班从同一站台发车。勾选的站台和本站台轮用，在前一站出发时按下一班种别的优先股道、再按勾选的先后选。");
		return `<div class="turn-back"><b>${tr("折返方式")}</b><p class="hint">${mode}</p><p class="hint">${tr("轮用站台：")}</p>${boxes || `<p class="hint">${tr("本站没有和这股连通的其他站台。")}</p>`}<p class="hint">${tr("按运用跑的车才轮用；按频率发车的车只用本站台。")}</p></div>`;
	}

	function stationDiagramHtml(model) {
		const diagram = model.diagram, i = diagram.stop, n = model.layers.length;
		const head = `<div class="diagram-head"><b>${tr("{0} 配线图", escapeHtml(stationLabel(model.layers[i][0])))}</b><span class="tools"><button type="button" data-zoom="in" title="${tr("放大")}">＋</button><button type="button" data-zoom="out" title="${tr("缩小")}">−</button></span></div>`;
		if (!diagram.data) return `<div class="layout">${head}<p class="hint">${escapeHtml(diagram.error || tr("正在读取…"))}</p></div>`;
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
		const parts = [`<svg class="layout-svg" viewBox="${minX} ${minY} ${viewW} ${viewH}" width="100%" style="aspect-ratio:${width} / ${height}" preserveAspectRatio="xMidYMid meet" data-unit="${unit}" role="img" aria-label="${tr("配线图")}">`];
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
			const label = `${pinned ? "● " : ""}${trackShort(p.id, p.name, p.passingTrack)}${!ours && p.stationName && p.station !== station ? " · " + shortName(p.stationName) : ""}`;
			const title = ours ? `${trackLabel(p.id, p.name, p.passingTrack)}：${pinned ? tr("已固定，点一下取消") : dead ? tr("和其他站连不起来，点一下仍可固定") : tr("点一下固定走这一股")}` : `${shortName(p.stationName) || tr("不属于车站")} ${p.name || "?"}${p.station === station ? tr("：和这条线的轨道不连") : ""}`;
			// The name goes before the left end, where tracks side by side don't crowd it
			const start = a[0] <= b[0] ? a : b;
			parts.push(`<g class="${classes}"${ours ? ` data-stop="${i}" data-track="${index}" tabindex="0"` : ""}><title>${escapeHtml(title)}</title><line x1="${a[0]}" y1="${a[1]}" x2="${b[0]}" y2="${b[1]}" stroke-width="${unit * (on ? 7 : 5)}"${on ? ` style="stroke:${model.color}"` : ""}${dead ? ` stroke-dasharray="${unit * 4} ${unit * 3}"` : ""}/><text x="${start[0] - unit * 6}" y="${start[1] + unit * 4}" font-size="${unit * 11}" text-anchor="end">${escapeHtml(label)}</text></g>`);
		});
		parts.push(`</svg>`);
		const before = i > 0 ? `← ${escapeHtml(stationLabel(model.layers[i - 1][0]))}` : "";
		const after = i + 1 < n ? `${escapeHtml(stationLabel(model.layers[i + 1][0]))} →` : "";
		return `<div class="layout">${head}<div class="layout-ends"><span>${before}</span><span>${after}</span></div><div class="layout-wrap">${parts.join("")}</div>${data.truncated ? `<p class="hint">${tr("轨道太多，只画了一部分。")}</p>` : ""}<p class="hint">${tr("列车从左往右开。灰色的是别的车站或轨道不连的站台；拖动平移，＋/− 缩放。")}</p>${throatHtml(model.route, i)}${turnHtml(model, i)}</div>`;
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
		const turn = target.closest("[data-turn]");
		if (turn) {
			if (model.route) toggleTurnPlatform(model.route, turn.dataset.turn, turn.dataset.platform);
			return true;
		}
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
			model.conflict = `${tr("固定的站台连不起来：{0}。取消其中一个再试。", names.join("、"))}`;
		}
		return true;
	}

	/** A picker model for stations given as tracks, offering the other tracks of each station on the same rails. */
	function topologyModel(tracks, stop, color) {
		return { layers: orderByLayout(tracks.map(stationTracks)), picks: tracks.slice(), stop: stop.slice(), pins: new Map(), color, conflict: "" };
	}

	/**
	 * Puts each station's tracks in the order they lie side by side, as the track diagram shows them: across the way from
	 * the station before to the station after, the leftmost first. Platform names are whatever players typed, so they
	 * don't say where a track is.
	 */
	function orderByLayout(layers) {
		const middle = p => {
			const line = topo.platforms[p].line;
			return line ? [(line[0] + line[2]) / 2, (line[1] + line[3]) / 2] : null;
		};
		const centres = layers.map(layer => {
			const points = layer.map(middle).filter(Boolean);
			return points.length ? [points.reduce((a, p) => a + p[0], 0) / points.length, points.reduce((a, p) => a + p[1], 0) / points.length] : null;
		});
		return layers.map((layer, i) => {
			const centre = centres[i];
			if (!centre || layer.length < 2) return layer;
			const before = centres.slice(0, i).reverse().find(Boolean) || centre, after = centres.slice(i + 1).find(Boolean) || centre;
			let dx = after[0] - before[0], dz = after[1] - before[1];
			if (Math.hypot(dx, dz) < 1E-6) {
				const line = topo.platforms[layer[0]].line;
				if (!line) return layer;
				dx = line[2] - line[0];
				dz = line[3] - line[1];
			}
			// With x east and z south, left of the way along has a negative cross product
			const left = p => {
				const point = middle(p);
				return point ? -(dx * (point[1] - centre[1]) - dz * (point[0] - centre[0])) : 0;
			};
			return layer.slice().sort((a, b) => left(b) - left(a) || platformLabel(a).localeCompare(platformLabel(b), undefined, { numeric: true }));
		});
	}

	function stopsSummaryOf(model) {
		const n = model.layers.length;
		const passed = model.layers.filter((tracks, i) => !model.stop[i] && i > 0 && i < n - 1).map(tracks => stationLabel(tracks[0]));
		return `${tr("{0} 站，停 {1} 站{2}", n, n - passed.length, passed.length ? `${tr("，通过 {0} 站：{1}", passed.length, passed.join("、"))}` : tr("，全部停车"))}`;
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
				routeError = tr("选好不同的起点站和终点站");
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
				routeError = `${tr("从{0}{1}到{2}沿轨道走不通", shortName(stations.find(o => o.station === from).name), vias.length ? tr("经过选的途经站") : "", shortName(stations.find(o => o.station === to).name))}`;
				return;
			}
			models.push(forward);
			if (both) {
				const backward = make(tracksOf(to), tracksOf(from), vias.slice().reverse(), counterpartOf(base), -1);
				if (backward) models.push(backward);
				else routeError = tr("反方向沿轨道走不通，只建这个方向");
			}
		};
		const renderPicker = form => {
			plan(form);
			models.forEach(m => {
				m.color = form.color.value;
				m.onChange = () => renderPicker(form);
			});
			const stationOptions = selected => stations.map(s => `<option value="${s.station}"${s.station === selected ? " selected" : ""}>${escapeHtml(s.name)}</option>`).join("");
			form.querySelector("#dlg-vias").innerHTML = vias.map((station, k) => `<button type="button" class="table-chip" data-via="${k}" title="${tr("点一下去掉")}">${escapeHtml((stations.find(o => o.station === station) || { name: "?" }).name)} ✕</button>`).join("") + `<select name="addVia"><option value="">${tr("＋ 途经站…")}</option>${stationOptions("")}</select>`;
			const model = models[Math.min(pickDir, models.length - 1)];
			form.querySelector(".dir-tabs").innerHTML = models.length > 1 ? models.map((m, i) => `<button type="button" class="table-chip${i === pickDir ? " on" : ""}" data-dir="${i}">${escapeHtml(stationLabel(m.picks[0]))} → ${escapeHtml(stationLabel(m.picks[m.picks.length - 1]))}</button>`).join("") : "";
			form.querySelector("#dlg-pick").innerHTML = model ? topologyHtml(model) : "";
			form.querySelector("#dlg-pick-summary").textContent = model ? stopsSummaryOf(model) : "";
			form.querySelector("#dlg-route-error").textContent = routeError;
		};
		openDialog(tr("新建种别"), `
			<div class="row"><label>${tr("线路组")} <select name="base">${line.routes.filter(r => r.stops.length > 1).map(r => `<option value="${r.id}"${r === base0 ? " selected" : ""}>${escapeHtml(routeTitle(r))}</option>`).join("")}</select></label></div>
			<div class="row"><label>${tr("起点")} <select name="from"></select></label><label>${tr("站台")} <select name="fromTrack"></select></label><label>${tr("终点")} <select name="to"></select></label></div>
			<div class="row vias" id="dlg-vias"></div>
			<label><input type="checkbox" name="both" checked> ${tr("反方向一起建")}</label>
			<div class="row"><label>${tr("种别")} <input name="type" required placeholder="${tr("急行")}" maxlength="16" size="8" autocomplete="off"></label>
			<label>${tr("颜色")} <input type="color" name="color" value="#2f9e55"></label></div>
			<label>${tr("线路名")} <input name="name" maxlength="128" size="28" autocomplete="off" placeholder="${tr("按线路组和种别自动起名")}"></label>
			<div class="picker">
				<div class="picker-head"><b>${tr("车站和股道")}</b><span class="dir-tabs"></span></div>
				<p class="hint">${tr("车站是沿轨道从起点到终点（依次经过途经站）最短的走法经过的车站，不照抄现有线路。只列和起点轨道连在一起的车站。连线表示列车不掉头能开过去；点一股固定它，其他站自动换成走得通的股道；最下一行点一下在停车和通过之间切换。")}</p>
				<p class="err" id="dlg-route-error"></p>
				<div id="dlg-pick"></div>
				<div class="hint" id="dlg-pick-summary"></div>
			</div>
			<label>${tr("班次")} <input name="pattern" placeholder="${tr("可以留空，例如 08:05 + 12 * 00:30")}" autocomplete="off"></label>
			<label>${tr("加入车厂")} <select name="depot"></select></label>
			<p class="hint">${tr("线路组的这条线路给出颜色、时刻表和车厂。新种别先放在草稿里，按“应用”才在游戏里建出来，并由高级编辑器管理；加入车厂会刷新车厂路径，路径刷新后才有最快时刻。")}</p>`, tr("新建"), form => {
			const type = form.type.value.trim();
			if (!type) return tr("写上种别名，例如 急行");
			if (!models.length) return routeError || tr("选好起点和终点");
			if (models.some(m => m.conflict)) return tr("有固定的股道连不起来");
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
				form.depot.innerHTML = list.map(depot => `<option value="${depot.id}">${escapeHtml(shortName(depot.name))}</option>`).join("") + `<option value="">${tr("不加入，之后在游戏里加")}</option>`;
			}
			if (form.fromTrack.dataset.station !== form.from.value) {
				form.fromTrack.dataset.station = form.from.value;
				form.fromTrack.innerHTML = `<option value="">${tr("任一股")}</option>` + tracksOf(form.from.value).map(p => `<option value="${p}">${escapeHtml(trackLabel(topo.platforms[p].id, platformLabel(p), topo.platforms[p].passingTrack))}</option>`).join("");
			}
			if (form.addVia && form.addVia.value) {
				vias.push(form.addVia.value);
				form.addVia.value = "";
			}
			form.name.placeholder = form.type.value.trim() ? typedName(data.routeById.get(form.base.value), form.type.value.trim()) : tr("按线路组和种别自动起名");
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

	/**
	 * A new line from nothing (L4): pick the transport mode, name the line and its type, and choose its stations along the
	 * rails as for a new type, with the track picker. A new line has only its stations (the user's rule): no services and
	 * no depot, which are added afterwards as for any route. Both ways are named up and down by where their ends lie,
	 * north or east being up (L2).
	 */
	function newLineDialog() {
		if (!data || !data.canEdit) return;
		const modes = [...new Set(topo.platforms.filter(p => p.station && p.mode).map(p => p.mode))].sort();
		if (!modes.length) {
			openDialog(tr("新建线路"), `<p class="err">${tr("游戏里还没有属于车站、放在轨道上的站台，先在游戏里建车站和站台。")}</p>`, tr("关闭"), () => {});
			return;
		}
		const modeNames = { TRAIN: tr("铁路"), BOAT: tr("船"), CABLE_CAR: tr("缆车"), AIRPLANE: tr("飞机") };
		let stations = [], vias = [], models = [], lastMode = null, lastFrom = null, lastKey = null, pickDir = 0, routeError = "";
		// A station once for each rail network it has tracks on, named with the network when it has tracks on several
		const stationsOf = mode => {
			const seen = new Map();
			topo.platforms.forEach((p, i) => {
				const key = p.station + "|" + p.network;
				if (p.mode === mode && p.station && !seen.has(key)) seen.set(key, { key, station: p.station, network: p.network, track: i, name: shortName(p.stationName) });
			});
			const list = [...seen.values()];
			const names = new Map();
			list.forEach(s => names.set(s.name, (names.get(s.name) || 0) + 1));
			list.forEach(s => { if (names.get(s.name) > 1) s.name += tr("（轨道网 {0}）", s.network); });
			return list.sort((a, b) => a.name.localeCompare(b.name, "zh-Hans-CN"));
		};
		const find = key => stations.find(s => s.key === key);
		const tracksOf = key => {
			const s = find(key);
			return s ? stationTracks(s.track) : [];
		};
		const directionOfModel = model => {
			const middle = p => topo.platforms[p].line ? [(topo.platforms[p].line[0] + topo.platforms[p].line[2]) / 2, (topo.platforms[p].line[1] + topo.platforms[p].line[3]) / 2] : null;
			const a = middle(model.picks[0]), b = middle(model.picks[model.picks.length - 1]);
			if (!a || !b) return "";
			const dx = b[0] - a[0], dz = b[1] - a[1];
			return Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? "up" : "down") : (dz < 0 ? "up" : "down");
		};
		const plan = form => {
			const from = form.from.value, to = form.to.value, startTrack = form.fromTrack.value, both = form.both.checked;
			const key = [form.mode.value, from, to, startTrack, both, vias.join(">")].join("|");
			if (key === lastKey) return;
			lastKey = key;
			models = [];
			routeError = "";
			if (!from || !to || from === to) {
				routeError = tr("选好不同的起点站和终点站");
				return;
			}
			const make = (start, ends, stops, pinnedStart) => {
				const tracks = findCorridor([start, ...stops.map(tracksOf), ends]);
				if (!tracks || tracks.length < 2) return null;
				const model = topologyModel(tracks, tracks.map(() => true), form.color.value);
				if (pinnedStart >= 0) model.pins.set(0, pinnedStart);
				const solution = solveStops(model.layers, model.picks, model.pins);
				if (solution.ok) model.picks = solution.picks;
				return model;
			};
			const forward = make(startTrack ? [+startTrack] : tracksOf(from), tracksOf(to), vias, startTrack ? +startTrack : -1);
			if (!forward) {
				routeError = tr("从{0}{1}到{2}沿轨道走不通", find(from).name, vias.length ? tr("经过选的途经站") : "", find(to).name);
				return;
			}
			models.push(forward);
			if (both) {
				const backward = make(tracksOf(to), tracksOf(from), vias.slice().reverse(), -1);
				if (backward) models.push(backward);
				else routeError = tr("反方向沿轨道走不通，只建这个方向");
			}
		};
		const nameOf = (form, model) => {
			const base = `${form.line.value.trim()} ${form.type.value.trim()}`.trim();
			if (models.length < 2) return base;
			const direction = directionOfModel(model);
			return direction ? `${base} ${tr(TRACK_DIRECTIONS[direction])}` : base;
		};
		const renderPicker = form => {
			plan(form);
			models.forEach(m => {
				m.color = form.color.value;
				m.onChange = () => renderPicker(form);
			});
			const network = find(form.from.value) ? find(form.from.value).network : null;
			const sameNetwork = stations.filter(s => s.network === network);
			form.querySelector("#dlg-vias").innerHTML = vias.map((key, k) => `<button type="button" class="table-chip" data-via="${k}" title="${tr("点一下去掉")}">${escapeHtml((find(key) || { name: "?" }).name)} ✕</button>`).join("") + `<select name="addVia"><option value="">${tr("＋ 途经站…")}</option>${sameNetwork.map(s => `<option value="${s.key}">${escapeHtml(s.name)}</option>`).join("")}</select>`;
			const model = models[Math.min(pickDir, models.length - 1)];
			form.querySelector(".dir-tabs").innerHTML = models.length > 1 ? models.map((m, i) => `<button type="button" class="table-chip${i === pickDir ? " on" : ""}" data-dir="${i}">${escapeHtml(stationLabel(m.picks[0]))} → ${escapeHtml(stationLabel(m.picks[m.picks.length - 1]))}</button>`).join("") : "";
			form.querySelector("#dlg-pick").innerHTML = model ? topologyHtml(model) : "";
			form.querySelector("#dlg-pick-summary").textContent = model ? stopsSummaryOf(model) : "";
			form.querySelector("#dlg-route-error").textContent = routeError;
			form.querySelector("#dlg-names").textContent = models.length && form.line.value.trim() && form.type.value.trim() ? tr("建出：{0}", models.map(m => nameOf(form, m)).join("、")) : "";
		};
		openDialog(tr("新建线路"), `
			<div class="row"><label>${tr("交通方式")} <select name="mode">${modes.map(mode => `<option value="${mode}">${escapeHtml(modeNames[mode] || mode)}</option>`).join("")}</select></label>
			<label>${tr("线路组名")} <input name="line" required maxlength="128" size="16" autocomplete="off" placeholder="${tr("1号线")}"></label></div>
			<div class="row"><label>${tr("种别")} <input name="type" required placeholder="${tr("普通")}" maxlength="16" size="8" autocomplete="off"></label>
			<label>${tr("颜色")} <input type="color" name="color" value="#2f6fd6"></label></div>
			<div class="row"><label>${tr("起点")} <select name="from"></select></label><label>${tr("站台")} <select name="fromTrack"></select></label><label>${tr("终点")} <select name="to"></select></label></div>
			<div class="row vias" id="dlg-vias"></div>
			<label><input type="checkbox" name="both" checked> ${tr("反方向一起建")}</label>
			<div class="picker">
				<div class="picker-head"><b>${tr("车站和股道")}</b><span class="dir-tabs"></span></div>
				<p class="hint">${tr("车站是沿轨道从起点到终点（依次经过途经站）最短的走法经过的车站。只列和起点轨道连在一起的车站。连线表示列车不掉头能开过去；点一股固定它，其他站自动换成走得通的股道；最下一行点一下在停车和通过之间切换。")}</p>
				<p class="err" id="dlg-route-error"></p>
				<div id="dlg-pick"></div>
				<div class="hint" id="dlg-pick-summary"></div>
			</div>
			<p class="hint" id="dlg-names"></p>
			<p class="hint">${tr("新线路只有车站表，没有班次和运用；时刻表、班次和车厂建好以后再加。先放在草稿里，按“应用”才在游戏里建出来，并由高级编辑器管理。")}</p>`, tr("新建"), form => {
			const lineName = form.line.value.trim(), type = form.type.value.trim();
			if (!lineName) return tr("写上线路组名，例如 1号线");
			if (data.lines.some(l => l.key === form.mode.value + "|" + lineName.toLowerCase())) return tr("已经有这个线路组了，请用“新建 ▾ → 种别…”");
			if (!type) return tr("写上种别名，例如 普通");
			if (!models.length) return routeError || tr("选好起点和终点");
			if (models.some(m => m.conflict)) return tr("有固定的股道连不起来");
			const color = parseInt(form.color.value.slice(1), 16);
			models.forEach((model, i) => {
				draft.created.push({
					key: "n" + Date.now().toString(36) + i, source: "", mode: form.mode.value, lineName, lineKey: form.mode.value + "|" + lineName.toLowerCase(), name: nameOf(form, model), serviceType: type, color,
					stops: model.picks.map((p, k) => ({ platformId: topo.platforms[p].id, pass: !model.stop[k] && k > 0 && k < model.picks.length - 1 })),
					services: [], table: 0, depot: "",
				});
			});
			saveDraft();
			renderAll();
		}, form => {
			if (form.mode.value !== lastMode) {
				lastMode = form.mode.value;
				stations = stationsOf(lastMode);
				vias = [];
				lastFrom = null;
				form.from.innerHTML = stations.map(s => `<option value="${s.key}">${escapeHtml(s.name)}</option>`).join("");
			}
			if (form.from.value !== lastFrom) {
				lastFrom = form.from.value;
				const network = find(lastFrom) ? find(lastFrom).network : null;
				// Only the stations the start's rails reach
				const others = stations.filter(s => s.network === network && s.key !== lastFrom);
				form.to.innerHTML = others.map(s => `<option value="${s.key}">${escapeHtml(s.name)}</option>`).join("");
				vias = vias.filter(key => find(key) && find(key).network === network);
				form.fromTrack.innerHTML = `<option value="">${tr("任一股")}</option>` + tracksOf(lastFrom).map(p => `<option value="${p}">${escapeHtml(trackLabel(topo.platforms[p].id, platformLabel(p), topo.platforms[p].passingTrack))}</option>`).join("");
			}
			if (form.addVia && form.addVia.value) {
				vias.push(form.addVia.value);
				form.addVia.value = "";
			}
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
	function stopsDialog(r, openStop) {
		const ids = platformsOf(r), pass = timetableOf(r).pass;
		const tracks = ids.map(id => topo.index.has(id) ? topo.index.get(id) : -1);
		if (tracks.some(p => p < 0)) {
			openDialog(`${tr("停站：{0}", escapeHtml(routeTitle(r)))}`, `<p class="err">${tr("有车站的站台没有轨道，没法按轨道选股道。先在游戏里把站台放回轨道上。")}</p>`, tr("关闭"), () => {});
			return;
		}
		const model = topologyModel(tracks, r.stops.map((stop, i) => !pass[i]), colorOf(r.color));
		// The route edited, for its stops' throats in the station diagram
		model.route = r;
		const problemsHtml = () => {
			const problems = routeProblems(r);
			return problems.length ? `<ul class="warn">${problems.map(p => `<li class="${p.level}">${escapeHtml(p.text)}</li>`).join("")}</ul>` : `<p class="hint">${tr("现在的股道沿轨道走得通，没有掉头和绕路。")}</p>`;
		};
		const render = form => {
			form.querySelector("#dlg-problems").innerHTML = problemsHtml();
			form.querySelector("#dlg-pick").innerHTML = topologyHtml(model);
			form.querySelector("#dlg-pick-summary").textContent = stopsSummaryOf(model);
		};
		openDialog(`${tr("停站：{0}", escapeHtml(routeTitle(r)))}`, `
			<div id="dlg-problems"></div>
			<div class="picker">
				<p class="hint">${tr("连线表示列车不掉头能开过去。点一股固定它，其他站自动换成走得通、改动最少的股道；最下一行点一下在停车和通过之间切换。")} <button type="button" data-stop-list>${tr("增删和排序停站…")}</button></p>
				<div id="dlg-pick"></div>
				<div class="hint" id="dlg-pick-summary"></div>
			</div>
			<p class="hint">${tr("改股道会清掉线路手画的路径，应用后要刷新车厂路径。")}</p>`, tr("确定"), () => {
			if (model.conflict) return tr("有固定的股道连不起来");
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
		if (openStop >= 0 && openStop < model.layers.length) {
			model.diagram = { stop: openStop, station: topo.platforms[model.layers[openStop][0]].station, margin: 48, pan: [0, 0], data: null, error: "" };
			loadDiagram(model, model.diagram, 0);
			render(form);
		}
		form.addEventListener("click", e => {
			if (e.target.closest("[data-stop-list]")) {
				dialog.close();
				stopListDialog(r);
				return;
			}
			if (topologyClick(model, e.target)) render(form);
		});
	}

	/**
	 * The stops of a route in order, to add, take away and move (web layers, step 1, 2026-11-09). Sent to the game at once,
	 * as its times along the new stops come from the game: with the route's timetable, the through services of other routes
	 * into it and the duties running it, renumbered to the new stops, and the same done to them in the draft. A stop with a
	 * through service, a depot order or the start or end of a duty segment on it isn't taken away (user's choice).
	 */
	function stopListDialog(r) {
		const pass = timetableOf(r).pass;
		const list = r.stops.map((stop, i) => ({ platformId: stop.platformId, from: i, pass: Boolean(pass[i]) }));
		let message = "";
		const platformName = id => platformText(id);
		const stationPlatforms = (topo ? topo.platforms : []).filter(platform => platform.station);
		const stations = [...new Map(stationPlatforms.map(platform => [platform.station, platform.stationName])).entries()].sort((a, b) => String(a[1]).localeCompare(String(b[1])));
		const render = form => {
			form.querySelector("#dlg-stop-list").innerHTML = `<table class="stop-list">${list.map((stop, i) => `<tr>
				<td class="num">${i + 1}</td><td>${escapeHtml(platformName(stop.platformId))}${stop.from < 0 ? ` <span class="pill">${tr("新加")}</span>` : ""}</td>
				<td>${i > 0 && i < list.length - 1 ? `<label class="hint"><input type="checkbox" data-stop-pass="${i}"${stop.pass ? " checked" : ""}> ${tr("通过")}</label>` : ""}</td>
				<td><button type="button" data-stop-up="${i}"${i === 0 ? " disabled" : ""}>↑</button><button type="button" data-stop-down="${i}"${i === list.length - 1 ? " disabled" : ""}>↓</button><button type="button" data-stop-remove="${i}"${list.length <= 2 ? " disabled" : ""}>${tr("删除")}</button><button type="button" data-stop-insert="${i}">${tr("在后面插入")}</button></td></tr>`).join("")}</table>
				<div class="stop-insert" id="dlg-stop-insert" hidden><label>${tr("车站")} <select name="station">${stations.map(([id, name]) => `<option value="${escapeHtml(id)}">${escapeHtml(shortName(name) || id)}</option>`).join("")}</select></label>
				<label>${tr("站台")} <select name="platform"></select></label> <button type="button" data-stop-insert-ok>${tr("插入")}</button></div>
				${message ? `<p class="err">${escapeHtml(message)}</p>` : ""}`;
		};
		let insertAfter = -1;
		const fillPlatforms = form => {
			const station = form.querySelector("select[name=station]").value;
			form.querySelector("select[name=platform]").innerHTML = stationPlatforms.filter(platform => platform.station === station).map(platform => `<option value="${escapeHtml(platform.id)}">${escapeHtml(platform.name || platform.id)}</option>`).join("");
		};
		openDialog(tr("增删和排序停站：{0}", escapeHtml(routeTitle(r))), `
			<p class="hint">${tr("改好后点“发给游戏”马上生效：时刻表、直通、出入库指令和运用按新的站序重新对应，草稿里的也一样。股道在“停站”里再挑。")}</p>
			<div id="dlg-stop-list"></div>`, tr("发给游戏"), () => {
			const result = sendStopList(r, list);
			if (result) return result;
		}, render);
		const form = dialog.querySelector("form");
		form.addEventListener("change", e => {
			if (e.target.matches("select[name=station]")) fillPlatforms(form);
			const passBox = e.target.closest("[data-stop-pass]");
			if (passBox) list[+passBox.dataset.stopPass].pass = passBox.checked;
		});
		form.addEventListener("click", e => {
			const button = e.target.closest("button");
			if (!button) return;
			message = "";
			if (button.dataset.stopUp !== undefined) {
				const i = +button.dataset.stopUp;
				[list[i - 1], list[i]] = [list[i], list[i - 1]];
			} else if (button.dataset.stopDown !== undefined) {
				const i = +button.dataset.stopDown;
				[list[i], list[i + 1]] = [list[i + 1], list[i]];
			} else if (button.dataset.stopRemove !== undefined) {
				const i = +button.dataset.stopRemove;
				const hanging = list[i].from < 0 ? [] : stopHangers(r, list[i].from);
				if (hanging.length) message = tr("这一站上还挂着：{0}。先处理掉再删。", hanging.join(tr("；")));
				else list.splice(i, 1);
			} else if (button.dataset.stopInsert !== undefined) {
				insertAfter = +button.dataset.stopInsert;
				render(form);
				const box = form.querySelector("#dlg-stop-insert");
				box.hidden = false;
				fillPlatforms(form);
				return;
			} else if (button.dataset.stopInsertOk !== undefined) {
				const platformId = form.querySelector("select[name=platform]").value;
				if (platformId) list.splice(insertAfter + 1, 0, { platformId, from: -1, pass: false });
			} else {
				return;
			}
			// The first and last stops are always stopped at
			list.forEach((stop, i) => { if (i === 0 || i === list.length - 1) stop.pass = false; });
			render(form);
		});
	}

	/**
	 * @return what keeps a stop of a route from being taken away, as the game and the draft have them: through services
	 * from it or into it, depot orders at it, duty segments starting or ending at it
	 */
	function stopHangers(r, stop) {
		const found = [];
		const timetables = [gameTimetable(r), timetableOf(r)];
		timetables.forEach(tt => tt.tables.forEach(table => {
			table.links.forEach(link => { if (link.fromStop === stop) found.push(tr("{0} 班直通", clock(mod(link.service - data.dayStartMillis, DAY)))); });
			(table.orders || []).forEach(order => { if (order.stop === stop) found.push(tr("{0} 班出入库指令", clock(mod(order.service - data.dayStartMillis, DAY)))); });
		}));
		data.routes.forEach(other => [gameTimetable(other), timetableOf(other)].forEach(tt => tt.tables.forEach(table => table.links.forEach(link => {
			if (link.route === r.id && link.toStop === stop) found.push(tr("{0} 直通接入", routeTitle(other)));
		}))));
		data.depots.forEach(depot => [depot.duties, dutiesOf(depot)].forEach(duties => Object.values((duties || {}).sidings || {}).forEach(slots => slots.forEach(slot => slot.forEach(day => day.segments.forEach(segment => {
			if (segment.route === r.id && (segment.from === stop && stop > 0 || segment.to === stop)) found.push(tr("车厂{0}的运用起止", shortName(depot.name)));
		}))))));
		return [...new Set(found)];
	}

	/** A route's timetable renumbered to its new stops: newOf[old stop] is its new number, or -1 if it was taken away. */
	function renumberTimetable(tt, list, newOf) {
		const copy = JSON.parse(JSON.stringify(tt));
		copy.pass = list.map(stop => stop.pass);
		copy.allowLonger = list.map(stop => stop.from >= 0 ? Boolean((tt.allowLonger || [])[stop.from]) : false);
		copy.tables.forEach(table => {
			table.stops = list.map(stop => stop.from >= 0 && table.stops[stop.from] ? table.stops[stop.from] : { arrival: -1, departure: -1 });
			table.orders = (table.orders || []).map(order => Object.assign({}, order, { stop: newOf[order.stop] })).filter(order => order.stop >= 0);
			table.links = table.links.map(link => Object.assign({}, link, { fromStop: link.fromStop < 0 ? link.fromStop : newOf[link.fromStop] }));
			table.exceptions = table.exceptions.map(exception => ({ service: exception.service, stops: exception.stops.map(stop => Object.assign({}, stop, { stop: newOf[stop.stop] })).filter(stop => stop.stop >= 0) })).filter(exception => exception.stops.length);
		});
		return normalize(copy);
	}

	/** Through services of another route into this one, renumbered to its new stops. */
	function renumberLinksInto(tt, routeId, newOf) {
		const copy = JSON.parse(JSON.stringify(tt));
		let changed = false;
		copy.tables.forEach(table => table.links.forEach(link => {
			if (link.route === routeId && link.toStop >= 0 && newOf[link.toStop] !== link.toStop) {
				link.toStop = newOf[link.toStop];
				changed = true;
			}
		}));
		return changed ? copy : null;
	}

	/** A depot's duties with the segments of this route renumbered to its new stops, or null if none is. */
	function renumberDuties(duties, routeId, newOf, newCount) {
		if (!duties) return null;
		const copy = JSON.parse(JSON.stringify(duties));
		let changed = false;
		Object.values(copy.sidings || {}).forEach(slots => slots.forEach(slot => slot.forEach(day => day.segments.forEach(segment => {
			if (segment.route !== routeId) return;
			// From the first stop and to the last stay so; others follow their stops
			const from = segment.from > 0 ? newOf[segment.from] : 0;
			const to = segment.to < 0 ? segment.to : newOf[segment.to];
			if (from !== segment.from || to !== segment.to) {
				segment.from = from;
				segment.to = to;
				changed = true;
			}
		}))));
		return changed ? copy : null;
	}

	/**
	 * Sends a route's new stops to the game with what counts its stops, and renumbers the same in the draft.
	 *
	 * @return why it can't, or nothing
	 */
	function sendStopList(r, list) {
		if (list.length < 2) return tr("至少要两站");
		const newOf = r.stops.map(() => -1);
		list.forEach((stop, i) => { if (stop.from >= 0) newOf[stop.from] = i; });
		const operations = [{ type: "stops", route: r.id, force: true, stops: list.map(stop => ({ platformId: stop.platformId, from: stop.from, pass: stop.pass })) }];
		operations.push({ type: "timetable", route: r.id, force: true, timetable: renumberTimetable(gameTimetable(r), list, newOf) });
		data.routes.forEach(other => {
			if (other === r) return;
			const renumbered = renumberLinksInto(gameTimetable(other), r.id, newOf);
			if (renumbered) operations.push({ type: "timetable", route: other.id, force: true, timetable: renumbered });
		});
		data.depots.forEach(depot => {
			const renumbered = renumberDuties(depot.duties, r.id, newOf, list.length);
			if (renumbered && renumbered.mode === "diagram") operations.push({ type: "duties", depot: depot.id, duties: renumbered });
		});
		// The draft follows the new stops too
		const entry = draftOf(r);
		if (entry && entry.timetable) entry.timetable = renumberTimetable(entry.timetable, list, newOf);
		if (entry) delete entry.platforms;
		data.routes.forEach(other => {
			const otherEntry = draftOf(other);
			if (other !== r && otherEntry && otherEntry.timetable) {
				const renumbered = renumberLinksInto(otherEntry.timetable, r.id, newOf);
				if (renumbered) otherEntry.timetable = renumbered;
			}
		});
		Object.values(draft.duties || {}).forEach(drafted => {
			const renumbered = renumberDuties(drafted.duties, r.id, newOf, list.length);
			if (renumbered) drafted.duties = renumbered;
		});
		saveDraft();
		(async () => {
			try {
				const result = await sendJson("api/apply", "POST", { operations });
				const errors = (result.errors || []).map(error => error.message).filter(Boolean);
				dutyNotice = errors.length ? tr("停站没改成：{0}", errors.join(tr("；"))) : tr("“{0}”的停站已发给游戏，{1} 站。车厂路径刷新后各站时刻会更新。", routeTitle(r), list.length);
			} catch (e) {
				dutyNotice = tr("发送失败，游戏可能已经关闭");
			}
			renderBanner();
			revision = null;
			poll(true);
		})();
	}

	/**
	 * The track diagram of a whole route: the rails along its path straightened by the server (RouteLayouts), the distance
	 * along the path across and the offset to the side up and down, left of the way the route runs at the top. It shows the
	 * route as it is in the game; track changes in the draft show once applied.
	 */
	/**
	 * Which route's track diagram each section of a line's stations is drawn from: the route going furthest along the
	 * section in one go, from the stop it leaves at to the stop it gets to.
	 *
	 * @return {section, r, from, to, reversed}, reversed when the route goes up the section's stations
	 */
	function linePieces(line) {
		const best = new Map();
		line.routes.forEach(r => {
			const n = r.rows.length;
			const step = i => {
				const a = r.leaveRows[i - 1], b = r.rows[i];
				return a !== b && Math.abs(a - b) === 1 && line.rows[a].section === line.rows[b].section ? Math.sign(b - a) : 0;
			};
			let start = -1, way = 0;
			const close = end => {
				if (start < 0 || end <= start) return;
				const section = line.rows[r.leaveRows[start]].section, found = best.get(section);
				if (!found || end - start > found.to - found.from) best.set(section, { section, r, from: start, to: end, reversed: way < 0 });
			};
			for (let i = 1; i < n; i++) {
				const direction = step(i);
				if (start >= 0 && (!direction || direction !== way || r.rows[i - 1] !== r.leaveRows[i - 1])) {
					close(i - 1);
					start = -1;
				}
				if (direction && start < 0) {
					start = i - 1;
					way = direction;
				}
			}
			close(n - 1);
		});
		return [...best.values()].sort((a, b) => a.section - b.section);
	}

	/**
	 * The track diagram of a whole line, from the track diagrams of its routes (EditorLayout): each section of its stations
	 * is a strip, the first along the top and each other below, slanting off the station it starts from. A loop is folded
	 * in two, going along the top of its strip and back along the bottom.
	 */
	function lineDiagramDialog(line) {
		const pieces = linePieces(line);
		const routes = [...new Set(pieces.map(piece => piece.r))];
		const view = { scale: 0, side: 2, layouts: new Map(), error: "", even: true, gap: 0 };
		const render = form => {
			form.querySelector("#dlg-line-diagram").innerHTML = lineDiagramHtml(line, pieces, view);
			const even = form.querySelector("[data-line-even]");
			if (even) even.textContent = view.even ? tr("按实际距离画") : tr("车站等距画");
		};
		openDialog(`${tr("线路组配线图：{0}", escapeHtml(shortName(line.name)))}`, `
			<div class="diagram-head"><span class="hint">${tr("每一段沿走得最远的一条线路的路径拉直，从左往右按运行图上车站的顺序；支线和另一路画在下面，从分出的车站斜着接出。按游戏里已应用的线路画。")}</span>
				<span class="tools"><button type="button" data-line-even></button><button type="button" data-line-zoom="in" title="${tr("放大")}">＋</button><button type="button" data-line-zoom="out" title="${tr("缩小")}">−</button></span></div>
			<div id="dlg-line-diagram"></div>`, tr("关闭"), () => {});
		dialog.classList.add("wide");
		dialog.addEventListener("close", () => dialog.classList.remove("wide"), { once: true });
		const form = dialog.querySelector("form");
		render(form);
		form.addEventListener("click", e => {
			if (e.target.closest("[data-line-even]")) {
				view.even = !view.even;
				render(form);
				return;
			}
			const zoom = e.target.closest("[data-line-zoom]");
			if (!zoom) return;
			view.scale = Math.max(0.05, Math.min(8, view.scale * (zoom.dataset.lineZoom === "in" ? 1.5 : 1 / 1.5)));
			render(form);
		});
		// The game keeps one route's track diagram at a time, so they are asked for one after another
		(async () => {
			for (const r of routes) {
				for (let tries = 0; ; tries++) {
					let result = null;
					try {
						result = await sendJson("api/route-layout", "POST", { route: r.id, halfWidth: LINE_DIAGRAM_HALF_WIDTH, fresh: tries === 0 });
					} catch (e) {
						view.error = tr("读取线路配线图失败");
					}
					if (result && result.ready) {
						view.layouts.set(r.id, result);
						break;
					}
					if (tries >= 15) {
						view.error = tr("服务端没有回应，关掉再打开试试");
						break;
					}
					if (result) view.error = tr("正在沿路径取轨道…（{0}/{1}）", view.layouts.size + 1, routes.length);
					if (dialog.open) render(form);
					await new Promise(resolve => setTimeout(resolve, 700));
				}
				if (!dialog.open) return;
			}
			if (view.layouts.size === routes.length) view.error = "";
			if (!view.scale) {
				let longest = 1, gap = 0;
				pieces.forEach(piece => {
					const layout = view.layouts.get(piece.r.id);
					const length = layout && layout.found ? layout.stops[piece.to] - layout.stops[piece.from] : 0;
					if (length > longest) {
						longest = length;
						// Evenly apart, the longest section is as wide as by distance
						gap = length / Math.max(1, piece.to - piece.from);
					}
				});
				view.gap = gap || longest;
				view.scale = Math.max(0.05, Math.min(8, (form.querySelector("#dlg-line-diagram").clientWidth - 160) / longest));
			}
			if (dialog.open) render(form);
		})();
	}

	function lineDiagramHtml(line, pieces, view) {
		if (!view.scale) return `<p class="hint">${escapeHtml(view.error || tr("正在读取…"))}</p>`;
		const pad = 32, slant = 40, sidePx = view.side * 2, laneH = LINE_DIAGRAM_HALF_WIDTH * sidePx * 2;
		const parts = [], labels = [], links = [], missing = [];
		const stationX = new Map();
		let top = 36, right = 0;
		pieces.forEach(piece => {
			const layout = view.layouts.get(piece.r.id), r = piece.r;
			if (!layout || !layout.found || layout.stops[piece.from] < 0 || layout.stops[piece.to] < 0) {
				missing.push(routeTitle(r));
				return;
			}
			const rows = line.rows.map((row, i) => i).filter(i => line.rows[i].section === piece.section);
			const firstRow = rows[0], lastRow = rows[rows.length - 1];
			const ring = line.rows[firstRow].id === line.rows[lastRow].id;
			const s0 = layout.stops[piece.from], s1 = layout.stops[piece.to], length = s1 - s0;
			// Starting off the station the section repeats at its top, a little to the right of it
			const junction = line.rows[firstRow].repeat ? stationX.get(line.rows[firstRow].group) : null;
			const x0 = junction ? junction.x + slant : pad;
			const fold = ring ? length / 2 : Infinity;
			// How far along the section a point is drawn: by distance, or with the stations evenly apart and what is between
			// two of them spread out evenly between them
			const stopUs = [];
			for (let i = piece.from; i <= piece.to; i++) stopUs.push(Math.max(0, Math.min(length, piece.reversed ? s1 - layout.stops[i] : layout.stops[i] - s0)));
			stopUs.sort((a, b) => a - b);
			const gapPx = view.gap * view.scale;
			const along = u => {
				if (!view.even || stopUs.length < 2 || !gapPx) return u * view.scale;
				if (u <= stopUs[0]) return (u - stopUs[0]) * view.scale;
				for (let k = 1; k < stopUs.length; k++) {
					if (u <= stopUs[k]) {
						const span = stopUs[k] - stopUs[k - 1];
						return (k - 1 + (span > 0 ? (u - stopUs[k - 1]) / span : 1)) * gapPx;
					}
				}
				return (stopUs.length - 1) * gapPx + (u - stopUs[stopUs.length - 1]) * view.scale;
			};
			const foldX = ring ? along(fold) : 0;
			const xOfU = u => u <= fold ? x0 + along(u) : x0 + 2 * foldX - along(u);

			// The names of the stations, each on the first row along the top (or the bottom of a loop) it fits on
			const names = [];
			for (let i = piece.from; i <= piece.to; i++) {
				const row = i === piece.from ? r.leaveRows[i] : r.rows[i];
				if (line.rows[row].repeat && !ring) continue;
				const u = piece.reversed ? s1 - layout.stops[i] : layout.stops[i] - s0;
				const name = shortName(line.rows[row].name);
				const width = [...name].reduce((w, c) => w + (c.charCodeAt(0) > 0x2e80 ? 12.5 : 7), 0);
				names.push({ i, row, x: xOfU(u), below: u > fold, name, width, level: 0 });
			}
			const ends = [[], []];
			names.slice().sort((a, b) => a.x - b.x).forEach(label => {
				const rows = ends[label.below ? 1 : 0];
				let level = 0;
				while (level < rows.length && rows[level] > label.x - label.width / 2 - 6) level++;
				rows[level] = label.x + label.width / 2;
				label.level = level;
			});
			const above = Math.max(0, ends[0].length - 1) * LABEL_ROW_PX;
			const y0 = top + laneH / 2 + 16 + above;
			const at = (s, d) => {
				const u = piece.reversed ? s1 - s : s - s0, side = piece.reversed ? -d : d;
				return u <= fold ? [xOfU(u), y0 - side * sidePx, 0] : [xOfU(u), y0 + laneH + 24 + side * sidePx, 1];
			};
			const inside = s => s >= s0 - 0.5 && s <= s1 + 0.5;
			const color = colorOf(r.color);
			// The route's own path, with the ends of a loop joined round
			const path = [];
			for (let k = 0; k <= 40; k++) path.push(at(s0 + length * k / 40, 0));
			const lanes = [path.filter(p => !p[2]), path.filter(p => p[2])].filter(lane => lane.length > 1);
			lanes.forEach(lane => parts.push(`<polyline points="${lane.map(p => p[0].toFixed(1) + "," + p[1].toFixed(1)).join(" ")}" class="route-path" style="stroke:${color}"/>`));
			if (ring) {
				const radius = (laneH + 24) / 2, endX = x0 + foldX;
				parts.push(`<path d="M${endX.toFixed(1)},${y0} a${radius},${radius} 0 0 1 0,${2 * radius}" class="route-path" fill="none" style="stroke:${color}"/>`);
			}
			const marker = railMarker(layout, null, true);
			layout.rails.forEach((rail, index) => {
				const mark = marker(index);
				// A rail running beside the route at about the same distance from it is drawn at that distance, so the small
				// wobbles of its curves don't show; where it really turns away it is drawn as it goes
				const offsets = [];
				for (let k = 0; k + 1 < rail.length; k += 2) if (inside(rail[k])) offsets.push(rail[k + 1]);
				offsets.sort((a, b) => a - b);
				const usual = offsets.length ? offsets[Math.floor(offsets.length / 2)] : 0;
				const steady = d => Math.abs(d - usual) <= RAIL_WOBBLE_BLOCKS ? usual : d;
				let points = [];
				const flush = () => {
					if (points.length > 1) parts.push(`<polyline points="${points.map(p => p[0].toFixed(1) + "," + p[1].toFixed(1)).join(" ")}" class="rail${mark.cls}"${mark.attrs}>${mark.title}</polyline>`);
					points = [];
				};
				for (let k = 0; k + 1 < rail.length; k += 2) {
					if (!inside(rail[k])) {
						flush();
						continue;
					}
					const p = at(rail[k], steady(rail[k + 1]));
					if (points.length && points[points.length - 1][2] !== p[2]) flush();
					points.push(p);
				}
				flush();
			});
			layout.turnouts.forEach(t => {
				if (!inside(t[0])) return;
				const p = at(t[0], t[1]);
				parts.push(`<circle cx="${p[0].toFixed(1)}" cy="${p[1].toFixed(1)}" r="2.5" class="turnout"/>`);
			});
			const routePlatforms = new Set(line.routes.flatMap(o => o.stops.map(stop => stop.platformId)));
			layout.platforms.forEach(p => {
				const middle = (p.line[0] + p.line[2]) / 2;
				if (!inside(middle)) return;
				const a = at(p.line[0], p.line[1]), b = at(p.line[2], p.line[3]), ours = routePlatforms.has(p.id);
				// One across the fold of a loop is drawn on the lane its middle is on
				if (a[2] !== b[2]) {
					const m = at(middle, (p.line[1] + p.line[3]) / 2), half = Math.abs(p.line[2] - p.line[0]) / 2 * view.scale;
					a[0] = m[0] - half;
					b[0] = m[0] + half;
					a[1] = b[1] = m[1];
				}
				parts.push(`<g class="platform ${ours ? "ours" : "other"}"><title>${escapeHtml(`${shortName(p.stationName) || tr("不属于车站")} ${trackLabel(p.id, p.name, p.passingTrack)}`)}</title><line x1="${a[0].toFixed(1)}" y1="${a[1].toFixed(1)}" x2="${b[0].toFixed(1)}" y2="${b[1].toFixed(1)}"${ours ? ` style="stroke:${color}"` : ""}/></g>`);
			});
			// Station names, over the top lane and under the bottom one, a row further out for each name in the way
			for (let i = piece.from; i <= piece.to; i++) {
				const row = i === piece.from ? r.leaveRows[i] : r.rows[i];
				const p = at(layout.stops[i], 0);
				if (!stationX.has(line.rows[row].group)) stationX.set(line.rows[row].group, { x: p[0], y: p[1] });
			}
			names.forEach(label => {
				const p = at(layout.stops[label.i], 0);
				const out = label.level * LABEL_ROW_PX;
				const lineTop = p[1] - laneH / 2 - 4 - (label.below ? 0 : out), lineBottom = p[1] + laneH / 2 + 4 + (label.below ? out : 0);
				labels.push(`<g class="stop-mark"><line x1="${p[0].toFixed(1)}" y1="${lineTop.toFixed(1)}" x2="${p[0].toFixed(1)}" y2="${lineBottom.toFixed(1)}"/><text x="${p[0].toFixed(1)}" y="${(label.below ? p[1] + laneH / 2 + 16 + out : p[1] - laneH / 2 - 8 - out).toFixed(1)}">${escapeHtml(label.name)}</text></g>`);
				right = Math.max(right, p[0] + label.width / 2);
			});
			// Slanting off the station the section starts from, and back onto the one another way ends at
			if (junction) links.push(`<line x1="${junction.x.toFixed(1)}" y1="${junction.y.toFixed(1)}" x2="${x0}" y2="${y0}" class="route-path" style="stroke:${color}"/>`);
			const end = line.rows[lastRow].repeat && !ring ? stationX.get(line.rows[lastRow].group) : null;
			if (end) {
				const p = at(piece.reversed ? s0 : s1, 0);
				links.push(`<line x1="${p[0].toFixed(1)}" y1="${p[1].toFixed(1)}" x2="${end.x.toFixed(1)}" y2="${end.y.toFixed(1)}" class="route-path" style="stroke:${color}"/>`);
			}
			right = Math.max(right, ring ? x0 + foldX : xOfU(length));
			top += (ring ? 2 * laneH + 24 : laneH) + 56 + above + Math.max(0, ends[1].length - 1) * LABEL_ROW_PX;
		});
		const width = Math.round(right + pad * 2), height = Math.round(top);
		return `<div class="route-diagram-wrap"><svg class="route-diagram" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-label="${tr("线路组配线图")}">${links.join("")}${parts.join("")}${labels.join("")}</svg></div>`
			+ (missing.length ? `<p class="hint">${tr("{0} 沿轨道寻不出路径，那一段没有画。", escapeHtml(missing.join(tr("、"))))}</p>` : "")
			+ (view.error ? `<p class="hint">${escapeHtml(view.error)}</p>` : "");
	}

	function routeDiagramDialog(r) {
		const view = { halfWidth: 24, scale: 0.6, side: 2, data: null, error: "", tries: 0 };
		const render = form => {
			form.querySelector("#dlg-route-diagram").innerHTML = routeDiagramHtml(r, view);
		};
		const load = async (form, fresh) => {
			try {
				const result = await sendJson("api/route-layout", "POST", { route: r.id, halfWidth: view.halfWidth, fresh });
				if (!result.ready) {
					view.error = tr("正在沿路径取轨道…");
					if (view.tries++ < 15) setTimeout(() => load(form, false), 700);
					else view.error = tr("服务端没有回应，关掉再打开试试");
				} else {
					// The first time, the whole line fits the width of the dialog
					if (!view.data && result.found && result.length > 0) view.scale = Math.max(0.05, Math.min(8, (form.querySelector("#dlg-route-diagram").clientWidth - 56) / result.length));
					view.data = result;
					view.error = "";
				}
			} catch (e) {
				view.error = tr("读取线路配线图失败");
			}
			if (dialog.open) render(form);
		};
		openDialog(`${tr("线路配线图：{0}", escapeHtml(routeTitle(r)))}`, `
			<div class="diagram-head"><span class="hint">${tr("沿这条线路在游戏里的路径拉直，从左往右是行车方向，上方是行车方向的左侧。按游戏里已应用的线路画，草稿里的股道改动应用后才显示。")}</span>
				<span class="tools"><button type="button" data-route-zoom="in" title="${tr("放大")}">＋</button><button type="button" data-route-zoom="out" title="${tr("缩小")}">−</button>
				<button type="button" data-route-side="1" title="${tr("侧向不放大")}">1×</button><button type="button" data-route-side="2" title="${tr("侧向放大 2 倍")}">2×</button><button type="button" data-route-side="3" title="${tr("侧向放大 3 倍")}">3×</button></span></div>
			<div id="dlg-route-diagram"></div>`, tr("关闭"), () => {});
		dialog.classList.add("wide");
		dialog.addEventListener("close", () => dialog.classList.remove("wide"), { once: true });
		const form = dialog.querySelector("form");
		render(form);
		load(form, true);
		form.addEventListener("contextmenu", e => {
			const rail = e.target.closest("[data-rail]");
			if (!rail) return;
			e.preventDefault();
			if (!data.canEdit) {
				openMenuAt(e, [[tr("没有编辑权限"), null]]);
				return;
			}
			const [a, b] = rail.dataset.rail.split(",");
			railMenu(e, r, { a, b, status: +rail.dataset.railStatus }, () => render(form));
		});
		form.addEventListener("click", e => {
			const zoom = e.target.closest("[data-route-zoom]");
			const side = e.target.closest("[data-route-side]");
			const station = e.target.closest("[data-route-stop]");
			const release = e.target.closest("[data-release]");
			if (release) {
				const [a, b] = release.dataset.release.split(",");
				toggleReleased(r, a, b);
				render(form);
				return;
			}
			if (zoom) view.scale = Math.max(0.05, Math.min(8, view.scale * (zoom.dataset.routeZoom === "in" ? 1.5 : 1 / 1.5)));
			else if (side) view.side = +side.dataset.routeSide;
			else if (station) {
				// The station opens in the stop editor, with its own track diagram
				const stop = +station.dataset.routeStop;
				dialog.close();
				if (data.canEdit && r.stops.length > 1) stopsDialog(r, stop);
				return;
			} else return;
			render(form);
		});
	}

	function routeDiagramHtml(r, view) {
		const result = view.data;
		if (!result) return `<p class="hint">${escapeHtml(view.error || tr("正在读取…"))}</p>`;
		if (!result.found) return `<p class="err">${tr("沿轨道寻不出这条线路的路径。先右键线路“编辑停站…”，在连通图里修好股道。")}</p>`;
		const pad = 24, head = 44, sidePx = view.side * 2;
		const width = Math.round(result.length * view.scale + pad * 2), middle = head + result.halfWidth * sidePx;
		const height = middle + result.halfWidth * sidePx + 16;
		const x = s => (pad + s * view.scale).toFixed(1), y = d => (middle - d * sidePx).toFixed(1);
		const color = colorOf(r.color);
		const routePlatforms = new Set(platformsOf(r));
		const problems = new Map();
		routeProblems(r).forEach(p => { if (!problems.has(p.stop) || p.level === "error") problems.set(p.stop, p.level); });
		const pass = timetableOf(r).pass;
		const parts = [`<svg class="route-diagram" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-label="${tr("线路配线图")}">`];
		parts.push(`<line x1="${x(0)}" y1="${middle}" x2="${x(result.length)}" y2="${middle}" class="route-path" style="stroke:${color}"/>`);
		const marker = railMarker(result, r);
		result.rails.forEach((piece, index) => {
			const points = [];
			for (let k = 0; k + 1 < piece.length; k += 2) points.push(`${x(piece[k])},${y(piece[k + 1])}`);
			const mark = marker(index);
			parts.push(`<polyline points="${points.join(" ")}" class="rail${mark.cls}"${mark.attrs}>${mark.title}</polyline>`);
		});
		result.turnouts.forEach(t => parts.push(`<circle cx="${x(t[0])}" cy="${y(t[1])}" r="2.5" class="turnout"/>`));
		(result.boundaryNodes || []).forEach(node => parts.push(`<circle cx="${x(node[0])}" cy="${y(node[1])}" r="4" class="boundary-node"><title>${escapeHtml(tr("寻路边界节点（在游戏里节点界面改）"))}</title></circle>`));
		result.platforms.forEach(p => {
			const ours = routePlatforms.has(p.id);
			const line = p.line;
			parts.push(`<g class="platform ${ours ? "ours" : "other"}"><title>${escapeHtml(`${shortName(p.stationName) || tr("不属于车站")} ${trackLabel(p.id, p.name, p.passingTrack)}`)}</title><line x1="${x(line[0])}" y1="${y(line[1])}" x2="${x(line[2])}" y2="${y(line[3])}"${ours ? ` style="stroke:${color}"` : ""}/>${ours ? `<text x="${((+x(line[0]) + +x(line[2])) / 2).toFixed(1)}" y="${(Math.min(+y(line[1]), +y(line[3])) - 4).toFixed(1)}">${escapeHtml(p.name || "?")}</text>` : ""}</g>`);
		});
		result.stops.forEach((s, i) => {
			if (s < 0) return;
			const level = problems.get(i);
			const passes = pass[i] && i > 0 && i < result.stops.length - 1;
			const classes = ["stop-mark", passes ? "passes" : "", level || ""].filter(Boolean).join(" ");
			parts.push(`<g class="${classes}" data-route-stop="${i}" tabindex="0"><title>${escapeHtml(`${tr("{0}{1}{2}，点一下打开这一站", shortName(r.stops[i].name) || tr("第 {0} 站", i + 1), passes ? tr("（通过）") : "", level ? tr("：股道有问题") : "")}`)}</title><line x1="${x(s)}" y1="${head - 6}" x2="${x(s)}" y2="${height - 10}"/><text x="${x(s)}" y="${head - 12}">${escapeHtml(shortName(r.stops[i].name) || "?")}</text></g>`);
		});
		parts.push(`</svg>`);
		const missing = result.stops.filter(s => s < 0).length;
		return `<div class="route-diagram-wrap">${parts.join("")}</div>`
			+ (missing ? `<p class="hint">${tr("有 {0} 站的站台不在路径上，没有标出。", missing)}</p>` : "")
			+ (result.truncated ? `<p class="hint">${tr("轨道太多，只画了一部分。")}</p>` : "")
			+ `<p class="hint">${tr("隔离：红色是寻路边界，所有寻路都不过；橙色虚线是联络线独占，别的线路不走；灰色虚线是已放行。")}${data.canEdit ? tr("右键一段轨道改隔离，放进草稿，应用后刷新车厂。") : ""}</p>`
			+ `<p class="hint">${tr("全长约 {0} 格，路径两侧各 {1} 格以内的轨道。红色、黄色的站名是股道有问题的站。", Math.round(result.length), result.halfWidth)}</p>`;
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
		if (depots.length) notes.push(`${tr("车厂 {0} 跑这个种别，删除后不再跑它，要刷新车厂路径", depots.map(escapeHtml).join("、"))}`);
		if (segments) notes.push(`${tr("运用里有 {0} 班是它的，这些运用会走不通，要改运用", segments)}`);
		if (links) notes.push(`${tr("别的种别有 {0} 班直通到它，这些直通会失效", links)}`);
		openDialog(tr("删除种别"), `
			<p>${tr("删除")} <b>${escapeHtml(routeTitle(r))}</b>${tr("：它的车站、停站设置和全部时刻表都从游戏里删掉，不能恢复。")}</p>
			${notes.length ? `<ul class="warn">${notes.map(note => `<li>${note}</li>`).join("")}</ul>` : ""}
			<p class="hint">${tr("先放在草稿里，按“应用”才删除。应用之前可以右键取消。这个种别别的没应用的改动也一起丢掉。")}</p>`, tr("删除"), () => {
			const entry = draftEntry(r);
			entry.deleted = true;
			if (!entry.conflict) delete entry.error;
			saveDraft();
			renderAll();
		});
	}

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

	function throughDialog(fromRoute) {
		const routes = data.routes.filter(r => r.stops.length > 1);
		if (routes.length < 2) return;
		const a0 = fromRoute || (state.route && data.routeById.get(state.route)) || routes[0];
		const lastName = r => r.stops[r.stops.length - 1].station;
		const b0 = routes.find(r => r !== a0 && r.stops[0].station === lastName(a0)) || routes.find(r => r !== a0);
		const routeOptions = (list, selected) => list.map(r => `<option value="${r.id}"${r.id === selected ? " selected" : ""}>${escapeHtml(routeTitle(r))}</option>`).join("");
		// The last stop and boundaries to leave from; the first stop and boundaries to join at
		const exitOptions = r => [`<option value="-1">${tr("{0}（末站）", escapeHtml(stopName(r, r.stops.length - 1)))}</option>`, ...r.stops.map((_, i) => i).filter(i => isBoundary(r, i)).map(i => `<option value="${i}">${tr("{0}（时刻边界）", escapeHtml(stopName(r, i)))}</option>`)].join("");
		const entryOptions = r => [`<option value="0">${tr("{0}（首站）", escapeHtml(stopName(r, 0)))}</option>`, ...r.stops.map((_, i) => i).filter(i => isBoundary(r, i)).map(i => `<option value="${i}">${tr("{0}（时刻边界）", escapeHtml(stopName(r, i)))}</option>`)].join("");
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
		openDialog(tr("新建直通关系"), `
			<div class="row"><label>${tr("从")} <select name="from">${routeOptions(routes, a0.id)}</select></label><label>${tr("在")} <select name="fromStop"></select> ${tr("接出")}</label></div>
			<div class="row"><label>${trc("route", "到")} <select name="to">${routeOptions(routes.filter(r => r !== a0), b0.id)}</select></label><label>${tr("在")} <select name="toStop"></select> ${tr("接入")}</label></div>
			<div class="row"><label>${tr("首站发车在")} <input name="t1" value="05:00" size="6"> ${tr("到")} <input name="t2" value="26:00" size="6"> ${tr("之间（{0}的表）", WEEKDAY_NAMES[state.weekday])}</label></div>
			<div class="row"><label>${tr("衔接至少")} <input name="gap" type="number" value="3" min="0" max="60" style="width:4.5em"> ${tr("分钟，最多再等")} <input name="wait" type="number" value="12" min="0" max="120" style="width:4.5em"> ${tr("分钟")}</label></div>
			<label><input type="checkbox" name="replace"> ${tr("替换这些班次原有的直通")}</label>
			<p class="preview" id="dialog-preview"></p>
			<p class="hint">${tr("中途接出和接入只能在游戏里标了时刻边界的站台（站台界面里设置）；没有边界的线路只能末站接首站。每一班接上对方能赶上的第一班，同一班不接两次，等太久的不接。")}</p>`, tr("新建"), form => {
			const found = pairs(form);
			if (!found) return tr("时刻写成 07:10");
			if (!found.result.length) return tr("没有能接上的班次");
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
				preview.textContent = tr("时刻写成 07:10");
				return;
			}
			preview.textContent = found.result.length
				? `${tr("{0} 对：\n", found.result.length)}` + found.result.slice(0, 8).map(x => `${tr("{0} 班 {1} {2} 到 → {3} 班 {4} {5} 发", clock(x.s.start), stopName(found.a, found.exitIndex), clock(x.out), clock(x.to.start), stopName(found.b, found.toStop), clock(x.into))}`).join("\n") + (found.result.length > 8 ? "\n…" : "")
				: tr("没有能接上的班次");
		});
	}

	// What was typed to add services, kept after adding and across visits (per viewer)
	const addInputs = (() => {
		try {
			return Object.assign({ start: "", end: "", count: "" }, JSON.parse(localStorage.getItem("mtr-editor-add-services") || "{}"));
		} catch (e) {
			return { start: "", end: "", count: "" };
		}
	})();

	/**
	 * The services to add: from the start every interval until the end (the end included where one falls on it); with no
	 * end, the start alone; or the start's own pattern when nothing else is filled in. The interval is in minutes, or
	 * minutes and seconds as 04:30.
	 */
	function servicesToAdd(startText, endText, intervalText) {
		if (!endText.trim() && !intervalText.trim()) return parsePattern(startText);
		const start = parseClock(startText.trim()), end = endText.trim() ? parseClock(endText.trim()) : start;
		const text = intervalText.trim(), parts = text.split(/[:：]/);
		const step = parts.length === 2 ? (Number(parts[0]) * 60 + Number(parts[1])) * 1000 : Number(text) * 60000;
		if (start === null || end === null || !(step >= 1000)) return null;
		return Batch.stepTimes(start, end, Math.round(step / 1000) * 1000).map(time => mod(time - data.offsetMillis, DAY));
	}

	/**
	 * Takes every service out of a table, with their through links, returns to the depot, orders and exceptions; the
	 * table, its stop times and its days stay. Into the draft like any other change, so it can still be dropped.
	 */
	function clearTable(r, index) {
		const table = timetableOf(r).tables[index];
		if (!table || !table.services.length) return;
		if (!confirm(tr("清空“{0}”的 {1} 个班次？改动先放进草稿，应用前都可以放弃。", tableName(table, index), table.services.length))) return;
		state.range = null;
		editTimetable(r, timetable => {
			const edited = timetable.tables[index];
			edited.services = [];
			edited.links = [];
			edited.returns = [];
			edited.suspended = [];
			edited.orders = [];
			edited.exceptions = [];
			return tr("已清空“{0}”", tableName(edited, index));
		});
	}

	function addServices() {
		const r = tableRouteOf(), input = $("add-service");
		if (!r || !input) return;
		addInputs.start = input.value;
		addInputs.end = $("add-end") ? $("add-end").value : "";
		addInputs.count = $("add-count") ? $("add-count").value : "";
		try { localStorage.setItem("mtr-editor-add-services", JSON.stringify(addInputs)); } catch (e) {}
		const services = servicesToAdd(addInputs.start, addInputs.end, addInputs.count);
		if (!services) {
			tableMessage = PATTERN_HINT;
			renderTable();
			return;
		}
		const today = timetableOf(r).tables.findIndex(table => table.weekdays >> state.weekday & 1);
		// From a time to a time every so often: the services already in that span are fitted to the new times, moved,
		// added or taken away as needed, rather than the new ones put in beside them
		const startTime = parseClock(addInputs.start.trim()), endTime = addInputs.end.trim() ? parseClock(addInputs.end.trim()) : null;
		if (startTime !== null && endTime !== null && addInputs.count.trim()) {
			const start = mod(startTime - data.offsetMillis, DAY), end = mod(endTime - data.offsetMillis, DAY);
			const fit = Batch.fitSpan(timetableOf(r).tables[today].services, start, end, services);
			if (fit.removes.length && !confirm(tr("{0}–{1} 已有的班次比新间隔多 {2} 班，多出的要删掉（连同接续和例外）。改动先放进草稿，确定吗？", addInputs.start.trim(), addInputs.end.trim(), fit.removes.length))) return;
			editTimetable(r, timetable => {
				const table = timetable.tables[today];
				fit.removes.forEach(service => removeService(table, service));
				for (const [from, to] of fit.moves) {
					const error = retimeService(table, from, to);
					if (error) return error;
				}
				if (table.services.length + fit.adds.length > MAX_SERVICES) return `${tr("一张表最多 {0} 个班次", MAX_SERVICES)}`;
				table.services.push(...fit.adds);
				return tr("{0}–{1}：保留 {2} 班，挪动 {3} 班，新增 {4} 班，删掉 {5} 班", addInputs.start.trim(), addInputs.end.trim(), fit.keep.length, fit.moves.length, fit.adds.length, fit.removes.length);
			});
			const next = $("add-service");
			if (next) next.focus();
			return;
		}
		editTimetable(r, timetable => {
			const table = timetable.tables[today];
			const added = services.filter(service => !table.services.includes(service));
			if (table.services.length + added.length > MAX_SERVICES) return `${tr("一张表最多 {0} 个班次", MAX_SERVICES)}`;
			table.services.push(...added);
			return added.length < services.length ? `${tr("已有的 {0} 班没有重复添加", services.length - added.length)}` : "";
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
			key: tr("这个页面的链接已经失效。请在游戏的仪表盘里重新点“高级编辑器”。"),
			world: tr("游戏现在不在世界里，进入世界后会自动刷新。"),
			offline: tr("连不上游戏。游戏关了，或者离开了这个世界。请在游戏的仪表盘里重新点“高级编辑器”。"),
		};
		const missingTimes = data && data.routes.some(r => !r.hasTimes && r.timetable);
		const problem = !key ? messages.key : messages[connection];
		const failed = Object.entries(draft.routes).filter(([, entry]) => entry.error || entry.conflict);
		const otherFailed = Object.values(draft.duties).some(entry => entry.error) || draft.created.some(made => made.error);
		let html = "";
		if (problem) {
			html = escapeHtml(problem);
		} else if (state.pendingExit && findService(state.pendingExit.id)) {
			const s = findService(state.pendingExit.id);
			html = `${tr("已设接出点：")}<b>${clock(s.start)} ${escapeHtml(routeTitle(s.route))}</b> ${tr("在")} <b>${escapeHtml(stopName(s.route, state.pendingExit.index))}</b>${tr("。打开目标线路的时刻表，右键首站或时刻边界站的一格，选“设为接入点”。")}<button data-cancel-exit>${tr("取消")}</button>`;
		} else if (data && !data.canEdit) {
			html = tr("没有编辑权限：需要创造模式，或服务端设置的权限等级。草稿照常保存，有权限后再应用。");
		} else if (!applyResult && failed.some(([, entry]) => entry.conflict)) {
			const name = id => data && data.routeById.get(id) ? escapeHtml(routeTitle(data.routeById.get(id))) : tr("已删除的线路");
			html = `${tr("下面的线路在草稿之后游戏里改过。它们的草稿保留着，应用时先不发，逐条处理后才能应用；其余线路照常应用：")}<ul>`
				+ failed.filter(([, entry]) => entry.conflict).map(([id, entry]) => `<li><a data-open-route="${id}">${name(id)}</a>：${escapeHtml(entry.error || tr("游戏里的这条线路在草稿之后改过"))}<button data-keep="${id}">${tr("以最新版本为基础，覆盖对方的修改")}</button><button data-drop="${id}">${tr("放弃草稿，用游戏里的")}</button></li>`).join("")
				+ "</ul>";
		} else if (applyResult && (!applyResult.ok || failed.length || otherFailed)) {
			const name = id => data && data.routeById.get(id) ? escapeHtml(routeTitle(data.routeById.get(id))) : tr("已删除的线路");
			html = `${tr("应用了 {0} 项，下面的没有应用，留在草稿里：", applyResult.applied)}<ul>`
				+ applyResult.general.map(message => `<li>${escapeHtml(message)}</li>`).join("")
				+ Object.entries(draft.duties).filter(([, entry]) => entry.error).map(([id, entry]) => `<li>${tr("车厂{0}的运用：{1}", escapeHtml(shortName(depotById(id) ? depotById(id).name : "?")), escapeHtml(entry.error))}</li>`).join("")
				+ draft.created.filter(made => made.error).map(made => `<li>${tr("新种别 {0}：{1}", escapeHtml(shortName(made.name)), escapeHtml(made.error))}</li>`).join("")
				+ failed.map(([id, entry]) => `<li><a data-open-route="${id}">${name(id)}</a>：${escapeHtml(entry.error || tr("游戏里的这条线路在草稿之后改过"))}${entry.conflict ? `<button data-keep="${id}">${tr("以最新版本为基础，覆盖对方的修改")}</button><button data-drop="${id}">${tr("放弃草稿，用游戏里的")}</button>` : ""}</li>`).join("")
				+ "</ul>";
		} else if (applyResult && applyResult.ok) {
			html = `${tr("已应用 {0} 项。", applyResult.applied)}`;
		} else if (data && state.route && data.routeById.get(state.route) && data.routeById.get(state.route).editors) {
			html = escapeHtml(tr("{0} 正在游戏里编辑这条线路，应用前留意别和对方改到同一处", data.routeById.get(state.route).editors));
		} else if (dutyNotice) {
			const check = dutyCheckText(depotById(sim.checkDepot));
			html = escapeHtml(dutyNotice) + (check ? " " + escapeHtml(check) : "");
		} else if (missingTimes) {
			html = tr("部分线路还没有各站时刻，正在向服务端取，取到之前按虚线画。");
		}
		banner.hidden = !html;
		banner.innerHTML = html;
		banner.classList.toggle("warn", Boolean(problem) || Boolean(data && !data.canEdit) || Boolean(applyResult && (!applyResult.ok || failed.length)));
	}

	/**
	 * Everything that doesn't match between the page and the game, or that the game couldn't do with what it was given:
	 * the connection, changes not applied, depots' paths, duties whose paths weren't found, trains too few, routes with
	 * no times yet. Each as {level: "err" | "warn", text, route?}.
	 */
	function syncProblems() {
		const problems = [];
		if (connection !== "ok") problems.push({ level: "err", text: connection === "world" ? tr("游戏现在不在世界里") : connection === "key" ? tr("页面的链接已经失效") : tr("连不上游戏") });
		if (!data) return problems;
		const routeName = id => data.routeById.get(id) ? routeTitle(data.routeById.get(id)) : tr("已删除的线路");
		Object.entries(draft.routes).forEach(([id, entry]) => {
			if (entry.conflict) problems.push({ level: "err", text: tr("{0}：游戏里在草稿之后改过，草稿先不发", routeName(id)), route: id });
			else if (entry.error) problems.push({ level: "err", text: tr("{0}：没有应用，{1}", routeName(id), entry.error), route: id });
		});
		Object.entries(draft.duties).forEach(([id, entry]) => {
			if (entry.error) problems.push({ level: "err", text: tr("车厂{0}的运用没有应用：{1}", shortName(depotById(id) ? depotById(id).name : "?"), entry.error) });
		});
		Object.entries(draft.depots || {}).forEach(([id, entry]) => {
			if (entry.error) problems.push({ level: "err", text: tr("车厂{0}的设置没有应用：{1}", shortName(depotById(id) ? depotById(id).name : "?"), entry.error) });
		});
		(draft.created || []).filter(made => made.error).forEach(made => problems.push({ level: "err", text: tr("新种别 {0} 没有建成：{1}", shortName(made.name), made.error) }));
		// Plans a dispatcher started, on until ended, and settings for trains no longer reported (D2)
		const reported = new Set(data.depots.flatMap(depot => (depot.alarms || []).map(alarm => alarm.train)));
		(data.contingencySystems || []).forEach(system => {
			[system].concat(system.others || []).forEach(owner => {
				owner.plans.filter(plan => plan.active).forEach(plan => problems.push({ level: "warn", text: tr("路网{0}：预案「{1}」已启用", systemTitle(system), plan.name || plan.id), actions: [{ label: tr("停用预案"), op: { type: "contingency", system: owner.anchor, action: "deactivate", plan: plan.id } }] }));
				Object.keys(owner.adhoc || {}).filter(train => !reported.has(train)).forEach(train => problems.push({ level: "warn", text: tr("路网{0}：列车 {1} 有临时处置", systemTitle(system), train), actions: [{ label: tr("解除"), op: { type: "contingency", system: owner.anchor, action: "release", train } }] }));
			});
			// Rails joined systems that each have plans: both hold, until merged (D2)
			if ((system.others || []).some(other => other.plans.length)) problems.push({ level: "warn", text: tr("路网{0}由 {1} 套故障预案接在一起，都还生效（原锚点小的优先）。在线路右键“路网故障预案…”里可以合成一套", systemTitle(system), 1 + system.others.length) });
		});
		data.depots.forEach(depot => {
			const name = shortName(depot.name) || tr("（未命名车厂）");
			const path = depot.path || {};
			if (!depot.routes.length) problems.push({ level: "warn", text: tr("车厂{0}没选线路，不会出车", name) });
			else if (path.state === "failed") problems.push({ level: "err", text: tr("车厂{0}的路径没生成：{1}", name, path.text || tr("原因不明，看游戏里的车厂界面")) });
			else if (path.state === "generating") problems.push({ level: "warn", text: tr("车厂{0}正在生成路径", name) });
			else if (path.state === "stale") problems.push({ level: "warn", text: tr("车厂{0}的路径是旧的，线路或轨道改过，要刷新", name) });
			(path.detours || []).forEach(detour => problems.push({ level: "warn", text: tr("车厂{0}：从 {1} 到 {2} 要空车走 {3} 格（两个站台只相距 {4} 格），绕远了。在 {1} 的线路上设站前或站后折返（折返线、多站台轮用），或者让下一班从同一个站台发车", name, shortName(detour.from), shortName(detour.to), detour.length, detour.apart) }));
			if (path.failedDuties > 0) problems.push({ level: "err", text: tr("车厂{0}有 {1} 份运用找不到路，这些车不会按运用出车：{2}", name, path.failedDuties, (path.failedDutyKeys || []).slice(0, 3).join("、")) });
			// Trains waiting on the line with no duty to go on with, which a click takes straight back (P1-C)
			(depot.held || []).forEach(held => {
				const siding = depot.sidings.find(other => other.id === held.siding);
				const why = held.reason === "OTHER_PLATFORM" ? tr("第二天的运用不从这个站台出发") : held.reason === "ALREADY_RUN" ? tr("第二天的运用已经由别的车跑了") : tr("第二天没有接着跑的运用");
				problems.push({ level: "err", text: tr("车厂{0}：{1} 车 {2} 留在线上过夜后{3}，停在 {4} 等处理。改好运用后它会接着跑", name, shortName(siding ? siding.name : "") || tr("存车线"), held.slot + 1, why, shortName(held.platform)), recall: { depot: depot.id, siding: held.siding, slot: held.slot } });
			});
			// Trains late or stuck with no plan on for them, and those a plan or a dispatcher's setting is on for (D2)
			(depot.alarms || []).forEach(alarm => {
				const siding = depot.sidings.find(other => other.id === alarm.siding);
				const r = data.routeById.get(alarm.route);
				const trainText = tr("{0} 车 {1}（{2}）", shortName(siding ? siding.name : "") || tr("存车线"), alarm.slot + 1, r ? routeTitle(r) : alarm.route);
				const owner = plansByAnchor(alarm.system);
				const plan = owner ? owner.plans.find(each => each.id === alarm.plan) : null;
				if (!alarm.handled) {
					const actions = [];
					if (plan) actions.push({ label: tr("启用预案「{0}」", plan.name || plan.id), op: { type: "contingency", system: alarm.system, action: "activate", plan: plan.id } });
					actions.push({ label: tr("临时处置…"), adhoc: { system: alarm.system, train: alarm.train } });
					problems.push({ level: "err", text: tr("车厂{0}：{1}{2}，没有生效的故障预案", name, trainText, alarmReasonText(alarm)), actions });
				} else {
					const actions = alarm.plan === "0" ? [{ label: tr("解除"), op: { type: "contingency", system: alarm.system, action: "release", train: alarm.train } }]
						: plan && plan.manual ? [{ label: tr("停用预案"), op: { type: "contingency", system: alarm.system, action: "deactivate", plan: plan.id } }] : [];
					problems.push({ level: "warn", text: tr("车厂{0}：{1}{2}，按{3}处理", name, trainText, alarmReasonText(alarm), alarm.plan === "0" ? tr("临时处置") : tr("预案「{0}」", plan ? plan.name || plan.id : alarm.plan)), actions });
				}
			});
			(depot.returnMisses || []).forEach(miss => problems.push({ level: "warn", text: tr("车厂{0}：一班设了回送车厂 {1}，但那里没有同车型、有空位又有路的存车线，车按平常回库了", name, shortName((depotById(miss.depot) || {}).name || miss.depot)) }));
			turnPairsOf(depot).filter(pair => pair.millis === null).forEach(pair => problems.push({ level: "warn", text: tr("车厂{0}：{1} 到 {2} 换不过去（同站换站台没有路），自动排班不会让车这样接续", name, stopLabel(pair.from), stopLabel(pair.to)) }));
			const duties = dutiesOf(depot);
			// Duties handed on to their pool or left with no siding of their vehicle (运用补完 step 5)
			const poolName = key => { const pool = (depot.pools || []).find(each => each.key === key); return pool ? pool.vehicle || key : key.split("/")[0] || tr("（不明）"); };
			Object.entries(duties.unplaced || {}).forEach(([key, count]) => problems.push({ level: "warn", text: tr("车厂{0}：车型 {1} 的 {2} 份运用没有这种车型的存车线，不运行；加回这种车型的存车线后自动放回", name, poolName(key), count) }));
			const movedTo = new Map();
			(duties.moves || []).filter(move => move.to !== "0" && move.from !== "0").forEach(move => movedTo.set(move.from + ">" + move.to, (movedTo.get(move.from + ">" + move.to) || 0) + 1));
			movedTo.forEach((count, key) => {
				const [from, to] = key.split(">");
				const fromSiding = depot.sidings.find(siding => String(siding.id) === from), toSiding = depot.sidings.find(siding => String(siding.id) === to);
				problems.push({ level: "warn", text: tr("车厂{0}：{1} 删除了或换了车型，它的 {2} 份运用移到同车型的 {3}（改一下运用后不再提示）", name, fromSiding ? shortName(fromSiding.name) || tr("存车线") : tr("一条已删除的存车线"), count, toSiding ? shortName(toSiding.name) || tr("存车线") : to) });
			});
			const deleted = Object.values(duties.sidings).some(slots => slots.some(slot => slot.some(day => day.segments.some(segment => !data.routeById.get(segment.route)))));
			if (deleted) problems.push({ level: "warn", text: tr("车厂{0}的运用里有已经删除的线路的班次", name) });
			if (depot.shortfall && depot.shortfall.uncovered > 0) problems.push({ level: "warn", text: tr("车厂{0}：{1}", name, shortfallText(depot.shortfall)) });
			// Lines not planned for want of a rule saying which station the depot's trains go by
			const unsetLines = depot.shortfall && depot.shortfall.entrancesUnset || [];
			if (unsetLines.length) {
				const lineName = key => { const found = depotLines(depot).find(([lineKey]) => lineKey === key); return found ? found[1] : key.split("|").slice(1).join("|") || key; };
				problems.push({ level: "err", text: tr("车厂{0}接到线路的车站不止一个，{1} 没有出入库规则指定从哪站进出，这些线路不排运用；在车厂右键「出入库规则…」里加一条", name, unsetLines.map(lineName).join("、")) });
			}
			if (duties.auto && depot.shortfall && depot.shortfall.replan) problems.push({ level: "warn", text: tr("车厂{0}：{1}", name, replanText(depot.shortfall)) });
			// Ways out and back path generation found missing, which the planning keeps off; and those not measured yet
			depot.sidings.forEach(siding => {
				const sidingName = shortName(siding.name) || tr("存车线");
				(siding.noWayOut || []).forEach(platformId => problems.push({ level: "warn", text: tr("车厂{0}：{1} 到 {2} 没有出库的路，这条存车线不从这里出车", name, sidingName, platformText(platformId)) }));
				(siding.noWayBack || []).forEach(platformId => problems.push({ level: "warn", text: tr("车厂{0}：从 {2} 回不到 {1}，车改回车池里别的存车线", name, sidingName, platformText(platformId)) }));
				const firsts = [...new Set((depot.routes || []).map(id => data.routeById.get(id)).filter(Boolean).filter(r => !(siding.routes || []).length || siding.routes.includes(r.id)).map(r => r.stops[0] && r.stops[0].platformId).filter(Boolean))];
				const estimated = firsts.filter(platformId => !(siding.measuredOut || []).includes(platformId) && !(siding.noWayOut || []).includes(platformId));
				if (estimated.length && siding.measuredOut) problems.push({ level: "hint", text: tr("车厂{0}：{1} 到 {2} 的出库还没量过，按距离估计排，刷新车厂路径后按实测", name, sidingName, estimated.map(platformText).join("、")) });
			});
			if (duties.mode === "diagram" && depot.sidings.some(siding => (duties.sidings[siding.id] || []).length && !siding.legs)) problems.push({ level: "warn", text: tr("车厂{0}还没有出入库时刻，刷新路径后运行图上才画出出库和回库", name) });
		});
		// Platforms in more than one station's area with none set for them, and where they went (R6)
		(topo ? topo.overlapping : []).slice(0, 20).forEach(overlap => problems.push({ level: "hint", text: overlap.byIdAlone
			? tr("{0} 在几个车站的范围里，这些车站都还没有别的站台可参照，暂时归到 {1}；右键时刻表里这一站可以指定", platformText(overlap.platform), stationNameOf(overlap.station))
			: tr("{0} 在几个车站的范围里，按最近的站台归到 {1}；右键时刻表里这一站可以指定", platformText(overlap.platform), stationNameOf(overlap.station)) }));
		// Platforms trains turning back hold for long, or that have no room for one at all (terminalNow)
		terminalNow().warnings.slice(0, 12).forEach(warning => {
			const from = findService(warning.from);
			const at = clock(warning.at), where = platformText(warning.platform);
			if (warning.type === "HOLDS_PLATFORM") problems.push({ level: "warn", text: tr("{0}：{1} 到的车在 {2} 一直停到 {3}，{4} 列车改用别的站台或折返线", from ? routeTitle(from.route) : "?", at, where, clock(warning.until), warning.count), route: from && from.route.id });
			else problems.push({ level: "err", text: tr("{0}：{1} 到 {2} 的车没有空站台可停，也没有空的折返线，要等前面的车开走", from ? routeTitle(from.route) : "?", at, where), route: from && from.route.id });
		});
		data.routes.filter(r => r.timetable && !r.hasTimes).forEach(r => problems.push({ level: "warn", text: r.pathFailure ? tr("{0}：找不到路，{1}", routeTitle(r), r.pathFailure) : tr("{0}：还没有各站时刻", routeTitle(r)), route: r.id }));
		return problems;
	}

	function renderSyncButton() {
		const button = $("btn-sync");
		if (!button) return;
		// Hints (ways not measured yet) aren't problems, and aren't counted
		const problems = syncProblems().filter(p => p.level !== "hint"), errors = problems.filter(p => p.level === "err").length;
		button.textContent = problems.length ? tr("同步状态 · {0}", problems.length) : tr("同步状态 · 正常");
		button.classList.toggle("bad", errors > 0);
		button.classList.toggle("warn", !errors && problems.length > 0);
	}

	/**
	 * Each change of platform within a station the depot's routes may make, from where one ends to where another starts,
	 * with how long it takes as measured (null: no way), once the server measured them.
	 */
	function turnPairsOf(depot) {
		if (!data || !(depot.path || {}).turnTimesMeasured) return [];
		const routes = depot.routes.map(id => data.routeById.get(id)).filter(r => r && r.stops.length);
		const pairs = [], seen = new Set();
		routes.forEach(before => {
			const from = before.stops[before.stops.length - 1];
			routes.forEach(after => {
				const to = after.stops[0], key = from.platformId + ">" + to.platformId;
				if (!from.station || from.station !== to.station || from.platformId === to.platformId || seen.has(key)) return;
				seen.add(key);
				pairs.push({ from, to, millis: turnTimeOf(depot, from.platformId, to.platformId) });
			});
		});
		return pairs;
	}

	function syncDialog() {
		const problems = syncProblems();
		const actionButtons = p => data.canEdit ? (p.actions || []).map(action => ` <button type="button" class="link" ${action.adhoc ? `data-adhoc='${escapeHtml(JSON.stringify(action.adhoc))}'` : `data-command='${escapeHtml(JSON.stringify(action.op))}'`}>${escapeHtml(action.label)}</button>`).join("") : "";
		const items = problems.map(p => `<li class="${p.level}">${p.route ? `<a data-open-route="${p.route}">${escapeHtml(p.text)}</a>` : escapeHtml(p.text)}${p.recall && data.canEdit ? ` <button type="button" class="link" data-recall='${escapeHtml(JSON.stringify(p.recall))}'>${tr("直接收回")}</button>` : ""}${actionButtons(p)}</li>`).join("");
		// The changes of platform as measured on the rails, which the planner and the duty check go by
		const turns = data ? data.depots.flatMap(depot => turnPairsOf(depot).filter(pair => pair.millis !== null).map(pair => `<li>${escapeHtml(tr("车厂{0}：{1} → {2} 实际要 {3}", shortName(depot.name) || tr("（未命名车厂）"), stopLabel(pair.from), stopLabel(pair.to), formatDuration(pair.millis)))}</li>`)) : [];
		const turnsText = turns.length ? `<h3>${tr("同站换站台用时（轨道上实测，含 30 秒余量）")}</h3><ul class="sync-turns">${turns.slice(0, 40).join("")}</ul>` : "";
		openDialog(tr("同步状态"), `<p class="hint">${connection === "ok" ? tr("已同步 {0}，每 1.5 秒检查一次游戏里的变化。", new Date(lastLoaded || Date.now()).toLocaleTimeString()) : tr("没有连上游戏。")}</p>${items ? `<ul class="sync-problems">${items}</ul>` : `<p>${tr("页面和游戏一致，没有发现问题。")}</p>`}${turnsText}`, tr("关闭"), () => {
		});
		// Taken back at once, not along the rails: the player's own command, which the game logs
		dialog.querySelectorAll("[data-recall]").forEach(button => button.addEventListener("click", async () => {
			if (!confirm(tr("把这列车直接收回存车线？它不走轨道，从站台上直接回到存车线。"))) return;
			button.disabled = true;
			try {
				await sendJson("api/apply", "POST", { operations: [Object.assign({ type: "recall" }, JSON.parse(button.dataset.recall))] });
				button.textContent = tr("已收回");
			} catch (e) {
				button.disabled = false;
			}
		}));
		// A dispatcher's commands about contingency plans, sent at once like taking a train back (D2)
		dialog.querySelectorAll("[data-command]").forEach(button => button.addEventListener("click", async () => {
			button.disabled = true;
			try {
				await sendJson("api/apply", "POST", { operations: [JSON.parse(button.dataset.command)] });
				button.textContent = tr("已发送");
			} catch (e) {
				button.disabled = false;
			}
		}));
		dialog.querySelectorAll("[data-adhoc]").forEach(button => button.addEventListener("click", () => {
			const target = JSON.parse(button.dataset.adhoc);
			const owner = plansByAnchor(target.system);
			if (owner) adhocDialog(owner.system, target.train, target.system);
		}));
		dialog.querySelectorAll("[data-open-route]").forEach(link => link.addEventListener("click", () => {
			dialog.close();
			const r = data.routeById.get(link.dataset.openRoute);
			if (r) openView("table", r.lineKey, r.id);
		}));
	}

	/** Why a train is reported, with how late it is or how long it has waited (D2). */
	function alarmReasonText(alarm) {
		const reason = alarm.reason === "DEADLOCK" ? tr("和别的车互相等着走不了（锁死）")
			: alarm.reason === "WAITING" ? tr("等进路已 {0} 分钟", Math.floor(alarm.waiting / 60))
			: alarm.reason === "LATE" ? tr("晚点 {0} 分钟", Math.floor(alarm.late / 60)) : "";
		// Passing the stations a plan or a dispatcher's setting has it pass (D3), and turning back early (D4)
		const turn = alarm.turn === 1 ? tr("，将提前折返") : alarm.turn === 2 ? tr("，已提前折返") : alarm.turn === 3 ? tr("，指定的车站折不回去") : "";
		// And handing its duty over to a spare (D5)
		// And what a dispatcher ordered (D6)
		const dispatched = alarm.dispatch ? tr("，有调度命令") : "";
		const takeover = alarm.takeover === 1 ? tr("，等备车接替") : alarm.takeover === 2 ? tr("，已交给备车，空车回库") : alarm.takeover === 3 ? tr("，没有备车可接替") : alarm.takeover === 4 ? tr("，备车出场要经过受影响区段") : "";
		return reason + (alarm.skipping ? tr("，跳站中") : "") + turn + takeover + dispatched;
	}

	/** The rail system, a network of rails joined together, with its plans (D2). */
	const systemByAnchor = anchor => (data.contingencySystems || []).find(system => system.anchor === anchor) || null;
	/**
	 * The plans of an anchor, a rail system's own or one of those rails joined into it, as
	 * { anchor, plans, adhoc, system }, the system being the network's.
	 */
	const plansByAnchor = anchor => {
		for (const system of data.contingencySystems || []) {
			if (system.anchor === anchor) return { anchor, plans: system.plans, adhoc: system.adhoc, system };
			const other = (system.others || []).find(each => each.anchor === anchor);
			if (other) return { anchor, plans: other.plans, adhoc: other.adhoc, system };
		}
		return null;
	};
	const systemOfRoute = routeId => (data.contingencySystems || []).find(system => system.routes.includes(routeId)) || null;
	const systemOfLine = lineKey => {
		const line = data.lines.find(each => each.key === lineKey);
		return line ? line.routes.map(r => systemOfRoute(r.id)).find(Boolean) || null : null;
	};
	/** A rail system named by the lines it has, as a network has no name of its own. */
	function systemTitle(system) {
		const lineNames = [...new Set(system.routes.map(id => data.routeById.get(id)).filter(Boolean).map(r => shortName(r.line)))];
		return lineNames.length > 3 ? tr("{0} 等 {1} 条线", lineNames.slice(0, 3).join("、"), lineNames.length) : lineNames.join("、");
	}

	/** The fields of what is done with a train, in a plan or set there and then (D2). */
	function contingencyMeasureFields(system, plan) {
		const stations = system.stations || [];
		const stationOptions = stations.map(station => `<option value="${station.id}"${plan.turnStation === station.id ? " selected" : ""}>${escapeHtml(shortName(station.name))}</option>`).join("");
		const skipBoxes = stations.map(station => `<label><input type="checkbox" name="skip" value="${station.id}"${(plan.skipStations || []).includes(station.id) ? " checked" : ""}> ${escapeHtml(shortName(station.name))}</label>`).join("");
		return `<label>${tr("临时折返")} <select name="turn">
				<option value="none"${plan.turn === "none" ? " selected" : ""}>${tr("不折返")}</option>
				<option value="nearest"${plan.turn === "nearest" ? " selected" : ""}>${tr("最近的能折返的站")}</option>
				<option value="station"${plan.turn === "station" ? " selected" : ""}>${tr("指定车站")}</option></select></label>
			<label>${tr("折返站")} <select name="turnStation"><option value="0">—</option>${stationOptions}</select></label>
			<label>${tr("折返以后")} <select name="afterTurn">
				<option value="resume"${plan.afterTurn !== "deadhead" ? " selected" : ""}>${tr("接线运营（空车折返后重新载客）")}</option>
				<option value="deadhead"${plan.afterTurn === "deadhead" ? " selected" : ""}>${tr("回送回库")}</option></select></label>
			<p class="hint">${tr("折返前都先在折返站清客。")}</p>
			<div class="hint">${tr("跳过这些站（通过不停）：")}</div>${skipBoxes || `<span class="hint">${tr("路网的线路还没有车站")}</span>`}
			<label><input type="checkbox" name="takeover"${plan.takeover ? " checked" : ""}> ${tr("由备车接替余下的运用")}</label>`;
	}

	function readMeasureFields(row, plan) {
		plan.turn = row.querySelector("select[name=turn]").value;
		plan.turnStation = row.querySelector("select[name=turnStation]").value;
		plan.afterTurn = row.querySelector("select[name=afterTurn]").value;
		plan.skipStations = [...row.querySelectorAll("input[name=skip]:checked")].map(box => box.value);
		plan.takeover = row.querySelector("input[name=takeover]").checked;
		return plan.turn === "station" && plan.turnStation === "0" ? tr("选了指定车站折返，但没选车站") : null;
	}

	/**
	 * A rail system's plans for trains running late or stuck (故障预案, D2), for every route of the network whichever depot
	 * runs it: which routes, what makes a train fall under one, whether it starts by itself or waits for a dispatcher, and
	 * what is done. Drafted.
	 */
	function contingencyDialog(system) {
		const plans = (draft.contingencies[system.anchor] || system.plans || []).map(plan => Object.assign({}, plan));
		const routes = system.routes.map(id => data.routeById.get(id)).filter(Boolean);
		const planRow = plan => `<fieldset data-plan-row="${escapeHtml(plan.id)}"><legend>${escapeHtml(tr("预案 {0}", plan.id))}${plan.active ? ` <span class="hint">${tr("已启用")}</span>` : ""}</legend>
			<label>${tr("名称")} <input type="text" name="name" maxlength="64" value="${escapeHtml(plan.name || "")}"></label>
			<div class="hint">${tr("适用线路（都不勾就是路网的全部线路）：")}</div>
			${routes.map(r => `<label><input type="checkbox" name="planRoute" value="${r.id}"${(plan.routes || []).includes(r.id) ? " checked" : ""}> ${escapeHtml(routeTitle(r))}</label>`).join("")}
			<div class="hint">${tr("满足任一条件的车受影响（填 0 不看这一条）：")}</div>
			<label>${tr("晚点达到（分钟）")} <input type="number" min="0" max="1440" name="lateMinutes" value="${plan.lateMinutes}"></label>
			<label>${tr("等进路达到（分钟）")} <input type="number" min="0" max="1440" name="waitMinutes" value="${plan.waitMinutes}"></label>
			<label><input type="checkbox" name="deadlock"${plan.deadlock ? " checked" : ""}> ${tr("锁死（和别的车互相等着走不了）")}</label>
			<label>${tr("启用方式")} <select name="manual"><option value="auto"${plan.manual ? "" : " selected"}>${tr("条件满足就自动启用")}</option><option value="manual"${plan.manual ? " selected" : ""}>${tr("只报警，等调度员启用")}</option></select></label>
			${contingencyMeasureFields(system, plan)}
			<label><input type="checkbox" name="delete"> ${tr("删除这个预案")}</label>
		</fieldset>`;
		const nextId = () => String(Math.max(0, ...plans.map(plan => Number(plan.id) || 0)) + 1);
		const blank = () => ({ id: nextId(), name: "", routes: [], lateMinutes: 5, waitMinutes: 10, deadlock: true, manual: true, turn: "none", turnStation: "0", afterTurn: "resume", skipStations: [], takeover: false });
		const others = (system.others || []).filter(other => other.plans.length || draft.contingencies[other.anchor]);
		const othersText = others.length ? `<p class="warn">${tr("轨道接通后，这片路网里还有 {0} 套原来别的路网的预案，都还生效，排在这一套之后：", others.length)}</p>
			<ul>${others.map(other => `<li>${escapeHtml((draft.contingencies[other.anchor] || other.plans).map(plan => tr("预案 {0}「{1}」", plan.id, plan.name)).join("、") || tr("（已在草稿里清空）"))}</li>`).join("")}</ul>
			<label><input type="checkbox" name="mergeOthers"> ${tr("合成一套：把它们的预案并进这一套（重新编号），那几套清空")}</label>` : "";
		openDialog(tr("路网故障预案：{0}", escapeHtml(systemTitle(system))), `
			<p class="hint">${tr("一片连通的轨道（路网）共用一套预案，管路网里所有线路的车，不管来自哪个车厂。列车晚点、等进路太久或锁死时按预案处理。没有预案的线路只报警，运行照常；几个预案都符合时取编号最小的。报警和启用在“同步状态”里。")}</p>
			${othersText}
			<div data-plans>${plans.map(planRow).join("") || ""}</div>
			<button type="button" data-new-plan>${tr("新建预案")}</button>`, tr("放进草稿"), form => {
			const result = [];
			let error = null;
			form.querySelectorAll("[data-plan-row]").forEach(row => {
				if (row.querySelector("input[name=delete]").checked) return;
				const plan = { id: row.dataset.planRow, name: row.querySelector("input[name=name]").value.trim().slice(0, 64), routes: [...row.querySelectorAll("input[name=planRoute]:checked")].map(box => box.value),
					lateMinutes: Math.max(0, Math.min(1440, Math.round(Number(row.querySelector("input[name=lateMinutes]").value) || 0))),
					waitMinutes: Math.max(0, Math.min(1440, Math.round(Number(row.querySelector("input[name=waitMinutes]").value) || 0))),
					deadlock: row.querySelector("input[name=deadlock]").checked, manual: row.querySelector("select[name=manual]").value === "manual" };
				error = error || readMeasureFields(row, plan);
				if (!plan.lateMinutes && !plan.waitMinutes && !plan.deadlock) error = error || tr("预案“{0}”没有任何条件，不会生效", plan.name || plan.id);
				result.push(plan);
			});
			if (error) return error;
			const mergeBox = form.querySelector("input[name=mergeOthers]");
			if (mergeBox && mergeBox.checked) {
				let nextNumber = Math.max(0, ...result.map(plan => Number(plan.id) || 0));
				others.forEach(other => {
					(draft.contingencies[other.anchor] || other.plans).forEach(plan => result.push(Object.assign({}, plan, { id: String(++nextNumber), active: undefined })));
					draft.contingencies[other.anchor] = [];
				});
				if (result.length > 64) return tr("合在一起超过 64 个预案");
			}
			const strip = list => JSON.stringify(list.map(plan => Object.assign({}, plan, { active: undefined })));
			if (strip(result) === strip(system.plans || [])) delete draft.contingencies[system.anchor];
			else draft.contingencies[system.anchor] = result;
			saveDraft();
			renderAll();
		});
		dialog.querySelector("[data-new-plan]").addEventListener("click", () => {
			const plan = blank();
			plans.push(plan);
			dialog.querySelector("[data-plans]").insertAdjacentHTML("beforeend", planRow(plan));
		});
	}

	/** What is done with one train there and then, by a dispatcher, until ended (D2). */
	function adhocDialog(system, train, anchor) {
		const target = anchor || system.anchor;
		const current = ((plansByAnchor(target) || system).adhoc || {})[train] || { turn: "nearest", turnStation: "0", afterTurn: "resume", skipStations: [], takeover: false };
		openDialog(tr("临时处置：列车 {0}", escapeHtml(train)), `<div data-adhoc-row>${contingencyMeasureFields(system, current)}</div>
			<p class="hint">${tr("马上发给游戏，不进草稿；在“同步状态”里解除。")}</p>`, tr("发送"), form => {
			const setting = {};
			const error = readMeasureFields(form.querySelector("[data-adhoc-row]"), setting);
			if (error) return error;
			if (setting.turn === "none" && !setting.skipStations.length && !setting.takeover) return tr("什么处置都没选");
			sendJson("api/apply", "POST", { operations: [{ type: "contingency", system: target, action: "adhoc", train, setting }] }).catch(() => {});
		});
	}

	function renderStatus() {
		const line = currentLine();
		const count = line ? line.routes.reduce((a, r) => a + r.services.length, 0) : 0;
		const s = state.selected && data ? findService(state.selected) : null;
		const parts = [];
		if (s) parts.push(`<span>${tr("选中")} <b>${clock(s.start)}</b> ${escapeHtml(routeTitle(s.route))}</span>`);
		parts.push(`<span>${tr("线路")} <b>${data ? data.lines.length : 0}</b></span>`, `<span>${tr("本线班次")} <b>${count}</b></span>`);
		parts.push(connection === "ok" ? `<span>${tr("已同步")} <b>${new Date(lastLoaded || Date.now()).toLocaleTimeString()}</b></span>` : `<span class="warn">${tr("未连接")}</span>`);
		$("status").innerHTML = parts.join("");
	}

	function renderAll() {
		// The fast simulation is a debugging tool the server turns on (editorSimulation)
		const simulation = !!(data && data.simulation);
		$("btn-simulate").hidden = !simulation;
		if (!simulation && sim.on) setSimulation(false);
		// The line or day shown changed since the last simulation
		if (sim.on && !sim.timer && (sim.line !== state.line || sim.weekday !== state.weekday)) scheduleSimulation();
		renderTree();
		renderViews();
		watchNetwork();
		drawNetwork();
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
		] : line ? [[tr("新建种别…"), data.canEdit ? () => newTypeDialog(line, null) : null], [tr("能走的逻辑线路…"), data.canEdit && usesLogicalLines() ? () => lineTracksDialog(line) : null], [tr("路网故障预案…"), systemOfLine(line) ? () => contingencyDialog(systemOfLine(line)) : null],
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
	// The same button folds the media library's kinds while that shows (media.js)
	$("btn-tree").addEventListener("click", () => { if (inTimetable()) setTreeOpen(!state.treeOpen); });
	$("scrim").addEventListener("click", () => setTreeOpen(false));
	$("tab-diagram").addEventListener("click", () => { state.view = "diagram"; renderAll(); });
	$("tab-table").addEventListener("click", () => { state.view = "table"; renderAll(); });
	$("tab-network").addEventListener("click", () => { state.view = "network"; renderAll(); });
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
			[tr("线路（在全网配线图上选站）…"), data && data.canEdit ? () => startPicking() : null],
			[tr("线路（按停站图）…"), data && data.canEdit ? () => newLineDialog() : null],
			[tr("种别…"), r && data.canEdit ? () => newTypeDialog(r.lineKey, r) : null],
			[tr("时刻表…"), r ? () => tableDialog(r, -1) : null],
			[tr("直通关系…"), data && data.routes.length > 1 ? () => throughDialog(r) : null],
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
		if (e.target.closest("[data-line-diagram]") && currentLine()) {
			lineDiagramDialog(currentLine());
			return;
		}
		const diagramButton = e.target.closest("[data-route-diagram]");
		if (diagramButton && data && data.routeById.get(diagramButton.dataset.routeDiagram)) {
			routeDiagramDialog(data.routeById.get(diagramButton.dataset.routeDiagram));
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
	$("btn-simulate").addEventListener("click", () => setSimulation(!sim.on));
	$("btn-resimulate").addEventListener("click", () => simulate());
	$("btn-sim-now").addEventListener("click", () => {
		sim.fromNow = !sim.fromNow;
		renderSimStatus();
		simulate();
	});
	$("btn-zoom-in").addEventListener("click", () => zoom(1));
	$("btn-sync").addEventListener("click", syncDialog);
	$("btn-no-train").setAttribute("aria-pressed", String(showNoTrain));
	$("chk-depots").checked = showDepots;
	$("chk-access").checked = showAccess;
	$("chk-access").addEventListener("change", e => {
		showAccess = e.target.checked;
		try { localStorage.setItem("mtr-editor-show-access", showAccess ? "1" : "0"); } catch (error) {}
		draw();
	});
	$("chk-depots").addEventListener("change", e => {
		showDepots = e.target.checked;
		try { localStorage.setItem("mtr-editor-show-depots", showDepots ? "1" : "0"); } catch (error) {}
		draw();
	});
	$("btn-no-train").addEventListener("click", () => {
		showNoTrain = !showNoTrain;
		$("btn-no-train").setAttribute("aria-pressed", String(showNoTrain));
		try { localStorage.setItem("mtr-editor-show-no-train", showNoTrain ? "1" : "0"); } catch (e) {}
		renderAll();
	});
	$("btn-zoom-out").addEventListener("click", () => zoom(-1));
	$("day").addEventListener("change", e => {
		if (data) setWeekday(Number(e.target.value));
	});
	$("view-table").addEventListener("click", e => {
		const wayButton = e.target.closest("[data-way]");
		if (wayButton) {
			state.lineWay = +wayButton.dataset.way;
			renderTable();
			return;
		}
		const r = tableRouteOf();
		const managedButton = e.target.closest("[data-managed]");
		if (managedButton) {
			if (r) setManaged(r, managedButton.dataset.managed === "true");
			return;
		}
		const connectingButton = e.target.closest("[data-connecting]");
		if (connectingButton) {
			if (r) setConnecting(r, connectingButton.dataset.connecting === "true");
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
		if (e.target.closest("[data-clear-table]") && r) {
			const today = timetableOf(r).tables.findIndex(table => table.weekdays >> state.weekday & 1);
			if (today >= 0) clearTable(r, today);
			return;
		}
		const cell = e.target.closest("td[data-svc], th[data-svc]");
		if (cell && (e.ctrlKey || e.metaKey) && cell.tagName === "TH") {
			const s = findService(cell.dataset.svc);
			if (s && toggleChosen(s, e.shiftKey)) {
				tableMessage = state.range ? tr("选中 {0} 班，右键表头可以调整间隔", state.range.ids.length) : "";
				renderAll();
				return;
			}
		}
		if (cell && e.shiftKey && cell.tagName === "TH") {
			const s = findService(cell.dataset.svc);
			if (s && chooseRange(s)) {
				tableMessage = tr("选中 {0} 班，右键表头可以调整间隔", state.range.ids.length);
				renderTable();
				draw();
				return;
			}
		}
		if (cell && !e.shiftKey && !e.ctrlKey && !e.metaKey) state.range = null;
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
			openMenuAt(e, [
				[tr("改名和运行的星期…"), () => tableDialog(r, index)],
				[tr("清空这张表的班次"), timetable.tables[index].services.length ? () => clearTable(r, index) : null],
				[timetable.tables.length > 1 ? tr("删除这张表") : tr("删除这张表（至少要留一张）"), timetable.tables.length > 1 ? () => {
					if (confirm(`${tr("删除“{0}”和它的 {1} 个班次？", tableName(timetable.tables[index], index), timetable.tables[index].services.length)}`)) editTimetable(r, edited => { edited.tables.splice(index, 1); });
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
	/**
	 * A table's least performance as shown: acceleration and deceleration in m/s², highest speed in km/h, or empty if unset.
	 */
	function perfText(table, key) {
		const value = key === "a" ? table.minAcceleration : key === "d" ? table.minDeceleration : table.minMaxSpeed;
		if (!(value > 0)) return "";
		return key === "v" ? String(Math.round(value * 72)) : (value * 400).toFixed(2);
	}

	$("view-table").addEventListener("change", e => {
		if (e.target.matches("[data-show-deadheads]")) {
			showDeadheads = e.target.checked;
			try { localStorage.setItem("mtr-editor-show-deadheads", showDeadheads ? "1" : "0"); } catch (error) {}
			renderTable();
			return;
		}
		const r = tableRouteOf();
		if (!r) return;
		if (e.target.matches("[data-perf]")) {
			const key = e.target.dataset.perf, value = Number(e.target.value);
			const today = timetableOf(r).tables.findIndex(table => table.weekdays >> state.weekday & 1);
			if (!(value > 0)) return;
			editTimetable(r, timetable => {
				const table = timetable.tables[today];
				// Kept as the game keeps them: m/tick² (m/s² ÷ 400) and blocks per tick (km/h ÷ 72)
				if (key === "a") table.minAcceleration = value / 400;
				else if (key === "d") table.minDeceleration = value / 400;
				else table.minMaxSpeed = value / 72;
			});
		} else if (e.target.matches("[data-margin]")) {
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
	/** Whether the drag going on rounds to the second, with Alt held. */
	let dragFine = false;
	const hitHandle = (x, y) => handles.find(h => Math.abs(h.x - x) < 7 && Math.abs(h.y - y) < 7);

	/**
	 * Drags the end of a stop: one selected service keeps its own stop time as an exception, and with the whole route
	 * selected the table's departure time changes for all of them.
	 */
	function dragDwell(handle, x) {
		const s = handle.s, i = handle.i, arrival = timesOf(s)[i][0];
		const step = dragFine || state.pxPerMin >= 32 ? 1 : 5;
		const dwell = Math.max(0, Math.round((millisAt(x) - arrival) / 1000 / step) * step);
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

	/**
	 * The services a drag along a line moves: the chosen run when the line is in it, the selected service, or every
	 * service of the route chosen with a double click; none while a duty is chosen, where clicks add services to it.
	 */
	function servicesToMove(s) {
		if (!s || state.slot || !data.canEdit) return null;
		// Of several chosen, the ones edited on their own (with exceptions, drawn blue) stay where they are
		const unedited = list => list.filter(o => o === s || !o.exceptions);
		if (inRange(s)) return unedited(rangeServices()).map(o => o.id);
		if (state.selectedRoute === s.route.id) return unedited(s.route.services.filter(o => o.tableIndex === s.tableIndex)).map(o => o.id);
		// Any other line moves on its own, chosen or not (2026-11-09), so it needn't be clicked first
		return [s.id];
	}

	/**
	 * How far a drag moves services: whole seconds, rounded more coarsely the further the view is zoomed out; to the
	 * second when zoomed right in or with Alt held.
	 */
	function moveShift(dx, fine) {
		const step = fine || state.pxPerMin >= 32 ? 1000 : state.pxPerMin >= 8 ? 5000 : state.pxPerMin >= 3 ? 15000 : state.pxPerMin >= 1 ? 60000 : 300000;
		return Math.round(dx / state.pxPerMin * 60000 / step) * step;
	}

	/** Moves services by the same time each, keeping their stops and run times, into the draft. */
	function moveServices(ids, shift) {
		const services = ids.map(findService).filter(Boolean);
		if (!services.length || !shift) return;
		const r = services[0].route, tableIndex = services[0].tableIndex;
		const wasSelected = state.selected && ids.includes(state.selected) ? state.selected : null;
		// Only those moved get their new times: a line dragged on its own leaves the others chosen as they are
		const movedIds = new Set(ids);
		const moved = id => {
			if (!movedIds.has(id)) return id;
			const [routeId, service] = id.split("@");
			return routeId + "@" + mod(Number(service) + shift, DAY);
		};
		let failed = false;
		editTimetable(r, timetable => {
			const table = timetable.tables[tableIndex];
			// All or none: a service landing on one that stays where it is stops the whole move
			const movingSet = new Set(services.map(o => o.service));
			const blocked = services.map(o => mod(o.service + shift, DAY)).find(time => table.services.includes(time) && !movingSet.has(time));
			if (blocked !== undefined) {
				failed = true;
				return tr("{0} 已经有一班，没有移动", clock(mod(blocked - data.dayStartMillis, DAY)));
			}
			// Moving later from the last, earlier from the first, so none lands on one not moved yet
			const order = services.map(o => o.service).sort((a, b) => shift > 0 ? b - a : a - b);
			for (const service of order) {
				const error = retimeService(table, service, mod(service + shift, DAY));
				if (error) return error;
			}
			return tr("{0} 班{1}了 {2}", services.length, shift > 0 ? tr("推后") : tr("提前"), durationText(Math.abs(shift)));
		});
		// The moved services keep being chosen, under their new times
		if (!failed) {
			if (state.range) state.range = { anchor: moved(state.range.anchor), ids: state.range.ids.map(moved) };
			if (wasSelected) state.selected = moved(wasSelected);
			renderAll();
		}
	}

	canvas.addEventListener("pointerdown", e => {
		const box = canvas.getBoundingClientRect();
		const handle = hitHandle(e.clientX - box.left, e.clientY - box.top);
		const s = !handle && hitService(e.clientX - box.left, e.clientY - box.top);
		const ids = s ? servicesToMove(s) : null;
		const chosen = !ids ? 0 : inRange(s) ? state.range.ids.length : state.selectedRoute === s.route.id ? s.route.services.filter(o => o.tableIndex === s.tableIndex).length : 1;
		dragFine = e.altKey;
		// A duty's run out of its depot, dragged to leave earlier or later (运用补完 step 3)
		const leg = !handle && !s && data && data.canEdit && !state.slot ? hitDepotLeg(e.clientX - box.left, e.clientY - box.top) : null;
		if (leg && leg.kind === "out") {
			drag = { deadhead: { depot: leg.depot, siding: leg.siding, index: leg.index, shift: 0 }, x: e.clientX, y: e.clientY, moved: false, leg };
			canvas.setPointerCapture(e.pointerId);
			return;
		}
		drag = handle ? { handle, moved: false } : ids ? { move: ids, kept: chosen - ids.length, x: e.clientX, y: e.clientY, moved: false } : { x: e.clientX, y: e.clientY, viewStart: state.viewStart, scrollY: state.scrollY, moved: false };
		canvas.setPointerCapture(e.pointerId);
	});
	canvas.addEventListener("pointermove", e => {
		const box = canvas.getBoundingClientRect();
		if (drag && drag.handle) {
			drag.moved = true;
			dragFine = e.altKey;
			canvas.classList.add("dragging");
			dragDwell(drag.handle, e.clientX - box.left);
			return;
		}
		if (drag && drag.deadhead) {
			const dx = e.clientX - drag.x;
			if (Math.abs(dx) > 3) drag.moved = true;
			if (drag.moved) {
				canvas.classList.add("dragging");
				drag.deadhead.shift = moveShift(dx, e.altKey);
				const shift = drag.deadhead.shift;
				showDragTip(tr("出库 {0}{1}", shift >= 0 ? tr("晚 ") : tr("早 "), durationText(Math.abs(shift))), e.clientX, e.clientY);
				draw();
			}
			return;
		}
		if (drag && drag.move) {
			const dx = e.clientX - drag.x;
			if (Math.abs(dx) > 3) drag.moved = true;
			if (drag.moved) {
				canvas.classList.add("dragging");
				state.move = { ids: drag.move, shift: moveShift(dx, e.altKey) };
				showTooltip(null);
				canvas.title = tr("{0} 班，{1}{2}", drag.move.length, state.move.shift >= 0 ? "+" : "−", durationText(Math.abs(state.move.shift))) + (drag.kept ? tr("（{0} 班单独编辑过，不动）", drag.kept) : "");
				showDragTip(canvas.title, e.clientX, e.clientY);
				draw();
			}
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
		const accessed = !s && hitAccess(e.clientX - box.left, e.clientY - box.top);
		showTooltip(s || accessed, e.clientX, e.clientY, Boolean(accessed));
	});
	canvas.addEventListener("pointerup", e => {
		const box = canvas.getBoundingClientRect();
		if (drag && drag.deadhead && drag.moved) {
			const moved = drag.deadhead;
			drag = null;
			showDragTip(null);
			canvas.classList.remove("dragging");
			const depot = depotById(moved.depot), siding = depot && depot.sidings.find(o => o.id === moved.siding);
			const slot = siding && (dutiesOf(depot).sidings[siding.id] || [])[moved.index];
			const day = slot && dutyOn(slot, state.weekday);
			const out = day && deadheadTimes(siding, day).out;
			if (out && moved.shift) {
				editDutyException(depot, siding.id, moved.index, edited => {
					// Leaving later is a shorter lead, never shorter than the way out takes
					edited.outLead = Math.max(out.wayOut, out.lead - moved.shift);
					edited.exceptions = (edited.exceptions || 0) | EXCEPTION.OUT_LEAD;
					return tr("{0} 改为首班前 {1} 出库（这份运用的例外）", out.number, durationText(edited.outLead));
				});
			} else {
				draw();
			}
			return;
		}
		if (drag && drag.deadhead) {
			// A click on the run out: as a click on a depot's run, choosing its duty
			drag = { x: drag.x, y: drag.y, moved: false };
		}
		if (drag && drag.move && drag.moved) {
			const move = state.move;
			state.move = null;
			drag = null;
			canvas.title = "";
			showDragTip(null);
			canvas.classList.remove("dragging");
			if (move) moveServices(move.ids, move.shift);
			else draw();
			return;
		}
		if (drag && !drag.moved && !drag.handle) {
			const x = e.clientX - box.left, y = e.clientY - box.top;
			const s = hitService(x, y);
			if (state.slot && s) {
				// Shift: every service the train can run one after another from the one clicked before to this one
				if (e.shiftKey && lastSlotService && lastSlotService !== s.id && findService(lastSlotService)) addRangeToSlot(findService(lastSlotService), s);
				else toggleInSlot(s);
				lastSlotService = s.id;
				selectService(s.id, false);
				drag = null;
				canvas.classList.remove("dragging");
				return;
			}
			const leg = !s && hitDepotLeg(x, y);
			if (leg) {
				drag = null;
				canvas.classList.remove("dragging");
				state.slot = { depot: leg.depot, siding: leg.siding, index: leg.index };
				dutyNotice = leg.text;
				renderAll();
				return;
			}
			if (s && (e.ctrlKey || e.metaKey) && toggleChosen(s, e.shiftKey)) {
				drag = null;
				canvas.classList.remove("dragging");
				renderAll();
				return;
			}
			if (s && e.shiftKey && chooseRange(s)) {
				drag = null;
				canvas.classList.remove("dragging");
				renderTable();
				draw();
				return;
			}
			state.range = null;
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
	// Right-click a line: choose it and every later service of its table, or move them together (2026-11-09)
	canvas.addEventListener("contextmenu", e => {
		const box = canvas.getBoundingClientRect();
		const s = data && hitService(e.clientX - box.left, e.clientY - box.top);
		const leg = !s && data ? hitDepotLeg(e.clientX - box.left, e.clientY - box.top) : null;
		if (leg) {
			e.preventDefault();
			deadheadMenu(e, leg);
			return;
		}
		if (!s) return;
		e.preventDefault();
		const later = laterServices(s);
		const items = [];
		if (later.length) {
			items.push([tr("选中这一班和之后的 {0} 班", later.length), () => chooseLater(s)]);
			items.push([tr("这一班和之后的 {0} 班一起平移…", later.length), data.canEdit && !state.slot ? () => shiftLaterDialog(s) : null]);
		}
		if (state.range || state.selectedRoute) items.push([tr("取消选择"), () => {
			state.range = null;
			state.selectedRoute = null;
			renderAll();
		}]);
		openMenuAt(e, items);
	});
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
		if (!inTimetable()) return;
		if (e.key === "Escape" && state.slot && !dialog.open && e.target.tagName !== "INPUT") {
			state.slot = null;
			renderAll();
			return;
		}
		if (e.key === "Escape" && (state.selected || state.selectedRoute || state.range) && !dialog.open && e.target.tagName !== "INPUT") {
			state.selectedRoute = null;
			state.range = null;
			selectService(null, false);
		}
	});
	new ResizeObserver(() => draw()).observe(wrap);
	// Back from the media library: the views were left alone meanwhile, so draw them as they are now, and ask the game at once
	document.addEventListener("mtr:workspace", e => {
		if (e.detail.ws !== "timetable") return;
		renderAll();
		poll(true);
	});
	// The line of the time now moves on
	setInterval(() => { if (data) draw(); }, 30000);
	addEventListener("resize", () => { if (inTimetable()) renderAll(); });
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
