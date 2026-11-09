"use strict";

/*
 * The media library of the advanced editor, its second workspace (shell.js loads this the first time it opens): the
 * files the server keeps, one kind at a time, picked on the left; the files of that kind in the middle, searched and
 * filtered; the file picked on the right, with what can be done to it. Files are added with the upload button or by
 * dropping them on the list.
 *
 * Each kind is one entry of KINDS: its API, the files it takes, its list's columns and its detail. Announcement audio
 * is the only kind so far (it was the page audio.html, which now opens this): Ogg Vorbis files the game keeps, each named
 * by announcers as the uploader's name in lowercase, a colon and its file name, and also a sound of the game,
 * mtr_audio:<uploader>/<sound ID>, which /playsound plays. The game sends files to the server and answers later, so the
 * list is read again every few seconds while it shows. Files only play here once this computer has them; the game asks
 * the server for the missing ones by itself.
 */
(() => {
	const POLL_MILLIS = 2000;
	const MAX_MESSAGES = 8;

	const $ = id => document.getElementById(id);
	const escapeHtml = text => String(text).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&#39;" })[c]);
	const key = MTREditor.key;

	async function fetchJson(path) {
		const response = await fetch(path, { headers: { "X-MTR-Editor-Key": key }, cache: "no-store" });
		if (!response.ok) throw new Error(String(response.status));
		return response.json();
	}

	async function sendJson(path, body) {
		const response = await fetch(path, { method: "POST", headers: { "X-MTR-Editor-Key": key, "Content-Type": "application/json" }, body: JSON.stringify(body), cache: "no-store" });
		if (!response.ok) throw new Error(String(response.status));
		return response.json();
	}

	const formatSize = bytes => bytes >= 1024 * 1024 ? (bytes / 1024 / 1024).toFixed(1) + " MB" : Math.max(1, Math.round(bytes / 1024)) + " KB";
	const formatDuration = millis => {
		const seconds = Math.round(millis / 100) / 10;
		return seconds >= 60 ? Math.floor(seconds / 60) + ":" + String(Math.round(seconds % 60)).padStart(2, "0") : seconds.toFixed(1) + tr(" 秒");
	};
	const formatClock = seconds => Math.floor(seconds / 60) + ":" + String(Math.floor(seconds % 60)).padStart(2, "0");
	const formatTime = millis => {
		const date = new Date(millis);
		const pad = value => String(value).padStart(2, "0");
		return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
	};
	const failed = e => e.message === "413" ? tr("文件太大") : tr("发送失败，游戏可能已经关闭");

	// ---------- Announcement audio ----------
	const SOUND_ID = /^[a-z0-9_.-]+(\/[a-z0-9_.-]+)*$/;
	const MAX_SOUND_ID = 64;
	const isSoundId = id => id.length <= MAX_SOUND_ID && SOUND_ID.test(id);
	// As the game makes one: the file name without .ogg in lowercase, spaces as _, or nothing for a name in Chinese
	const soundIdFromName = name => {
		const id = name.replace(/\.ogg$/i, "").toLowerCase().replace(/ /g, "_");
		return isSoundId(id) ? id : "";
	};
	const askSoundId = (text, suggested) => {
		for (;;) {
			const answer = prompt(text, suggested);
			if (answer === null) return null;
			const id = answer.trim();
			if (isSoundId(id)) return id;
			alert(tr("声音 ID 只能用小写字母、数字和 _ - . /，最长 {0} 个字符。", MAX_SOUND_ID));
			suggested = id;
		}
	};

	const audio = {
		id: "audio",
		name: () => tr("音频"),
		api: "api/audio",
		accept: ".ogg,audio/ogg",
		takes: file => /\.ogg$/i.test(file.name) || /ogg/.test(file.type),
		wrongType: () => tr("只收 Ogg Vorbis（.ogg）文件"),
		uploadLabel: () => tr("上传音频"),
		noUpload: () => tr("没有上传音频的权限"),
		uploadTitle: () => tr("选择 Ogg Vorbis 文件（.ogg），也可以直接拖到列表上"),
		limits: data => data.maxFileBytes > 0
			? tr("只支持 Ogg Vorbis（.ogg），单个文件不超过 {0}、{1} 秒。单声道约每秒 6 KB。", formatSize(data.maxFileBytes), data.maxSeconds) + (data.canUpload ? "" : tr("你没有上传音频的权限。"))
			: tr("服务器没有开放上传音频。"),
		empty: () => tr("服务器上还没有报站音频。"),
		matches: (entry, text) => [entry.name, entry.reference, entry.sound || ""].some(value => value.toLowerCase().includes(text)),
		row: entry => {
			const isPlaying = playing === entry.hash;
			return `<td class="name">${escapeHtml(entry.name)}<div class="ref">${escapeHtml(entry.reference)}</div></td>
				<td class="num">${formatDuration(entry.durationMillis)}</td>
				<td class="num">${formatSize(entry.size)}</td>
				<td class="uploader">${escapeHtml(entry.uploaderName)}</td>
				<td class="${entry.cached ? "" : "missing"}">${localText(entry)}</td>
				<td class="actions"><button data-play="${entry.hash}" ${entry.cached ? "" : `disabled title="${tr("本机还没有这个文件")}"`}>${isPlaying ? tr("停止") : tr("试听")}</button></td>`;
		},
		detail: entry => {
			const isPlaying = playing === entry.hash;
			return `<h2>${tr("音频")}</h2>
				<div class="card">
					<div class="title">${escapeHtml(entry.name)}</div>
					<div class="media-player">
						<button data-play="${entry.hash}" ${entry.cached ? "" : `disabled title="${tr("本机还没有这个文件")}"`}>${isPlaying ? tr("停止") : tr("试听")}</button>
						<span class="t" id="media-position">0:00</span>
						<span class="bar"><i id="media-progress"></i></span>
						<span class="t">${formatClock(entry.durationMillis / 1000)}</span>
					</div>
					<dl class="kv">
						<dt>${tr("时长")}</dt><dd>${formatDuration(entry.durationMillis)}</dd>
						<dt>${tr("大小")}</dt><dd>${formatSize(entry.size)}</dd>
						<dt>${tr("上传者")}</dt><dd>${escapeHtml(entry.uploaderName)}</dd>
						<dt>${tr("上传时间")}</dt><dd>${formatTime(entry.uploadedAt)}</dd>
						<dt>${tr("本机")}</dt><dd>${localText(entry)}</dd>
					</dl>
				</div>
				<div class="card">
					<div class="media-ref"><span class="label" title="${tr("填进报站器的“音频文件”栏")}">${tr("引用名")}</span><code>${escapeHtml(entry.reference)}</code><button class="link" data-copy="${escapeHtml(entry.reference)}">${tr("复制")}</button></div>
					${entry.sound ? `<div class="media-ref"><span class="label" title="${tr("/playsound 用这个名字播放，报站器的“音频文件”栏也可以填它")}">/playsound</span><code>${escapeHtml(entry.sound)}</code><button class="link" data-copy="${escapeHtml(entry.sound)}">${tr("复制")}</button></div>` : ""}
					<p class="hint">${tr("每个音频在游戏里也是一个声音，可以用 /playsound 播放，例如 /playsound mtr_audio:<上传者>/<声音 ID> block @a。")}</p>
				</div>
				${entry.canDelete ? `<div class="media-actions"><button data-sound-id="${escapeHtml(entry.key)}">${tr("改声音 ID")}</button><button class="danger" data-delete="${escapeHtml(entry.key)}">${tr("删除")}</button></div>` : ""}`;
		},
		status: data => `<span>${tr("音频")} <b>${data.entries.length}</b> ${tr("个")}</span><span>${tr("本机已有")} <b>${data.entries.filter(entry => entry.cached).length}</b> ${tr("个")}</span>` +
			(data.entries.some(entry => entry.downloading) ? tr("<span>正在下载…</span>") : ""),

		async upload(file) {
			// One of this player's own files of the same name is updated: it keeps its sound ID, so nothing is asked
			const data = stores.audio.data;
			const fileName = /\.ogg$/i.test(file.name) ? file.name : file.name + ".ogg";
			const updating = data && data.entries.some(entry => entry.mine && entry.name.toLowerCase() === fileName.toLowerCase());
			// A file name in Chinese can't be a sound ID, so one has to be given
			const suggested = soundIdFromName(file.name);
			const soundId = updating ? "" : askSoundId(suggested ? tr("“{0}”的声音 ID（/playsound 用它播放）：", file.name) : tr("“{0}”的文件名不能当声音 ID，请填一个（小写字母、数字和 _ - . /）：", file.name), suggested);
			if (soundId === null) return;
			const result = await sendJson("api/audio", { action: "upload", name: file.name, soundId, data: await readBase64(file) });
			if (!result.ok) note(file.name, result.message);
		},

		// One name goes; the same recording under another name stays
		async remove(entry) {
			if (!confirm(`${tr("从服务器删除“{0}”？用这个名字的报站器都会不出声，不能撤销。", entry.reference)}`)) return;
			const result = await sendJson("api/audio", { action: "delete", key: entry.key });
			if (!result.ok) note(entry.name, result.message);
		},

		async changeSoundId(entry) {
			const soundId = askSoundId(tr("“{0}”的新声音 ID（用旧 ID 的 /playsound 和报站器要改成新的）：", entry.reference), entry.soundId);
			if (soundId === null || soundId === entry.soundId) return;
			const result = await sendJson("api/audio", { action: "soundId", key: entry.key, soundId });
			if (!result.ok) note(entry.name, result.message);
		}
	};

	const KINDS = [audio];

	// ---------- State ----------
	// Each kind's answer from the game, and whether it answered
	const stores = Object.fromEntries(KINDS.map(kind => [kind.id, { data: null, connection: "connecting" }]));
	const view = { kind: KINDS[0], selected: null, search: "", uploader: "", missingOnly: false };
	// Messages made here (files refused before sending, copies), newest first, shown with the game's answers to uploads
	const messages = [];
	let playing = null, playingUrl = null, pollTimer = null, sideOpen = true;

	const kindOf = part => KINDS.find(kind => kind.id === part) || KINDS[0];
	const store = () => stores[view.kind.id];
	const active = () => MTREditor.ws === "media";
	const localText = entry => entry.cached ? tr("有") : entry.downloading ? tr("下载中") : tr("没有");
	// The list is read every few seconds: a part is only written again when it changed, so focus and hover stay put
	const setHtml = (element, html) => {
		if (element.dataset.html !== html) {
			element.innerHTML = html;
			element.dataset.html = html;
		}
	};
	const note = (name, message, ok) => {
		messages.unshift({ name, message, ok: Boolean(ok) });
		messages.splice(MAX_MESSAGES);
	};

	function visibleEntries() {
		const data = store().data;
		if (!data) return [];
		const text = view.search.trim().toLowerCase();
		return data.entries
			.filter(entry => !view.uploader || entry.uploaderName === view.uploader)
			.filter(entry => !view.missingOnly || !entry.cached)
			.filter(entry => !text || view.kind.matches(entry, text))
			.sort((a, b) => b.uploadedAt - a.uploadedAt);
	}

	// ---------- Reading ----------
	async function load() {
		const s = store();
		try {
			s.data = await fetchJson(view.kind.api);
			I18N.setLanguage(s.data.language);
			s.connection = "ok";
		} catch (e) {
			s.connection = e.message === "401" ? "key" : e.message === "503" ? "world" : "offline";
		}
		render();
	}

	// Read while the library shows and the page is in view; otherwise the next look reads at once
	async function poll() {
		clearTimeout(pollTimer);
		if (active() && !document.hidden) await load();
		pollTimer = setTimeout(poll, POLL_MILLIS);
	}

	// ---------- Drawing ----------
	function render() {
		if (!active()) return;
		const s = store(), data = s.data, kind = view.kind;
		const banner = $("media-banner");
		const problems = { key: tr("页面的链接已经失效，请在游戏里重新打开高级编辑器。"), world: tr("游戏没有响应，可能不在世界里。"), offline: tr("连不上游戏，游戏可能已经关闭。") };
		banner.hidden = !problems[s.connection];
		banner.textContent = problems[s.connection] || "";

		const canUpload = Boolean(data && data.canUpload && data.maxFileBytes > 0);
		const upload = $("media-upload");
		upload.textContent = kind.uploadLabel();
		upload.disabled = !canUpload || s.connection !== "ok";
		upload.title = canUpload ? kind.uploadTitle() : kind.noUpload();
		$("media-file").accept = kind.accept;

		// How much of the server's room is used, amber from nine tenths, red when full
		const usage = data && data.maxTotalBytes > 0 ? data.totalBytes / data.maxTotalBytes : 0;
		$("media-usage").hidden = !data || !(data.maxTotalBytes > 0);
		$("media-usage").className = "usage" + (usage >= 1 ? " full" : usage >= 0.9 ? " high" : "");
		$("media-meter").style.width = Math.min(100, usage * 100).toFixed(1) + "%";
		$("media-usage-text").textContent = data ? tr("已用 {0} / {1}", formatSize(data.totalBytes), formatSize(data.maxTotalBytes)) : "";
		$("media-limits").textContent = data ? kind.limits(data) + " " + tr("也可以把文件拖到列表上。") : "";

		renderKinds();
		renderUploaders();
		renderMessages();
		renderEntries();
		renderDetail();
		setHtml($("media-status"), data ? kind.status(data) : tr("<span>正在连接游戏…</span>"));
	}

	function renderKinds() {
		const list = KINDS.length > 1 ? [{ id: "", name: () => tr("全部") }, ...KINDS] : KINDS;
		setHtml($("media-kinds"), list.map(kind => {
			const data = kind.id && stores[kind.id].data;
			const count = kind.id ? (data ? data.entries.length : "") : KINDS.reduce((sum, k) => sum + (stores[k.id].data ? stores[k.id].data.entries.length : 0), 0);
			const on = kind.id === view.kind.id;
			return `<button class="node${on ? " active" : ""}" role="listitem" data-kind="${kind.id}" aria-current="${on ? "page" : "false"}"><span class="label">${escapeHtml(kind.name())}</span><span class="count">${count}</span></button>`;
		}).join(""));
	}

	function renderUploaders() {
		const data = store().data, select = $("media-uploader");
		const names = data ? [...new Set(data.entries.map(entry => entry.uploaderName))].sort((a, b) => a.localeCompare(b)) : [];
		if (view.uploader && !names.includes(view.uploader)) view.uploader = "";
		// Only rebuilt when the names change, so an open list isn't closed under the mouse
		const signature = names.join("\n") + "\n" + I18N.language;
		if (select.dataset.signature === signature) return;
		select.dataset.signature = signature;
		select.innerHTML = `<option value="">${tr("所有上传者")}</option>` + names.map(name => `<option value="${escapeHtml(name)}">${escapeHtml(name)}</option>`).join("");
		select.value = view.uploader;
	}

	function renderMessages() {
		const data = store().data;
		const items = messages.map(item => ({ name: item.name, done: true, ok: item.ok, message: item.message }));
		if (data && data.requests) items.push(...data.requests.slice().reverse());
		$("media-requests-head").hidden = !items.length;
		setHtml($("media-requests"), items.slice(0, MAX_MESSAGES).map(item => {
			const state = !item.done ? "" : item.ok ? "ok" : "error";
			return `<li class="${state}">${item.name ? `<b>${escapeHtml(item.name)}</b>` : ""}<span>${escapeHtml(item.done ? item.message : tr("正在发送…"))}</span></li>`;
		}).join(""));
	}

	function renderEntries() {
		const data = store().data, entries = visibleEntries();
		const filtered = data && data.entries.length > 0 && !entries.length;
		$("media-empty").hidden = !data || entries.length > 0;
		$("media-empty").textContent = filtered ? tr("没有符合条件的文件。") : view.kind.empty();
		$("media-missing").setAttribute("aria-pressed", String(view.missingOnly));
		setHtml($("media-entries"), entries.map(entry => `<tr data-key="${escapeHtml(entry.key)}" tabindex="0" class="${entry.key === view.selected ? "sel" : ""}" aria-selected="${entry.key === view.selected}">${view.kind.row(entry)}</tr>`).join(""));
	}

	function renderDetail() {
		const data = store().data;
		const entry = data && data.entries.find(item => item.key === view.selected);
		setHtml($("media-detail"), entry ? view.kind.detail(entry) : `<h2>${escapeHtml(view.kind.name())}</h2><p class="hint">${tr("点列表里的一个文件，在这里试听、复制它的名字、改名或删除。")}</p>`);
		showProgress();
	}

	function showProgress() {
		const player = $("media-player"), bar = $("media-progress"), position = $("media-position");
		const on = playing && view.selected && store().data && store().data.entries.some(entry => entry.key === view.selected && entry.hash === playing);
		if (bar) bar.style.width = on && player.duration ? (player.currentTime / player.duration * 100).toFixed(1) + "%" : "0";
		if (position) position.textContent = formatClock(on ? player.currentTime : 0);
	}

	// ---------- Doing ----------
	async function uploadFiles(files) {
		const data = store().data, kind = view.kind;
		for (const file of files) {
			if (!kind.takes(file)) {
				note(file.name, kind.wrongType());
				continue;
			}
			if (data && file.size > data.maxFileBytes) {
				note(file.name, tr("文件不能超过 {0}", formatSize(data.maxFileBytes)));
				continue;
			}
			try {
				await kind.upload(file);
			} catch (e) {
				note(file.name, failed(e));
			}
			render();
		}
		load();
	}

	function readBase64(file) {
		return new Promise((resolve, reject) => {
			const reader = new FileReader();
			reader.onload = () => resolve(String(reader.result).replace(/^data:[^,]*,/, ""));
			reader.onerror = () => reject(new Error("read"));
			reader.readAsDataURL(file);
		});
	}

	function stop() {
		const player = $("media-player");
		player.pause();
		if (playingUrl) URL.revokeObjectURL(playingUrl);
		playingUrl = null;
		playing = null;
	}

	async function play(hash) {
		const again = playing === hash;
		stop();
		if (again) {
			render();
			return;
		}
		const player = $("media-player");
		try {
			const response = await fetch("api/audio-file?hash=" + hash, { headers: { "X-MTR-Editor-Key": key }, cache: "no-store" });
			if (!response.ok) throw new Error(String(response.status));
			playingUrl = URL.createObjectURL(await response.blob());
			player.src = playingUrl;
			playing = hash;
			await player.play();
		} catch (e) {
			playing = null;
			note("", e.message === "404" ? tr("本机还没有这个文件，正在下载") : tr("这个浏览器无法播放这个文件"));
		}
		render();
	}

	async function act(action, entryKey) {
		const entry = store().data && store().data.entries.find(item => item.key === entryKey);
		if (!entry) return;
		try {
			await action(entry);
		} catch (e) {
			note(entry.name, failed(e));
		}
		load();
	}

	async function copy(text) {
		try {
			await navigator.clipboard.writeText(text);
			note(text, tr("已复制"), true);
		} catch (e) {
			prompt(tr("复制这个名字："), text);
		}
		render();
	}

	function select(entryKey) {
		view.selected = entryKey;
		if (matchMedia("(max-width: 860px)").matches) requestAnimationFrame(() => $("media-detail").scrollIntoView({ block: "nearest" }));
		render();
	}

	// The kinds fold away like the timetable's tree: a column given back when wide, a drawer over the list when tall
	const portrait = matchMedia("(orientation: portrait) and (max-width: 1100px)");
	function setSideOpen(open) {
		sideOpen = open;
		document.body.classList.toggle("media-side-closed", !open);
		if (active()) $("btn-tree").setAttribute("aria-expanded", String(open));
	}

	// ---------- Events ----------
	$("media-upload").addEventListener("click", () => $("media-file").click());
	$("media-file").addEventListener("change", event => {
		const files = [...event.target.files];
		event.target.value = "";
		uploadFiles(files);
	});
	$("media-kinds").addEventListener("click", event => {
		const button = event.target.closest("[data-kind]");
		if (button && button.dataset.kind) MTREditor.open("media", button.dataset.kind);
	});
	$("media-search").addEventListener("input", event => {
		view.search = event.target.value;
		renderEntries();
	});
	$("media-uploader").addEventListener("change", event => {
		view.uploader = event.target.value;
		renderEntries();
	});
	$("media-missing").addEventListener("click", () => {
		view.missingOnly = !view.missingOnly;
		renderEntries();
	});
	$("media-entries").addEventListener("click", event => {
		const button = event.target.closest("button");
		if (button && button.dataset.play) {
			play(button.dataset.play);
			return;
		}
		const row = event.target.closest("tr[data-key]");
		if (row) select(row.dataset.key);
	});
	$("media-entries").addEventListener("keydown", event => {
		const row = event.target.closest("tr[data-key]");
		if (!row) return;
		if (event.key === "Enter" || event.key === " ") {
			event.preventDefault();
			select(row.dataset.key);
		} else if (event.key === "ArrowDown" || event.key === "ArrowUp") {
			event.preventDefault();
			const next = event.key === "ArrowDown" ? row.nextElementSibling : row.previousElementSibling;
			if (next) {
				select(next.dataset.key);
				document.querySelector(`#media-entries tr[data-key="${CSS.escape(next.dataset.key)}"]`)?.focus();
			}
		}
	});
	$("media-detail").addEventListener("click", event => {
		const button = event.target.closest("button");
		if (!button) return;
		if (button.dataset.play) play(button.dataset.play);
		if (button.dataset.copy) copy(button.dataset.copy);
		if (button.dataset.delete) act(entry => view.kind.remove(entry), button.dataset.delete);
		if (button.dataset.soundId) act(entry => view.kind.changeSoundId(entry), button.dataset.soundId);
	});
	$("media-player").addEventListener("timeupdate", showProgress);
	$("media-player").addEventListener("ended", () => {
		stop();
		render();
	});

	// Files dropped on the list are uploaded as the kind shown; the frame says beforehand whether they would be taken
	const list = $("media-list"), drop = $("media-drop");
	const hasFiles = event => event.dataTransfer && [...event.dataTransfer.types].includes("Files");
	list.addEventListener("dragover", event => {
		if (!hasFiles(event)) return;
		event.preventDefault();
		const data = store().data, kind = view.kind;
		const canUpload = Boolean(data && data.canUpload && data.maxFileBytes > 0);
		const items = [...event.dataTransfer.items].filter(item => item.kind === "file");
		// The browser tells the type, not the name, while dragging; an unknown type is let through and checked on the drop
		const wrong = items.some(item => item.type && !kind.takes({ name: "", type: item.type }));
		const refused = !canUpload || wrong;
		event.dataTransfer.dropEffect = refused ? "none" : "copy";
		drop.hidden = false;
		drop.classList.toggle("refused", refused);
		drop.textContent = !canUpload ? kind.noUpload() : wrong ? kind.wrongType() : tr("松开上传到{0}", kind.name());
	});
	list.addEventListener("dragleave", event => {
		if (!list.contains(event.relatedTarget)) drop.hidden = true;
	});
	list.addEventListener("drop", event => {
		if (!hasFiles(event)) return;
		event.preventDefault();
		drop.hidden = true;
		const data = store().data;
		if (!(data && data.canUpload && data.maxFileBytes > 0)) return;
		uploadFiles([...event.dataTransfer.files]);
	});

	$("btn-tree").addEventListener("click", () => { if (active()) setSideOpen(!sideOpen); });
	$("media-scrim").addEventListener("click", () => setSideOpen(false));
	portrait.addEventListener("change", () => setSideOpen(!portrait.matches));

	document.addEventListener("mtr:workspace", event => {
		if (event.detail.ws !== "media") {
			// Nothing plays on behind the timetable
			if (playing) stop();
			$("btn-tree").setAttribute("aria-expanded", String(!document.body.classList.contains("tree-closed")));
			return;
		}
		const kind = kindOf(event.detail.part);
		if (kind !== view.kind) {
			view.kind = kind;
			view.selected = null;
		}
		$("btn-tree").setAttribute("aria-expanded", String(sideOpen));
		render();
		poll();
	});

	view.kind = kindOf(MTREditor.part);
	// A reload may bring back what was typed in the search
	view.search = $("media-search").value;
	setSideOpen(!portrait.matches);
	render();
	poll();
})();
