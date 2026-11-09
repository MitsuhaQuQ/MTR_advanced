/** Colours are the design system's tokens (MTM 运行图编辑器), defined as CSS variables in src/styles.css. */
const v = name => `var(--${name})`

/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        bg: v("bg"), panel: v("panel"), panel2: v("panel-2"), ink: v("ink"), dim: v("muted"),
        grid: v("grid"), gridstrong: v("grid-strong"), ctl: v("control-line"),
        brand: v("accent"), brandink: v("accent-ink"), sel: v("sel"), warn: v("warn"),
        caution: v("caution"), duty: v("duty"), now: v("now"), connecting: v("connecting"),
        boundary: v("boundary"), late: v("late"),
      },
      borderColor: { DEFAULT: v("grid") },
      borderRadius: { sm: "4px", md: "6px", lg: "8px", pill: "999px" },
    },
  },
  plugins: [],
}
