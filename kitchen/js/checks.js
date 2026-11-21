/* ---------- checks engine ---------- */
function checks(){
  if(C_)return C_;
  const G=geom(), st=S.settings, out=[], ran=new Set(), byItem={}, lines=[];
  const items=S.items;
  const lab=it=>CAT[it.t].sku(it)+(it.wall!=null?' (W'+(it.wall+1)+')':'');
  const push=(sev,rule,title,detail,ids=[],extra={})=>{out.push({sev,rule,title,detail,ids,...extra});for(const id of ids){const o=byItem[id];if(!o||rank(sev)>rank(o))byItem[id]=sev}};
  const rank=s=>({info:1,warn:2,err:3}[s]||0);
  const R=n=>ran.add(n);
  const wallItems=j=>items.filter(i=>i.wall===j);
  const baseRow=j=>wallItems(j).filter(isBaseLevel).sort((a,b)=>a.x-b.x);
  const n=S.walls.length;

  // 1. measurements close
  R('Room measurements close');
  if(n>=3){
    const last=G.walls[n-1]; const meas=+S.walls[n-1].len;
    const err=Math.abs(last.len-meas);
    if(meas>0&&err>1)push('err','Room measurements close','Room does not close by '+fmt(err),'W'+n+' measured '+fmt(meas,'ft')+' but the other walls and angles put it at '+fmt(last.len,'ft')+'. Re-shoot wall lengths and check corner diagonals.');
    else if(meas>0&&err>0.25)push('warn','Room measurements close','Closure off by '+fmt(err),'W'+n+' measured '+fmt(meas,'ft')+' vs '+fmt(last.len,'ft')+' computed. Within tolerance for layout, but verify before ordering.');
    for(let i=0;i<n;i++){
      if(!cornerHasDiag(i))continue;
      const m=cornerMeasured(i),g=G.corners[i];
      if(i>=n-2&&Math.abs(m-g)>0.5)push('warn','Room measurements close','Corner '+(i+1)+' diagonal disagrees with walls','Diagonal check reads '+m.toFixed(1)+'°, wall lengths imply '+g.toFixed(1)+'°. One of the wall lengths is likely off.');
    }
  }
  // 2. out of square at cabinet runs
  R('Out-of-square corners');
  for(let j=0;j<n;j++){
    const w=G.walls[j];
    for(const end of [0,1]){
      const ci=end?j:startCorner(j); const ang=G.corners[ci];
      if(Math.abs(ang-90)<0.15)continue;
      const row=wallItems(j).filter(it=>end?Math.abs(it.x+it.w-w.len)<0.6:it.x<0.6);
      for(const it of row){
        const loss=frontLoss(ang,it.d);
        if(Math.abs(loss)<1/32)continue;
        if(it.t==='F'||it.t==='PNL'){push('info','Out-of-square corners','Scribe '+lab(it)+': taper '+fmt(Math.abs(loss)),'Corner '+(ci+1)+' is '+ang.toFixed(2)+'°. Filler is '+fmt(it.w)+' at the wall and '+fmt(it.w-loss)+' at the face — scribe to the wall.',[it.id]);}
        else if(loss>0)push('err','Out-of-square corners',lab(it)+' face hits the return wall','Corner '+(ci+1)+' is '+ang.toFixed(2)+'° (closed). At '+fmt(it.d)+' depth the face corner runs into the wall by '+fmt(loss)+'. Add a scribe filler of at least '+fmt(Math.max(0.75,loss+0.5))+'.',[it.id]);
        else push('info','Out-of-square corners',lab(it)+' leaves a '+fmt(-loss)+' gap at the face','Corner '+(ci+1)+' is '+ang.toFixed(2)+'° (open). Cover with an applied scribe molding or filler.',[it.id]);
      }
    }
  }
  // 3. collisions & bounds
  R('No collisions between cabinets and appliances');
  for(let a=0;a<items.length;a++){const A=items[a],PA=itemPoly(A);if(!PA)continue;
    for(let b=a+1;b<items.length;b++){const B=items[b];if(A.z>=B.z+B.h-0.05||B.z>=A.z+A.h-0.05)continue;const PB=itemPoly(B);if(!PB)continue;
      if(polyOverlap(PA,PB,0.07))push('err','No collisions between cabinets and appliances','Collision: '+lab(A)+' and '+lab(B),'These two occupy the same space. Move one, change a width, or add a filler.',[A.id,B.id]);}}
  R('Everything fits inside the room');
  for(const it of items){
    if(it.wall!=null){const w=G.walls[it.wall];if(!w){push('err','Everything fits inside the room',lab(it)+' is on a wall that no longer exists','Move it to another wall.',[it.id]);continue}
      if(it.x<-0.06||it.x+it.w>w.len+0.06)push('err','Everything fits inside the room',lab(it)+' runs past the end of W'+(it.wall+1),'Overhangs by '+fmt(Math.max(-it.x,it.x+it.w-w.len))+'.',[it.id]);}
    else{const P=itemPoly(it);if(P.some(p=>!inPoly(p,G.P)))push('err','Everything fits inside the room','Island extends outside the room','Move the island back inside the walls.',[it.id])}
  }
  // 4. ceiling & soffits
  R('Ceiling and soffit clearance');
  for(const it of items){const top=it.z+it.h;
    if(top>S.ceiling-0.25&&it.t!=='HOOD')push('err','Ceiling and soffit clearance',lab(it)+' is taller than the ceiling allows','Top at '+fmt(top)+' AFF; ceiling is '+fmt(S.ceiling)+'. Need ¼" install clearance.',[it.id]);
    if(it.wall==null)continue;
    for(const u of S.utils){if(u.type!=='soffit'||u.wall!==it.wall)continue;const sb=S.ceiling-u.h;
      if(top>sb+0.01&&it.x<u.x+u.w&&it.x+it.w>u.x)push('err','Ceiling and soffit clearance',lab(it)+' runs into the soffit','Soffit bottom is '+fmt(sb)+' AFF; cabinet top is '+fmt(top)+'. Use a shorter cabinet or remove the soffit.',[it.id])}
  }
  // 5. openings, trim
  R('Window and door trim clearance');
  for(const u of S.utils){
    if(u.type!=='window'&&u.type!=='door'&&u.type!=='other')continue;
    const t=+u.trim||0, x0=u.x-t, x1=u.x+u.w+t, z0=(u.type==='door'?0:u.z-t), z1=u.z+u.h+t;
    for(const it of wallItems(u.wall)){
      if(it.x>=x1-0.01||it.x+it.w<=x0+0.01)continue;
      if(it.z>=z1-0.01||it.z+it.h<=z0+0.01)continue;
      const what=u.type==='door'?'door opening':u.type==='window'?'window':'obstacle';
      push('err','Window and door trim clearance',lab(it)+' overlaps the '+what+(t?' trim':''),UTIL[u.type].n+' at '+fmt(u.x)+' from the left'+(t?', '+fmt(t)+' casing':'')+'. Clear '+fmt(Math.min(it.x+it.w-x0,x1-it.x))+' horizontally or change the cabinet.',[it.id]);
    }
    if(u.type==='window'&&u.z-t<st.counterH&&wallItems(u.wall).some(it=>hasCT(it)&&it.x<x1&&it.x+it.w>x0))push('err','Window and door trim clearance','Window sill is below the countertop','Window (incl. casing) starts at '+fmt(u.z-t)+' AFF; countertop is '+fmt(st.counterH)+'. Raise the sill or drop counter height.',[]);
  }
  // 6. walkways
  R('Walkway and island clearances');
  const obst=items.filter(it=>isBaseLevel(it)&&it.t!=='HOOD');
  const islands=items.filter(i=>i.wall==null);
  const adj=(a,b)=>a===b||Math.abs(a-b)===1||Math.abs(a-b)===n-1;
  const seen=new Set();
  const walkCheck=(A,B,isl)=>{
    const key=[A.id,B.id].sort().join();if(seen.has(key))return;seen.add(key);
    const r=polyDist(ctPoly(A),ctPoly(B));if(!r.p||r.d>66)return;
    const bad=r.d<36-1/32, warn=r.d<42-1/32;
    lines.push({p:r.p,q:r.q,d:r.d,bad:warn,a:A,b:B});
    if(bad)push('err','Walkway and island clearances','Walkway only '+fmt(r.d)+' between '+lab(A)+' and '+lab(B),'NKBA minimum is 36" for a walkway and 42" for a work aisle (48" for two cooks).',[A.id,B.id]);
    else if(warn)push('warn','Walkway and island clearances','Work aisle '+fmt(r.d)+' — under 42"','Between '+lab(A)+' and '+lab(B)+'. NKBA recommends 42" for one cook, 48" for two.',[A.id,B.id]);
  };
  for(const I of islands){
    for(const O of obst){if(O===I)continue;walkCheck(I,O,true)}
    for(let j=0;j<n;j++){const w=G.walls[j];const P=ctPoly(I);let md=1e9,mp,mq;for(const p of P){const r=segPt(p,w.a,w.b);if(r.d<md){md=r.d;mp=p;mq=r.q}}
      const nearer=obst.some(O=>O.wall===j&&polyDist(ctPoly(I),ctPoly(O)).d<md-1);
      if(!nearer&&md<36){lines.push({p:mp,q:mq,d:md,bad:true});push('warn','Walkway and island clearances','Island is '+fmt(md)+' from W'+(j+1),'A walkway behind an island should be at least 36" (44" behind seating).',[I.id])}
    }
  }
  for(const I of islands){
    if(!(I.seat>=9))continue;
    const f=frame(I);const dir={x:-f.V.x,y:-f.V.y};
    const segs=G.walls.map(w=>[w.a,w.b]);
    for(const O of obst){if(O===I)continue;const P=ctPoly(O);P.forEach((p,k)=>segs.push([p,P[(k+1)%P.length]]))}
    let md=1e9,mp=null,mq=null;
    for(let u=6;u<=I.w-6+0.01;u+=Math.max(6,(I.w-12)/8)){
      const p={x:f.o.x+f.U.x*u,y:f.o.y+f.U.y*u};
      for(const [a,b] of segs){const t=rayHit(p,dir,a,b);if(t!=null&&t<md){md=t;mp=p;mq={x:p.x+dir.x*t,y:p.y+dir.y*t}}}
    }
    if(mp&&md<44-1/32){lines.push({p:mp,q:mq,d:md,bad:true,force:1});push(md<36?'err':'warn','Walkway and island clearances','Only '+fmt(md)+' behind island seating','NKBA: 44" behind seated diners where people walk past, 36" if nobody does.',[I.id])}
  }
  // galley aisles: base items on non-adjacent walls
  for(let a=0;a<obst.length;a++)for(let b=a+1;b<obst.length;b++){const A=obst[a],B=obst[b];if(A.wall==null||B.wall==null||adj(A.wall,B.wall))continue;walkCheck(A,B,false)}

  // 7. landing areas
  R('Landing areas (sink, range, refrigerator)');
  const landing=(it,side)=>{const row=baseRow(it.wall);let total=0,edge=side<0?it.x:it.x+it.w;
    for(let k=0;k<30;k++){const r=row.find(o=>o!==it&&(side<0?Math.abs(o.x+o.w-edge)<0.3:Math.abs(o.x-edge)<0.3));if(!r||!hasCT(r))break;total+=r.w;edge=side<0?r.x:r.x+r.w}
    return total};
  const nearIsland=(it,dist)=>{const P=ctPoly(it);return islands.some(I=>polyDist(P,ctPoly(I)).d<=dist)};
  for(const it of items){if(it.wall==null)continue;
    if(it.t==='RG'){const L=landing(it,-1),Rr=landing(it,1);const lo=Math.min(L,Rr),hi=Math.max(L,Rr);
      if(!(hi>=15&&lo>=12))push('warn','Landing areas (sink, range, refrigerator)','Range landing: '+fmt(L)+' left / '+fmt(Rr)+' right','NKBA: 12" on one side and 15" on the other, at the same height as the cooktop.',[it.id]);}
    if(it.t==='SB'){const L=landing(it,-1),Rr=landing(it,1);const lo=Math.min(L,Rr),hi=Math.max(L,Rr);
      if(!(hi>=24&&lo>=18))push('warn','Landing areas (sink, range, refrigerator)','Sink landing: '+fmt(L)+' left / '+fmt(Rr)+' right','NKBA: 24" on one side and 18" on the other.',[it.id]);}
    if(it.t==='REF'){const L=landing(it,-1),Rr=landing(it,1);
      if(Math.max(L,Rr)<15&&!nearIsland(it,48))push('warn','Landing areas (sink, range, refrigerator)','No landing space beside the refrigerator','NKBA: 15" on the handle side, or across an island within 48".',[it.id]);}
  }
  // 8. dishwasher
  R('Dishwasher placement and door swing');
  for(const it of items.filter(i=>i.t==='DW')){
    const sinks=items.filter(s=>s.t==='SB');
    if(!sinks.length)push('warn','Dishwasher placement and door swing','Dishwasher has no sink','Place the dishwasher within 36" of a sink.',[it.id]);
    else{const P=itemPoly(it);const dmin=Math.min(...sinks.map(s=>polyDist(P,itemPoly(s)).d));if(dmin>36)push('warn','Dishwasher placement and door swing','Dishwasher is '+fmt(dmin)+' from the sink','NKBA: within 36" of the sink edge.',[it.id])}
  }
  // corner front collisions
  R('Corner door and drawer collisions');
  for(let j=0;j<n;j++){
    const occ=occupied(j,0,st.counterH-1);
    const own=occ.filter(o=>o.own),other=occ.filter(o=>!o.own);
    for(const o of own){
      const it=o.it; if(it.t==='F'||it.t==='PNL'||it.t==='HOOD')continue;
      const hasFront=it.t==='DW'||it.t==='MWD'||it.t==='RG'||fronts(it).some(f=>f.k==='door'||f.k==='drawer');
      if(!hasFront)continue;
      for(const x of other){
        if(x.it.wall==null)continue;
        const touchL=Math.abs(x.u1-o.u0)<0.6, touchR=Math.abs(x.u0-o.u1)<0.6;
        if(!touchL&&!touchR)continue;
        if(x.it.t==='F'||x.it.t==='PNL')continue;
        if(it.t==='BBC')continue;
        const sev=(it.t==='DW'||it.t==='RG'||it.t==='REF')?'err':'warn';
        push(sev,'Corner door and drawer collisions',(it.t==='DW'?'Dishwasher door':lab(it)+' fronts')+' will hit '+lab(x.it),'These meet at an inside corner with no filler, so doors, drawers and pulls collide. Add a 3" filler (min 1½") at the corner.',[it.id,x.it.id]);
      }
    }
  }
  // 9. range & hood
  R('Range hood height and coverage');
  for(const rg of items.filter(i=>i.t==='RG')){
    const hoods=items.filter(h=>h.t==='HOOD'&&h.wall===rg.wall&&Math.abs((h.x+h.w/2)-(rg.x+rg.w/2))<=4);
    const uppers=items.filter(w=>w.wall===rg.wall&&w.t!=='HOOD'&&isUpper(w)&&w.x<rg.x+rg.w-0.1&&w.x+w.w>rg.x+0.1);
    if(!hoods.length){
      push('warn','Range hood height and coverage','No hood over the '+lab(rg),'Add a ducted hood at least as wide as the range.',[rg.id]);
      for(const u of uppers){const c=u.z-st.counterH;if(c<30)push('err','Range hood height and coverage',lab(u)+' is only '+fmt(c)+' above the range','Combustible cabinets need 30" clearance above the cooking surface without a hood.',[u.id,rg.id])}
    } else {
      const h=hoods[0];const c=h.z-st.counterH;const gas=rg.fuel==='gas';
      if(gas){if(c<24)push('err','Range hood height and coverage','Hood is '+fmt(c)+' above a gas range','Most manufacturers require 30" minimum over gas (24" absolute floor). Raise the hood.',[h.id,rg.id]);else if(c<30)push('warn','Range hood height and coverage','Hood is '+fmt(c)+' above a gas range','Most gas hood installs call for 30"–36". Check the hood manual.',[h.id,rg.id]);else if(c>36)push('warn','Range hood height and coverage','Hood is high ('+fmt(c)+')','Capture drops above 36". Consider lowering or upsizing CFM.',[h.id]);}
      else{if(c<20)push('err','Range hood height and coverage','Hood is '+fmt(c)+' above the cooktop','Electric minimum is typically 20"–24".',[h.id,rg.id]);else if(c>30)push('warn','Range hood height and coverage','Hood is high ('+fmt(c)+')','Electric hoods typically mount 20"–30" above the cooktop.',[h.id])}
      if(h.w<rg.w)push('warn','Range hood height and coverage','Hood is narrower than the range','A '+h.w+'" hood over a '+rg.w+'" range lets smoke escape. Match or exceed range width.',[h.id,rg.id]);
      if(!S.utils.some(u=>u.type==='duct'&&u.wall===h.wall&&Math.abs(u.x-(h.x+h.w/2))<h.w/2+6))push('info','Range hood height and coverage','No duct marked above the hood','Pin the duct location on W'+(h.wall+1)+' or plan a new penetration.',[h.id]);
    }
  }
  // 10. work triangle
  R('Work triangle');
  const pt=it=>{if(!it)return null;const f=frame(it);return {x:f.o.x+f.U.x*it.w/2+f.V.x*it.d,y:f.o.y+f.U.y*it.w/2+f.V.y*it.d}};
  const sink=items.find(i=>i.t==='SB'),range=items.find(i=>i.t==='RG'),ref=items.find(i=>i.t==='REF');
  let tri=null;
  if(sink&&range&&ref){
    const A=pt(sink),B=pt(range),Cc=pt(ref);const l=[Math.hypot(A.x-B.x,A.y-B.y),Math.hypot(B.x-Cc.x,B.y-Cc.y),Math.hypot(Cc.x-A.x,Cc.y-A.y)];const sum=l[0]+l[1]+l[2];
    tri={pts:[A,B,Cc],legs:l,sum};
    const names=['Sink–range','Range–fridge','Fridge–sink'];
    l.forEach((v,k)=>{if(v<48)push('warn','Work triangle',names[k]+' leg is '+fmt(v,'ft'),'NKBA: each leg 4′–9′. A short leg crowds two work centers.',[]);else if(v>108)push('warn','Work triangle',names[k]+' leg is '+fmt(v,'ft'),'NKBA: each leg 4′–9′. Long legs mean extra steps.',[])});
    if(sum>312)push('warn','Work triangle','Triangle totals '+fmt(sum,'ft'),'NKBA: the three legs should total 26′ or less.',[]);
  }
  // 11. utilities vs fixtures
  R('Plumbing, gas and power at fixtures');
  const near=(it,types,slack)=>S.utils.filter(u=>types.includes(u.type)&&u.wall===it.wall&&u.x>=it.x-slack&&u.x<=it.x+it.w+slack);
  const nearestDist=(it,types)=>{const us=S.utils.filter(u=>types.includes(u.type)&&u.wall===it.wall);if(!us.length)return null;const c=it.x+it.w/2;return Math.min(...us.map(u=>Math.abs(u.x-c)))};
  const reloc={plumbing:0,elec:0,gas:0};
  for(const it of items){
    if(it.t==='SB'){
      if(it.wall==null)continue;
      const w=near(it,['water'],2),d=near(it,['drain'],2);
      if(!w.length||!d.length){reloc.plumbing++;const nd=nearestDist(it,['drain','water']);push('warn','Plumbing, gas and power at fixtures','Plumbing must move for the sink','No '+(!w.length&&!d.length?'supply or drain':!w.length?'supply':'drain')+' inside the '+lab(it)+'.'+(nd!=null?' Nearest stub is '+fmt(nd)+' from the sink centerline.':' None pinned on this wall.'),[it.id])}
    }
    if(it.t==='ISL'&&false){}
    if(it.t==='RG'&&it.wall!=null){
      const need=it.fuel==='gas'?['gas']:['v240'];
      if(!near(it,need,6).length){if(it.fuel==='gas')reloc.gas++;else reloc.elec++;push('warn','Plumbing, gas and power at fixtures',(it.fuel==='gas'?'Gas':'240V')+' supply must move for the range','No '+(it.fuel==='gas'?'gas stub':'240V circuit')+' within the range opening on W'+(it.wall+1)+'.',[it.id])}
    }
    if(it.t==='REF'&&it.wall!=null&&!near(it,['outlet','gfci'],0).length){reloc.elec++;push('warn','Plumbing, gas and power at fixtures','No receptacle behind the refrigerator','Add a dedicated 120V receptacle in the fridge opening.',[it.id])}
    if(it.t==='DW'&&it.wall!=null&&!near(it,['outlet','gfci'],24).length){reloc.elec++;push('info','Plumbing, gas and power at fixtures','No dishwasher circuit pinned','Add a receptacle or junction box in the adjacent sink base.',[it.id])}
    if(it.t==='MWD'&&it.wall!=null&&!near(it,['outlet','gfci'],0).length){reloc.elec++;push('info','Plumbing, gas and power at fixtures','No receptacle for the microwave drawer','Add a dedicated 20A receptacle in the opening.',[it.id])}
  }
  // hidden or blocked utilities
  R('Outlets, switches and registers not buried');
  for(const u of S.utils){
    if(UTIL[u.type]?.box)continue;
    for(const it of wallItems(u.wall)){
      if(u.x<it.x||u.x>it.x+it.w||u.z<it.z||u.z>it.z+it.h)continue;
      if(u.type==='vent'&&isBaseLevel(it))push('warn','Outlets, switches and registers not buried','Register behind '+lab(it),'HVAC register at '+fmt(u.x)+' would be buried. Relocate to a toe-kick diffuser.',[it.id],{reloc:'hvac'});
      else if((u.type==='outlet'||u.type==='gfci')&&!['REF','DW','MWD','RG','SB'].includes(it.t)&&!(it.t==='OV')){reloc.elec++;push(it.cls==='tall'||CAT[it.t].cls==='tall'?'warn':'info','Outlets, switches and registers not buried','Receptacle buried behind '+lab(it),'Receptacle at '+fmt(u.x)+' from left, '+fmt(u.z)+' AFF. Relocate it or cut it in.',[it.id])}
      else if(u.type==='sw'||u.type==='light')push('warn','Outlets, switches and registers not buried',UTIL[u.type].n+' behind '+lab(it),'At '+fmt(u.x)+' from left, '+fmt(u.z)+' AFF. Relocate before cabinets go in.',[it.id]);
    }
  }
  // 12. countertop receptacles
  R('Countertop receptacle spacing (NEC 210.52)');
  for(let j=0;j<n;j++){
    const row=baseRow(j).filter(it=>(hasCT(it)||it.t==='SB')&&!['VAN','VDB'].includes(it.t));
    const segs=[];let cur=null;
    for(const it of row){if(it.t==='SB'||!hasCT(it)){cur=null;continue}if(cur&&Math.abs(it.x-cur.b)<0.3)cur.b=it.x+it.w;else{cur={a:it.x,b:it.x+it.w};segs.push(cur)}}
    const outs=S.utils.filter(u=>(u.type==='gfci'||u.type==='outlet')&&u.wall===j&&u.z>=st.counterH&&u.z<=st.counterH+20);
    for(const s of segs){if(s.b-s.a<12)continue;let miss=null;for(let x=s.a;x<=s.b+0.01;x+=2){if(!outs.some(o=>Math.abs(o.x-x)<=24)){miss=x;break}}
      if(miss!=null){reloc.elec++;push('warn','Countertop receptacle spacing (NEC 210.52)','Countertop on W'+(j+1)+' needs a receptacle near '+fmt(miss),'Every point along a counter must be within 24" of a receptacle. Run '+fmt(s.a)+'–'+fmt(s.b)+'.',[])}}
    if(outs.some(o=>o.type==='outlet'))push('info','Countertop receptacle spacing (NEC 210.52)','Counter receptacles on W'+(j+1)+' need GFCI protection','All kitchen countertop receptacles must be GFCI protected.',[]);
  }
  // 13. upper cabinet height
  R('Upper cabinet height above counter');
  for(const it of items){if(it.t!=='W'||it.wall==null)continue;const c=it.z-st.counterH;
    const overCT=baseRow(it.wall).some(b=>hasCT(b)&&b.x<it.x+it.w&&b.x+b.w>it.x);
    if(overCT&&c<15)push('warn','Upper cabinet height above counter',lab(it)+' is '+fmt(c)+' above the counter','NKBA: 15" minimum between counter and wall cabinets (18" typical).',[it.id]);}

  // 14. bath planning
  const bathItems=items.filter(i=>['WC','VAN','VDB','TUB','TP','SHW'].includes(i.t));
  if(bathItems.length){
    R('Bath clearances (IRC / NKBA)');
    const segsFor=self=>{const sg=G.walls.map(w=>[w.a,w.b]);for(const O of items){if(O===self||O.z>=30||O.t==='MIR')continue;const P=itemPoly(O);if(P)P.forEach((p,k)=>sg.push([p,P[(k+1)%P.length]]))}return sg};
    const cast=(p,d,sg)=>{let m=1e9;for(const [a,b] of sg){const t=rayHit(p,d,a,b);if(t!=null&&t<m)m=t}return m};
    for(const it of bathItems){const f=frame(it);const sg=segsFor(it);const at=(u,v)=>({x:f.o.x+f.U.x*u+f.V.x*v,y:f.o.y+f.U.y*u+f.V.y*v});
      const front=cast(at(it.w/2,it.d+0.01),f.V,sg);
      if(it.t==='WC'){const c=at(it.w/2,it.d*0.6);const l=cast(c,{x:-f.U.x,y:-f.U.y},sg),r=cast(c,f.U,sg);const m=Math.min(l,r);
        if(m<15)push('err','Bath clearances (IRC / NKBA)','Toilet centerline only '+fmt(m)+' from the side','IRC requires 15" from toilet centerline to any wall, tub or vanity. NKBA recommends 16".',[it.id]);
        else if(m<16)push('warn','Bath clearances (IRC / NKBA)','Toilet centerline '+fmt(m)+' from the side','Meets the 15" code minimum; NKBA recommends 16"–18".',[it.id]);
        if(front<21)push('err','Bath clearances (IRC / NKBA)','Only '+fmt(front)+' in front of the toilet','IRC requires 21" clear in front of the bowl; NKBA recommends 30".',[it.id]);
        else if(front<30)push('warn','Bath clearances (IRC / NKBA)',fmt(front)+' in front of the toilet','Code minimum is 21"; NKBA recommends 30" clear in front.',[it.id]);}
      if(it.t==='VAN'||it.t==='VDB'){if(front<21)push('err','Bath clearances (IRC / NKBA)','Only '+fmt(front)+' in front of the vanity','IRC requires 21" clear in front of a lavatory; NKBA recommends 30".',[it.id]);else if(front<30)push('warn','Bath clearances (IRC / NKBA)',fmt(front)+' in front of the vanity','NKBA recommends 30" of clear floor space in front of the vanity.',[it.id]);
        if(it.t==='VAN'&&it.wall!=null){const basins=it.w>=60?[it.x+it.w*0.25,it.x+it.w*0.75]:[it.x+it.w/2];
          for(const bx of basins)if(!S.utils.some(u=>(u.type==='gfci'||u.type==='outlet')&&u.wall===it.wall&&Math.abs(u.x-bx)<=36&&u.z>=st.counterH)){reloc.elec++;push('warn','Bath clearances (IRC / NKBA)','No receptacle within 36" of the basin','NEC 210.52(D): a GFCI receptacle within 36" of each basin edge, on the wall beside or behind it.',[it.id]);break}
          if(!near(it,['water'],2).length||!near(it,['drain'],2).length){reloc.plumbing++;push('warn','Plumbing, gas and power at fixtures','Plumbing must move for the vanity','Supply and drain stubs are not inside the '+lab(it)+'.',[it.id])}}}
      if(it.t==='SHW'){const inside=Math.min(it.w,it.d)-1;if(inside<30)push('err','Bath clearances (IRC / NKBA)','Shower is only '+fmt(inside)+' inside','IRC minimum is 30" × 30" (900 sq in); NKBA recommends 36" × 36".',[it.id]);else if(inside<36)push('info','Bath clearances (IRC / NKBA)','Shower '+fmt(it.w)+' × '+fmt(it.d),'Meets code; NKBA recommends 36" × 36" interior.',[it.id]);
        if(front<30)push('warn','Bath clearances (IRC / NKBA)','Only '+fmt(front)+' in front of the shower entry','NKBA recommends 30" of clear floor space in front of the shower entry.',[it.id])}
      if((it.t==='TUB'||it.t==='TP')&&front<30)push('warn','Bath clearances (IRC / NKBA)','Only '+fmt(front)+' beside the tub','NKBA recommends 30" of clear floor space along the tub.',[it.id]);
    }
    if(items.some(i=>['WC','SHW','TUB','TP'].includes(i.t))&&!S.utils.some(u=>u.type==='fan'))push('info','Bath clearances (IRC / NKBA)','No exhaust fan pinned','Bathrooms with a tub, shower or toilet need mechanical exhaust (50 CFM intermittent) unless an operable window serves them.',[]);
  }
  // keep only square-on dimension lines, nearest per island side, for the plan overlay
  const shown=[];const bestSide={};
  for(const l of lines){
    if(l.force){shown.push(l);continue}
    const I=l.a&&l.a.wall==null?l.a:l.b&&l.b.wall==null?l.b:l.a;const f=frame(I);
    const dx=l.q.x-l.p.x,dy=l.q.y-l.p.y,L=Math.hypot(dx,dy)||1;const du=(dx*f.U.x+dy*f.U.y)/L,dv=(dx*f.V.x+dy*f.V.y)/L;
    if(Math.abs(du)<0.985&&Math.abs(dv)<0.985)continue;
    const key=I.id+(Math.abs(du)>0.985?(du>0?'u+':'u-'):(dv>0?'v+':'v-'));
    if(!bestSide[key]||bestSide[key].d>l.d)bestSide[key]=l;
  }
  shown.push(...Object.values(bestSide));
  const counts={err:0,warn:0,info:0};out.forEach(o=>counts[o.sev]++);
  const failed=new Set(out.filter(o=>o.sev!=='info').map(o=>o.rule));
  const passed=[...ran].filter(r=>!failed.has(r));
  out.sort((a,b)=>rank(b.sev)-rank(a.sev));
  return C_={list:out,byItem,counts,passed,lines:shown,tri,reloc};
}

