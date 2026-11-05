import android.os.SystemClock; import android.view.*; import java.lang.reflect.Method;
public class Hdrag {
  static Object im; static Method inj, sd; static int D;
  static void ev(int b,float x,float y) throws Exception { long n=SystemClock.uptimeMillis();
    MotionEvent.PointerProperties p=new MotionEvent.PointerProperties(); p.id=0; p.toolType=MotionEvent.TOOL_TYPE_MOUSE;
    MotionEvent.PointerCoords c=new MotionEvent.PointerCoords(); c.x=x;c.y=y;c.size=1f;c.pressure=b!=0?1f:0f;
    MotionEvent e=MotionEvent.obtain(n,n,MotionEvent.ACTION_HOVER_MOVE,1,new MotionEvent.PointerProperties[]{p},new MotionEvent.PointerCoords[]{c},0,b,1f,1f,-1,0,InputDevice.SOURCE_MOUSE,0);
    sd.invoke(e,D); inj.invoke(im,e,0); e.recycle(); }
  public static void main(String[] a) throws Exception {
    Class<?> g=Class.forName("android.hardware.input.InputManagerGlobal"); im=g.getMethod("getInstance").invoke(null);
    inj=g.getMethod("injectInputEvent",InputEvent.class,int.class); sd=MotionEvent.class.getMethod("setDisplayId",int.class);
    D=Integer.parseInt(a[0]); float x1=Float.parseFloat(a[1]),y1=Float.parseFloat(a[2]),x2=Float.parseFloat(a[3]),y2=Float.parseFloat(a[4]); long hold=Long.parseLong(a[5]);
    ev(0,x1,y1); Thread.sleep(400); ev(1,x1,y1); Thread.sleep(200);
    for(int i=1;i<=25;i++){ float t=i/25f; ev(1,x1+(x2-x1)*t,y1+(y2-y1)*t); Thread.sleep(40);} 
    Thread.sleep(hold); ev(0,x2,y2); Thread.sleep(100); ev(0,x2,y2); }
}
