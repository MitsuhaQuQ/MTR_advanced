"use strict";

/*
 * The advanced editor's shell: two workspaces on one page, the timetable editor (editor.js) and the media library
 * (media.js), switched in the top bar without loading the page again, so the diagram keeps its zoom, scroll and
 * selection. The game opens the page as #key=…; the key is read once here for both, and the address then names the
 * workspace: #/timetable, or #/media/<kind> (#/media/audio). The media library's script is loaded the first time it opens.
 * The body's data-ws says which workspace shows; elements marked data-ws show only in theirs (editor.css).
 */
const MTREditor = (() => {
	const WORKSPACES = ["timetable", "media"];

	const key = (() => {
		const match = location.hash.match(/key=([0-9a-f]+)/);
		try {
			if (match) sessionStorage.setItem("mtr-editor-key", match[1]);
			return match ? match[1] : sessionStorage.getItem("mtr-editor-key") || "";
		} catch (e) {
			return match ? match[1] : "";
		}
	})();

	/** The workspace and the part of it named in the address; anything else is the timetable. */
	const parse = hash => {
		const match = String(hash).match(/^#\/([a-z]+)(?:\/([a-z0-9_-]+))?/);
		return match && WORKSPACES.includes(match[1]) ? { ws: match[1], part: match[2] || "" } : { ws: "timetable", part: "" };
	};
	const hashOf = route => "#/" + route.ws + (route.part ? "/" + route.part : "");

	let route = parse(location.hash);
	let mediaLoading = false;

	function show() {
		document.body.dataset.ws = route.ws;
		document.querySelectorAll("[data-ws-tab]").forEach(tab => {
			const selected = tab.dataset.wsTab === route.ws;
			tab.setAttribute("aria-selected", String(selected));
			tab.tabIndex = selected ? 0 : -1;
		});
		if (route.ws === "media" && !mediaLoading) {
			mediaLoading = true;
			const script = document.createElement("script");
			script.src = "media.js";
			document.body.appendChild(script);
		}
		document.dispatchEvent(new CustomEvent("mtr:workspace", { detail: { ws: route.ws, part: route.part } }));
	}

	/** Opens a workspace (and a part of it), as a new entry of the browser's history so Back returns. */
	function open(ws, part) {
		const next = { ws, part: part || "" };
		if (hashOf(next) === hashOf(route)) return;
		route = next;
		history.pushState(null, "", location.pathname + hashOf(route));
		show();
	}

	// The key leaves the address at once, so it isn't kept in the history or shown
	history.replaceState(null, "", location.pathname + hashOf(route));
	// Back and Forward, or an address typed in: both events may come for one change, so only a new route counts
	const follow = () => {
		const next = parse(location.hash);
		if (hashOf(next) === hashOf(route)) return;
		route = next;
		show();
	};
	addEventListener("hashchange", follow);
	addEventListener("popstate", follow);

	document.querySelectorAll("[data-ws-tab]").forEach(tab => tab.addEventListener("click", event => {
		event.preventDefault();
		open(tab.dataset.wsTab, tab.dataset.wsTab === route.ws ? route.part : tab.dataset.wsPart);
	}));
	// Arrow keys move along the workspace tabs, as in any tab list
	document.querySelector(".ws-tabs")?.addEventListener("keydown", event => {
		if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
		const tabs = [...document.querySelectorAll("[data-ws-tab]")];
		const index = tabs.findIndex(tab => tab.dataset.wsTab === route.ws);
		const next = tabs[(index + (event.key === "ArrowRight" ? 1 : tabs.length - 1)) % tabs.length];
		open(next.dataset.wsTab, next.dataset.wsPart);
		next.focus();
	});

	show();

	return {
		key,
		get ws() {
			return route.ws;
		},
		get part() {
			return route.part;
		},
		open,
		inTimetable: () => route.ws === "timetable"
	};
})();
