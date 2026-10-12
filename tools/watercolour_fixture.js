#!/usr/bin/env node
'use strict';
// W1 reference trace: execute the READ-ONLY POC's actual maths, not a JS port of Kotlin.
// Canvas drawing is stubbed; begin/append/stamp and response/RNG run unchanged, except
// a logging insertion immediately before stamp's bounds update. No browser process needed.
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const crypto = require('crypto');

const root = path.resolve(__dirname, '..');
const option = (name, fallback) => {
  const i = process.argv.indexOf(name);
  return i < 0 ? fallback : process.argv[i + 1];
};
const poc = path.resolve(option('--poc', 'C:/Users/JoyRaptor/Downloads/Watercolor-POC'));
const output = path.resolve(option('--output', path.join(root, 'joybrush/core/src/jvmTest/resources/watercolour/poc-golden.json')));
const source = fs.readFileSync(path.join(poc, 'index.html'), 'utf8');
const approved = JSON.parse(fs.readFileSync(path.join(poc, 'approved-watercolor-settings.json'), 'utf8'));
const controls = Object.fromEntries(Object.entries(approved.controls).map(([key, item]) => [key, item.value]));
const settings = { ...controls };
for (const key of ['spacing', 'taper', 'tiltMinimum', 'scatter', 'opacity', 'texture']) settings[key] /= 100;
Object.assign(settings, {
  pressureCurve: approved.pressureBezier, tiltCurve: approved.tiltBezier,
  scatterDynamics: approved.scatterInputs, spacingDynamics: approved.spacingInputs,
  compensate: approved.spacingCompensation, invert: approved.backdropInverted,
  blend: approved.glazing, color: approved.color,
});

const functionLine = name => {
  const line = source.split(/\r?\n/).find(line => line.startsWith(`function ${name}(`));
  if (!line) throw new Error(`Reference function ${name} missing; inspect POC before updating fixture`);
  return line;
};
const sampleLine = source.split(/\r?\n/).find(line => line.startsWith('const samplePaths='));
if (!sampleLine) throw new Error('Reference samplePaths missing');
const context = vm.createContext({ Math, Float32Array, Map, WeakMap, settings });
vm.runInContext(`
const clamp=(v,a=0,b=1)=>Math.max(a,Math.min(b,v));
const responseTables=new Map(),responseArrays=new WeakMap();
const R=.65,W=1400,H=900;
const noop=()=>{};
const layer={getContext:()=>({save:noop,restore:noop,scale:noop,translate:noop,rotate:noop,drawImage:noop,clearRect:noop})};
const dryLayers=[layer,layer,layer];
const tint=()=>[],textureMask=()=>({}),dryMasks=()=>[],schedule=noop;
let active=null;
${['bez','response','rng','tiltResponse','diameter','dabWindow','modulation','dabSpacing'].map(functionLine).join('\n')}
${functionLine('stamp').replace('const extent=d*.72;', `
  (a.exported??=[]).push([idx,x,y,d,rotation,alpha,
    band===0?1-fraction:0,band===0?fraction:1-fraction,band===1?fraction:0,
    band===0?1-Math.pow(1-alpha,1-fraction):0,
    1-Math.pow(1-alpha,band===0?fraction:1-fraction),
    band===1?1-Math.pow(1-alpha,fraction):0]);
  const extent=d*.72;`)}
${functionLine('begin')}
${functionLine('append')}
${sampleLine}
function trace(points,seed) {
  begin(points[0],settings,seed);
  for(const point of points.slice(1)) append(point);
  return {seed,points,dabs:active.exported,backdropOffset:active.backdropOffset};
}
globalThis.traced=samplePaths.map(path=>trace(path.points.map(p=>({...p,
  p:clamp(p.p*${controls.pressure}/65),t:clamp(p.t+${controls.tilt}-25,0,60)})),path.seed));
// Trace the phone's actual float channels too. Kotlin PenSample stores radians/Float; use
// the same round-trip here before calling the source functions, so those tests isolate maths
// from unavoidable input quantization rather than granting a larger comparison tolerance.
globalThis.penTraced=traced.map(path=>{
  const samples=path.points.map((p,i)=>({x:Math.fround(p.x),y:Math.fround(p.y),
    pressure:Math.fround(p.p),tilt:Math.fround(p.t*Math.PI/180),timeMs:i*8}));
  const points=samples.map(p=>({x:p.x,y:p.y,p:p.pressure,t:p.tilt*180/Math.PI}));
  return {...trace(points,path.seed),samples};
});
`, context, { filename: 'READ_ONLY_POC_TRACE', timeout: 5000 });

const normalized = {};
for (const [key, {value, unit}] of Object.entries(approved.controls)) {
  if (key === 'window') normalized[key] = value;
  else if (key === 'pressure') normalized.previewPressure = value / 100;
  else if (key === 'tilt') normalized.previewTilt = value;
  else normalized[key] = unit.startsWith('%') ? value / 100 : value;
}
Object.assign(normalized, {
  dabImages: Array.from({length:25}, (_,i)=>`watercolour/poc25/dab_${String(i).padStart(2,'0')}.png`),
  backdrop: 'watercolour/poc25/backdrop.png',
  pressureBezier: approved.pressureBezier, tiltBezier: approved.tiltBezier,
  scatterInputs: approved.scatterInputs, spacingInputs: approved.spacingInputs,
  spacingCompensation: approved.spacingCompensation, backdropInverted: approved.backdropInverted,
  poolingFadeMilliseconds: approved.poolingFadeMilliseconds,
  renderScale: approved.renderScale, worldPixels: approved.worldPixels,
  seedLock: approved.seedLock, sampleSeeds: approved.sampleSeeds, approvedTint: approved.color,
});
const result = {
  format: 'joybrush.watercolour.poc-golden.v1',
  sourceSha256: crypto.createHash('sha256').update(source).digest('hex'),
  columns: ['sampleIndex','x','y','diameter','angle','alpha','weight0','weight1','weight2','band0','band1','band2'],
  settings: normalized,
  paths: JSON.parse(JSON.stringify(context.traced)),
  penPaths: JSON.parse(JSON.stringify(context.penTraced)),
};
for (const p of result.paths) {
  if (!p.dabs.length || p.dabs.some(d => d.some(v => !Number.isFinite(v)))) throw new Error('Invalid POC trace');
  if (p.dabs.some(d => d[0]<0 || d[0]>=25 || Math.abs(d[6]+d[7]+d[8]-1)>1e-12)) throw new Error('Invalid index/bands');
}
fs.mkdirSync(path.dirname(output), {recursive:true});
fs.writeFileSync(output, JSON.stringify(result));
const presetPath = path.resolve(option('--preset', path.join(root, 'joybrush/brushes/watercolour/brush.json')));
const preset = {
  format:'joybrush.brush', version:10, id:'joybrush.watercolour', name:'Watercolour line', engine:'watercolour',
  size:{base:normalized.size}, opacity:{base:normalized.strokeOpacity}, flow:{base:normalized.opacity},
  spacing:normalized.spacing, accumulate:'buildup', blend:approved.glazing, smoothing:0,
  watercolour:normalized, license:'Owner-provided AI-generated reference assets', author:'JoyRaptor',
};
fs.mkdirSync(path.dirname(presetPath), {recursive:true});
fs.writeFileSync(presetPath, JSON.stringify(preset,null,2)+'\n');
console.log(JSON.stringify({sourceSha256:result.sourceSha256,counts:result.paths.map(p=>p.dabs.length),output,preset:presetPath}));
