"""Desktop EGL parity test for TextFxGlEffect's bitmap and borrowed-texture shaders.
Set EGL_LIB_DIR to a directory containing libEGL and libGLESv2, or use system EGL on Linux.
Pass an optional repository root to replay another revision.
"""
import ast
import ctypes as c
import ctypes.util
import math
import os
from pathlib import Path
import re
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
code = (root / "app/src/main/java/com/fadcam/ui/faditor/export/TextFxGlEffect.java").read_text(encoding="utf-8")
direct = "gpuTitle.draw(" in code


def java_string(name):
    section = code.split("String " + name + " =", 1)[1].split('";', 1)[0] + '"'
    return "".join(ast.literal_eval(s) for s in re.findall(r'"(?:\\.|[^"\\])*"', section))


P, I, U, F, S = c.c_void_p, c.c_int, c.c_uint, c.c_float, c.c_char_p
library = os.environ.get("EGL_LIB_DIR")
if library and os.name == "nt":
    dll_search = os.add_dll_directory(str(Path(library).resolve()))
def load(name):
    if library:
        suffix = ".dll" if os.name == "nt" else ".so"
        return c.CDLL(str(Path(library) / ("lib" + name + suffix)))
    return c.CDLL(ctypes.util.find_library(name))
egl, gl = load("EGL"), load("GLESv2")


def fn(lib, name, result, *args):
    f = getattr(lib, name)
    f.restype, f.argtypes = result, list(args)
    return f


def ints(*a):
    return (I * len(a))(*a)
def gf(name, result, *args):
    return fn(gl, "gl"+name, result, *args)
def ef(name, result, *args):
    return fn(egl, "egl"+name, result, *args)


display = ef("GetDisplay", P, P)(None)
assert ef("Initialize", U, P, P, P)(display, None, None), "cannot initialize desktop EGL"
config, n = P(), I()
attrs = ints(0x3033, 1, 0x3040, 0x40, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3038)
assert ef("ChooseConfig", U, P, P, P, I, P)(display, attrs, c.byref(config), 1, c.byref(n)) and n.value
W, H = 16, 12
surface = ef("CreatePbufferSurface", P, P, P, P)(display, config, ints(0x3057,W,0x3056,H,0x3038))
make = ef("MakeCurrent", U, P,P,P,P)
ctx = ef("CreateContext", P, P,P,P,P)
destroy = ef("DestroyContext", U,P,P)
def current(context):
    assert make(display, surface, surface, context)
def name(kind):
    value = U()
    gf("Gen"+kind, None, I,P)(1,c.byref(value))
    return value.value


def texture(w, h, rgba=None):
    tex = name("Textures")
    gf("BindTexture",None,U,U)(0x0DE1,tex)
    for key,v in [(0x2801,0x2601),(0x2800,0x2601),(0x2802,0x812F),(0x2803,0x812F)]:
        gf("TexParameteri",None,U,U,I)(0x0DE1,key,v)
    gf("TexImage2D",None,U,I,I,I,I,I,U,U,P)(0x0DE1,0,0x1908,w,h,0,0x1908,0x1401,rgba)
    return tex


def pixels(w,h):
    out = (c.c_ubyte*(w*h*4))()
    gf("ReadPixels",None,I,I,I,I,U,U,P)(0,0,w,h,0x1908,0x1401,out)
    return bytes(out)


def program(vert, frag):
    pid = gf("CreateProgram",U)()
    for stage,text in [(0x8B31,vert),(0x8B30,frag)]:
        shader = gf("CreateShader",U,U)(stage)
        string = S(text.encode())
        gf("ShaderSource",None,U,I,P,P)(shader,1,c.byref(string),None)
        gf("CompileShader",None,U)(shader)
        ok=I()
        gf("GetShaderiv",None,U,U,P)(shader,0x8B81,c.byref(ok))
        if not ok.value:
            log=c.create_string_buffer(2048)
            gf("GetShaderInfoLog",None,U,I,P,P)(shader,len(log),None,log)
            raise AssertionError(log.value)
        gf("AttachShader",None,U,U)(pid,shader)
    gf("BindAttribLocation",None,U,U,S)(pid,0,b"aFramePosition")
    gf("LinkProgram",None,U)(pid)
    status=I()
    gf("GetProgramiv",None,U,U,P)(pid,0x8B82,c.byref(status))
    assert status.value, "shader link failed"
    return pid


def sync():
    fence=gf("FenceSync",P,U,U)(0x9117,0)
    assert fence
    gf("Flush",None)()
    return fence
def wait(fence):
    if fence:
        gf("WaitSync",None,P,U,c.c_ulonglong)(fence,0,2**64-1)
        gf("DeleteSync",None,P)(fence)


def laid_down(raw, w, h, rows):
    # Independent reference: rectangular raster top-to-bottom, filtered premultiplied RGBA8.
    out=bytearray(W*H*4)
    for y in range(H):
        for x in range(W):
            pos=((x+.5)/W,(y+.5)/H,1.)
            u,v,z=[sum(a*b for a,b in zip(row,pos)) for row in rows]
            u,v=u/z,v/z
            if not 0<=u<1 or not 0<=v<1: continue
            sx,sy=u*w-.5,(1-v)*h-.5
            ix,iy=math.floor(sx),math.floor(sy)
            for k in range(4):
                total=0.
                for j in range(2):
                    for i in range(2):
                        col=min(w-1,max(0,ix+i))
                        line=min(h-1,max(0,iy+j))
                        total+=raw[4*(line*w+col)+k]*(1-abs(sx-ix-i))*(1-abs(sy-iy-j))
                out[4*(y*W+x)+k]=round(total)
    return bytes(out)


def uniform(pid, label):
    return gf("GetUniformLocation",I,U,S)(pid,label.encode())
def render(pid,title,background,rows=None):
    gf("BindFramebuffer",None,U,U)(0x8D40,0)
    gf("Viewport",None,I,I,I,I)(0,0,W,H)
    gf("UseProgram",None,U)(pid)
    for unit,(label,tex) in enumerate([("uVideoTexSampler0",background),("uTextTexSampler0",title)]):
        gf("ActiveTexture",None,U)(0x84C0+unit)
        gf("BindTexture",None,U,U)(0x0DE1,tex)
        gf("Uniform1i",None,I,I)(uniform(pid,label),unit)
    if rows:
        for label,row in zip(("uTitleX","uTitleY","uTitleW"),rows):
            gf("Uniform3f",None,I,F,F,F)(uniform(pid,label),*row)
    gf("DrawArrays",None,U,I,I)(5,0,4)
    return pixels(W,H)  # Validation only; excluded from transfer counts.


vert,frag=java_string("VERTEX_SHADER"),java_string("BASE_FRAGMENT")
# Identity, rotated/translated, a perspective pin, reverse seek, then a new share group.
poses=[((1,0,0),(0,1,0),(0,0,1)),((0,2,-.5),(-2,0,1.5),(0,0,1)),
       ((1.7,.2,-.25),(.15,1.4,-.2),(.2,-.1,1))]
reads=frames=worst=0
for generation in range(2):
    consumer=ctx(display,config,None,ints(0x3098,3,0x3038))
    producer=ctx(display,config,consumer,ints(0x3098,3,0x3038))
    assert consumer and producer
    current(consumer)
    legacy=program(vert,frag)
    if direct:
        dfrag=frag.replace("void main() {","uniform highp vec3 uTitleX, uTitleY, uTitleW;\nvoid main() {")
        dfrag=dfrag.replace("varying vec2","varying highp vec2").replace("vec2 ovc = vec2(","highp vec2 ovc = vec2(")
        dfrag=dfrag.replace("vec4 src = texture2D(uTextTexSampler0, ovc);",java_string("TEXTURE_SAMPLE"))
        borrowed=program(vert.replace("varying vec2","varying highp vec2"),dfrag)
    buf=name("Buffers")
    quad=(F*8)(-1,-1,1,-1,-1,1,1,1)
    gf("BindBuffer",None,U,U)(0x8892,buf)
    gf("BufferData",None,U,c.c_ssize_t,P,U)(0x8892,c.sizeof(quad),quad,0x88E4)
    gf("VertexAttribPointer",None,U,I,U,U,I,P)(0,2,0x1406,0,0,None)
    gf("EnableVertexAttribArray",None,U)(0)
    bg=texture(1,1,bytes((30,55,90,255)))
    current(producer)
    w,h=4+generation,3
    title=texture(w,h)
    fbo=name("Framebuffers")
    gf("BindFramebuffer",None,U,U)(0x8D40,fbo)
    gf("FramebufferTexture2D",None,U,U,U,U,I)(0x8D40,0x8CE0,0x0DE1,title,0)
    assert gf("CheckFramebufferStatus",U,U)(0x8D40)==0x8CD5
    used=None
    for t,rows in zip([0,1,2,0],poses+[poses[0]]):
        current(producer)
        wait(used)
        raw=bytearray()
        gf("Enable",None,U)(0x0C11)
        for y in range(h):
            for x in range(w):
                a=0 if x==0 and y==0 else 40+((x*32+y*50+t*17)%215)
                rgba=(a if x%2 else 0,a*y//h,a*((x+t)%3)//2,a)
                raw.extend(rgba)
                gf("Scissor",None,I,I,I,I)(x,y,1,1)
                gf("ClearColor",None,F,F,F,F)(*(v/255 for v in rgba))
                gf("Clear",None,U)(0x4000)
        if not direct:
            raw=pixels(w,h)
            reads+=1
        ready=sync()
        current(consumer)
        wait(ready)
        reference_texture=texture(W,H,laid_down(raw,w,h,rows))
        reference=render(legacy,reference_texture,bg)
        actual=render(borrowed,title,bg,rows) if direct else reference
        used=sync()
        worst=max(worst,max(abs(x-y) for x,y in zip(reference,actual)))
        assert gf("GetError",U)()==0
        frames+=1
    current(producer)
    wait(used)
    assert make(display,None,None,None)
    assert destroy(display,producer) and destroy(display,consumer)
print(f"{frames} frames, {reads} producer readbacks, maximum RGBA8 channel difference {worst}")
assert reads==0, "each new title frame still crosses the CPU"
assert worst<=2, "borrowed texture changed the composited picture"
