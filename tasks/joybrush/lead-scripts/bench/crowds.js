// node crowds.js <dir> <tag> : per stroke (in order) busy p50/p95, latency p50/p95, frames; window vs atlas; plus atlas events
const fs=require('fs');const [, , dir, tag]=process.argv;
function load(f){const out=[];if(!fs.existsSync(f))return out;for(const l of fs.readFileSync(f,'utf8').split('\n')){const i=l.indexOf('{"legacy"');if(i<0)continue;try{out.push(JSON.parse(l.slice(i)))}catch(e){}}return out;}
for(const kind of ['flat','tilt']){console.log('== '+kind+'  stroke: busy p50/p95  latency p50/p95  frames   (window | atlas)');
 const w=load(`${dir}/${tag}${kind}_falsetoday.log`),a=load(`${dir}/${tag}${kind}_truetoday.log`);
 const s=j=>j?`${j.busyMs.p50.toFixed(0)}/${j.busyMs.p95.toFixed(0)}  ${(j.latencyMs||{}).p50?.toFixed(0)}/${(j.latencyMs||{}).p95?.toFixed(0)}  fr ${j.frames}`:'-';
 for(let i=0;i<Math.max(w.length,a.length);i++)console.log(String(i+1).padStart(2),((w[i]||a[i]).brush).padEnd(20),s(w[i]).padEnd(30),'|',s(a[i]));}
