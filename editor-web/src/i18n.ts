/**
 * The old editor's i18n.js, loaded before this page's script (index.html), is the one dictionary for both pages: text is
 * written in Simplified Chinese and looked up there. New text of this page goes into i18n.js too, with its English.
 */
declare global {
  const I18N: {
    readonly language: string
    translate(text: string, args: unknown[]): string
    setLanguage(code: string): boolean
    applyStatic(root: ParentNode): void
  }
}

export const tr = (text: string, ...args: unknown[]) => (typeof I18N === "undefined" ? text : I18N.translate(text, args))
export const setLanguage = (code: string) => typeof I18N !== "undefined" && I18N.setLanguage(code)
