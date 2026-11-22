/* ---------- Library: saved cabinet groupings ---------- */
let LIB=LS.get('ps.library')||[];
async function loadLibraryCloud(){
  if(!CLOUD.ready)return;
  try{const d=await CLOUD.db.collection('data/users/'+CLOUD.uid).doc('_library').get();if(d.exists){const cl=JSON.parse(d.data().json||'[]');const ids=new Set(LIB.map(x=>x.id));cl.forEach(x=>{if(!ids.has(x.id))LIB.push(x)});LS.set('ps.library',LIB);if(UI.tab==='design')renderDesign()}}catch(e){}
}
async function saveLibrary(){LS.set('ps.library',LIB);if(CLOUD.ready){try{await CLOUD.db.collection('data/users/'+CLOUD.uid).doc('_library').set({json:JSON.stringify(LIB),updatedAt:Date.now()})}catch(e){}}}
function libraryHTML(){
  return `<div class="grp"><div class="eyebrow">My library</div>${LIB.length?LIB.map(g=>`<div class="row" style="gap:4px;flex-wrap:nowrap"><button class="cbtn" data-act="libinsert" data-id="${g.id}" style="flex:1;min-width:0"><span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(g.name)}</span><small>${g.items.length} pc</small></button><button class="btn ghost sm danger" data-act="libdel" data-id="${g.id}" aria-label="Remove ${esc(g.name)} from library">✕</button></div>`).join(''):'<p class="hint" style="margin:0">Save an island, a cabinet or a whole wall run here, then drop it into any project.</p>'}</div>`;
}
function libInsert(g){
  const j=UI.wall,w=geom().walls[j];const added=[];
  if(g.kind==='island'){const b=geom().bbox;g.items.forEach(x=>{const it={...clone(x),id:uid(),cx:r2(b.cx),cy:r2(b.cy),wall:null};S.items.push(it);added.push(it)})}
  else{const span=Math.max(...g.items.map(x=>x.x+x.w));const gs=gaps(j,0,S.ceiling);const gp=gs.find(q=>q.b-q.a>=span)||{a:0};
    if(span>w.len+0.5)toast('That run is '+fmt(span)+' wide; W'+(j+1)+' is '+fmt(w.len)+'. Check the ends.','err');
    g.items.forEach(x=>{const it={...clone(x),id:uid(),wall:j,x:r2(gp.a+x.x)};S.items.push(it);added.push(it)})}
  SEL=added[0]?.id||null;commit();toast('Inserted '+g.name);
}

/* ---------- Design options ---------- */
function optionsCardHTML(){
  return `<div class="card stack"><div><div class="eyebrow">Compare iterations</div><h3>Design options</h3></div>
    ${S.options.length?S.options.map(o=>`<div class="row" style="justify-content:space-between;gap:6px"><span style="min-width:0;flex:1">${esc(o.name)}</span><button class="btn sm" data-act="optload" data-id="${o.id}">Load</button></div>`).join(''):'<p class="hint" style="margin:0">Save the current layout and selections as an option, try another idea, then compare prices side by side.</p>'}
    <div class="row" style="flex-wrap:nowrap"><input class="in" id="optName" placeholder="Option name" style="flex:1;min-width:0"><button class="btn sm" data-act="optsave">Save</button></div>
    <button class="btn sm ghost" data-act="cmpopts">Compare in Estimate →</button></div>`;
}
function saveOption(name){const o={id:uid(),name:name||('Option '+String.fromCharCode(65+S.options.length)),items:clone(S.items),sel:currentSel(),at:Date.now()};S.options.push(o);return o}
function loadOption(o){S.items=clone(o.items);Object.assign(S.settings,o.sel||{});SEL=null}

/* ---------- AI (Claude) ---------- */
function aiCardHTML(){
  if(!CLOUD.sample)return '';
  return `<div class="card stack"><div><div class="eyebrow">AI design studio</div><h3>Generate a layout</h3></div>
    <p class="hint" style="margin:0">Claude reads this room, its openings and rough-ins, then proposes a full cabinet and appliance layout. It is saved as a new design option, so you can compare it with your own.</p>
    <div class="fgrid"><label class="f">Shape<select class="in" id="aiShape"><option value="best">Best fit</option><option value="L">L-shape</option><option value="U">U-shape</option><option value="galley">Galley</option><option value="single">Single wall</option></select></label>
    <label class="f">Island<select class="in" id="aiIsland"><option value="auto">If it fits</option><option value="yes">Yes</option><option value="no">No</option></select></label></div>
    <label class="f">Priorities<input class="in" id="aiNotes" placeholder="e.g. lots of drawers, 36&quot; range, pantry wall"></label>
    <button class="btn pri" data-act="ailayout" ${UI.aiBusy?'disabled':''}>${UI.aiBusy?'Designing…':'Generate layout'}</button></div>`;
}
function roomBrief(){
  const G=geom();
  return {units:'inches',ceiling:S.ceiling,
    coordinate_system:'Plan x east, y north. Walls are listed clockwise; each wall runs from point a to b. "x" for wall-mounted items is the distance from the wall\'s start (a) toward b. Items extend from the wall into the room by their depth. Island center (cx,cy) is in plan coordinates.',
    walls:G.walls.map(w=>({index:w.i,length:r2(w.len),a:{x:r2(w.a.x),y:r2(w.a.y)},b:{x:r2(w.b.x),y:r2(w.b.y)},into_room_normal:{x:r2(w.n.x),y:r2(w.n.y)},note:S.walls[w.i].note||'',
      openings:S.utils.filter(u=>u.wall===w.i&&UTIL[u.type]?.box).map(u=>({type:u.type,x0:r2(u.x-(+u.trim||0)),x1:r2(u.x+u.w+(+u.trim||0)),bottom:r2(u.type==='door'?0:u.z-(+u.trim||0)),top:r2(u.z+u.h+(+u.trim||0))})),
      rough_ins:S.utils.filter(u=>u.wall===w.i&&!UTIL[u.type]?.box).map(u=>({type:u.type,x:r2(u.x),height:r2(u.z)}))})),
    room_bbox:{minx:r2(G.bbox.minx),maxx:r2(G.bbox.maxx),miny:r2(G.bbox.miny),maxy:r2(G.bbox.maxy)}};
}
const AI_CATALOG='Types (t): B base (widths 9-36 by 3, d24,h34.5), DB drawer base (12-36), SB sink base (30,33,36), BBC blind corner base (36-48, put at an inside corner; "hinge":"L" or "R" = blind side toward the corner), WP waste pullout (15,18), W wall cabinet (9-42, d12, h30 or 36 or 42, z = bottom height, default 54), T pantry (18-36, d24, h84), OV wall-oven cabinet (27-33, d24,h84), F filler (w 0.75-6; "lay":"base"|"wall"|"tall"), RG range (w30/36/48, d28, "fuel":"gas"|"electric"), DW dishwasher (w24, d24), REF refrigerator (w30-48, d30 or 24 for built-in, h70 or 84), HOOD range hood (w ≥ range, d20, h18, z = 66 for gas), MWD microwave drawer (24/30), ISL island (w 48-120, d 36-48, seat = overhang 0-15, rot degrees).';
async function aiLayout(){
  const sm=CLOUD.sample;if(!sm)return;
  const shape=$('#aiShape')?.value||'best',isl=$('#aiIsland')?.value||'auto',notes=$('#aiNotes')?.value||'';
  UI.aiBusy=true;renderInspector();
  const prompt=`You are an expert NKBA-certified kitchen designer. Design a complete, buildable kitchen cabinet and appliance layout for this measured room.

ROOM (JSON):
${JSON.stringify(roomBrief())}

CATALOG:
${AI_CATALOG}

RULES:
- Fill each cabinet run wall-to-wall exactly with standard widths; use fillers (1.5"-3") against walls and at inside corners so doors clear. Never overlap items; never place base or wall cabinets across a door opening; never put wall cabinets across a window (incl. its casing).
- Sink base centered under the window if there is one, and near water/drain rough-ins. Dishwasher directly beside the sink. Range on gas or 240V rough-in when present, with a HOOD centered above it (same x and width ≥ range). Refrigerator at the end of a run, not trapped between tall pieces.
- NKBA: work aisles ≥ 42", walkways ≥ 36", 44" behind island seating; landing 12"+15" beside range, 24"+18" beside sink, 15" beside fridge; work triangle legs 4'-9'.
- Wall cabinets 30" tall at z=54 (tops at 84) unless the ceiling suggests 36" or 42". Over the fridge use a W with d 24, h 12 or 15 at z = 84 - h. Pantry/tall cabinets d 24 h 84.
- Shape preference: ${shape}. Island: ${isl}. Client priorities: ${notes||'none given'}.

Return ONLY JSON: {"name": short option name, "rationale": 2-3 sentences, "items": [ {"t":type, "wall":index, "x":inches, "w":inches, "h"?:inches, "d"?:inches, "z"?:inches, "fuel"?, "hinge"?, "lay"?} ... ], "island": null or {"cx":..,"cy":..,"w":..,"d":..,"rot":degrees,"seat":inches}}`;
  try{
    const r=await sm.json(prompt,{modelTier:'complex'});
    const items=validateAIItems(r);
    if(!items.length)throw {message:'The layout came back empty. Try again.'};
    const before=saveOption('Before AI · '+new Date().toLocaleTimeString([], {hour:'numeric',minute:'2-digit'}));
    S.items=items;const o=saveOption('AI · '+String(r.name||'Layout').slice(0,40));
    commit();const C=checks();
    UI.aiNote=String(r.rationale||'');toast('AI layout applied: '+items.length+' pieces, '+C.counts.err+' must-fix. Your previous design is saved as “'+before.name+'”.');
  }catch(e){toast(e&&e.code==='rate_limited'?'Claude is busy right now. Try again in a minute.':e&&e.code==='not_granted'?'Claude access was not allowed for this page.':'AI layout failed: '+(e&&e.message||e),'err')}
  finally{UI.aiBusy=false;if(UI.tab==='design')renderInspector()}
}
function validateAIItems(r){
  const G=geom();const out=[];const num=v=>typeof v==='number'&&isFinite(v);
  for(const x of (r&&r.items)||[]){
    if(!x||!CAT[x.t]||CAT[x.t].hidden||x.t==='ISL')continue;const w=G.walls[x.wall];if(!w||!num(x.x)||!num(x.w)||x.w<=0)continue;
    const o={wall:x.wall,x:r2(clamp(x.x,0,w.len)),w:r2(x.w)};['h','d','z'].forEach(k=>{if(num(x[k])&&x[k]>0)o[k]=r2(x[k])});
    if(x.fuel==='gas'||x.fuel==='electric')o.fuel=x.fuel;if(x.hinge==='L'||x.hinge==='R')o.hinge=x.hinge;if(['base','wall','tall'].includes(x.lay))o.lay=x.lay;
    if(x.t==='F'&&o.lay&&o.z==null){o.z=o.lay==='wall'?S.settings.wallBottom:0;o.h=o.h||(o.lay==='wall'?30:o.lay==='tall'?84:34.5);o.d=o.d||(o.lay==='wall'?12:24)}
    if(o.x+o.w>w.len+0.5)continue;
    out.push(mkItem(x.t,o));
  }
  const I=r&&r.island;if(I&&num(I.cx)&&num(I.cy)&&num(I.w)&&num(I.d)){const it=mkItem('ISL',{cx:r2(I.cx),cy:r2(I.cy),w:r2(clamp(I.w,36,144)),d:r2(clamp(I.d,24,60)),rot:num(I.rot)?I.rot:0,seat:num(I.seat)?I.seat:12});if(itemPoly(it).every(p=>inPoly(p,G.P)))out.push(it)}
  return out;
}
async function aiImport(file){
  const sm=CLOUD.sample;if(!sm)return;
  UI.aiImportBusy=true;renderMeasure();
  const prompt=`This image is a photo of a hand sketch, a floor plan, or a measured drawing of a room (usually a kitchen or bath). Extract the room shell and anything pinned on the walls.

Walk the perimeter CLOCKWISE (as seen from above), starting at the left end of the main cabinet wall (or the top-left wall if unclear). Convert every dimension to inches (12' 6" = 150). Use 90 for corners that look square; give a different angle only if one is written or clearly drawn.

Return ONLY JSON:
{"ceiling": inches or null,
 "walls": [{"len": inches, "note": short label}],
 "corners": [interior angle in degrees at the END of each wall, same order],
 "pins": [{"type": one of ${Object.keys(UTIL).join('|')}, "wall": wall index, "x": inches from that wall's start, "z": height in inches (bottom for window/door), "w": width for window/door/soffit, "h": height for window/door/soffit, "note": text}],
 "confidence": "high"|"medium"|"low", "unreadable": [list anything you had to guess]}`;
  try{
    const r=await sm.json(prompt,{images:[file],modelTier:'complex'});
    if(!r||!Array.isArray(r.walls)||r.walls.length<3)throw {message:'Could not find at least three walls in that image.'};
    UI.aiImport=r;toast('Read '+r.walls.length+' walls — review and apply below');
  }catch(e){toast(e&&e.code==='rate_limited'?'Claude is busy right now. Try again in a minute.':e&&e.code==='image_rejected'?'That image type is not supported. Use a JPEG or PNG photo.':'Could not read the drawing: '+(e&&e.message||e),'err')}
  finally{UI.aiImportBusy=false;if(UI.tab==='measure')renderMeasure()}
}
function aiImportCardHTML(){
  if(!CLOUD.sample)return '';
  const r=UI.aiImport;
  return `<div class="card stack"><div class="eyebrow">AI floor plan recognition</div>
    <p class="hint" style="margin:0">Snap a photo of a hand-drawn sketch, an existing floor plan or a measured drawing. Claude reads the walls, angles, openings and rough-ins into this project.</p>
    ${CLOUD.sampleImages?`<button class="btn ${r?'':'pri'}" data-act="aiimport" ${UI.aiImportBusy?'disabled':''}>${UI.aiImportBusy?'Reading drawing…':'Import from photo or sketch'}</button>`:'<p class="hint">Image reading is not available in this view.</p>'}
    ${r?`<div class="stack" style="gap:6px;border-top:1px solid var(--line);padding-top:8px"><b>${r.walls.length} walls · ${(r.pins||[]).length} pins · confidence ${esc(r.confidence||'?')}</b>
      <div class="mono" style="font-size:12.5px">${r.walls.map((w,i)=>'W'+(i+1)+' '+fmt(+w.len,'ft')+(w.note?' ('+esc(w.note)+')':'')).join('<br>')}</div>
      ${r.unreadable&&r.unreadable.length?`<p class="hint" style="margin:0">Guessed: ${esc(r.unreadable.join('; '))}</p>`:''}
      <div class="row"><button class="btn pri sm" data-act="aiapply">Replace room shell</button><button class="btn sm ghost" data-act="aidiscard">Discard</button></div>
      <p class="hint" style="margin:0">Cabinets stay; ones on walls that no longer exist are removed. Undo restores everything.</p></div>`:''}
  </div>`;
}
function applyAIImport(){
  const r=UI.aiImport;if(!r)return;
  S.walls=r.walls.map(w=>({len:clamp(+w.len||96,12,2400),note:String(w.note||'')}));
  S.corners=S.walls.map((_,i)=>({ang:clamp(+(r.corners||[])[i]||90,30,330)}));
  if(+r.ceiling>60)S.ceiling=+r.ceiling;
  S.utils=(r.pins||[]).filter(u=>UTIL[u.type]&&u.wall>=0&&u.wall<S.walls.length).map(u=>({id:uid(),type:u.type,wall:u.wall,x:+u.x||0,z:+u.z||0,w:+u.w||0,h:+u.h||0,trim:(u.type==='window'||u.type==='door')?3.5:0,note:String(u.note||'')}));
  S.items=S.items.filter(i=>i.wall==null||i.wall<S.walls.length);
  UI.aiImport=null;commit();toast('Room shell replaced. Check the closure and corner angles.');
}

/* ---------- captures (presentation images, kept on this device) ---------- */
let CAPS=[],CAPS_FOR=null;
async function loadCaps(){if(CAPS_FOR===S.id)return;CAPS_FOR=S.id;CAPS=(await idbGet('caps:'+S.id))||[]}
async function addCapture(url,label){await loadCaps();CAPS.push({id:uid(),url,label,at:Date.now()});await idbSet('caps:'+S.id,CAPS);toast('Captured for the presentation ('+CAPS.length+')')}

/* ---------- DOCS: specs, schedules, attachments, branding ---------- */
function renderDocs(){
  const st=S.settings,sp=S.specs;
  const sel=(key,map,label,lab=v=>Array.isArray(v)?v[0]:v)=>`<label class="f">${label}<select class="in" id="d-${key}" data-bind="settings.${key}" data-kind="text">${Object.entries(map).map(([k,v])=>`<option value="${k}" ${st[key]===k?'selected':''}>${esc(lab(v))}</option>`).join('')}</select></label>`;
  const txt=(key,label,ph='')=>`<label class="f">${label}<input class="in" id="d-sp-${key}" data-bind="specs.${key}" data-kind="text" value="${esc(sp[key]||'')}" placeholder="${esc(ph)}"></label>`;
  const cabRows=[...S.items].filter(i=>!CAT[i.t].app).sort((a,b)=>(a.wall??99)-(b.wall??99)||a.z-b.z||a.x-b.x).map(it=>`<tr><td class="mono">${esc(CAT[it.t].sku(it))}</td><td>${it.wall==null?'Island':'W'+(it.wall+1)}</td><td>${esc(CAT[it.t].name)}</td><td class="n">${fmt(it.w)}×${fmt(it.h)}×${fmt(it.d)}</td><td>${esc(FINISH[it.finish||(it.t==='ISL'&&st.islandFinish)||st.finish][0])}</td><td>${esc(it.note||'')}</td></tr>`).join('');
  const appRows=S.items.filter(i=>CAT[i.t].app).map(it=>{const m=MODELS[it.t]?.[it.model];return `<tr><td class="mono">${esc(CAT[it.t].sku(it))}</td><td>${it.wall==null?'Island':'W'+(it.wall+1)}</td><td>${esc(m?m[0]:CAT[it.t].name)}${it.t==='RG'?' · '+it.fuel:''}</td><td class="n">${fmt(it.w)}×${fmt(it.h)}×${fmt(it.d)}</td><td><input class="in" id="d-ap-${it.id}" data-bind="it.${it.id}.note" data-kind="text" value="${esc(it.note||'')}" placeholder="Model #"></td></tr>`}).join('');
  const dwRows=S.utils.filter(u=>u.type==='window'||u.type==='door').map((u,k)=>`<tr><td class="mono">${u.type==='door'?'D':'WN'}${k+1}</td><td>W${u.wall+1}</td><td>${UTIL[u.type].n}</td><td class="n">${fmt(u.w)}×${fmt(u.h)}</td><td class="n">${fmt(u.x)}</td><td class="n">${fmt(u.z)}</td><td class="n">${fmt(+u.trim||0)}</td></tr>`).join('');
  const roughRows=S.utils.filter(u=>!UTIL[u.type]?.box).sort((a,b)=>a.wall-b.wall||a.x-b.x).map(u=>`<tr><td>W${u.wall+1}</td><td>${UTIL[u.type].n}</td><td class="n">${fmt(u.x)}</td><td class="n">${fmt(u.z)}</td><td>${esc(u.note||'')}</td></tr>`).join('');
  const docs=S.docs.map(d=>`<div class="docrow">${d.thumb?`<img src="${d.thumb}" alt="" class="dthumb">`:`<span class="dthumb dext">${esc((d.name.split('.').pop()||'').slice(0,4).toUpperCase())}</span>`}
    <div style="flex:1;min-width:0"><b style="display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(d.name)}</b><span class="hint">${(d.size/1024).toFixed(0)} KB · ${d.asset?'saved to your account':'on this device'}</span></div>
    <select class="in" id="d-tag-${d.id}" data-bind="docs.${S.docs.indexOf(d)}.tag" data-kind="text" style="width:auto"><option value="">General</option>${S.walls.map((w,i)=>`<option value="W${i+1}" ${d.tag==='W'+(i+1)?'selected':''}>W${i+1}</option>`).join('')}<option value="appliance" ${d.tag==='appliance'?'selected':''}>Spec sheet</option><option value="contract" ${d.tag==='contract'?'selected':''}>Contract</option></select>
    <button class="btn sm" data-act="docopen" data-id="${d.id}">Open</button><button class="btn sm ghost danger" data-act="docdel" data-id="${d.id}" aria-label="Remove ${esc(d.name)}">✕</button></div>`).join('');
  $('#v-docs').innerHTML=`<div class="stack">
    <div><div class="eyebrow">Files &amp; specs</div><h2>Choices, lists and files for this job</h2></div>
    <div class="grid2">
      <div class="card stack"><h3>Cabinet specification</h3>
        <div class="fgrid"><label class="f">Cabinet supplier<select class="in" id="d-supplier" data-bind="settings.supplier" data-kind="text" data-after="supplier">${Object.entries(SUPPLIER).map(([k,v])=>`<option value="${k}" ${st.supplier===k?'selected':''}>${esc(v[0])}</option>`).join('')}</select></label>
        ${isCabinotch()?`<label class="f">Face frame wood<select class="in" id="d-ff" data-bind="settings.ffSpecies" data-kind="text">${FF_SPECIES.map(x=>`<option ${st.ffSpecies===x?'selected':''}>${esc(x)}</option>`).join('')}</select></label>`:''}${sel('line',LINE,'Cabinet line')}${sel('construction',CONSTR,'Construction')}${sel('doorStyle',DOOR,'Door style')}${sel('finish',FINISH,'Cabinet finish')}
        <label class="f">Island finish<select class="in" id="d-islandFinish" data-bind="settings.islandFinish" data-kind="text"><option value="">Same as cabinets</option>${Object.entries(FINISH).map(([k,v])=>`<option value="${k}" ${st.islandFinish===k?'selected':''}>${v[0]}</option>`).join('')}</select></label>${sel('hw',HW,'Hardware')}</div>
        <label class="row" style="font-size:13px"><input type="checkbox" id="d-glass" data-bind="settings.glassUppers" data-kind="bool" ${st.glassUppers?'checked':''}> Glass-front wall cabinets (lit interiors)</label>
        ${txt('accessories','Accessories','Roll-outs, tray dividers, spice pull-out…')}</div>
      <div class="card stack"><h3>Countertops &amp; fixtures</h3>
        <div class="fgrid">${sel('counter',COUNTER,'Material')}${sel('edge',EDGE,'Edge profile')}
        <label class="f">Thickness<select class="in" id="d-thick" data-bind="settings.ctThick" data-kind="num">${[[1.25,'3 cm (1¼")'],[1.5,'1½" laminated'],[0.75,'2 cm (¾")'],[2,'2" build-up']].map(([v,l])=>`<option value="${v}" ${st.ctThick===v?'selected':''}>${l}</option>`).join('')}</select></label>${sel('splash',SPLASH,'Backsplash')}${sel('sink',SINK,'Sink')}
        <label class="row" style="font-size:13px;align-self:end;min-height:34px"><input type="checkbox" id="d-wf" data-bind="settings.waterfall" data-kind="bool" ${st.waterfall?'checked':''}> Waterfall island ends</label></div>
        ${txt('faucet','Faucet','Pull-down, single handle')}${txt('lighting','Lighting','Pendants, under-cabinet LED tape…')}${txt('appliances','Appliance notes','Owner-supplied, verify cutouts…')}</div>
      <div class="card stack"><h3>Budget &amp; client</h3>
        <label class="f">Client budget<input class="in mono" id="d-budget" data-bind="settings.budget" data-kind="num" value="${st.budget||''}" placeholder="0" inputmode="decimal"></label>
        <label class="f">Client<input class="in" id="d-client" data-bind="client" data-kind="text" value="${esc(S.client||'')}"></label>
        <label class="f">Site address<input class="in" id="d-addr" data-bind="address" data-kind="text" value="${esc(S.address||'')}"></label>
        <label class="f">Project notes<textarea class="in" id="d-notes" rows="4" data-bind="specs.notes" data-kind="text">${esc(sp.notes||'')}</textarea></label></div>
      <div class="card stack"><h3>Your brand</h3><p class="hint" style="margin:0">Used on proposals and presentations.</p>
        <div class="row">${st.logo?`<img src="${st.logo}" alt="Logo" style="max-height:48px;max-width:160px;border:1px solid var(--line);border-radius:4px;background:#fff;padding:4px">`:''}<button class="btn sm" data-act="logo">${st.logo?'Replace logo':'Upload logo'}</button>${st.logo?'<button class="btn sm ghost danger" data-act="logodel">Remove</button>':''}</div>
        <div class="fgrid"><label class="f">Company<input class="in" id="d-co" data-bind="settings.company" data-kind="text" value="${esc(st.company)}"></label>
        <label class="f">Brand color<input class="in" type="color" id="d-brand" data-bind="settings.brand" data-kind="text" value="${esc(st.brand||'#1f4e79')}" style="padding:2px"></label>
        <label class="f">Phone<input class="in" id="d-ph" data-bind="settings.phone" data-kind="text" value="${esc(st.phone)}"></label>
        <label class="f">Email / web<input class="in" id="d-em" data-bind="settings.email" data-kind="text" value="${esc(st.email||'')}"></label>
        <label class="f">License #<input class="in" id="d-lic" data-bind="settings.license" data-kind="text" value="${esc(st.license||'')}"></label></div></div>
    </div>
    <div class="card stack"><div class="row" style="justify-content:space-between"><div><h3>Files &amp; site photos</h3><p class="hint" style="margin:0">Site photos, appliance spec sheets, contracts. ${CLOUD.assets?'Saved to your account with this project.':'Kept on this device.'}</p></div><button class="btn pri sm" data-act="docadd">+ Add files</button></div>
      ${docs||'<p class="hint">No files yet. Photograph each wall during the site visit and tag it here.</p>'}</div>
    <div class="card stack pro"><div class="row" style="justify-content:space-between"><h3>Schedules</h3><button class="btn sm" data-act="csvsched">Export all schedules (CSV)</button></div>
      <div class="eyebrow">Cabinet schedule</div><div class="tw"><table class="t"><thead><tr><th>SKU</th><th>Location</th><th>Type</th><th class="n">W × H × D</th><th>Finish</th><th>Spec</th></tr></thead><tbody>${cabRows||'<tr><td colspan="6" class="muted">No cabinets.</td></tr>'}</tbody></table></div>
      <div class="eyebrow">Appliance &amp; fixture schedule</div><div class="tw"><table class="t"><thead><tr><th>Tag</th><th>Location</th><th>Item</th><th class="n">W × H × D</th><th>Model / spec</th></tr></thead><tbody>${appRows||'<tr><td colspan="5" class="muted">No appliances or fixtures.</td></tr>'}</tbody></table></div>
      <div class="eyebrow">Door &amp; window schedule</div><div class="tw"><table class="t"><thead><tr><th>Tag</th><th>Wall</th><th>Type</th><th class="n">W × H</th><th class="n">From left</th><th class="n">Sill / AFF</th><th class="n">Casing</th></tr></thead><tbody>${dwRows||'<tr><td colspan="7" class="muted">No openings pinned.</td></tr>'}</tbody></table></div>
      <div class="eyebrow">Rough-in schedule (all walls)</div><div class="tw"><table class="t"><thead><tr><th>Wall</th><th>Item</th><th class="n">From left</th><th class="n">AFF</th><th>Note</th></tr></thead><tbody>${roughRows||'<tr><td colspan="5" class="muted">No rough-ins pinned.</td></tr>'}</tbody></table></div>
    </div></div>`;
}
async function addDocs(files){
  for(const f of files){
    const d={id:uid(),name:f.name,type:f.type,size:f.size,tag:'',at:Date.now()};
    if(f.type.startsWith('image/'))d.thumb=await thumbOf(f,160);
    let ok=false;
    if(CLOUD.assets){try{const r=await CLOUD.assets.upload(f);d.asset=r.id;ok=true}catch(e){}}
    if(!ok){await idbSet('doc:'+d.id,f);d.local=1}
    S.docs.push(d);
  }
  commit();toast('Added '+files.length+' file'+(files.length>1?'s':''));
}
function thumbOf(file,max){return new Promise(res=>{const im=new Image();im.onload=()=>{const k=Math.min(1,max/Math.max(im.width,im.height));const c=document.createElement('canvas');c.width=im.width*k;c.height=im.height*k;c.getContext('2d').drawImage(im,0,0,c.width,c.height);URL.revokeObjectURL(im.src);res(c.toDataURL('image/jpeg',0.75))};im.onerror=()=>res('');im.src=URL.createObjectURL(file)})}

/* ---------- PRESENT: client-ready story ---------- */
const LIGHT_TOKENS='--floor:#f6f7f3;--wallfill:#36444c;--cab:#fffefb;--cab-stroke:#2f3e47;--wallcab:rgba(34,96,143,.07);--tall:#dde3de;--app:#cdd5d9;--isl:#f5ecd9;--hatch:#9aa59f;--muted:#57656d;--ink:#1b262d;--err:#c23d26;--warn:#a96f00;--blue:#22608f;--accent:#f0b400;--panel:#f8f9f6;--dim:#2f3e47;--line2:#aab4ad;--blue-soft:rgba(34,96,143,.12)';
function presentationHTML(){
  const st=S.settings,B=bom(),G=geom(),brand=esc(st.brand||'#1f4e79');
  const keepUI={...UI};UI.showClr=false;UI.showTri=false;UI.showUpper=true;UI.showDims=true;const keepSel=SEL;SEL=null;
  const b=G.bbox,m=34;const plan=`<svg class="plan" viewBox="${b.minx-m} ${-b.maxy-m} ${b.w+2*m} ${b.h+2*m}" xmlns="http://www.w3.org/2000/svg">${planInner(false)}</svg>`;
  Object.assign(UI,keepUI);SEL=keepSel;
  const elevs=S.walls.map((w,i)=>i).filter(i=>S.items.some(it=>it.wall===i)).map(i=>{const E=elevData(i);return `<figure><svg class="el" viewBox="${E.vb}" xmlns="http://www.w3.org/2000/svg">${E.s}</svg><figcaption>${esc(E.title)}${S.walls[i].note?' — '+esc(S.walls[i].note):''}</figcaption></figure>`}).join('')+
    S.items.filter(i=>i.wall==null).map(it=>{const E=islandElev(it,'front');return `<figure><svg class="el" viewBox="${E.vb}" xmlns="http://www.w3.org/2000/svg">${E.s}</svg><figcaption>Island front</figcaption></figure>`}).join('');
  const hero=CAPS[0];
  const optRows=S.options.length?[{name:'Current design',items:S.items,sel:currentSel(),id:'_cur'},...S.options].map(o=>{const r=optionSummary(o);return `<tr><td>${esc(o.name)}</td><td>${esc(FINISH[r.sel.finish]?.[0]||'')}${r.sel.islandFinish&&r.sel.islandFinish!==r.sel.finish?' / '+esc(FINISH[r.sel.islandFinish][0]):''}</td><td>${esc(COUNTER[r.sel.counter]?.[0]||'')}</td><td class="n">${money(r.total)}</td></tr>`}).join(''):'';
  const C=checks();
  const sel=[['Cabinets',`${LINE[st.line][0]}, ${CONSTR[st.construction][0].toLowerCase()}, ${DOOR[st.doorStyle].toLowerCase()} doors`],['Finish',FINISH[st.finish][0]+(st.islandFinish&&st.islandFinish!==st.finish?' with '+FINISH[st.islandFinish][0].toLowerCase()+' island':'')],
    ['Countertops',`${COUNTER[st.counter][0]}, ${EDGE[st.edge][0].toLowerCase()} edge${st.waterfall?', waterfall island':''}`],['Backsplash',SPLASH[st.splash]],['Sink',SINK[st.sink]],['Hardware',HW[st.hw][0]],
    ...(S.specs.faucet?[['Faucet',S.specs.faucet]]:[]),...(S.specs.lighting?[['Lighting',S.specs.lighting]]:[]),...(S.specs.accessories?[['Accessories',S.specs.accessories]]:[])];
  const swatches=[FINISH[st.finish],st.islandFinish?FINISH[st.islandFinish]:null,COUNTER[st.counter],HW[st.hw],FLOOR[st.floor],WALLC[st.wallc]].filter(Boolean);
  return `<section class="cover" style="border-color:${brand}">
      <div class="brandrow">${st.logo?`<img src="${st.logo}" alt="" class="logo">`:`<b style="color:${brand}">${esc(st.company||'')}</b>`}<span>${new Date().toLocaleDateString('en-US',{year:'numeric',month:'long',day:'numeric'})}</span></div>
      <h1>${esc(S.name)}</h1><p class="sub">${esc(layoutName())} · prepared for ${esc(S.client||'our client')}${S.address?' · '+esc(S.address):''}</p>
      ${hero?`<img class="hero" src="${hero.url}" alt="${esc(hero.label)}">`:''}
    </section>
    <section><h2>The design</h2><p>${esc(layoutName())} plan in a ${fmt(b.w,'ft')} × ${fmt(b.h,'ft')} room with a ${fmt(S.ceiling)} ceiling. ${S.items.filter(i=>!CAT[i.t].app&&CAT[i.t].cls!=='filler').length} cabinets, ${B.sq.toFixed(0)} sq ft of ${esc(COUNTER[st.counter][0].toLowerCase())}${C.tri?', and a '+fmt(C.tri.sum,'ft')+' work triangle':''}. Every layout is checked against NKBA planning guidelines and code clearances${C.counts.err?' — '+C.counts.err+' item(s) still being resolved':' and passes'}.</p>
      <div class="sw">${swatches.map(s=>`<div><i style="background:${s[1]}"></i>${esc(s[0])}</div>`).join('')}</div></section>
    ${CAPS.length>1?`<section><h2>Views</h2><div class="gal">${CAPS.slice(1).map(c=>`<figure><img src="${c.url}" alt="${esc(c.label)}"><figcaption>${esc(c.label)}</figcaption></figure>`).join('')}</div></section>`:''}
    <section><h2>Floor plan</h2><div class="planbox">${plan}</div></section>
    ${elevs?`<section><h2>Elevations</h2><div class="els">${elevs}</div></section>`:''}
    <section><h2>Selections</h2><table>${sel.map(([k,v])=>`<tr><th>${esc(k)}</th><td>${esc(v)}</td></tr>`).join('')}</table></section>
    ${optRows?`<section><h2>Options</h2><table><thead><tr><th>Option</th><th>Cabinets</th><th>Countertop</th><th class="n">Investment</th></tr></thead><tbody>${optRows}</tbody></table></section>`:''}
    <section><h2>Investment</h2><table><tr><td>Cabinetry, countertops, materials &amp; installation</td><td class="n">${money(B.sub+B.mk)}</td></tr><tr><td>Sales tax</td><td class="n">${money(B.tax)}</td></tr><tr class="tot"><td>Total</td><td class="n">${money(B.total)}</td></tr>${st.budget?`<tr><td>Your budget</td><td class="n">${money(st.budget)}</td></tr>`:''}<tr><td>Deposit to schedule (${st.deposit}%)</td><td class="n">${money(B.total*st.deposit/100)}</td></tr></table></section>
    <section><h2>Next steps</h2><ol><li>Review the design and choose an option.</li><li>Sign the proposal and place the deposit to lock in pricing and lead times.</li><li>Final field verification of all measurements before ordering.</li><li>Cabinet delivery, install, countertop template, and finishing.</li></ol>
      <p class="contact" style="border-color:${brand}"><b>${esc(st.company||'')}</b>${st.phone?' · '+esc(st.phone):''}${st.email?' · '+esc(st.email):''}${st.license?' · Lic. '+esc(st.license):''}</p></section>`;
}
const PRESENT_CSS=`.pres{background:#fff;color:#1d2930;font-family:var(--f-ui),Helvetica,Arial,sans-serif;max-width:980px;margin:0 auto;border-radius:8px;border:1px solid var(--line);overflow:hidden}
.pres section{padding:clamp(18px,4vw,44px);border-bottom:1px solid #e4e6e2}
.pres h1{font-family:var(--f-disp),'Arial Narrow',sans-serif;font-size:clamp(34px,6vw,58px);line-height:1;margin:18px 0 6px;letter-spacing:.01em}
.pres h2{font-family:var(--f-disp),'Arial Narrow',sans-serif;font-size:26px;letter-spacing:.03em;text-transform:uppercase;margin:0 0 12px}
.pres .cover{border-top:8px solid}.pres .sub{color:#55626a;margin:0 0 18px}.pres .brandrow{display:flex;justify-content:space-between;align-items:center;gap:12px;color:#55626a;font-size:13px}
.pres .logo{max-height:56px;max-width:220px}.pres .hero{width:100%;border-radius:6px;display:block}
.pres .sw{display:flex;flex-wrap:wrap;gap:14px;margin-top:10px}.pres .sw div{display:flex;align-items:center;gap:8px;font-size:13px}.pres .sw i{width:28px;height:28px;border-radius:50%;border:1px solid #ccc;display:inline-block}
.pres .gal{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,280px),1fr));gap:12px}.pres figure{margin:0}.pres figure img{width:100%;border-radius:6px;display:block}.pres figcaption{font-size:12px;color:#55626a;margin-top:4px}
.pres .planbox svg{width:100%;height:auto;max-height:640px;display:block}.pres .els{display:grid;gap:18px}.pres .els svg{width:100%;height:auto;display:block}
.pres table{border-collapse:collapse;width:100%;font-size:14px}.pres td,.pres th{padding:8px 6px;border-bottom:1px solid #e1e4e0;text-align:left;vertical-align:top}.pres th{width:30%;color:#55626a;font-weight:500}
.pres .n{text-align:right;font-family:var(--f-mono),Menlo,monospace}.pres tr.tot td{font-weight:700;font-size:17px;border-top:2px solid #1d2930}.pres .contact{border-left:4px solid;padding-left:12px;margin-top:18px}`;
async function renderPresent(){
  await loadCaps();
  if(UI.tab!=='present')return;
  $('#v-present').innerHTML=`<div class="stack">
    <div class="row" style="justify-content:space-between"><div><div class="eyebrow">Show the client</div><h2>Presentation for the homeowner</h2></div>
      <div class="row"><button class="btn pri" data-act="dlpres">Save presentation to send</button><button class="btn" data-act="tab" data-v="view3d">📷 Add more 3D pictures</button></div></div>
    <div class="card stack"><div class="row" style="justify-content:space-between"><h3>Captured views · ${CAPS.length}</h3><span class="hint">In “See it in 3D”, tap “📷 Add to client show”. The first picture becomes the cover.</span></div>
      <div class="caps">${CAPS.map((c,k)=>`<figure><img src="${c.url}" alt="${esc(c.label)}"><figcaption><span>${k===0?'<b>Cover</b> · ':''}${esc(c.label)}</span><span>${k?`<button class="btn sm ghost" data-act="capfirst" data-id="${c.id}">Make cover</button>`:''}<button class="btn sm ghost danger" data-act="capdel" data-id="${c.id}" aria-label="Remove">✕</button></span></figcaption></figure>`).join('')||'<p class="hint">No views captured yet. The presentation still includes the plan, elevations, selections and investment.</p>'}</div></div>
    <div class="pres" id="pres"><style>${PRESENT_CSS}</style>${presentationHTML()}</div></div>`;
}
function presentationFile(){
  const css=[...document.styleSheets].flatMap(s=>{try{return [...s.cssRules].map(r=>r.cssText).filter(t=>/^\.(el|pl-)/.test(t))}catch(e){return []}}).join('\n');
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(S.name)} — design presentation</title>
<style>:root{${LIGHT_TOKENS};--f-ui:Helvetica,Arial,sans-serif;--f-disp:'Arial Narrow',Helvetica,sans-serif;--f-mono:Menlo,monospace;--line:#e1e4e0}body{margin:0;background:#eef0ec;padding:20px 0}${PRESENT_CSS}${css}.el text{font-family:Menlo,monospace}</style></head><body><div class="pres">${presentationHTML()}</div></body></html>`;
}

/* ---------- DXF (CAD) export: floor plan with walls, cabinets, openings, labels ---------- */
function dxfPlan(){
  const G=geom();const L=[];const add=(...a)=>L.push(...a.map(String));
  const line=(a,b,layer)=>add('0','LINE','8',layer,'10',r2(a.x),'20',r2(a.y),'30','0','11',r2(b.x),'21',r2(b.y),'31','0');
  const poly=(P,layer)=>P.forEach((p,k)=>line(p,P[(k+1)%P.length],layer));
  const text=(p,h,t,layer,rot=0)=>add('0','TEXT','8',layer,'10',r2(p.x),'20',r2(p.y),'30','0','40',h,'1',String(t).replace(/[\r\n]+/g,' '),'50',r2(rot));
  add('0','SECTION','2','HEADER','9','$INSUNITS','70','1','0','ENDSEC','0','SECTION','2','ENTITIES');
  for(const w of G.walls){const T=4.5;const o={x:-w.n.x*T,y:-w.n.y*T};line(w.a,w.b,'A-WALL');line({x:w.a.x+o.x,y:w.a.y+o.y},{x:w.b.x+o.x,y:w.b.y+o.y},'A-WALL');
    const m={x:(w.a.x+w.b.x)/2-w.n.x*14,y:(w.a.y+w.b.y)/2-w.n.y*14};text(m,4,'W'+(w.i+1)+' '+fmt(w.len,'ft').replace('"','in'),'A-ANNO',Math.atan2(w.d.y,w.d.x)/D2R)}
  for(const u of S.utils){const w=G.walls[u.wall];if(!w)continue;const at=x=>({x:w.a.x+w.d.x*x,y:w.a.y+w.d.y*x});
    if(u.type==='window'||u.type==='door'){line(at(u.x),at(u.x+u.w),u.type==='door'?'A-DOOR':'A-GLAZ')}
    else{const p=at(u.x);text({x:p.x+w.n.x*2,y:p.y+w.n.y*2},2.5,UTIL[u.type].s,'E-DEVC')}}
  for(const it of S.items){const P=itemPoly(it);if(!P)continue;const layer=CAT[it.t].app?'Q-APPL':isUpper(it)?'Q-CASE-UPPR':'Q-CASE';poly(P,layer);
    if(hasCT(it)&&it.wall!=null)poly(ctPoly(it),'Q-CTOP');
    const f=frame(it);const c={x:f.o.x+f.U.x*it.w/2+f.V.x*it.d/2,y:f.o.y+f.U.y*it.w/2+f.V.y*it.d/2};text(c,Math.min(3,it.w/5),CAT[it.t].sku(it),'Q-ANNO',Math.atan2(f.U.y,f.U.x)/D2R)}
  for(const n of S.notes||[])text(n,3,n.text,'A-NOTE');
  add('0','ENDSEC','0','EOF');return L.join('\r\n');
}

/* ---------- actions for the new modules ---------- */
Object.assign(ACT,{
  addnote:()=>{UI.addNote=!UI.addNote;renderDesign();if(UI.addNote)toast('Tap the plan where the note should go')},
  delnote:(el)=>{S.notes=S.notes.filter(n=>n.id!==el.dataset.id);SEL=null;commit()},
  libitem:()=>{const it=selItem();if(!it)return;const base={...clone(it)};delete base.id;if(it.wall!=null)base.x=0;LIB.push({id:uid(),name:CAT[it.t].sku(it)+(it.finish?' · '+FINISH[it.finish][0]:''),kind:it.wall==null?'island':'run',items:[base]});saveLibrary();renderDesign();toast('Saved to your library')},
  libwall:()=>{const j=UI.wall;const its=S.items.filter(i=>i.wall===j);if(!its.length){toast('W'+(j+1)+' has nothing to save');return}const x0=Math.min(...its.map(i=>i.x));LIB.push({id:uid(),name:'W'+(j+1)+' run · '+fmt(Math.max(...its.map(i=>i.x+i.w))-x0),kind:'run',items:its.map(i=>{const c=clone(i);delete c.id;c.x=r2(c.x-x0);return c})});saveLibrary();renderDesign();toast('Saved W'+(j+1)+' run ('+its.length+' pieces) to your library')},
  libinsert:(el)=>{const g=LIB.find(x=>x.id===el.dataset.id);if(g){UI.wall=+$('#tgtWall').value||0;libInsert(g)}},
  libdel:(el)=>{LIB=LIB.filter(x=>x.id!==el.dataset.id);saveLibrary();renderDesign()},
  optsave:()=>{const n=($('#optName')?.value||'').trim();const o=saveOption(n);commit();toast('Saved option “'+o.name+'”')},
  optload:(el)=>{const o=S.options.find(x=>x.id===el.dataset.id);if(!o)return;loadOption(o);commit();toast('Loaded “'+o.name+'” — undo to go back')},
  optdel:(el)=>{S.options=S.options.filter(x=>x.id!==el.dataset.id);commit()},
  ailayout:()=>aiLayout(),
  cmpopts:()=>{UI.est='compare';UI.tab='estimate';location.hash='estimate';render()},
  aiimport:()=>{const f=$('#aiFile');f.value='';f.click()},
  aiapply:()=>applyAIImport(),
  aidiscard:()=>{UI.aiImport=null;renderMeasure()},
  paint:()=>{UI.paint=UI.paint?null:(S.settings.islandFinish||'navy');render3DView()},
  paintpick:(el)=>{UI.paint=el.dataset.v;render3DView()},
  paintreset:()=>{S.items.forEach(i=>delete i.finish);commit();toast('All cabinets back to the project finish')},
  capture:()=>capture(),
  shot:async(el)=>{toast(el.dataset.v==='4k'?'Rendering 4K image…':'Rendering image…');const b=await shotBlob(el.dataset.v,'image/png');if(b)saveFile((el.dataset.v==='4k'?'render-4k-':'render-')+slug()+'.png',b,'image/png')},
  pano:()=>panorama(),
  importModel:()=>{const f=$('#modelFile');f.value='';f.click()},
  dxf:()=>saveFile('plan-'+slug()+'.dxf',dxfPlan(),'application/dxf'),
  docadd:()=>{const f=$('#docFile');f.value='';f.click()},
  docopen:async(el)=>{const d=S.docs.find(x=>x.id===el.dataset.id);if(!d)return;
    if(d.asset){const a=document.createElement('a');a.href='/_blob/'+d.asset;a.target='_blank';a.rel='noopener';document.body.appendChild(a);a.click();a.remove();return}
    const blob=await idbGet('doc:'+d.id);if(!blob){toast('That file is stored on another device','err');return}
    if(IN_FRAME)saveFile(d.name,blob,d.type);else window.open(URL.createObjectURL(blob),'_blank')},
  docdel:async(el)=>{const d=S.docs.find(x=>x.id===el.dataset.id);if(!d)return;if(d.asset&&CLOUD.assets){try{await CLOUD.assets.delete(d.asset)}catch(e){}}if(d.local)idbDel('doc:'+d.id);S.docs=S.docs.filter(x=>x!==d);commit()},
  logo:()=>{const f=$('#logoFile');f.value='';f.click()},
  logodel:()=>{S.settings.logo='';commit()},
  csvsched:()=>{const st=S.settings;const rows=[['Schedule','Tag','Location','Type','W','H','D','Spec / note']];
    S.items.forEach(it=>rows.push([CAT[it.t].app?'Appliance/fixture':'Cabinet',CAT[it.t].sku(it),it.wall==null?'Island':'W'+(it.wall+1),CAT[it.t].name,it.w,it.h,it.d,it.note||'']));
    S.utils.forEach(u=>rows.push([UTIL[u.type].box?'Opening':'Rough-in',UTIL[u.type].s,'W'+(u.wall+1),UTIL[u.type].n,u.w||'',u.h||'',u.z,(u.box?'':'from left '+u.x+'; ')+(u.note||'')]));
    saveFile('schedules-'+slug()+'.csv',csv(rows),'text/csv')},
  dlpres:()=>saveFile('presentation-'+slug()+'.html',presentationFile(),'text/html'),
  capdel:async(el)=>{CAPS=CAPS.filter(c=>c.id!==el.dataset.id);await idbSet('caps:'+S.id,CAPS);renderPresent()},
  capfirst:async(el)=>{const c=CAPS.find(x=>x.id===el.dataset.id);CAPS=[c,...CAPS.filter(x=>x!==c)];await idbSet('caps:'+S.id,CAPS);renderPresent()}
});
document.addEventListener('change',async e=>{
  const t=e.target;
  if(t.id==='rstyle'){S.settings.renderStyle=t.value;commit();return}
  if(t.id==='aiFile'&&t.files[0])aiImport(t.files[0]);
  if(t.id==='modelFile'&&t.files[0])importModel(t.files[0]);
  if(t.id==='docFile'&&t.files.length)addDocs([...t.files]);
  if(t.id==='logoFile'&&t.files[0]){const u=await thumbOf(t.files[0],320);if(u){S.settings.logo=u.replace('image/jpeg','image/jpeg');commit()}}
});
