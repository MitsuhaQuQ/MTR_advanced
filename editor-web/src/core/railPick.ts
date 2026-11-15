/**
 * Choosing many rails of the real network map at once, for the ownership layer's brushes (0.83.13): the rails along the
 * shortest way between two rails, the rails along one until a turnout, the rails in a box, and the rails under a stroke of
 * the mouse. Pure: the map's rails come in as the old map has them, each `rails[i]` starting with its two nodes' indices
 * and `points[i]` its points x, z, x, z, ... from the first node to the second (the same points it is drawn with).
 */

export type RailEnds = ArrayLike<ArrayLike<number>>
export type RailPoints = ArrayLike<ArrayLike<number>>

type Box = { minX: number; minZ: number; maxX: number; maxZ: number }

const boxCache = new WeakMap<object, Box[]>()
const adjacentCache = new WeakMap<object, Map<number, number[]>>()

/** Each rail's bounding box, kept with the points array (the map's arrays are replaced, not changed) */
function boxes(points: RailPoints): Box[] {
  let value = boxCache.get(points as object)
  if (value && value.length === points.length) return value
  value = []
  for (let i = 0; i < points.length; i++) {
    const p = points[i]
    let minX = Infinity, minZ = Infinity, maxX = -Infinity, maxZ = -Infinity
    for (let k = 0; k + 1 < p.length; k += 2) {
      minX = Math.min(minX, p[k]); maxX = Math.max(maxX, p[k])
      minZ = Math.min(minZ, p[k + 1]); maxZ = Math.max(maxZ, p[k + 1])
    }
    value.push({ minX, minZ, maxX, maxZ })
  }
  boxCache.set(points as object, value)
  return value
}

/** The rails at each node */
export function railsAtNodes(rails: RailEnds): Map<number, number[]> {
  let value = adjacentCache.get(rails as object)
  if (value) return value
  value = new Map()
  for (let i = 0; i < rails.length; i++) {
    for (const node of [rails[i][0], rails[i][1]]) {
      const here = value.get(node)
      if (here) { if (!here.includes(i)) here.push(i) } else value.set(node, [i])
    }
  }
  adjacentCache.set(rails as object, value)
  return value
}

const otherEnd = (rails: RailEnds, index: number, node: number) => rails[index][0] === node ? rails[index][1] : rails[index][0]

/** A rail's length along its points */
export function railLength(points: ArrayLike<number>) {
  let length = 0
  for (let k = 0; k + 3 < points.length; k += 2) length += Math.hypot(points[k + 2] - points[k], points[k + 3] - points[k + 1])
  return length
}

/** Which way a rail leaves a node, as a unit vector: towards its first point that isn't on the node */
function leaving(rails: RailEnds, points: RailPoints, index: number, node: number): [number, number] {
  const p = points[index], forward = rails[index][0] === node, count = p.length / 2
  const at = (i: number) => forward ? i : count - 1 - i
  const x0 = p[at(0) * 2], z0 = p[at(0) * 2 + 1]
  for (let i = 1; i < count; i++) {
    const dx = p[at(i) * 2] - x0, dz = p[at(i) * 2 + 1] - z0, length = Math.hypot(dx, dz)
    if (length > 1e-6) return [dx / length, dz / length]
  }
  return [0, 0]
}

/**
 * The rails along the shortest way from one rail to another, both included, in order; empty when there is none. A train
 * can't turn back at a turnout, so the way goes on through each node the way it came (turning less than a right angle);
 * only when there is no such way at all is any way through the nodes taken.
 */
export function railPath(rails: RailEnds, points: RailPoints, from: number, to: number): number[] {
  if (from < 0 || to < 0 || from >= rails.length || to >= rails.length) return []
  if (from === to) return [from]
  return search(rails, points, from, to, true) || search(rails, points, from, to, false) || []
}

function search(rails: RailEnds, points: RailPoints, from: number, to: number, smooth: boolean): number[] | null {
  const atNodes = railsAtNodes(rails)
  // A state is a rail and the node it is left by: rail * 2 + (0: left by its first node, 1: by its second)
  const best = new Map<number, number>(), previous = new Map<number, number>()
  const heap = new Heap()
  for (const side of [0, 1]) {
    best.set(from * 2 + side, 0)
    heap.push(0, from * 2 + side)
  }
  const lengths = new Map<number, number>()
  const lengthOf = (index: number) => {
    let length = lengths.get(index)
    if (length === undefined) { length = railLength(points[index]); lengths.set(index, length) }
    return length
  }
  while (heap.size) {
    const [cost, state] = heap.pop()
    if (cost > (best.get(state) ?? Infinity)) continue
    const rail = state >> 1, node = rails[rail][state & 1]
    if (rail === to) {
      const result = [rail]
      let at = state
      while (previous.has(at)) { at = previous.get(at)!; result.push(at >> 1) }
      return result.reverse()
    }
    // The way the rail comes into the node is against the way it leaves it
    const [backX, backZ] = leaving(rails, points, rail, node)
    for (const next of atNodes.get(node) || []) {
      if (next === rail) continue
      if (smooth) {
        const [outX, outZ] = leaving(rails, points, next, node)
        if (-(backX * outX + backZ * outZ) <= 0) continue
      }
      const nextState = next * 2 + (rails[next][0] === node ? 1 : 0)
      const nextCost = cost + lengthOf(next)
      if (nextCost < (best.get(nextState) ?? Infinity)) {
        best.set(nextState, nextCost)
        previous.set(nextState, state)
        heap.push(nextCost, nextState)
      }
    }
  }
  return null
}

/**
 * The rails joined to one end to end, both ways, as far as nodes with exactly two rails go: up to a turnout, a crossing
 * or the end of the track. The rail itself first, then the rest in no particular order.
 */
export function railsAlong(rails: RailEnds, start: number): number[] {
  if (start < 0 || start >= rails.length) return []
  const atNodes = railsAtNodes(rails)
  const result = [start], seen = new Set([start])
  for (const first of [rails[start][0], rails[start][1]]) {
    let node = first, rail = start
    for (;;) {
      const here = atNodes.get(node) || []
      if (here.length !== 2) break
      const next = here[0] === rail ? here[1] : here[0]
      if (seen.has(next)) break
      seen.add(next)
      result.push(next)
      node = otherEnd(rails, next, node)
      rail = next
    }
  }
  return result
}

/** The distance between two pieces of line */
export function segmentDistance(ax: number, az: number, bx: number, bz: number, cx: number, cz: number, dx: number, dz: number) {
  if (segmentsCross(ax, az, bx, bz, cx, cz, dx, dz)) return 0
  return Math.min(pointSegment(ax, az, cx, cz, dx, dz), pointSegment(bx, bz, cx, cz, dx, dz), pointSegment(cx, cz, ax, az, bx, bz), pointSegment(dx, dz, ax, az, bx, bz))
}

function pointSegment(x: number, z: number, x1: number, z1: number, x2: number, z2: number) {
  const dx = x2 - x1, dz = z2 - z1, lengthSquared = dx * dx + dz * dz
  const t = lengthSquared ? Math.max(0, Math.min(1, ((x - x1) * dx + (z - z1) * dz) / lengthSquared)) : 0
  return Math.hypot(x - x1 - t * dx, z - z1 - t * dz)
}

function segmentsCross(ax: number, az: number, bx: number, bz: number, cx: number, cz: number, dx: number, dz: number) {
  const side = (px: number, pz: number, qx: number, qz: number, rx: number, rz: number) => Math.sign((qx - px) * (rz - pz) - (qz - pz) * (rx - px))
  const d1 = side(cx, cz, dx, dz, ax, az), d2 = side(cx, cz, dx, dz, bx, bz), d3 = side(ax, az, bx, bz, cx, cz), d4 = side(ax, az, bx, bz, dx, dz)
  return d1 * d2 < 0 && d3 * d4 < 0
}

/** The rails coming within `reach` of the piece of line a–b: those the mouse passed over between two moves */
export function railsNearSegment(points: RailPoints, ax: number, az: number, bx: number, bz: number, reach: number): number[] {
  const all = boxes(points), result: number[] = []
  const minX = Math.min(ax, bx) - reach, maxX = Math.max(ax, bx) + reach, minZ = Math.min(az, bz) - reach, maxZ = Math.max(az, bz) + reach
  for (let i = 0; i < all.length; i++) {
    const box = all[i]
    if (box.maxX < minX || box.minX > maxX || box.maxZ < minZ || box.minZ > maxZ) continue
    const p = points[i]
    for (let k = 0; k + 3 < p.length; k += 2) {
      if (segmentDistance(ax, az, bx, bz, p[k], p[k + 1], p[k + 2], p[k + 3]) <= reach) { result.push(i); break }
    }
  }
  return result
}

/** The rails inside the box or crossing its edge; the corners in any order */
export function railsInBox(points: RailPoints, x0: number, z0: number, x1: number, z1: number): number[] {
  const minX = Math.min(x0, x1), maxX = Math.max(x0, x1), minZ = Math.min(z0, z1), maxZ = Math.max(z0, z1)
  const inside = (x: number, z: number) => x >= minX && x <= maxX && z >= minZ && z <= maxZ
  const edges = [[minX, minZ, maxX, minZ], [maxX, minZ, maxX, maxZ], [maxX, maxZ, minX, maxZ], [minX, maxZ, minX, minZ]]
  const all = boxes(points), result: number[] = []
  for (let i = 0; i < all.length; i++) {
    const box = all[i]
    if (box.maxX < minX || box.minX > maxX || box.maxZ < minZ || box.minZ > maxZ) continue
    const p = points[i]
    let hit = false
    for (let k = 0; k + 1 < p.length && !hit; k += 2) hit = inside(p[k], p[k + 1])
    for (let k = 0; k + 3 < p.length && !hit; k += 2) hit = edges.some(e => segmentsCross(p[k], p[k + 1], p[k + 2], p[k + 3], e[0], e[1], e[2], e[3]))
    if (hit) result.push(i)
  }
  return result
}

/** A binary heap of [cost, value], cheapest first */
class Heap {
  private items: [number, number][] = []
  get size() { return this.items.length }
  push(cost: number, value: number) {
    const items = this.items
    items.push([cost, value])
    let i = items.length - 1
    while (i > 0) {
      const parent = (i - 1) >> 1
      if (items[parent][0] <= items[i][0]) break
      const swap = items[parent]; items[parent] = items[i]; items[i] = swap
      i = parent
    }
  }
  pop(): [number, number] {
    const items = this.items, top = items[0], last = items.pop()!
    if (items.length) {
      items[0] = last
      let i = 0
      for (;;) {
        const left = i * 2 + 1, right = left + 1
        let smallest = i
        if (left < items.length && items[left][0] < items[smallest][0]) smallest = left
        if (right < items.length && items[right][0] < items[smallest][0]) smallest = right
        if (smallest === i) break
        const swap = items[smallest]; items[smallest] = items[i]; items[i] = swap
        i = smallest
      }
    }
    return top
  }
}
