/* ---------- ELEVATIONS ---------- */
const ELDEFS=`<defs><pattern id="hatch" width="3" height="3" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><rect width="3" height="3" fill="#f2f3f1"/><line x1="0" y1="0" x2="0" y2="3" stroke="#9aa39e" stroke-width=".35"/></pattern>
  <pattern id="tile" width="6" height="3" patternUnits="userSpaceOnUse"><rect width="6" height="3" fill="#f4f2ed"/><path d="M0 3H6M3 0V1.5M0 1.5H6M6 1.5V3" stroke="#d6d1c6" stroke-width=".2" fill="none"/></pattern></defs>`;
function splashTop(st,uppersOver){const H=st.counterH;if(st.splash==='4')return H+4;if(st.splash==='full')return uppersOver.length?Math.min(...uppersOver):S.ceiling;return Math.min(H+st.backsplashH,...uppersOver)}
function renderElev(){
  const n=S.walls.length;const isl=S.items.filter(i=>i.wall==null);
  if(typeof UI.elevWall==='string'&&!isl.some(i=>UI.elevWall.startsWith(i.id)))UI.elevWall=0;
  const views=S.walls.map((w,i)=>[i,'W'+(i+1)]).concat(isl.flatMap((it,k)=>[[it.id+':front','Island'+(isl.length>1?' '+(k+1):'')+' front'],[it.id+':back','Island'+(isl.length>1?' '+(k+1):'')+' back']]));
  const E=typeof UI.elevWall==='string'?islandElev(isl.find(i=>UI.elevWall.startsWith(i.id)),UI.elevWall.split(':')[1]):elevData(UI.elevWall);
  $('#v-elevations').innerHTML=`<div class="stack">
    <div class="row" style="justify-content:space-between"><div><div class="eyebrow">Technical elevations &amp; sections</div><h2>${esc(E.title)}</h2></div>
      <div class="row"><div class="seg" role="group" aria-label="View" style="flex-wrap:wrap">${views.map(([k,l])=>`<button data-act="elev" data-v="${k}" aria-pressed="${String(k)===String(UI.elevWall)}">${l}</button>`).join('')}</div>
      <button class="btn sm" data-act="dlsvg">Save drawing (.svg)</button><button class="btn sm" data-act="dxf">CAD export (.dxf)</button></div></div>
    <div class="elwrap"><svg class="el" id="elsvg" viewBox="${E.vb}" xmlns="http://www.w3.org/2000/svg">${E.s}</svg></div>
    <div class="grid2">
      <div class="card stack"><h3>Rough-in schedule</h3><p class="hint">Dimensions to center of box or stub. Hand this to the electrician and plumber before drywall.</p>
        <div class="tw"><table class="t"><thead><tr><th>#</th><th>Item</th><th class="n">From left</th><th class="n">From right</th><th class="n">AFF</th><th>Note</th></tr></thead><tbody>${E.rough||'<tr><td colspan="6" class="muted">No rough-ins on this view.</td></tr>'}</tbody></table></div></div>
      <div class="card stack"><h3>Cabinet schedule</h3>
        <div class="tw"><table class="t"><thead><tr><th>SKU</th><th>Type</th><th class="n">From left</th><th class="n">Width</th><th class="n">Bottom</th><th class="n">Top</th></tr></thead><tbody>${E.cabs||'<tr><td colspan="6" class="muted">No cabinets on this view.</td></tr>'}</tbody></table></div></div>
    </div></div>`;
}
function elevData(j){
  const G=geom(),w=G.walls[j],st=S.settings,H=S.ceiling,L=w.len;
  const y=z=>H-z;const fs=clamp(L/62,2.6,4.2);
  let s=ELDEFS;
  s+=`<rect class="wbg" x="0" y="0" width="${L}" height="${H}"/>`;
  const mine=S.items.filter(it=>it.wall===j);
  // backsplash
  for(const it of mine)if(hasCT(it)||it.t==='RG'){const top=splashTop(st,mine.filter(u=>isUpper(u)&&u.t!=='HOOD'&&u.t!=='MIR'&&u.x<it.x+it.w&&u.x+u.w>it.x).map(u=>u.z));s+=`<rect class="bs" x="${it.x}" y="${y(top)}" width="${it.w}" height="${top-st.counterH}"/>`}
  // openings & soffits
  for(const u of S.utils.filter(u=>u.wall===j&&UTIL[u.type]?.box)){
    const t=+u.trim||0;
    if(u.type==='soffit')s+=`<rect class="sof" x="${u.x}" y="0" width="${u.w}" height="${u.h}"/>`;
    else{const z0=u.type==='door'?0:u.z;if(t)s+=`<rect class="trim" x="${u.x-t}" y="${y(u.z+u.h+t)}" width="${u.w+2*t}" height="${u.h+t+(u.type==='door'?0:t)}"/>`;
      s+=`<rect class="win" x="${u.x}" y="${y(u.z+u.h)}" width="${u.w}" height="${u.h}" ${u.type==='door'?'style="fill:#fff"':''}/>`;
      if(u.type==='window')s+=`<line class="dl" x1="${u.x+u.w/2}" y1="${y(u.z+u.h)}" x2="${u.x+u.w/2}" y2="${y(u.z)}"/>`;
      s+=`<text x="${u.x+u.w/2}" y="${y(u.z+u.h/2)}" text-anchor="middle" style="font-size:${fs}px">${u.type==='door'?'OPENING':'WINDOW'} ${esc(fmt(u.w))}×${esc(fmt(u.h))}</text>`}
  }
  // returns from adjacent walls (side profiles)
  for(const it of S.items){if(it.wall===j)continue;const P=itemPoly(it);if(!P)continue;let u0=1e9,u1=-1e9,v0=1e9;for(const p of P){const t=toWall(w,p);u0=Math.min(u0,t.u);u1=Math.max(u1,t.u);v0=Math.min(v0,t.v)}
    if(v0>2||u1<=0||u0>=L)continue;u0=Math.max(0,u0);u1=Math.min(L,u1);s+=`<rect class="side" x="${u0}" y="${y(it.z+it.h)}" width="${u1-u0}" height="${it.h}"/>`}
  // items
  const sorted=[...mine].sort((a,b)=>a.z-b.z);
  for(const it of sorted)s+=elevItem(it,y,fs);
  // countertops
  {const th=st.edge==='mitered'?3:st.ctThick;for(const it of mine)if(hasCT(it))s+=`<rect class="ctop" x="${it.x}" y="${y(st.counterH)}" width="${it.w}" height="${th}"/>`}
  // utilities
  const us=S.utils.filter(u=>u.wall===j&&!UTIL[u.type]?.box).sort((a,b)=>a.x-b.x);
  const placed=[];
  us.forEach((u,k)=>{let tx=u.x,tz=u.z;for(let g=0;g<6;g++){if(placed.some(p=>Math.abs(p.x-tx)<fs*1.7&&Math.abs(p.z-tz)<fs*1.7))tx+=fs*1.8;else break}placed.push({x:tx,z:tz});
    s+=`<line class="cl" x1="${u.x}" y1="${y(u.z)}" x2="${u.x}" y2="${H+4}"/>${tx!==u.x?`<line class="cl" x1="${u.x}" y1="${y(u.z)}" x2="${tx}" y2="${y(tz)}" style="stroke-dasharray:none"/><circle cx="${u.x}" cy="${y(u.z)}" r=".7" fill="#b53a22"/>`:''}<circle class="ut" cx="${tx}" cy="${y(tz)}" r="${fs*0.75}"/><text class="utt" x="${tx}" y="${y(tz)+fs*0.33}" text-anchor="middle" style="font-size:${fs*0.8}px">${k+1}</text>`});
  // dimension chains
  const chain=(edges,yy,lbl)=>{edges=[...new Set(edges.map(e=>r2(e)))].sort((a,b)=>a-b);let o=`<line class="dl" x1="${edges[0]}" y1="${yy}" x2="${edges[edges.length-1]}" y2="${yy}"/>`;
    edges.forEach(e=>o+=`<line class="dl" x1="${e}" y1="${yy-2}" x2="${e}" y2="${yy+2}"/><line class="dl" x1="${e-1.2}" y1="${yy+1.2}" x2="${e+1.2}" y2="${yy-1.2}"/>`);
    for(let k=0;k<edges.length-1;k++){const a=edges[k],b=edges[k+1];if(b-a<0.4)continue;const t=fmt(b-a);const small=(b-a)<t.length*fs*0.62;o+=`<text x="${(a+b)/2}" y="${yy-(small&&k%2?-fs*1.1:1.2)}" text-anchor="middle" style="font-size:${small?fs*0.72:fs}px">${esc(t)}</text>`}
    if(lbl)o+=`<text x="-3" y="${yy+fs*0.35}" text-anchor="end" style="font-size:${fs*0.8}px;fill:#6b777d">${lbl}</text>`;return o};
  const be=[0,L];mine.filter(isBaseLevel).forEach(it=>be.push(it.x,it.x+it.w));
  const ue=[0,L];mine.filter(it=>it.z>=st.counterH).forEach(it=>ue.push(it.x,it.x+it.w));
  s+=chain(be,H+10,'BASE');s+=chain(ue,-10,'UPPER');s+=chain([0,L],H+22,'');
  s+=`<text x="${L/2}" y="${H+22-1.2}" text-anchor="middle" style="font-size:${fs*1.1}px;font-weight:600">${esc(fmt(L,'ft'))} (${esc(fmt(L))})</text>`;
  // vertical
  const vz=[0,st.toe,st.counterH,st.wallBottom,st.wallBottom+30,H];const vx=L+10;
  s+=`<line class="dl" x1="${vx}" y1="0" x2="${vx}" y2="${H}"/>`;
  [...new Set(vz)].sort((a,b)=>a-b).forEach((z,k,arr)=>{s+=`<line class="dl" x1="${vx-2}" y1="${y(z)}" x2="${vx+2}" y2="${y(z)}"/><line class="dl" x1="${vx-1.2}" y1="${y(z)+1.2}" x2="${vx+1.2}" y2="${y(z)-1.2}"/>`;
    if(k){const a=arr[k-1];s+=`<text x="${vx+2.5}" y="${y((a+z)/2)+fs*0.35}" style="font-size:${fs*0.85}px">${esc(fmt(z-a))}</text>`}});
  s+=`<text x="${vx+2.5}" y="${y(H)-2}" style="font-size:${fs*0.8}px;fill:#6b777d">CLG ${esc(fmt(H))}</text>`;
  s+=`<text x="0" y="-22" style="font-size:${fs*1.5}px;font-weight:600">ELEVATION W${j+1}${S.walls[j].note?' — '+esc(S.walls[j].note.toUpperCase()):''}</text>`;
  const vb=`-30 -32 ${L+80} ${H+66}`;const title='Wall '+(j+1)+' elevation';
  const rough=us.map((u,k)=>`<tr><td class="mono">${k+1}</td><td>${UTIL[u.type].n}</td><td class="n">${fmt(u.x)}</td><td class="n">${fmt(L-u.x)}</td><td class="n">${fmt(u.z)}</td><td>${esc(u.note||'')}</td></tr>`).join('');
  const cabs=mine.filter(i=>!CAT[i.t].app||true).sort((a,b)=>a.z-b.z||a.x-b.x).map(it=>`<tr><td class="mono">${esc(CAT[it.t].sku(it))}</td><td>${esc(CAT[it.t].name)}</td><td class="n">${fmt(it.x)}</td><td class="n">${fmt(it.w)}</td><td class="n">${fmt(it.z)}</td><td class="n">${fmt(it.z+it.h)}</td></tr>`).join('');
  return {s,vb,rough,cabs,title,L,H};
}
/* island front (cabinet side) or back (seating side) elevation, as a section through the room */
function islandElev(it,side){
  const st=S.settings,H=S.ceiling,L=it.w,y=z=>H-z,fs=clamp(L/50,2.6,4);const top=st.counterH,T=st.edge==='mitered'?3:st.ctThick;
  let s=ELDEFS+`<rect class="wbg" x="0" y="0" width="${L}" height="${H}" style="fill:#fff;stroke:none"/><line class="dl" x1="-20" y1="${H}" x2="${L+20}" y2="${H}" style="stroke-width:.6"/>`;
  const ends=st.waterfall?st.ctThick:0;
  if(side==='front'){
    s+=`<rect class="toe" x="${1+ends}" y="${y(st.toe)}" width="${L-2-2*ends}" height="${st.toe}"/><rect class="bx" x="${1+ends}" y="${y(top-st.ctThick)}" width="${L-2-2*ends}" height="${top-st.ctThick-st.toe}"/>`;
    for(const f of fronts(it)){const fx=f.x+ends;s+=`<rect class="fr" x="${fx}" y="${y(f.y+f.h)}" width="${f.w}" height="${f.h}"/>`;if(f.h>8)s+=`<rect class="fr" x="${fx+2.5}" y="${y(f.y+f.h-2.5)}" width="${f.w-5}" height="${f.h-5}" style="fill:none"/>`;
      if(f.k==='drawer')s+=`<line class="dl" x1="${fx+f.w/2-3}" y1="${y(f.y+f.h/2)}" x2="${fx+f.w/2+3}" y2="${y(f.y+f.h/2)}" stroke-width=".7"/>`;
      if(f.k==='door'){const hx=f.hinge==='R'?fx+f.w:fx,ox=f.hinge==='R'?fx:fx+f.w;s+=`<path class="sw" d="M${ox} ${y(f.y+f.h)} L${hx} ${y(f.y+f.h/2)} L${ox} ${y(f.y)}"/>`}}
  } else {
    s+=`<rect class="fr" x="${1+ends}" y="${y(top-st.ctThick)}" width="${L-2-2*ends}" height="${top-st.ctThick}" style="fill:#f7f6f2"/>`;
    for(let x=1+ends+6;x<L-1-ends;x+=6)s+=`<line class="dl" x1="${x}" y1="${y(top-st.ctThick)}" x2="${x}" y2="${y(0)}" style="stroke:#c9c5bb"/>`;
    const n=it.seat>=9?Math.floor(L/24):0;for(let k=0;k<n;k++){const cx=(k+0.5)*L/n;s+=`<rect x="${cx-7}" y="${y(26)}" width="14" height="2" fill="#3a4247"/><line class="dl" x1="${cx-6}" y1="${y(24)}" x2="${cx-7}" y2="${y(0)}" stroke-width=".7"/><line class="dl" x1="${cx+6}" y1="${y(24)}" x2="${cx+7}" y2="${y(0)}" stroke-width=".7"/><line class="dl" x1="${cx-6.5}" y1="${y(9)}" x2="${cx+6.5}" y2="${y(9)}"/>`}
    s+=`<text x="${L/2}" y="${y(30)}" text-anchor="middle" style="font-size:${fs}px">SEATING · ${n} @ 24" · ${fmt(it.seat||0)} OVERHANG</text>`;
  }
  s+=`<rect class="ctop" x="0" y="${y(top)}" width="${L}" height="${T}"/>`;
  if(st.waterfall)s+=`<rect class="ctop" x="0" y="${y(top)}" width="${st.ctThick}" height="${top}"/><rect class="ctop" x="${L-st.ctThick}" y="${y(top)}" width="${st.ctThick}" height="${top}"/>`;
  const np=Math.max(1,Math.round(L/30));for(let k=0;k<np;k++){const px=(k+0.5)*L/np;s+=`<line class="dl" x1="${px}" y1="0" x2="${px}" y2="${y(70)}"/><path class="app" d="M${px-2.5} ${y(72)} L${px+2.5} ${y(72)} L${px+5} ${y(66)} L${px-5} ${y(66)} Z"/>`}
  const edges=[0,L];if(side==='front')fronts(it).forEach(f=>{edges.push(r2(f.x-1/16+ends),r2(f.x+f.w+1/16+ends))});
  const E=[...new Set(edges)].sort((a,b)=>a-b);s+=`<line class="dl" x1="0" y1="${H+10}" x2="${L}" y2="${H+10}"/>`;E.forEach(e=>s+=`<line class="dl" x1="${e}" y1="${H+8}" x2="${e}" y2="${H+12}"/>`);
  for(let k=0;k<E.length-1;k++){const a=E[k],b=E[k+1];if(b-a<3)continue;s+=`<text x="${(a+b)/2}" y="${H+8.8}" text-anchor="middle" style="font-size:${fs*0.85}px">${esc(fmt(b-a))}</text>`}
  s+=`<text x="${L/2}" y="${H+22}" text-anchor="middle" style="font-size:${fs*1.1}px;font-weight:600">${esc(fmt(L,'ft'))} × ${esc(fmt(it.d))} TOP · ${esc(EDGE[st.edge][0].toUpperCase())} EDGE${st.waterfall?' · WATERFALL ENDS':''}</text>`;
  s+=`<line class="dl" x1="${L+10}" y1="${y(0)}" x2="${L+10}" y2="${y(top)}"/><text x="${L+12}" y="${y(top/2)}" style="font-size:${fs*0.85}px">${esc(fmt(top))}</text>`;
  s+=`<text x="0" y="${H-90}" style="font-size:${fs*1.4}px;font-weight:600">ISLAND ${side==='front'?'FRONT (CABINET SIDE)':'BACK (SEATING SIDE)'} — SECTION</text>`;
  const cabs=splitIsland(it).units.map((u,k)=>`<tr><td class="mono">${(it.style==='doors'?'B':'DB')+u}</td><td>Island ${it.style==='doors'?'base':'drawer base'}</td><td class="n">${fmt(1.75+ends+splitIsland(it).units.slice(0,k).reduce((a,b)=>a+b,0))}</td><td class="n">${fmt(u)}</td><td class="n">0"</td><td class="n">${fmt(top-st.ctThick)}</td></tr>`).join('');
  return {s,vb:`-24 ${H-100} ${L+56} 128`,rough:'',cabs,title:'Island '+(side==='front'?'front':'back')+' section',L,H};
}
function elevItem(it,y,fs){
  const st=S.settings,c=CAT[it.t],x=it.x,w=it.w,z=it.z,h=it.h;let s='';
  const R=(cls,x0,z0,ww,hh,extra='')=>`<rect class="${cls}" x="${x0}" y="${y(z0+hh)}" width="${ww}" height="${hh}" ${extra}/>`;
  const label=(t,zz)=>`<text x="${x+w/2}" y="${y(zz)}" text-anchor="middle" style="font-size:${Math.min(fs*0.8,w/5)}px">${esc(t)}</text>`;
  if(c.cls==='filler'){s+=R('fr',x,z,w,h,'style="fill:#eef0ed"');return s}
  if(it.t==='WC'){const tw=Math.min(w,19);s+=R('app',x+(w-tw)/2,15,tw,15,'rx="1"');s+=R('app',x+(w-tw)/2-0.3,30,tw+0.6,1);s+=`<path class="app" d="M${x+2} ${y(16)} L${x+w-2} ${y(16)} L${x+w-4.5} ${y(9)} L${x+w/2+4} ${y(0)} L${x+w/2-4} ${y(0)} L${x+4.5} ${y(9)} Z"/>`;s+=label('WC · 12" ROUGH',33);return s}
  if(it.t==='TUB'||it.t==='TP'){s+=R(it.t==='TP'?'bx':'app',x,0,w,h);if(it.t==='TP')s+=R('ctop',x,h,w,1.5);s+=`<line class="dl" x1="${x+4}" y1="${y(h-3)}" x2="${x+w-4}" y2="${y(h-3)}"/>`;s+=label(it.t==='TP'?'TUB DECK':'TUB '+w+'"',h/2);return s}
  if(it.t==='SHW'){s+=R('app',x,0,w,3);s+=R('win',x,3,w,h-3,'style="fill:#eef6fb;fill-opacity:.6"');s+=`<line class="dl" x1="${x+w/2}" y1="${y(72)}" x2="${x+w/2}" y2="${y(80)}"/><rect class="dark" x="${x+w/2-4}" y="${y(80)}" width="8" height="0.8"/>`;s+=label('SHOWER '+w+'×'+it.d,40);return s}
  if(it.t==='MIR'){s+=R('bx',x,z,w,h);s+=R('win',x+1,z+1,w-2,h-2);s+=R('app',x+w*0.15,z+h+2.5,w*0.7,1.5);s+=label('MIRROR',z+h/2);return s}
  if(it.t==='CUST'){s+=R('app',x,z,w,h,'style="stroke-dasharray:2 1"');s+=label((it.label||'MODEL').toUpperCase(),z+h/2);return s}
  if(it.t==='HOOD'){const top=it.z+h;s+=`<path class="app" d="M${x} ${y(z)} L${x+w} ${y(z)} L${x+w*0.68} ${y(top)} L${x+w*0.32} ${y(top)} Z"/>`;s+=R('app',x+w*0.32,top,w*0.36,Math.max(0,S.ceiling-top));s+=R('dark',x+1,z,w-2,1);s+=label('HOOD '+w+'"',z+h*0.45);return s}
  if(it.t==='RG'){s+=R('app',x,0,w,h);s+=R('dark',x,h-1,w,1);s+=R('app',x+0.5,h-5,w-1,3.5);for(let k=0;k<(w>=36?6:4);k++){const kx=x+3+k*(w-6)/((w>=36?6:4)-1);s+=`<circle cx="${kx}" cy="${y(h-3.2)}" r="0.9" fill="#3a4247"/>`}
    s+=R('dark',x+2.5,8,w-5,15,'rx="1"');s+=R('app',x+2.5,4.5,w-5,3);s+=`<line class="dl" x1="${x+4}" y1="${y(25.5)}" x2="${x+w-4}" y2="${y(25.5)}" stroke-width="1"/>`;s+=label('RANGE '+w+'" '+(it.fuel==='gas'?'GAS':'ELEC'),27.5);return s}
  if(it.t==='DW'||it.t==='MWD'){s+=R('toe',x,0,w,st.toe);s+=R('app',x+1/16,st.toe,w-1/8,h-st.toe);s+=R('dark',x+1/16,h-4,w-1/8,4);s+=`<line class="dl" x1="${x+3}" y1="${y(h-6)}" x2="${x+w-3}" y2="${y(h-6)}" stroke-width=".8"/>`;s+=label(it.t==='DW'?'DW':'MW DRAWER',st.toe+6);return s}
  if(it.t==='REF'){s+=R('app',x,0,w,h);const fz=h*0.35;s+=`<line class="dl" x1="${x}" y1="${y(fz)}" x2="${x+w}" y2="${y(fz)}"/><line class="dl" x1="${x+w/2}" y1="${y(h)}" x2="${x+w/2}" y2="${y(fz)}"/>`;
    s+=`<line class="dl" x1="${x+w/2-2}" y1="${y(h-8)}" x2="${x+w/2-2}" y2="${y(fz+16)}" stroke-width=".9"/><line class="dl" x1="${x+w/2+2}" y1="${y(h-8)}" x2="${x+w/2+2}" y2="${y(fz+16)}" stroke-width=".9"/><line class="dl" x1="${x+6}" y1="${y(fz-3)}" x2="${x+w-6}" y2="${y(fz-3)}" stroke-width=".9"/>`;s+=label('REF '+w+'"',fz/2);return s}
  // cabinets
  const hasToe=isBaseLevel(it);
  if(hasToe)s+=R('toe',x,0,w,st.toe);
  s+=R('bx',x,hasToe?st.toe:z,w,hasToe?h-st.toe:h);
  for(const f of fronts(it)){
    const fx=x+f.x,fz=z+f.y;
    if(f.k==='blind'){s+=R('fr',fx,fz,f.w,f.h,'style="fill:#f4f5f3"');continue}
    if(f.k==='oven'){s+=R('app',fx,fz,f.w,f.h);s+=R('dark',fx+2,fz+4,f.w-4,f.h-10);continue}
    s+=R('fr',fx,fz,f.w,f.h);
    if(f.h>8&&f.w>6)s+=R('fr',fx+2.5,fz+2.5,f.w-5,f.h-5,'style="fill:none"');
    if(f.k==='door'){const hx=f.hinge==='R'?fx+f.w:fx,ox=f.hinge==='R'?fx:fx+f.w;s+=`<path class="sw" d="M${ox} ${y(fz+f.h)} L${hx} ${y(fz+f.h/2)} L${ox} ${y(fz)}"/>`;
      const px=f.hinge==='R'?fx+1.6:fx+f.w-1.6;const pz=isUpper(it)?fz+2:fz+f.h-7;s+=`<line class="dl" x1="${px}" y1="${y(pz)}" x2="${px}" y2="${y(pz+5)}" stroke-width=".7"/>`}
    if(f.k==='drawer'){s+=`<line class="dl" x1="${fx+f.w/2-3}" y1="${y(fz+f.h/2)}" x2="${fx+f.w/2+3}" y2="${y(fz+f.h/2)}" stroke-width=".7"/>`}
  }
  s+=`<text x="${x+w/2}" y="${y((hasToe?st.toe:z))-1}" text-anchor="middle" style="font-size:${Math.min(fs*0.7,w/4.2)}px;fill:#55626a">${esc(c.sku(it))}</text>`;
  return s;
}

