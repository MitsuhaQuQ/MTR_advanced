/** Small parts the page's panels share. */
import { useEffect, useRef, useState, type ReactNode } from "react"
import { tr } from "../i18n"

export const Pill = ({ tone, children, title }: { tone: "warn" | "caution" | "sel" | "accent" | "plain"; children: ReactNode; title?: string }) => (
  <span title={title} className={"inline-flex h-5 shrink-0 items-center whitespace-nowrap rounded-pill px-2 text-[12px] font-medium " + {
    warn: "tint-warn text-warn", caution: "tint-caution text-caution", sel: "tint-sel text-sel", accent: "tint-accent text-brand", plain: "bg-panel2 text-dim",
  }[tone]}>{children}</span>
)

/**
 * A panel sliding in from the right, over the page, for lists to read and act on (the draft's changes, sync state).
 * Esc or the backdrop closes it; focus moves into it and back.
 */
export function SidePanel({ open, onClose, title, hint, children, footer, width = 460 }: {
  open: boolean; onClose: () => void; title: string; hint?: ReactNode; children: ReactNode; footer?: ReactNode; width?: number
}) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const before = document.activeElement as HTMLElement | null
    ref.current?.focus()
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") { e.stopPropagation(); onClose() } }
    addEventListener("keydown", onKey, true)
    return () => { removeEventListener("keydown", onKey, true); before?.focus?.() }
  }, [open, onClose])
  if (!open) return null
  return (
    <div className="fixed inset-0 z-40 flex justify-end">
      <div className="absolute inset-0 bg-ink/20" style={{ background: "color-mix(in srgb, var(--ink) 18%, transparent)" }} onClick={onClose} />
      <div ref={ref} tabIndex={-1} role="dialog" aria-modal="true" aria-label={title}
        className="relative flex h-full w-full flex-col border-l border-grid bg-panel outline-none" style={{ maxWidth: width, boxShadow: "var(--shadow)" }}>
        <header className="flex items-start gap-2 border-b border-grid px-4 py-3">
          <div className="min-w-0 flex-1">
            <h2 className="m-0 text-[16px] font-bold">{title}</h2>
            {hint && <div className="mt-0.5 text-[12px] text-dim">{hint}</div>}
          </div>
          <button className="btn btn-ghost" onClick={onClose} aria-label={tr("关闭")}>✕</button>
        </header>
        <div className="scroll-thin min-h-0 flex-1 overflow-y-auto px-4 py-3">{children}</div>
        {footer && <footer className="flex flex-wrap items-center gap-2 border-t border-grid px-4 py-3">{footer}</footer>}
      </div>
    </div>
  )
}

/**
 * A button for something that can't be undone from the page: the first click asks, in place, the second does it.
 * (The old page used confirm(), which some browsers block in pages opened like this one.)
 */
export function ConfirmButton({ children, confirm, onConfirm, className = "btn", disabled }: {
  children: ReactNode; confirm: string; onConfirm: () => void; className?: string; disabled?: boolean
}) {
  const [asking, setAsking] = useState(false)
  useEffect(() => {
    if (!asking) return
    const timer = setTimeout(() => setAsking(false), 6000)
    return () => clearTimeout(timer)
  }, [asking])
  if (!asking) return <button className={className} disabled={disabled} onClick={() => setAsking(true)}>{children}</button>
  return (
    <span className="inline-flex flex-wrap items-center gap-2 rounded-md border border-warn px-2 py-1 text-[12px]" role="alert">
      <span className="text-warn">{confirm}</span>
      <button className="btn btn-primary" style={{ background: "var(--warn)", borderColor: "var(--warn)", color: "#fff" }} onClick={() => { setAsking(false); onConfirm() }}>{tr("确定")}</button>
      <button className="btn" onClick={() => setAsking(false)}>{tr("取消")}</button>
    </span>
  )
}

export const Kbd = ({ children }: { children: ReactNode }) => (
  <kbd className="num inline-flex h-[18px] min-w-[18px] items-center justify-center rounded-sm border border-gridstrong bg-panel2 px-1 text-[11px] text-dim">{children}</kbd>
)
