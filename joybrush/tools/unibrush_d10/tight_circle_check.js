// D10 production parity regression. Do not run during a shared build/low RAM.
// Actual production vertex/fragment + MRT + commit, every RGBA8 pixel, no JS coverage oracle.
// --prepare-only reads/validates source/cases WITHOUT loading Playwright or launching a browser.
const fs = require('fs');
const path = require('path');
const os = require('os');
const crypto = require('crypto');
const options = {repo:path.resolve(__dirname,'../../..'),candidate:null};
for (let i=2;i<process.argv.length;i++) {
  const flag=process.argv[i];
  if (flag==='--prepare-only') options.prepareOnly=true;
  else if (flag==='--diagnose') options.diagnose=true;
  else if (['--repo','--candidate'].includes(flag) && process.argv[i+1]) options[flag.slice(2)]=process.argv[++i];
  else throw new Error('Usage: node tight_circle_check.js [--repo DIR] [--candidate FILE] [--prepare-only] [--diagnose]');
}
options.candidate ||= path.join(options.repo,'joybrush/shaders/jb_dab.vert');
const shaderDir=path.join(options.repo,'joybrush/shaders');
const cache=new Map();
const readUtf8=file=>fs.readFileSync(file,'utf8').replace(/\r\n/g,'\n');
function source(name, stack=[]) {
  if (stack.includes(name)) throw new Error('Shader include cycle: '+[...stack,name].join(' -> '));
  if (cache.has(name)) return cache.get(name);
  const file=path.resolve(shaderDir,name);
  if (!file.startsWith(path.resolve(shaderDir)+path.sep)) throw new Error('Include escapes shader directory');
  const text=readUtf8(file).replace(/^\s*#include\s+"([^"]+)"[^\r\n]*$/gm,
    (_,child)=>source(path.posix.normalize(path.posix.join(path.posix.dirname(name),child)),[...stack,name]));
  cache.set(name,text); return text;
}
function once(text, needle, replacement) {
  if (text.split(needle).length!==2) throw new Error('Expected one candidate mutation site: '+needle);
  return text.replace(needle,replacement);
}
function playwright() {
  const bundled=path.join(os.homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright-core');
  const tries=['playwright-core', ...((process.env.NODE_PATH||'').split(path.delimiter).filter(Boolean).map(p=>path.join(p,'playwright-core'))),bundled];
  const failures=[];
  for (const p of tries) try { const resolved=require.resolve(p,{paths:[options.repo,__dirname]}); return {resolved,chromium:require(resolved).chromium}; }
  catch(e) { failures.push(p+': '+e.message.split('\n')[0]); }
  throw Object.assign(new Error('Playwright unavailable; no package installation attempted'),{dependencyFallbacks:failures});
}
const circle=[-1,-1,1,-1,1,1,-1,1];
function makeCase(name, changes={}) {
  const c={name,size:96,radius:256,angle:.37,aspect:0,anchor:0,taper:0,hardness:0,minPx:1,profile:4,
    quad:circle,meta:[1,1,0,5],scale:1,world:[0,0],probe:[.72,0],fraction:[.25,.75],fade:.4,fadeAngle:.7,
    sample:null,dual:0,live:1,eligible:true,background:'checker',blend:0,colors:true,...changes};
  c.eligible=c.eligible && c.radius>=256 && c.radius<=2048 && (c.scale===.5||c.scale===1) && c.profile===4 && c.hardness===0 && c.meta[2]===0;
  return c;
}
function cases() {
  const list=[];
  for (const radius of [256,256.5,1000,2048]) for (const scale of [.125,.5,1,2,16,64,256])
    list.push(makeCase(`circle-r${radius}-s${scale}`,{radius,scale}));
  for (const fraction of [[0,0],[.25,.25],[.5,.5],[.75,.75]])
    for (const world of [[255.5,256.5],[-256.5,-.5],[4096.25,-4096.75]])
      list.push(makeCase(`tile-edge-${world}-${fraction}`,{fraction,world}));
  for (const profile of [0,1,2,3,4,5]) for (const hardness of [0,.9,1])
    list.push(makeCase(`profile${profile}-h${hardness}`,{profile,hardness,probe:[.75,0]}));
  for (const scale of [64,256]) list.push(makeCase(`deep-AA-floor-s${scale}`,
    {scale,radius:2048,profile:1,hardness:1,probe:[1.000025,0],fraction:[0,0],colors:false,count:1}));
  for (const dual of [1,2,3]) list.push(makeCase(`dual${dual}`,{dual,probe:[.72,.1]}));
  for (const background of ['clear','opaque','checker']) for (const blend of [0,1,2])
    list.push(makeCase(`commit-${background}-blend${blend}`,{background,blend}));
  list.push(makeCase('minPx-equal-diameter',{minPx:512}));
  list.push(makeCase('below-radius-cutoff',{radius:255.5,eligible:false}));
  list.push(makeCase('legacy-no-quad',{quad:[0,0,0,0,0,0,0,0],meta:[0,0,0,0],eligible:false}));
  list.push(makeCase('disabled-attribute-default',{quad:[0,0,0,0,0,0,0,0],meta:[0,0,0,1],live:0,eligible:false}));
  list.push(makeCase('aspect-razor',{aspect:.95,probe:[0,.4],eligible:false}));
  list.push(makeCase('anchor-left',{anchor:.9,angle:0,probe:[-1.4,0],eligible:false}));
  list.push(makeCase('taper',{taper:.8,probe:[.2,-.2],eligible:false}));
  list.push(makeCase('expanded-minPx',{minPx:1536,probe:[1.2,0],eligible:false,fade:0}));
  list.push(makeCase('rotated-square',{meta:[0,1,0,4],angle:Math.PI/4,profile:1,hardness:1,probe:[1.2,0],eligible:false,fade:0}));
  list.push(makeCase('triangle',{quad:[-1,-1,1,-1,0,1,0,1],meta:[0,.4,0,3],angle:.8,profile:1,hardness:1,eligible:false}));
  list.push(makeCase('translated-circle',{quad:[-1,-1,.4,-1,.4,.4,-1,.4],meta:[1,.7,0,5],angle:.785398,
    profile:1,hardness:1,probe:[0,-1.07],eligible:false,fade:0}));
  list.push(makeCase('native1x1',{sample:[1,1,1,1],meta:[0,1,0,0],profile:0,angle:0,probe:[1.3,0],eligible:false,fade:0}));
  list.push(makeCase('native1x64',{sample:[1,1,64,1],meta:[0,1,0,0],profile:0,angle:1.1,probe:[0,0],eligible:false,fade:0}));
  list.push(makeCase('profile-morph',{meta:[1,1,.6,5],profileB:5}));
  for(const radius of [256,256.5,1000,2048])for(const scale of [.5,1])for(const angle of [0,Math.PI/4])
    for(const phase of [0,.25,.5,.75])list.push(makeCase(`bounded-phase-r${radius}-s${scale}-a${angle}-p${phase}`,
      {radius,scale,angle,fraction:[phase,phase],probe:[.72,.1]}));
  for(const scale of [.4999,.5,.5001,.9999,1,1.0001])list.push(makeCase(`scale-boundary-${scale}`,{scale,radius:256.5}));
  // Restrict to the public nominal diameter range. Curves/jitter may exceed it;
  // those larger resolved dabs must retain the exact historical vertex path.
  for(const radius of [255.999,2048.001,4096,1000000]) for(const scale of [.5,1])
    list.push(makeCase(`radius-boundary-r${radius}-s${scale}`,{radius,scale}));
  // Repeated low-flow deposits can amplify RGBA8 rounding differences. Exercise
  // overdraw in addition to the three-dab transport/geometry probes above.
  for(const radius of [256,1000]) for(const scale of [.5,1]) for(const stepDoc of [0,3.5])
    list.push(makeCase(`soft-overdraw-r${radius}-s${scale}-step${stepDoc}`,{radius,scale,stepDoc,count:32,flow:.08,cap:1,fade:0,colors:false}));
  for(const scale of [.5,1])list.push(makeCase(`soft-overdraw-64-s${scale}`,{radius:1000,scale,stepDoc:0,count:64,flow:.08,cap:1,fade:0,colors:false}));
  return list;
}
async function main() {
  const candidate=readUtf8(options.candidate);
  const unbounded=readUtf8(path.join(__dirname,'diagnostics/jb_dab_unbounded.vert'));
  if (!candidate.includes('bool tightCircle =') || !candidate.includes('a_dab.z * 1e-4')) throw new Error('Expected reviewed tight-circle candidate');
  const match=candidate.match(/    bool tightCircle = [\s\S]*?;\n/);
  if (!match) throw new Error('Cannot find candidate predicate for guarded control');
  const shaders={reference:readUtf8(path.join(__dirname,'fixtures/jb_dab_reference.vert')),candidate,stamp:source('jb_dab.frag'),smudge:source('jb_smudge_dab.frag'),
    tile:source('jb_tile.vert'),commit:source('jb_commit.frag'),
    wrongPredicate:once(candidate,match[0],'    bool tightCircle = true;\n'),
    tooSmall:once(candidate,'ext = a_dab.z + 2.0 / s + a_dab.z * 1e-4;','ext = a_dab.z * 0.60;'),
    unbounded,
    noAaFloor:once(unbounded,'ext = a_dab.z + 2.0 / s + a_dab.z * 1e-4;','ext = a_dab.z + 2.0 / s;'),
    contact:source('jb_contact.glsl')};
  shaders.inspector=once(once(candidate,'void main() {','flat out float d10Flag;\nvoid main() {'),
    '    vec2 off = a_corner * ext;','    d10Flag = tightCircle ? 1.0 : 0.0;\n    vec2 off = a_corner * ext;');
  const data=cases();
  if(crypto.createHash('sha256').update(shaders.reference).digest('hex')!=='1d8f8eca82bd79e3919cab821dc17d847c8e536f56cfa40d40fc6087ffc0c228') throw new Error('Frozen D9 reference shader changed');
  const sourceSha256=Object.fromEntries(['reference','candidate','stamp','commit'].map(k=>[k,
    crypto.createHash('sha256').update(shaders[k]).digest('hex')]));
  if (options.prepareOnly) { console.log(JSON.stringify({status:'PREPARED_NOT_RUN',cases:data.length,eligible:data.filter(c=>c.eligible).length,
    shaders:Object.keys(shaders),sourceSha256,candidate:options.candidate,repo:options.repo,gpuLaunched:false})); return; }
  const runtime=playwright();
  const browser=await runtime.chromium.launch({executablePath:process.env.CHROME||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    headless:true,args:['--use-angle=swiftshader','--enable-unsafe-swiftshader','--ignore-gpu-blocklist']});
  try {
    const page=await browser.newPage();
    const result=await page.evaluate(({shaders,data,diagnose})=>{
      const N=96, canvas=document.createElement('canvas'); canvas.width=N;canvas.height=N;
      const gl=canvas.getContext('webgl2',{antialias:false}); if (!gl) throw new Error('WebGL2 unavailable');
      function program(vs,frag,label,feedback=false) {
        const p=gl.createProgram(),parts=[];
        for (const [type,text] of [[gl.VERTEX_SHADER,vs],[gl.FRAGMENT_SHADER,frag]]) {
          const s=gl.createShader(type);gl.shaderSource(s,text);gl.compileShader(s);
          if (!gl.getShaderParameter(s,gl.COMPILE_STATUS)) throw new Error(label+': '+gl.getShaderInfoLog(s));
          gl.attachShader(p,s);parts.push(s);
        }
        if (feedback) gl.transformFeedbackVaryings(p,['d10Flag'],gl.INTERLEAVED_ATTRIBS);
        gl.linkProgram(p);if(!gl.getProgramParameter(p,gl.LINK_STATUS)) throw new Error(label+': '+gl.getProgramInfoLog(p));
        for (const s of parts) {gl.detachShader(p,s);gl.deleteShader(s);} return p;
      }
      const programs=Object.fromEntries(['reference','candidate','wrongPredicate','tooSmall','noAaFloor','unbounded'].map(k=>[k,program(shaders[k],shaders.stamp,k)]));
      for (const k of ['reference','candidate']) gl.deleteProgram(program(shaders[k],shaders.smudge,k+' actual smudge'));
      const inspect=program(shaders.inspector,'#version 300 es\nprecision highp float; out vec4 color;void main(){color=vec4(0);}', 'predicate transform feedback',true);
      const commit=program(shaders.tile,shaders.commit,'actual production commit');
      gl.disable(gl.DITHER);gl.pixelStorei(gl.UNPACK_ALIGNMENT,1);
      const loc=(p,n)=>gl.getUniformLocation(p,n);
      function texture(unit,w,h,internal,format,bytes,filter=gl.NEAREST,wrap=gl.CLAMP_TO_EDGE,type=gl.UNSIGNED_BYTE) {
        gl.activeTexture(gl.TEXTURE0+unit);const t=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,t);
        for(const [a,b] of [[gl.TEXTURE_MIN_FILTER,filter],[gl.TEXTURE_MAG_FILTER,filter],[gl.TEXTURE_WRAP_S,wrap],[gl.TEXTURE_WRAP_T,wrap]]) gl.texParameteri(gl.TEXTURE_2D,a,b);
        gl.texImage2D(gl.TEXTURE_2D,0,internal,w,h,0,format,type,bytes);return t;
      }
      const white=texture(0,1,1,gl.RGBA8,gl.RGBA,new Uint8Array([255,255,255,255]));
      for(const unit of [1,2]) {gl.activeTexture(gl.TEXTURE0+unit);gl.bindTexture(gl.TEXTURE_2D,white);}
      texture(3,3,3,gl.R8,gl.RED,new Uint8Array([0,0,0,0,255,0,0,0,0]),gl.LINEAR);
      texture(4,4,4,gl.R8,gl.RED,Uint8Array.from({length:16},(_,i)=>[0,64,192,255][i%4]),gl.LINEAR,gl.REPEAT);
      const backgrounds={};
      for(const name of ['clear','opaque','checker']) {
        const px=new Uint8Array(N*N*4);
        for(let y=0;y<N;y++)for(let x=0;x<N;x++) {
          const a=name==='clear'?0:name==='opaque'?255:((x>>3)+(y>>3))%2?96:192;
          px.set([Math.round(a*.4),Math.round(a*.2),Math.round(a*.6),a],(y*N+x)*4);
        }
        backgrounds[name]=texture(5,N,N,gl.RGBA8,gl.RGBA,px);
      }
      const cap=texture(6,N,N,gl.RGBA8,gl.RGBA,null),moment=texture(7,N,N,gl.RGBA8,gl.RGBA,null);
      const output=texture(8,N,N,gl.RGBA8,gl.RGBA,null);
      function framebuffer(textures) {
        const f=gl.createFramebuffer();gl.bindFramebuffer(gl.FRAMEBUFFER,f);
        textures.forEach((t,i)=>gl.framebufferTexture2D(gl.FRAMEBUFFER,gl.COLOR_ATTACHMENT0+i,gl.TEXTURE_2D,t,0));
        gl.drawBuffers(textures.map((_,i)=>gl.COLOR_ATTACHMENT0+i));
        if(gl.checkFramebufferStatus(gl.FRAMEBUFFER)!==gl.FRAMEBUFFER_COMPLETE) throw new Error('Incomplete framebuffer');return f;
      }
      const fbo=framebuffer([cap,moment]),outFbo=framebuffer([output]);
      const vao=gl.createVertexArray();gl.bindVertexArray(vao);
      const corners=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,corners);
      gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-1,-1,1,-1,-1,1,1,1]),gl.STATIC_DRAW);
      gl.enableVertexAttribArray(0);gl.vertexAttribPointer(0,2,gl.FLOAT,false,0,0);
      const instances=gl.createBuffer();
      const tileVao=gl.createVertexArray();gl.bindVertexArray(tileVao);
      const tileCorners=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,tileCorners);
      gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([0,0,1,0,0,1,1,1]),gl.STATIC_DRAW);
      gl.enableVertexAttribArray(0);gl.vertexAttribPointer(0,2,gl.FLOAT,false,0,0);
      const lut=Float32Array.from({length:256},(_,i)=>i<196?1:Math.max(0,(255-i)/59));
      const rects=new Float32Array(64);rects.set([1/3,1/3,2/3,2/3]);
      function uniforms(p,c) {
        gl.useProgram(p);
        const origin=[c.world[0]+c.probe[0]*c.radius-N/(2*c.scale)+c.fraction[0],c.world[1]+c.probe[1]*c.radius-N/(2*c.scale)+c.fraction[1]];
        gl.uniform2f(loc(p,'u_tileOrigin'),...origin);
        for(const [name,value] of [['u_tileSize',N],['u_scale',c.scale],['u_aspect',c.aspect],['u_corner',2],['u_taper',c.taper],
          ['u_hardness',c.hardness],['u_minPx',c.minPx],['u_tipGrainPitchPx',0],['u_paperGrainPitchPx',0],['u_paperInfluence',0],['u_dualInvPeriod',.9]]) gl.uniform1f(loc(p,name),value);
        for(const [name,value] of [['u_tipGrain',0],['u_paperSurface',1],['u_paperFluid',2],['u_sampleMasks',3],['u_dualMask',4],
          ['u_sampleEnabled',c.sample?1:0],['u_dualMode',c.dual],['u_tipProfile',c.profile],['u_tipProfileB',c.profileB||0]]) gl.uniform1i(loc(p,name),value);
        gl.uniform4fv(loc(p,'u_tipProfileLut[0]'),lut);gl.uniform4fv(loc(p,'u_tipProfileLutB[0]'),lut);
        gl.uniform4fv(loc(p,'u_sampleRects[0]'),rects);gl.uniform3f(loc(p,'u_stampBaseColor'),.2,.4,.8);
      }
      function dabs(c) {
        return Array.from({length:c.count||3},(_,i)=>({prefix:[c.world[0]+i*(c.stepDoc??3.5),c.world[1]-i*(c.stepDoc===undefined?2.25:c.stepDoc),c.radius,c.angle,
          c.flow??[.6,.25,.8][i%3],c.cap??[.25,.9,.55][i%3],1,0,c.aspect,c.hardness,0,0,c.anchor,0,0,0,c.live],
          fade:[c.fade,c.fadeAngle],quad:[...c.quad,...c.meta],color:c.colors?[[.9,.1,.2,1],[.2,.8,.3,1],[.1,.3,.9,1]][i%3]:[0,0,0,0],
          sample:c.sample||[0,0,0,0]}));
      }
      function upload(group) {
        gl.bindVertexArray(vao);gl.bindBuffer(gl.ARRAY_BUFFER,instances);
        gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([...group.flatMap(d=>d.prefix),...group.flatMap(d=>d.fade),...group.flatMap(d=>d.quad),
          ...group.flatMap(d=>d.color),...group.flatMap(d=>d.sample)]),gl.STREAM_DRAW);
        for(const [a,n,off] of [[1,4,0],[2,2,16],[4,2,24],[5,4,32],[6,4,48],[7,1,64]]) {
          gl.enableVertexAttribArray(a);gl.vertexAttribPointer(a,n,gl.FLOAT,false,68,off);gl.vertexAttribDivisor(a,1);
        }
        const n=group.length;
        for(const [a,size,stride,off] of [[8,2,8,68*n],[9,4,48,76*n],[10,4,48,76*n+16],[11,4,48,76*n+32],[12,4,16,124*n],[13,4,16,140*n]]) {
          gl.enableVertexAttribArray(a);gl.vertexAttribPointer(a,size,gl.FLOAT,false,stride,off);gl.vertexAttribDivisor(a,1);
        }
        gl.disableVertexAttribArray(3);gl.vertexAttrib4f(3,0,0,0,0);
      }
      function read(attachment=0) {
        gl.readBuffer(gl.COLOR_ATTACHMENT0+attachment);const px=new Uint8Array(N*N*4);
        gl.readPixels(0,0,N,N,gl.RGBA,gl.UNSIGNED_BYTE,px);
        const e=gl.getError();if(e!==gl.NO_ERROR) throw new Error('GL error '+e);return px;
      }
      function render(p,c,individual=false) {
        gl.bindFramebuffer(gl.FRAMEBUFFER,fbo);gl.drawBuffers([gl.COLOR_ATTACHMENT0,gl.COLOR_ATTACHMENT1]);gl.viewport(0,0,N,N);
        gl.disable(gl.SCISSOR_TEST);gl.clearColor(0,0,0,0);gl.clear(gl.COLOR_BUFFER_BIT);
        uniforms(p,c);gl.enable(gl.BLEND);gl.blendFuncSeparate(gl.ONE,gl.ONE_MINUS_SRC_ALPHA,gl.ONE,gl.ONE_MINUS_SRC_ALPHA);
        const list=dabs(c);for(const group of individual?list.map(d=>[d]):[list]) {upload(group);gl.drawArraysInstanced(gl.TRIANGLE_STRIP,0,4,group.length);}
        const result=[read(0),read(1)];
        gl.bindFramebuffer(gl.FRAMEBUFFER,outFbo);gl.disable(gl.BLEND);gl.useProgram(commit);gl.bindVertexArray(tileVao);
        gl.uniform2f(loc(commit,'u_tileOrigin'),0,0);gl.uniform1f(loc(commit,'u_tileSize'),N);
        gl.uniformMatrix3fv(loc(commit,'u_docToClip'),false,new Float32Array([2/N,0,0,0,2/N,0,-1,-1,1]));
        for(const [name,value] of [['u_layer',5],['u_stroke',6],['u_colorMoments',7],['u_coloredStroke',c.colors?1:0],['u_rgbaStroke',0],['u_smudge',0],['u_erase',0],['u_blend',c.blend]]) gl.uniform1i(loc(commit,name),value);
        gl.uniform3f(loc(commit,'u_color'),.2,.4,.8);gl.uniform1f(loc(commit,'u_strokeScale'),.7);gl.uniform1f(loc(commit,'u_layerOpacity'),1);
        gl.activeTexture(gl.TEXTURE5);gl.bindTexture(gl.TEXTURE_2D,backgrounds[c.background]);
        gl.drawArrays(gl.TRIANGLE_STRIP,0,4);result.push(read());return result;
      }
      const tf=gl.createTransformFeedback(),tfBuffer=gl.createBuffer();
      function predicate(c) {
        uniforms(inspect,c);upload(dabs(c));gl.bindTransformFeedback(gl.TRANSFORM_FEEDBACK,tf);
        const count=c.count||3;
        gl.bindBuffer(gl.TRANSFORM_FEEDBACK_BUFFER,tfBuffer);gl.bufferData(gl.TRANSFORM_FEEDBACK_BUFFER,count*4*4,gl.STREAM_READ);
        gl.bindBufferBase(gl.TRANSFORM_FEEDBACK_BUFFER,0,tfBuffer);gl.enable(gl.RASTERIZER_DISCARD);
        gl.beginTransformFeedback(gl.POINTS);gl.drawArraysInstanced(gl.POINTS,0,4,count);gl.endTransformFeedback();gl.disable(gl.RASTERIZER_DISCARD);
        const flags=new Float32Array(count*4);gl.getBufferSubData(gl.TRANSFORM_FEEDBACK_BUFFER,0,flags);
        gl.bindBufferBase(gl.TRANSFORM_FEEDBACK_BUFFER,0,null);gl.bindTransformFeedback(gl.TRANSFORM_FEEDBACK,null);
        const e=gl.getError();if(e!==gl.NO_ERROR) throw new Error('Predicate feedback GL error '+e);
        return {pass:Array.from(flags).every(f=>f===(c.eligible?1:0)),flags:Array.from(flags)};
      }
      function diff(a,b) {
        let max=0,changed=0,squares=0,count=0;
        a.forEach((px,k)=>px.forEach((v,i)=>{const d=Math.abs(v-b[k][i]);max=Math.max(max,d);if(d)changed++;squares+=d*d;count++;}));
        return {maxByte:max,changedBytes:changed,rmsByte:Math.sqrt(squares/count),bytes:count};
      }
      const controls=Object.fromEntries(['wrongPredicate','tooSmall','noAaFloor','unbounded'].map(k=>[k,{detected:0,maxByte:0,witnesses:[]}]));
      const rows=[];let failed=0,nonempty=0,eligible=0;
      for(const c of data) {
        const flags=predicate(c),reference=render(programs.reference,c),candidate=render(programs.candidate,c),delta=diff(reference,candidate);
        const allowed=c.eligible?1:0,transport=diff(candidate,render(programs.candidate,c,true));
        const alphaPixels=reference[0].reduce((n,v,i)=>n+(i%4===0&&v>0?1:0),0);if(alphaPixels)nonempty++;
        const pass=flags.pass&&alphaPixels>0&&delta.maxByte<=allowed&&transport.changedBytes===0;
        if(!pass)failed++;if(c.eligible)eligible++;
        rows.push({name:c.name,eligible:c.eligible,predicate:flags,pass,allowedByteError:allowed,referenceCoveredPixels:alphaPixels,...delta,transport});
        for(const name of Object.keys(controls)) {
          const d=diff(reference,render(programs[name],c));const out=controls[name];out.maxByte=Math.max(out.maxByte,d.maxByte);
          if(d.maxByte>2){out.detected++;out.witnesses.push({name:c.name,maxByte:d.maxByte,changedBytes:d.changedBytes});}
        }
      }
      function precisionDiagnostics() {
        if(!gl.getExtension('EXT_color_buffer_float')) throw new Error('--diagnose requires EXT_color_buffer_float');
        const coordinates=texture(9,N,N,gl.RGBA32F,gl.RGBA,null,gl.NEAREST,gl.CLAMP_TO_EDGE,gl.FLOAT);
        const coverage=texture(10,N,N,gl.RGBA32F,gl.RGBA,null,gl.NEAREST,gl.CLAMP_TO_EDGE,gl.FLOAT);
        const target=framebuffer([coordinates,coverage]);
        const fragment=`#version 300 es
precision highp float;
${shaders.contact}
layout(location=0) out vec4 d10Offsets;
layout(location=1) out vec4 d10Coverage;
void main(){
  float s=u_scale>0.0?u_scale:1.0;
  vec2 direct=gl_FragCoord.xy/s+(u_tileOrigin-v_dabCentre);
  float d=jb_contactCoverage();
  d10Offsets=vec4(v_offset,direct);
  d10Coverage=vec4(d,v_cap*d,0.0,1.0);
}`;
        const ps=Object.fromEntries(['reference','unbounded','candidate'].map(k=>[k,program(shaders[k],fragment,k+' precision diagnostic')]));
        function floatRead(attachment) {
          gl.readBuffer(gl.COLOR_ATTACHMENT0+attachment);const px=new Float32Array(N*N*4);
          gl.readPixels(0,0,N,N,gl.RGBA,gl.FLOAT,px);
          const e=gl.getError();if(e!==gl.NO_ERROR)throw new Error('Diagnostic float read GL error '+e);return px;
        }
        function inputs(c) {
          const s=Math.fround(c.scale),r=Math.fround(c.radius);
          const origin=[Math.fround(c.world[0]+c.probe[0]*c.radius-N/(2*c.scale)+c.fraction[0]),
            Math.fround(c.world[1]+c.probe[1]*c.radius-N/(2*c.scale)+c.fraction[1])];
          return {s,r,origin,center:c.world.map(Math.fround)};
        }
        function cpu(c,x,y,i) {
          const ox=(x+.5)/i.s+i.origin[0]-i.center[0],oy=(y+.5)/i.s+i.origin[1]-i.center[1];
          const d=Math.hypot(ox,oy)/i.r;
          const aa=Math.max(Math.abs(Math.hypot(ox+1/i.s,oy)/i.r-d)+Math.abs(Math.hypot(ox,oy+1/i.s)/i.r-d),1e-4);
          const h=Math.fround(c.hardness),inner=Math.min(Math.max(0,Math.min(1,h)),1-aa),outer=1+.5*aa;
          const clamp=v=>Math.max(0,Math.min(1,v));
          const smooth=(a,b,v)=>{const t=clamp((v-a)/(b-a));return t*t*(3-2*t);};
          let cov;
          if(c.profile===1)cov=1-smooth(1-.5*aa,outer,d);
          else if(c.profile===4){const t=clamp((d-inner)/(outer-inner)),tail=Math.exp(-4.5);cov=clamp((Math.exp(-4.5*t*t)-tail)/(1-tail));}
          else throw new Error('Diagnostic CPU oracle covers only the measured Gaussian/hard unit circles');
          const angle=Math.fround(c.angle),a=Math.fround(c.fadeAngle),qx=Math.cos(angle)*ox+Math.sin(angle)*oy,qy=-Math.sin(angle)*ox+Math.cos(angle)*oy;
          const across=clamp(.5+.5*(qx*Math.cos(a)+qy*Math.sin(a))/i.r);
          return {ox,oy,d,aa,coverage:cov*(1-Math.fround(c.fade)*across)*Math.fround(.6)};
        }
        const selected=data.filter(c=>['circle-r256.5-s0.125','deep-AA-floor-s64','deep-AA-floor-s256'].includes(c.name));
        return selected.map(original=>{
          const c={...original,count:1},i=inputs(c),variants={};
          for(const [name,p]of Object.entries(ps)) {
            gl.bindFramebuffer(gl.FRAMEBUFFER,target);gl.drawBuffers([gl.COLOR_ATTACHMENT0,gl.COLOR_ATTACHMENT1]);
            gl.viewport(0,0,N,N);gl.disable(gl.BLEND);gl.disable(gl.SCISSOR_TEST);gl.clearColor(0,0,0,0);gl.clear(gl.COLOR_BUFFER_BIT);
            uniforms(p,c);upload(dabs(c));gl.drawArraysInstanced(gl.TRIANGLE_STRIP,0,4,1);
            const offsets=floatRead(0),values=floatRead(1);let maxInterpolation=0,maxDirect=0,maxCoverage=0,missingSupport=0,probes=0,worst=null;
            for(let y=0;y<N;y+=2)for(let x=0;x<N;x+=2) {
              const at=(y*N+x)*4,truth=cpu(c,x,y,i);
              if(values[at+3]===0){if(truth.coverage>1e-6)missingSupport++;continue;}
              probes++;
              const interpolation=Math.max(Math.abs(offsets[at]-truth.ox),Math.abs(offsets[at+1]-truth.oy));
              const direct=Math.max(Math.abs(offsets[at+2]-truth.ox),Math.abs(offsets[at+3]-truth.oy));
              const delta=Math.abs(values[at]-truth.coverage);
              maxInterpolation=Math.max(maxInterpolation,interpolation);maxDirect=Math.max(maxDirect,direct);
              if(delta>maxCoverage){maxCoverage=delta;worst={x,y,actualCoverage:values[at],cpuCoverage:truth.coverage,
                interpolatedOffset:Array.from(offsets.slice(at,at+2)),directGpuOffset:Array.from(offsets.slice(at+2,at+4)),cpuOffset:[truth.ox,truth.oy],d:truth.d,aa:truth.aa};}
            }
            variants[name]={probes,maxInterpolationErrorDocPx:maxInterpolation,maxDirectErrorDocPx:maxDirect,
              maxContactCoverageErrorVsCpu:maxCoverage,missingNonzeroCpuSupport:missingSupport,worst};
          }
          return {name:c.name,scope:'one first dab; actual production contact in RGBA32F; independent double unit-circle truth at even/even forward derivatives; diagnostics do not replace strict production parity',variants};
        });
      }
      const diagnostics=diagnose?precisionDiagnostics():null;
      return {pass:failed===0&&nonempty>0&&eligible>0&&Object.values(controls).every(c=>c.detected>0),diagnostics,
        backend:'PC WebGL2 SwiftShader; no Note9 FPS claim',cases:rows,summary:{cases:rows.length,eligible,failed,nonempty},controls,
        targetFormats:'RGBA8 cap-density + RGBA8 RGB moments; RGBA8 committed output. Does not certify phone R16F/RGBA16F format path.',
        proof:'actual full/candidate production stamp + MRT cap/moment + production tile/commit; all pixels; real split VBO; predicate via transform feedback; batch/single exact',
        policy:'eligible max1 RGBA8 level, ineligible exact; every broken control must differ by >2 levels'};
    },{shaders,data,diagnose:!!options.diagnose});
    console.log(JSON.stringify({...result,sourceSha256,playwright:runtime.resolved,repo:options.repo,candidate:options.candidate},null,2));
    if(!result.pass)process.exitCode=1;
  } finally {await browser.close();}
}
main().catch(e=>{console.error(JSON.stringify({pass:false,error:e.message,dependencyFallbacks:e.dependencyFallbacks||[]},null,2));process.exitCode=1;});
