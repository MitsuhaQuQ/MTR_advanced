"use strict";

/*
 * The announcement audio page of the advanced editor: the files the server keeps, adding Ogg Vorbis files, playing one
 * back and deleting one. Announcers name a file as the uploader's name in lowercase, a colon and its file name, which is unique for each uploader. The game sends files to the server and answers later, so the list is read again every few
 * seconds. Files only play here once this computer has them; the game asks the server for the missing ones by itself.
 */
(() => {
	const POLL_MILLIS = 2000;

	const $ = id => document.getElementById(id);
	const escapeHtml = text => String(text).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&#39;" })[c]);

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

	let data = null, connection = "connecting", playing = null, playingUrl = null;
	// Problems found before anything is sent, which the game never hears about
	const localErrors = [];

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
		return seconds >= 60 ? Math.floor(seconds / 60) + ":" + String(Math.round(seconds % 60)).padStart(2, "0") : seconds.toFixed(1) + " 秒";
	};
	const formatTime = millis => {
		const date = new Date(millis);
		const pad = value => String(value).padStart(2, "0");
		return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
	};

	async function poll() {
		try {
			data = await fetchJson("api/audio");
			connection = "ok";
		} catch (e) {
			connection = e.message === "401" ? "key" : e.message === "503" ? "world" : "offline";
		}
		render();
		setTimeout(poll, POLL_MILLIS);
	}

	function render() {
		const banner = $("banner");
		const messages = { key: "页面的链接已经失效，请在游戏里重新打开高级编辑器。", world: "游戏没有响应，可能不在世界里。", offline: "连不上游戏，游戏可能已经关闭。" };
		banner.hidden = connection === "ok" || connection === "connecting";
		banner.className = "banner warn";
		banner.textContent = messages[connection] || "";

		const canUpload = Boolean(data && data.canUpload);
		const button = $("btn-upload");
		button.disabled = !canUpload || connection !== "ok";
		button.title = canUpload ? "选择 Ogg Vorbis 文件（.ogg）" : "没有上传音频的权限";

		if (data) {
			$("usage").textContent = `已用 ${formatSize(data.totalBytes)} / ${formatSize(data.maxTotalBytes)}`;
			$("limits").textContent = data.maxFileBytes > 0
				? `只支持 Ogg Vorbis（.ogg），单个文件不超过 ${formatSize(data.maxFileBytes)}、${data.maxSeconds} 秒。单声道约每秒 6 KB。` + (canUpload ? "" : "你没有上传音频的权限。")
				: "服务器没有开放上传音频。";
		}
		renderRequests();
		renderEntries();

		const status = $("status");
		status.innerHTML = data
			? `<span>音频 <b>${data.entries.length}</b> 个</span><span>本机已有 <b>${data.entries.filter(entry => entry.cached).length}</b> 个</span>` +
				(data.entries.some(entry => entry.downloading) ? "<span>正在下载…</span>" : "")
			: "<span>正在连接游戏…</span>";
	}

	function renderRequests() {
		const list = $("requests");
		const items = localErrors.map(error => ({ name: error.name, done: true, ok: false, message: error.message }));
		if (data) items.push(...data.requests.slice().reverse());
		list.innerHTML = items.slice(0, 8).map(item => {
			const state = !item.done ? "" : item.ok ? "ok" : "error";
			const message = item.done ? item.message : "正在发送…";
			return `<li class="${state}"><b>${escapeHtml(item.name || "")}</b>　${escapeHtml(message)}</li>`;
		}).join("");
	}

	function renderEntries() {
		const entries = data ? data.entries.slice().sort((a, b) => b.uploadedAt - a.uploadedAt) : [];
		$("empty").hidden = !data || entries.length > 0;
		$("entries").innerHTML = entries.map(entry => {
			const local = entry.cached ? "有" : entry.downloading ? "下载中" : "没有";
			const isPlaying = playing === entry.hash;
			return `<tr>
				<td class="name">${escapeHtml(entry.name)}<div class="hint" title="填进报站器的“音频文件”栏">${escapeHtml(entry.reference)}</div></td>
				<td class="num">${formatDuration(entry.durationMillis)}</td>
				<td class="num">${formatSize(entry.size)}</td>
				<td>${escapeHtml(entry.uploaderName)}</td>
				<td class="num">${formatTime(entry.uploadedAt)}</td>
				<td>${local}</td>
				<td class="actions">
					<button data-play="${entry.hash}" ${entry.cached ? "" : "disabled title=\"本机还没有这个文件\""}>${isPlaying ? "停止" : "试听"}</button>
					${entry.canDelete ? `<button class="danger" data-delete="${escapeHtml(entry.key)}">删除</button>` : ""}
				</td>
			</tr>`;
		}).join("");
	}

	async function uploadFiles(files) {
		for (const file of files) {
			if (data && file.size > data.maxFileBytes) {
				localErrors.unshift({ name: file.name, message: `文件不能超过 ${formatSize(data.maxFileBytes)}` });
				continue;
			}
			try {
				const base64 = await readBase64(file);
				const result = await sendJson("api/audio", { action: "upload", name: file.name, data: base64 });
				if (!result.ok) localErrors.unshift({ name: file.name, message: result.message });
			} catch (e) {
				localErrors.unshift({ name: file.name, message: e.message === "413" ? "文件太大" : "发送失败，游戏可能已经关闭" });
			}
			localErrors.splice(8);
			render();
		}
		poll.now();
	}

	function readBase64(file) {
		return new Promise((resolve, reject) => {
			const reader = new FileReader();
			reader.onload = () => resolve(String(reader.result).replace(/^data:[^,]*,/, ""));
			reader.onerror = () => reject(new Error("read"));
			reader.readAsDataURL(file);
		});
	}

	async function play(hash) {
		const player = $("player");
		player.pause();
		if (playingUrl) URL.revokeObjectURL(playingUrl);
		playingUrl = null;
		if (playing === hash) {
			playing = null;
			render();
			return;
		}
		try {
			const response = await fetch("api/audio-file?hash=" + hash, { headers: { "X-MTR-Editor-Key": key }, cache: "no-store" });
			if (!response.ok) throw new Error(String(response.status));
			playingUrl = URL.createObjectURL(await response.blob());
			player.src = playingUrl;
			playing = hash;
			await player.play();
		} catch (e) {
			playing = null;
			localErrors.unshift({ name: "", message: e.message === "404" ? "本机还没有这个文件，正在下载" : "这个浏览器无法播放这个文件" });
		}
		render();
	}

	// One name goes; the same recording under another name stays
	async function remove(nameKey) {
		const entry = data && data.entries.find(item => item.key === nameKey);
		if (!entry || !confirm(`从服务器删除“${entry.reference}”？用这个名字的报站器都会不出声，不能撤销。`)) return;
		try {
			const result = await sendJson("api/audio", { action: "delete", key: nameKey });
			if (!result.ok) localErrors.unshift({ name: entry.name, message: result.message });
		} catch (e) {
			localErrors.unshift({ name: entry.name, message: "发送失败，游戏可能已经关闭" });
		}
		poll.now();
	}

	poll.now = async () => {
		try {
			data = await fetchJson("api/audio");
			connection = "ok";
		} catch (e) {
			connection = e.message === "401" ? "key" : "offline";
		}
		render();
	};

	$("btn-upload").addEventListener("click", () => $("file").click());
	$("file").addEventListener("change", event => {
		const files = [...event.target.files];
		event.target.value = "";
		uploadFiles(files);
	});
	$("entries").addEventListener("click", event => {
		const button = event.target.closest("button");
		if (!button) return;
		if (button.dataset.play) play(button.dataset.play);
		if (button.dataset.delete) remove(button.dataset.delete);
	});
	$("player").addEventListener("ended", () => {
		playing = null;
		render();
	});

	render();
	poll();
})();
