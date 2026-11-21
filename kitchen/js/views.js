/* ---------- ESTIMATE ---------- */
function renderEstimate(){
  const B=bom(),st=S.settings;
  const tabs=`<div class="seg" role="group" aria-label="Estimate view">${[['bom','Materials & price'],['quote','Quote for the customer'],['order',isCabinotch()?'Order from Cabinotch':'Cabinet order'],['compare','Compare options'],['cut','Cut list']].map(([k,l])=>`<button data-act="est" data-v="${k}" aria-pressed="${UI.est===k}" class="${k==='compare'||k==='cut'?'pro':''}">${l}</button>`).join('')}</div>`;
  let body='';
  if(UI.est==='bom'){
    let lastG='';
    const rows=B.lines.map((l,k)=>{const g=l.grp!==lastG?`<tr class="grp"><td colspan="6">${esc(l.grp)}</td></tr>`:'';lastG=l.grp;
      const priceIn=l.key?`<input class="in mono" style="width:96px;text-align:right" id="pr-${k}" data-bind="settings.${l.key.startsWith('over.')?'over.'+l.key.slice(5):l.key.replace(/^price\./,'price.')}" data-kind="num" ${l.key.startsWith('over.')?'data-null="delete"':''} value="${l.price}" aria-label="Unit cost ${esc(l.sku)}" inputmode="decimal">`:money(l.price);
      return g+`<tr><td class="mono">${esc(l.sku)}</td><td>${esc(l.desc)}</td><td class="n">${l.qty}</td><td>${esc(l.unit)}</td><td class="n">${priceIn}</td><td class="n">${money(l.qty*l.price)}</td></tr>`}).join('');
    body=`<div class="egrid"><div class="card stack"><div class="row" style="justify-content:space-between"><h3>Everything in this job</h3><div class="row"><button class="btn sm" data-act="csvbom">Save as spreadsheet</button></div></div>
      <p class="hint">This list updates by itself as you change the design. To change a price, type over it in the "Unit cost" column.</p>
      <div class="tw"><table class="t"><thead><tr><th>SKU</th><th>Description</th><th class="n">Qty</th><th>Unit</th><th class="n">Unit cost</th><th class="n">Extended</th></tr></thead><tbody>${rows}</tbody></table></div></div>
      <div class="stack" style="position:sticky;top:110px">${totalsCard(B)}
      <div class="card stack"><h3>Quantities</h3><dl class="kv"><dt>Countertop</dt><dd>${B.sq.toFixed(1)} sq ft</dd><dt>Edge profile</dt><dd>${B.edge.toFixed(1)} lin ft</dd><dt>Doors / drawers</dt><dd>${B.fc.doors} / ${B.fc.drawers}</dd><dt>Toe kick</dt><dd>${(B.toe/12).toFixed(1)} lin ft</dd><dt>Crown</dt><dd>${(B.crown/12).toFixed(1)} lin ft</dd></dl></div></div></div>`;
  } else if(UI.est==='order'){
    body=orderHTML();
  } else if(UI.est==='compare'){
    const cur={id:'_cur',name:'Current design',items:S.items,sel:currentSel()};const opts=[cur,...S.options];const sums=opts.map(o=>({o,r:optionSummary(o)}));const base=sums[0].r.total;
    const row=(l,f)=>`<tr><th style="text-transform:none;letter-spacing:0;font-size:13px;color:var(--muted)">${l}</th>${sums.map(x=>`<td class="n">${f(x)}</td>`).join('')}</tr>`;
    body=`<div class="card stack"><div class="row" style="justify-content:space-between"><div><h3>Compare design options</h3><p class="hint">Each option is a saved snapshot of the layout and selections. Save new options from the Design tab, then compare price, scope and issues side by side.</p></div><button class="btn sm" data-act="optsave">Save current as option</button></div>
      <div class="tw"><table class="t"><thead><tr><th></th>${sums.map(x=>`<th class="n" style="white-space:normal;min-width:130px">${esc(x.o.name)}</th>`).join('')}</tr></thead><tbody>
      ${row('Total',x=>`<b>${money(x.r.total)}</b>`)}${row('vs. current',x=>x.o.id==='_cur'?'—':`<span style="color:${x.r.total>base?'var(--err)':'var(--ok)'}">${x.r.total>base?'+':''}${money(x.r.total-base)}</span>`)}
      ${S.settings.budget?row('vs. budget',x=>`<span style="color:${x.r.total>S.settings.budget?'var(--err)':'var(--ok)'}">${x.r.total>S.settings.budget?'over ':'under '}${money(Math.abs(x.r.total-S.settings.budget))}</span>`):''}
      ${row('Cabinets',x=>x.r.cabs)}${row('Countertop',x=>x.r.sq.toFixed(1)+' sq ft')}
      ${row('Cabinet finish',x=>esc(FINISH[x.r.sel.finish]?.[0]||''))}${row('Island finish',x=>esc(FINISH[x.r.sel.islandFinish||x.r.sel.finish]?.[0]||''))}
      ${row('Countertop',x=>esc(COUNTER[x.r.sel.counter]?.[0]||''))}${row('Edge',x=>esc(EDGE[x.r.sel.edge]?.[0]||'')+(x.r.sel.waterfall?' + waterfall':''))}${row('Door style',x=>esc(DOOR[x.r.sel.doorStyle]||''))}${row('Hardware',x=>esc(HW[x.r.sel.hw]?.[0]||''))}
      ${row('Must-fix / warnings',x=>`${x.r.err} / ${x.r.warn}`)}
      <tr><th></th>${sums.map(x=>`<td class="n">${x.o.id==='_cur'?'':`<button class="btn sm" data-act="optload" data-id="${x.o.id}">Load</button> <button class="btn sm ghost danger" data-act="optdel" data-id="${x.o.id}" aria-label="Delete option">✕</button>`}</td>`).join('')}</tr>
      </tbody></table></div></div>`;
  } else if(UI.est==='quote'){
    body=`<div class="egrid"><div class="stack" style="min-width:0"><div class="row"><button class="btn pri" data-act="dlquote">Save quote to send</button>${IN_FRAME?'':'<button class="btn" data-act="print">Print / PDF</button>'}<button class="btn" data-act="copyquote">Copy for an email</button></div>
      <div class="doc" id="quoteDoc">${proposalHTML(B)}</div></div>
      <div class="stack">${totalsCard(B)}<div class="card stack"><h3>Terms</h3>
      <label class="f">Terms &amp; exclusions<textarea class="in" id="q-terms" rows="6" data-bind="settings.terms" data-kind="text">${esc(st.terms)}</textarea></label><p class="hint">Company name, logo and brand color are set on the Docs tab.</p></div></div></div>`;
  } else {
    const CL=cutlist();
    const pr=CL.parts.map(p=>`<tr><td class="mono">${esc(p.cab)}</td><td>${esc(p.part)}</td><td class="n">${p.qty}</td><td class="n">${fmt(p.w)}</td><td class="n">${fmt(p.l)}</td><td>${esc(p.mat)}</td><td>${p.eb?'Front':''}</td></tr>`).join('');
    const fr=CL.fronts.map(f=>`<tr><td class="mono">${esc(f.cab)}</td><td>${f.k==='false'?'False front':f.k==='drawer'?'Drawer front':'Door'+(f.hinge?' ('+(f.hinge==='L'?'hinge left':'hinge right')+')':'')}</td><td class="n">${fmt(f.w)}</td><td class="n">${fmt(f.h)}</td></tr>`).join('');
    body=`<div class="stack"><div class="row"><button class="btn pri" data-act="csvcut">Export cut list (CSV)</button><button class="btn" data-act="csvfronts">Export door &amp; drawer order (CSV)</button><button class="btn" data-act="copyorder">Copy order summary</button></div>
      <div class="row"><span class="pill p-info">${CL.sheets34} sheets ¾" ply</span><span class="pill p-info">${CL.sheets14} sheets ¼" ply</span><span class="pill p-info">${CL.eb} lin ft edge banding</span><span class="pill p-info">${CL.fronts.length} fronts</span></div>
      <p class="hint">${esc(CONSTR[st.construction][0])} construction${st.construction!=='frameless'?' with ¾" × 1½" hardwood face frames;':', '} boxes in ¾" ply, ¼" backs captured in dadoes, full-overlay fronts with ⅛" reveals. Box depth is cabinet depth minus the ¾" front. Sizes are finished dimensions, width × length (grain runs with length).</p>
      <div class="grid2"><div class="card stack"><h3>Carcass parts</h3><div class="tw"><table class="t"><thead><tr><th>Cabinet</th><th>Part</th><th class="n">Qty</th><th class="n">W</th><th class="n">L</th><th>Material</th><th>Edge</th></tr></thead><tbody>${pr}</tbody></table></div></div>
      <div class="card stack"><h3>Door &amp; drawer fronts</h3><div class="tw"><table class="t"><thead><tr><th>Cabinet</th><th>Front</th><th class="n">W</th><th class="n">H</th></tr></thead><tbody>${fr}</tbody></table></div></div></div></div>`;
  }
  $('#v-estimate').innerHTML=`<div class="stack"><div class="row" style="justify-content:space-between"><div><div class="eyebrow">Step 5 · Price &amp; quote</div><h2>${esc(S.name)}</h2></div>${tabs}</div>${body}</div>`;
}
function totalsCard(B){const st=S.settings;return `<div class="card tot"><h3 style="margin-bottom:6px">Totals</h3>
  <div class="l"><span>Materials</span><span>${money(B.mat)}</span></div><div class="l"><span>Labor</span><span>${money(B.lab)}</span></div>
  <div class="l"><span>Markup <input class="in mono" id="t-mk" style="width:64px;display:inline-block;min-height:28px;padding:2px 6px" data-bind="settings.markup" data-kind="num" value="${st.markup}" aria-label="Markup percent">%</span><span>${money(B.mk)}</span></div>
  <div class="l"><span>Tax on materials <input class="in mono" id="t-tx" style="width:64px;display:inline-block;min-height:28px;padding:2px 6px" data-bind="settings.tax" data-kind="num" value="${st.tax}" aria-label="Tax percent">%</span><span>${money(B.tax)}</span></div>
  <div class="l g"><span>Total</span><span>${money(B.total)}</span></div>
  ${st.budget?`<div class="l"><span>Budget</span><span>${money(st.budget)}</span></div><div class="meter" role="img" aria-label="Budget used"><i style="width:${Math.min(100,B.total/st.budget*100)}%;background:${B.total>st.budget?'var(--err)':'var(--ok)'}"></i></div><div class="l" style="font-size:12.5px;color:${B.total>st.budget?'var(--err)':'var(--ok)'}"><span>${B.total>st.budget?'Over budget':'Under budget'}</span><span>${money(Math.abs(st.budget-B.total))}</span></div>`:''}
  <div class="l"><span>Deposit <input class="in mono" id="t-dp" style="width:64px;display:inline-block;min-height:28px;padding:2px 6px" data-bind="settings.deposit" data-kind="num" value="${st.deposit}" aria-label="Deposit percent">%</span><span>${money(B.total*st.deposit/100)}</span></div></div>`}
function layoutName(){const ws=new Set(S.items.filter(i=>i.wall!=null&&isBaseLevel(i)).map(i=>i.wall));const isl=S.items.some(i=>i.wall==null);const n=ws.size;
  const nm=n<=1?'Single-wall':n===2?([...ws].some(a=>[...ws].some(b=>a!==b&&(Math.abs(a-b)===1||Math.abs(a-b)===S.walls.length-1)))?'L-shaped':'Galley'):'U-shaped';return nm+(isl?' with island':'')}
function proposalHTML(B){
  const st=S.settings,mk=1+st.markup/100,today=new Date();const no='P-'+today.toISOString().slice(2,10).replace(/-/g,'')+'-'+S.id.slice(0,3).toUpperCase();
  const groups={};B.lines.forEach(l=>{(groups[l.grp]=groups[l.grp]||[]).push(l)});
  const rows=Object.entries(groups).map(([g,ls])=>{const tot=ls.reduce((s,l)=>s+l.qty*l.price*mk,0);if(tot<=0&&g!=='Appliances')return '';
    return `<tr><td colspan="3" style="padding-top:14px"><b>${esc(g)}</b></td></tr>`+ls.filter(l=>l.qty>0).map(l=>`<tr><td>${esc(l.desc)}</td><td class="n">${l.qty} ${esc(l.unit)}</td><td class="n">${l.price>0?money(l.qty*l.price*mk):'By owner'}</td></tr>`).join('')+`<tr><td></td><td class="n" style="font-weight:600">${esc(g)} subtotal</td><td class="n" style="font-weight:600">${money(tot)}</td></tr>`}).join('');
  const sum=B.sub+B.mk;
  const brand=st.brand||'#1d2930';
  return `<div style="display:flex;justify-content:space-between;gap:20px;flex-wrap:wrap;border-bottom:3px solid ${esc(brand)};padding-bottom:12px;margin-bottom:16px">
    <div>${st.logo?`<img src="${st.logo}" alt="" style="max-height:54px;max-width:200px;display:block;margin-bottom:8px">`:''}<div style="font:600 11px var(--f-ui);letter-spacing:.14em;text-transform:uppercase;color:${esc(brand)}">${S.items.some(i=>['WC','VAN','SHW','TUB','TP'].includes(i.t))&&!S.items.some(i=>i.t==='RG')?'Bath':'Kitchen'} proposal</div><h1>${esc(S.name)}</h1><div>${esc(S.client||'Client')}${S.address?' · '+esc(S.address):''}</div></div>
    <div style="text-align:right"><b>${esc(st.company||'Your company')}</b><br>${esc(st.phone||'')}${st.email?'<br>'+esc(st.email):''}${st.license?'<br>Lic. '+esc(st.license):''}<br><span class="mono">${no}</span><br>${today.toLocaleDateString('en-US',{year:'numeric',month:'long',day:'numeric'})}</div></div>
    <p><b>Scope.</b> ${layoutName()} kitchen, ${S.items.filter(i=>!CAT[i.t].app&&CAT[i.t].cls!=='filler').length} cabinets (${esc(LINE[st.line][0].toLowerCase())}, ${esc(CONSTR[st.construction][0].toLowerCase())}, ${esc(DOOR[st.doorStyle].toLowerCase())} doors) in ${esc(FINISH[st.finish][0].toLowerCase())}${st.islandFinish&&st.islandFinish!==st.finish?' with a '+esc(FINISH[st.islandFinish][0].toLowerCase())+' island':''}; ${esc(COUNTER[st.counter][0].toLowerCase())} countertops (${B.sq.toFixed(1)} sq ft, ${esc(EDGE[st.edge][0].toLowerCase())} edge${st.waterfall?', waterfall island':''}); ${esc(HW[st.hw][0].toLowerCase())} hardware; ${esc(SPLASH[st.splash].toLowerCase())} tile backsplash. Room ${fmt(geom().bbox.w,'ft')} × ${fmt(geom().bbox.h,'ft')}, ${S.ceiling}" ceiling.</p>
    <table><thead><tr><th>Item</th><th class="n">Qty</th><th class="n">Amount</th></tr></thead><tbody>${rows}</tbody></table>
    <table style="margin-top:16px;max-width:360px;margin-left:auto"><tr><td>Subtotal</td><td class="n">${money(sum)}</td></tr><tr><td>Sales tax</td><td class="n">${money(B.tax)}</td></tr><tr><td><b>Total</b></td><td class="n"><b>${money(B.total)}</b></td></tr><tr><td>Deposit due at signing (${st.deposit}%)</td><td class="n">${money(B.total*st.deposit/100)}</td></tr></table>
    <p style="font-size:12px;color:#55626a;margin-top:20px">${esc(st.terms)}</p>
    <div class="sig"><div>Client signature &amp; date</div><div>${esc(st.company||'Contractor')} &amp; date</div></div>`;
}
function proposalText(B){const st=S.settings,mk=1+st.markup/100;let t=`KITCHEN PROPOSAL — ${S.name}\n${S.client||''}${S.address?' · '+S.address:''}\n${st.company||''} ${st.phone||''}\n\n`;
  let g='';for(const l of B.lines){if(l.qty<=0)continue;if(l.grp!==g){g=l.grp;t+=`\n${g.toUpperCase()}\n`}t+=`  ${l.desc} — ${l.qty} ${l.unit}${l.price>0?' — '+money(l.qty*l.price*mk):' — by owner'}\n`}
  t+=`\nSubtotal ${money(B.sub+B.mk)}\nTax ${money(B.tax)}\nTOTAL ${money(B.total)}\nDeposit (${st.deposit}%) ${money(B.total*st.deposit/100)}\n\n${st.terms}\n`;return t}

/* ---------- CHECKS ---------- */
function renderChecks(){
  const C=checks();const by=s=>C.list.filter(x=>x.sev===s);
  const sec=(s,title)=>{const l=by(s);if(!l.length)return '';return `<div class="stack"><h3>${title} · ${l.length}</h3>${l.map(x=>`<button class="issue" data-act="goissue" data-ids="${x.ids.join(',')}"><span class="sev sev-${x.sev}"></span><span><b>${esc(x.title)}</b><span>${esc(x.detail)}</span><br><span class="eyebrow" style="font-size:10px">${esc(x.rule)}</span></span></button>`).join('')}</div>`};
  const tri=C.tri?`<dl class="kv"><dt>Sink → range</dt><dd>${fmt(C.tri.legs[0],'ft')}</dd><dt>Range → fridge</dt><dd>${fmt(C.tri.legs[1],'ft')}</dd><dt>Fridge → sink</dt><dd>${fmt(C.tri.legs[2],'ft')}</dd><dt>Total</dt><dd>${fmt(C.tri.sum,'ft')}</dd></dl>`:'<p class="hint">Add a sink base, range and refrigerator to measure the work triangle.</p>';
  $('#v-checks').innerHTML=`<div class="egrid"><div class="stack">
    <div><div class="eyebrow">Step 4 · Problems</div><h2>${!C.counts.err&&!C.counts.warn?'No problems found':(C.counts.err?C.counts.err+' must fix'+(C.counts.warn?', ':''):'')+(C.counts.warn?C.counts.warn+' worth a look':'')}</h2><p class="hint">Tap a problem to jump to that cabinet on the plan.</p></div>
    ${sec('err','Must fix')}${sec('warn','Warnings')}<div class="pro">${sec('info','Notes for the installer')}</div>
    ${!C.list.length?'<div class="card"><span class="pill p-ok">Everything passes</span></div>':''}
  </div><div class="stack" style="position:sticky;top:110px">
    <div class="card stack"><h3>Passing</h3>${C.passed.map(p=>`<div class="row"><span class="pill p-ok">✓</span><span>${esc(p)}</span></div>`).join('')||'<p class="hint">Nothing fully passing yet.</p>'}</div>
    <div class="card stack"><h3>Work triangle</h3>${tri}</div>
    <div class="card stack"><h3>What is checked</h3><p class="hint">NKBA kitchen planning guidelines (aisles, landing areas, work triangle, dishwasher placement, upper cabinet height), NEC 210.52 countertop receptacle spacing and GFCI, hood clearances, physical collisions in 3D, out-of-square scribe, window and door casing, soffits and ceiling height, and utilities that a fixture needs or a cabinet would bury. Guidelines vary by jurisdiction and manufacturer; confirm with local code and install manuals.</p></div>
  </div></div>`;
}

/* ---------- actions ---------- */
async function openMenu(){
  const m=$('#menu');if(!m.hidden){m.hidden=true;return}
  m.innerHTML='<div class="hint" style="padding:8px">Loading…</div>';m.hidden=false;
  const list=await listProjects();
  m.innerHTML=`<button data-act="new">＋ Start a new job</button><button data-act="sample">Open the example kitchen</button><button data-act="dupproj">Make a copy of this job</button><hr>
    <div class="eyebrow" style="padding:6px 10px">Your saved jobs</div><div class="pl">${list.map(p=>`<button data-act="open" data-id="${p.id}">${esc(p.name)} <span class="muted" style="font-size:11px">· ${new Date(p.updatedAt).toLocaleDateString()}</span></button>`).join('')||'<div class="hint" style="padding:6px 10px">None saved yet.</div>'}</div><hr>
    <button data-act="export">Save a backup file of this job</button><button data-act="import">Open a backup file…</button><hr><button data-act="delproj" style="color:var(--err)">Delete this job…</button>`;
}
let confirmDel=0;
const ACT={
  tab:(el)=>{UI.tab=el.dataset.v;location.hash=UI.tab;render()},
  undo,redo,
  units:(el)=>{S.units=el.dataset.v;commit()},
  menu:()=>openMenu(),
  new:()=>{$('#menu').hidden=true;setProject(blankProject());UI.tab='measure';commit();toast('New job started. Type in the wall lengths first.')},
  sample:()=>{$('#menu').hidden=true;setProject(sampleProject());render();toast('Sample kitchen loaded')},
  dupproj:()=>{$('#menu').hidden=true;const p=clone(S);p.id=uid();p.name=S.name+' (copy)';setProject(p);commit();toast('Duplicated')},
  open:async(el)=>{$('#menu').hidden=true;const p=await loadProject(el.dataset.id);if(p){setProject(p);toast('Opened '+p.name)}else toast('Could not open that project','err')},
  export:()=>{$('#menu').hidden=true;saveFile((S.name||'kitchen').replace(/[^\w\- ]+/g,'').trim().replace(/\s+/g,'-')+'.json',JSON.stringify(S,null,1),'application/json')},
  import:()=>{$('#menu').hidden=true;$('#fileOpen').click()},
  delproj:async(el)=>{if(Date.now()-confirmDel>4000){confirmDel=Date.now();el.textContent='Tap again to delete “'+S.name+'”';return}
    $('#menu').hidden=true;const id=S.id;try{localStorage.removeItem('ps.p.'+id);const idx=localIndex();delete idx[id];LS.set('ps.index',idx)}catch(e){}
    if(CLOUD.ready){try{await CLOUD.db.collection('data/users/'+CLOUD.uid).doc(id).delete()}catch(e){}}
    const list=await listProjects();const nx=list[0]?await loadProject(list[0].id):null;setProject(nx||sampleProject());toast('Project deleted')},
  tmpl:(el)=>{if(el.dataset.v==='rect'){S.walls=[{len:144,note:''},{len:120,note:''},{len:144,note:''},{len:120,note:''}];S.corners=S.walls.map(()=>({ang:90}))}
    else{S.walls=[{len:168,note:''},{len:84,note:''},{len:60,note:''},{len:60,note:''},{len:108,note:''},{len:144,note:''}];S.corners=[{ang:90},{ang:90},{ang:270},{ang:90},{ang:90},{ang:90}]}
    S.items=S.items.filter(i=>i.wall==null||i.wall<S.walls.length);S.utils=S.utils.filter(u=>u.wall<S.walls.length);commit()},
  addwall:()=>{S.walls.push({len:96,note:''});S.corners.push({ang:90});commit()},
  delwall:(el)=>{const i=+el.dataset.i;if(S.walls.length<=3)return;S.walls.splice(i,1);S.corners.splice(i,1);S.items=S.items.filter(it=>it.wall!==i).map(it=>{if(it.wall!=null&&it.wall>i)it.wall--;return it});S.utils=S.utils.filter(u=>u.wall!==i).map(u=>{if(u.wall>i)u.wall--;return u});commit()},
  addutil:()=>{const t=$('#addUtilType').value;const T=UTIL[t];const u={id:uid(),type:t,wall:0,x:24,z:T.box?(t==='window'?42:t==='soffit'?0:0):(t==='gfci'||t==='outlet'?44:t==='sw'?48:t==='vent'?4:18),w:T.box?(t==='soffit'?48:36):0,h:T.box?(t==='window'?40:t==='door'?80:12):0,trim:t==='window'||t==='door'?3.5:0,note:''};S.utils.push(u);commit();setTimeout(()=>{const el=$('#m-ut_'+u.id+'_x');el&&el.focus()},30)},
  delutil:(el)=>{S.utils=S.utils.filter(u=>u.id!==el.dataset.id);commit()},
  laser:()=>laserConnect(),
  add:(el)=>{UI.wall=+$('#tgtWall').value||0;addItem(el.dataset.t)},
  fit:()=>{UI.view=null;drawPlan()},
  zoom:(el)=>{const svg=$('#plan');const v=svg.viewBox.baseVal;const k=+el.dataset.v;const cx=v.x+v.width/2,cy=v.y+v.height/2;UI.view={x:cx-v.width*k/2,y:cy-v.height*k/2,w:v.width*k,h:v.height*k};drawPlan()},
  tog:(el)=>{UI[el.dataset.v]=!UI[el.dataset.v];renderDesign()},
  desel:()=>{SEL=null;renderDesign()},
  nudge:(el)=>{const it=selItem();if(!it)return;it.x=r2(it.x+ +el.dataset.v);commit()},
  rot:(el)=>{const it=selItem();if(!it)return;it.rot=((it.rot||0)+ +el.dataset.v+360)%360;commit()},
  dup:()=>{const it=selItem();if(!it)return;const n=clone(it);n.id=uid();if(n.wall!=null){const gs=gaps(n.wall,n.z,n.z+n.h);const g=gs.find(g=>g.b-g.a>=n.w-0.01&&g.a>=it.x)||gs.find(g=>g.b-g.a>=n.w-0.01);n.x=g?g.a:it.x+it.w}else{n.cx+=12;n.cy-=12}S.items.push(n);SEL=n.id;commit()},
  del:()=>{S.items=S.items.filter(i=>i.id!==SEL);SEL=null;commit()},
  fillgap:(el)=>{fillGap(UI.wall,+el.dataset.a,+el.dataset.b,+el.dataset.z);commit()},
  autofill:()=>{const k=autofill(UI.wall);commit();toast(k?'Added '+k+' piece'+(k>1?'s':'')+' on W'+(UI.wall+1):'Nothing to fill on W'+(UI.wall+1))},
  goissue:(el)=>{const ids=el.dataset.ids.split(',').filter(Boolean);if(ids.length){SEL=ids[0];const it=selItem();if(it&&it.wall!=null)UI.wall=it.wall}UI.tab='design';location.hash='design';render()},
  elev:(el)=>{const v=el.dataset.v;UI.elevWall=/^\d+$/.test(v)?+v:v;renderElev()},
  dlsvg:()=>{const svg=$('#elsvg');const css=[...document.styleSheets].flatMap(s=>{try{return [...s.cssRules].map(r=>r.cssText).filter(t=>t.startsWith('.el'))}catch(e){return []}}).join('\n');
    const out=svg.outerHTML.replace('>',`><style>${css} text{font-family:monospace}</style><rect x="-30" y="-32" width="100%" height="100%" fill="#fff"/>`);saveFile('elevation-'+(typeof UI.elevWall==='number'?'W'+(UI.elevWall+1):'island-'+UI.elevWall.split(':')[1])+'.svg',out,'image/svg+xml')},
  cam:(el)=>{setCam(el.dataset.v);render3DView()},
  light:(el)=>{S.settings.light=el.dataset.v;commit()},
  finish:(el)=>{S.settings[el.dataset.k]=el.dataset.v;commit()},
  est:(el)=>{UI.est=el.dataset.v;renderEstimate()},
  csvbom:()=>{const B=bom();saveFile('bom-'+slug()+'.csv',csv([['Group','SKU','Description','Qty','Unit','Unit cost','Extended'],...B.lines.map(l=>[l.grp,l.sku,l.desc,l.qty,l.unit,l.price,r2(l.qty*l.price)]),[],['','','Materials','','','',r2(B.mat)],['','','Labor','','','',r2(B.lab)],['','','Markup','','','',r2(B.mk)],['','','Tax','','','',r2(B.tax)],['','','Total','','','',r2(B.total)]]),'text/csv')},
  csvcut:()=>{const CL=cutlist();saveFile('cutlist-'+slug()+'.csv',csv([['Cabinet','Part','Qty','Width (in)','Length (in)','Material','Edge band'],...CL.parts.map(p=>[p.cab,p.part,p.qty,p.w,p.l,p.mat,p.eb?'Front':''])]),'text/csv')},
  csvfronts:()=>{const CL=cutlist();saveFile('fronts-'+slug()+'.csv',csv([['Cabinet','Front','Width (in)','Height (in)','Hinge','Finish'],...CL.fronts.map(f=>[f.cab,f.k,r2(f.w),r2(f.h),f.hinge||'',FINISH[S.settings.finish][0]])]),'text/csv')},
  copyorder:async()=>{const B=bom();const cab=B.lines.filter(l=>l.grp==='Cabinets'||l.grp==='Fillers & panels');const t=`Cabinet order — ${S.name}\nFinish: ${FINISH[S.settings.finish][0]} · Hardware: ${HW[S.settings.hw][0]}\n\n`+cab.map(l=>`${l.qty} × ${l.sku}  ${l.desc}`).join('\n')+`\n\nCountertop: ${COUNTER[S.settings.counter][0]}, ${B.sq.toFixed(1)} sq ft, ${B.lines.find(l=>l.sku==='CUT-SINK')?.qty||0} sink cutout(s)\n`;copyText(t,'Order summary copied — paste it into an email to your supplier')},
  dlquote:()=>{const B=bom();const html=`<!doctype html><html><head><meta charset="utf-8"><title>Proposal — ${esc(S.name)}</title><style>body{font-family:Helvetica,Arial,sans-serif;color:#1d2930;max-width:820px;margin:40px auto;padding:0 20px}h1{font-size:30px;margin:0 0 4px}table{border-collapse:collapse;width:100%;font-size:13px}td,th{padding:6px;border-bottom:1px solid #d8dcd8;text-align:left}.n{text-align:right;font-family:Menlo,monospace}.mono{font-family:Menlo,monospace}.sig{display:grid;grid-template-columns:1fr 1fr;gap:30px;margin-top:40px}.sig div{border-top:1px solid #1d2930;padding-top:6px;font-size:12px}</style></head><body>${proposalHTML(B)}</body></html>`;saveFile('proposal-'+slug()+'.html',html,'text/html')},
  copyquote:()=>copyText(proposalText(bom()),'Proposal copied as text'),
  print:()=>{$('#printArea').innerHTML='<div class="doc" style="border:0;max-width:none">'+proposalHTML(bom())+'</div>';window.print()}
};
const slug=()=>(S.name||'kitchen').toLowerCase().replace(/[^\w]+/g,'-').replace(/^-|-$/g,'');
async function copyText(t,msg){try{await navigator.clipboard.writeText(t);toast(msg)}catch(e){const ta=document.createElement('textarea');ta.value=t;ta.style.position='fixed';ta.style.opacity='0';document.body.appendChild(ta);ta.select();try{document.execCommand('copy');toast(msg)}catch(_){toast('Copy is blocked here — select the text manually','err')}ta.remove()}}

/* ---------- wiring ---------- */
document.addEventListener('click',e=>{
  const t=e.target.closest('[data-tab]');if(t){UI.tab=t.dataset.tab;location.hash=UI.tab;render();return}
  const a=e.target.closest('[data-act]');if(a&&!a.disabled){const f=ACT[a.dataset.act];if(f){e.preventDefault();f(a)}return}
  if(!e.target.closest('#menu')&&!e.target.closest('#bMenu'))$('#menu').hidden=true;
});
document.addEventListener('change',e=>{if(e.target.id==='tgtWall'){UI.wall=+e.target.value;SEL=null;renderDesign();return}if(e.target.id==='fileOpen')return;onChange(e)});
document.addEventListener('keydown',e=>{
  if(e.key==='Enter'&&e.target.matches('input.in')){NEXT_FOCUS=true;e.target.blur();if(document.activeElement===document.body)NEXT_FOCUS=NEXT_FOCUS;return}
  if(e.target.matches('input,select,textarea'))return;
  if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='z'){e.preventDefault();e.shiftKey?redo():undo();return}
  if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='y'){e.preventDefault();redo();return}
  if(UI.tab!=='design'||!SEL)return;const it=selItem();if(!it)return;
  if(e.key==='Delete'||e.key==='Backspace'){e.preventDefault();ACT.del();return}
  if(e.key==='Escape'){SEL=null;renderDesign();return}
  const step=e.shiftKey?1:0.125;
  if(e.key==='ArrowLeft'||e.key==='ArrowRight'){e.preventDefault();if(it.wall!=null)it.x=r2(it.x+(e.key==='ArrowLeft'?-step:step));else it.cx=r2(it.cx+(e.key==='ArrowLeft'?-step:step));commit()}
  if((e.key==='ArrowUp'||e.key==='ArrowDown')&&it.wall==null){e.preventDefault();it.cy=r2(it.cy+(e.key==='ArrowUp'?step:-step));commit()}
});
$('#fileOpen').addEventListener('change',async e=>{const f=e.target.files[0];if(!f)return;try{const p=JSON.parse(await f.text());if(!p.walls||!p.items)throw 0;p.id=p.id||uid();setProject(p);commit();toast('Opened '+p.name)}catch(_){toast('That file is not a Plumb & Square project','err')}e.target.value=''});
window.addEventListener('hashchange',()=>{const h=location.hash.slice(1);if(h&&h!==UI.tab&&$('#v-'+h)){UI.tab=h;render()}});
document.addEventListener('visibilitychange',()=>{if(!document.hidden&&UI.tab==='view3d'&&!V3.raf&&V3.ready)loop3D()});

