// gl.js — small WebGL2 helpers for the media lab. Shaders are the app's own files under joybrush/shaders,
// fetched at run time with #include resolved against the shaders root (same rule as the Android loader).

export async function loadShaderSource(root, name, seen = new Set()) {
  if (seen.has(name)) return '';
  seen.add(name);
  const bundled = globalThis.__LAB_BUNDLE && globalThis.__LAB_BUNDLE.shaders[name];
  let text = bundled;
  if (text === undefined) {
    const res = await fetch(root + name);
    if (!res.ok) throw new Error('shader ' + name + ': ' + res.status);
    text = await res.text();
  }
  const out = [];
  for (const line of text.split('\n')) {
    const m = line.trim().match(/^#include\s+"([^"]+)"/);
    out.push(m ? await loadShaderSource(root, m[1], seen) : line);
  }
  return out.join('\n');
}

export function makeProgram(gl, vsSrc, fsSrc, label) {
  const mk = (type, src) => {
    const sh = gl.createShader(type);
    gl.shaderSource(sh, src);
    gl.compileShader(sh);
    if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
      const log = gl.getShaderInfoLog(sh);
      throw new Error(label + ' ' + (type === gl.VERTEX_SHADER ? 'vs' : 'fs') + ': ' + log);
    }
    return sh;
  };
  const p = gl.createProgram();
  gl.attachShader(p, mk(gl.VERTEX_SHADER, vsSrc));
  gl.attachShader(p, mk(gl.FRAGMENT_SHADER, fsSrc));
  gl.linkProgram(p);
  if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(label + ' link: ' + gl.getProgramInfoLog(p));
  const uniforms = {};
  const n = gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS);
  let unit = 0;
  for (let i = 0; i < n; i++) {
    const info = gl.getActiveUniform(p, i);
    const name = info.name.replace(/\[0\]$/, '');
    const loc = gl.getUniformLocation(p, info.name);
    const isSampler = info.type === gl.SAMPLER_2D || info.type === gl.INT_SAMPLER_2D || info.type === gl.UNSIGNED_INT_SAMPLER_2D;
    uniforms[name] = { loc, type: info.type, size: info.size, unit: isSampler ? unit++ : -1 };
  }
  return { gl, prog: p, uniforms, label };
}

// Set uniforms by name; unknown names are ignored (a uniform the compiler stripped is not an error).
// Textures: pass a WebGLTexture; the program's sampler units were fixed at link time.
export function use(prog, values = {}) {
  const gl = prog.gl;
  gl.useProgram(prog.prog);
  for (const [name, v] of Object.entries(values)) {
    const u = prog.uniforms[name];
    if (!u || v === undefined) continue;
    switch (u.type) {
      case gl.FLOAT: u.size > 1 ? gl.uniform1fv(u.loc, v) : gl.uniform1f(u.loc, v); break;
      case gl.FLOAT_VEC2: gl.uniform2fv(u.loc, v); break;
      case gl.FLOAT_VEC3: gl.uniform3fv(u.loc, v); break;
      case gl.FLOAT_VEC4: gl.uniform4fv(u.loc, v); break;
      case gl.INT: case gl.BOOL: u.size > 1 ? gl.uniform1iv(u.loc, v.map(Number)) : gl.uniform1i(u.loc, Number(v)); break;
      case gl.INT_VEC2: gl.uniform2iv(u.loc, v); break;
      case gl.FLOAT_MAT2: gl.uniformMatrix2fv(u.loc, false, v); break;
      case gl.SAMPLER_2D:
        gl.activeTexture(gl.TEXTURE0 + u.unit);
        gl.bindTexture(gl.TEXTURE_2D, v);
        gl.uniform1i(u.loc, u.unit);
        break;
      default: throw new Error(prog.label + ': unsupported uniform type for ' + name);
    }
  }
}

export function makeTexture(gl, w, h, { internal = gl.RGBA16F, format = gl.RGBA, type = gl.HALF_FLOAT, filter = gl.LINEAR, wrap = gl.CLAMP_TO_EDGE, data = null } = {}) {
  const t = gl.createTexture();
  gl.bindTexture(gl.TEXTURE_2D, t);
  gl.texImage2D(gl.TEXTURE_2D, 0, internal, w, h, 0, format, type, data);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, filter);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, filter === gl.LINEAR_MIPMAP_LINEAR ? gl.LINEAR : filter);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, wrap);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, wrap);
  t.w = w; t.h = h;
  return t;
}

export function makeFbo(gl, textures) {
  const f = gl.createFramebuffer();
  gl.bindFramebuffer(gl.FRAMEBUFFER, f);
  textures.forEach((t, i) => gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0 + i, gl.TEXTURE_2D, t, 0));
  gl.drawBuffers(textures.map((_, i) => gl.COLOR_ATTACHMENT0 + i));
  const status = gl.checkFramebufferStatus(gl.FRAMEBUFFER);
  if (status !== gl.FRAMEBUFFER_COMPLETE) throw new Error('framebuffer incomplete: 0x' + status.toString(16));
  f.w = textures[0].w; f.h = textures[0].h;
  return f;
}

// One unit quad (0,0)..(1,1) as a triangle strip at attribute 0, shared by every pass.
export function makeQuad(gl) {
  const buf = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, buf);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0, 0, 1, 0, 0, 1, 1, 1]), gl.STATIC_DRAW);
  return buf;
}
