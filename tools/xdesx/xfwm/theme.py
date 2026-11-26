"""Generate sharp XFWM decoration and Xcursor assets at the three desktop scales."""
import io
import struct
from PIL import Image, ImageDraw

PAPER = "#f7f7f8"
EDGE = "#c5c5c7"
INK = "#19191b"
LINES = {
    "shade": [[(-5,5),(0,0)], [(-5,1),(-5,5),(-1,5)], [(1,-1),(5,-5)], [(1,-5),(5,-5),(5,-1)]],
    "hide": [[(-5,0),(5,0)]], "maximize": [[(-5,-5),(5,-5),(5,5),(-5,5),(-5,-5)]],
    "maximize-toggled": [[(-3,-5),(5,-5),(5,3)], [(-5,-3),(3,-3),(3,5),(-5,5),(-5,-3)]],
    "close": [[(-5,-5),(5,5)], [(-5,5),(5,-5)]],
    "menu": [[(-4,-4),(4,-4),(4,4),(-4,4),(-4,-4)]],
    "stick": [[(-4,0),(4,0)],[(0,-4),(0,4)]],
}
def png(im):
    buf=io.BytesIO()
    im.save(buf,format="PNG")
    return buf.getvalue()

def decorations(z,scale,dark=False):
    paper,edge,ink=("#25262a","#46474c","#eeeff2") if dark else (PAPER,EDGE,INK)
    factor=scale/100
    px=lambda v:max(1,round(v*factor))
    folder=f"share/themes/Xdesx{'Dark' if dark else ''}{scale}/xfwm4/"
    def save(name,im): z.writestr(folder+name+".png",png(im))
    h=px(32)
    for state in ("active","inactive"):
        for name in ["title-"+str(i) for i in range(1,6)]+["top-left","top-right","left","right","bottom","bottom-left","bottom-right"]:
            corner=name in ("top-left","top-right","bottom-left","bottom-right")
            lower_corner=corner and name.startswith("bottom")
            width=px(18) if corner else px(4) if name in ("left","right") else 1
            height=h if name.startswith(("title","top")) else px(18) if lower_corner else px(4) if name=="bottom" else 2
            im=Image.new("RGBA",(width,height),paper)
            draw=ImageDraw.Draw(im)
            # The L follows both frame edges. Leave the interior corner transparent
            # so the client still receives clicks on its content and scrollbars.
            if lower_corner:
                grip=px(4)
                left=grip if name=="bottom-left" else 0
                right=width-1 if name=="bottom-left" else width-grip-1
                draw.rectangle((left,0,right,height-grip-1),fill=(0,0,0,0))
            if height==h: draw.line([(0,0),(width,0)],fill=edge)
            if "left" in name: draw.line([(0,0),(0,height)],fill=edge)
            if "right" in name: draw.line([(width-1,0),(width-1,height)],fill=edge)
            if "bottom" in name: draw.line([(0,height-1),(width,height-1)],fill=edge)
            if name in ("top-left","top-right"):
                radius=px(7)
                for x in range(radius):
                    for y in range(radius):
                        if (radius-x-.5)**2+(radius-y-.5)**2>radius**2:
                            im.putpixel((x if "left" in name else width-1-x,y if "top" in name else height-1-y),(0,0,0,0))
            save(name+"-"+state,im)
    for name,lines in LINES.items():
        for state in ("active","inactive","pressed","prelight"):
            w=px(28)
            bh=h-2*px(4)
            im=Image.new("RGB",(w*4,bh*4),paper)
            draw=ImageDraw.Draw(im)
            if state in ("pressed","prelight"):
                pad=px(3)*4
                draw.rounded_rectangle((pad,pad,w*4-pad,bh*4-pad),radius=px(5)*4,fill="#4c5056" if dark else "#dedee0" if state=="pressed" else "#eaeaec")
            for line in lines:
                draw.line([((w/2+x*factor)*4,(bh/2+y*factor)*4) for x,y in line],fill=ink if state!="inactive" else "#727276",width=round(5.6*factor))
            save(name+"-"+state,im.resize((w,bh),Image.Resampling.LANCZOS))
    z.writestr(folder+"themerc",f"active_text_color={ink}\ninactive_text_color=#6a6a70\nbutton_spacing={px(2)}\nframe_border_top={px(3)}\nshow_app_icon=true\nfull_width_title=true\ntitle_alignment=left\ntitle_horizontal_offset={px(6)}\ntitle_shadow_active=false\ntitle_shadow_inactive=false\n")

def cursor_images(kind):
    entries=[]
    for size in (24,32,40,48):
        im=Image.new("RGBA",(size*4,size*4))
        draw=ImageDraw.Draw(im)
        k=size/6
        def line(points,color,width):
            draw.line([(round(x*k),round(y*k)) for x,y in points],fill=color,width=max(1,round(width*k)),joint="curve")
        tip=(size//2,size//2)
        if kind=="arrow":
            points=[(1,1),(1,20),(6,15),(10,23),(13,21),(9,14),(16,14),(1,1)]
            draw.polygon([(x*k,y*k) for x,y in points],fill=INK)
            line(points,"white",1.1)
            tip=(round(k/4),round(k/4))
        else:
            axes={"horizontal":(1,0),"vertical":(0,1),"nwse":(.707,.707),"nesw":(.707,-.707)}
            if kind=="text": lines=[[(12,3),(12,21)],[(8,3),(16,3)],[(8,21),(16,21)]]
            else:
                u,v=axes[kind]
                def pt(a,b):return (12+a*u-b*v,12+a*v+b*u)
                lines=[[pt(-9,0),pt(9,0)]]+[[pt(s*5,-4),pt(s*9,0),pt(s*5,4)] for s in (-1,1)]
            for color,width in [("white",3.5),(INK,1.7)]:
                for points in lines: line(points,color,width)
        im=im.resize((size,size),Image.Resampling.LANCZOS)
        pixels=[]
        for r,g,b,a in im.getdata():pixels.append((a<<24)|((r*a//255)<<16)|((g*a//255)<<8)|(b*a//255))
        entries.append((size,struct.pack("<9I",36,0xfffd0002,size,1,size,size,*tip,0)+struct.pack("<"+"I"*len(pixels),*pixels)))
    result=struct.pack("<4I",0x72756358,16,0x10000,len(entries))
    offset=len(result)+12*len(entries)
    for size,data in entries:
        result+=struct.pack("<3I",0xfffd0002,size,offset)
        offset+=len(data)
    return result+b"".join(data for _,data in entries)

def write(z):
    for scale in (100,133,167,200):
        for dark in (False,True): decorations(z,scale,dark)
    for kind,names in {"arrow":"left_ptr default arrow top_left_arrow","text":"xterm text",
        "horizontal":"left_side right_side sb_h_double_arrow ew-resize w-resize e-resize",
        "vertical":"top_side bottom_side sb_v_double_arrow ns-resize n-resize s-resize",
        "nwse":"top_left_corner bottom_right_corner nwse-resize nw-resize se-resize",
        "nesw":"top_right_corner bottom_left_corner nesw-resize ne-resize sw-resize"}.items():
        data=cursor_images(kind)
        for name in names.split():z.writestr("share/icons/Xdesx/cursors/"+name,data)
