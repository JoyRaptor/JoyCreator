const fs = require('fs'), path = require('path');
const { chromium } = require('playwright-core');
const root = path.join(__dirname, '..');
function source(name) { return fs.readFileSync(path.join(root, 'shaders', name), 'utf8').replace(/^#include "([^"]+)"/gm, (_, n) => source(n)); }
(async () => {
  const browser = await chromium.launch({executablePath:process.env.CHROME || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', args:['--use-angle=swiftshader','--enable-unsafe-swiftshader']});
  try {
    const page = await browser.newPage();
    const result = await page.evaluate(({vert,frag,smudge,tileVert,commitFrag}) => {
      const canvas=document.createElement('canvas');canvas.width=256;canvas.height=256;
      const gl=canvas.getContext('webgl2');if(!gl || !gl.getExtension('EXT_color_buffer_float'))throw Error('Float WebGL2 required');
      function shader(type,s){const sh=gl.createShader(type);gl.shaderSource(sh,s);gl.compileShader(sh);if(!gl.getShaderParameter(sh,gl.COMPILE_STATUS))throw Error(gl.getShaderInfoLog(sh));return sh;}
      function program(f){const p=gl.createProgram();gl.attachShader(p,shader(gl.VERTEX_SHADER,vert));gl.attachShader(p,shader(gl.FRAGMENT_SHADER,f));gl.linkProgram(p);if(!gl.getProgramParameter(p,gl.LINK_STATUS))throw Error(gl.getProgramInfoLog(p));return p;}
      const p=program(frag);program(smudge);gl.useProgram(p);
      const u=n=>gl.getUniformLocation(p,n);const set=(n,v)=>gl.uniform1f(u(n),v);
      gl.uniform2f(u('u_tileOrigin'),0,0);set('u_tileSize',256);set('u_corner',2);set('u_minPx',0.6);set('u_aspect',0);set('u_hardness',1);
      const output=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,output);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA16F,256,256,0,gl.RGBA,gl.HALF_FLOAT,null);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.NEAREST);
      const fbo=gl.createFramebuffer();gl.bindFramebuffer(gl.FRAMEBUFFER,fbo);gl.framebufferTexture2D(gl.FRAMEBUFFER,gl.COLOR_ATTACHMENT0,gl.TEXTURE_2D,output,0);if(gl.checkFramebufferStatus(gl.FRAMEBUFFER)!==gl.FRAMEBUFFER_COMPLETE)throw Error('FBO');
      const paper=gl.createTexture();gl.activeTexture(gl.TEXTURE1);gl.bindTexture(gl.TEXTURE_2D,paper);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA8,1,1,0,gl.RGBA,gl.UNSIGNED_BYTE,new Uint8Array([128,128,166,166]));gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.NEAREST);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.NEAREST);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.REPEAT);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.REPEAT);
      gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,paper);
      gl.uniform1i(u('u_paperSurface'),1);gl.uniform1i(u('u_tipGrain'),0);set('u_paperSize',1);set('u_paperTexelPx',1);set('u_paperHexTexels',1);set('u_paperInfluence',1);set('u_paperEdge',0.16);set('u_paperTiltGradient',0.95);
      const vao=gl.createVertexArray();gl.bindVertexArray(vao);const quad=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,quad);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-1,-1,1,-1,-1,1,1,1]),gl.STATIC_DRAW);gl.enableVertexAttribArray(0);gl.vertexAttribPointer(0,2,gl.FLOAT,false,0,0);
      const data=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,data);
      for(const [loc,count,off] of [[1,4,0],[2,2,16],[4,2,24],[5,4,32],[6,4,48],[7,1,64]]){gl.enableVertexAttribArray(loc);gl.vertexAttribPointer(loc,count,gl.FLOAT,false,68,off);gl.vertexAttribDivisor(loc,1);}
      gl.viewport(0,0,256,256);gl.disable(gl.BLEND);
      const pixel=(x,y)=>{const a=new Float32Array(4);gl.readPixels(x,y,1,1,gl.RGBA,gl.FLOAT,a);return a[0];};
      function draw(dabs){gl.clearColor(0,0,0,0);gl.clear(gl.COLOR_BUFFER_BIT);gl.bindBuffer(gl.ARRAY_BUFFER,data);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array(dabs.flat()),gl.STREAM_DRAW);gl.drawArraysInstanced(gl.TRIANGLE_STRIP,0,4,dabs.length);}
      const dab=(x,y,r,angle,depth,anchor,tilt,leanX=1,leanY=0)=>[x,y,r,angle,1,1,0,0,-0.88,0.95,1,depth,anchor,tilt,leanX,leanY,1];
      set('u_paperGrainPitchPx',0);draw([dab(128,128,36,0,0.5,0.92,0.707)]);
      const geometry={near:pixel(124,128),far:pixel(66,128),outside:pixel(128,137)};
      if(!(geometry.near>0.2 && geometry.far>0.2 && geometry.outside<0.01))throw Error('Long anchored footprint '+JSON.stringify(geometry));
      draw([dab(128,128,36,Math.PI,0.5,0.92,0.707,-1)]);const mirror= pixel(190,128);if(!(mirror>0.2 && pixel(66,128)<0.01))throw Error('Lean mirror failed');
      set('u_paperGrainPitchPx',1);draw([dab(128,128,36,0,0.3,0.92,0.707)]);
      const graze={near:pixel(120,128),far:pixel(70,128)};if(!(graze.near>graze.far+0.3))throw Error('Graded tooth contact '+JSON.stringify(graze));
      set('u_paperTiltGradient',0);draw([dab(100,60,20,0,0.1,0,0),dab(100,180,20,0,0.95,0,0)]);
      const depth={light:pixel(100,60),firm:pixel(100,180)};if(!(depth.light<0.01 && depth.firm>0.99))throw Error('Live per-dab depth '+JSON.stringify(depth));
      set('u_paperGrainPitchPx',0);draw([dab(270,128,36,0,0.5,0.92,0.707)]);const boundary=pixel(220,128);if(!(boundary>0.2))throw Error('Footprint clipped at tile');
      const paint=program(smudge);gl.useProgram(paint);
      const pu=n=>gl.getUniformLocation(paint,n), ps=(n,v)=>gl.uniform1f(pu(n),v);
      gl.uniform2f(pu('u_tileOrigin'),0,0);ps('u_tileSize',256);ps('u_corner',2);ps('u_minPx',0.6);
      ps('u_texturePickup',0.68);ps('u_tipGrainPitchPx',0);ps('u_paperGrainPitchPx',0);
      gl.uniform1i(pu('u_tipGrain'),0);gl.uniform1i(pu('u_paperSurface'),1);
      gl.vertexAttrib4f(3,0,1,0,1);
      function pickupTexture(stripes,red) {
        const t=gl.createTexture(), pixels=new Uint8Array(256*256*4);
        for(let y=0;y<256;y++)for(let x=0;x<256;x++){const i=(y*256+x)*4, r=stripes ? Math.floor(x/8)%2===0 : red;pixels[i]=r?255:0;pixels[i+2]=r?0:255;pixels[i+3]=255;}
        gl.bindTexture(gl.TEXTURE_2D,t);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA8,256,256,0,gl.RGBA,gl.UNSIGNED_BYTE,pixels);
        gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.NEAREST);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.NEAREST);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);return t;
      }
      for(let y=0;y<3;y++)for(let x=0;x<3;x++){const unit=2+y*3+x;gl.activeTexture(gl.TEXTURE0+unit);pickupTexture(true,false);gl.uniform1i(pu('u_pickup'+x+y),unit);}
      const oilDab=dab(128,128,40,0,1,0,0);oilDab[6]=1;
      draw([oilDab]);
      const rgba=(x,y)=>{const a=new Float32Array(4);gl.readPixels(x,y,1,1,gl.RGBA,gl.FLOAT,a);return Array.from(a);};
      const oil={a:rgba(120,128),b:rgba(128,128)};
      if(!(Math.abs(oil.a[0]-oil.b[0])>0.6 && Math.abs(oil.a[2]-oil.b[2])>0.6))throw Error('Spatial pigment averaged '+JSON.stringify(oil));
      for(let y=0;y<3;y++)for(let x=0;x<3;x++){gl.activeTexture(gl.TEXTURE0+2+y*3+x);pickupTexture(false,x===0);}
      const edgeDab=dab(5,128,40,0,1,0,0);edgeDab[6]=1;draw([edgeDab]);
      const pickupEdge=rgba(3,128);if(!(pickupEdge[0]>0.65 && pickupEdge[2]<0.01))throw Error('Neighbour pickup seam '+JSON.stringify(pickupEdge));
      gl.uniform2f(pu('u_tileOrigin'),-256,0);edgeDab[0]=-251;draw([edgeDab]);
      const negativeEdge=rgba(3,128);if(!(negativeEdge[0]>0.65 && negativeEdge[2]<0.01))throw Error('Negative neighbour pickup seam');
      const commit=gl.createProgram();gl.attachShader(commit,shader(gl.VERTEX_SHADER,tileVert));gl.attachShader(commit,shader(gl.FRAGMENT_SHADER,commitFrag));gl.linkProgram(commit);if(!gl.getProgramParameter(commit,gl.LINK_STATUS))throw Error(gl.getProgramInfoLog(commit));gl.useProgram(commit);
      const cu=n=>gl.getUniformLocation(commit,n);
      gl.bindBuffer(gl.ARRAY_BUFFER,quad);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([0,0,1,0,0,1,1,1]),gl.STATIC_DRAW);gl.uniform1f(cu('u_tileSize'),256);gl.uniform2f(cu('u_tileOrigin'),0,0);gl.uniformMatrix3fv(cu('u_docToClip'),false,new Float32Array([2/256,0,0,0,2/256,0,-1,-1,1]));
      const dest=gl.createTexture();gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,dest);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA16F,256,256,0,gl.RGBA,gl.HALF_FLOAT,null);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.NEAREST);gl.framebufferTexture2D(gl.FRAMEBUFFER,gl.COLOR_ATTACHMENT0,gl.TEXTURE_2D,dest,0);
      const empty=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,empty);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA8,1,1,0,gl.RGBA,gl.UNSIGNED_BYTE,new Uint8Array([0,0,0,0]));gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.NEAREST);
      gl.activeTexture(gl.TEXTURE1);gl.bindTexture(gl.TEXTURE_2D,output);gl.uniform1i(cu('u_layer'),0);gl.uniform1i(cu('u_stroke'),1);gl.uniform1f(cu('u_layerOpacity'),1);gl.uniform1f(cu('u_strokeScale'),1);
      gl.uniform1i(cu('u_smudge'),1);gl.drawArrays(gl.TRIANGLE_STRIP,0,4);const legacyBlank=rgba(3,128);
      gl.uniform1i(cu('u_smudge'),2);gl.drawArrays(gl.TRIANGLE_STRIP,0,4);const paintedBlank=rgba(3,128);
      if(!(legacyBlank[3]===0 && paintedBlank[3]>0.99))throw Error('Paint cannot cover empty canvas '+JSON.stringify({legacyBlank,paintedBlank,error:gl.getError()}));
      gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,empty);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA8,1,1,0,gl.RGBA,gl.UNSIGNED_BYTE,new Uint8Array([255,255,255,255]));gl.uniform1i(cu('u_erase'),1);gl.drawArrays(gl.TRIANGLE_STRIP,0,4);const erased=rgba(3,128);if(!(erased[3]<0.01))throw Error('Paint erasing failed');
      if(gl.getError()!==gl.NO_ERROR)throw Error('GL error');
      return {compiled:true,geometry,mirror,graze,depth,boundary,oil,pickupEdge,negativeEdge,legacyBlank,paintedBlank,erased};
    },{vert:source('jb_dab.vert'),frag:source('jb_dab.frag'),smudge:source('jb_smudge_dab.frag'),tileVert:source('jb_tile.vert'),commitFrag:source('jb_commit.frag')});
    console.log(JSON.stringify(result,null,2));
  } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
