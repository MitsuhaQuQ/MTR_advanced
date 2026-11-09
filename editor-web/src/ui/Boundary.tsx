/** Keeps an error in one layer's work area there: the rest of the page (top bar, draft, apply) goes on working. */
import { Component, type ReactNode } from "react"
import { tr } from "../i18n"

export class Boundary extends Component<{ children: ReactNode; resetKey: string }, { error: Error | null; key: string }> {
  state = { error: null as Error | null, key: this.props.resetKey }
  static getDerivedStateFromError(error: Error) { return { error } }
  static getDerivedStateFromProps(props: { resetKey: string }, state: { key: string }) {
    // Another layer opened: try again
    return props.resetKey !== state.key ? { error: null, key: props.resetKey } : null
  }
  componentDidCatch(error: Error) { console.error(error) }
  render() {
    if (!this.state.error) return this.props.children
    return (
      <div role="alert" className="m-4 flex max-w-2xl flex-col gap-2 rounded-lg border border-warn bg-panel p-4 text-[13px]">
        <b className="text-warn">{tr("这一层出错了，草稿不受影响。")}</b>
        <code className="num whitespace-pre-wrap break-all text-[12px] text-dim">{String(this.state.error.message || this.state.error)}</code>
        <div><button className="btn" onClick={() => location.reload()}>{tr("重新载入页面")}</button></div>
      </div>
    )
  }
}
