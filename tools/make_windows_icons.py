import struct, io, sys
from playwright.sync_api import sync_playwright
SHIELD="M50 4L92 18V52C92 78 74 97 50 106C26 97 8 78 8 52V18Z"
TOP="M0 0H100V30Q46 60 0 80Z"
BOT="M0 88Q52 70 100 45V110H0Z"
def svg(px, on=True):
    small = px <= 24
    fill = 0.84 if small else 0.68          # shield height / tile
    radius = 22 if not small else 20
    h = 102*fill/100*px/1.0                 # shield height in px (shield path is 102 tall)
    sc = (fill*100)/102                     # scale for 100-unit tile
    ox = 50 - 50*sc; oy = 50 - (4+51)*sc     # centre shield (bbox y 4..106)
    top = "#3B82FF" if on else "#7A7A7A"
    edge = '' if small else '<rect x="0.75" y="0.75" width="98.5" height="98.5" rx="%s" fill="none" stroke="#2C2C2C" stroke-width="1.5"/>' % (radius-0.75)
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{px}" height="{px}" viewBox="0 0 100 100">
<defs><clipPath id="c"><path d="{SHIELD}"/></clipPath>
<linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#1A1A1A"/><stop offset="1" stop-color="#050505"/></linearGradient></defs>
<rect width="100" height="100" rx="{radius}" fill="url(#g)"/>{edge}
<g transform="translate({ox:.3f} {oy:.3f}) scale({sc:.4f})"><g clip-path="url(#c)">
<path d="{TOP}" fill="{top}"/><path d="{BOT}" fill="#FFFFFF"/></g></g></svg>'''
def render(pg, px, on):
    pg.set_viewport_size({'width':px,'height':px})
    pg.set_content(f'<html><body style="margin:0;background:transparent">{svg(px,on)}</body></html>')
    return pg.screenshot(omit_background=True, clip={'x':0,'y':0,'width':px,'height':px})
def ico(pngs):
    n=len(pngs); out=io.BytesIO(); out.write(struct.pack('<HHH',0,1,n)); off=6+16*n; data=b''
    for px,b in pngs:
        out.write(struct.pack('<BBBBHHII', px%256, px%256, 0,0,1,32,len(b),off+len(data))); data+=b
    out.write(data); return out.getvalue()
with sync_playwright() as p:
    b=p.chromium.launch(); pg=b.new_page()
    app=[(s,render(pg,s,True)) for s in (16,20,24,32,40,48,64,96,128,256)]
    on=[(s,render(pg,s,True)) for s in (16,20,24,32,40,48)]
    off=[(s,render(pg,s,False)) for s in (16,20,24,32,40,48)]
    open('app.ico','wb').write(ico(app)); open('tray_on.ico','wb').write(ico(on)); open('tray_off.ico','wb').write(ico(off))
    for s,bts in app: open(f'app_{s}.png','wb').write(bts)
    for s,bts in off: open(f'off_{s}.png','wb').write(bts)
    # preview sheet: on dark and light taskbars, at real size and zoomed
    import base64
    def im(f,z=1,s=None):
        d=base64.b64encode(open(f,'rb').read()).decode(); w=(s or 0)*z
        return f'<img src="data:image/png;base64,{d}" style="width:{w}px;height:{w}px;image-rendering:pixelated;margin:10px">'
    row=lambda bg: f'<div style="background:{bg};padding:12px;display:flex;align-items:center">'+''.join(im(f'app_{s}.png',1,s) for s in (16,24,32,48))+im('off_16.png',1,16)+im('app_256.png',1,128)+'</div>'
    zoom='<div style="background:#202020;padding:12px">'+''.join(im(f'app_{s}.png',6,s) for s in (16,24))+im('off_16.png',6,16)+im('app_48.png',3,48)+'</div>'
    pg.set_viewport_size({'width':760,'height':560})
    pg.set_content('<body style="margin:0;font-family:sans-serif">'+row('#1C1C1C')+row('#F3F3F3')+zoom+'</body>')
    pg.screenshot(path='/tmp/claude-0/-home-claude/559e6d3f-3fa4-5858-9181-5716575b5f1f/scratchpad/icon/preview.png', full_page=True)
    b.close()
print('ok')
