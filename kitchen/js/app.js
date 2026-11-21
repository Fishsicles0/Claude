/* ---------- persistence ---------- */
const LS={get(k){try{return JSON.parse(localStorage.getItem(k))}catch(e){return null}},set(k,v){try{localStorage.setItem(k,JSON.stringify(v));return true}catch(e){return false}}};
const CLOUD={db:null,uid:null,ready:false,dl:null};
let saveT=null,saving=false,pendingSave=false;
function localIndex(){return LS.get('ps.index')||{}}
function scheduleSave(){
  clearTimeout(saveT);
  setSave('Saving…');
  saveT=setTimeout(doSave,900);
}
async function doSave(){
  const p=clone(S);
  const idx=localIndex();idx[p.id]={name:p.name,updatedAt:p.updatedAt};LS.set('ps.index',idx);LS.set('ps.p.'+p.id,p);LS.set('ps.current',p.id);
  if(CLOUD.ready){
    if(saving){pendingSave=true;return}
    saving=true;
    try{await CLOUD.db.collection('data/users/'+CLOUD.uid).doc(p.id).set({json:JSON.stringify(p),name:p.name,updatedAt:p.updatedAt});setSave('Saved to your account')}
    catch(e){setSave('Saved on this device');if(e&&e.code==='invalid_argument')CLOUD.ready=false}
    saving=false; if(pendingSave){pendingSave=false;doSave()}
  } else setSave('Saved on this device');
}
function setSave(t){const el=$('#saveState');if(el)el.textContent=t}
async function listProjects(){
  const idx=localIndex();const out={};
  for(const [id,v] of Object.entries(idx))out[id]={id,name:v.name,updatedAt:v.updatedAt,src:'device'};
  if(CLOUD.ready){try{const snap=await CLOUD.db.collection('data/users/'+CLOUD.uid).get();snap.docs.forEach(d=>{if(d.id.startsWith('_'))return;const v=d.data();if(!out[d.id]||out[d.id].updatedAt<=v.updatedAt)out[d.id]={id:d.id,name:v.name,updatedAt:v.updatedAt,src:'account'}})}catch(e){}}
  return Object.values(out).sort((a,b)=>b.updatedAt-a.updatedAt);
}
async function loadProject(id){
  let p=LS.get('ps.p.'+id);
  if(CLOUD.ready){try{const d=await CLOUD.db.collection('data/users/'+CLOUD.uid).doc(id).get();if(d.exists){const v=d.data();const cp=JSON.parse(v.json);if(!p||cp.updatedAt>p.updatedAt)p=cp}}catch(e){}}
  return p?normalize(p):null;
}
function setProject(p,keepHist){
  S=normalize(p);SEL=null;UI.view=null;UI.wall=0;UI.elevWall=0;invalidate();
  if(!keepHist){HIST.stack=[JSON.stringify(S)];HIST.i=0}
  render();
}
async function initCloud(){
  if(!window.claude||!window.claude.use)return;
  try{
    const [db,user,dl,sm,as]=await Promise.all([window.claude.use('db'),window.claude.use('user'),window.claude.use('downloads'),window.claude.use('sample'),window.claude.use('assets')]);
    CLOUD.dl=dl;CLOUD.sample=sm;CLOUD.assets=as;
    if(sm){try{const l=await sm.limits();CLOUD.sampleImages=!!(l&&l.images)}catch(e){}}
    if(UI.tab==='design'||UI.tab==='measure'||UI.tab==='docs')render();
    if(!db||!user)return;
    const id=await user.id(); if(!id)return;
    CLOUD.db=db;CLOUD.uid=id;CLOUD.ready=true;loadLibraryCloud();
    // pull a newer copy of the open project, if the account has one
    const d=await db.collection('data/users/'+id).doc(S.id).get().catch(()=>null);
    if(d&&d.exists){const v=d.data();if(v.updatedAt>S.updatedAt){setProject(JSON.parse(v.json));toast('Loaded the latest copy from your account')}}
    else if(!S.sample) doSave();
    setSave(S.sample&&!(d&&d.exists)?'Sample project':'Saved to your account');
  }catch(e){CLOUD.ready=false}
}

/* ---------- file output ---------- */
async function saveFile(name,data,mime){
  if(CLOUD.dl){try{await CLOUD.dl.save({filename:name,data});toast('Saved '+name);return}catch(e){if(e&&e.code==='declined')return;
    if(e&&(e.code==='rejected_extension'||e.code==='extension_not_enabled')){try{await CLOUD.dl.save({filename:name+'.txt',data});toast('Saved '+name+'.txt — rename it to '+name+' to open it');return}catch(_){}}}}
  if(!IN_FRAME){const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([data],{type:mime||'text/plain'}));a.download=name;document.body.appendChild(a);a.click();setTimeout(()=>{URL.revokeObjectURL(a.href);a.remove()},500);toast('Downloaded '+name);return}
  if(typeof data!=='string'){toast('File saving is not available in this view','err');return}
  try{await navigator.clipboard.writeText(data);toast('File saving is not available here — copied '+name+' contents to the clipboard')}catch(e){toast('File saving is not available in this view','err')}
}
const csv=rows=>rows.map(r=>r.map(v=>{const s=String(v??'');return /[",\n]/.test(s)?'"'+s.replace(/"/g,'""')+'"':s}).join(',')).join('\n');

/* ---------- toast ---------- */
let toastT;
function toast(msg,kind){const t=$('#toast');t.textContent=msg;if(kind==='undo'){const b=document.createElement('button');b.className='tbtn';b.dataset.act='undo';b.textContent='↶ Undo';t.appendChild(b)}t.className='toast'+(kind==='err'?' err':'');t.hidden=false;clearTimeout(toastT);toastT=setTimeout(()=>t.hidden=true,kind==='err'?6500:kind==='undo'?7000:4000)}

/* ---------- history ---------- */
function commit(){
  S.updatedAt=Date.now();if(S.sample)S.sample=false;
  const snap=JSON.stringify(S);
  if(HIST.stack[HIST.i]!==snap){HIST.stack=HIST.stack.slice(0,HIST.i+1);HIST.stack.push(snap);if(HIST.stack.length>100)HIST.stack.shift();HIST.i=HIST.stack.length-1}
  invalidate();scheduleSave();render();
}
function undo(){if(HIST.i>0){HIST.i--;S=JSON.parse(HIST.stack[HIST.i]);invalidate();scheduleSave();render();toast('Undone')}}
function redo(){if(HIST.i<HIST.stack.length-1){HIST.i++;S=JSON.parse(HIST.stack[HIST.i]);invalidate();scheduleSave();render()}}

/* ---------- binding ---------- */
function resolve(path){
  const parts=path.split('.');let obj=S;
  if(parts[0]==='it'){obj=S.items.find(i=>i.id===parts[1]);parts.splice(0,2)}
  else if(parts[0]==='ut'){obj=S.utils.find(i=>i.id===parts[1]);parts.splice(0,2)}
  if(!obj)return null;
  for(let k=0;k<parts.length-1;k++){if(obj[parts[k]]==null)obj[parts[k]]={};obj=obj[parts[k]]}
  return {obj,key:parts[parts.length-1]};
}
const AFTER={
  model(it){const m=MODELS[it.t]?.[it.model];if(m){it.w=m[1];it.d=m[2];it.h=m[3]}},
  lay(it){const st=S.settings;if(it.lay==='wall'){it.z=st.wallBottom;it.h=30;it.d=12}else if(it.lay==='tall'){it.z=0;it.h=84;it.d=24}else{it.z=0;it.h=34.5;it.d=24}},
  wallh(it){/* keep tops aligned at 84 when changing wall cabinet height */const top=S.settings.wallBottom+30;if(it.t==='W'&&it._prevTop==null){it.z=Math.max(S.settings.counterH+12,top-it.h)}},
  walls(){while(S.corners.length<S.walls.length)S.corners.push({ang:90})},
  diag(el){}
};
let NEXT_FOCUS=false;
function onChange(e){
  const el=e.target;const b=el.dataset.bind;if(!b)return;
  const k=el.dataset.kind;let v;
  if(k==='len'){
    if(el.value.trim()===''&&el.dataset.null!=null)v=null;
    else{v=parseLen(el.value);if(v==null||isNaN(v)){toast('Couldn\'t read "'+el.value+'". Try 123 5/8, 10\' 3-1/2", or 3125mm.','err');render();return}
      if(el.dataset.min!=null&&v<+el.dataset.min){toast('That value is too small.','err');render();return}}
  } else if(k==='num'){if(el.value.trim()===''&&el.dataset.null!=null)v=null;else{v=parseFloat(el.value);if(isNaN(v)){render();return}}}
  else if(k==='int'){v=parseInt(el.value,10);if(isNaN(v))v=null}
  else if(k==='bool')v=el.checked;
  else v=el.value;
  const r=resolve(b);if(!r)return;
  if(v===null&&el.dataset.null==='delete')delete r.obj[r.key];else r.obj[r.key]=v;
  if(el.dataset.after)AFTER[el.dataset.after]?.(r.obj,el);
  const seq=NEXT_FOCUS?el.dataset.seq:null;NEXT_FOCUS=false;
  commit();
  if(seq!=null){const all=$$('[data-seq]').sort((a,b)=>a.dataset.seq-b.dataset.seq);const nx=all.find(x=>+x.dataset.seq>+seq);if(nx){nx.focus();nx.select?.()}}
}

/* ---------- laser (Web Bluetooth + HID keyboard mode) ---------- */
const LASER={dev:null,name:'',lastInput:null};
const LEICA_S='3ab10100-f831-4395-b29d-570977d5bf94',LEICA_D='3ab10101-f831-4395-b29d-570977d5bf94';
const BOSCH_S='02a6c0d0-0451-4000-b000-fb3210111989',BOSCH_C='02a6c0d1-0451-4000-b000-fb3210111989';
async function laserConnect(){
  if(!navigator.bluetooth){toast(IN_FRAME?'Bluetooth is blocked inside this viewer. Use keyboard (HID) mode, or open the standalone file in Chrome/Edge (Android, Windows, Mac) or the Bluefy browser on iPhone.':'This browser has no Web Bluetooth. Use Chrome or Edge, Bluefy on iPhone, or the laser\'s keyboard (HID) mode.','err');return}
  try{
    const dev=await navigator.bluetooth.requestDevice({filters:[{services:[LEICA_S]},{services:[BOSCH_S]},{namePrefix:'DISTO'},{namePrefix:'GLM'},{namePrefix:'PLR'}],optionalServices:[LEICA_S,BOSCH_S]});
    const server=await dev.gatt.connect();
    let ok=false;
    try{const svc=await server.getPrimaryService(LEICA_S);const ch=await svc.getCharacteristic(LEICA_D);await ch.startNotifications();ch.addEventListener('characteristicvaluechanged',e=>laserReading(e.target.value.getFloat32(0,true)/0.0254));ok=true}catch(e){}
    if(!ok){const svc=await server.getPrimaryService(BOSCH_S);const ch=await svc.getCharacteristic(BOSCH_C);await ch.startNotifications();
      ch.addEventListener('characteristicvaluechanged',e=>{const dv=e.target.value;if(dv.byteLength>=11&&dv.getUint8(0)===0xC0&&dv.getUint8(1)===0x55&&dv.getUint8(2)===0x10)laserReading(dv.getFloat32(7,true)/0.0254)});
      await ch.writeValue(new Uint8Array([0xC0,0x55,0x02,0x01,0x00,0x1A]));ok=true}
    LASER.dev=dev;LASER.name=dev.name||'Laser';dev.addEventListener('gattserverdisconnected',()=>{LASER.dev=null;render();toast('Laser disconnected')});
    toast('Connected to '+LASER.name+'. Tap a field, then press the laser\'s measure button.');render();
  }catch(e){if(e&&e.name==='NotFoundError')return;toast('Could not connect: '+(e&&e.message||e),'err')}
}
function laserReading(inches){
  if(!(inches>0))return;
  let el=document.activeElement;
  if(!el||!el.matches||!el.matches('input[data-kind="len"]'))el=LASER.lastInput&&document.body.contains(LASER.lastInput)?LASER.lastInput:null;
  if(!el){toast('Laser: '+fmt(inches,'ft')+' — tap a measurement field first');return}
  el.value=fmt(inches);NEXT_FOCUS=true;
  toast('Laser: '+fmt(inches,'ft')+(el.getAttribute('aria-label')?' → '+el.getAttribute('aria-label'):''));
  el.dispatchEvent(new Event('change',{bubbles:true}));
}

/* ---------- top-level render ---------- */
function render(){
  if(!S)return;
  $('#pname').value=S.name;
  $('#uIn').setAttribute('aria-pressed',S.units==='in');$('#uMm').setAttribute('aria-pressed',S.units==='mm');
  $('#bUndo').disabled=HIST.i<=0;$('#bRedo').disabled=HIST.i>=HIST.stack.length-1;
  $$('.tab').forEach(t=>t.setAttribute('aria-selected',t.dataset.tab===UI.tab));
  $$('.view').forEach(v=>v.hidden=v.id!=='v-'+UI.tab);
  const C=checks();
  $('#chkBadge').innerHTML=C.counts.err?'<span class="badge b-err">'+C.counts.err+'</span>':C.counts.warn?'<span class="badge b-warn">'+C.counts.warn+'</span>':'<span class="badge b-ok">✓</span>';
  if(UI.wall>=S.walls.length)UI.wall=0;if(UI.elevWall>=S.walls.length)UI.elevWall=0;
  const f={measure:renderMeasure,design:renderDesign,elevations:renderElev,view3d:render3DView,estimate:renderEstimate,docs:renderDocs,present:renderPresent,checks:renderChecks}[UI.tab];
  const act=document.activeElement;const aid=act&&act.id;const ss=act&&act.selectionStart;
  f&&f();
  if(aid&&document.activeElement!==act){const n=document.getElementById(aid);if(n&&n!==act&&n.tagName==='INPUT'){n.focus();try{n.setSelectionRange(ss,ss)}catch(e){}}}
}


/* ---------- IndexedDB (device-local blobs: captures, models, files) ---------- */
let IDB_P=null;
function idb(){if(IDB_P)return IDB_P;IDB_P=new Promise((res,rej)=>{try{const r=indexedDB.open('plumbsquare',1);r.onupgradeneeded=()=>r.result.createObjectStore('kv');r.onsuccess=()=>res(r.result);r.onerror=()=>rej(r.error)}catch(e){rej(e)}});return IDB_P}
async function idbSet(k,v){try{const d=await idb();return await new Promise((res,rej)=>{const t=d.transaction('kv','readwrite');t.objectStore('kv').put(v,k);t.oncomplete=()=>res(true);t.onerror=()=>rej(t.error)})}catch(e){return false}}
async function idbGet(k){try{const d=await idb();return await new Promise((res)=>{const r=d.transaction('kv').objectStore('kv').get(k);r.onsuccess=()=>res(r.result);r.onerror=()=>res(null)})}catch(e){return null}}
async function idbDel(k){try{const d=await idb();return await new Promise(res=>{const t=d.transaction('kv','readwrite');t.objectStore('kv').delete(k);t.oncomplete=()=>res(true);t.onerror=()=>res(false)})}catch(e){return false}}
