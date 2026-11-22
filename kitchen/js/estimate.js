/* ---------- bill of materials ---------- */
function unitPrice(it){
  const st=S.settings,p=st.price,sku=CAT[it.t].sku(it);
  if(st.over[sku]!=null&&st.over[sku]!=='')return +st.over[sku];
  const pk=CAT[it.t].pk; if(!pk)return 0;
  const pr=p[pk]; if(!pr)return 0;
  const k=cabMult(it);
  if(pk==='filler')return r2(pr[0]*(it.lay==='tall'?2:1)*k);
  if(pk==='panel')return r2(pr[0]*(it.lay==='tall'?2.4:1)*k);
  return r2((pr[0]+pr[1]*it.w)*k);
}
function cabMult(it){const st=S.settings;const ds=(it&&it.door&&DOOR[it.door]&&it.door!=='glass')?it.door:st.doorStyle;return (LINE[st.line]||LINE.semi)[1]*(CONSTR[st.construction]||CONSTR.frameless)[1]*({raised:1.08,beaded:1.05,slab:0.97}[ds]||1)}
function glassDoorCount(){let n=0;for(const it of S.items){const g=it.door==='glass'||(S.settings.glassUppers&&it.t==='W'&&it.door!=='solid');if(g)n+=fronts(it).filter(f=>f.k==='door').length}return n}
function frontCounts(){
  let doors=0,drawers=0,hinges=0,talls=0;
  for(const it of S.items){for(const f of fronts(it)){if(f.k==='door'){doors++;hinges+=f.h>40?3:2}else if(f.k==='drawer')drawers++}}
  return {doors,drawers,hinges};
}
function bom(){
  const st=S.settings,p=st.price,G=geom(),L=[];
  const add=(grp,sku,desc,qty,unit,price,key,kind='mat')=>{if(qty<=0&&kind!=='lump')return;L.push({grp,sku,desc,qty:r2(qty),unit,price:r2(price),key,kind})};
  // cabinets grouped by sku
  const cab={};
  for(const it of S.items){
    const c=CAT[it.t]; if(c.app)continue;
    if(it.t==='ISL'){
      const s=splitIsland(it);
      s.units.forEach(u=>{const sku=(it.style==='doors'?'B':'DB')+u;const k='ISL:'+sku;cab[k]=cab[k]||{grp:'Cabinets',sku,desc:'Island '+(it.style==='doors'?'base':'drawer base')+' '+u+'"',qty:0,unit:'ea',price:st.over[sku]!=null?+st.over[sku]:r2((p[it.style==='doors'?'base':'drawer'][0]+p[it.style==='doors'?'base':'drawer'][1]*u)*cabMult(it)),key:'over.'+sku};cab[k].qty++});
      const ep='IEP'+it.d;cab[ep]=cab[ep]||{grp:'Cabinets',sku:ep,desc:'Island end panel '+it.d+'" deep',qty:0,unit:'ea',price:st.over[ep]!=null?+st.over[ep]:p.panel[0]*1.4,key:'over.'+ep};cab[ep].qty+=2;
      const bp='IBP'+it.w;cab[bp]=cab[bp]||{grp:'Cabinets',sku:bp,desc:'Island back panel '+it.w+'" (seating side)',qty:0,unit:'ea',price:st.over[bp]!=null?+st.over[bp]:r2(p.panel[0]+3*it.w),key:'over.'+bp};cab[bp].qty++;
      if(s.fill>0.0625){const fs='BF'+s.fill;cab['ISLF'+fs]=cab['ISLF'+fs]||{grp:'Cabinets',sku:fs,desc:'Island filler',qty:0,unit:'ea',price:p.filler[0],key:'price.filler'};cab['ISLF'+fs].qty++}
      continue;
    }
    const sku=c.sku(it);
    const key=sku+'|'+it.w+'|'+it.h+'|'+it.d;
    cab[key]=cab[key]||{grp:c.cls==='filler'?'Fillers & panels':'Cabinets',sku,desc:c.name+(c.cls==='filler'?' ('+(it.lay||'base')+')':'')+' — '+fmt(it.w)+' W × '+fmt(it.h)+' H × '+fmt(it.d)+' D',qty:0,unit:'ea',price:unitPrice(it),key:'over.'+sku};
    cab[key].qty++;
  }
  const ng=glassDoorCount();if(ng)cab['GLASS']={grp:'Cabinets',sku:'GLS-DR',desc:'Glass door insert upgrade',qty:ng,unit:'ea',price:p.glassDoor,key:'price.glassDoor'};
  Object.values(cab).sort((a,b)=>a.grp.localeCompare(b.grp)||a.sku.localeCompare(b.sku,undefined,{numeric:true})).forEach(c=>L.push({...c,kind:'mat'}));
  // appliances
  for(const it of S.items){const c=CAT[it.t];if(!c.app)continue;const m=MODELS[it.t]?.[it.model];
    add(c.fix?'Plumbing fixtures':'Appliances',c.sku(it),(m?c.name+' — '+m[0]:c.name)+(it.t==='RG'?' · '+(it.fuel==='gas'?'gas':'electric'):'')+(it.note?' — '+it.note:''),1,'ea',p.app[it.t]||0,'price.app.'+it.t);}
  // countertops
  let sq=0,edge=0,sinks=0,cooks=0;
  for(const it of S.items){
    if(it.wall==null){sq+=it.w*it.d/144;edge+=2*(it.w+it.d)/12;continue}
    if(hasCT(it)){sq+=it.w*st.ctDepth/144;edge+=it.w/12}
    if(it.t==='SB')sinks++;
  }
  for(const it of S.items)if(it.wall==null&&false)cooks++;
  const ctm=COUNTER[st.counter][0];
  add('Countertops','CT-'+st.counter.toUpperCase(),ctm+', '+fmt(st.ctThick)+' with eased edge — '+r2(sq*1.1).toFixed(1)+' sq ft incl. 10% waste',sq*1.1,'sq ft',p.ct[st.counter],'price.ct.'+st.counter);
  const nvb=S.items.filter(i=>i.t==='VAN').reduce((a,i)=>a+(i.w>=60?2:1),0);
  add('Countertops','CUT-SINK',(SINK[st.sink]||'Sink')+' cutout & polish',sinks+nvb,'ea',p.sinkCut,'price.sinkCut');
  add('Countertops','EDGE-'+st.edge.toUpperCase(),EDGE[st.edge][0]+' edge profile upgrade',(p.edge[st.edge]||0)>0?edge:0,'lin ft',p.edge[st.edge]||0,'price.edge.'+st.edge);
  const nisl=S.items.filter(i=>i.wall==null).length;
  add('Countertops','WTRFL','Waterfall end panels, mitered',st.waterfall?nisl*2:0,'ea',p.waterfall,'price.waterfall');
  // backsplash: wall run length behind counters
  let bsa=0;for(const it of S.items)if(it.wall!=null&&(hasCT(it)||it.t==='RG')){const ups=S.items.filter(u=>u.wall===it.wall&&isUpper(u)&&u.t!=='HOOD'&&u.t!=='MIR'&&u.x<it.x+it.w&&u.x+u.w>it.x).map(u=>u.z);bsa+=it.w*(splashTop(st,ups)-st.counterH)}
  for(const it of S.items)if(it.t==='SHW')bsa+=(it.w+2*it.d)*(it.h);
  add('Countertops','BSP',SPLASH[st.splash]+' tile backsplash'+(S.items.some(i=>i.t==='SHW')?' + shower walls':'')+', installed',bsa/144,'sq ft',p.splash,'price.splash');
  // hardware
  const fc=frontCounts();
  add('Hardware','PULL',HW[st.hw][0]+' bar pull, 5" c-c',fc.doors+fc.drawers,'ea',p.pull,'price.pull');
  add('Hardware','HNG','Soft-close concealed hinge',fc.hinges,'ea',p.hinge,'price.hinge');
  add('Hardware','SLD','Full-extension soft-close slide (pair)',fc.drawers,'pr',p.slide,'price.slide');
  // trim
  let toe=0,crown=0,rail=0;
  for(const it of S.items){const c=CAT[it.t];if(c.app)continue;
    if(it.wall==null){toe+=2*(it.w-2);continue}
    if(isBaseLevel(it)&&c.cls!=='filler')toe+=it.w;
    if(it.t==='W'||c.cls==='tall'){crown+=it.w}
    if(it.t==='W')rail+=it.w;}
  add('Trim','TK8','Toe kick, finished, 8\' lengths',toe/12,'lin ft',p.toe,'price.toe');
  add('Trim','CRN','Crown molding'+(crown?' (incl. 10% & returns)':''),crown/12*1.1,'lin ft',p.crown,'price.crown');
  add('Trim','LR','Light rail molding',rail/12*1.1,'lin ft',p.rail,'price.rail');
  // labor
  const ncab=S.items.filter(i=>!CAT[i.t].app&&CAT[i.t].cls!=='filler').length+S.items.filter(i=>i.t==='ISL').reduce((s,i)=>s+splitIsland(i).units.length-1,0);
  const napp=S.items.filter(i=>CAT[i.t].app&&!CAT[i.t].fix).length;const nfix=S.items.filter(i=>CAT[i.t].fix&&i.t!=='MIR').length;
  const C=checks();
  add('Labor','INST-CAB','Cabinet installation (level, scribe, fasten)',ncab,'ea',p.installCab,'price.installCab','lab');
  add('Labor','INST-CT','Countertop template & install',sq,'sq ft',p.installCt,'price.installCt','lab');
  add('Labor','INST-APP','Appliance set & hook-up',napp,'ea',p.installApp,'price.installApp','lab');
  add('Labor','INST-FIX','Plumbing fixture set (toilet, tub, shower pan & glass)',nfix,'ea',p.installFix,'price.installFix','lab');
  add('Labor','PLMB','Plumbing relocation (from site survey)',C.reloc.plumbing,'ea',p.plumbing,'price.plumbing','lab');
  add('Labor','ELEC','Electrical add / relocate (from site survey)',C.reloc.elec,'ea',p.elec,'price.elec','lab');
  add('Labor','GAS','Gas line relocation (from site survey)',C.reloc.gas,'ea',p.gas,'price.gas','lab');
  add('Labor','DEMO','Demolition & disposal',p.demo>0?1:0,'lot',p.demo,'price.demo','lab');
  add('Labor','PRMT','Permit & inspections',p.permit>0?1:0,'lot',p.permit,'price.permit','lab');
  const mat=L.filter(l=>l.kind==='mat').reduce((s,l)=>s+l.qty*l.price,0);
  const lab=L.filter(l=>l.kind==='lab').reduce((s,l)=>s+l.qty*l.price,0);
  const sub=mat+lab, mk=sub*st.markup/100, tax=mat*(1+st.markup/100)*st.tax/100, total=sub+mk+tax;
  return {lines:L,mat,lab,sub,mk,tax,total,sq,edge,fc,toe,crown,rail};
}

/* ---------- cut list ---------- */
function cutlist(){
  const st=S.settings,parts=[],frontsL=[],boxes=[];
  const P=(cab,part,qty,w,l,mat,eb)=>parts.push({cab,part,qty,w:r2(w),l:r2(l),mat,eb});
  const box=(label,t,w,h,d)=>{
    if(typeof isCabinotch==='function'&&isCabinotch()){P(label,'Box from Cabinotch (ready to assemble)',1,w,h,'Cabinotch · '+st.ffSpecies+' face frame','');return}
    const tall=CAT[t]?.cls==='tall', wall=t==='W', base=!wall&&!tall;
    const hb=wall?h:h-st.toe, db=d-0.75, iw=w-1.5;
    P(label,'Side',2,db,hb,'¾" ply','F');
    P(label,'Bottom',1,iw,db,'¾" ply','F');
    if(base)P(label,'Stretcher',2,iw,4,'¾" ply','F');else P(label,'Top',1,iw,db,'¾" ply','F');
    P(label,'Back',1,w-1,hb-1,'¼" ply','');
    const sh=wall?(h>=30?2:1):tall?4:(t==='B'||t==='SB'||t==='BBC'?1:0);
    if(sh&&t!=='SB')P(label,'Adj. shelf',sh,iw-0.0625,db-1,'¾" ply','F');
    if(base||tall)P(label,'Toe kick (finished)',1,w,st.toe,'¾" ply','');
    if(st.construction!=='frameless'){P(label,'Face frame stile',2,1.5,hb,'¾" hardwood','');P(label,'Face frame rail',base?2:2,w-3,base?1.5:1.5,'¾" hardwood','');}
  };
  const drawerBox=(label,fr,w)=>{if(fr.k!=='drawer')return;const hh=Math.max(3,Math.round((fr.h-1.5)*2)/2);P(label,'Drawer box '+fmt(w-1.5-1)+' × '+fmt(hh)+' × 21"',1,w-2.5,21,'½" Baltic birch','')};
  for(const it of S.items){
    const c=CAT[it.t]; if(c.app)continue;
    if(c.cls==='filler'){P(c.sku(it),it.t==='PNL'?'Finished end panel':'Filler',1,it.t==='PNL'?it.d:it.w,it.h,it.t==='PNL'?'¾" finished panel':'¾" finished stock','');continue}
    if(it.t==='ISL'){const s=splitIsland(it);s.units.forEach((u,k)=>{const lab='ISL '+(it.style==='doors'?'B':'DB')+u+'-'+(k+1);box(lab,it.style==='doors'?'B':'DB',u,st.counterH-st.ctThick,24);frontsFor(it.style==='doors'?'B':'DB',u,st.counterH-st.ctThick).forEach(f=>{frontsL.push({cab:lab,...f});drawerBox(lab,f,u)})});
      P('ISL','Finished end panel',2,it.d-1,st.counterH-st.ctThick,'¾" finished panel','');P('ISL','Back panel',1,it.w-2,st.counterH-st.ctThick,'¾" finished panel','');continue}
    const label=c.sku(it);box(label,it.t,it.w,it.h,it.d);
    for(const f of fronts(it)){if(f.k==='blind'||f.k==='oven')continue;frontsL.push({cab:label,...f});drawerBox(label,f,it.w)}
  }
  let a34=0,a14=0,eb=0;
  for(const p of parts){const a=p.w*p.l*p.qty;if(p.mat==='¾" ply')a34+=a;else if(p.mat==='¼" ply')a14+=a;if(p.eb)eb+=p.w*p.qty}
  if(st.construction!=='frameless')frontsL.forEach(f=>{if(st.construction==='inset'){f.w=r2(f.w-0.125);f.h=r2(f.h-0.125)}else{f.w=r2(f.w+0.375);f.h=r2(f.h+0.375)}});
  return {parts,fronts:frontsL,sheets34:Math.ceil(a34/(48*96*0.82)),sheets14:Math.ceil(a14/(48*96*0.85)),eb:Math.ceil(eb/12)};
}


/* ---------- design options (compare iterations) ---------- */
const SEL_KEYS=['finish','islandFinish','counter','hw','doorStyle','edge','line','construction','splash','waterfall','glassUppers','sink'];
function currentSel(){const o={};SEL_KEYS.forEach(k=>o[k]=S.settings[k]);return o}
function withOption(opt,fn){
  const keepItems=S.items,keepSet=S.settings;
  try{S.items=opt.items;S.settings={...keepSet,...(opt.sel||{})};invalidate();return fn()}
  finally{S.items=keepItems;S.settings=keepSet;invalidate()}
}
function optionSummary(opt){return withOption(opt,()=>{const B=bom(),C=checks();return {total:B.total,mat:B.mat,lab:B.lab,sq:B.sq,cabs:S.items.filter(i=>!CAT[i.t].app&&CAT[i.t].cls!=='filler').length,err:C.counts.err,warn:C.counts.warn,sel:currentSel()}})}
