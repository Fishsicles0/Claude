/* ---------- Easy mode: plain names, pictures, page help, next steps, welcome, text size ---------- */
const PLAIN={B:'Base cabinet',DB:'Drawer cabinet',SB:'Sink cabinet',BBC:'Corner cabinet',WP:'Trash pull-out',W:'Upper (wall) cabinet',T:'Pantry (tall)',OV:'Oven cabinet (tall)',F:'Filler strip',PNL:'End panel',
  RG:'Stove / range',DW:'Dishwasher',REF:'Refrigerator',HOOD:'Range hood',MWD:'Microwave drawer',ISL:'Island',VAN:'Vanity with sink',VDB:'Vanity drawers',LT:'Linen cabinet (tall)',MIR:'Mirror',WC:'Toilet',TUB:'Bathtub',TP:'Tub with deck',SHW:'Shower'};
const GRP_PLAIN={'Base cabinets':'Lower cabinets','Wall cabinets':'Upper cabinets','Tall cabinets':'Tall cabinets','Trim':'Fillers & panels','Appliances':'Appliances','Islands':'Island','Bath':'Bathroom'};

/* small line pictures so people can recognize items without reading codes */
function catIcon(t){
  const S_='fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round" stroke-linecap="round"';
  const box=(x,y,w,h)=>`<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="1"/>`;
  const L=(x1,y1,x2,y2)=>`<path d="M${x1} ${y1}L${x2} ${y2}"/>`;
  const doors2=(x,y,w,h)=>box(x,y,w,h)+L(x+w/2,y,x+w/2,y+h)+L(x+w/2-2.5,y+3,x+w/2-2.5,y+7)+L(x+w/2+2.5,y+3,x+w/2+2.5,y+7);
  const g={
    B:box(5,4,24,20)+L(5,9,29,9)+L(17,9,17,24)+L(14,6.5,20,6.5)+L(14.5,12,14.5,16)+L(19.5,12,19.5,16),
    DB:box(5,4,24,20)+L(5,9,29,9)+L(5,15.5,29,15.5)+L(14,6.5,20,6.5)+L(14,12,20,12)+L(14,19.5,20,19.5),
    SB:box(5,9,24,15)+L(17,9,17,24)+`<path d="M9 9 Q17 4 25 9"/>`+L(17,3,17,6),
    BBC:box(3,4,28,20)+L(14,4,14,24)+`<path d="M4 6L13 15M4 12L11 19M4 18L9 23" stroke-width=".9"/>`+L(20,12,20,16),
    WP:box(8,4,18,20)+L(13,8,21,8)+`<path d="M12 12h10l-1 9h-8z"/>`,
    W:doors2(5,3,24,19)+L(3,3,31,3),
    T:box(10,1,14,26)+L(10,14,24,14)+L(21,7,21,10)+L(21,17,21,21),
    OV:box(10,1,14,26)+box(12,9,10,8)+L(10,20,24,20),
    F:box(15,3,4,22),
    PNL:box(14,3,6,22)+L(14,8,20,3)+L(14,14,20,8)+L(14,20,20,14),
    RG:box(5,8,24,16)+box(8,14,18,7)+`<circle cx="10" cy="5.5" r="1.8"/><circle cx="17" cy="5.5" r="1.8"/><circle cx="24" cy="5.5" r="1.8"/>`+L(5,8,29,8),
    DW:box(6,3,22,21)+L(6,7,28,7)+L(11,10,23,10),
    REF:box(8,1,18,26)+L(17,1,17,18)+L(8,18,26,18)+L(15,6,15,13)+L(19,6,19,13)+L(13,21,21,21),
    HOOD:`<path d="M6 22h22l-6-9h-10z"/>`+box(13,3,8,10),
    MWD:box(6,4,22,20)+box(9,8,16,8)+L(13,19,21,19),
    ISL:box(3,6,28,10)+`<circle cx="10" cy="22" r="3"/><circle cx="24" cy="22" r="3"/>`,
    VAN:box(5,10,24,14)+`<ellipse cx="17" cy="9" rx="6" ry="2"/>`+L(17,3,17,7)+L(17,10,17,24),
    VDB:box(9,4,16,20)+L(9,10,25,10)+L(9,16,25,16),
    LT:box(11,1,12,26)+L(11,12,23,12)+L(20,5,20,9),
    MIR:box(8,3,18,22)+L(12,10,17,5)+L(12,16,22,6),
    WC:`<path d="M8 4h9v9H8z"/><path d="M6 13h18q0 6-6 7l1 5h-8l1-5q-6-1-6-7z"/>`,
    TUB:`<path d="M3 11h28v4q0 7-8 7h-12q-8 0-8-7z"/><path d="M8 22l-1 3M26 22l1 3M6 11V5q0-2 3-2"/>`,
    TP:box(3,10,28,14)+`<path d="M7 10q0 7 10 7t10-7"/>`,
    SHW:box(7,3,20,22)+`<path d="M11 3v4h5"/><circle cx="17" cy="8" r="1.6"/>`+`<path d="M15 12v2M18 13v2M21 12v2M16 17v2M20 17v2" stroke-width="1"/>`
  };
  return `<svg viewBox="0 0 34 28" aria-hidden="true" ${S_}>${g[t]||box(5,4,24,20)}</svg>`;
}

const GUIDE={
  measure:['Step 1: Measure the room',['Measure each wall with a tape or laser, then type the length in the boxes. 12\' 6" or 150 both work.','Go around the room in order, clockwise. Start at the left end of the main cabinet wall.','In the second box, add windows, doors, outlets and water or gas pipes so no cabinet covers them.','Not sure where to start? Tap “Start as a rectangle” and change the numbers.']],
  design:['Step 2: Place the cabinets',['At the top of the plan, pick the wall you are working on.','Tap a cabinet or appliance in the list to add it to that wall.','Drag it on the plan to move it. Tap it to change its size.','Tap “Fill the empty spaces” to finish a wall with standard sizes.']],
  view3d:['Step 3: See it in 3D',['Drag with your finger or the mouse to turn the room.','Pinch, or scroll the mouse wheel, to zoom in and out.','Tap “Walk inside” to look around from where you would stand.','Pick colors below. Tap “Add to client show” to save a picture for the homeowner.']],
  checks:['Step 4: Fix any problems',['Red means it must be fixed before ordering. Yellow is worth a second look.','Tap a problem to jump to that cabinet on the plan.','Fix it on the plan. This list updates by itself.']],
  estimate:['Step 5: Price it and make the quote',['The list adds everything up while you design.','To use your own prices, type over the numbers in the “Unit cost” column.','Set your markup and tax in the Totals box.','Tap “Quote for the customer” to see the quote, then save it or copy it into an email.','Once the job is approved, open “Order from Cabinotch” for the exact list of boxes to order.']],
  elevations:['Wall drawings',['Pick a wall at the top.','Each drawing shows cabinet sizes and where the outlets and pipes go.','Tap “Save this drawing” to send it to the installer.']],
  present:['Show the client',['In 3D, tap “Add to client show” to add pictures.','This page puts the pictures, plan, drawings and price together.','Tap “Save presentation to send” and email the file to the homeowner.']],
  docs:['Files & specs',['Pick the cabinet color, countertop and hardware.','Add photos of the room and appliance spec sheets.','Put your company name and logo here. They show on quotes.']]
};
const STEPS=['measure','design','view3d','checks','estimate'];
const NEXT={measure:'design',design:'view3d',view3d:'checks',checks:'estimate',estimate:'present',elevations:'estimate',present:'estimate',docs:'estimate'};
const PREV={design:'measure',view3d:'design',checks:'view3d',estimate:'checks',present:'estimate',elevations:'design',docs:'estimate'};
const TABNAME={measure:'Measure room',design:'Place cabinets',view3d:'See it in 3D',checks:'Problems',estimate:'Price & quote',elevations:'Wall drawings',present:'Show the client',docs:'Files & specs'};

let GHIDE=LS.get('ps.guideHide')||{};
UI.mode=LS.get('ps.mode')||'simple';
UI.ts=+(LS.get('ps.ts')||1.15);
function applyEasy(){
  document.documentElement.dataset.mode=UI.mode;
  document.documentElement.style.setProperty('--ts',UI.ts);
  [['ts1',1],['ts2',1.15],['ts3',1.3]].forEach(([id,v])=>$('#'+id)?.setAttribute('aria-pressed',UI.ts===v));
  $('#mSimple')?.setAttribute('aria-pressed',UI.mode==='simple');$('#mFull')?.setAttribute('aria-pressed',UI.mode==='full');
}
function renderGuide(){
  const g=GUIDE[UI.tab],el=$('#guide');if(!el)return;
  if(!g){el.innerHTML='';return}
  if(GHIDE[UI.tab]){el.innerHTML=`<div class="guide-mini"><button class="btn ghost sm" data-act="guideshow">? Show tips for this page</button></div>`;return}
  const open=window.innerWidth>820;
  el.innerHTML=`<details class="guide" role="note" ${open?'open':''}><summary><h3>${esc(g[0])}</h3><span class="gtog">How to use this page</span></summary><div class="gbody"><ol>${g[1].map(x=>`<li>${esc(x)}</li>`).join('')}</ol><button class="btn sm x" data-act="guidehide">Hide tips</button></div></details>`;
}
function renderNext(){
  const el=$('#nextbar');if(!el)return;
  const p=PREV[UI.tab],n=NEXT[UI.tab];
  el.className='nextbar';
  el.innerHTML=(p?`<button class="btn" data-act="tab" data-v="${p}">← Back: ${esc(TABNAME[p])}</button>`:'<span></span>')+
    (n?`<button class="btn pri" data-act="tab" data-v="${n}">Next: ${esc(TABNAME[n])} →</button>`:'');
}
function showWelcome(){
  const w=$('#welcome');const hasJob=S&&!S.sample&&(S.items.length||S.walls.some(x=>x.len!==144&&x.len!==120));
  w.innerHTML=`<div class="box" role="dialog" aria-modal="true" aria-labelledby="wTitle">
    <div class="row" style="justify-content:space-between;align-items:flex-start"><div><div class="eyebrow">Welcome</div><h2 id="wTitle">Plumb &amp; Square</h2></div><button class="btn" data-act="wclose">Close</button></div>
    <p style="font-size:calc(16px * var(--ts));max-width:60ch;margin:6px 0 0">Design a kitchen or bathroom in five steps: <b>measure the room</b>, <b>place the cabinets</b>, <b>see it in 3D</b>, <b>fix any problems</b>, then <b>print the price and quote</b>. Each page tells you what to do.</p>
    <div class="wchoices">
      <button class="wchoice" data-act="wnew"><span class="big">＋</span><b>Start a new job</b><span>Begin with an empty room and type in your measurements.</span></button>
      <button class="wchoice" data-act="wsample"><span class="big">⌂</span><b>Look at the example</b><span>A finished sample kitchen to click around in. Nothing you do can break it.</span></button>
      <button class="wchoice" data-act="wopen"><span class="big">▤</span><b>Open a saved job</b><span>Pick up a job you saved before on this device.</span></button>
      ${hasJob?`<button class="wchoice" data-act="wclose"><span class="big">→</span><b>Keep working</b><span>${esc(S.name)}</span></button>`:''}
    </div>
    <div class="row" style="gap:18px">
      <div><div class="eyebrow" style="margin-bottom:6px">Text size</div><div class="seg"><button data-act="tsize" data-v="1" aria-pressed="${UI.ts===1}">Normal</button><button data-act="tsize" data-v="1.15" aria-pressed="${UI.ts===1.15}">Large</button><button data-act="tsize" data-v="1.3" aria-pressed="${UI.ts===1.3}">Extra large</button></div></div>
      <div><div class="eyebrow" style="margin-bottom:6px">Screen</div><div class="seg"><button data-act="mode" data-v="simple" aria-pressed="${UI.mode==='simple'}">Simple</button><button data-act="mode" data-v="full" aria-pressed="${UI.mode==='full'}">Full (all tools)</button></div></div>
    </div>
    <p class="hint" style="margin-top:14px">Made a mistake? Tap <b>↶ Undo</b> at the top, any time. Your work saves by itself on this device. Tap <b>? Help</b> to come back to this screen.</p>
  </div>`;
  w.hidden=false;setTimeout(()=>w.querySelector('.wchoice')?.focus(),30);
}
function closeWelcome(){$('#welcome').hidden=true;LS.set('ps.welcomed',1)}

/* hook into the main render */
{
  const base=render;
  render=function(){
    if(UI.mode==='simple'&&(UI.est==='compare'||UI.est==='cut'))UI.est='bom';
    base();applyEasy();renderGuide();renderNext();
  };
}

/* actions */
const UNDOABLE={del:'Removed.',delutil:'Removed.',delnote:'Note removed.',delwall:'Wall removed.',autofill:null,fillgap:'Empty space filled.',optload:null,tmpl:'Room reset to a new shape.',aiapply:null,paintreset:null,libinsert:null,dup:'Copied.',nudge:null};
Object.keys(UNDOABLE).forEach(k=>{const f=ACT[k];if(!f)return;ACT[k]=async el=>{const before=HIST.i,len=HIST.stack.length;const prevMsg=$('#toast').hidden?'':$('#toast').textContent;await f(el);
  if((HIST.i!==before||HIST.stack.length!==len)&&k!=='nudge'){const cur=$('#toast').hidden?'':$('#toast').textContent;toast(UNDOABLE[k]||(cur&&cur!==prevMsg?cur:'Done.'),'undo')}}});
Object.assign(ACT,{
  guidehide:()=>{GHIDE[UI.tab]=1;LS.set('ps.guideHide',GHIDE);renderGuide()},
  guideshow:()=>{delete GHIDE[UI.tab];LS.set('ps.guideHide',GHIDE);renderGuide()},
  tsize:(el)=>{UI.ts=+el.dataset.v;LS.set('ps.ts',UI.ts);applyEasy();if(!$('#welcome').hidden)showWelcome();if(UI.tab==='design'){UI.view=null;drawPlan()}},
  mode:(el)=>{UI.mode=el.dataset.v;LS.set('ps.mode',UI.mode);if(!$('#welcome').hidden)showWelcome();render();toast(UI.mode==='simple'?'Simple screen: everyday tools only.':'Full screen: every tool is showing.')},
  home:()=>showWelcome(),
  wclose:()=>closeWelcome(),
  wnew:()=>{closeWelcome();ACT.new()},
  wsample:()=>{closeWelcome();setProject(sampleProject());UI.tab='design';location.hash='design';render();toast('This is the example kitchen. Try dragging a cabinet.')},
  wopen:()=>{closeWelcome();openMenu()}
});
document.addEventListener('keydown',e=>{if(e.key==='Escape'&&!$('#welcome').hidden)closeWelcome()});
