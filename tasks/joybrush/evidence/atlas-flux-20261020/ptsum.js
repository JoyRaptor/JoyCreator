const d=JSON.parse(require('fs').readFileSync(process.argv[2],'utf8')); const pt=d.passTime||{};
for (const n of Object.keys(pt.new||{})) { const w=pt.new[n]||{}, a=(pt.atlas||{})[n]||{}; console.log(n.padEnd(10),'window',String(w.ms).padStart(8),'n',w.n,'atlas',String(a.ms).padStart(8),'n',a.n,((a.ms||0)/Math.max(w.ms||1,0.1)).toFixed(2)+'x'); }
for (const [sc,v] of Object.entries(d.timing||{})) console.log('  '+sc.padEnd(44),'sim w',v.new.simMedian,'a',v.atlas.simMedian,'| look w',v.new.lookMedian,'a',v.atlas.lookMedian);
console.log(d.renderer||'');
