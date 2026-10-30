// Draft only: production GLSL vs actual JVM DualTips.bake/TipMath fixture; no JS tip oracle.
// NODE_PATH/CHROME are optional. JOYBRUSH_ROOT and UNIBRUSH_DUAL_FIXTURE override input paths.
const fs=require('fs'),path=require('path'),os=require('os');
const root=[process.env.JOYBRUSH_ROOT,path.join(__dirname,'..'),'C:/Temp/jb-codex-unibrush/joybrush']
  .filter(Boolean).find(p=>fs.existsSync(path.join(p,'shaders','jb_dab.vert')));
if(!root)throw new Error('Set JOYBRUSH_ROOT to the joybrush directory');
const fixturePath=process.env.UNIBRUSH_DUAL_FIXTURE||path.join(root,'core','build','unibrush-dual-fixture.json');
const shaderDir=path.join(root,'shaders'),tolerance=2/255,controlThreshold=2*tolerance,sources=new Map();
function source(name,stack=[]){
  if(stack.includes(name))throw new Error('Shader include cycle: '+[...stack,name].join(' -> '));
  if(sources.has(name))return sources.get(name);
  const file=path.resolve(shaderDir,name);
  if(!file.startsWith(path.resolve(shaderDir)+path.sep))throw new Error('Shader include escapes its directory');
  const expanded=fs.readFileSync(file,'utf8').replace(/^\s*#include\s+"([^"]+)"[^\r\n]*$/gm,(_,child)=>source(child,[...stack,name]));
  sources.set(name,expanded);return expanded;
}
function replaceOnce(text,pattern,replacement,label){
  const matches=text.match(pattern);
  if(!matches||matches.length!==1)throw new Error(label+': expected exactly one production mutation site');
  return text.replace(pattern,replacement);
}
function fixture(){
  const d=JSON.parse(fs.readFileSync(fixturePath,'utf8'));
  const integer=(n,min,max)=>Number.isInteger(n)&&n>=min&&n<=max;
  const floats=(v,n)=>Array.isArray(v)&&v.length===n&&v.every(Number.isFinite);
  const bytes=(v,n)=>Array.isArray(v)&&v.length===n&&v.every(x=>integer(x,0,255));
  if(!integer(d.atlasWidth,1,4096)||!integer(d.atlasHeight,1,4096)||!bytes(d.atlasBytes,d.atlasWidth*d.atlasHeight))
    throw new Error('Invalid primary R8 atlas');
  if(!Array.isArray(d.rects)||d.rects.length!==2||d.rects.some(r=>!floats(r,4)||r.some(v=>v<0||v>1)||r[0]>=r[2]||r[1]>=r[3]))
    throw new Error('Invalid primary atlas rectangles');
  if(!Array.isArray(d.masks)||d.masks.length<3||d.masks.some(m=>m.width!==256||m.height!==256||
    !bytes(m.bytes,65536)||!Number.isFinite(m.period)||m.period<=0||!integer(m.mode,1,3)))throw new Error('Invalid baked dual masks');
  if(!Array.isArray(d.cases)||d.cases.length<12)throw new Error('Missing actual CPU cases');
  for(const c of d.cases){
    if(typeof c.name!=='string'||!c.name||c.width!==64||c.height!==64||!integer(c.maskIndex,0,d.masks.length-1)||
      !integer(c.mode,1,3)||!integer(c.index,-1,1)||!integer(c.profile,0,5)||!integer(c.imageWidth,1,2048)||!integer(c.imageHeight,1,2048))
      throw new Error('Invalid fixture case header');
    for(const k of ['radius','angle','aspect','taper','anchor','hardness','minPx','fade','fadeAngle','flow','cap','period'])
      if(!Number.isFinite(c[k]))throw new Error(c.name+': nonfinite '+k);
    if(c.radius<=0||c.minPx<=0||c.flow<=0||c.flow>1||c.cap<=0||c.cap>1||c.period!==d.masks[c.maskIndex].period||c.mode!==d.masks[c.maskIndex].mode)
      throw new Error(c.name+': invalid radius/flow/cap/dual identity');
    for(const[k,n]of[['lut',256],['quad',8],['quadMeta',4],['ink',3],['coverage',4096],['baseCoverage',4096]])
      if(!floats(c[k],n))throw new Error(c.name+': invalid '+k);
    if(c.coverage.some((v,i)=>v<0||v>1||v>c.baseCoverage[i]+1e-6)||c.baseCoverage.some(v=>v<0||v>1)||c.quadMeta[1]<=0)
      throw new Error(c.name+': invalid primary/subtractive coverage');
  }
  for(const mode of[1,2,3])for(const kind of['shape','image'])
    if(!d.cases.some(c=>c.mode===mode&&c.secondaryKind===kind&&c.index<0&&c.quadMeta[3]>0)||
      !d.cases.some(c=>c.mode===mode&&c.secondaryKind===kind&&c.index>=0))throw new Error('Missing mode/source/main geometry combination');
  if(!d.cases.some(c=>c.index===0&&c.imageWidth===1&&c.imageHeight===1&&c.quadMeta[3]===0))
    throw new Error('Missing unclipped 1x1 sampled primary for wrap-control visibility');
  return d;
}
function playwright(){
  const bundled=path.join(os.homedir(),'.cache','codex-runtimes','codex-primary-runtime','dependencies','node','node_modules');
  const candidates=['playwright-core',...(process.env.NODE_PATH||'').split(path.delimiter).filter(Boolean).map(p=>path.join(p,'playwright-core')),path.join(bundled,'playwright-core')];
  for(const candidate of [...new Set(candidates)])try{const resolved=require.resolve(candidate);return{chromium:require(resolved).chromium,resolved};}catch{}
  throw new Error('playwright-core unavailable; no package installed');
}
async function main(){
  const data=fixture(),vert=source('jb_dab.vert'),stamp=source('jb_dab.frag');
  const call=/    tipCov = jb_dualTipCoverage\(tipCov, contactOffset, t\);/g;
  const bypass=replaceOnce(stamp,call,'    // Guarded control: bypass the dual operation.','dual call');
  const wrongFlowOrder=replaceOnce(bypass,/    float d = tipCov \* v_flow;/g,
    '    float d = jb_dualTipCoverage(tipCov * v_flow, contactOffset, t);','dual flow order');
  const interpolated=replaceOnce(stamp,/    if \(\(u_sampleEnabled != 0 && v_sample\.x > 0\.5\) \|\| u_dualMode != 0\) \{/g,
    '    if (u_sampleEnabled != 0 && v_sample.x > 0.5) {','pixel-centre reconstruction');
  const shaders={vert,stamp,bypass,wrongFlowOrder,interpolated,smudge:source('jb_smudge_dab.frag')};
  if(process.argv.includes('--validate-only')){
    console.log(JSON.stringify({validated:true,cases:data.cases.length,masks:data.masks.length,fixture:fixturePath,
      guardedMutations:['dual bypass','flow order','pixel-centre reconstruction'],gpuExecuted:false}));return;
  }
  const runtime=playwright(),executablePath=process.env.CHROME||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
  const browser=await runtime.chromium.launch({executablePath,headless:true,
    args:['--use-angle=swiftshader','--enable-unsafe-swiftshader','--ignore-gpu-blocklist']});
  try{
    const page=await browser.newPage();
    const result=await page.evaluate(({data,shaders,tolerance,controlThreshold})=>{
      const canvas=document.createElement('canvas');canvas.width=canvas.height=64;
      const gl=canvas.getContext('webgl2',{antialias:false});if(!gl)throw new Error('WebGL2 unavailable');
      function program(vs,fs,label){
        const pair=[[gl.VERTEX_SHADER,vs],[gl.FRAGMENT_SHADER,fs]].map(([type,text])=>{
          const s=gl.createShader(type);gl.shaderSource(s,text);gl.compileShader(s);
          if(!gl.getShaderParameter(s,gl.COMPILE_STATUS))throw new Error(label+': '+gl.getShaderInfoLog(s));return s;
        });
        const p=gl.createProgram();pair.forEach(s=>gl.attachShader(p,s));gl.linkProgram(p);
        if(!gl.getProgramParameter(p,gl.LINK_STATUS))throw new Error(label+': '+gl.getProgramInfoLog(p));
        pair.forEach(s=>{gl.detachShader(p,s);gl.deleteShader(s);});return p;
      }
      const production=program(shaders.vert,shaders.stamp,'production dual stamp');
      gl.deleteProgram(program(shaders.vert,shaders.smudge,'production shared smudge'));
      const bypass=program(shaders.vert,shaders.bypass,'guarded dual bypass');
      const wrongFlowOrder=program(shaders.vert,shaders.wrongFlowOrder,'guarded wrong flow order');
      const interpolated=program(shaders.vert,shaders.interpolated,'guarded wrong interpolated coordinates');
      const uniformCache=new Map();
      function loc(p,name){if(!uniformCache.has(p))uniformCache.set(p,new Map());const m=uniformCache.get(p);
        if(!m.has(name))m.set(name,gl.getUniformLocation(p,name));return m.get(name);}
      function texture(unit,internal,w,h,format,bytes,filter,repeat=false){
        const t=gl.createTexture();gl.activeTexture(gl.TEXTURE0+unit);gl.bindTexture(gl.TEXTURE_2D,t);
        gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,filter);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,filter);
        gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,repeat?gl.REPEAT:gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,repeat?gl.REPEAT:gl.CLAMP_TO_EDGE);
        gl.texImage2D(gl.TEXTURE_2D,0,internal,w,h,0,format,gl.UNSIGNED_BYTE,bytes);return t;
      }
      gl.pixelStorei(gl.UNPACK_ALIGNMENT,1);
      const white=texture(0,gl.RGBA8,1,1,gl.RGBA,new Uint8Array([255,255,255,255]),gl.NEAREST);
      for(const unit of[1,2]){gl.activeTexture(gl.TEXTURE0+unit);gl.bindTexture(gl.TEXTURE_2D,white);}
      const atlas=texture(3,gl.R8,data.atlasWidth,data.atlasHeight,gl.RED,new Uint8Array(data.atlasBytes),gl.LINEAR);
      const dualTextures=data.masks.map(m=>texture(4,gl.R8,m.width,m.height,gl.RED,new Uint8Array(m.bytes),gl.LINEAR,true));
      const coverage=texture(6,gl.RGBA8,64,64,gl.RGBA,null,gl.NEAREST);
      const moments=texture(7,gl.RGBA8,64,64,gl.RGBA,null,gl.NEAREST);
      const fbo=gl.createFramebuffer();gl.bindFramebuffer(gl.FRAMEBUFFER,fbo);
      gl.framebufferTexture2D(gl.FRAMEBUFFER,gl.COLOR_ATTACHMENT0,gl.TEXTURE_2D,coverage,0);
      gl.framebufferTexture2D(gl.FRAMEBUFFER,gl.COLOR_ATTACHMENT1,gl.TEXTURE_2D,moments,0);
      gl.drawBuffers([gl.COLOR_ATTACHMENT0,gl.COLOR_ATTACHMENT1]);
      if(gl.checkFramebufferStatus(gl.FRAMEBUFFER)!==gl.FRAMEBUFFER_COMPLETE)throw new Error('Incomplete dual MRT framebuffer');
      gl.disable(gl.DITHER);gl.disable(gl.SCISSOR_TEST);
      const vao=gl.createVertexArray();gl.bindVertexArray(vao);
      const quad=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,quad);
      gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-1,-1,1,-1,-1,1,1,1]),gl.STATIC_DRAW);
      gl.enableVertexAttribArray(0);gl.vertexAttribPointer(0,2,gl.FLOAT,false,0,0);
      const vbo=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,vbo);
      for(const[a,n,offset]of[[1,4,0],[2,2,16],[4,2,24],[5,4,32],[6,4,48],[7,1,64]]){
        gl.enableVertexAttribArray(a);gl.vertexAttribPointer(a,n,gl.FLOAT,false,68,offset);gl.vertexAttribDivisor(a,1);
      }
      const rects=new Float32Array(64);data.rects.forEach((r,i)=>rects.set(r,i*4));
      function uniforms(p,c,mutation){
        gl.useProgram(p);gl.uniform2f(loc(p,'u_tileOrigin'),0,0);
        for(const[n,v]of[['u_tileSize',64],['u_scale',1],['u_aspect',c.aspect],['u_corner',2],['u_taper',c.taper],
          ['u_hardness',c.hardness],['u_minPx',c.minPx],['u_tipGrainPitchPx',0],['u_paperGrainPitchPx',0],['u_paperInfluence',0]])
          gl.uniform1f(loc(p,n),v);
        for(const[n,u]of[['u_tipGrain',0],['u_paperSurface',1],['u_paperFluid',2],['u_sampleMasks',3],['u_dualMask',4]])gl.uniform1i(loc(p,n),u);
        gl.uniform1i(loc(p,'u_sampleEnabled'),1);gl.uniform4fv(loc(p,'u_sampleRects[0]'),rects);
        gl.uniform1i(loc(p,'u_tipProfile'),c.profile);gl.uniform1i(loc(p,'u_tipProfileB'),0);
        gl.uniform4fv(loc(p,'u_tipProfileLut[0]'),new Float32Array(c.lut));gl.uniform4fv(loc(p,'u_tipProfileLutB[0]'),new Float32Array(c.lut));
        gl.uniform3f(loc(p,'u_stampBaseColor'),...c.ink);
        gl.uniform1i(loc(p,'u_dualMode'),mutation==='wrongOff'?0:mutation==='wrongMode'?c.mode%3+1:c.mode);
        gl.uniform1f(loc(p,'u_dualInvPeriod'),(mutation==='wrongPeriod'?1.73:1)/c.period);
        gl.activeTexture(gl.TEXTURE3);gl.bindTexture(gl.TEXTURE_2D,atlas);
        gl.activeTexture(gl.TEXTURE4);gl.bindTexture(gl.TEXTURE_2D,dualTextures[c.maskIndex]);
        const wrap=mutation==='wrongClamp'?gl.CLAMP_TO_EDGE:gl.REPEAT;
        gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,wrap);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,wrap);
      }
      function dab(c){return{
        prefix:[32,32,c.radius,c.angle,c.flow,c.cap,0,0,c.aspect,c.hardness,0,0,c.anchor,0,0,0,1],
        fade:[c.fade,c.fadeAngle],quad:[...c.quad,...c.quadMeta],color:[...c.ink,1],
        sample:[c.index+1,c.imageWidth,c.imageHeight,c.minPx],
      };}
      function upload(group,wrong=null){
        gl.bindVertexArray(vao);gl.bindBuffer(gl.ARRAY_BUFFER,vbo);
        gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([...group.flatMap(d=>d.prefix),...group.flatMap(d=>d.fade),
          ...group.flatMap(d=>d.quad),...group.flatMap(d=>d.color),...group.flatMap(d=>d.sample)]),gl.STREAM_DRAW);
        const n=group.length;
        for(const[a,count,stride,offset,fault]of[[8,2,8,68*n,'fade'],[9,4,48,76*n,'quad'],[10,4,48,76*n+16,'quad'],
          [11,4,48,76*n+32,'quad'],[12,4,16,124*n,'rgb'],[13,4,16,140*n,'sample']]){
          gl.enableVertexAttribArray(a);gl.vertexAttribPointer(a,count,gl.FLOAT,false,stride,
            offset-(wrong===fault?(fault==='fade'||fault==='quad'?8:16):0));gl.vertexAttribDivisor(a,1);
        }
      }
      function read(attachment){
        gl.readBuffer(gl.COLOR_ATTACHMENT0+attachment);const bytes=new Uint8Array(64*64*4);
        gl.readPixels(0,0,64,64,gl.RGBA,gl.UNSIGNED_BYTE,bytes);
        const error=gl.getError();if(error!==gl.NO_ERROR)throw new Error('Dual draw GL error '+error);return bytes;
      }
      function draw(c,mutation=null,p=production){
        uniforms(p,c,mutation);gl.viewport(0,0,64,64);gl.disable(gl.BLEND);gl.clearColor(0,0,0,0);gl.clear(gl.COLOR_BUFFER_BIT);
        upload([dab(c)]);gl.drawArraysInstanced(gl.TRIANGLE_STRIP,0,4,1);return[read(0),read(1)];
      }
      function measure(c,pixels){
        let max=0,squares=0,probes=0,failures=0,controlPixels=0,worst=null;
        for(let y=0;y<64;y+=2)for(let x=0;x<64;x+=2){
          const i=y*64+x,d=c.coverage[i]*c.flow,cap=d*c.cap;
          const expected=[[cap,0,0,d],[c.ink[0]*cap,c.ink[1]*cap,c.ink[2]*cap,d]];
          let visibleControl=false;
          for(let a=0;a<2;a++)for(let channel=0;channel<4;channel++){
            const error=Math.abs(pixels[a][i*4+channel]/255-expected[a][channel]);
            if(error>max){max=error;worst={x,y,attachment:a,channel,gpu:pixels[a][i*4+channel]/255,cpu:expected[a][channel]};}
            squares+=error*error;probes++;if(error>=tolerance)failures++;if(error>controlThreshold)visibleControl=true;
          }
          if(visibleControl)controlPixels++;
        }
        return{max,rms:Math.sqrt(squares/probes),probes,failures,controlPixels,worst};
      }
      const controls=Object.fromEntries(['wrongOff','wrongMode','wrongPeriod','wrongClamp','wrongFlowOrder','interpolatedPixels']
        .map(n=>[n,{relevantCases:0,detectedCases:0,max:0,witnesses:[],required:n!=='interpolatedPixels'}]));
      function control(name,c,pixels){const result=measure(c,pixels),out=controls[name];out.relevantCases++;
        out.max=Math.max(out.max,result.max);if(result.controlPixels){out.detectedCases++;out.witnesses.push({name:c.name,pixels:result.controlPixels,max:result.max});}}
      const cases=[];let max=0,squares=0,probes=0,failedCases=0,legacyDisabledDifferingBytes=0;
      const diff=(a,b)=>a.reduce((sum,pixels,k)=>sum+pixels.reduce((n,v,i)=>n+(v!==b[k][i]?1:0),0),0);
      for(const c of data.cases){
        const result=measure(c,draw(c));cases.push({name:c.name,...result});max=Math.max(max,result.max);
        squares+=result.rms*result.rms*result.probes;probes+=result.probes;if(result.failures)failedCases++;
        for(const n of['wrongOff','wrongMode','wrongPeriod'])control(n,c,draw(c,n));
        if(c.index===0&&c.quadMeta[3]===0)control('wrongClamp',c,draw(c,'wrongClamp'));
        if(c.mode!==1&&c.flow<1)control('wrongFlowOrder',c,draw(c,null,wrongFlowOrder));
        if(c.index<0)control('interpolatedPixels',c,draw(c,null,interpolated));
        legacyDisabledDifferingBytes+=diff(draw(c,'wrongOff'),draw(c,'wrongOff',bypass));
      }
      function transport(){
        const c=data.cases.find(c=>c.mode===2&&c.profile===4&&c.secondaryKind==='shape');
        const globals={...c,profile:4,aspect:0,taper:0};
        const dabs=[[24,26,18,.2,.2,-.4,.8,.2,.3],[34,31,15,-.4,.8,.9,.2,.8,.4],[29,38,20,.7,.5,-1.1,.4,.3,.9]].map((v,i)=>{
          const primary=data.cases.find(c=>c.index===[-1,0,1][i]&&c.mode===2&&c.secondaryKind==='shape');
          const d=dab(primary);d.prefix=[v[0],v[1],v[2],v[3],.6,[.45,.8,.6][i],0,0,[-.1,.3,-.5][i],0,0,0,[0,.4,.2][i],0,0,0,1];
          d.fade=[v[4],v[5]];d.quad=[-1,-1,1,-1,1,1,-1,1,.2+i*.3,.8,0,4];d.color=[v[6],v[7],v[8],1];return d;
        });
        function groups(groups,wrong=null){
          uniforms(production,globals,null);gl.viewport(0,0,64,64);gl.clearColor(0,0,0,0);gl.clear(gl.COLOR_BUFFER_BIT);
          gl.enable(gl.BLEND);gl.blendFuncSeparate(gl.ONE,gl.ONE_MINUS_SRC_ALPHA,gl.ONE,gl.ONE_MINUS_SRC_ALPHA);
          for(const group of groups){upload(group,wrong);gl.drawArraysInstanced(gl.TRIANGLE_STRIP,0,4,group.length);}return[read(0),read(1)];
        }
        const batch=groups([dabs]),single=groups(dabs.map(d=>[d]));
        const differingByAttachment=batch.map((pixels,k)=>pixels.reduce((n,v,i)=>n+(v!==single[k][i]?1:0),0));
        const wrongOffsets=Object.fromEntries(['sample','fade','quad','rgb'].map(n=>[n,diff(batch,groups([dabs],n))]));
        const wrongDrawOrderBytes=diff(batch,groups([dabs.slice().reverse()]));
        const coveredPixels=batch[0].reduce((n,v,i)=>n+(i%4===0&&v>0?1:0),0);
        return{pass:differingByAttachment.every(n=>n===0)&&coveredPixels>0&&Object.values(wrongOffsets).every(n=>n>0)&&wrongDrawOrderBytes>0,
          dabs:3,differingByAttachment,wrongOffsetDifferingBytes:wrongOffsets,wrongDrawOrderBytes,coveredPixels,
          layout:'17n prefix + 2n fade + 12n quad + 4n RGB + 4n sample; dual uniform texture4; identical MRT draw order'};
      }
      const transported=transport();
      const pass=failedCases===0&&legacyDisabledDifferingBytes===0&&transported.pass&&
        Object.values(controls).filter(c=>c.required).every(c=>c.detectedCases>0);
      return{pass,compiled:['production stamp','production shared smudge','guarded dual bypass/flow-order/pixel-centre controls'],
        tolerance,controlThreshold,probeConvention:'even/even fragment centres; actual CPU forward derivatives; both MRT attachments RGBA8',
        parity:{cases:cases.length,failedCases,channelProbes:probes,max,rms:Math.sqrt(squares/probes)},cases,controls,
        legacyDisabledDifferingBytes,transport:transported};
    },{data,shaders,tolerance,controlThreshold});
    console.log(JSON.stringify({...result,fixture:fixturePath,playwright:runtime.resolved},null,2));if(!result.pass)process.exitCode=1;
  }finally{await browser.close();}
}
main().catch(error=>{console.error(JSON.stringify({pass:false,error:error.message},null,2));process.exitCode=1;});
