/* ---------- 3D: build, render styles, material painter, exports ---------- */
const V3={ready:false,renderer:null,scene:null,camera:null,group:null,raf:0,orb:{th:-0.6,ph:1.05,r:300,t:null},walk:{x:0,z:0,yaw:0,pitch:0},mv:{},walls:[],models:{},busy:false};
const STYLE_CSS={
  watercolor:{filter:'saturate(1.5) contrast(0.82) brightness(1.12) blur(0.9px)',paper:1},
  sketch:{filter:'grayscale(1) contrast(1.2) brightness(1.04)',paper:1},
  pastel:{filter:'saturate(0.62) brightness(1.08) contrast(0.88)',paper:0},
  line:{filter:'',paper:0},technical:{filter:'',paper:0},clay:{filter:'',paper:0},toon:{filter:'',paper:0},blueprint:{filter:'',paper:0},xray:{filter:'',paper:0},photo:{filter:'',paper:0}
};
function render3DView(){
  const st=S.settings;
  $('#v3tools').innerHTML=`
    <div class="seg" role="group" aria-label="Camera">${[['orbit','Orbit'],['walk','Walk'],['top','Top']].map(([k,l])=>`<button data-act="cam" data-v="${k}" aria-pressed="${UI.cam===k}">${l}</button>`).join('')}</div>
    <select class="in" id="rstyle" style="width:auto;min-height:32px" aria-label="Rendering style">${RSTYLE.map(([k,l])=>`<option value="${k}" ${st.renderStyle===k?'selected':''}>${l}</option>`).join('')}</select>
    <div class="seg" role="group" aria-label="Lighting"><button data-act="light" data-v="day" aria-pressed="${st.light==='day'}">Day</button><button data-act="light" data-v="evening" aria-pressed="${st.light!=='day'}">Evening</button></div>
    <button class="btn sm" data-act="paint" aria-pressed="${!!UI.paint}" style="${UI.paint?'background:var(--accent);color:var(--on-accent)':''}">🖌 Paint</button>
    <button class="btn sm" data-act="capture">Capture for presentation</button>
    <button class="btn sm" data-act="shot" data-v="1">Save image</button>
    <button class="btn sm" data-act="shot" data-v="4k">4K render</button>
    <button class="btn sm" data-act="pano">360° panorama</button>
    <button class="btn sm" data-act="importModel">Import 3D model</button>`;
  $('#paintbar').hidden=!UI.paint;
  if(UI.paint)$('#paintbar').innerHTML=`<span class="eyebrow" style="color:#fff">Tap a cabinet or island to paint it</span>${Object.entries(FINISH).map(([k,v])=>`<button class="swatch" style="background:${v[1]}" title="${esc(v[0])}" aria-label="${esc(v[0])}" aria-pressed="${UI.paint===k}" data-act="paintpick" data-v="${k}"></button>`).join('')}<button class="btn sm" data-act="paintreset">Reset all to project finish</button><button class="btn sm" data-act="paint">Done</button>`;
  $('#joy').hidden=UI.cam!=='walk';
  const sw=(key,map,label)=>`<div><div class="eyebrow" style="margin-bottom:6px">${label}</div><div class="sw-row">${Object.entries(map).map(([k,v])=>`<button class="swatch" style="background:${v[1]}" title="${esc(v[0])}" aria-label="${esc(v[0])}" aria-pressed="${(st[key]||st.finish)===k}" data-act="finish" data-k="${key}" data-v="${k}"></button>`).join('')}</div><div class="hint" style="margin-top:4px">${esc((map[st[key]]||map[st.finish])[0])}</div></div>`;
  $('#finishes').innerHTML=sw('finish',FINISH,'Cabinets')+sw('islandFinish',FINISH,'Island')+sw('counter',COUNTER,'Countertop')+sw('hw',HW,'Hardware')+sw('floor',FLOOR,'Floor')+sw('wallc',WALLC,'Walls')+
    `<div><div class="eyebrow" style="margin-bottom:6px">Door style</div><select class="in" id="f-door" data-bind="settings.doorStyle" data-kind="text">${Object.entries(DOOR).filter(([k])=>k!=='glass').map(([k,v])=>`<option value="${k}" ${st.doorStyle===k?'selected':''}>${v}</option>`).join('')}</select>
     <label class="row" style="margin-top:8px;font-size:13px"><input type="checkbox" id="f-glass" data-bind="settings.glassUppers" data-kind="bool" ${st.glassUppers?'checked':''}> Glass-front wall cabinets</label></div>
     <div><div class="eyebrow" style="margin-bottom:6px">Countertop edge</div><select class="in" id="f-edge" data-bind="settings.edge" data-kind="text">${Object.entries(EDGE).map(([k,v])=>`<option value="${k}" ${st.edge===k?'selected':''}>${v[0]}</option>`).join('')}</select>
     <label class="row" style="margin-top:8px;font-size:13px"><input type="checkbox" id="f-wf" data-bind="settings.waterfall" data-kind="bool" ${st.waterfall?'checked':''}> Waterfall island ends</label></div>
     <div><div class="eyebrow" style="margin-bottom:6px">Backsplash</div><select class="in" id="f-splash" data-bind="settings.splash" data-kind="text">${Object.entries(SPLASH).map(([k,v])=>`<option value="${k}" ${st.splash===k?'selected':''}>${v}</option>`).join('')}</select>
     <div class="eyebrow" style="margin:10px 0 6px">Sink</div><select class="in" id="f-sink" data-bind="settings.sink" data-kind="text">${Object.entries(SINK).map(([k,v])=>`<option value="${k}" ${st.sink===k?'selected':''}>${v}</option>`).join('')}</select></div>`;
  if(!init3D())return;
  build3D();
  resize3D();
  if(!V3.raf)loop3D();
}
function init3D(){
  if(V3.ready)return true;
  if(!window.THREE){$('#no3d').hidden=false;$('#no3d').textContent='The 3D engine could not load. Check your connection and reload.';return false}
  try{
    const c=$('#c3d');const r=new THREE.WebGLRenderer({canvas:c,antialias:true});r.setPixelRatio(Math.min(2,window.devicePixelRatio||1));
    r.shadowMap.enabled=true;r.shadowMap.type=THREE.PCFSoftShadowMap;r.outputEncoding=THREE.sRGBEncoding;r.toneMapping=THREE.ACESFilmicToneMapping;r.toneMappingExposure=0.92;
    V3.renderer=r;V3.scene=new THREE.Scene();V3.camera=new THREE.PerspectiveCamera(50,1,1,6000);if(!V3.orb.t){V3.orb.t=new THREE.Vector3();setCam(UI.cam||'orbit')}
    new ResizeObserver(resize3D).observe($('#v3wrap'));
    $('#paper').style.backgroundImage='url('+paperURL()+')';
    wire3D(c);V3.ready=true;return true;
  }catch(e){$('#no3d').hidden=false;$('#no3d').textContent='3D needs WebGL, which this device or browser has turned off.';return false}
}
function resize3D(){if(!V3.ready||V3.busy)return;const el=$('#v3wrap');const w=el.clientWidth,h=el.clientHeight;if(!w||!h)return;V3.renderer.setSize(w,h,false);V3.camera.aspect=w/h;V3.camera.updateProjectionMatrix()}
function wire3D(c){
  const pts=new Map();let pd=null,down=null;
  c.addEventListener('pointerdown',e=>{c.setPointerCapture(e.pointerId);pts.set(e.pointerId,{x:e.clientX,y:e.clientY});down={x:e.clientX,y:e.clientY,t:Date.now()};if(pts.size===2){const [a,b]=[...pts.values()];pd=Math.hypot(a.x-b.x,a.y-b.y)}});
  c.addEventListener('pointermove',e=>{if(!pts.has(e.pointerId))return;const prev=pts.get(e.pointerId);pts.set(e.pointerId,{x:e.clientX,y:e.clientY});
    if(pts.size===2){const [a,b]=[...pts.values()];const d=Math.hypot(a.x-b.x,a.y-b.y);if(pd){V3.orb.r=clamp(V3.orb.r*pd/d,60,1400)}pd=d;return}
    const dx=e.clientX-prev.x,dy=e.clientY-prev.y;
    if(UI.cam==='walk'){V3.walk.yaw-=dx*0.005;V3.walk.pitch=clamp(V3.walk.pitch-dy*0.004,-1.2,1.2)}
    else if(UI.cam==='top'){}
    else if(e.shiftKey||e.buttons===2){const k=V3.orb.r/600;const th=V3.orb.th;V3.orb.t.x-=Math.cos(th)*dx*k;V3.orb.t.z+=Math.sin(th)*dx*k;V3.orb.t.y+=dy*k}
    else{V3.orb.th-=dx*0.006;V3.orb.ph=clamp(V3.orb.ph-dy*0.005,0.08,1.52)}
  });
  const up=e=>{pts.delete(e.pointerId);if(pts.size<2)pd=null;
    if(down&&UI.paint&&Math.hypot(e.clientX-down.x,e.clientY-down.y)<6&&Date.now()-down.t<600)paintAt(e);down=null};
  c.addEventListener('pointerup',up);c.addEventListener('pointercancel',e=>{pts.delete(e.pointerId);down=null});c.addEventListener('contextmenu',e=>e.preventDefault());
  c.addEventListener('wheel',e=>{e.preventDefault();if(UI.cam==='walk'){moveWalk(e.deltaY>0?-8:8,0)}else V3.orb.r=clamp(V3.orb.r*(e.deltaY>0?1.1:0.91),60,1400)},{passive:false});
  $$('#joy button').forEach(b=>{const on=e=>{e.preventDefault();V3.mv[b.dataset.mv]=true},off=()=>{V3.mv[b.dataset.mv]=false};b.addEventListener('pointerdown',on);b.addEventListener('pointerup',off);b.addEventListener('pointerleave',off);b.addEventListener('pointercancel',off)});
  window.addEventListener('keydown',e=>{if(UI.tab!=='view3d'||UI.cam!=='walk'||e.target.matches('input,select,textarea'))return;const m={ArrowUp:'f',KeyW:'f',ArrowDown:'b',KeyS:'b',ArrowLeft:'l',KeyA:'l',ArrowRight:'r',KeyD:'r'}[e.code];if(m){V3.mv[m]=true;e.preventDefault()}});
  window.addEventListener('keyup',e=>{const m={ArrowUp:'f',KeyW:'f',ArrowDown:'b',KeyS:'b',ArrowLeft:'l',KeyA:'l',ArrowRight:'r',KeyD:'r'}[e.code];if(m)V3.mv[m]=false});
}
function paintAt(e){
  const r=$('#c3d').getBoundingClientRect();const v=new THREE.Vector2((e.clientX-r.left)/r.width*2-1,-(e.clientY-r.top)/r.height*2+1);
  const rc=new THREE.Raycaster();rc.setFromCamera(v,V3.camera);
  const hits=rc.intersectObjects(V3.group.children,true).filter(h=>h.object.visible&&h.object.isMesh);
  for(const h of hits){let o=h.object;while(o&&!o.userData.itemId)o=o.parent;if(!o)continue;const it=S.items.find(i=>i.id===o.userData.itemId);if(!it)continue;
    const c=CAT[it.t];if(c.app&&it.t!=='CUST'){toast(c.name+' is an appliance — pick a cabinet');return}
    it.finish=UI.paint;commit();toast(CAT[it.t].sku(it)+' → '+FINISH[UI.paint][0]);return}
}
function moveWalk(fw,st){const y=V3.walk.yaw;V3.walk.x+=-Math.sin(y)*fw+Math.cos(y)*st;V3.walk.z+=-Math.cos(y)*fw-Math.sin(y)*st}
function setCam(mode){
  UI.cam=mode;const b=geom().bbox;if(!V3.orb.t)return;
  if(mode==='walk'){V3.walk.x=b.cx;V3.walk.z=-(b.miny+14);V3.walk.yaw=0;V3.walk.pitch=-0.12}
  if(mode==='orbit'){V3.orb.th=-0.55;V3.orb.ph=1.0;V3.orb.r=Math.max(b.w,b.h)*1.35+60}
  V3.orb.t.set(b.cx,30,-b.cy);
}
function placeCamera(cam){
  if(UI.cam==='walk'){cam.fov=68;cam.position.set(V3.walk.x,64,V3.walk.z);const y=V3.walk.yaw,p=V3.walk.pitch;cam.lookAt(V3.walk.x-Math.sin(y)*Math.cos(p),64+Math.sin(p),V3.walk.z-Math.cos(y)*Math.cos(p))}
  else if(UI.cam==='top'){const b=geom().bbox;cam.fov=24;cam.position.set(b.cx,Math.max(b.w,b.h*cam.aspect)/(2*Math.tan(12*D2R))+60,-b.cy+0.01);cam.lookAt(b.cx,0,-b.cy)}
  else{const o=V3.orb;cam.fov=50;cam.position.set(o.t.x+o.r*Math.sin(o.ph)*Math.sin(o.th),o.t.y+o.r*Math.cos(o.ph),o.t.z+o.r*Math.sin(o.ph)*Math.cos(o.th));cam.lookAt(o.t)}
  cam.updateProjectionMatrix();
  const tmp=new THREE.Vector3();
  for(const w of V3.walls){tmp.copy(cam.position).sub(w.p);w.g.visible=UI.cam==='walk'||tmp.dot(w.n)>0}
}
function loop3D(){
  if(UI.tab!=='view3d'||document.hidden){V3.raf=0;return}
  V3.raf=requestAnimationFrame(loop3D);
  if(V3.busy)return;
  if(UI.cam==='walk'){const sp=1.6;if(V3.mv.f)moveWalk(sp,0);if(V3.mv.b)moveWalk(-sp,0);if(V3.mv.l)moveWalk(0,-sp);if(V3.mv.r)moveWalk(0,sp)}
  placeCamera(V3.camera);
  V3.renderer.render(V3.scene,V3.camera);
}

/* ---------- textures ---------- */
const TEX={};
function canvasTex(key,draw,size=512){if(TEX[key])return TEX[key];const c=document.createElement('canvas');c.width=c.height=size;const g=c.getContext('2d');draw(g,size);const t=new THREE.CanvasTexture(c);t.wrapS=t.wrapT=THREE.RepeatWrapping;t.encoding=THREE.sRGBEncoding;t.anisotropy=4;return TEX[key]=t}
function rng(seed){let s=seed;return ()=>{s=(s*9301+49297)%233280;return s/233280}}
function floorTex(kind){
  const base=FLOOR[kind][1];
  return canvasTex('floor'+kind,(g,n)=>{const r=rng(7);
    if(kind==='oak'||kind==='walnut'){const rows=8;for(let i=0;i<rows;i++){let x=-r()*n;while(x<n){const len=n*(0.35+r()*0.5);g.fillStyle=shade(base,(r()-0.5)*0.16);g.fillRect(x,i*n/rows,len,n/rows);g.strokeStyle='rgba(0,0,0,.25)';g.lineWidth=1.2;g.strokeRect(x,i*n/rows,len,n/rows);
      g.strokeStyle='rgba(0,0,0,.06)';for(let k=0;k<5;k++){g.beginPath();const yy=i*n/rows+r()*n/rows;g.moveTo(x,yy);g.bezierCurveTo(x+len*0.3,yy+3,x+len*0.6,yy-3,x+len,yy);g.stroke()}x+=len}}}
    else{const k=4;for(let i=0;i<k;i++)for(let j=0;j<k;j++){g.fillStyle=shade(base,(r()-0.5)*0.08);g.fillRect(i*n/k,j*n/k,n/k,n/k)}g.strokeStyle=kind==='slate'?'rgba(0,0,0,.5)':'rgba(120,110,100,.5)';g.lineWidth=3;for(let i=0;i<=k;i++){g.beginPath();g.moveTo(i*n/k,0);g.lineTo(i*n/k,n);g.stroke();g.beginPath();g.moveTo(0,i*n/k);g.lineTo(n,i*n/k);g.stroke()}}
  });
}
function counterTex(kind){
  const base=COUNTER[kind][1];
  return canvasTex('ct'+kind,(g,n)=>{g.fillStyle=base;g.fillRect(0,0,n,n);const r=rng(3);
    if(kind==='marble'||kind==='quartz'||kind==='soapstone'){const a=kind==='marble'?0.35:kind==='soapstone'?0.25:0.08;for(let k=0;k<(kind==='marble'?9:14);k++){g.strokeStyle=kind==='soapstone'?`rgba(200,205,205,${a*r()})`:`rgba(120,118,112,${a*r()})`;g.lineWidth=0.6+r()*(kind==='marble'?2.4:0.8);g.beginPath();let x=r()*n,y=0;g.moveTo(x,y);while(y<n){x+=(r()-0.5)*60;y+=20+r()*40;g.lineTo(x,y)}g.stroke()}}
    if(kind==='granite'||kind==='concrete'){for(let k=0;k<5000;k++){g.fillStyle=kind==='granite'?`rgba(${200*r()|0},${200*r()|0},${200*r()|0},${0.25*r()})`:`rgba(60,60,60,${0.1*r()})`;g.fillRect(r()*n,r()*n,1+r()*2,1+r()*2)}}
    if(kind==='butcher'){for(let i=0;i<16;i++){g.fillStyle=shade(base,(r()-0.5)*0.2);g.fillRect(0,i*n/16,n,n/16)}}
  });
}
function tileTex(){return canvasTex('splash',(g,n)=>{g.fillStyle='#f3f1ec';g.fillRect(0,0,n,n);g.strokeStyle='#d8d2c6';g.lineWidth=3;const rows=8;for(let i=0;i<=rows;i++){g.beginPath();g.moveTo(0,i*n/rows);g.lineTo(n,i*n/rows);g.stroke();const off=i%2?n/8:0;for(let x=off;x<=n;x+=n/4){g.beginPath();g.moveTo(x,i*n/rows);g.lineTo(x,(i+1)*n/rows);g.stroke()}}})}
let PAPER=null;
function paperURL(){if(PAPER)return PAPER;const c=document.createElement('canvas');c.width=c.height=256;const g=c.getContext('2d');g.fillStyle='#fbf8f1';g.fillRect(0,0,256,256);const r=rng(11);
  for(let k=0;k<9000;k++){const v=200+r()*55|0;g.fillStyle=`rgba(${v},${v-6},${v-14},${0.18*r()})`;g.fillRect(r()*256,r()*256,1+r()*2,1)}
  for(let k=0;k<60;k++){g.strokeStyle=`rgba(150,140,120,${0.06*r()})`;g.beginPath();const x=r()*256,y=r()*256;g.moveTo(x,y);g.lineTo(x+(r()-0.5)*40,y+(r()-0.5)*40);g.stroke()}
  return PAPER=c.toDataURL('image/png')}
function shade(hex,amt){const c=new THREE.Color(hex);const hsl={};c.getHSL(hsl);c.setHSL(hsl.h,hsl.s,clamp(hsl.l+amt,0,1));return '#'+c.getHexString()}

/* ---------- scene build ---------- */
function build3D(){
  const sc=V3.scene,st=S.settings,G=geom();
  if(V3.group){sc.remove(V3.group);V3.group.traverse(o=>{if(o.geometry)o.geometry.dispose()})}
  sc.children.filter(o=>o.isLight||o.userData.extra).forEach(l=>sc.remove(l));
  const grp=new THREE.Group();V3.group=grp;sc.add(grp);V3.walls=[];
  const eve=st.light!=='day';
  sc.background=new THREE.Color(eve?'#151a1e':'#dfe3e1');
  sc.add(new THREE.HemisphereLight(eve?'#6a7690':'#ffffff',eve?'#2b2520':'#a99d8b',eve?0.35:0.55));
  const b=G.bbox;const sun=new THREE.DirectionalLight(eve?'#7d8db0':'#fff6e8',eve?0.25:1.0);sun.position.set(b.cx+b.w*0.6,260,-b.cy+b.h*0.9);sun.target.position.set(b.cx,0,-b.cy);
  sun.castShadow=true;sun.shadow.mapSize.set(2048,2048);const sh=Math.max(b.w,b.h);Object.assign(sun.shadow.camera,{left:-sh,right:sh,top:sh,bottom:-sh,near:10,far:900});sun.shadow.bias=-0.0008;sc.add(sun);sun.target.userData.extra=1;sc.add(sun.target);
  const M=(color,o={})=>new THREE.MeshStandardMaterial({color,roughness:0.6,metalness:0,...o});
  const finCache={};
  const fin=key=>{key=FINISH[key]?key:st.finish;if(finCache[key])return finCache[key];const c=FINISH[key][1];const wood=['oak','walnut','cherry'].includes(key);
    return finCache[key]={front:M(c,{roughness:wood?0.7:0.5}),carc:M(shade(c,-0.04)),toe:M(shade(c,-0.25)),panel:M(shade(c,0.03),{roughness:wood?0.7:0.5}),groove:M(shade(c,-0.18))}};
  const splashMap=tileTex();splashMap.repeat.set(1,1);
  const mats={fin,
    ct:M('#ffffff',{map:counterTex(st.counter),roughness:['concrete','butcher','soapstone'].includes(st.counter)?0.75:0.22}),
    steel:M('#c3c8cc',{metalness:0.65,roughness:0.32}),dark:M('#1d2124',{roughness:0.35}),glass:M('#0e1214',{roughness:0.08,metalness:0.2}),
    clear:new THREE.MeshStandardMaterial({color:'#dcefff',transparent:true,opacity:0.22,roughness:0.05,metalness:0.1,depthWrite:false}),
    porcelain:M('#f7f7f5',{roughness:0.18}),mirror:M('#dfe6ea',{metalness:0.95,roughness:0.06}),
    hw:M(HW[st.hw][1],{metalness:0.85,roughness:0.28}),wall:M(WALLC[st.wallc][1],{roughness:0.95}),
    floor:M('#ffffff',{map:floorTex(st.floor),roughness:0.7}),win:M('#cfe6f5',{emissive:new THREE.Color(eve?'#0b1624':'#b9d9f0'),emissiveIntensity:eve?0.3:0.9,roughness:0.1}),
    trim:M('#f4f3ef'),led:M('#fff3d6',{emissive:new THREE.Color('#ffd9a0'),emissiveIntensity:eve?2.2:0.6}),splash:M('#ffffff',{map:splashMap,roughness:0.3}),
    interior:M('#efe9dc',{emissive:new THREE.Color('#ffe2b0'),emissiveIntensity:eve?0.5:0.08})
  };
  const box=(g,w,h,d,mat,x,y,z,cast=true)=>{const m=new THREE.Mesh(new THREE.BoxGeometry(Math.max(w,0.01),Math.max(h,0.01),Math.max(d,0.01)),mat);m.position.set(x+w/2,y+h/2,z+d/2);m.castShadow=cast;m.receiveShadow=true;g.add(m);return m};
  // floor
  const shape=new THREE.Shape(G.P.map(p=>new THREE.Vector2(p.x,p.y)));
  const fl=new THREE.Mesh(new THREE.ShapeGeometry(shape),mats.floor);fl.rotation.x=-Math.PI/2;fl.receiveShadow=true;mats.floor.map.repeat.set(1/96,1/96);grp.add(fl);
  // walls with openings
  for(const w of G.walls){
    const wg=new THREE.Group();wg.position.set(w.a.x,0,-w.a.y);wg.rotation.y=Math.atan2(w.d.y,w.d.x);grp.add(wg);
    V3.walls.push({g:wg,p:new THREE.Vector3((w.a.x+w.b.x)/2,0,-(w.a.y+w.b.y)/2),n:new THREE.Vector3(w.n.x,0,-w.n.y)});
    const ops=S.utils.filter(u=>u.wall===w.i&&(u.type==='window'||u.type==='door')).sort((a,b)=>a.x-b.x);
    const H=S.ceiling;let cur=0;const T=4.5;
    const wallPiece=(x0,x1,z0,z1)=>{if(x1-x0<0.05||z1-z0<0.05)return;const m=new THREE.Mesh(new THREE.PlaneGeometry(x1-x0,z1-z0),mats.wall);m.position.set((x0+x1)/2,(z0+z1)/2,0);m.receiveShadow=true;m.userData.noEdge=0;wg.add(m)};
    for(const u of ops){const x0=clamp(u.x,0,w.len),x1=clamp(u.x+u.w,0,w.len);const z0=u.type==='door'?0:u.z,z1=u.z+u.h;
      wallPiece(cur,x0,0,H);wallPiece(x0,x1,z1,H);wallPiece(x0,x1,0,z0);cur=x1;
      const t=+u.trim||0;if(t){box(wg,t,z1-z0+t+(u.type==='door'?0:t),0.6,mats.trim,x0-t,z0-(u.type==='door'?0:t),0,false);box(wg,t,z1-z0+t+(u.type==='door'?0:t),0.6,mats.trim,x1,z0-(u.type==='door'?0:t),0,false);box(wg,x1-x0,t,0.6,mats.trim,x0,z1,0,false);if(u.type==='window')box(wg,x1-x0+2*t,1,2,mats.trim,x0-t,z0-1,0,false)}
      if(u.type==='window'){const gl=new THREE.Mesh(new THREE.PlaneGeometry(x1-x0,z1-z0),mats.win);gl.position.set((x0+x1)/2,(z0+z1)/2,-T);gl.userData.noEdge=1;wg.add(gl);box(wg,1.2,z1-z0,1.5,mats.trim,(x0+x1)/2-0.6,z0,-T,false)}
    }
    wallPiece(cur,w.len,0,H);
    buildCounterRuns(wg,w,mats,box);
  }
  // items
  let leds=0;
  for(const it of S.items){
    const f=frame(it);if(!f)continue;
    const g=new THREE.Group();g.position.set(f.o.x,it.wall==null?0:it.z,-f.o.y);g.rotation.y=Math.atan2(f.U.y,f.U.x);g.userData.itemId=it.id;grp.add(g);
    buildItem3D(g,it,mats,box);
    if(isUpper(it)&&it.t==='W'&&leds<5&&eve){const pl=new THREE.PointLight('#ffcf8a',0.55,90,2);pl.position.set(it.w/2,-2,it.d*0.7);g.add(pl);leds++}
  }
  if(eve){const pl=new THREE.PointLight('#ffe2b8',0.6,500,1.6);pl.position.set(b.cx,S.ceiling-6,-b.cy);sc.add(pl)}
  applyStyle();
}
/* merged countertops + backsplash per wall run */
function buildCounterRuns(wg,w,mats,box){
  const st=S.settings;const items=S.items.filter(i=>i.wall===w.i&&isBaseLevel(i)&&hasCT(i)).sort((a,b)=>a.x-b.x);
  const runs=[];for(const it of items){const r=runs[runs.length-1];if(r&&Math.abs(it.x-r.b)<0.3){r.b=it.x+it.w;r.d=Math.max(r.d,it.d);r.items.push(it)}else runs.push({a:it.x,b:it.x+it.w,d:it.d,items:[it]})}
  const baseAll=S.items.filter(i=>i.wall===w.i&&isBaseLevel(i));
  const uppers=S.items.filter(i=>i.wall===w.i&&isUpper(i)&&i.t!=='MIR');
  const wins=S.utils.filter(u=>u.wall===w.i&&u.type==='window');
  const H=st.counterH,T=st.ctThick,thick=st.edge==='mitered'?3:T;
  const g=new THREE.Group();g.rotation.y=0;wg.add(g);
  // wall group: x along wall, y up, local +z points into the room (same frame as wall-mounted items)
  for(const r of runs){
    const exposedL=r.a>0.3&&!baseAll.some(o=>Math.abs(o.x+o.w-r.a)<0.3), exposedR=r.b<w.len-0.3&&!baseAll.some(o=>Math.abs(o.x-r.b)<0.3);
    const a=r.a-(exposedL?1:0),b=r.b+(exposedR?1:0),dep=r.d+1.5;
    const slab=box(g,b-a,T,dep,mats.ct,a,H-T,0);slab.userData.ct=1;
    edgeTreatment(g,mats,box,a,b,H,T,dep,thick);
    // sink cut visual on each sink base handled in item; backsplash
    const pts=new Set([r.a,r.b]);uppers.forEach(u=>{if(u.x>r.a&&u.x<r.b)pts.add(u.x);if(u.x+u.w>r.a&&u.x+u.w<r.b)pts.add(u.x+u.w)});
    wins.forEach(u=>{const t=+u.trim||0;[u.x-t,u.x+u.w+t].forEach(x=>{if(x>r.a&&x<r.b)pts.add(x)})});
    const xs=[...pts].sort((p,q)=>p-q);
    for(let k=0;k<xs.length-1;k++){const x0=xs[k],x1=xs[k+1],mid=(x0+x1)/2;if(x1-x0<0.2)continue;
      const up=uppers.find(u=>u.x<=mid&&u.x+u.w>=mid);const win=wins.find(u=>u.x-(+u.trim||0)<=mid&&u.x+u.w+(+u.trim||0)>=mid);
      let top=st.splash==='4'?H+4:st.splash==='full'?S.ceiling:st.wallBottom;
      if(up&&st.splash!=='4')top=Math.min(up.z,st.splash==='full'?up.z:st.wallBottom);
      if(win)top=Math.min(top,win.z-(+win.trim||0));
      if(top-H>0.5){const m=box(g,x1-x0,top-H,0.4,mats.splash,x0,H,0,false);m.material=mats.splash.clone();m.material.map=mats.splash.map.clone();m.material.map.needsUpdate=true;m.material.map.repeat.set((x1-x0)/24,(top-H)/12)}
    }
  }
}
function edgeTreatment(g,mats,box,a,b,H,T,zFront,thick){
  const st=S.settings;
  if(st.edge==='mitered')box(g,b-a,thick-T,T,mats.ct,a,H-thick,zFront-T);
  else if(st.edge==='bullnose'||st.edge==='ogee'){const cyl=new THREE.Mesh(new THREE.CylinderGeometry(T/2,T/2,b-a,12,1),mats.ct);cyl.rotation.z=Math.PI/2;cyl.position.set((a+b)/2,H-T/2,zFront);g.add(cyl)}
}
function buildItem3D(g,it,m,box){
  const st=S.settings,w=it.w,d=it.d,h=it.h,toe=st.toe;
  const fk=it.finish||(it.t==='ISL'&&st.islandFinish?st.islandFinish:st.finish);const F=m.fin(fk);
  const glassUp=(it.door==='glass')||(st.glassUppers&&it.t==='W'&&it.door!=='solid');
  const style=it.door&&it.door!=='glass'&&it.door!=='solid'?it.door:st.doorStyle;
  const frameStyle=st.construction!=='frameless';
  const door=(x,y,ww,hh,zf,kind)=>{
    if(hh<8||ww<8||style==='slab'&&!(glassUp&&kind==='door')){box(g,ww,hh,0.75,F.front,x,y,zf-0.75);return}
    const r=Math.min(2.5,ww/5);
    box(g,ww,r,0.75,F.front,x,y+hh-r,zf-0.75);box(g,ww,r,0.75,F.front,x,y,zf-0.75);box(g,r,hh-2*r,0.75,F.front,x,y+r,zf-0.75);box(g,r,hh-2*r,0.75,F.front,x+ww-r,y+r,zf-0.75);
    if(glassUp&&kind==='door'){const gp=box(g,ww-2*r,hh-2*r,0.15,m.clear,x+r,y+r,zf-0.5,false);gp.userData.noEdge=1;return}
    if(style==='raised'){box(g,ww-2*r,hh-2*r,0.5,F.front,x+r,y+r,zf-0.75);box(g,ww-2*r-2.4,hh-2*r-2.4,0.45,F.panel,x+r+1.2,y+r+1.2,zf-0.4)}
    else{box(g,ww-2*r,hh-2*r,0.5,F.front,x+r,y+r,zf-0.75);
      if(style==='beaded')for(let gx=x+r+1.75;gx<x+ww-r-0.5;gx+=1.75)box(g,0.12,hh-2*r,0.05,F.groove,gx,y+r,zf-0.27,false)}
  };
  const pull=(x,y,zf,vert,len=6)=>{if(vert){box(g,0.45,len,0.45,m.hw,x-0.22,y-len/2,zf+0.5);box(g,0.3,0.3,0.6,m.hw,x-0.15,y-len/2+0.5,zf);box(g,0.3,0.3,0.6,m.hw,x-0.15,y+len/2-0.8,zf)}else{box(g,len,0.45,0.45,m.hw,x-len/2,y-0.22,zf+0.5);box(g,0.3,0.3,0.6,m.hw,x-len/2+0.5,y-0.15,zf);box(g,0.3,0.3,0.6,m.hw,x+len/2-0.8,y-0.15,zf)}};
  const fr=(list,zf,upper=false)=>{for(const f of list){if(f.k==='blind'){box(g,f.w,f.h,0.75,F.front,f.x,f.y,zf-0.75);continue}if(f.k==='oven'){box(g,f.w,f.h,0.75,m.steel,f.x,f.y,zf-0.75);box(g,f.w-4,f.h-10,0.3,m.glass,f.x+2,f.y+4,zf);pull(f.x+f.w/2,f.y+f.h-3,zf,false,f.w-6);continue}
    door(f.x,f.y,f.w,f.h,zf,f.k);
    if(f.k==='door'){const px=f.hinge==='R'?f.x+1.8:f.x+f.w-1.8;const py=upper?f.y+4:f.y+f.h-5;pull(px,py,zf,true,Math.min(6,f.h*0.4))}
    else if(f.k==='drawer')pull(f.x+f.w/2,f.y+Math.min(f.h/2,f.h-3),zf,false,Math.min(8,f.w*0.45))}};
  const faceFrame=(y0,y1,zf)=>{if(!frameStyle)return;box(g,1.5,y1-y0,0.75,F.front,0,y0,zf-0.75);box(g,1.5,y1-y0,0.75,F.front,w-1.5,y0,zf-0.75);box(g,w,1.5,0.75,F.front,0,y1-1.5,zf-0.75);box(g,w,1.5,0.75,F.front,0,y0,zf-0.75)};
  const c=CAT[it.t];
  if(it.t==='ISL'){
    const H=st.counterH-st.ctThick;const cz=d-1-24;const ends=st.waterfall?st.ctThick:0;
    box(g,w-2-2*ends,toe,20,F.toe,1+ends,0,cz+1);
    box(g,0.75,H,it.d-1-cz+0.01,F.front,1+ends,0,cz);box(g,0.75,H,it.d-1-cz+0.01,F.front,w-1.75-ends,0,cz);
    box(g,w-3.5-2*ends,H-toe,23.25,F.carc,1.75+ends,toe,cz);
    box(g,w-2-2*ends,H,0.75,F.front,1+ends,0,cz-0.75);
    fr(fronts(it).map(f=>({...f,x:f.x+ends})),d-1);
    const thick=st.edge==='mitered'?3:st.ctThick;
    box(g,w,st.ctThick,d,m.ct,0,H,0);
    if(st.edge==='mitered')box(g,w,thick-st.ctThick,st.ctThick,m.ct,0,H-(thick-st.ctThick),d-st.ctThick);
    else if(st.edge==='bullnose'||st.edge==='ogee'){const cyl=new THREE.Mesh(new THREE.CylinderGeometry(st.ctThick/2,st.ctThick/2,w,12),m.ct);cyl.rotation.z=Math.PI/2;cyl.position.set(w/2,H+st.ctThick/2,d);g.add(cyl)}
    if(st.waterfall){box(g,st.ctThick,H,d,m.ct,0,0,0);box(g,st.ctThick,H,d,m.ct,w-st.ctThick,0,0)}
    if(it.seat>=9){const n=Math.floor(w/24);for(let k=0;k<n;k++){const sx=(k+0.5)*w/n;const s=new THREE.Group();s.position.set(sx,0,cz-11);g.add(s);
      const seat=new THREE.Mesh(new THREE.CylinderGeometry(7,7,2,20),m.dark);seat.position.y=25;seat.castShadow=true;s.add(seat);
      for(const [a,b] of [[-5,-5],[5,-5],[-5,5],[5,5]]){const l=new THREE.Mesh(new THREE.CylinderGeometry(0.5,0.5,24,6),m.hw);l.position.set(a*0.9,12,b*0.9);l.castShadow=true;s.add(l)}}}
    // pendants
    const np=Math.max(1,Math.round(w/30));for(let k=0;k<np;k++){const px=(k+0.5)*w/np;const cord=new THREE.Mesh(new THREE.CylinderGeometry(0.15,0.15,S.ceiling-68,4),m.dark);cord.position.set(px,68+(S.ceiling-68)/2,d/2);g.add(cord);
      const sh=new THREE.Mesh(new THREE.CylinderGeometry(2.5,5,6,16,1,true),m.hw);sh.position.set(px,66,d/2);g.add(sh);const bulb=new THREE.Mesh(new THREE.SphereGeometry(1.6,10,8),m.led);bulb.position.set(px,63.6,d/2);g.add(bulb)}
    return;
  }
  if(c.cls==='filler'){box(g,w,h,d,F.front,0,0,0);if(it.lay==='base'||it.lay==='tall')box(g,w,toe,d-3,F.toe,0,0,0);return}
  if(it.t==='RG'){box(g,w,h-1,d-1,m.steel,0,0,0);box(g,w,1,d,m.dark,0,h-1,0);box(g,w,6,2,m.steel,0,h-1,0);
    for(const [u,v] of [[.27,.35],[.73,.35],[.27,.72],[.73,.72]]){const bu=new THREE.Mesh(new THREE.CylinderGeometry(Math.min(w,d)*0.12,Math.min(w,d)*0.12,0.6,18),m.dark);bu.position.set(w*u,h+0.2,d*v);g.add(bu)}
    box(g,w-5,15,0.3,m.glass,2.5,9,d-1);box(g,w-6,0.8,1.6,m.steel,3,25.5,d-0.6);for(let k=0;k<(w>=36?6:4);k++){const kn=new THREE.Mesh(new THREE.CylinderGeometry(0.9,0.9,1.2,12),m.dark);kn.rotation.x=Math.PI/2;kn.position.set(3+k*(w-6)/((w>=36?6:4)-1),h-3.5,d-0.5);g.add(kn)}return}
  if(it.t==='DW'||it.t==='MWD'){box(g,w,toe,d-3,F.toe,0,0,0);box(g,w-0.125,h-toe,d-0.5,it.t==='DW'?m.steel:m.dark,0.06,toe,0);box(g,w-0.125,3,0.3,m.dark,0.06,h-3.5,d-0.5);pull(w/2,h-6,d-0.5,false,w-6);return}
  if(it.t==='REF'){box(g,w,h,d-2,m.steel,0,0,0);const fz=h*0.35;box(g,w/2-0.1,h-fz-0.2,2,m.steel,0,fz+0.2,d-2);box(g,w/2-0.1,h-fz-0.2,2,m.steel,w/2+0.1,fz+0.2,d-2);box(g,w,fz,2,m.steel,0,0,d-2);
    pull(w/2-2,fz+(h-fz)/2,d,true,24);pull(w/2+2,fz+(h-fz)/2,d,true,24);pull(w/2,fz-3,d,false,w-10);return}
  if(it.t==='HOOD'){const can=new THREE.Mesh(new THREE.CylinderGeometry(0.5,0.70710678,1,4,1),m.steel);can.rotation.y=Math.PI/4;can.scale.set(w,h,d);can.position.set(w/2,h/2,d/2);can.castShadow=true;g.add(can);
    box(g,w*0.36,Math.max(0,S.ceiling-it.z-h),d*0.45,m.steel,w*0.32,h,1);box(g,w-2,0.6,d-2,m.led,1,-0.1,1,false);return}
  if(it.t==='WC'){const tank=box(g,Math.min(w,19),15,7.5,m.porcelain,(w-Math.min(w,19))/2,15,0.5);box(g,Math.min(w,19)+0.5,1,8,m.porcelain,(w-Math.min(w,19))/2-0.25,30,0.25);
    const ped=new THREE.Mesh(new THREE.CylinderGeometry(4.5,5.5,10,20),m.porcelain);ped.scale.set(1,1,1.4);ped.position.set(w/2,5,d*0.5);g.add(ped);
    const bowl=new THREE.Mesh(new THREE.CylinderGeometry(7.5,5.5,6,24),m.porcelain);bowl.scale.set(1,1,1.35);bowl.position.set(w/2,13,d*0.58);g.add(bowl);
    const seat=new THREE.Mesh(new THREE.TorusGeometry(6.2,1.1,8,24),m.porcelain);seat.rotation.x=Math.PI/2;seat.scale.set(1,1.35,1);seat.position.set(w/2,16.4,d*0.58);g.add(seat);
    box(g,2.5,0.6,0.6,m.hw,(w-Math.min(w,19))/2+1.5,27,8.2);return}
  if(it.t==='TUB'||it.t==='TP'){const ht=h;box(g,w,ht,d,it.t==='TP'?m.fin(fk).front:m.porcelain,0,0,0);
    if(it.t==='TP'){box(g,w,1.5,d,m.ct,0,ht,0)}
    const ins=box(g,w-6,1,d-6,m.porcelain,3,ht+(it.t==='TP'?1.2:0.05),3,false);ins.material=new THREE.MeshStandardMaterial({color:'#e8eef0',roughness:0.1});
    const sp=new THREE.Mesh(new THREE.CylinderGeometry(0.5,0.5,5,10),m.hw);sp.rotation.x=Math.PI/2;sp.position.set(w/2,ht+(it.t==='TP'?3:12),2.5);g.add(sp);return}
  if(it.t==='SHW'){box(g,w,3,d,m.porcelain,0,0,0);const gl=[[0,0,w,0.4],[w-0.4,0,0.4,d]];
    const pnl=box(g,w,h-3,0.4,m.clear,0,3,d-0.4,false);pnl.userData.noEdge=0;box(g,w,0.6,0.6,m.hw,0,h,d-0.5,false);
    const head=new THREE.Mesh(new THREE.CylinderGeometry(4,4,0.6,20),m.hw);head.position.set(w/2,78,6);g.add(head);box(g,0.6,10,0.6,m.hw,w/2-0.3,72,1);
    const tile=box(g,w,h,0.3,m.splash,0,0,0,false);tile.material=m.splash.clone();tile.material.map=m.splash.map.clone();tile.material.map.needsUpdate=true;tile.material.map.repeat.set(w/24,h/12);return}
  if(it.t==='MIR'){box(g,w,h,0.8,F.front,0,0,0);const mr=box(g,w-2,h-2,0.2,m.mirror,1,1,0.8,false);mr.userData.noEdge=0;
    box(g,w*0.7,1.2,3,m.hw,w*0.15,h+3,0.5);return}
  if(it.t==='CUST'){const ph=box(g,w,h,d,new THREE.MeshStandardMaterial({color:'#9fb3c2',transparent:true,opacity:0.35}),0,0,0);ph.userData.noEdge=1;loadModelInto(g,it,ph);return}
  // cabinets
  const base=isBaseLevel(it);
  if(base){box(g,w,toe,d-3.75,F.toe,0,0,0);box(g,w,h-toe,d-0.75,F.carc,0,toe,0);faceFrame(toe,h,d)}else{box(g,w,h,d-0.75,F.carc,0,0,0);faceFrame(0,h,d)}
  if(glassUp&&!base){box(g,w-1.5,h-1.5,0.3,m.interior,0.75,0.75,1.2,false);[h/3,2*h/3].forEach(y=>box(g,w-1.5,0.6,d-2.5,m.clear,0.75,y,1,false))}
  fr(fronts(it),d,!base);
  if(it.t==='SB'){const sinkY=h+st.ctThick+0.05;
    if(st.sink==='farmhouse'){box(g,w-3,9,1.5,m.porcelain,1.5,h-8,d-0.6)}
    const sk=new THREE.Mesh(new THREE.BoxGeometry(w-8,0.4,16),st.sink==='drop'?m.steel:st.sink==='integrated'?m.ct:m.dark);sk.position.set(w/2,sinkY,d*0.55);g.add(sk);
    const fa=new THREE.Mesh(new THREE.CylinderGeometry(0.5,0.5,12,10),m.hw);fa.position.set(w/2,h+st.ctThick+6,3);g.add(fa);const spo=new THREE.Mesh(new THREE.CylinderGeometry(0.4,0.4,8,8),m.hw);spo.rotation.x=Math.PI/2;spo.position.set(w/2,h+st.ctThick+12,6.5);g.add(spo)}
  if(it.t==='VAN'){const n=w>=60?2:1;for(let k=0;k<n;k++){const cx=n===1?w/2:(k===0?w*0.25:w*0.75);const bs=new THREE.Mesh(new THREE.CylinderGeometry(7,7,0.6,24),m.porcelain);bs.scale.set(1.2,1,0.85);bs.position.set(cx,h+st.ctThick+0.05,d*0.55);g.add(bs);
    const fa=new THREE.Mesh(new THREE.CylinderGeometry(0.45,0.45,7,10),m.hw);fa.position.set(cx,h+st.ctThick+3.5,2.5);g.add(fa)}}
  if(it.t==='W'&&!base)box(g,w-2,0.3,2,m.led,1,-0.3,d-4,false);
}

/* ---------- render styles ---------- */
function applyStyle(){
  const style=S.settings.renderStyle||'photo';const grp=V3.group;if(!grp)return;
  const sc=V3.scene;const eve=S.settings.light!=='day';
  const lineCol={line:'#23272a',technical:'#1e2b33',sketch:'#4b4136',blueprint:'#e6eefc',xray:'#2b5f8a',watercolor:'#5a4a38',toon:'#111111'}[style];
  const lineOp={watercolor:0.5,sketch:0.85,xray:0.6}[style]??1;
  const toonGrad=(()=>{const d=new Uint8Array([90,170,255]);const t=new THREE.DataTexture(d,3,1,THREE.LuminanceFormat);t.minFilter=t.magFilter=THREE.NearestFilter;t.needsUpdate=true;return t})();
  const waterGrad=(()=>{const d=new Uint8Array([150,190,225,255]);const t=new THREE.DataTexture(d,4,1,THREE.LuminanceFormat);t.minFilter=t.magFilter=THREE.NearestFilter;t.needsUpdate=true;return t})();
  const cache=new Map();
  const conv=m0=>{if(cache.has(m0))return cache.get(m0);const col=m0.color?m0.color.clone():new THREE.Color('#ccc');let m=m0;const po={polygonOffset:true,polygonOffsetFactor:1,polygonOffsetUnits:1};
    if(style==='clay')m=new THREE.MeshStandardMaterial({color:m0.transparent?'#dfe8ee':'#f1efe9',roughness:0.92,transparent:m0.transparent,opacity:m0.opacity,...po});
    else if(style==='line'||style==='sketch')m=new THREE.MeshBasicMaterial({color:m0.transparent?'#eef4f8':'#ffffff',transparent:m0.transparent,opacity:m0.transparent?0.3:1,...po});
    else if(style==='technical')m=new THREE.MeshLambertMaterial({color:m0.map?col.lerp(new THREE.Color('#d9d2c3'),0.6):col,transparent:m0.transparent,opacity:m0.opacity,...po});
    else if(style==='blueprint')m=new THREE.MeshBasicMaterial({color:'#1b4278',transparent:true,opacity:0.92,...po});
    else if(style==='xray')m=new THREE.MeshStandardMaterial({color:col,transparent:true,opacity:0.16,depthWrite:false,roughness:0.5});
    else if(style==='watercolor')m=new THREE.MeshToonMaterial({color:col.clone().lerp(new THREE.Color('#ffffff'),0.12),map:m0.map||null,gradientMap:waterGrad,transparent:m0.transparent,opacity:m0.opacity,...po});
    else if(style==='toon')m=new THREE.MeshToonMaterial({color:m0.map?col.lerp(new THREE.Color('#e7dcc6'),0.5):col,gradientMap:toonGrad,transparent:m0.transparent,opacity:m0.opacity,...po});
    cache.set(m0,m);return m};
  const lm=lineCol?new THREE.LineBasicMaterial({color:lineCol,transparent:lineOp<1,opacity:lineOp}):null;
  const kill=[];grp.traverse(o=>{if(o.userData.edge)kill.push(o)});kill.forEach(o=>o.parent.remove(o));
  const meshes=[];grp.traverse(o=>{if(o.isMesh)meshes.push(o)});
  for(const o of meshes){if(!o.userData.m0)o.userData.m0=o.material;o.material=style==='photo'||style==='pastel'?o.userData.m0:conv(o.userData.m0);
    if(lm&&!o.userData.noEdge&&o.geometry){const e=new THREE.LineSegments(new THREE.EdgesGeometry(o.geometry,28),lm);e.userData.edge=1;o.add(e)}}
  sc.background=new THREE.Color({line:'#fbfaf6',sketch:'#fbfaf6',technical:'#f4f3ee',clay:'#e9e7e1',blueprint:'#1b4278',xray:'#eef2f4'}[style]||(eve?'#151a1e':'#dfe3e1'));
  const css=STYLE_CSS[style]||STYLE_CSS.photo;$('#c3d').style.filter=css.filter;$('#paper').hidden=!css.paper;
}

/* ---------- exports ---------- */
function composite(src,w,h){
  const style=S.settings.renderStyle||'photo';const css=STYLE_CSS[style]||STYLE_CSS.photo;
  const c=document.createElement('canvas');c.width=w;c.height=h;const g=c.getContext('2d');
  if(css.filter){try{g.filter=css.filter.replace(/blur\(([\d.]+)px\)/,(m,v)=>'blur('+(v*w/1400).toFixed(2)+'px)')}catch(e){}}
  g.drawImage(src,0,0,w,h);g.filter='none';
  if(css.paper){return new Promise(res=>{const im=new Image();im.onload=()=>{g.globalCompositeOperation='multiply';const p=g.createPattern(im,'repeat');g.fillStyle=p;g.fillRect(0,0,w,h);g.globalCompositeOperation='source-over';res(c)};im.src=paperURL()})}
  return Promise.resolve(c);
}
async function renderShot(scale){
  if(!init3D())return null;
  const r=V3.renderer,cam=V3.camera,el=$('#v3wrap');
  let w,h;if(scale==='4k'){w=3840;h=2160}else{w=Math.round(el.clientWidth*Math.min(2,window.devicePixelRatio||1)*1.5);h=Math.round(el.clientHeight*Math.min(2,window.devicePixelRatio||1)*1.5)}
  const max=Math.min(r.capabilities.maxTextureSize||4096,4096);if(w>max){h=Math.round(h*max/w);w=max}
  V3.busy=true;const pr=r.getPixelRatio();
  try{r.setPixelRatio(1);r.setSize(w,h,false);cam.aspect=w/h;placeCamera(cam);r.render(V3.scene,cam);
    const c=await composite(r.domElement,w,h);return c}
  finally{r.setPixelRatio(pr);V3.busy=false;resize3D()}
}
async function shotBlob(scale,type='image/png',q){const c=await renderShot(scale);if(!c)return null;return new Promise(res=>c.toBlob(res,type,q))}
async function panorama(){
  if(!init3D())return;
  const r=V3.renderer,size=Math.min(1536,r.capabilities.maxTextureSize||2048);
  toast('Rendering 360° panorama…');
  V3.busy=true;
  try{
    const rt=new THREE.WebGLCubeRenderTarget(size,{format:THREE.RGBAFormat,generateMipmaps:false});rt.texture.encoding=THREE.sRGBEncoding;
    const cc=new THREE.CubeCamera(1,6000,rt);const b=geom().bbox;
    const pos=UI.cam==='walk'?new THREE.Vector3(V3.walk.x,64,V3.walk.z):new THREE.Vector3(b.cx,64,-(b.miny+30));cc.position.copy(pos);
    V3.walls.forEach(w=>w.g.visible=true);
    const tm=r.toneMapping;cc.update(r,V3.scene);
    const faces=[];for(let f=0;f<6;f++){const buf=new Uint8Array(size*size*4);r.readRenderTargetPixels(rt,0,0,size,size,buf,f);faces.push(buf)}
    rt.dispose();
    const W=Math.min(4096,size*4),H=W/2;const c=document.createElement('canvas');c.width=W;c.height=H;const g=c.getContext('2d');const img=g.createImageData(W,H);const D=img.data;
    for(let j=0;j<H;j++){const lat=Math.PI/2-(j+0.5)/H*Math.PI;const cl=Math.cos(lat),sl=Math.sin(lat);
      for(let i=0;i<W;i++){const lon=(i+0.5)/W*2*Math.PI-Math.PI;const x=cl*Math.sin(lon),y=sl,z=-cl*Math.cos(lon);
        const ax=Math.abs(x),ay=Math.abs(y),az=Math.abs(z);let f,sc,tc,ma;
        if(ax>=ay&&ax>=az){ma=ax;if(x>0){f=0;sc=-z;tc=-y}else{f=1;sc=z;tc=-y}}
        else if(ay>=az){ma=ay;if(y>0){f=2;sc=x;tc=z}else{f=3;sc=x;tc=-z}}
        else{ma=az;if(z>0){f=4;sc=x;tc=-y}else{f=5;sc=-x;tc=-y}}
        const u=clamp(Math.floor((sc/ma+1)/2*size),0,size-1),v=clamp(Math.floor((tc/ma+1)/2*size),0,size-1);
        const si=(v*size+u)*4,di=(j*W+i)*4;const F=faces[f];D[di]=F[si];D[di+1]=F[si+1];D[di+2]=F[si+2];D[di+3]=255}}
    g.putImageData(img,0,0);
    const out=await composite(c,W,H);
    const blob=await new Promise(res=>out.toBlob(res,'image/jpeg',0.9));
    await saveFile('panorama-360-'+slug()+'.jpg',blob,'image/jpeg');
    UI.lastPano=out.toDataURL('image/jpeg',0.6);
  }catch(e){toast('Could not render the panorama: '+(e.message||e),'err')}
  finally{V3.busy=false}
}
async function capture(){
  const c=await renderShot(1);if(!c)return;
  const small=document.createElement('canvas');const k=Math.min(1,1600/c.width);small.width=c.width*k;small.height=c.height*k;small.getContext('2d').drawImage(c,0,0,small.width,small.height);
  const url=small.toDataURL('image/jpeg',0.85);
  await addCapture(url,RSTYLE.find(r=>r[0]===S.settings.renderStyle)?.[1]+' · '+({orbit:'Overview',walk:'Walkthrough',top:'Top view'}[UI.cam]));
}

/* ---------- custom models (GLB) ---------- */
let GLTF_P=null;
function gltfLoader(){if(GLTF_P)return GLTF_P;GLTF_P=new Promise((res,rej)=>{if(THREE.GLTFLoader)return res();const s=document.createElement('script');s.src='https://cdn.jsdelivr.net/npm/three@0.128.0/examples/js/loaders/GLTFLoader.js';s.onload=()=>res();s.onerror=()=>{GLTF_P=null;rej(new Error('Could not load the model reader'))};document.head.appendChild(s)});return GLTF_P}
async function parseGLB(buf){await gltfLoader();return new Promise((res,rej)=>new THREE.GLTFLoader().parse(buf,'',g=>res(g.scene),rej))}
async function loadModelInto(g,it,ph){
  try{
    let obj=V3.models[it.model];
    if(!obj){const buf=await idbGet('model:'+it.model);if(!buf){ph.material.color.set('#e0a0a0');return}obj=await parseGLB(buf);V3.models[it.model]=obj}
    const o=obj.clone(true);const bb=new THREE.Box3().setFromObject(o);const sz=bb.getSize(new THREE.Vector3());
    o.scale.set(it.w/(sz.x||1),it.h/(sz.y||1),it.d/(sz.z||1));const bb2=new THREE.Box3().setFromObject(o);o.position.sub(bb2.min);
    o.traverse(m=>{if(m.isMesh){m.castShadow=m.receiveShadow=true}});
    if(g.parent){g.remove(ph);g.add(o);applyStyle()}
  }catch(e){ph.material.color.set('#e0a0a0')}
}
async function importModel(file){
  try{
    const buf=await file.arrayBuffer();const obj=await parseGLB(buf);
    const bb=new THREE.Box3().setFromObject(obj);const sz=bb.getSize(new THREE.Vector3());
    let k=1;const mx=Math.max(sz.x,sz.y,sz.z);if(mx<20)k=39.3701;
    const key=uid();await idbSet('model:'+key,buf);V3.models[key]=obj;
    const it=mkItem('CUST',{wall:UI.wall,w:r2(sz.x*k),h:r2(sz.y*k),d:r2(sz.z*k),model:key,label:file.name.replace(/\.[^.]+$/,'')});
    const gs=gaps(UI.wall,0,it.h);const gp=gs.find(g=>g.b-g.a>=it.w);it.x=gp?r2(gp.a):0;
    S.items.push(it);SEL=it.id;commit();toast('Imported '+it.label+' ('+fmt(it.w)+' × '+fmt(it.d)+' × '+fmt(it.h)+'). Models stay on this device.');
  }catch(e){toast('Could not read that model. Use a .glb file.','err')}
}
