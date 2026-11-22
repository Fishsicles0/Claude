/* ---------- Supplier: Cabinotch custom-size face-frame boxes ---------- */
const SUPPLIER={cabinotch:['Cabinotch'],other:['Other (standard sizes)']};
const FF_SPECIES=['Soft maple','Hard maple','Red oak','White oak','Cherry','Walnut','Hickory','Alder','Paint grade'];
Object.assign(DEF_SETTINGS,{supplier:'cabinotch',ffSpecies:'Soft maple',ffScribe:1.5});
const isCabinotch=()=>S&&S.settings.supplier==='cabinotch';
AFTER.supplier=()=>{if(isCabinotch())S.settings.construction='framed'};
{const baseNorm=normalize;normalize=function(p){p=baseNorm(p);if(p.settings.supplier==='cabinotch'&&p.settings.construction==='frameless')p.settings.construction='framed';return p}}

/* exposed cabinet sides → finished ends to order */
function finishedEnds(it){
  if(it.wall==null)return {L:true,R:true};
  const hides=o=>!CAT[o.it.t].app||['DW','RG','MWD'].includes(o.it.t);
  const occ=occupied(it.wall,it.z+1,it.z+it.h-1,it);const len=geom().walls[it.wall].len;
  const L=it.x>0.3&&!occ.some(o=>Math.abs(o.u1-it.x)<0.3&&hides(o));
  const R=it.x+it.w<len-0.3&&!occ.some(o=>Math.abs(o.u0-(it.x+it.w))<0.3&&hides(o));
  return {L,R};
}
const boxKind=it=>({B:'Base',DB:'Drawer base',SB:'Sink base',BBC:'Blind corner base',WP:'Waste pull-out base',W:it.d>13?'Wall (deep, over fridge)':'Wall',T:'Tall / pantry',OV:'Tall oven',VAN:'Vanity sink base',VDB:'Vanity drawer base',LT:'Tall linen'}[it.t]||CAT[it.t].name);
function cabinotchOrder(){
  const st=S.settings,rows=[];
  const add=(it,label,w,h,d,loc,ends,fr)=>{const doors=fr.filter(f=>f.k==='door').length,drw=fr.filter(f=>f.k==='drawer').length;
    const sh=it.t==='W'?(h>=30?2:1):CAT[it.t]?.cls==='tall'?4:(['B','SB','BBC','VAN'].includes(it.t)?1:0);
    rows.push({tag:label,loc,type:boxKind(it),w:r2(w),h:r2(h),d:r2(d),toe:(isBaseLevel(it)||CAT[it.t]?.cls==='tall')?st.toe:0,ff:st.ffSpecies,endL:ends.L,endR:ends.R,doors,drawers:drw,shelves:it.t==='SB'?0:sh,note:it.note||''})};
  let n=0;
  for(const it of [...S.items].sort((a,b)=>(a.wall??99)-(b.wall??99)||a.z-b.z||a.x-b.x)){
    const c=CAT[it.t];if(c.app||c.cls==='filler')continue;
    if(it.t==='ISL'){const s=splitIsland(it);const H=st.counterH-st.ctThick;let k=0;for(const u of s.units){k++;const fake={t:it.style==='doors'?'B':'DB',z:0,h:H,d:24};add(fake,'C'+(++n),u,H,24,'Island',{L:k===1,R:k===s.units.length},frontsFor(fake.t,u,H))}continue}
    add(it,'C'+(++n),it.w,it.h,it.d,'W'+(it.wall+1)+' @ '+fmt(it.x),finishedEnds(it),fronts(it));
  }
  return rows;
}
function orderHTML(){
  const rows=cabinotchOrder(),st=S.settings,cab=isCabinotch();
  const fill=S.items.filter(i=>CAT[i.t].cls==='filler');const ends=rows.reduce((a,r)=>a+(r.endL?1:0)+(r.endR?1:0),0);
  const fr=fronts;let nd=0,ndr=0;rows.forEach(r=>{nd+=r.doors;ndr+=r.drawers});
  return `<div class="stack">
    <div class="card stack"><div class="row" style="justify-content:space-between"><div><h3>${cab?'Order from Cabinotch':'Cabinet box order'}</h3>
      <p class="hint" style="margin:0">${cab?`Every box at its exact size, with a ${esc(st.ffSpecies.toLowerCase())} face frame. Enter these in the Cabinotch webstore, or send the spreadsheet with your quote request.`:'Every cabinet box at its exact size.'} Check sizes on site before ordering.</p></div>
      <div class="row"><button class="btn pri" data-act="orderxls">Save order spreadsheet</button><button class="btn" data-act="ordercopy">Copy for an email</button></div></div>
      <div class="row"><span class="pill p-info">${rows.length} boxes</span><span class="pill p-info">${ends} finished ends</span><span class="pill p-info">${nd} doors · ${ndr} drawers (order separately)</span>${fill.length?`<span class="pill p-info">${fill.length} fillers</span>`:''}</div>
      <div class="tw"><table class="t"><thead><tr><th>#</th><th>Where</th><th>Type</th><th class="n">Width</th><th class="n">Height</th><th class="n">Depth</th><th>Face frame</th><th>Finished end</th><th class="n">Shelves</th><th class="n">Doors / drawers</th></tr></thead><tbody>
      ${rows.map(r=>`<tr><td class="mono">${r.tag}</td><td>${esc(r.loc)}</td><td>${esc(r.type)}</td><td class="n">${fmt(r.w)}</td><td class="n">${fmt(r.h)}</td><td class="n">${fmt(r.d)}</td><td>${esc(r.ff)}</td><td>${r.endL&&r.endR?'Both':r.endL?'Left':r.endR?'Right':'—'}</td><td class="n">${r.shelves}</td><td class="n">${r.doors} / ${r.drawers}</td></tr>`).join('')||'<tr><td colspan="10" class="muted">No cabinets yet.</td></tr>'}
      </tbody></table></div>
      ${cab?`<p class="hint">Cabinotch boxes: ½" PureBond plywood sides, back, top and bottom with ¾" adjustable shelves and a hardwood face frame. Doors, drawer fronts and finishing come from you. Confirm current options and pricing with Cabinotch.</p>`:''}
    </div>
    ${fill.length?`<div class="card stack"><h3>Fillers &amp; panels</h3><div class="tw"><table class="t"><thead><tr><th>Where</th><th>Type</th><th class="n">Width</th><th class="n">Height</th></tr></thead><tbody>${fill.map(f=>`<tr><td>W${f.wall+1} @ ${fmt(f.x)}</td><td>${f.t==='PNL'?'End panel':'Filler'} (${f.lay||'base'})</td><td class="n">${fmt(f.w)}</td><td class="n">${fmt(f.h)}</td></tr>`).join('')}</tbody></table></div></div>`:''}
  </div>`;
}
function orderCSV(){const st=S.settings;return csv([['Job',S.name],['Client',S.client||''],['Face frame species',st.ffSpecies],['Toe kick height (in)',st.toe],[],
  ['#','Location','Cabinet type','Width (in)','Height (in)','Depth (in)','Face frame','Finished end left','Finished end right','Adjustable shelves','Doors','Drawers','Notes'],
  ...cabinotchOrder().map(r=>[r.tag,r.loc,r.type,r.w,r.h,r.d,r.ff,r.endL?'Yes':'No',r.endR?'Yes':'No',r.shelves,r.doors,r.drawers,r.note])])}
function orderText(){const st=S.settings;return `Cabinet box order — ${S.name}${S.client?' ('+S.client+')':''}\nFace frame: ${st.ffSpecies} · Toe kick: ${fmt(st.toe)}\n\n`+cabinotchOrder().map(r=>`${r.tag}  ${r.type}  ${fmt(r.w)} W × ${fmt(r.h)} H × ${fmt(r.d)} D${r.endL||r.endR?'  · finished end '+(r.endL&&r.endR?'both':r.endL?'left':'right'):''}${r.shelves?'  · '+r.shelves+' shelf':''}  [${r.loc}]`).join('\n')+'\n'}

/* exact-size filling: Cabinotch builds any width, so fill each gap with equal cabinets and only a scribe filler at walls and corners */
{
  const stockFill=fillGap;
  fillGap=function(j,a,b,z){
    if(!isCabinotch())return stockFill(j,a,b,z);
    const st=S.settings,upper=z>st.counterH,wlen=geom().walls[j].len,sc=st.ffScribe||1.5;
    const q=v=>Math.round(v*64)/64;
    const mkF=(x,w)=>mkItem('F',{wall:j,x:q(x),w:q(w),lay:upper?'wall':'base',z:upper?st.wallBottom:0,h:upper?30:34.5,d:upper?12:24});
    const occ=occupied(j,z,z+(upper?30:34.5));
    const corner=u=>Math.abs(u)<0.1||Math.abs(u-wlen)<0.1||occ.some(o=>!o.own&&(Math.abs(o.u1-u)<0.1||Math.abs(o.u0-u)<0.1));
    let x0=a,x1=b,added=0;
    if(b-a<=6){S.items.push(mkF(a,b-a));return 1}
    if(corner(a)&&x1-x0>12){S.items.push(mkF(x0,sc));x0+=sc;added++}
    if(corner(b)&&x1-x0>12){S.items.push(mkF(x1-sc,sc));x1-=sc;added++}
    const len=x1-x0;if(len<9){S.items.push(mkF(x0,len));return added+1}
    const n=Math.ceil(len/36),tot=Math.floor(len*16+1e-6),base=Math.floor(tot/n),extra=tot-base*n;
    for(let k=0;k<n;k++){const ww=(base+(k<extra?1:0))/16;S.items.push(mkItem(upper?'W':'B',{wall:j,x:q(x0),w:ww,z:upper?st.wallBottom:0,h:upper?30:34.5}));x0+=ww;added++}
    return added;
  };
}
Object.assign(ACT,{
  orderxls:()=>saveFile((isCabinotch()?'cabinotch-order-':'cabinet-order-')+slug()+'.csv',orderCSV(),'text/csv'),
  ordercopy:()=>copyText(orderText(),'Order copied — paste it into an email or the Cabinotch quote request')
});
