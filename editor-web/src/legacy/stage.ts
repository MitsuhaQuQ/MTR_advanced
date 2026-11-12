/**
 * The old page's elements the old code still uses (markup.ts), made once on a hidden stage before the old code runs, so
 * every element it looks up by ID is there and its event handlers find them. The new page's layout borrows the ones it
 * shows (LegacySlot) and puts them back when it doesn't.
 *
 * The tooltip lives in a box that is always shown. Every box holding old elements has the class "legacy", which the old
 * page's styles (public/legacy.css) are limited to.
 */
import { LEGACY_MARKUP } from "./markup"
import { applyStatic } from "../i18n"

export const stage = document.createElement("div")
stage.className = "legacy"
stage.hidden = true
stage.innerHTML = LEGACY_MARKUP
document.body.appendChild(stage)

export const overlays = document.createElement("div")
overlays.className = "legacy legacy-overlays"
document.body.appendChild(overlays)
for (const id of ["tooltip"]) {
  const element = document.getElementById(id)
  if (element) overlays.appendChild(element)
}
applyStatic(stage)
applyStatic(overlays)

/** An old element by ID; it exists for every ID of the old page. */
export const legacyElement = (id: string) => document.getElementById(id)

/** Puts an element borrowed by the layout back on the stage. */
export const returnToStage = (element: HTMLElement) => { stage.appendChild(element) }
