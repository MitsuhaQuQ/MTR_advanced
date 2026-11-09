/**
 * The old editor's i18n.js, loaded before this page's script (index.html), is the one dictionary for both pages: text is
 * written in Simplified Chinese and looked up there. New text of this page goes into i18n.js too, with its English.
 *
 * i18n.js declares `const I18N` in a plain script: a global binding every script sees by name, but not a property of
 * globalThis, so it is read by name here. In tests, where i18n.js isn't loaded, text stays Chinese.
 */
type Dictionary = { readonly language: string; translate(text: string, args: unknown[]): string; setLanguage(code: string): boolean; applyStatic(root: ParentNode): void }
declare const I18N: Dictionary | undefined

const dictionary = (): Dictionary | undefined => (typeof I18N === "undefined" ? undefined : I18N)
const format = (text: string, args: unknown[]) => text.replace(/\{\{|\}\}|\{(\d+)\}/g, (match, index) => (match === "{{" ? "{" : match === "}}" ? "}" : String(args[+index])))
const translate = (text: string, args: unknown[]) => { const d = dictionary(); return d ? d.translate(text, args) : format(text, args) }

export const tr = (text: string, ...args: unknown[]) => translate(text, args)
/** A word that needs another translation in one place, looked up as "context\u0004text" (trc in i18n.js). */
export const trc = (context: string, text: string, ...args: unknown[]) =>
  translate(translate(context + "\u0004" + text, []) === context + "\u0004" + text ? text : context + "\u0004" + text, args)
export const setLanguage = (code: string) => Boolean(dictionary()?.setLanguage(code))
export const applyStatic = (root: ParentNode) => dictionary()?.applyStatic(root)
