import type { Point, TrackMap } from "./trackSchematic"
import { schematicLayout } from "./schematicLayout"

/** Horizontal/vertical trunks with short 45-degree connectors, independent of the world's bend coordinates. */
export function elbow(a: Point,b: Point,vertical: boolean): Point[] {
  const dx=b[0]-a[0],dy=b[1]-a[1],sx=Math.sign(dx),sy=Math.sign(dy)
  if(Math.abs(dx)<1e-6||Math.abs(dy)<1e-6) return [a,b]
  const bevel=Math.min(32,Math.abs(dx),Math.abs(dy))
  return (vertical?[a,[a[0],b[1]-sy*bevel],[a[0]+sx*bevel,b[1]],b]:
    [a,[b[0]-sx*bevel,a[1]],[b[0],a[1]+sy*bevel],b] as Point[]).map(p=>p as Point).filter((p,i,all)=>!i||Math.hypot(p[0]-all[i-1][0],p[1]-all[i-1][1])>1e-6)
}

export function orthogonalTracks(input: TrackMap): TrackMap {
  if(!input.rails.length) return {...input}
  const layout=schematicLayout(input)
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
  // Align the two ends of a station throat as a group, rather than letting a crossover choose the wrong lane.
  for(const platforms of stationGroups) {
    if(!platforms.length) continue
    const first=platforms[0],a=first.points[0],b=first.points[1],vertical=Math.abs(b[1]-a[1])>Math.abs(b[0]-a[0])
    if(Math.min(Math.abs(b[0]-a[0]),Math.abs(b[1]-a[1]))>1e-6) continue
    const sideAxis=vertical?0:1, alongAxis=vertical?1:0
    for(const end of [0,1]) {
      const ports=platforms.map(r=>r.nodes[end]),far=new Set<number>()
      for(const port of ports) for(const i of adjacent.get(port)||[]) {
        const r=rails[i],n=r.nodes[0]===port?r.nodes[1]:r.nodes[0]
        if(r.kind!==1&&!platformNodes.has(n)&&(adjacent.get(n)?.length||0)>2) {
          const p=input.rails[i].points[0],q=input.rails[i].points.at(-1)!
          if(Math.hypot(q[0]-p[0],q[1]-p[1])<320) far.add(n)
        }
      }
      if(far.size!==ports.length||ports.length<2) continue
      const sortedPorts=[...ports].sort((a,b)=>nodes.get(a)![sideAxis]-nodes.get(b)![sideAxis])
      const sortedFar=[...far].sort((a,b)=>{
        const original=(n:number)=>{const r=input.rails[(adjacent.get(n)||[])[0]];return r.nodes[0]===n?r.points[0]:r.points.at(-1)!}
        return original(a)[sideAxis]-original(b)[sideAxis]
      })
      const previousAlong=sortedFar.reduce((n,p)=>n+nodes.get(p)![alongAxis],0)/sortedFar.length
      const portAlong=sortedPorts.reduce((n,p)=>n+nodes.get(p)![alongAxis],0)/sortedPorts.length
      const separation=Math.abs(nodes.get(sortedPorts.at(-1)!)![sideAxis]-nodes.get(sortedPorts[0])![sideAxis])
      const along=portAlong+Math.sign(previousAlong-portAlong)*Math.max(4,separation)
      sortedFar.forEach((n,i)=>{const p=[...nodes.get(n)!] as Point;p[sideAxis]=nodes.get(sortedPorts[i])![sideAxis];p[alongAxis]=along;nodes.set(n,p)})
    }
  }
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
  const routeChain=(start:number,firstRail:number)=>{
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
    if(!chain.length) return
    const a=nodes.get(start)!,b=nodes.get(current)!,first=chain[0],raw=layout.rails[first.rail]
    const source=raw.nodes[0]===start?raw.points:[...raw.points].reverse()
    const hint=source.find(p=>Math.hypot(p[0]-source[0][0],p[1]-source[0][1])>4)||b
    const vertical=Math.abs(hint[1]-source[0][1])>=Math.abs(hint[0]-source[0][0])
    const dx=b[0]-a[0],dy=b[1]-a[1]
    let path:Point[]
    if(Math.abs(dy)<40&&Math.abs(dx)>Math.abs(dy)*2) {
      const half=(Math.abs(dx)-Math.abs(dy))/2,sx=Math.sign(dx)
      path=[a,[a[0]+sx*half,a[1]],[b[0]-sx*half,b[1]],b]
    } else if(Math.abs(dx)<40&&Math.abs(dy)>Math.abs(dx)*2) {
      const half=(Math.abs(dy)-Math.abs(dx))/2,sy=Math.sign(dy)
      path=[a,[a[0],a[1]+sy*half],[b[0],b[1]-sy*half],b]
    } else if(Math.hypot(dx,dy)<1e-6) {
      const all=chain.flatMap(c=>layout.rails[c.rail].points),far=all.reduce((best,p)=>Math.hypot(p[0]-a[0],p[1]-a[1])>Math.hypot(best[0]-a[0],best[1]-a[1])?p:best,a)
      path=[...elbow(a,far,vertical),...elbow(far,b,!vertical).slice(1)]
    } else path=elbow(a,b,vertical)
    const isTrunk=chain.every(c=>layout.trunks.includes(c.rail))
    const siding=chain.some(c=>rails[c.rail].kind===2||rails[c.rail].kind===3)
    const startFork=(adjacent.get(start)?.length||0)>2,endFork=(adjacent.get(current)?.length||0)>2
    if(siding&&startFork&&endFork&&Math.min(Math.abs(dx),Math.abs(dy))<40) {
      const horizontal=Math.abs(dx)>=Math.abs(dy),all=chain.flatMap(c=>input.rails[c.rail].points)
      const originalA=input.rails[first.rail].nodes[0]===start?input.rails[first.rail].points[0]:input.rails[first.rail].points.at(-1)!
      const side=Math.sign(all.reduce((n,p)=>n+(horizontal?p[1]-originalA[1]:p[0]-originalA[0]),0))||-1
      const gap=Math.min(32,(horizontal?Math.abs(dx):Math.abs(dy))/4)
      const p:Point=horizontal?[a[0]+Math.sign(dx)*gap,a[1]+side*gap]:[a[0]+side*gap,a[1]+Math.sign(dy)*gap]
      const q:Point=horizontal?[b[0]-Math.sign(dx)*gap,b[1]+side*gap]:[b[0]+side*gap,b[1]-Math.sign(dy)*gap]
      path=[a,...elbow(p,q,!horizontal),b]
    } else if(!isTrunk&&Math.abs(dx)>80&&Math.abs(dy)>80) {
      // Fan branches away from the through tracks before routing their interval.
      if(startFork) {
        const lead:Point=[a[0]+Math.sign(dx)*32,a[1]+Math.sign(dy)*32]
        path=[a,...elbow(lead,b,vertical)]
      } else if(endFork) {
        const lead:Point=[b[0]-Math.sign(dx)*32,b[1]-Math.sign(dy)*32]
        path=[...elbow(a,lead,vertical),b]
      }
    }
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
  for(const [node,edges] of adjacent) if(anchor(node)) for(const i of edges) if(rails[i].kind!==1&&!visited.has(i)) routeChain(node,i)
  rails.forEach((r,i)=>{if(r.kind!==1&&!visited.has(i))routeChain(r.nodes[0],i)})
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
