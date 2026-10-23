// node ph.js <log> <brushSubstr> : phase means per frame + counters for that brush's runs
const fs=require('fs');const lines=fs.readFileSync(process.argv[2],'utf8').split('\n');
for(const l of lines){const i=l.indexOf('{"legacy"');if(i<0)continue;const j=JSON.parse(l.slice(i));
 if(!j.brush.includes(process.argv[3]))continue;
 const ph=Object.entries(j.phasesMs||{}).map(([k,v])=>k+'='+v.mean.toFixed(1)+'/'+v.p50.toFixed(1)).join(' ');
 console.log(j.brush,j.case,'fr',j.frames,'busy',j.busyMs.p50,'media',j.mediaMs.mean.toFixed(1),'sim',j.simMs&&j.simMs.mean.toFixed(1),'flush',j.flushMs&&j.flushMs.mean.toFixed(1),'loads',j.windowLoads,'\n   ',ph);}
