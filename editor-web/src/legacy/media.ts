/**
 * The old media library (media.generated.js from media.js) in the new page. It works as it did in the old page's shell:
 * it reads MTREditor.ws and .part, and starts or stops asking the game when an mtr:workspace event says the workspace
 * changed. The new page plays that shell's part.
 */
import "./stage"
import { key } from "../api/client"
import { createMedia } from "./media.generated.js"

const workspace = { ws: "timetable", part: "audio" }
const announce = () => document.dispatchEvent(new CustomEvent("mtr:workspace", { detail: { ws: workspace.ws, part: workspace.part } }))

const MTREditor = {
  key,
  get ws() { return workspace.ws },
  get part() { return workspace.part },
  open(ws: string, part?: string) {
    workspace.ws = ws
    workspace.part = part || workspace.part
    announce()
  },
  inTimetable: () => workspace.ws === "timetable",
}

let created = false
/** Shows the media library (made the first time), or leaves it. */
export function setMediaShown(shown: boolean) {
  workspace.ws = shown ? "media" : "timetable"
  if (shown && !created) {
    created = true
    createMedia({
      MTREditor,
      // Paths written for the page at the server's root ("api/audio") go there from this page too
      fetch: (input: string, init?: RequestInit) => globalThis.fetch(new URL("/" + String(input).replace(/^\/+/, ""), location.origin).toString(), init),
    })
  }
  announce()
}
