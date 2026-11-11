import type { Point, TrackMap } from "./trackSchematic"

const length = (p: Point[]) => p.slice(1).reduce((n, v, i) => n + Math.hypot(v[0]-p[i][0],v[1]-p[i][1]),0)
const equal = (a: Point,b: Point) => Math.hypot(a[0]-b[0],a[1]-b[1]) < 1e-6

/** Straighten a station-to-station trunk as a shared ribbon. All nearby tracks and switches move with it. */
export function schematicLayout(input: TrackMap): TrackMap & { trunks: number[] } {
  const adjacency = new Map<number, { rail: number; to: number }[]>()
  const stations = new Map<number, { id: string; vertical: boolean }>()
  input.rails.forEach((r,i) => {
    for (const [from,to] of [r.nodes,[r.nodes[1],r.nodes[0]]] as [number,number][]) {
      if (!adjacency.has(from)) adjacency.set(from,[])
      if (r.kind === 0) adjacency.get(from)!.push({rail:i,to})
      if (r.kind === 1 && r.station) {
        const a=r.points[0],b=r.points[r.points.length-1]
        stations.set(from,{id:r.station,vertical:Math.abs(b[1]-a[1])>Math.abs(b[0]-a[0])})
      }
    }
  })
  const candidates = new Map<string,{cost:number;start:number;steps:{rail:number;from:number}[];vertical:boolean}>()
  const lengths=input.rails.map(r=>length(r.points))
  for (const [start,station] of stations) {
    const distances=new Map([[start,0]]), paths=new Map<number,{rail:number;from:number}[]>([[start,[]]]), done=new Set<number>()
    for (;;) {
      let node=-1,cost=Infinity
      for (const [n,d] of distances) if (!done.has(n)&&d<cost) {node=n;cost=d}
      if (node<0) break
      done.add(node)
      const end=stations.get(node)
      if (node!==start&&end) {
        if (end.id!==station.id&&end.vertical!==station.vertical) {
          const key=[station.id,end.id].sort().join("|"), previous=candidates.get(key)
          if (!previous||cost<previous.cost) candidates.set(key,{cost,start,steps:paths.get(node)!,vertical:station.vertical})
        }
        continue
      }
      for (const edge of adjacency.get(node)||[]) {
        const next=cost+lengths[edge.rail]
        if (next<(distances.get(edge.to)??Infinity)) {
          distances.set(edge.to,next);paths.set(edge.to,[...paths.get(node)!,{rail:edge.rail,from:node}])
        }
      }
    }
  }
  let map: TrackMap={...input,rails:input.rails.map(r=>({...r,points:r.points.map(p=>[...p] as Point)})),stations:input.stations.map(s=>({...s})),ends:input.ends.map(p=>[...p] as Point),junctions:input.junctions.map(p=>[...p] as Point)}
  const used=new Set<number>()
  const parent=new Map<number,number>()
  input.rails.forEach(r=>r.nodes.forEach(n=>parent.set(n,n)))
  const find=(n:number):number=>{let p=n;while(parent.get(p)!==p)p=parent.get(p)!;return p}
  input.rails.forEach(r=>parent.set(find(r.nodes[0]),find(r.nodes[1])))
  const locked=new Set<string>()
  const key=(p:Point)=>p.map(v=>v.toFixed(6)).join(',')
  for (const route of [...candidates.values()].sort((a,b)=>a.cost-b.cost)) {
    if (route.steps.some(s=>used.has(s.rail))) continue
    const path:Point[]=[]
    for(const step of route.steps) {
      const rail=map.rails[step.rail], points=rail.nodes[0]===step.from?rail.points:[...rail.points].reverse()
      for(const p of points) if(!path.length||!equal(path[path.length-1],p)) path.push(p)
    }
    if(path.length<3) continue
    const a=path[0],b=path[path.length-1],corner:Point=route.vertical?[a[0],b[1]]:[b[0],a[1]]
    const dx=Math.abs(b[0]-a[0]),dy=Math.abs(b[1]-a[1])
    if(Math.min(dx,dy)<80) continue
    const first=path[1],last=path[path.length-2]
    if((first[0]-a[0])*(corner[0]-a[0])+(first[1]-a[1])*(corner[1]-a[1])<=0 ||
      (b[0]-last[0])*(b[0]-corner[0])+(b[1]-last[1])*(b[1]-corner[1])<=0) continue
    const bevel=Math.min(32,dx/4,dy/4)
    const before:Point=route.vertical?[corner[0],corner[1]-Math.sign(b[1]-a[1])*bevel]:[corner[0]-Math.sign(b[0]-a[0])*bevel,corner[1]]
    const after:Point=route.vertical?[corner[0]+Math.sign(b[0]-a[0])*bevel,corner[1]]:[corner[0],corner[1]+Math.sign(b[1]-a[1])*bevel]
    const target:Point[]=[a,before,after,b]
    const sourceDistances=[0]
    path.slice(1).forEach((p,i)=>sourceDistances.push(sourceDistances[i]+Math.hypot(p[0]-path[i][0],p[1]-path[i][1])))
    const total=sourceDistances[sourceDistances.length-1],targetLength=length(target)
    const support=Math.max(160,Math.max(dx,dy)*1.5)
    const nearPath=(p:Point)=>{
      let nearest=Infinity
      for(let i=1;i<path.length;i++) {
        const a=path[i-1],b=path[i],dx=b[0]-a[0],dy=b[1]-a[1],square=dx*dx+dy*dy
        const t=square?Math.max(0,Math.min(1,((p[0]-a[0])*dx+(p[1]-a[1])*dy)/square)):0
        nearest=Math.min(nearest,Math.hypot(p[0]-a[0]-t*dx,p[1]-a[1]-t*dy))
      }
      return nearest
    }
    const reserved=map.rails.flatMap((r,i)=>find(r.nodes[0])===find(route.start)&&nearPath(r.points[0])<40&&nearPath(r.points[r.points.length-1])<40?[i]:[])
    const transform=(p:Point):Point=>{
      let best=Infinity,along=0,side=0,overhang=0
      for(let i=1;i<path.length;i++) {
        const start=path[i-1],end=path[i],len=sourceDistances[i]-sourceDistances[i-1]
        if(!len) continue
        const ux=(end[0]-start[0])/len,uy=(end[1]-start[1])/len
        const raw=(p[0]-start[0])*ux+(p[1]-start[1])*uy,t=Math.max(0,Math.min(len,raw))
        const distance=Math.hypot(p[0]-start[0]-t*ux,p[1]-start[1]-t*uy)
        if(distance<best) {best=distance;along=sourceDistances[i-1]+t;side=-(p[0]-start[0])*uy+(p[1]-start[1])*ux;overhang=raw-t}
      }
      if(best>=support) return [...p]
      let remain=along/total*targetLength
      for(let i=1;i<target.length;i++) {
        const start=target[i-1],end=target[i],len=Math.hypot(end[0]-start[0],end[1]-start[1])
        if(remain>len&&i<target.length-1) {remain-=len;continue}
        const ux=(end[0]-start[0])/len,uy=(end[1]-start[1])/len
        const weight=Math.min(1,(support-best)/(support/2))
        return [p[0]+(start[0]+ux*(remain+overhang)-uy*side-p[0])*weight,p[1]+(start[1]+uy*(remain+overhang)+ux*side-p[1])*weight]
      }
      return [...p]
    }
    for(const strength of [1]) {
      const move=(p:Point):Point=>{if(locked.has(key(p))) return [...p];const q=transform(p);return [p[0]+(q[0]-p[0])*strength,p[1]+(q[1]-p[1])*strength]}
      const proposed:TrackMap={...map,rails:map.rails.map(r=>({...r,points:find(r.nodes[0])===find(route.start)?r.points.map(move):r.points})),ends:map.ends.map(move),junctions:map.junctions.map(move),stations:map.stations.map(s=>{const p=move([s.x,s.y]);return {...s,x:p[0],y:p[1]}})}
      map=proposed
      reserved.forEach(i=>{used.add(i);map.rails[i].points.forEach(p=>locked.add(key(p)))})
      break
    }
  }
  return {...map,trunks:[...used]}
}
