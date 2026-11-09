/** Borrowing the old page's elements into the new layout, and drawing the old code's menus. */
import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties } from "react"
import { legacy, menuHost, type MenuItem } from "./engine"
import { legacyElement, returnToStage } from "./stage"
import { tr } from "../i18n"

/**
 * A box in the layout showing an old element (by its old ID) while mounted, with the old styles around it. The element
 * keeps its event handlers; the old code goes on drawing into it.
 */
export function LegacySlot({ id, className = "", style, onResize }: { id: string; className?: string; style?: CSSProperties; onResize?: () => void }) {
  const box = useRef<HTMLDivElement>(null)
  useLayoutEffect(() => {
    const element = legacyElement(id)
    if (!element || !box.current) return
    element.hidden = false
    box.current.appendChild(element)
    return () => returnToStage(element)
  }, [id])
  useEffect(() => {
    if (!onResize || !box.current) return
    const observer = new ResizeObserver(() => onResize())
    observer.observe(box.current)
    return () => observer.disconnect()
  }, [onResize])
  return <div ref={box} className={"legacy legacy-slot " + className} style={style} />
}

/** The old code's menus: [label, action] items, an item with a list for its action opening a second level. */
export function LegacyMenu() {
  const [menu, setMenu] = useState<{ x: number; y: number; items: MenuItem[] } | null>(null)
  const [sub, setSub] = useState<{ index: number; x: number; y: number; items: MenuItem[] } | null>(null)
  const box = useRef<HTMLDivElement>(null)
  useEffect(() => {
    menuHost.current = {
      open: (x, y, items) => { setSub(null); setMenu({ x, y, items: items.length ? items : [[tr("这里没有可用的操作"), null]] }) },
      close: () => { setMenu(null); setSub(null) },
    }
    return () => { menuHost.current = null }
  }, [])
  useEffect(() => {
    if (!menu) return
    const close = (e: Event) => { if (!(e.target instanceof Node) || !box.current?.parentElement?.contains(e.target)) { setMenu(null); setSub(null) } }
    const key = (e: KeyboardEvent) => { if (e.key === "Escape") { setMenu(null); setSub(null) } }
    addEventListener("pointerdown", close, true)
    addEventListener("keydown", key, true)
    addEventListener("blur", close)
    return () => { removeEventListener("pointerdown", close, true); removeEventListener("keydown", key, true); removeEventListener("blur", close) }
  }, [menu])
  useLayoutEffect(() => {
    // Kept inside the window
    const element = box.current
    if (!element || !menu) return
    const rect = element.getBoundingClientRect()
    if (rect.right > innerWidth - 8) element.style.left = Math.max(8, innerWidth - rect.width - 8) + "px"
    if (rect.bottom > innerHeight - 8) element.style.top = Math.max(8, innerHeight - rect.height - 8) + "px"
  }, [menu])
  if (!menu) return null
  const list = (items: MenuItem[], level: number) => items.map(([label, action], i) => {
    const isSub = Array.isArray(action)
    return (
      <button key={i} type="button" role="menuitem" disabled={!action}
        className="flex w-full items-center justify-between gap-4 rounded-sm px-2.5 py-1.5 text-left text-[13px] enabled:hover:bg-panel2 disabled:text-dim"
        onMouseEnter={e => {
          if (level > 0) return
          if (isSub) { const r = e.currentTarget.getBoundingClientRect(); setSub({ index: i, x: r.right + 2, y: r.top - 4, items: action as MenuItem[] }) } else setSub(null)
        }}
        onClick={e => {
          if (isSub) { const r = e.currentTarget.getBoundingClientRect(); setSub({ index: i, x: r.right + 2, y: r.top - 4, items: action as MenuItem[] }); return }
          setMenu(null); setSub(null)
          ;(action as () => void)()
        }}>
        <span>{label}</span>{isSub && <span className="text-dim">▸</span>}
      </button>
    )
  })
  const panel = "fixed z-50 min-w-[180px] max-w-[420px] rounded-lg border border-grid bg-panel p-1"
  return (
    <div>
      <div ref={box} role="menu" className={panel} style={{ left: menu.x, top: menu.y, boxShadow: "var(--shadow)" }}>{list(menu.items, 0)}</div>
      {sub && <div role="menu" className={panel} style={{ left: Math.min(sub.x, innerWidth - 200), top: sub.y, boxShadow: "var(--shadow)" }}>{list(sub.items, 1)}</div>}
    </div>
  )
}

/** Runs one of the old page's functions, by name. */
export const run = (name: string, ...args: unknown[]) => legacy.fn[name](...args)
