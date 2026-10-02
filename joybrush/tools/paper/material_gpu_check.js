// Real packaged material checks in the production background shader. No second GLSL renderer.
const fs=require('fs'), path=require('path');
const {chromium}=require('playwright-core');
const root=path.resolve(__dirname,'../..'), assets=path.join(root,'assets/paper');
const fixtures=JSON.parse(fs.readFileSync(path.join(__dirname,'out/material-gpu-fixtures.json'),'utf8'));
const input={vertex:fs.readFileSync(path.join(root,'shaders/jb_paper_bg.vert'),'utf8'),
 fragment:fs.readFileSync(path.join(root,'shaders/jb_paper_bg.frag'),'utf8'), fixtures:fixtures.map(f=>({...f,
 lookPng:f.look.file?fs.readFileSync(path.join(assets,f.look.file)).toString('base64'):null,
 surfacePng:f.surface?fs.readFileSync(path.join(assets,f.surface.file)).toString('base64'):null}))};
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROME||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
 args:['--use-angle=swiftshader','--enable-unsafe-swiftshader','--ignore-gpu-blocklist']});
 try {
 const page=await browser.newPage();
 const result=await page.evaluate(async input=>{
  const canvas=document.createElement('canvas'); canvas.width=128; canvas.height=128;
  const gl=canvas.getContext('webgl2'); if(!gl) throw Error('WebGL2 unavailable');
  function program(v,f){
   const mk=(type,source)=>{const s=gl.createShader(type);gl.shaderSource(s,source);gl.compileShader(s);
    if(!gl.getShaderParameter(s,gl.COMPILE_STATUS))throw Error(gl.getShaderInfoLog(s));return s;};
   const p=gl.createProgram();gl.attachShader(p,mk(gl.VERTEX_SHADER,v));gl.attachShader(p,mk(gl.FRAGMENT_SHADER,f));gl.linkProgram(p);
   if(!gl.getProgramParameter(p,gl.LINK_STATUS))throw Error(gl.getProgramInfoLog(p));return p;
  }
  const bg=program(input.vertex,input.fragment);
  const raw=program(input.vertex,'#version 300 es\nprecision highp float;uniform sampler2D tex;out vec4 color;void main(){color=texelFetch(tex,ivec2(0),0);}');
  const vao=gl.createVertexArray();gl.bindVertexArray(vao);const vertices=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,vertices);
  gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([0,0,1,0,0,1,1,1]),gl.STATIC_DRAW);gl.enableVertexAttribArray(0);gl.vertexAttribPointer(0,2,gl.FLOAT,false,0,0);
  gl.activeTexture(gl.TEXTURE2);const target=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,target);
  gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA8,128,128,0,gl.RGBA,gl.UNSIGNED_BYTE,null);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.NEAREST);
  const fbo=gl.createFramebuffer();gl.bindFramebuffer(gl.FRAMEBUFFER,fbo);gl.framebufferTexture2D(gl.FRAMEBUFFER,gl.COLOR_ATTACHMENT0,gl.TEXTURE_2D,target,0);
  if(gl.checkFramebufferStatus(gl.FRAMEBUFFER)!==gl.FRAMEBUFFER_COMPLETE)throw Error('framebuffer incomplete');
  gl.disable(gl.BLEND);gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL,false);gl.pixelStorei(gl.UNPACK_COLORSPACE_CONVERSION_WEBGL,gl.NONE);
  async function texture(base64,unit){
   gl.activeTexture(gl.TEXTURE0+unit);const t=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,t);
   if(base64){const bytes=Uint8Array.from(atob(base64),c=>c.charCodeAt(0));
    const image=await createImageBitmap(new Blob([bytes],{type:'image/png'}),{premultiplyAlpha:'none',colorSpaceConversion:'none'});
    gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA8,gl.RGBA,gl.UNSIGNED_BYTE,image);image.close();
   }else gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA8,1,1,0,gl.RGBA,gl.UNSIGNED_BYTE,new Uint8Array([127,127,128,64]));
   gl.generateMipmap(gl.TEXTURE_2D);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
   gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.REPEAT);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.REPEAT);
   return t;
  }
  const loc=(p,name)=>gl.getUniformLocation(p,name);
  const rgb=hex=>[1,3,5].map(i=>parseInt(hex.slice(i,i+2),16)/255);
  const sheet=document.createElement('canvas');sheet.width=648;sheet.height=input.fixtures.length*156;
  const ctx=sheet.getContext('2d');ctx.fillStyle='#eaeaea';ctx.fillRect(0,0,sheet.width,sheet.height);
  const checks=[];
  for(const [row,f] of input.fixtures.entries()){
   const lt=await texture(f.lookPng,0), st=await texture(f.surfacePng,1);
   let rawError=0;
   if(f.surface){gl.useProgram(raw);gl.uniform1i(loc(raw,'tex'),1);gl.viewport(0,0,16,16);gl.drawArrays(gl.TRIANGLE_STRIP,0,4);
    const pixel=new Uint8Array(4);gl.readPixels(0,0,1,1,gl.RGBA,gl.UNSIGNED_BYTE,pixel);
    rawError=Math.max(...pixel.map((v,i)=>Math.abs(v-f.surfaceFirst[i])));
   }
   function render(zoom,mip,size){
    for(const [unit,t] of [[0,lt],[1,st]]){gl.activeTexture(gl.TEXTURE0+unit);gl.bindTexture(gl.TEXTURE_2D,t);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,mip?gl.LINEAR_MIPMAP_LINEAR:gl.LINEAR);}
    gl.useProgram(bg);gl.viewport(0,0,size,size);
    const flag=(name,value)=>gl.uniform1i(loc(bg,name),value?1:0);
    flag('u_hasLook',!!f.lookPng);flag('u_hasSurface',!!f.surface);flag('u_tinted',false);flag('u_light',f.look.lightByDefault!==false);
    gl.uniform1i(loc(bg,'u_look'),0);gl.uniform1i(loc(bg,'u_surface'),1);
    gl.uniform3fv(loc(bg,'u_base'),rgb(f.look.base));gl.uniform3fv(loc(bg,'u_mean'),rgb(f.look.mean||f.look.base));
    const lamp=[-.45,-.55,.70],len=Math.hypot(...lamp);gl.uniform3fv(loc(bg,'u_lamp'),lamp.map(v=>v/len));
    gl.uniform1f(loc(bg,'u_show'),1);gl.uniform1f(loc(bg,'u_relief'),f.surface?.relief||0);gl.uniform1f(loc(bg,'u_slopeRange'),f.surface?.slopeRange||.099);gl.uniform1f(loc(bg,'u_detail'),0);
    gl.uniformMatrix2fv(loc(bg,'u_docStep'),false,new Float32Array([1/zoom,0,0,1/zoom]));
    for(let k=0;k<3;k++){
     const e=k===0?f.look:f.surface, pitch=(e?.texelPx||2)/(k===2?8:1);
     gl.uniform1f(loc(bg,`u_pitch[${k}]`),pitch);gl.uniform1f(loc(bg,`u_hex[${k}]`),e?.hexTexels||180);gl.uniform1f(loc(bg,`u_size[${k}]`),e?.size||512);
     flag(`u_rotate[${k}]`,e?.rotatable??true);gl.uniform2iv(loc(bg,`u_hexBase[${k}]`),[0,0]);gl.uniform2fv(loc(bg,`u_localOrigin[${k}]`),[0,0]);gl.uniform2fv(loc(bg,`u_baseCentreMod[${k}]`),[0,0]);
    }
    gl.drawArrays(gl.TRIANGLE_STRIP,0,4);const pixels=new Uint8Array(size*size*4);gl.readPixels(0,0,size,size,gl.RGBA,gl.UNSIGNED_BYTE,pixels);return pixels;
   }
   const flat=render(1,false,16), mip=render(1,true,16);
   const error=pixels=>Math.max(...pixels.map((v,i)=>Math.abs(v-f.expected[i])));
   checks.push({id:f.look.id,rawError,bilinearError:error(flat),mipError:error(mip)});
   for(const [col,zoom] of [.25,1,4].entries()){
    const pixels=render(zoom,true,128);ctx.putImageData(new ImageData(new Uint8ClampedArray(pixels),128,128),col*216,row*156);
    ctx.fillStyle='#111';ctx.font='12px sans-serif';ctx.fillText(`${f.look.name} zoom ${zoom}x`,col*216+2,row*156+145);
   }
   gl.deleteTexture(lt);gl.deleteTexture(st);
  }
  return {checks,glError:gl.getError(),sheet:sheet.toDataURL('image/png').split(',')[1]};
 },input);
 const sheet=result.sheet;delete result.sheet;
 fs.writeFileSync(path.join(__dirname,'out/material-gpu-contact.png'),Buffer.from(sheet,'base64'));
 fs.writeFileSync(path.join(__dirname,'out/material-gpu-check.json'),JSON.stringify(result,null,2));
 console.log(JSON.stringify(result));
 if(result.glError!==0||result.checks.some(c=>c.rawError!==0||c.bilinearError>4))process.exitCode=1;
 }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
