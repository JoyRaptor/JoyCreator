const fs=require('fs'); const [,,dir,...labels]=process.argv; const out={};
for (const lab of labels) { out[lab]={}; for (const f of fs.readdirSync(dir).filter(f=>f.startsWith(lab)&&f.endsWith('_today.log'))) {
  for (const line of fs.readFileSync(dir+'/'+f,'utf8').split('\n')) { const i=line.indexOf('{"case":"matrix","idx"'); if(i<0) continue; let j; try{j=JSON.parse(line.slice(i));}catch(e){continue;} if(j.event||j.refused) continue;
    const a=(out[lab][j.name]=out[lab][j.name]||{p50:[],fr:[],work:[],h:new Set()}); a.p50.push(j.p50); a.fr.push(j.frames); if(j.workMs) a.work.push(j.workMs.mean); a.h.add(j.afterHash); } } }
const med=a=>{const s=[...a].sort((x,y)=>x-y);return s.length?s[Math.floor(s.length/2)]:NaN;};
const names=Object.keys(out[labels[0]]);
for (const n of names) console.log(n.padEnd(12), labels.map(l=>{const a=out[l][n]||{p50:[],fr:[],work:[],h:new Set()}; return `${l}: p50 med ${med(a.p50).toFixed(1)} [${a.p50.map(x=>x.toFixed(0)).join(',')}] frames ${med(a.fr)} hashes ${a.h.size}`}).join('   |   '));
