from pathlib import Path
import sys,math,json
from PIL import Image,ImageDraw
# Usage: python buildScript/prepare_mobile_seasonal.py SOURCE_DIR OUTPUT_DIR [THEME]
SOURCE=Path(sys.argv[1]).resolve()
OUTPUT=Path(sys.argv[2]).resolve()
sys.path.insert(0,str(SOURCE))
import render
PAD=24
SHIFT_Y=0
class OffsetDraw:
    def __init__(self,draw):self.draw=draw
    def __getattr__(self,name):
        method=getattr(self.draw,name)
        if name not in ('polygon','rectangle','rounded_rectangle','ellipse','line','point','arc','text','textbbox'):return method
        def call(xy,*args,**kwargs):
            offset=PAD*render.SCALE
            if isinstance(xy[0],(int,float)):
                xy=tuple(v+offset+(SHIFT_Y*render.SCALE if i%2 else 0) for i,v in enumerate(xy))
            else:xy=[(x+offset,y+offset+SHIFT_Y*render.SCALE) for x,y in xy]
            result=method(xy,*args,**kwargs)
            return tuple(v-offset for v in result) if name=='textbbox' else result
        return call
class SafeCanvas(render.Canvas):
    def __init__(self,mode,size,color=None,raw=None):
        self.size=tuple(map(int,size));self.width,self.height=self.size
        self.raw=raw if raw is not None else Image.new(mode,((self.width+2*PAD)*render.SCALE,(self.height+2*PAD)*render.SCALE),color)
class SafeImages:
    new=staticmethod(lambda mode,size,color=None:SafeCanvas(mode,size,color))
    @staticmethod
    def alpha_composite(a,b):return SafeCanvas('RGBA',a.size,raw=Image.alpha_composite(a.raw,b.raw))
class SafeVectorDraw(render.VectorDraw):
    def __init__(self,image):self.image=image;self.d=OffsetDraw(ImageDraw.Draw(image.raw))
class SafeDraws:Draw=SafeVectorDraw

def main():
    out=OUTPUT;(out/'animations').mkdir(parents=True,exist_ok=True)
    metadata=json.loads((out/'current-manifest.json').read_text()) if (out/'current-manifest.json').exists() else {}
    render.SCALE=6
    for theme,seconds in [('halloween',18),('newyear',24),('easter',24),('kupala',24)]:
        if len(sys.argv)>3 and theme!=sys.argv[3]:continue
        module=render.load(theme);module.Image=SafeImages;module.ImageDraw=SafeDraws
        if theme=='halloween':
            original_ghost=module.ghost
            def safe_ghost(im,x,t):
                global SHIFT_Y
                phase=t%9
                peek=math.sin(phase*math.pi/5)**2 if phase<5 else 0
                old_y=(-18+peek*23) if t<9 else (46-peek*21)
                SHIFT_Y=7+3*math.sin(t*math.tau/18)-old_y
                try:original_ghost(im,x,t)
                finally:SHIFT_Y=0
            module.ghost=safe_ghost
            original_frame=module.frame
            def grounded_frame(kind,t):
                im=SafeImages.new('RGBA',(136,46));d=SafeVectorDraw(im)
                d.polygon([(2,45),(7,43),(22,42),(37,43),(54,43),(67,44),(84,43),(100,44),(120,43),(133,45),
                    (131,48),(115,49),(95,48),(76,49),(56,48),(35,49),(17,48),(4,47)],fill='#945c35')
                d.line([(7,44),(22,43),(38,44),(57,44),(76,45),(95,44),(116,44),(129,45)],fill='#bd7b43',width=.6)
                im.alpha_composite(original_frame(kind,t))
                return im
            module.frame=grounded_frame
        def frame(t):return (module.frame(theme,'wide',t) if theme=='easter' else module.frame('wide',t)).raw
        bounds=None
        for i in range(seconds*20):
            art=frame(i/20);box=art.getchannel('A').getbbox()
            if box:
                assert box[0]>0 and box[1]>0 and box[2]<art.width and box[3]<art.height, 'Increase drawing guard'
                bounds=box if bounds is None else (min(bounds[0],box[0]),min(bounds[1],box[1]),max(bounds[2],box[2]),max(bounds[3],box[3]))
        print(theme+' full animation bounds '+str(bounds),flush=True)
        scale=min(540/(bounds[2]-bounds[0]),180/(bounds[3]-bounds[1]))
        size=(round((bounds[2]-bounds[0])*scale),round((bounds[3]-bounds[1])*scale))
        frames=[]
        for i in range(seconds*20):
            art=frame(i/20).crop(bounds).resize(size,Image.Resampling.LANCZOS)
            target=Image.new('RGBA',(544,184));target.alpha_composite(art,((544-size[0])//2,182-size[1]))
            box=target.getchannel('A').getbbox()
            assert box[0]>0 and box[1]>0 and box[2]<544 and box[3]<184
            frames.append(target)
        frames[0].save(out/'animations'/(theme+'-wide.png'),save_all=True,append_images=frames[1:],duration=50,loop=0,disposal=0,blend=0)
        metadata[theme]={'wide':{'transparent_master':theme+'-wide.png','duration_ms':seconds*1000}}
        print(theme+' safe master saved',flush=True)
    (out/'current-manifest.json').write_text(json.dumps(metadata),encoding='utf-8')
if __name__=='__main__':main()
