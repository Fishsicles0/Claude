/* ---------- boot ---------- */
(function boot(){
  const h=location.hash.slice(1);if(h&&['measure','design','elevations','view3d','estimate','docs','present','checks'].includes(h))UI.tab=h;
  const cur=LS.get('ps.current');const p=cur?LS.get('ps.p.'+cur):null;
  S=null;setProject(p?normalize(p):sampleProject());
  if(window.THREE){V3.orb.t=new THREE.Vector3();setCam('orbit')}
  if(S.sample)setSave('Sample project');
  initCloud();
  if(!LS.get('ps.welcomed'))showWelcome();
})();
