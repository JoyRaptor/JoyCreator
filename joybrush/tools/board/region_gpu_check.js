// Actual production commit shader and GLES-equivalent blit/scissor on a real WebGL2 driver.
// Fresh ownership fixtures come from RegionGpuFixtureTest, not another geometry implementation.
const fs=require('fs'),path=require('path'),os=require('os'),{chromium}=require('playwright-core');
const root=path.resolve(__dirname,'../..');
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROME||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',args:['--use-angle=swiftshader','--enable-unsafe-swiftshader','--ignore-gpu-blocklist']});
 try {
 const page=await browser.newPage();
 const fixtures=JSON.parse(fs.readFileSync(process.env.REGION_FIXTURES||path.join(os.tmpdir(),'jb-region-gpu-fixtures.json'),'utf8'));
 // Deliberately overwrite one neighbouring pixel: the driver proof must catch this boundary error.
 if(process.argv.includes('--mutate-boundary'))fixtures[0].slices.find(s=>s.plane===1).rect[2]++;
 const result=await page.evaluate(input=>{
  const gl=document.createElement('canvas').getContext('webgl2');if(!gl)throw Error('WebGL2 unavailable');
  const program=gl.createProgram();
  for(const [type,source]of[[gl.VERTEX_SHADER,input.vertex],[gl.FRAGMENT_SHADER,input.fragment]]){const s=gl.createShader(type);gl.shaderSource(s,source);gl.compileShader(s);if(!gl.getShaderParameter(s,gl.COMPILE_STATUS))throw Error(gl.getShaderInfoLog(s));gl.attachShader(program,s);}
  gl.linkProgram(program);if(!gl.getProgramParameter(program,gl.LINK_STATUS))throw Error(gl.getProgramInfoLog(program));gl.useProgram(program);
  const loc=n=>gl.getUniformLocation(program,n),vao=gl.createVertexArray();gl.bindVertexArray(vao);
  const vertices=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,vertices);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([0,0,1,0,0,1,1,1]),gl.STATIC_DRAW);gl.enableVertexAttribArray(0);gl.vertexAttribPointer(0,2,gl.FLOAT,false,0,0);
  gl.uniform1f(loc('u_tileSize'),256);gl.uniform2f(loc('u_tileOrigin'),0,0);gl.uniformMatrix3fv(loc('u_docToClip'),false,new Float32Array([2/256,0,0,0,2/256,0,-1,-1,1]));
  gl.uniform1i(loc('u_layer'),0);gl.uniform1i(loc('u_stroke'),1);gl.uniform3f(loc('u_color'),1,0,0);gl.uniform1f(loc('u_strokeScale'),1);gl.uniform1f(loc('u_layerOpacity'),1);gl.uniform1i(loc('u_smudge'),0);
  const read=gl.createFramebuffer(),draw=gl.createFramebuffer();gl.viewport(0,0,256,256);gl.disable(gl.BLEND);
  const colours=[[30,60,90,255],[60,90,120,255],[90,120,150,255]];
  function texture(colour){const t=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,t);const a=new Uint8Array(256*256*4);for(let i=0;i<a.length;i+=4)a.set(colour,i);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA8,256,256,0,gl.RGBA,gl.UNSIGNED_BYTE,a);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.NEAREST);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.NEAREST);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);return t;}
  let checked=0;
  for(const fixture of input.fixtures)for(const erase of[false,true]){
   gl.activeTexture(gl.TEXTURE0);const sources=colours.slice(0,fixture.planes).map(texture),stroke=texture([255,0,0,255]);
   for(let plane=0;plane<fixture.planes;plane++){
    const target=texture([0,0,0,0]);gl.disable(gl.SCISSOR_TEST);
    gl.bindFramebuffer(gl.READ_FRAMEBUFFER,read);gl.framebufferTexture2D(gl.READ_FRAMEBUFFER,gl.COLOR_ATTACHMENT0,gl.TEXTURE_2D,sources[plane],0);
    gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER,draw);gl.framebufferTexture2D(gl.DRAW_FRAMEBUFFER,gl.COLOR_ATTACHMENT0,gl.TEXTURE_2D,target,0);
    gl.blitFramebuffer(0,0,256,256,0,0,256,256,gl.COLOR_BUFFER_BIT,gl.NEAREST);
    gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,sources[plane]);gl.activeTexture(gl.TEXTURE1);gl.bindTexture(gl.TEXTURE_2D,stroke);gl.uniform1i(loc('u_erase'),erase?1:0);gl.enable(gl.SCISSOR_TEST);
    for(const slice of fixture.slices.filter(s=>s.plane===plane)){gl.scissor(...slice.rect);gl.drawArrays(gl.TRIANGLE_STRIP,0,4);}
    gl.disable(gl.SCISSOR_TEST);gl.bindFramebuffer(gl.FRAMEBUFFER,draw);const rgba=new Uint8Array(256*256*4);gl.readPixels(0,0,256,256,gl.RGBA,gl.UNSIGNED_BYTE,rgba);
    if(gl.getError()!==gl.NO_ERROR)throw Error('GL error');
    for(let pixel=0;pixel<65536;pixel++){const expected=fixture.owners[pixel]===plane?(erase?[0,0,0,0]:[255,0,0,255]):colours[plane];for(let c=0;c<4;c++)if(rgba[pixel*4+c]!==expected[c])throw Error(`Ownership mismatch plane${plane}, pixel${pixel}, channel${c}: ${rgba[pixel*4+c]} != ${expected[c]}`);}
    checked+=65536;gl.deleteTexture(target);
   }
   sources.forEach(t=>gl.deleteTexture(t));gl.deleteTexture(stroke);
  }
  return {fixtures:input.fixtures.length,pixelsChecked:checked,glError:gl.getError()};
 },{vertex:fs.readFileSync(path.join(root,'shaders/jb_tile.vert'),'utf8'),fragment:fs.readFileSync(path.join(root,'shaders/jb_commit.frag'),'utf8'),fixtures});
 console.log(JSON.stringify(result));
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
