"use strict";

/*
 * The advanced editor, read only for now: the routes of each line on a running diagram, and each route's timetable laid
 * out like a printed one. The game serves this page to this computer only, and the data needs the key in the address.
 */
(() => {
	const DAY = 24 * 60 * 60 * 1000;
	const POLL_MILLIS = 3000;
	// Room below the last station for the labels of trains carrying on to another route
	const LABEL_W = 120, AXIS_H = 28, MIN_ROW_GAP = 22, PAD_BOTTOM = 30;
	const ZOOMS = [0.5, 1, 2, 3, 4, 6, 8, 12, 16];
	const WEEKDAY_NAMES = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"];
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
	const state = {
		view: "diagram",
		line: null,
		route: null,
		// The selected service as "routeId@service"
		selected: null,
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

	async function poll() {
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
		setTimeout(poll, POLL_MILLIS);
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

	/** The table a route runs on a day for each of its services, which is the first of its tables that has it that day. */
	function buildServices() {
		const weekday = state.weekday;
		data.routes.forEach(r => {
			const services = new Map();
			r.tables.forEach((table, tableIndex) => {
				if ((table.weekdays >> weekday & 1) === 0) return;
				table.services.forEach(service => {
					if (services.has(service)) return;
					const link = table.links.find(l => l.service === service);
					services.set(service, {
						id: r.id + "@" + service, route: r, service, table, tableIndex, link,
						returns: table.returns.includes(service),
						// The time in the operating day, so the last trains after midnight come after the first ones
						start: mod(service - data.dayStartMillis, DAY),
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
	const timesOf = s => s.table.plans.map(([arrival, departure]) => [s.start + arrival, s.start + departure]);

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
					parts.push(`<li>${node(`data-route="${r.id}"`, routeOpen ? "▾" : "▸", `<span class="swatch" style="background:${colorOf(r.color)}"></span><span class="label">${escapeHtml(routeTitle(r))}</span><span class="count">${r.services.length}</span>`, false)}`);
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
	let layout = null, drawn = [];

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
			const isSelected = s.id === state.selected;
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
			if (s.link || s.returns) {
				const next = s.link && data.routeById.get(s.link.route);
				ctx.fillStyle = s.link ? c.ink : c.muted;
				ctx.font = `11px ${css("--font-ui")}`;
				ctx.textAlign = "left";
				ctx.fillText(s.link ? "→ " + (next ? shortName(next.name) : "?") : "回库", ex + 4, ey + (s.route.rows[0] < s.route.rows[s.route.rows.length - 1] ? 12 : -4));
			}
			ctx.globalAlpha = 1;
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
			const pass = stop.pass && i > 0 && i < times.length - 1;
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
		const line = currentLine();
		if (line) {
			parts.push(`<h2>线路</h2><div class="card"><div class="title">${escapeHtml(shortName(line.name))}</div><dl class="kv">${line.routes.map(r => `<dt>${escapeHtml(routeTitle(r))}</dt><dd>${r.services.length} 班</dd>`).join("")}</dl></div>`);
		}
		parts.push(`<p class="hint">滚轮缩放时间轴，拖动平移，Shift + 滚轮上下移动。点一条线选中这一班，在下面的时刻表里高亮。现在只能查看，编辑在下一步加入。</p>`);
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
		const tools = `<div class="tt-tools"><b>${escapeHtml(routeTitle(r))}</b><span class="hint">${WEEKDAY_NAMES[state.weekday]} · ${services.length} 班${r.hasTimes ? "" : " · 正在向服务端取各站时刻"}</span></div>`;
		if (!services.length) {
			view.innerHTML = tools + `<div class="table-empty">这条线路${WEEKDAY_NAMES[state.weekday]}没有班次</div>`;
			return;
		}
		const multipleTables = r.tables.length > 1;
		const head = `<thead><tr><th class="st">车站</th><th class="kind"></th>${services.map(s => `<th class="svc-head${s.id === state.selected ? " hl" : ""}" data-svc="${s.id}"><span class="time">${clock(s.start)}</span>${multipleTables ? `<span class="note">${escapeHtml(s.table.name || "表 " + (s.tableIndex + 1))}</span>` : ""}${s.link ? `<span class="note">直通</span>` : s.returns ? `<span class="note">回库</span>` : ""}</th>`).join("")}</tr></thead>`;
		const allTimes = services.map(timesOf);
		const rows = [];
		r.stops.forEach((stop, i) => {
			const last = i === r.stops.length - 1;
			const pass = stop.pass && i > 0 && !last;
			const kinds = i === 0 ? ["发"] : last ? ["到"] : pass ? ["通过"] : ["到", "发"];
			kinds.forEach((kind, k) => {
				const cells = services.map((s, j) => {
					const t = allTimes[j][i];
					return `<td class="${pass ? "pass" : ""}${s.id === state.selected ? " hl" : ""}" data-svc="${s.id}">${clock(kind === "发" ? t[1] : t[0], true)}</td>`;
				}).join("");
				rows.push(`<tr class="${k === 0 ? "first-of-station" : ""}">${k === 0 ? `<th class="st" rowspan="${kinds.length}" title="${escapeHtml(stop.name)}">${escapeHtml(shortName(stop.name))}</th>` : ""}<th class="kind">${kind}</th>${cells}</tr>`);
			});
		});
		view.innerHTML = tools + `<table class="tt">${head}<tbody>${rows.join("")}</tbody></table>`;
		// Another route's table starts from its first service
		if (tableRoute !== r.id) {
			view.scrollLeft = 0;
			tableRoute = r.id;
		}
		const selectedHead = view.querySelector("th.svc-head.hl");
		if (selectedHead) selectedHead.scrollIntoView({ block: "nearest", inline: "center" });
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
		const text = !key ? messages.key : messages[connection] || (missingTimes ? "部分线路还没有各站时刻，正在向服务端取，取到之前按虚线画。" : "");
		banner.hidden = !text;
		banner.textContent = text;
		banner.classList.toggle("warn", Boolean(messages[connection]) || !key);
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
	$("btn-zoom-in").addEventListener("click", () => zoom(1));
	$("btn-zoom-out").addEventListener("click", () => zoom(-1));
	$("day").addEventListener("change", e => {
		state.weekday = Number(e.target.value);
		if (data) {
			buildServices();
			if (state.selected && !findService(state.selected)) state.selected = null;
		}
		renderAll();
	});
	$("view-table").addEventListener("click", e => {
		const el = e.target.closest("[data-svc]");
		if (el) selectService(el.dataset.svc === state.selected ? null : el.dataset.svc, true);
	});

	let drag = null;
	canvas.addEventListener("pointerdown", e => {
		drag = { x: e.clientX, y: e.clientY, viewStart: state.viewStart, scrollY: state.scrollY, moved: false };
		canvas.setPointerCapture(e.pointerId);
	});
	canvas.addEventListener("pointermove", e => {
		const box = canvas.getBoundingClientRect();
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
		canvas.classList.toggle("pointer", Boolean(s));
		showTooltip(s, e.clientX, e.clientY);
	});
	canvas.addEventListener("pointerup", e => {
		const box = canvas.getBoundingClientRect();
		if (drag && !drag.moved) {
			const s = hitService(e.clientX - box.left, e.clientY - box.top);
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
		if (e.key === "Escape" && state.selected) selectService(null, false);
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
