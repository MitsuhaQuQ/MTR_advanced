import type { Point, TrackMap } from "./trackSchematic"

/** Horizontal/vertical trunks with short 45-degree connectors, independent of the world's bend coordinates. */
export function elbow(a: Point,b: Point,vertical: boolean): Point[] {
  const dx=b[0]-a[0],dy=b[1]-a[1],sx=Math.sign(dx),sy=Math.sign(dy)
  if(Math.abs(dx)<1e-6||Math.abs(dy)<1e-6) return [a,b]
  const bevel=Math.min(32,Math.abs(dx),Math.abs(dy))
  return (vertical?[a,[a[0],b[1]-sy*bevel],[a[0]+sx*bevel,b[1]],b]:
    [a,[b[0]-sx*bevel,a[1]],[b[0],a[1]+sy*bevel],b] as Point[]).map(p=>p as Point).filter((p,i,all)=>!i||Math.hypot(p[0]-all[i-1][0],p[1]-all[i-1][1])>1e-6)
}

/** Douglas-Peucker: the points a polyline needs to stay within tolerance of itself */
function simplify(points: Point[],tolerance: number): Point[] {
  if(points.length<3) return points
  const a=points[0],b=points[points.length-1],dx=b[0]-a[0],dy=b[1]-a[1],len=Math.hypot(dx,dy)
  let far=0,index=0
  for(let i=1;i<points.length-1;i++) {
    const p=points[i],d=len?Math.abs((p[0]-a[0])*dy-(p[1]-a[1])*dx)/len:Math.hypot(p[0]-a[0],p[1]-a[1])
    if(d>far){far=d;index=i}
  }
  if(far<=tolerance) return [a,b]
  return [...simplify(points.slice(0,index+1),tolerance).slice(0,-1),...simplify(points.slice(index),tolerance)]
}

const EIGHT:Point[]=[0,1,2,3,4,5,6,7].map(k=>[Math.round(Math.cos(k*Math.PI/4)*1e9)/1e9,Math.round(Math.sin(k*Math.PI/4)*1e9)/1e9])
const nearestDirection=(dx:number,dy:number)=>((Math.round(Math.atan2(dy,dx)/(Math.PI/4))%8)+8)%8
/** How far (drawing units) the drawing of a track may leave out its real wiggles */
const BEND_TOLERANCE=8

/** v as α·EIGHT[k] + β·EIGHT[e] with α, β ≥ 0 and e a neighbour of k (45 degrees off), or null if v is not between them */
function split(v:Point,k:number):{along:number;other:number;e:number}|null {
  for(const e of [(k+1)%8,(k+7)%8]) {
    const d=EIGHT[k],f=EIGHT[e],det=d[0]*f[1]-d[1]*f[0]
    const along=(v[0]*f[1]-v[1]*f[0])/det,other=(d[0]*v[1]-d[1]*v[0])/det
    if(along>=-1e-6&&other>=-1e-6) return {along:Math.max(0,along),other:Math.max(0,other),e}
  }
  return null
}

/**
 * The real track from a to b, abstracted into horizontal, vertical and 45-degree parts. Its bends (Douglas-Peucker) are
 * taken a run at a time, a run being the bends one after another whose way is nearest the same of the eight directions;
 * each run is drawn in that direction, with what it leaves over in the direction 45 degrees beside it. A leftover too short
 * to show is not drawn but carried into the next run, so a gentle curve that wavers about 45 degrees has no little
 * right-angle steps. Each run aims at the real track's bend, so the drawing stays near it, and tracks side by side fold
 * alike and stay side by side. Where a or b has been moved (a station straightened), the bends between move with them.
 */
export function followTrack(real: Point[],a: Point,b: Point,directions?: number[]): Point[] {
  const bends=directions?real:simplify(real,BEND_TOLERANCE)
  const lengths=[0]
  for(let i=1;i<bends.length;i++) lengths.push(lengths[i-1]+Math.hypot(bends[i][0]-bends[i-1][0],bends[i][1]-bends[i-1][1]))
  const total=lengths[lengths.length-1]||1,first=bends[0],last=bends[bends.length-1]
  const shifted=(i:number):Point=>i===bends.length-1?[...b]:[bends[i][0]+(a[0]-first[0])*(1-lengths[i]/total)+(b[0]-last[0])*lengths[i]/total,bends[i][1]+(a[1]-first[1])*(1-lengths[i]/total)+(b[1]-last[1])*lengths[i]/total]
  // Runs of bends whose way is nearest one direction: the bend each run ends at, and its direction
  const runs:{end:number;direction:number}[]=[]
  for(let i=1;i<bends.length;i++) {
    const dx=bends[i][0]-bends[i-1][0],dy=bends[i][1]-bends[i-1][1]
    if(Math.hypot(dx,dy)<1e-6) continue
    const direction=directions?directions[i-1]:nearestDirection(dx,dy),previous=runs[runs.length-1]
    if(previous&&previous.direction===direction) previous.end=i; else runs.push({end:i,direction})
  }
  const path:Point[]=[[...a]]
  const step=(v:Point)=>{if(Math.hypot(v[0],v[1])>1e-6){const p=path[path.length-1];path.push([p[0]+v[0],p[1]+v[1]])}}
  runs.forEach((run,index)=>{
    const from=path[path.length-1],to=shifted(run.end),v:Point=[to[0]-from[0],to[1]-from[1]]
    const lastRun=index===runs.length-1
    if(Math.hypot(v[0],v[1])<1e-6) return
    // What was carried in may have turned the way to the bend out of the run's direction: then the run is drawn in the
    // direction nearest the way it now has to go, never left out (leaving it out let the drawing fall far behind)
    const direction=split(v,run.direction)?run.direction:nearestDirection(v[0],v[1])
    const parts=split(v,direction)!
    const main:Point=[EIGHT[direction][0]*parts.along,EIGHT[direction][1]*parts.along]
    const rest:Point=[EIGHT[parts.e][0]*parts.other,EIGHT[parts.e][1]*parts.other]
    // The leftover goes where it joins a part of its own direction: before the run if the path already goes its way,
    // after it if the next run does. Decided by directions, not lengths, so tracks side by side decide alike.
    const q=path[path.length-2],p=path[path.length-1],before=q?nearestDirection(p[0]-q[0],p[1]-q[1]):-1
    const after=lastRun?-1:runs[index+1].direction
    if(parts.other<1e-6) step(main)
    else if(parts.e===before) {step(rest);step(main)}
    else if(parts.e===after||lastRun) {step(main);step(rest)}
    // A leftover that would stand alone between two other directions is a step: made up in the next run instead, when
    // that run goes within 45 degrees of it and so takes it in; otherwise it is drawn here, or the drawing would drift
    else if((Math.abs(after-parts.e)+8)%8===1||(Math.abs(after-parts.e)+8)%8===7) step(main)
    else {step(main);step(rest)}
  })
  path[path.length-1]=[...b]
  if(path.length===1) path.push([...b])
  // One part for each run of one direction
  const merged:Point[]=[path[0]]
  for(let i=1;i<path.length;i++) {
    if(merged.length>=2) {
      const p=merged[merged.length-2],q=merged[merged.length-1],r=path[i]
      if(Math.abs((q[0]-p[0])*(r[1]-q[1])-(q[1]-p[1])*(r[0]-q[0]))<1e-6&&(q[0]-p[0])*(r[0]-q[0])+(q[1]-p[1])*(r[1]-q[1])>=0) {merged[merged.length-1]=r;continue}
    }
    if(Math.hypot(path[i][0]-merged[merged.length-1][0],path[i][1]-merged[merged.length-1][1])>1e-6) merged.push(path[i])
  }
  return merged
}

/**
 * The direction (one of eight) of each part between bends, decided with the tracks beside it: a part's way is averaged
 * with the parts of other tracks running near and alike (as an axis, so tracks drawn the other way count too), weighted by
 * length, and only then turned to the nearest of the eight. Two tracks side by side average the same parts, so they get
 * the same direction even where the real track runs near the middle between two of the eight, and stay side by side.
 */
export function sharedDirections(bendsOf: Point[][]): number[][] {
  const parts=bendsOf.flatMap((bends,c)=>bends.slice(1).map((q,i)=>{
    const p=bends[i],dx=q[0]-p[0],dy=q[1]-p[1]
    return {c,i,p,q,length:Math.hypot(dx,dy),angle:Math.atan2(dy,dx)}
  }))
  const near=(a:typeof parts[number],b:typeof parts[number])=>{
    // b's middle within reach of a's line, and a's middle within reach of b's
    const m=(s:typeof parts[number]):Point=>[(s.p[0]+s.q[0])/2,(s.p[1]+s.q[1])/2]
    const d=(x:Point,s:typeof parts[number])=>{const dx=s.q[0]-s.p[0],dy=s.q[1]-s.p[1],sq=dx*dx+dy*dy,t=sq?Math.max(0,Math.min(1,((x[0]-s.p[0])*dx+(x[1]-s.p[1])*dy)/sq)):0;return Math.hypot(x[0]-s.p[0]-t*dx,x[1]-s.p[1]-t*dy)}
    return d(m(b),a)<BESIDE_TRACKS&&d(m(a),b)<BESIDE_TRACKS
  }
  const result=bendsOf.map(bends=>bends.slice(1).map(()=>0))
  for(const part of parts) {
    let x=0,y=0
    for(const other of parts) {
      // As axes (doubled angles), so a track drawn the other way agrees; only tracks running alike (within 30 degrees)
      const difference=Math.abs(((other.angle-part.angle)%Math.PI+Math.PI*1.5)%Math.PI-Math.PI/2)
      if(other!==part&&(difference>Math.PI/6||!near(part,other))) continue
      x+=Math.cos(other.angle*2)*other.length;y+=Math.sin(other.angle*2)*other.length
    }
    const axis=Math.atan2(y,x)/2
    // Of the two ways along the averaged axis, the one this part goes
    const angle=Math.cos(axis-part.angle)>=0?axis:axis+Math.PI
    result[part.c][part.i]=nearestDirection(Math.cos(angle),Math.sin(angle))
  }
  return result
}
const BESIDE_TRACKS=28

/** Straight along the longer axis with one 45-degree part in the middle, for a track that runs one way */
export function straightThenDiagonal(a: Point,b: Point): Point[] {
  const dx=b[0]-a[0],dy=b[1]-a[1],sx=Math.sign(dx),sy=Math.sign(dy)
  if(Math.abs(dx)<1e-6||Math.abs(dy)<1e-6||Math.abs(Math.abs(dx)-Math.abs(dy))<1e-6) return [a,b]
  if(Math.abs(dx)>Math.abs(dy)) {const half=(Math.abs(dx)-Math.abs(dy))/2;return [a,[a[0]+sx*half,a[1]],[b[0]-sx*half,b[1]],b]}
  const half=(Math.abs(dy)-Math.abs(dx))/2
  return [a,[a[0],a[1]+sy*half],[b[0],b[1]-sy*half],b]
}

export function orthogonalTracks(input: TrackMap): TrackMap {
  if(!input.rails.length) return {...input}
  // The schematic follows the real drawing beside it: nodes start where it has them
  const layout={...input,trunks:[] as number[]}
  const rails=layout.rails.map(r=>({...r,points:r.points.map(p=>[...p] as Point)}))
  const nodes=new Map<number,Point>()
  rails.forEach(r=>{nodes.set(r.nodes[0],r.points[0]);nodes.set(r.nodes[1],r.points[r.points.length-1])})
  // Station platforms remain straight and parallel, with all their attached rail endpoints moved together.
  const stationGroups=[...layout.stations.map(station=>rails.filter(r=>r.kind===1&&r.station===station.id)),...rails.filter(r=>r.kind===1&&!r.station).map(r=>[r])]
  for(const platforms of stationGroups) {
    if(!platforms.length) continue
    const first=input.rails[platforms[0].rail],a=first.points[0],b=first.points[first.points.length-1]
    const angle=Math.atan2(b[1]-a[1],b[0]-a[0]),snap=Math.round(angle/(Math.PI/4))*Math.PI/4
    const c=Math.cos(snap-angle),s=Math.sin(snap-angle)
    const points=platforms.flatMap(r=>[r.points[0],r.points[r.points.length-1]])
    const centre:Point=[points.reduce((n,p)=>n+p[0],0)/points.length,points.reduce((n,p)=>n+p[1],0)/points.length]
    const originals=platforms.flatMap(r=>[input.rails[r.rail].points[0],input.rails[r.rail].points.at(-1)!])
    const originalCentre:Point=[originals.reduce((n,p)=>n+p[0],0)/originals.length,originals.reduce((n,p)=>n+p[1],0)/originals.length]
    for(const r of platforms) {
      const original=input.rails[r.rail],p=original.points[0],q=original.points[original.points.length-1],mid:Point=[(p[0]+q[0])/2,(p[1]+q[1])/2],half=Math.hypot(q[0]-p[0],q[1]-p[1])/2
      const m:Point=[centre[0]+(mid[0]-originalCentre[0])*c-(mid[1]-originalCentre[1])*s,centre[1]+(mid[0]-originalCentre[0])*s+(mid[1]-originalCentre[1])*c]
      r.points=[[m[0]-Math.cos(snap)*half,m[1]-Math.sin(snap)*half],[m[0]+Math.cos(snap)*half,m[1]+Math.sin(snap)*half]]
      nodes.set(r.nodes[0],r.points[0]);nodes.set(r.nodes[1],r.points[1])
    }
  }
  const adjacent=new Map<number,number[]>(), platformNodes=new Set<number>()
  rails.forEach((r,i)=>{r.nodes.forEach(n=>{if(!adjacent.has(n))adjacent.set(n,[]);adjacent.get(n)!.push(i);if(r.kind===1)platformNodes.add(n)})})
  // The switches at a station's ends stay where the real drawing has them: moving them to line up with the platforms
  // pulled the tracks beyond off their real way (a box where two tracks left one throat bending different ways)
  const snap=(p:Point):Point=>[Math.round(p[0]/4)*4,Math.round(p[1]/4)*4]
  nodes.forEach((p,n)=>nodes.set(n,snap(p)))
  rails.filter(r=>r.kind===1).forEach(r=>{
    const a=snap(r.points[0]),delta:Point=[r.points[1][0]-r.points[0][0],r.points[1][1]-r.points[0][1]]
    const span=Math.max(4,Math.round(Math.max(Math.abs(delta[0]),Math.abs(delta[1]))/4)*4)
    const b:Point=[a[0]+(Math.abs(delta[0])<1e-6?0:Math.sign(delta[0])*span),a[1]+(Math.abs(delta[1])<1e-6?0:Math.sign(delta[1])*span)]
    r.points=[a,b];nodes.set(r.nodes[0],a);nodes.set(r.nodes[1],b)
  })
  const visited=new Set<number>()
  const anchor=(n:number)=>platformNodes.has(n)||(adjacent.get(n)?.length||0)!==2
  const collectChain=(start:number,firstRail:number)=>{
    const chain:{rail:number;from:number;to:number;length:number}[]=[]
    let current=start,index=firstRail
    for(;;) {
      if(visited.has(index)) break
      visited.add(index)
      const r=rails[index],to=r.nodes[0]===current?r.nodes[1]:r.nodes[0]
      const length=input.rails[index].points.slice(1).reduce((n,p,i)=>n+Math.hypot(p[0]-input.rails[index].points[i][0],p[1]-input.rails[index].points[i][1]),0)
      chain.push({rail:index,from:current,to,length:Math.max(length,1)})
      current=to
      if(anchor(current)||current===start) break
      const next=(adjacent.get(current)||[]).find(i=>i!==index&&rails[i].kind!==1)
      if(next===undefined) break
      index=next
    }
    if(chain.length) collected.push({start,current,chain})
  }
  const collected:{start:number;current:number;chain:{rail:number;from:number;to:number;length:number}[]}[]=[]
  const routeChain=({start,current,chain}:typeof collected[number],bends:Point[],directions:number[])=>{
    const a=nodes.get(start)!,b=nodes.get(current)!
    const path=followTrack(bends,a,b,directions)
    const distances=[0]
    path.slice(1).forEach((p,i)=>distances.push(distances[i]+Math.hypot(p[0]-path[i][0],p[1]-path[i][1])))
    const total=distances.at(-1)!,originalLength=chain.reduce((n,c)=>n+c.length,0)
    const at=(d:number):Point=>{
      if(d<=0)return [...a]
      if(d>=total)return [...b]
      for(let i=1;i<path.length;i++) if(d<=distances[i]&&distances[i]>distances[i-1]) {
        const t=(d-distances[i-1])/(distances[i]-distances[i-1])
        return [path[i-1][0]+(path[i][0]-path[i-1][0])*t,path[i-1][1]+(path[i][1]-path[i-1][1])*t]
      }
      return [...b]
    }
    let position=0
    for(const c of chain) {
      const from=position/originalLength*total;position+=c.length;const to=position/originalLength*total
      const points:Point[]=[at(from),...path.filter((_,i)=>distances[i]>from&&distances[i]<to),at(to)]
      nodes.set(c.from,points[0]);nodes.set(c.to,points.at(-1)!)
      rails[c.rail].points=rails[c.rail].nodes[0]===c.from?points:points.reverse()
    }
  }
  for(const [node,edges] of adjacent) if(anchor(node)) for(const i of edges) if(rails[i].kind!==1&&!visited.has(i)) collectChain(node,i)
  rails.forEach((r,i)=>{if(r.kind!==1&&!visited.has(i))collectChain(r.nodes[0],i)})
  const bendsOf=collected.map(({chain})=>simplify(chain.flatMap((link,k)=>{const r=input.rails[link.rail],p=r.nodes[0]===link.from?r.points:[...r.points].reverse();return k?p.slice(1):p}),BEND_TOLERANCE))
  const directionsOf=sharedDirections(bendsOf)
  collected.forEach((c,i)=>routeChain(c,bendsOf[i],directionsOf[i]))
  const degree=new Map<number,number>()
  rails.forEach(r=>r.nodes.forEach(n=>degree.set(n,(degree.get(n)||0)+1)))
  const points=rails.flatMap(r=>r.points),xs=points.map(p=>p[0]),ys=points.map(p=>p[1])
  const offset:Point=[60-(Math.min(...xs)||0),60-(Math.min(...ys)||0)]
  const move=(p:Point):Point=>[p[0]+offset[0],p[1]+offset[1]]
  rails.forEach(r=>{r.points=r.points.map(move)})
  const stations=layout.stations.map(station=>{
    const platforms=rails.filter(r=>r.station===station.id&&r.kind===1),p=platforms.flatMap(r=>r.points)
    if(!p.length) return {...station,x:station.x+offset[0],y:station.y+offset[1]}
    const a=platforms[0].points[0],b=platforms[0].points[1],vertical=Math.abs(b[1]-a[1])>Math.abs(b[0]-a[0])
    const xs=p.map(v=>v[0]),ys=p.map(v=>v[1])
    return {...station,vertical,x:vertical?Math.max(...xs)+18:(Math.min(...xs)+Math.max(...xs))/2,y:vertical?(Math.min(...ys)+Math.max(...ys))/2:Math.max(...ys)+22}
  })
  return {...layout,rails,stations,ends:[...degree].filter(([,d])=>d===1).map(([n])=>move(nodes.get(n)!)),junctions:[...degree].filter(([,d])=>d>2).map(([n])=>move(nodes.get(n)!)),width:points.length?Math.max(...xs)-Math.min(...xs)+220:220,height:points.length?Math.max(...ys)-Math.min(...ys)+140:140}
}
