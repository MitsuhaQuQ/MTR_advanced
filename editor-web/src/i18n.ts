/**
 * The old editor's i18n.js, loaded before this page's script (index.html), is the one dictionary for both pages: text is
 * written in Simplified Chinese and looked up there. New text of this page goes into i18n.js too, with its English.
 * In tests, where i18n.js isn't loaded, text stays Chinese.
 */
declare global {
  // eslint-disable-next-line no-var
  var I18N: {
    readonly language: string
    translate(text: string, args: unknown[]): string
    setLanguage(code: string): boolean
    applyStatic(root: ParentNode): void
  } | undefined
}

const format = (text: string, args: unknown[]) => text.replace(/\{\{|\}\}|\{(\d+)\}/g, (match, index) => (match === "{{" ? "{" : match === "}}" ? "}" : String(args[+index])))
const translate = (text: string, args: unknown[]) => (globalThis.I18N ? globalThis.I18N.translate(text, args) : format(text, args))

export const tr = (text: string, ...args: unknown[]) => translate(text, args)
/** A word that needs another translation in one place, looked up as "context\u0004text" (trc in i18n.js). */
export const trc = (context: string, text: string, ...args: unknown[]) =>
  translate(translate(context + "\u0004" + text, []) === context + "\u0004" + text ? text : context + "\u0004" + text, args)
export const setLanguage = (code: string) => Boolean(globalThis.I18N && globalThis.I18N.setLanguage(code))
