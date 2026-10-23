// node phx.js <log> <brushSubstr> : per phase mean / p95 / max
const fs=require('fs');for(const l of fs.readFileSync(process.argv[2],'utf8').split('\n')){const i=l.indexOf('{"legacy"');if(i<0)continue;const j=JSON.parse(l.slice(i));
 if(!j.brush.includes(process.argv[3]))continue;console.log(j.brush,'busy p50/p95/max',j.busyMs.p50,j.busyMs.p95,j.busyMs.max,'fr',j.frames);
 for(const [k,v] of Object.entries(j.phasesMs||{}).sort((a,b)=>b[1].max-a[1].max).slice(0,9))console.log('   ',k.padEnd(22),v.mean.toFixed(0),v.p95.toFixed(0),v.max.toFixed(0));}
