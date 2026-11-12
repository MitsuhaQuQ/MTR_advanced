/**
 * The old page's elements the old code (oldEditor.js) still looks up by ID, without the rest of its index.html (deleted
 * with the old page, 2026-11-13). The new layout borrows the ones it shows (LegacySlot): the tree and the inspector it
 * draws into, the running diagram's box it measures by, the real network map with its tool bar, the simulation's status;
 * the tooltip lives in a box that is always shown (stage.ts). Texts with data-i18n are translated by applyStatic.
 */
export const LEGACY_MARKUP = `
<nav class="tree" id="tree" aria-label="线路和车厂" data-i18n-aria-label="线路和车厂"></nav>
<aside class="inspector" id="inspector"></aside>
<div class="canvas-wrap" id="canvas-wrap"></div>
<span class="sim-status" id="sim-status"></span>
<section class="view network-view" id="view-network" hidden>
	<div class="net-tools">
		<select id="net-mode" aria-label="运输方式" data-i18n-aria-label="运输方式"></select>
		<select id="net-layer" aria-label="图层" data-i18n-aria-label="图层" title="物理线路：轨道、寻路边界、留置位；区段归属：每段轨道归哪些线路，联络线也在这一层" data-i18n-title="物理线路：轨道、寻路边界、留置位；区段归属：每段轨道归哪些线路，联络线也在这一层"></select>
		<button id="net-fit" data-i18n="全图">全图</button>
		<button id="net-zoom-out" title="缩小" data-i18n-title="缩小">−</button>
		<button id="net-zoom-in" title="放大" data-i18n-title="放大">＋</button>
		<button id="net-refresh" title="向游戏要最新的轨道" data-i18n-title="向游戏要最新的轨道">↻</button>
		<button id="net-console-toggle" title="看各信号区段的占用和列车，扣停、折返、清客、回库" data-i18n-title="看各信号区段的占用和列车，扣停、折返、清客、回库" data-i18n="调度台">调度台</button>
		<span class="hint" id="net-info" title="放大后按实际走向画，缩小时画成示意图（只有横、竖和 45°）" data-i18n-title="放大后按实际走向画，缩小时画成示意图（只有横、竖和 45°）"></span>
		<span class="hint net-legend" id="net-legend" data-i18n="紫：寻路边界　橙虚线：联络线独占　灰虚线：已放行　粗线：站台　黄：存车线　棕：留置位">紫：寻路边界　橙虚线：联络线独占　灰虚线：已放行　粗线：站台　黄：存车线　棕：留置位</span>
	</div>
	<div class="net-wrap" id="net-wrap"><canvas id="net-canvas" aria-label="全网配线" data-i18n-aria-label="全网配线"></canvas><aside class="net-waypick" id="net-waypick" hidden></aside></div>
</section>
<div class="tooltip" id="tooltip" hidden></div>
`
