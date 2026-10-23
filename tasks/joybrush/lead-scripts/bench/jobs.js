// node jobs.js <dir> <tag> : per stroke busy p50/p95/max, gap p50/p95, latency p50/p95, frames, top phases
const fs=require('fs');const [, , dir, tag]=process.argv;
for(const kind of ['flat','tilt']){const f=`${dir}/${tag}${kind}_truetoday.log`;if(!fs.existsSync(f))continue;console.log('== '+tag+kind);
 for(const l of fs.readFileSync(f,'utf8').split('\n')){const i=l.indexOf('{"legacy"');if(i<0)continue;const j=JSON.parse(l.slice(i));
  const p=(x)=>x?`${x.p50.toFixed(0)}/${x.p95.toFixed(0)}`:'-';
  console.log(j.brush.replace('media:wet:','').padEnd(10),'busy',p(j.busyMs).padEnd(9),'max',j.busyMs.max.toFixed(0).padEnd(5),'gap',p(j.gapMs).padEnd(9),'lat',p(j.latencyMs).padEnd(9),'fr',j.frames, 'first',(j.firstFrameMs||0).toFixed(0));}}
