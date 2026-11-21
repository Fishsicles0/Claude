/* ---------- MEASURE ---------- */
function renderMeasure(){
  const G=geom(),n=S.walls.length;let seq=0;
  const L=(bind,val,label,extra='')=>`<input class="in len" id="m-${bind.replace(/\W/g,'_')}" data-bind="${bind}" data-kind="len" data-seq="${seq++}" aria-label="${esc(label)}" value="${val==null?'':esc(fmt(val,'ft'))}" inputmode="decimal" ${extra}>`;
  const wallRows=S.walls.map((w,i)=>{
    const last=i===n-1;const g=G.walls[i];
    return `<tr><td><b>W${i+1}</b></td><td style="min-width:130px">${L('walls.'+i+'.len',w.len,'Wall '+(i+1)+' length','data-min="12"')}</td>
      <td class="n pro">${last?`<span title="Computed from the other walls">${fmt(g.len,'ft')}</span>`:'<span class="muted">—</span>'}</td>
      <td style="min-width:160px"><input class="in" id="m-note${i}" data-bind="walls.${i}.note" data-kind="text" value="${esc(w.note||'')}" placeholder="Note" aria-label="Wall ${i+1} note"></td>
      <td><button class="btn ghost sm danger" data-act="delwall" data-i="${i}" ${n<=3?'disabled':''} aria-label="Delete wall ${i+1}">✕</button></td></tr>`}).join('');
  const cornerRows=S.corners.map((c,i)=>{
    const g=G.corners[i],m=cornerMeasured(i),hasD=cornerHasDiag(i),dev=g-90;
    const st=Math.abs(dev)<0.15?'<span class="pill p-ok">Square</span>':`<span class="pill ${Math.abs(dev)>0.6?'p-err':'p-warn'}">${dev>0?'Open':'Closed'} ${Math.abs(dev).toFixed(2)}°</span>`;
    const constructed=i<n-2;
    return `<tr><td><b>C${i+1}</b><div class="muted" style="font-size:11px">W${i+1}→W${(i+1)%n+1}</div></td>
      <td style="min-width:80px">${constructed&&!hasD?`<input class="in mono" id="m-ang${i}" data-bind="corners.${i}.ang" data-kind="num" value="${c.ang??90}" aria-label="Corner ${i+1} angle" inputmode="decimal">`:`<span class="mono">${(constructed?m:g).toFixed(2)}°</span>`}</td>
      <td style="min-width:90px">${L('corners.'+i+'.a',c.a,'Corner '+(i+1)+' leg A','data-null placeholder="36 in"')}</td>
      <td style="min-width:90px">${L('corners.'+i+'.b',c.b,'Corner '+(i+1)+' leg B','data-null placeholder="36 in"')}</td>
      <td style="min-width:100px">${L('corners.'+i+'.c',c.c,'Corner '+(i+1)+' diagonal','data-null placeholder="diag"')}</td>
      <td class="n">${hasD?m.toFixed(2)+'°':'—'}</td><td>${st}</td>
      <td class="n">${Math.abs(dev)<0.15?'—':fmt(Math.abs(frontLoss(g,24)))}</td></tr>`}).join('');
  const meas=+S.walls[n-1].len,cl=Math.abs(G.walls[n-1].len-meas);
  const clPill=!(meas>0)?'<span class="pill p-info">Enter W'+n+' to check</span>':cl<=0.25?'<span class="pill p-ok">Closes within '+fmt(cl)+'</span>':cl<=1?'<span class="pill p-warn">Off by '+fmt(cl)+'</span>':'<span class="pill p-err">Off by '+fmt(cl)+'</span>';
  const utilRows=S.utils.map(u=>{const T=UTIL[u.type]||UTIL.other;return `<tr>
    <td><select class="in" id="u-t-${u.id}" data-bind="ut.${u.id}.type" data-kind="text" aria-label="Type">${Object.entries(UTIL).map(([k,v])=>`<option value="${k}" ${k===u.type?'selected':''}>${v.n}</option>`).join('')}</select></td>
    <td><select class="in" id="u-w-${u.id}" data-bind="ut.${u.id}.wall" data-kind="int" aria-label="Wall">${S.walls.map((w,i)=>`<option value="${i}" ${i===u.wall?'selected':''}>W${i+1}</option>`).join('')}</select></td>
    <td style="min-width:96px">${L('ut.'+u.id+'.x',u.x,T.n+' from left')}</td>
    <td style="min-width:90px">${L('ut.'+u.id+'.z',u.z,T.n+(T.box?' bottom height':' height to center'))}</td>
    <td style="min-width:90px">${T.box?L('ut.'+u.id+'.w',u.w,T.n+' width'):'<span class="muted">—</span>'}</td>
    <td style="min-width:90px">${T.box?L('ut.'+u.id+'.h',u.h,T.n+' height'):'<span class="muted">—</span>'}</td>
    <td style="min-width:80px">${u.type==='window'||u.type==='door'?L('ut.'+u.id+'.trim',u.trim,'Casing width'):'<span class="muted">—</span>'}</td>
    <td style="min-width:120px"><input class="in" id="u-n-${u.id}" data-bind="ut.${u.id}.note" data-kind="text" value="${esc(u.note||'')}" aria-label="Note"></td>
    <td><button class="btn ghost sm danger" data-act="delutil" data-id="${u.id}" aria-label="Delete">✕</button></td></tr>`}).join('');
  $('#v-measure').innerHTML=`
  <div class="mgrid">
    <div class="stack">
      <div class="card stack">
        <div class="row" style="justify-content:space-between"><div><div class="eyebrow">Step 1</div><h2>Walls of the room</h2></div>
          <div class="row"><button class="btn sm" data-act="tmpl" data-v="rect">Start as a rectangle</button><button class="btn sm pro" data-act="tmpl" data-v="L">L-shaped room</button><button class="btn sm" data-act="addwall">+ Add a wall</button></div></div>
        <p class="hint">Walk the room clockwise starting from the left end of the main cabinet wall. Measure each wall at counter height, where cabinets actually land. The last wall is computed from the others, so its measured length becomes your closure check.</p>
        <div class="row">
          <label class="f" style="width:170px">Ceiling height ${L('ceiling',S.ceiling,'Ceiling height')}</label>
          <label class="f" style="flex:1;min-width:160px">Client <input class="in" id="m-client" data-bind="client" data-kind="text" value="${esc(S.client||'')}"></label>
          <label class="f" style="flex:2;min-width:200px">Site address <input class="in" id="m-addr" data-bind="address" data-kind="text" value="${esc(S.address||'')}"></label>
        </div>
        <div class="tw"><table class="t"><thead><tr><th>Wall</th><th>Length you measured</th><th class="n pro">Calculated</th><th>Note (optional)</th><th></th></tr></thead><tbody>${wallRows}</tbody></table></div>
        <div class="row">${clPill}<span class="hint">Room area ${(G.area/144).toFixed(1)} sq ft · perimeter ${fmt(G.walls.reduce((s,w)=>s+w.len,0),'ft')}</span></div>
      </div>
      <details class="card stack" ${UI.mode==='full'?'open':''}>
        <summary><div><div class="eyebrow">Optional</div><h3>Check if the corners are square</h3></div></summary>
        <p class="hint">At each corner, mark A along the first wall and B along the second (36" each is typical), then shoot the diagonal C between the marks. A square corner at 36/36 reads ${fmt(Math.sqrt(2)*36)}. The angle feeds the room shape and the scribe allowance cabinets need at that corner.</p>
        <div class="tw"><table class="t"><thead><tr><th>Corner</th><th>Angle</th><th>Leg A</th><th>Leg B</th><th>Diagonal C</th><th class="n">From diag</th><th>Status</th><th class="n">Scribe @ 24"</th></tr></thead><tbody>${cornerRows}</tbody></table></div>
      </details>
      <div class="card stack">
        <div class="row" style="justify-content:space-between"><div><div class="eyebrow">Step 1b</div><h3>Windows, doors, outlets &amp; pipes</h3></div>
          <div class="row"><select class="in" id="addUtilType" style="width:auto" aria-label="Type to add">${Object.entries(UTIL).map(([k,v])=>`<option value="${k}">${v.n}</option>`).join('')}</select><button class="btn sm pri" data-act="addutil">+ Add it</button></div></div>
        <p class="hint">Positions run from the left end of the wall as you face it. Points (outlets, stubs, registers) are measured to their center; windows, doors and soffits use the bottom-left corner of the opening, and casing width is checked against cabinets.</p>
        <div class="tw"><table class="t"><thead><tr><th>What</th><th>Wall</th><th>From left end</th><th>Height off floor</th><th>Width</th><th>Height</th><th>Trim width</th><th>Note</th><th></th></tr></thead><tbody>${utilRows||'<tr><td colspan="9" class="muted">Nothing pinned yet. Add outlets, supply and drain stubs, gas, vents, windows and doors.</td></tr>'}</tbody></table></div>
      </div>
    </div>
    <div class="stack" style="position:sticky;top:110px">
      <div class="card stack">
        <div class="eyebrow">Laser measure (optional)</div>
        <div class="laser"><span class="dot ${LASER.dev?'on':''}"></span><div style="flex:1;min-width:160px"><b>${LASER.dev?esc(LASER.name)+' connected':'No laser connected'}</b><div class="hint">${LASER.dev?'Tap any measurement field, then fire the laser. The value lands and focus jumps to the next field.':'Bluetooth: Leica DISTO (BLE) and Bosch GLM (beta).'}</div></div>
          <button class="btn ${LASER.dev?'':'pri'}" data-act="laser">${LASER.dev?'Reconnect':'Connect laser'}</button></div>
        <p class="hint"><b>Keyboard (HID) mode</b> works on every device, including iPhone: put the DISTO in keyboard mode, tap a field, and fire. Values like 3.457 m, 136-1/8" or 11' 4" are all understood. Press Enter to jump to the next field.</p>
      </div>
      ${aiImportCardHTML()}
      <div class="card stack">
        <div class="row" style="justify-content:space-between"><h3>Live shell</h3><span class="hint">${n} walls · ${fmt(G.bbox.w,'ft')} × ${fmt(G.bbox.h,'ft')}</span></div>
        <svg class="mini" id="miniPlan" viewBox="0 0 10 10" aria-label="Plan preview">${planInner(true)}</svg>
        <button class="btn pri" data-act="tab" data-v="design">Next: place cabinets →</button>
      </div>
    </div>
  </div>`;
  fitView($('#miniPlan'),true);
  $$('#v-measure input[data-kind="len"]').forEach(i=>i.addEventListener('focus',()=>{LASER.lastInput=i;$$('.laser-target').forEach(x=>x.classList.remove('laser-target'));if(LASER.dev)i.classList.add('laser-target')}));
}

/* ---------- PLAN (SVG) ---------- */
function planInner(mini){
  const G=geom(),C=checks(),st=S.settings;const Y=y=>-y;
  let s='';
  const pts=G.P.map(p=>p.x+','+Y(p.y)).join(' ');
  s+=`<polygon class="pl-floor" points="${pts}"/>`;
  // walls
  for(const w of G.walls){const T=4.5;const o={x:-w.n.x*T,y:-w.n.y*T};
    s+=`<polygon class="pl-wall" points="${w.a.x},${Y(w.a.y)} ${w.b.x},${Y(w.b.y)} ${w.b.x+o.x},${Y(w.b.y+o.y)} ${w.a.x+o.x},${Y(w.a.y+o.y)}"/>`;
    // openings
    for(const u of S.utils){if(u.wall!==w.i||(u.type!=='window'&&u.type!=='door'))continue;
      const a={x:w.a.x+w.d.x*u.x,y:w.a.y+w.d.y*u.x},b={x:w.a.x+w.d.x*(u.x+u.w),y:w.a.y+w.d.y*(u.x+u.w)};
      s+=`<polygon fill="var(--floor)" points="${a.x+w.n.x*0.3},${Y(a.y+w.n.y*0.3)} ${b.x+w.n.x*0.3},${Y(b.y+w.n.y*0.3)} ${b.x+o.x*1.1},${Y(b.y+o.y*1.1)} ${a.x+o.x*1.1},${Y(a.y+o.y*1.1)}"/>`;
      if(u.type==='window')s+=`<line x1="${a.x+o.x/2}" y1="${Y(a.y+o.y/2)}" x2="${b.x+o.x/2}" y2="${Y(b.y+o.y/2)}" stroke="var(--blue)" stroke-width=".8"/><line x1="${a.x}" y1="${Y(a.y)}" x2="${b.x}" y2="${Y(b.y)}" stroke="var(--wallfill)" stroke-width=".4"/><line x1="${a.x+o.x}" y1="${Y(a.y+o.y)}" x2="${b.x+o.x}" y2="${Y(b.y+o.y)}" stroke="var(--wallfill)" stroke-width=".4"/>`;
      else{const r=u.w;const e={x:a.x+w.n.x*r,y:a.y+w.n.y*r};s+=`<path d="M${a.x},${Y(a.y)} L${e.x},${Y(e.y)} A${r},${r} 0 0 1 ${b.x},${Y(b.y)}" fill="none" stroke="var(--muted)" stroke-width=".4" stroke-dasharray="1.5 1"/>`}
    }
    {const off=(!mini&&UI.showDims)?26:11;const m={x:(w.a.x+w.b.x)/2-w.n.x*off,y:(w.a.y+w.b.y)/2-w.n.y*off};let ang=Math.atan2(-w.d.y,w.d.x)/D2R;if(ang>90)ang-=180;if(ang<=-90)ang+=180;
      s+=`<text class="pl-wl" x="${m.x}" y="${Y(m.y)}" text-anchor="middle" dominant-baseline="middle" transform="rotate(${ang} ${m.x} ${Y(m.y)})" ${mini?'style="font-size:7px"':''}>W${w.i+1} · ${esc(fmt(w.len,'ft'))}</text>`}
  }
  G.corners.forEach((a,i)=>{if(Math.abs(a-90)<0.15)return;const p=G.P[(i+1)%G.P.length];const w=G.walls[(i+1)%G.walls.length],w0=G.walls[i];const q={x:p.x+(w.n.x+w0.n.x)*9,y:p.y+(w.n.y+w0.n.y)*9};s+=`<text class="pl-ang" x="${q.x}" y="${Y(q.y)}" text-anchor="middle" ${mini?'style="font-size:6px"':''}>${a.toFixed(1)}°</text>`});
  if(mini){
    for(const u of S.utils){const T=UTIL[u.type];if(!T||T.box)continue;const w=G.walls[u.wall];if(!w)continue;const p={x:w.a.x+w.d.x*u.x+w.n.x*3,y:w.a.y+w.d.y*u.x+w.n.y*3};s+=`<circle cx="${p.x}" cy="${Y(p.y)}" r="2.6" fill="${T.c}"/>`}
    for(const it of S.items){const P=itemPoly(it);if(!P)continue;s+=`<polygon points="${P.map(p=>p.x+','+Y(p.y)).join(' ')}" fill="var(--blue-soft)" stroke="var(--line2)" stroke-width=".5"/>`}
    return s;
  }
  // countertop outlines
  for(const it of S.items){if(!(hasCT(it)||it.t==='RG')||it.wall==null)continue;const P=ctPoly(it);s+=`<polygon class="pl-ct" points="${P.map(p=>p.x+','+Y(p.y)).join(' ')}"/>`}
  // items, base level first then uppers
  const order=[...S.items].sort((a,b)=>(isUpper(a)?1:0)-(isUpper(b)?1:0));
  for(const it of order){
    if(isUpper(it)&&!UI.showUpper)continue;
    const P=itemPoly(it);if(!P)continue;const c=CAT[it.t];
    const cls=it.t==='ISL'?'pl-isl':c.cls==='filler'?'pl-fill':c.app?'pl-app':c.cls==='tall'?'pl-tall':isUpper(it)?'pl-wallc':'pl-base';
    const sev=C.byItem[it.id];const sel=SEL===it.id;
    s+=`<polygon data-id="${it.id}" class="pl-it ${cls} ${sel?'pl-sel':sev==='err'?'pl-e':sev==='warn'?'pl-w':''}" points="${P.map(p=>p.x+','+Y(p.y)).join(' ')}"/>`;
    const f=frame(it);
    if(it.t==='ISL'){
      const cd=it.d-1-24; const cl=rectPoly(f,it.w,it.d-1,cd);s+=`<polygon points="${cl.map(p=>p.x+','+Y(p.y)).join(' ')}" fill="none" stroke="var(--cab-stroke)" stroke-width=".35" stroke-dasharray="2 1" pointer-events="none"/>`;
      if(it.seat>=9){const nseat=Math.floor(it.w/24);for(let k=0;k<nseat;k++){const u=(k+0.5)*it.w/nseat;const p={x:f.o.x+f.U.x*u-f.V.x*8,y:f.o.y+f.U.y*u-f.V.y*8};s+=`<circle class="pl-seat" cx="${p.x}" cy="${Y(p.y)}" r="7"/>`}}
    }
    if(it.t==='RG'){for(const [u,v] of [[.27,.3],[.73,.3],[.27,.7],[.73,.7]]){const p={x:f.o.x+f.U.x*it.w*u+f.V.x*it.d*v,y:f.o.y+f.U.y*it.w*u+f.V.y*it.d*v};s+=`<circle cx="${p.x}" cy="${Y(p.y)}" r="${Math.min(it.w,it.d)*0.15}" fill="none" stroke="var(--cab-stroke)" stroke-width=".4" pointer-events="none"/>`}}
    if(it.t==='SB'){const p={x:f.o.x+f.U.x*it.w/2+f.V.x*it.d*0.55,y:f.o.y+f.U.y*it.w/2+f.V.y*it.d*0.55};const sw=it.w-8,sd=16;const ang=Math.atan2(-f.U.y,f.U.x)/D2R;s+=`<rect x="${p.x-sw/2}" y="${Y(p.y)-sd/2}" width="${sw}" height="${sd}" rx="2" fill="none" stroke="var(--cab-stroke)" stroke-width=".45" transform="rotate(${ang} ${p.x} ${Y(p.y)})" pointer-events="none"/>`}
    const P2=(u,v)=>{const q={x:f.o.x+f.U.x*u+f.V.x*v,y:f.o.y+f.U.y*u+f.V.y*v};return q.x+','+Y(q.y)};
    const rot=Math.atan2(-f.U.y,f.U.x)/D2R;const ctr=(u,v)=>({x:f.o.x+f.U.x*u+f.V.x*v,y:f.o.y+f.U.y*u+f.V.y*v});
    if(it.t==='WC'){const tk=ctr(it.w/2,4);const bw=ctr(it.w/2,it.d*0.62);s+=`<g pointer-events="none" transform="rotate(${rot} ${tk.x} ${Y(tk.y)})"><rect x="${tk.x-9}" y="${Y(tk.y)-3.5}" width="18" height="7" rx="1.5" fill="var(--panel)" stroke="var(--cab-stroke)" stroke-width=".45"/></g><ellipse cx="${bw.x}" cy="${Y(bw.y)}" rx="7.5" ry="10" transform="rotate(${rot} ${bw.x} ${Y(bw.y)})" fill="var(--panel)" stroke="var(--cab-stroke)" stroke-width=".45" pointer-events="none"/>`}
    if(it.t==='TUB'||it.t==='TP'){s+=`<polygon points="${P2(3,3)} ${P2(it.w-3,3)} ${P2(it.w-3,it.d-3)} ${P2(3,it.d-3)}" fill="none" stroke="var(--cab-stroke)" stroke-width=".4" stroke-linejoin="round" pointer-events="none"/>`;const dr=ctr(8,it.d/2);s+=`<circle cx="${dr.x}" cy="${Y(dr.y)}" r="1.2" fill="none" stroke="var(--cab-stroke)" stroke-width=".35" pointer-events="none"/>`}
    if(it.t==='SHW'){s+=`<polyline points="${P2(0,0)} ${P2(it.w,it.d)}" fill="none" stroke="var(--cab-stroke)" stroke-width=".3" pointer-events="none"/><polyline points="${P2(it.w,0)} ${P2(0,it.d)}" fill="none" stroke="var(--cab-stroke)" stroke-width=".3" pointer-events="none"/><polyline points="${P2(0,it.d-0.4)} ${P2(it.w,it.d-0.4)}" fill="none" stroke="var(--blue)" stroke-width="1" pointer-events="none"/>`}
    if(it.t==='VAN'){const n=it.w>=60?2:1;for(let k=0;k<n;k++){const b0=ctr(n===1?it.w/2:(k?it.w*0.75:it.w*0.25),it.d*0.55);s+=`<ellipse cx="${b0.x}" cy="${Y(b0.y)}" rx="8" ry="6" transform="rotate(${rot} ${b0.x} ${Y(b0.y)})" fill="none" stroke="var(--cab-stroke)" stroke-width=".45" pointer-events="none"/>`}}
    if(it.finish||(it.t==='ISL'&&st.islandFinish)){const fk=it.finish||st.islandFinish;const sw=ctr(Math.min(5,it.w/2),Math.min(5,it.d/2));if(FINISH[fk])s+=`<circle cx="${sw.x}" cy="${Y(sw.y)}" r="2.2" fill="${FINISH[fk][1]}" stroke="var(--cab-stroke)" stroke-width=".3" pointer-events="none"/>`}
    if(it.w>=6){const lv=it.wall==null?it.d/2:isUpper(it)?it.d/2:(UI.showUpper&&it.d>=20?Math.max(it.d/2,16.5):it.d/2);const cx=f.o.x+f.U.x*it.w/2+f.V.x*lv,cy=f.o.y+f.U.y*it.w/2+f.V.y*lv;let ang=Math.atan2(-f.U.y,f.U.x)/D2R;if(ang>90)ang-=180;if(ang<=-90)ang+=180;
      const fs=Math.min(4,it.w/4.6);s+=`<text class="pl-lbl" style="font-size:${fs}px" x="${cx}" y="${Y(cy)}" transform="rotate(${ang} ${cx} ${Y(cy)})">${esc(c.sku(it))}</text>`}
  }
  // utilities
  for(const u of S.utils){const T=UTIL[u.type];if(!T||T.box)continue;const w=G.walls[u.wall];if(!w)continue;const p={x:w.a.x+w.d.x*u.x-w.n.x*2.2,y:w.a.y+w.d.y*u.x-w.n.y*2.2};
    s+=`<g pointer-events="none"><circle cx="${p.x}" cy="${Y(p.y)}" r="2.7" fill="var(--panel)" stroke="${T.c}" stroke-width=".7"/><text class="pl-u" x="${p.x}" y="${Y(p.y)}" fill="${T.c}" style="font-size:${T.s.length>1?2.4:3.4}px">${T.s}</text></g>`}
  // automatic plan dimensions: each wall's base run, plus overall, outside the walls
  if(UI.showDims){
    for(const w of G.walls){
      const own=S.items.filter(it=>it.wall===w.i&&isBaseLevel(it));if(!own.length)continue;
      const edges=[...new Set([0,w.len,...own.flatMap(it=>[r2(it.x),r2(it.x+it.w)])])].filter(e=>e>=-0.01&&e<=w.len+0.01).sort((a,b)=>a-b);
      const off=13;const at=u=>({x:w.a.x+w.d.x*u-w.n.x*off,y:w.a.y+w.d.y*u-w.n.y*off});
      const A=at(edges[0]),B=at(edges[edges.length-1]);s+=`<line class="pl-dim" x1="${A.x}" y1="${Y(A.y)}" x2="${B.x}" y2="${Y(B.y)}"/>`;
      let ang=Math.atan2(-w.d.y,w.d.x)/D2R;const flip=ang>90||ang<=-90;if(ang>90)ang-=180;if(ang<=-90)ang+=180;
      edges.forEach(e=>{const p=at(e),q={x:p.x+w.n.x*3,y:p.y+w.n.y*3},r={x:p.x-w.n.x*2,y:p.y-w.n.y*2};s+=`<line class="pl-dim" x1="${q.x}" y1="${Y(q.y)}" x2="${r.x}" y2="${Y(r.y)}"/>`});
      for(let k=0;k<edges.length-1;k++){const a=edges[k],b=edges[k+1];if(b-a<3)continue;const m=at((a+b)/2);const t={x:m.x-w.n.x*2.2,y:m.y-w.n.y*2.2};
        s+=`<text class="pl-dimt" x="${t.x}" y="${Y(t.y)}" transform="rotate(${ang} ${t.x} ${Y(t.y)})" style="font-size:${Math.min(3.6,(b-a)/4.2)}px">${esc(fmt(b-a))}</text>`}
    }
    for(const it of S.items.filter(i=>i.wall==null)){/* island position to the two nearest walls */
      const P=ctPoly(it);const near=G.walls.map(w=>{let md=1e9,mp,mq;for(const p of P){const r=segPt(p,w.a,w.b);if(r.d<md){md=r.d;mp=p;mq=r.q}}return {md,mp,mq,w}}).sort((a,b)=>a.md-b.md);
      const used=[];for(const n of near){if(used.length>=2)break;if(used.some(u=>Math.abs(u.w.d.x*n.w.d.x+u.w.d.y*n.w.d.y)>0.7))continue;used.push(n);
        const m={x:(n.mp.x+n.mq.x)/2,y:(n.mp.y+n.mq.y)/2};s+=`<line class="pl-dim" x1="${n.mp.x}" y1="${Y(n.mp.y)}" x2="${n.mq.x}" y2="${Y(n.mq.y)}" marker-start="url(#arrd)" marker-end="url(#arrd)"/><text class="pl-dimt" x="${m.x}" y="${Y(m.y)}" style="paint-order:stroke;stroke:var(--floor);stroke-width:1.4">${esc(fmt(n.md))}</text>`}
    }
  }
  for(const n of S.notes||[]){const sel=SEL==='n:'+n.id;const lines=String(n.text||'Note').split(/\n/).slice(0,4);const wpx=Math.max(...lines.map(l=>l.length))*2.05+6;
    s+=`<g class="pl-note${sel?' sel':''}" data-note="${n.id}"><rect data-note="${n.id}" x="${n.x}" y="${Y(n.y)}" width="${wpx}" height="${lines.length*4.6+3}" rx="1.2"/>${lines.map((l,k)=>`<text x="${n.x+3}" y="${Y(n.y)+4.6+k*4.6}" pointer-events="none">${esc(l)}</text>`).join('')}</g>`}
  // overlays
  if(UI.showTri&&C.tri){s+=`<polygon class="pl-tri" points="${C.tri.pts.map(p=>p.x+','+Y(p.y)).join(' ')}"/>`;}
  if(UI.showClr){for(const l of C.lines){const m={x:(l.p.x+l.q.x)/2,y:(l.p.y+l.q.y)/2};s+=`<line class="pl-clr ${l.bad?'bad':''}" x1="${l.p.x}" y1="${Y(l.p.y)}" x2="${l.q.x}" y2="${Y(l.q.y)}" marker-start="url(#arr)" marker-end="url(#arr)"/><text class="pl-clrt ${l.bad?'bad':''}" x="${m.x}" y="${Y(m.y)}">${esc(fmt(l.d))}</text>`}}
  return `<defs><marker id="arrd" viewBox="0 0 6 6" refX="3" refY="3" markerWidth="3.5" markerHeight="3.5" orient="auto-start-reverse"><path d="M0,0 L6,3 L0,6 z" fill="var(--dim)"/></marker><marker id="arr" viewBox="0 0 6 6" refX="3" refY="3" markerWidth="4" markerHeight="4" orient="auto-start-reverse"><path d="M0,0 L6,3 L0,6 z" fill="var(--blue)"/></marker></defs>`+s;
}
function fitView(svg,mini){
  const b=geom().bbox;const m=mini?22:34;
  const v={x:b.minx-m,y:-b.maxy-m,w:b.w+2*m,h:b.h+2*m};
  if(!mini&&UI.view){svg.setAttribute('viewBox',`${UI.view.x} ${UI.view.y} ${UI.view.w} ${UI.view.h}`);return}
  svg.setAttribute('viewBox',`${v.x} ${v.y} ${v.w} ${v.h}`);
  if(!mini)UI.view=null;
}

/* ---------- DESIGN ---------- */
function renderDesign(){
  const root=$('#v-design');
  if(!root.dataset.built){
    root.innerHTML=`<div class="dgrid">
      <aside class="cat card" id="catalog" aria-label="Catalog"></aside>
      <div class="stack" style="min-width:0">
        <div class="planwrap" id="planwrap">
          <svg id="plan" aria-label="Floor plan. Drag cabinets along walls; drag islands anywhere."></svg>
          <div class="ptools">
            <select class="in" id="tgtWall" style="width:auto;min-height:32px" aria-label="Wall for new cabinets"></select>
            <button class="btn sm" data-act="fit">Fit to screen</button><button class="btn sm" data-act="zoom" data-v="0.8">＋</button><button class="btn sm" data-act="zoom" data-v="1.25">－</button>
            <div class="seg" role="group" aria-label="Overlays"><button data-act="tog" data-v="showUpper" id="tgU">Wall cabinets</button><button data-act="tog" data-v="showTri" id="tgT" class="pro">Work triangle</button><button data-act="tog" data-v="showClr" id="tgC">Walkway sizes</button><button data-act="tog" data-v="showDims" id="tgD">Measurements</button></div>
            <button class="btn sm" data-act="addnote" id="bNote">+ Add a note</button>
          </div>
          <div class="legend"><span><i style="background:var(--cab)"></i>Base</span><span><i style="background:var(--wallcab);border-style:dashed"></i>Wall</span><span><i style="background:var(--tall)"></i>Tall</span><span><i style="background:var(--app)"></i>Appliance</span><span><i style="background:var(--hatch)"></i>Filler</span></div>
        </div>
      </div>
      <aside class="insp stack" id="insp"></aside>
    </div>`;
    root.dataset.built='1';
    wirePlan();
  }
  {
    const groups={};Object.entries(CAT).forEach(([k,c])=>{if(!c.hidden)(groups[c.grp]=groups[c.grp]||[]).push([k,c])});
    $('#catalog').innerHTML='<h3 style="margin-bottom:4px">Add to the room</h3>'+`<div class="pro">${libraryHTML()}</div>`+Object.entries(groups).map(([g,list])=>`<div class="grp"><div class="eyebrow">${esc(GRP_PLAIN[g]||g)}</div>${list.map(([k,c])=>`<button class="cbtn" data-act="add" data-t="${k}" title="Add ${esc(PLAIN[k]||c.name)}"><span class="ci">${catIcon(k)}</span><span class="cn">${esc(PLAIN[k]||c.name)}</span><small class="pro">${esc(c.sku({...c,t:k,w:c.w,h:c.h,d:c.d,lay:'base',hinge:'L'}).split(' ')[0])}</small></button>`).join('')}</div>`).join('')+
      `<p class="hint">Tap an item to add it to the wall picked above the plan. Then drag it on the plan to move it.</p>`;
  }
  $('#tgtWall').innerHTML=S.walls.map((w,i)=>`<option value="${i}" ${i===UI.wall?'selected':''}>New items go on wall W${i+1}${w.note?' · '+esc(w.note.slice(0,18)):''}</option>`).join('');
  $('#tgU').setAttribute('aria-pressed',UI.showUpper);$('#tgT').setAttribute('aria-pressed',UI.showTri);$('#tgC').setAttribute('aria-pressed',UI.showClr);$('#tgD').setAttribute('aria-pressed',!!UI.showDims);$('#bNote').style.background=UI.addNote?'var(--accent)':'';
  drawPlan();
  renderInspector();
}
function drawPlan(){const svg=$('#plan');if(!svg)return;svg.innerHTML=planInner(false);fitView(svg,false)}
function selItem(){return S.items.find(i=>i.id===SEL)}
function renderInspector(){
  const it=selItem();const el=$('#insp');const C=checks();
  if(typeof SEL==='string'&&SEL.startsWith('n:')){const n=(S.notes||[]).find(x=>'n:'+x.id===SEL);if(n){el.innerHTML=`<div class="card stack"><div class="row" style="justify-content:space-between"><div><div class="eyebrow">Plan annotation</div><h3>Note</h3></div><button class="btn ghost sm" data-act="desel">Done</button></div>
      <label class="f">Text<textarea class="in" id="n-text" rows="4" data-bind="notes.${S.notes.indexOf(n)}.text" data-kind="text">${esc(n.text)}</textarea></label><p class="hint">Drag the note on the plan to move it. Notes print on the plan in the presentation.</p><button class="btn sm danger" data-act="delnote" data-id="${n.id}">Delete note</button></div>`;return}}
  if(!it){
    const j=UI.wall,w=geom().walls[j],st=S.settings;
    const runSVG=(z0,z1,label)=>{const occ=occupied(j,z0,z1);let s='';for(const o of occ){const c=CAT[o.it.t];s+=`<rect x="${o.u0/w.len*100}" y="0" width="${(o.u1-o.u0)/w.len*100}" height="10" fill="${o.own?(c.app?'var(--app)':c.cls==='filler'?'var(--hatch)':'var(--blue-soft)'):'var(--line2)'}" stroke="var(--cab-stroke)" stroke-width=".15"/>`}
      const gs=gaps(j,z0,z1);return `<div class="eyebrow" style="margin-top:6px">${label}</div><svg viewBox="0 0 100 10" preserveAspectRatio="none" style="width:100%;height:18px;border:1px solid var(--line2);border-radius:3px;background:var(--panel2)">${s}</svg>
      ${gs.length?`<div class="stack" style="gap:4px;margin-top:4px">${gs.map(g=>`<div class="row" style="justify-content:space-between"><span class="mono" style="font-size:12.5px">Empty: ${fmt(g.a)} → ${fmt(g.b)} <b>(${fmt(g.b-g.a)})</b></span><button class="btn sm" data-act="fillgap" data-a="${g.a}" data-b="${g.b}" data-z="${z0}">Fill</button></div>`).join('')}</div>`:'<div class="hint">No empty spaces.</div>'}`};
    const sc=geom().corners[startCorner(j)],ec=geom().corners[j];
    el.innerHTML=`<div class="card stack"><div><div class="eyebrow">Selected wall</div><h3>W${j+1} · ${fmt(w.len,'ft')}</h3></div><p class="hint" style="margin:0">Tap any cabinet on the plan to change it. Tap empty floor to come back here.</p>
      <div class="kv pro"><dt>Left corner</dt><dd>${sc.toFixed(2)}°${Math.abs(sc-90)>=0.15?' · scribe '+fmt(Math.abs(frontLoss(sc,24))):''}</dd><dt>Right corner</dt><dd>${ec.toFixed(2)}°${Math.abs(ec-90)>=0.15?' · scribe '+fmt(Math.abs(frontLoss(ec,24))):''}</dd>
      <dt>Usable at base face</dt><dd>${fmt(w.len-Math.max(0,frontLoss(sc,24))-Math.max(0,frontLoss(ec,24)))}</dd></div>
      ${runSVG(0,st.counterH-1,'Lower cabinets on this wall')}${runSVG(st.counterH+6,S.ceiling,'Upper cabinets on this wall')}
      <div class="row"><button class="btn pri" data-act="autofill">Fill the empty spaces on W${j+1}</button><button class="btn sm pro" data-act="libwall">Save W${j+1} run to library</button></div>
      <p class="hint">This uses standard cabinet sizes and leaves a small filler strip at the wall, so everything fits even when walls are not perfectly straight.</p></div>
      ${aiCardHTML()}
      <div class="pro">${optionsCardHTML()}</div>
      ${issuesCard(C.list.filter(x=>x.sev!=='info').slice(0,5),'Problems to fix')}`;
    return;
  }
  const c=CAT[it.t];let seq=500;
  const L=(k,label,extra='')=>`<label class="f">${label}<input class="in len" id="i-${k}" data-bind="it.${it.id}.${k}" data-kind="len" data-seq="${seq++}" value="${esc(fmt(it[k]))}" ${extra}></label>`;
  const widthSel=`<label class="f">Width<select class="in" id="i-wsel" data-bind="it.${it.id}.w" data-kind="num">${[...new Set([...c.ws,it.w])].sort((a,b)=>a-b).map(w=>`<option value="${w}" ${w===it.w?'selected':''}>${fmt(w)}</option>`).join('')}</select></label>`;
  const iss=C.list.filter(x=>x.ids.includes(it.id));
  const mdl=MODELS[it.t];
  el.innerHTML=`<div class="card stack">
    <div class="row" style="justify-content:space-between"><div><div class="eyebrow">${esc(c.grp)}</div><h3>${esc(c.sku(it))}</h3></div><button class="btn pri sm" data-act="desel">Done</button></div>
    <p class="hint" style="margin:0">Change the size below, or drag it on the plan. Use the arrow buttons to nudge it ⅛".</p>
    ${mdl?`<label class="f">Model class<select class="in" id="i-model" data-bind="it.${it.id}.model" data-kind="int" data-after="model">${mdl.map((m,k)=>`<option value="${k}" ${k===it.model?'selected':''}>${esc(m[0])} — ${m[1]}×${m[2]}×${m[3]}</option>`).join('')}</select></label>`:''}
    <div class="fgrid">
      ${c.ws.length>1?widthSel:''}${L('w','Exact width','data-min="0.5"')}
      ${c.hs?`<label class="f">Height<select class="in" id="i-hsel" data-bind="it.${it.id}.h" data-kind="num" data-after="wallh">${[...new Set([...c.hs,it.h])].sort((a,b)=>a-b).map(h=>`<option value="${h}" ${h===it.h?'selected':''}>${fmt(h)}</option>`).join('')}</select></label>`:L('h','Height','data-min="0.5"')}
      ${L('d','Depth','data-min="0.5"')}
      ${it.wall!=null?L('x','Distance from left end of W'+(it.wall+1)):L('cx','Center X')}
      ${it.wall!=null?L('z','Height off floor (bottom)'):L('cy','Center Y')}
      ${it.wall==null?`<label class="f">Rotation<select class="in" id="i-rot" data-bind="it.${it.id}.rot" data-kind="num">${[0,45,90,135,180,225,270,315].map(r=>`<option value="${r}" ${r===(it.rot||0)?'selected':''}>${r}°</option>`).join('')}</select></label>`:''}
      ${it.t==='ISL'?L('seat','Seating overhang')+`<label class="f">Cabinet style<select class="in" id="i-style" data-bind="it.${it.id}.style" data-kind="text"><option value="drawers" ${it.style==='drawers'?'selected':''}>Drawer bases</option><option value="doors" ${it.style==='doors'?'selected':''}>Door bases</option></select></label>`:''}
      ${it.t==='RG'?`<label class="f">Fuel<select class="in" id="i-fuel" data-bind="it.${it.id}.fuel" data-kind="text"><option value="gas" ${it.fuel==='gas'?'selected':''}>Gas</option><option value="electric" ${it.fuel!=='gas'?'selected':''}>Electric / induction</option></select></label>`:''}
      ${(it.t==='F'||it.t==='PNL')?`<label class="f">Layer<select class="in" id="i-lay" data-bind="it.${it.id}.lay" data-kind="text" data-after="lay">${['base','wall','tall'].map(l=>`<option value="${l}" ${it.lay===l?'selected':''}>${l}</option>`).join('')}</select></label>`:''}
      ${['W','BBC','T'].includes(it.t)||(it.t==='B'&&it.w<24)?`<label class="f">Hinge / blind side<select class="in" id="i-hinge" data-bind="it.${it.id}.hinge" data-kind="text"><option value="L" ${it.hinge==='L'?'selected':''}>Left</option><option value="R" ${it.hinge==='R'?'selected':''}>Right</option></select></label>`:''}
      ${(!c.app&&c.cls!=='filler')||it.t==='ISL'||c.cls==='filler'?`<label class="f">Finish<select class="in" id="i-fin" data-bind="it.${it.id}.finish" data-kind="text"><option value="">Project default</option>${Object.entries(FINISH).map(([k,v])=>`<option value="${k}" ${it.finish===k?'selected':''}>${v[0]}</option>`).join('')}</select></label>`:''}
      ${fronts(it).some(f=>f.k==='door')?`<label class="f">Door style<select class="in" id="i-door" data-bind="it.${it.id}.door" data-kind="text"><option value="">Project default</option>${Object.entries(DOOR).map(([k,v])=>`<option value="${k}" ${it.door===k?'selected':''}>${v}</option>`).join('')}${it.t==='W'?`<option value="solid" ${it.door==='solid'?'selected':''}>Solid (no glass)</option>`:''}</select></label>`:''}
      ${it.wall!=null?`<label class="f">Wall<select class="in" id="i-wall" data-bind="it.${it.id}.wall" data-kind="int">${S.walls.map((w,i)=>`<option value="${i}" ${i===it.wall?'selected':''}>W${i+1}</option>`).join('')}</select></label>`:''}
    </div>
    <div class="row">
      ${it.wall!=null?`<button class="btn sm" data-act="nudge" data-v="-0.125" title="Left ⅛ (←)">◀ ⅛"</button><button class="btn sm" data-act="nudge" data-v="0.125" title="Right ⅛ (→)">⅛" ▶</button>`:`<button class="btn sm" data-act="rot" data-v="-15">⟲ 15°</button><button class="btn sm" data-act="rot" data-v="15">⟳ 15°</button>`}
      <button class="btn sm" data-act="dup">Duplicate</button><button class="btn sm pro" data-act="libitem">Save to library</button><button class="btn sm danger" data-act="del">Delete</button>
    </div>
    <label class="f">Spec / model number<input class="in" id="i-note" data-bind="it.${it.id}.note" data-kind="text" value="${esc(it.note||'')}" placeholder="${c.app?'e.g. manufacturer model #':'e.g. pull-out organizer'}"></label>
    <div class="kv"><dt>Price (list)</dt><dd>${c.app?(S.settings.price.app[it.t]?money(S.settings.price.app[it.t]):'Owner / allowance'):money(unitPrice(it))}</dd>${it.wall!=null?`<dt>Right edge</dt><dd>${fmt(it.x+it.w)}</dd><dt>Top</dt><dd>${fmt(it.z+it.h)} AFF</dd>`:''}</div>
  </div>
  ${issuesCard(iss,'Issues on this item')}`;
}
function issuesCard(list,title){
  return `<div class="card stack"><h3>${title}</h3>${list.length?list.map((x,k)=>`<button class="issue" data-act="goissue" data-ids="${x.ids.join(',')}"><span class="sev sev-${x.sev}"></span><span><b>${esc(x.title)}</b><span>${esc(x.detail)}</span></span></button>`).join(''):'<div class="row"><span class="pill p-ok">No issues</span></div>'}</div>`;
}
function addItem(t){
  const c=CAT[t];const st=S.settings;
  if(t==='ISL'){const b=geom().bbox;const it=mkItem('ISL',{cx:r2(b.cx),cy:r2(b.cy),rot:0});S.items.push(it);SEL=it.id;commit();return}
  const j=UI.wall;const it=mkItem(t,{wall:j});
  if(t==='W')it.z=st.wallBottom;
  if(t==='MIR'){const v=S.items.find(i=>i.wall===j&&i.t==='VAN');if(v){it.w=Math.min(it.w,v.w);it.x=r2(v.x+(v.w-it.w)/2);S.items.push(it);SEL=it.id;commit();return}}
  const gs=gaps(j,it.z,it.z+it.h);let g=gs.find(g=>g.b-g.a>=it.w-0.01);
  if(g)it.x=r2(g.a);else{it.x=0;toast('No gap wide enough on W'+(j+1)+'. Placed at the left end — resolve the collision.','err')}
  S.items.push(it);SEL=it.id;commit();
}
function fillGap(j,a,b,z){
  const st=S.settings;const len=b-a;const upper=z>st.counterH;const layer=upper?'wall':'base';
  const wlen=geom().walls[j].len;const atRightWall=Math.abs(b-wlen)<0.1;
  const mkF=(x,w)=>mkItem('F',{wall:j,x:r2(x),w:r2(w),lay:layer,z:upper?st.wallBottom:0,h:upper?30:34.5,d:upper?12:24});
  if(len<=6){S.items.push(mkF(a,len));return 1}
  const t=upper?'W':'B';const ws=CAT[t].ws.slice().reverse();
  let rem=len;const widths=[];
  while(rem>6){
    const fit=ws.find(w=>rem-w>=1&&rem-w<=4);if(fit){widths.push(fit);rem-=fit;break}
    const w=ws.find(w=>w<=rem-1&&rem-w>=10)||ws.find(w=>w<=rem-1);if(!w)break;widths.push(w);rem-=w;
  }
  rem=r2(rem);
  if(!widths.length){S.items.push(mkF(a,len));return 1}
  let x=a;
  if(!atRightWall&&rem>0.0625){S.items.push(mkF(x,rem));x+=rem}
  for(const w of widths){S.items.push(mkItem(t,{wall:j,x:r2(x),w,z:upper?st.wallBottom:0,h:upper?30:34.5}));x+=w}
  if(atRightWall&&rem>0.0625)S.items.push(mkF(x,rem));
  return widths.length+(rem>0.0625?1:0);
}
function autofill(j){
  const st=S.settings;let k=0;
  for(const [z0,z1] of [[0,st.counterH-1],[st.counterH+6,S.ceiling]]){
    const gs=gaps(j,z0,z1);
    for(const g of gs){
      // skip gaps blocked by openings
      const blocked=S.utils.some(u=>u.wall===j&&(u.type==='window'||u.type==='door')&&u.x-(u.trim||0)<g.b&&u.x+u.w+(u.trim||0)>g.a&&(u.type==='door'||z0>0));
      if(blocked)continue;
      k+=fillGap(j,g.a,g.b,z0);
    }
  }
  return k;
}
/* plan interaction: drag, pan, pinch, wheel */
function wirePlan(){
  const svg=$('#plan');const pts=new Map();let drag=null,pan=null,pinch=null;
  const world=(e)=>{const p=svg.createSVGPoint();p.x=e.clientX;p.y=e.clientY;const m=svg.getScreenCTM();if(!m)return {x:0,y:0};const q=p.matrixTransform(m.inverse());return {x:q.x,y:-q.y}};
  const vb=()=>{const v=svg.viewBox.baseVal;return {x:v.x,y:v.y,w:v.width,h:v.height}};
  svg.addEventListener('pointerdown',e=>{
    svg.setPointerCapture(e.pointerId);pts.set(e.pointerId,{x:e.clientX,y:e.clientY});
    if(pts.size===2){drag=null;const [a,b]=[...pts.values()];pinch={d:Math.hypot(a.x-b.x,a.y-b.y),v:vb(),c:{x:(a.x+b.x)/2,y:(a.y+b.y)/2}};return}
    const id=e.target.dataset&&e.target.dataset.id;const nid=e.target.dataset&&e.target.dataset.note;
    const p=world(e);
    if(UI.addNote&&!id&&!nid){const n={id:uid(),x:r2(p.x),y:r2(p.y),text:'New note'};S.notes.push(n);UI.addNote=false;SEL='n:'+n.id;commit();setTimeout(()=>{const t=$('#n-text');if(t){t.focus();t.select()}},30);return}
    if(nid){const n=S.notes.find(x=>x.id===nid);if(!n)return;if(SEL!=='n:'+nid){SEL='n:'+nid;renderInspector();drawPlan()}drag={note:n,start:p,moved:false,ox:n.x,oy:n.y};return}
    if(id){const it=S.items.find(i=>i.id===id);if(!it)return;if(SEL!==id){SEL=id;if(it.wall!=null)UI.wall=it.wall;renderInspector();drawPlan()}
      const f=frame(it);
      drag={it,start:p,moved:false,grabU:(p.x-f.o.x)*f.U.x+(p.y-f.o.y)*f.U.y,grabV:(p.x-f.o.x)*f.V.x+(p.y-f.o.y)*f.V.y,ox:it.cx,oy:it.cy};
    } else {pan={c:{x:e.clientX,y:e.clientY},v:vb(),moved:false}}
  });
  svg.addEventListener('pointermove',e=>{
    if(!pts.has(e.pointerId))return;pts.set(e.pointerId,{x:e.clientX,y:e.clientY});
    if(pinch&&pts.size===2){const [a,b]=[...pts.values()];const d=Math.hypot(a.x-b.x,a.y-b.y);const k=pinch.d/d;const r=svg.getBoundingClientRect();
      const cx=pinch.v.x+(pinch.c.x-r.left)/r.width*pinch.v.w,cy=pinch.v.y+(pinch.c.y-r.top)/r.height*pinch.v.h;
      UI.view={x:cx-(cx-pinch.v.x)*k,y:cy-(cy-pinch.v.y)*k,w:pinch.v.w*k,h:pinch.v.h*k};svg.setAttribute('viewBox',`${UI.view.x} ${UI.view.y} ${UI.view.w} ${UI.view.h}`);return}
    if(drag){const p=world(e);if(!drag.moved&&Math.hypot(p.x-drag.start.x,p.y-drag.start.y)<1.2)return;drag.moved=true;dragMove(drag,p);svg.innerHTML=planInner(false)}
    else if(pan){const r=svg.getBoundingClientRect();const dx=(e.clientX-pan.c.x)/r.width*pan.v.w,dy=(e.clientY-pan.c.y)/r.height*pan.v.h;if(Math.abs(e.clientX-pan.c.x)+Math.abs(e.clientY-pan.c.y)>3)pan.moved=true;
      UI.view={x:pan.v.x-dx,y:pan.v.y-dy,w:pan.v.w,h:pan.v.h};svg.setAttribute('viewBox',`${UI.view.x} ${UI.view.y} ${UI.view.w} ${UI.view.h}`)}
  });
  const up=e=>{pts.delete(e.pointerId);if(pts.size<2)pinch=null;
    if(drag){const d=drag;drag=null;if(d.moved)commit()}
    else if(pan){if(!pan.moved&&SEL){SEL=null;renderInspector();drawPlan()}pan=null}};
  svg.addEventListener('pointerup',up);svg.addEventListener('pointercancel',up);
  svg.addEventListener('wheel',e=>{e.preventDefault();const v=vb();const k=e.deltaY>0?1.1:1/1.1;const r=svg.getBoundingClientRect();const cx=v.x+(e.clientX-r.left)/r.width*v.w,cy=v.y+(e.clientY-r.top)/r.height*v.h;
    UI.view={x:cx-(cx-v.x)*k,y:cy-(cy-v.y)*k,w:v.w*k,h:v.h*k};svg.setAttribute('viewBox',`${UI.view.x} ${UI.view.y} ${UI.view.w} ${UI.view.h}`)},{passive:false});
}
function dragMove(d,p){
  if(d.note){d.note.x=r2(d.ox+p.x-d.start.x);d.note.y=r2(d.oy+p.y-d.start.y);return}
  const it=d.it;
  if(it.wall==null){const dx=p.x-d.start.x,dy=p.y-d.start.y;it.cx=Math.round((d.ox+dx)*2)/2;it.cy=Math.round((d.oy+dy)*2)/2;invalidate();return}
  const G=geom();let best=null;
  for(const w of G.walls){const t=toWall(w,p);if(t.u<-18||t.u>w.len+18||t.v<-30||t.v>Math.max(60,it.d+30))continue;const sc=Math.abs(t.v-it.d/2);if(!best||sc<best.sc)best={w,t,sc}}
  if(!best)return;
  it.wall=best.w.i;
  let x=best.t.u-d.grabU;
  const snap=UI.snap;x=Math.round(x/snap)*snap;
  const occ=occupied(it.wall,it.z,it.z+it.h,it);
  const edges=[0,best.w.len];occ.forEach(o=>{edges.push(o.u0,o.u1)});
  let bd=1.6,bx=null;for(const e of edges){if(Math.abs(x-e)<bd){bd=Math.abs(x-e);bx=e}if(Math.abs(x+it.w-e)<bd){bd=Math.abs(x+it.w-e);bx=e-it.w}}
  if(bx!=null)x=bx;
  it.x=r2(clamp(x,-it.w+2,best.w.len-2));
  UI.wall=it.wall;invalidate();
}

