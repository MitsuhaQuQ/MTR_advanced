/** Small line icons drawn here rather than taken from an icon package, to keep the page's script small. */
const paths = {
  track: "M4 20 L10 4 M14 4 L20 20 M6 15 H18 M8 10 H16",
  owners: "M4 7 H20 M4 12 H14 M4 17 H10 M17 14 l3 3 -3 3",
  lines: "M5 6 a2 2 0 1 0 0.01 0 M19 18 a2 2 0 1 0 0.01 0 M7 6 H14 a4 4 0 0 1 0 8 H10 a4 4 0 0 0 0 8 H17",
  timetable: "M4 6 H20 V20 H4 Z M4 10 H20 M9 3 V7 M15 3 V7 M8 14 L11 17 L16 12",
  depots: "M3 20 V9 L12 4 L21 9 V20 M7 20 V13 H17 V20 M7 16 H17",
  dispatch: "M12 12 m-2 0 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0 M7.5 7.5 a6.5 6.5 0 0 0 0 9 M16.5 7.5 a6.5 6.5 0 0 1 0 9 M4.5 4.5 a10.5 10.5 0 0 0 0 15 M19.5 4.5 a10.5 10.5 0 0 1 0 15",
  media: "M9 18 V5 L20 3 V16 M9 18 a3 3 0 1 1 -6 0 a3 3 0 1 1 6 0 M20 16 a3 3 0 1 1 -6 0 a3 3 0 1 1 6 0",
  back: "M15 5 L8 12 L15 19",
} as const

export type IconName = keyof typeof paths

export function Icon({ name, size = 18 }: { name: IconName; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d={paths[name]} />
    </svg>
  )
}
