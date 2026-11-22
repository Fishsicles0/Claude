'use strict';
/* =========================================================================
   Plumb & Square — kitchen site-capture, design, checks and estimating
   Internal unit: inches. Plan world: x east, y north (room traced clockwise,
   so each wall's interior is on its right-hand side).
   ========================================================================= */
const $=(s,r=document)=>r.querySelector(s), $$=(s,r=document)=>[...r.querySelectorAll(s)];
const uid=()=>Math.random().toString(36).slice(2,9);
const clamp=(v,a,b)=>Math.max(a,Math.min(b,v));
const D2R=Math.PI/180;
const clone=o=>JSON.parse(JSON.stringify(o));
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const IN_FRAME=(()=>{try{return window.self!==window.top}catch(e){return true}})();
const money=v=>'$'+(Math.round(v*100)/100).toLocaleString('en-US',{minimumFractionDigits:2,maximumFractionDigits:2});
const q64=v=>Math.round(v*64)/64;
const r2=v=>Math.round(v*100)/100;

/* ---------- units ---------- */
function frac(x){
  const neg=x<0; x=Math.abs(x);
  const n=Math.round(x*16); const whole=Math.floor(n/16); let r=n%16, d=16;
  let s;
  if(r){while(r%2===0){r/=2;d/=2} s=(whole?whole+'-':'')+r+'/'+d}
  else s=String(whole);
  return (neg?'-':'')+s+'"';
}
function fmt(x,mode){
  if(x==null||!isFinite(x))return '—';
  if(S&&S.units==='mm')return Math.round(x*25.4).toLocaleString('en-US')+(mode==='bare'?'':' mm');
  if(mode==='ft'&&Math.abs(x)>=24){
    const neg=x<0; const n=Math.round(Math.abs(x)*16); const ft=Math.floor(n/192); const rem=(n-ft*192)/16;
    return (neg?'-':'')+ft+"' "+frac(rem);
  }
  return frac(x);
}
function fmtIn(x){return fmt(x)} // for inputs
function parseInches(t){
  t=t.replace(/"|inches|inch|in\b/g,'').trim();
  if(!t)return 0;
  let m;
  if(m=t.match(/^(\d+(?:\.\d+)?)[\s-]+(\d+)\/(\d+)$/))return +m[1]+m[2]/m[3];
  if(m=t.match(/^(\d+)\/(\d+)$/))return m[1]/m[2];
  if(/^\d*\.?\d+$/.test(t))return +t;
  return NaN;
}
function parseLen(str){
  if(typeof str==='number')return str;
  let s=String(str??'').trim().toLowerCase().replace(/[”″]/g,'"').replace(/[’′]/g,"'").replace(/,/g,'');
  if(!s)return null;
  let neg=false; if(s[0]==='-'){neg=true;s=s.slice(1).trim()}
  let m,v;
  if(m=s.match(/^(\d*\.?\d+)\s*mm$/))v=m[1]/25.4;
  else if(m=s.match(/^(\d*\.?\d+)\s*cm$/))v=m[1]/2.54;
  else if(m=s.match(/^(\d*\.?\d+)\s*m$/))v=m[1]/0.0254;
  else if(/'|ft/.test(s)){
    const [a,b='']=s.split(/'|ft/); const ft=parseFloat(a); const inch=parseInches(b.replace(/^[\s-]+/,''));
    v=isNaN(ft)||isNaN(inch)?NaN:ft*12+inch;
  } else if(S&&S.units==='mm'&&/^\d*\.?\d+$/.test(s)) v=s/25.4;
  else v=parseInches(s);
  if(v==null||isNaN(v))return NaN;
  return neg?-v:v;
}

/* ---------- catalog ---------- */
const fi=h=>String(h).replace('.5','½');
const wS=w=>Number.isInteger(w)?String(w):frac(w).replace('"','');
const CAT={
  B:  {name:'Base',grp:'Base cabinets',cls:'base',w:18,ws:[9,12,15,18,21,24,27,30,33,36],d:24,h:34.5,ct:1,pk:'base',sku:i=>'B'+wS(i.w)},
  DB: {name:'Drawer base',grp:'Base cabinets',cls:'base',w:24,ws:[12,15,18,21,24,27,30,33,36],d:24,h:34.5,ct:1,pk:'drawer',sku:i=>'DB'+wS(i.w)},
  SB: {name:'Sink base',grp:'Base cabinets',cls:'base',w:36,ws:[24,27,30,33,36,42],d:24,h:34.5,ct:1,pk:'sink',sku:i=>'SB'+wS(i.w)},
  BBC:{name:'Blind corner base',grp:'Base cabinets',cls:'base',w:42,ws:[36,39,42,45,48],d:24,h:34.5,ct:1,pk:'corner',sku:i=>'BBC'+wS(i.w)+(i.hinge==='R'?'R':'L')},
  WP: {name:'Waste pullout',grp:'Base cabinets',cls:'base',w:18,ws:[15,18,21],d:24,h:34.5,ct:1,pk:'drawer',sku:i=>'BWP'+wS(i.w)},
  W:  {name:'Wall',grp:'Wall cabinets',cls:'wall',w:30,ws:[9,12,15,18,21,24,27,30,33,36,42],hs:[12,15,18,24,30,36,42],d:12,h:30,pk:'wall',sku:i=>'W'+wS(i.w)+(Number.isInteger(i.w)?'':'x')+fi(i.h)+(i.d>13?'-24':'')},
  T:  {name:'Pantry / utility',grp:'Tall cabinets',cls:'tall',w:24,ws:[18,24,30,33,36],hs:[84,90,96],d:24,h:84,pk:'tall',sku:i=>'U'+wS(i.w)+(Number.isInteger(i.w)?'':'x')+fi(i.h)},
  OV: {name:'Wall-oven cabinet',grp:'Tall cabinets',cls:'tall',w:30,ws:[27,30,33],hs:[84,90,96],d:24,h:84,pk:'tall',sku:i=>'OV'+wS(i.w)+(Number.isInteger(i.w)?'':'x')+fi(i.h)},
  F:  {name:'Filler',grp:'Trim',cls:'filler',w:3,ws:[0.75,1.5,3,6],d:24,h:34.5,pk:'filler',sku:i=>(i.lay==='tall'?'TF':i.lay==='wall'?'WF':'BF')+r2(i.w)},
  PNL:{name:'End panel',grp:'Trim',cls:'filler',w:0.75,ws:[0.75],d:24,h:34.5,pk:'panel',sku:i=>(i.lay==='tall'?'TEP':i.lay==='wall'?'WEP':'BEP')+fi(i.h)},
  RG: {name:'Range',grp:'Appliances',cls:'app',w:30,ws:[30,36,48],d:28,h:36,app:1,fuel:'gas',sku:i=>'RANGE '+wS(i.w)},
  DW: {name:'Dishwasher',grp:'Appliances',cls:'app',w:24,ws:[18,24],d:24,h:34.5,app:1,ct:1,sku:i=>'DW '+wS(i.w)},
  REF:{name:'Refrigerator',grp:'Appliances',cls:'app',w:36,ws:[30,33,36,42,48],d:30,h:70,app:1,sku:i=>'REF '+wS(i.w)},
  HOOD:{name:'Range hood',grp:'Appliances',cls:'app',w:30,ws:[30,36,42,48],d:20,h:18,app:1,sku:i=>'HOOD '+wS(i.w)},
  MWD:{name:'Microwave drawer',grp:'Appliances',cls:'app',w:24,ws:[24,30],d:24,h:34.5,app:1,ct:1,sku:i=>'MWD '+wS(i.w)},
  ISL:{name:'Island',grp:'Islands',cls:'island',w:72,ws:[48,60,72,84,96,108,120],d:42,h:36,pk:'island',sku:i=>'ISLAND '+wS(i.w)+'×'+i.d},
  VAN:{name:'Vanity sink base',grp:'Bath',cls:'base',w:36,ws:[24,30,36,42,48,60,72],d:21,h:34.5,ct:1,pk:'vanity',sku:i=>(i.w>=60?'VDSB':'VSB')+wS(i.w)},
  VDB:{name:'Vanity drawer base',grp:'Bath',cls:'base',w:15,ws:[12,15,18,21,24],d:21,h:34.5,ct:1,pk:'vanity',sku:i=>'VDB'+wS(i.w)},
  LT: {name:'Linen tower',grp:'Bath',cls:'tall',w:18,ws:[15,18,24],hs:[84,90],d:21,h:84,pk:'tall',sku:i=>'LT'+wS(i.w)+(Number.isInteger(i.w)?'':'x')+fi(i.h)},
  MIR:{name:'Mirror / medicine cab.',grp:'Bath',cls:'app',w:30,ws:[24,30,36,48,60],d:4.5,h:30,app:1,fix:1,sku:i=>'MIRROR '+wS(i.w)},
  WC: {name:'Toilet',grp:'Bath',cls:'app',w:20,ws:[18,20],d:29,h:30,app:1,fix:1,sku:()=>'WC'},
  TUB:{name:'Alcove tub',grp:'Bath',cls:'app',w:60,ws:[60,66,72],d:30,h:20,app:1,fix:1,sku:i=>'TUB '+wS(i.w)},
  TP: {name:'Tub platform',grp:'Bath',cls:'app',w:66,ws:[60,66,72],d:36,h:22,app:1,fix:1,sku:i=>'TUB DECK '+wS(i.w)},
  SHW:{name:'Shower',grp:'Bath',cls:'app',w:48,ws:[32,36,42,48,60],d:36,h:80,app:1,fix:1,sku:i=>'SHOWER '+wS(i.w)+'×'+i.d},
  CUST:{name:'Imported 3D model',grp:'Custom',cls:'app',w:24,ws:[24],d:24,h:36,app:1,hidden:1,sku:i=>(i.label||'Model').toUpperCase().slice(0,16)}
};
const DOOR={shaker:'Shaker (5-piece)',slab:'Slab / flat panel',raised:'Raised panel',beaded:'Beadboard',glass:'Glass-front'};
const CONSTR={frameless:['Frameless, full overlay',1],framed:['Framed, partial overlay',1.05],inset:['Framed, inset doors',1.3]};
const LINE={stock:['Stock line',1],semi:['Semi-custom line',1.4],custom:['Custom shop',2.1]};
const EDGE={eased:['Eased square','eased'],bevel:['Bevel','bevel'],bullnose:['Full bullnose','bullnose'],ogee:['Ogee','ogee'],mitered:['Mitered (thick look)','mitered']};
const SPLASH={'4':'4" curb','18':'Standard, up to wall cabinets','full':'Full height'};
const SINK={undermount:'Undermount stainless',farmhouse:'Farmhouse apron front',drop:'Drop-in',integrated:'Integrated quartz'};
const RSTYLE=[['photo','Photo'],['watercolor','Watercolor'],['sketch','Pencil sketch'],['line','Line drawing'],['technical','Technical'],['clay','Clay'],['toon','Toon'],['blueprint','Blueprint'],['xray','X-ray'],['pastel','Pastel']];
const MODELS={
  RG:[['30" freestanding',30,28,36],['30" slide-in',30,27,36],['36" pro-style',36,28,36],['48" pro-style',48,28,36]],
  REF:[['36" counter-depth French door',36,30,70],['36" standard-depth French door',36,35.5,70],['36" built-in column',36,24,84],['42" built-in side-by-side',42,24,84],['30" top-freezer',30,33,67]],
  DW:[['24" built-in',24,24,34.5],['18" compact',18,24,34.5]],
  HOOD:[['30" chimney',30,20,18],['36" chimney',36,22,18],['48" pro chimney',48,24,24]],
  MWD:[['24" drawer',24,24,34.5],['30" drawer',30,24,34.5]],
  WC:[['Elongated, comfort height',20,29.5,30],['Round front, compact',18,27,29],['Wall-hung',20,22,26]],
  SHW:[['32×32 neo-square',32,32,80],['36×36 square',36,36,80],['48×36 walk-in',48,36,80],['60×32 tub replacement',60,32,80],['60×36 curbless',60,36,80]],
  TUB:[['60×30 alcove',60,30,20],['66×32 soaking',66,32,22],['72×36 soaking',72,36,22]]
};
const UTIL={
  gfci:{n:'GFCI receptacle',s:'G',c:'#b53a22'}, outlet:{n:'Receptacle 120V',s:'O',c:'#b53a22'}, v240:{n:'240V range circuit',s:'2',c:'#b53a22'},
  sw:{n:'Switch',s:'S',c:'#7a4fa0'}, light:{n:'Light box',s:'L',c:'#7a4fa0'},
  water:{n:'Water supply',s:'W',c:'#1f6fb2'}, drain:{n:'Drain',s:'D',c:'#1f6fb2'}, gas:{n:'Gas stub',s:'Gs',c:'#c47a00'},
  vent:{n:'HVAC register',s:'V',c:'#3d7d5a'}, duct:{n:'Hood duct',s:'Hd',c:'#3d7d5a'}, fan:{n:'Exhaust fan',s:'F',c:'#3d7d5a'},
  window:{n:'Window',s:'Win',c:'#2b5f8a',box:1}, door:{n:'Door / opening',s:'Dr',c:'#2b5f8a',box:1}, soffit:{n:'Soffit / bulkhead',s:'Sf',c:'#666',box:1},
  other:{n:'Other obstacle',s:'X',c:'#444',box:1}
};
const FINISH={white:['Bright white','#f1f0ec'],cream:['Cream','#ece3cf'],greige:['Greige','#b9b0a2'],sage:['Sage','#8d9c85'],navy:['Navy','#2d3f58'],oak:['White oak','#b89468'],walnut:['Walnut','#6a4a34'],cherry:['Cherry','#7b3f2a'],black:['Matte black','#2b2c2e']};
const COUNTER={quartz:['White quartz','#efede9'],marble:['Calacatta-look','#f2f0eb'],granite:['Black granite','#262626'],soapstone:['Soapstone','#4a5052'],butcher:['Butcher block','#b5834e'],concrete:['Concrete','#9c9b97']};
const FLOOR={oak:['Oak plank','#b9925f'],walnut:['Walnut plank','#6e4d35'],tile:['Light tile','#d9d6cf'],slate:['Slate tile','#4b5053']};
const WALLC={warm:['Warm white','#ece6db'],white:['Bright white','#f5f5f2'],greige:['Greige','#cfc6b6'],sage:['Pale sage','#c9d1c0'],charcoal:['Charcoal','#4b4f52']};
const HW={brass:['Brass','#b58b3d'],nickel:['Nickel','#b9bdc1'],black:['Black','#1c1c1c']};

const DEF_SETTINGS={
  counterH:36, toe:4.5, ctThick:1.5, ctDepth:25.5, wallBottom:54, backsplashH:18,
  finish:'white', counter:'quartz', floor:'oak', wallc:'warm', hw:'brass', light:'day',
  islandFinish:'', doorStyle:'shaker', construction:'frameless', line:'semi', edge:'eased', waterfall:false, splash:'18', sink:'undermount', glassUppers:false,
  renderStyle:'photo', budget:0, brand:'#1f4e79', logo:'', email:'', license:'',
  markup:30, tax:7.5, deposit:50, company:'', phone:'', terms:'Price valid 30 days. Appliances supplied by owner unless listed. Electrical and plumbing relocations are priced per the site survey; concealed conditions found during demolition are billed as a change order.',
  over:{},
  price:{base:[150,9],drawer:[235,12],sink:[140,8],corner:[260,8],wall:[95,6.5],tall:[380,15],filler:[38,0],panel:[115,0],island:[0,0],
    ct:{quartz:85,marble:120,granite:70,soapstone:105,butcher:55,concrete:95},vanity:[150,8],edge:{eased:0,bevel:8,bullnose:14,ogee:22,mitered:45},waterfall:650,glassDoor:85,installFix:250,sinkCut:250,cookCut:150,splash:32,pull:9,hinge:7,slide:42,toe:6,crown:15,rail:9,
    installCab:95,installCt:14,installApp:175,plumbing:650,elec:225,gas:750,demo:1800,permit:450,
    app:{RG:0,DW:0,REF:0,HOOD:0,MWD:0,WC:0,TUB:0,TP:0,SHW:0,MIR:0,CUST:0}}
};

/* ---------- project state ---------- */
let S=null;
let SEL=null;
const UI={tab:'design',wall:0,elevWall:0,showTri:true,showClr:true,showUpper:true,snap:0.125,est:'bom',view:null,cam:'orbit'};
const HIST={stack:[],i:-1};
let G_=null, C_=null;
function invalidate(){G_=null;C_=null}

function mkItem(t,o={}){
  const c=CAT[t];
  const it={id:uid(),t,wall:0,x:0,w:c.w,d:c.d,h:c.h,z:0,...o};
  if(t==='W'&&o.z==null)it.z=(S?S.settings.wallBottom:54);
  if(t==='HOOD'&&o.z==null)it.z=(S?S.settings.counterH:36)+30;
  if(t==='MIR'&&o.z==null)it.z=40;
  if(t==='RG'&&!it.fuel)it.fuel='gas';
  if((t==='F'||t==='PNL')&&!it.lay)it.lay='base';
  if(t==='ISL'){it.wall=null;it.seat=it.seat??12;it.style=it.style||'drawers';it.rot=it.rot||0}
  if(MODELS[t]&&it.model==null)it.model=0;
  if(!it.hinge)it.hinge='L';
  return it;
}
function sampleProject(){
  const set=clone(DEF_SETTINGS);
  const P={id:uid(),name:'Sample — L-shape with island',client:'Sample client',address:'',units:'in',ceiling:96,
    walls:[{len:180,note:'Back wall, window over sink'},{len:150,note:'Right wall'},{len:178.5,note:'Front wall, entry'},{len:150,note:'Left wall'}],
    corners:[{ang:90},{ang:90},{ang:90},{ang:90}],
    utils:[], items:[], settings:set, updatedAt:Date.now(), sample:true};
  const U=(type,wall,x,z,o={})=>P.utils.push({id:uid(),type,wall,x,z,w:0,h:0,trim:0,note:'',...o});
  U('window',0,55.5,42,{w:30,h:40,trim:3});
  U('water',0,70.5,20,{note:'H/C supply'}); U('drain',0,70.5,16);
  U('gas',0,127.5,4,{note:'½" gas stub'}); U('outlet',0,121,8,{note:'Range igniter'});
  U('gfci',0,20,44); U('gfci',0,45,44); U('gfci',0,100,44); U('gfci',0,160,44);
  U('outlet',0,50,12,{note:'DW circuit, in sink base'});
  U('duct',0,127.5,90,{note:'6" hood duct'}); U('light',0,70.5,90);
  U('gfci',1,40,44); U('gfci',1,136,44); U('outlet',1,81,78,{note:'Fridge, dedicated'}); U('water',1,77,10,{note:'Ice maker'});
  U('door',2,120,0,{w:36,h:80,trim:3.5}); U('sw',2,113,48);
  U('vent',3,60,4,{note:'Floor-level register'}); U('outlet',3,75,16);
  const A=(t,wall,x,o={})=>{const it=mkItem(t,{wall,x,...o});P.items.push(it);return it};
  // W1 base run: sink under the window, range near the corner
  A('F',0,0,{w:1.5}); A('DB',0,1.5,{w:27}); A('DW',0,28.5,{w:24}); A('SB',0,52.5,{w:36});
  A('B',0,88.5,{w:24}); A('RG',0,112.5,{w:30,d:27,model:1,fuel:'gas'}); A('F',0,142.5,{w:1.5}); A('BBC',0,144,{w:36,hinge:'R'});
  // W1 uppers
  A('F',0,0,{w:1.5,lay:'wall',z:54,h:30,d:12}); A('W',0,1.5,{w:24,h:30,z:54}); A('W',0,25.5,{w:27,h:30,z:54});
  A('W',0,88.5,{w:24,h:30,z:54}); A('HOOD',0,112.5,{w:30,z:66,model:0}); A('F',0,142.5,{w:1.5,lay:'wall',z:54,h:30,d:12});
  A('W',0,144,{w:24,h:30,z:54}); A('W',0,168,{w:12,h:30,z:54});
  // W2: fridge and pantry
  A('F',1,24,{w:3}); A('DB',1,27,{w:36}); A('REF',1,63,{w:36,d:24,h:84,model:2}); A('T',1,99,{w:24,h:84});
  A('B',1,123,{w:24}); A('F',1,147,{w:3});
  A('F',1,12,{w:3,lay:'wall',z:54,h:30,d:12}); A('W',1,15,{w:12,h:30,z:54}); A('W',1,27,{w:36,h:30,z:54});
  A('W',1,123,{w:24,h:30,z:54}); A('F',1,147,{w:3,lay:'wall',z:54,h:30,d:12});
  P.items.push(mkItem('ISL',{cx:78,cy:-87.5,rot:180,w:66,d:37,seat:12}));
  set.islandFinish='navy';set.budget=58000;set.company='Your Company';set.glassUppers=false;
  P.notes=[{id:uid(),x:24,y:-128,text:'Verify floor is level ±¼" before setting island'},{id:uid(),x:118,y:-60,text:'Pendants: 3 @ 22" o.c. over island'}];
  P.specs={faucet:'Pull-down, single handle',appliances:'Owner-supplied; verify cutouts against spec sheets'};
  const sel=k=>({finish:set.finish,islandFinish:set.islandFinish,counter:set.counter,hw:set.hw,doorStyle:set.doorStyle,edge:set.edge,line:set.line,construction:set.construction,splash:set.splash,waterfall:set.waterfall,...k});
  P.options=[{id:uid(),name:'A · Navy island, white quartz',items:clone(P.items),sel:sel({}),at:Date.now()},
    {id:uid(),name:'B · Walnut island, marble, waterfall',items:clone(P.items),sel:sel({islandFinish:'walnut',counter:'marble',edge:'mitered',waterfall:true,hw:'black'}),at:Date.now()}];
  return P;
}
function blankProject(){
  return {id:uid(),name:'New kitchen',client:'',address:'',units:S?S.units:'in',ceiling:96,
    walls:[{len:144,note:''},{len:120,note:''},{len:144,note:''},{len:120,note:''}],
    corners:[{ang:90},{ang:90},{ang:90},{ang:90}],utils:[],items:[],settings:clone(DEF_SETTINGS),updatedAt:Date.now()};
}
function normalize(p){
  p.settings=Object.assign(clone(DEF_SETTINGS),p.settings||{});
  p.settings.price=Object.assign(clone(DEF_SETTINGS.price),p.settings.price||{});
  p.settings.price.ct=Object.assign(clone(DEF_SETTINGS.price.ct),p.settings.price.ct||{});
  p.settings.price.app=Object.assign(clone(DEF_SETTINGS.price.app),p.settings.price.app||{});
  p.settings.over=p.settings.over||{};
  p.walls=p.walls||[];p.corners=p.corners||[];p.utils=p.utils||[];p.items=p.items||[];
  p.notes=p.notes||[];p.options=p.options||[];p.docs=p.docs||[];p.specs=p.specs||{};
  while(p.corners.length<p.walls.length)p.corners.push({ang:90});
  p.corners.length=p.walls.length;
  return p;
}

/* ---------- geometry ---------- */
function cornerMeasured(i){
  const c=S.corners[i]||{};
  if(c.c>0){const a=c.a>0?c.a:36,b=c.b>0?c.b:36;const v=(a*a+b*b-c.c*c.c)/(2*a*b); if(v>=-1&&v<=1)return Math.acos(v)/D2R}
  return c.ang??90;
}
function cornerHasDiag(i){const c=S.corners[i]||{};if(!(c.c>0))return false;const a=c.a>0?c.a:36,b=c.b>0?c.b:36;return Math.abs((a*a+b*b-c.c*c.c)/(2*a*b))<=1}
function geom(){
  if(G_)return G_;
  const n=S.walls.length; const P=[{x:0,y:0}]; let ang=0;
  for(let i=0;i<n-1;i++){
    const p=P[i], L=+S.walls[i].len||0;
    P.push({x:p.x+Math.cos(ang*D2R)*L,y:p.y+Math.sin(ang*D2R)*L});
    ang-=(180-cornerMeasured(i));
  }
  const walls=P.map((a,i)=>{const b=P[(i+1)%n];const dx=b.x-a.x,dy=b.y-a.y,len=Math.hypot(dx,dy)||1;const d={x:dx/len,y:dy/len};return {i,a,b,len,d,n:{x:d.y,y:-d.x}}});
  const corners=walls.map((w,i)=>{const w2=walls[(i+1)%n];const cr=w.d.x*w2.d.y-w.d.y*w2.d.x,dt=w.d.x*w2.d.x+w.d.y*w2.d.y;return 180+Math.atan2(cr,dt)/D2R});
  let area=0,minx=1e9,miny=1e9,maxx=-1e9,maxy=-1e9;
  P.forEach((p,i)=>{const q=P[(i+1)%n];area+=p.x*q.y-q.x*p.y;minx=Math.min(minx,p.x);maxx=Math.max(maxx,p.x);miny=Math.min(miny,p.y);maxy=Math.max(maxy,p.y)});
  return G_={P,walls,corners,area:Math.abs(area)/2,bbox:{minx,miny,maxx,maxy,cx:(minx+maxx)/2,cy:(miny+maxy)/2,w:maxx-minx,h:maxy-miny}};
}
const startCorner=i=>(i-1+S.walls.length)%S.walls.length;
function frame(it){
  const G=geom();
  if(it.wall==null){
    const r=(it.rot||0)*D2R,c=Math.cos(r),s=Math.sin(r);const U={x:c,y:s},V={x:s,y:-c};
    return {o:{x:it.cx-U.x*it.w/2-V.x*it.d/2,y:it.cy-U.y*it.w/2-V.y*it.d/2},U,V};
  }
  const w=G.walls[it.wall]; if(!w)return null;
  return {o:{x:w.a.x+w.d.x*it.x,y:w.a.y+w.d.y*it.x},U:w.d,V:w.n};
}
function rectPoly(f,w,d,d0=0){
  const P=(u,v)=>({x:f.o.x+f.U.x*u+f.V.x*v,y:f.o.y+f.U.y*u+f.V.y*v});
  return [P(0,d0),P(w,d0),P(w,d),P(0,d)];
}
function itemPoly(it){const f=frame(it);return f?rectPoly(f,it.w,it.d):null}
function ctPoly(it){
  const f=frame(it); if(!f)return null;
  if(it.wall==null)return rectPoly(f,it.w,it.d);
  const c=CAT[it.t]; const d=(c.ct||it.t==='RG'||(it.t==='F'&&it.lay==='base'))?Math.max(it.d,S.settings.ctDepth):it.d;
  return rectPoly(f,it.w,d);
}
const isBaseLevel=it=>it.z<S.settings.counterH-2;
const isUpper=it=>it.z>=S.settings.counterH-0.01&&it.wall!=null;
function hasCT(it){const c=CAT[it.t];return !!(c.ct||(it.t==='F'&&it.lay==='base'))}
function proj(P,ax){let a=1e9,b=-1e9;for(const p of P){const v=p.x*ax.x+p.y*ax.y;a=Math.min(a,v);b=Math.max(b,v)}return [a,b]}
function polyOverlap(A,B,tol=0.06){
  for(const P of [A,B])for(let i=0;i<P.length;i++){
    const p=P[i],q=P[(i+1)%P.length];let ax={x:-(q.y-p.y),y:q.x-p.x};const L=Math.hypot(ax.x,ax.y)||1;ax={x:ax.x/L,y:ax.y/L};
    const [a0,a1]=proj(A,ax),[b0,b1]=proj(B,ax);if(a1-tol<=b0||b1-tol<=a0)return false;
  }
  return true;
}
function segPt(p,a,b){const dx=b.x-a.x,dy=b.y-a.y,L2=dx*dx+dy*dy;let t=L2?((p.x-a.x)*dx+(p.y-a.y)*dy)/L2:0;t=clamp(t,0,1);const q={x:a.x+dx*t,y:a.y+dy*t};return {d:Math.hypot(p.x-q.x,p.y-q.y),q}}
function polyDist(A,B){
  if(polyOverlap(A,B,0.001))return {d:0};
  let best={d:1e9};
  for(const [P,Q,sw] of [[A,B,0],[B,A,1]])for(const p of P)for(let i=0;i<Q.length;i++){const r=segPt(p,Q[i],Q[(i+1)%Q.length]);if(r.d<best.d)best=sw?{d:r.d,p:r.q,q:p}:{d:r.d,p,q:r.q}}
  return best;
}
function rayHit(p,d,a,b){const ex=b.x-a.x,ey=b.y-a.y;const den=d.x*ey-d.y*ex;if(Math.abs(den)<1e-9)return null;const t=((a.x-p.x)*ey-(a.y-p.y)*ex)/den;const s2=((a.x-p.x)*d.y-(a.y-p.y)*d.x)/den;return t>0.01&&s2>=-1e-6&&s2<=1+1e-6?t:null}
function inPoly(p,P){let c=false;for(let i=0,j=P.length-1;i<P.length;j=i++){const a=P[i],b=P[j];if(((a.y>p.y)!==(b.y>p.y))&&(p.x<(b.x-a.x)*(p.y-a.y)/(b.y-a.y)+a.x))c=!c}return c}
function toWall(w,p){const rx=p.x-w.a.x,ry=p.y-w.a.y;return {u:rx*w.d.x+ry*w.d.y,v:rx*w.n.x+ry*w.n.y}}
/* intervals along wall j occupied by any item touching the wall within a height range */
function occupied(j,z0,z1,exclude){
  const G=geom(),w=G.walls[j];const out=[];
  if(!w)return out;
  for(const it of S.items){
    if(it===exclude)continue;
    if(it.z>=z1-0.01||it.z+it.h<=z0+0.01)continue;
    const P=itemPoly(it);if(!P)continue;
    let u0=1e9,u1=-1e9,v0=1e9;
    for(const p of P){const t=toWall(w,p);u0=Math.min(u0,t.u);u1=Math.max(u1,t.u);v0=Math.min(v0,t.v)}
    if(v0>2||u1<=0.01||u0>=w.len-0.01)continue;
    out.push({u0:Math.max(0,u0),u1:Math.min(w.len,u1),it,own:it.wall===j});
  }
  return out.sort((a,b)=>a.u0-b.u0);
}
function gaps(j,z0,z1,exclude){
  const w=geom().walls[j];if(!w)return [];
  const occ=occupied(j,z0,z1,exclude);const out=[];let cur=0;
  for(const o of occ){if(o.u0-cur>0.0625)out.push({a:cur,b:o.u0});cur=Math.max(cur,o.u1)}
  if(w.len-cur>0.0625)out.push({a:cur,b:w.len});
  return out;
}
/* out-of-square: front-line loss at a wall end for a cabinet of depth d */
function frontLoss(angle,d){return d/Math.tan(angle*D2R)}

/* ---------- fronts layout (shared by elevations, 3D, cut list) ---------- */
function splitIsland(it){
  const run=it.w-2-1.5; if(run<9)return {units:[],fill:0,run};
  const n=Math.max(1,Math.ceil(run/36));let u=Math.floor(run/n/3)*3;u=clamp(u,9,36);
  const units=Array(n).fill(u);return {units,fill:r2(run-u*n),run};
}
function frontsFor(t,w,h,o={}){
  const g=1/16, st=S.settings, toe=st.toe;
  const R=[]; const add=(x,y,ww,hh,k,hinge)=>R.push({x:x+g,y:y+g,w:ww-2*g,h:hh-2*g,k,hinge});
  const doors=(x,y,ww,hh,single)=>{if(ww>=24&&!single){add(x,y,ww/2,hh,'door','L');add(x+ww/2,y,ww/2,hh,'door','R')}else add(x,y,ww,hh,'door',o.hinge||'L')};
  const top=h;
  switch(t){
    case 'B': add(0,top-6,w,6,'drawer');doors(0,toe,w,top-6-toe);break;
    case 'DB':{const hb=top-toe;add(0,top-6,w,6,'drawer');add(0,top-16.5,w,10.5,'drawer');add(0,toe,w,hb-16.5,'drawer');break}
    case 'SB': add(0,top-6,w,6,'false');doors(0,toe,w,top-6-toe);break;
    case 'BBC':{const ew=Math.max(9,w-24);const x=o.hinge==='R'?0:w-ew;add(x,top-6,ew,6,'drawer');add(x,toe,ew,top-6-toe,'door',o.hinge==='R'?'L':'R');add(o.hinge==='R'?ew:0,toe,w-ew,top-toe,'blind');break}
    case 'WP': add(0,toe,w,top-toe,'drawer');break;
    case 'VAN': if(w>=48){const bank=Math.min(18,w/4);const side=(w-bank)/2;add(0,top-6,side,6,'false');doors(0,toe,side,top-6-toe);add(side,top-8,bank,8,'drawer');add(side,top-8-((top-toe-8)/2),bank,(top-toe-8)/2,'drawer');add(side,toe,bank,(top-toe-8)/2,'drawer');add(side+bank,top-6,side,6,'false');doors(side+bank,toe,side,top-6-toe)}else{add(0,top-6,w,6,'false');doors(0,toe,w,top-6-toe)}break;
    case 'VDB':{const hb=top-toe;add(0,top-7,w,7,'drawer');add(0,top-17,w,10,'drawer');add(0,toe,w,hb-17,'drawer');break}
    case 'LT':{const lo=Math.round((h-toe)*0.45);add(0,toe,w,lo,'door',o.hinge||'L');add(0,toe+lo,w,h-toe-lo,'door',o.hinge||'L');break}
    case 'W': doors(0,0,w,h);break;
    case 'T':{const lo=Math.round((h-toe)*0.55);doors(0,toe,w,lo);doors(0,toe+lo,w,h-toe-lo);break}
    case 'OV':{add(0,toe,w,10,'drawer');add(0,toe+10,w,30,'oven');add(0,toe+40,w,h-toe-40,'door');break}
  }
  return R;
}
function fronts(it){
  if(it.t==='ISL'){
    const s=splitIsland(it);const out=[];let x=1.75+(s.fill>0?0:0);
    for(const u of s.units){frontsFor(it.style==='doors'?'B':'DB',u,S.settings.counterH-S.settings.ctThick).forEach(f=>out.push({...f,x:f.x+x,unit:u}));x+=u}
    return out;
  }
  return frontsFor(it.t,it.w,it.h,it);
}

/* ---------- checks engine ---------- */
