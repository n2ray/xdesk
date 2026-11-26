#include <cairo-xlib.h>

// Keep the rendered image as the X root pixmap. No drawing process runs between changes.
static void paint_wallpaper(Panel *p) {
    Display *d=GDK_DISPLAY_XDISPLAY(gdk_display_get_default());
    Window root=DefaultRootWindow(d);
    int w=wnck_screen_get_width(p->screen), h=wnck_screen_get_height(p->screen);
    if (w<=0 || h<=0) return;
    gchar *mode=NULL;
    g_file_get_contents("wallpaper-mode",&mode,NULL,NULL);
    gboolean photo=mode && (!strcmp(mode,"fit") || !strcmp(mode,"fill"));
    GdkPixbuf *image=photo?gdk_pixbuf_new_from_file_at_scale("wallpaper.jpg",w,h,TRUE,NULL):NULL;
    Pixmap pix=XCreatePixmap(d,root,image?w:1,image?h:1,DefaultDepth(d,DefaultScreen(d)));
    cairo_surface_t *s=cairo_xlib_surface_create(d,pix,DefaultVisual(d,DefaultScreen(d)),image?w:1,image?h:1);
    cairo_t *cr=cairo_create(s);
    if(mode && !strcmp(mode,"slate")) cairo_set_source_rgb(cr,.17,.21,.25);
    else if(mode && !strcmp(mode,"blue")) cairo_set_source_rgb(cr,.20,.32,.46);
    else cairo_set_source_rgb(cr,.85,.847,.839);
    cairo_paint(cr);
    if(image){
        double iw=gdk_pixbuf_get_width(image),ih=gdk_pixbuf_get_height(image);
        double scale=!strcmp(mode,"fill")?MAX(w/iw,h/ih):MIN(w/iw,h/ih);
        cairo_translate(cr,(w-iw*scale)/2,(h-ih*scale)/2);
        cairo_scale(cr,scale,scale);gdk_cairo_set_source_pixbuf(cr,image,0,0);cairo_paint(cr);
        g_object_unref(image);
    }
    cairo_destroy(cr);cairo_surface_destroy(s);
    XChangeProperty(d,root,XInternAtom(d,"_XROOTPMAP_ID",False),XA_PIXMAP,32,PropModeReplace,(unsigned char*)&pix,1);
    XSetWindowBackgroundPixmap(d,root,pix);XClearWindow(d,root);XFlush(d);
    if(p->wallpaper) XFreePixmap(d,p->wallpaper);
    p->wallpaper=pix;g_free(mode);
}
static void wallpaper_size(GdkScreen *screen,Panel *p){paint_wallpaper(p);}
static void wallpaper_choice(GtkButton *button,Panel *p){
    const char *mode=g_object_get_data(G_OBJECT(button),"mode");
    if(!strcmp(mode,"pick")) {action("wallpaper-pick");return;}
    if((!strcmp(mode,"fit")||!strcmp(mode,"fill")) && !g_file_test("wallpaper.jpg",G_FILE_TEST_IS_REGULAR)){
        action("wallpaper-pick");return;
    }
    if(g_file_set_contents("wallpaper-mode",mode,-1,NULL)) paint_wallpaper(p);
}
static void wallpaper_controls(GtkWidget *page,Panel *p){
    const char *names[]={T("默认灰"),T("石墨"),T("海蓝"),T("从手机选择图片…"),T("适应"),T("填满")};
    const char *modes[]={"default","slate","blue","pick","fit","fill"};
    GtkWidget *row=NULL;
    for(int i=0;i<6;i++){
        if(i%3==0){row=gtk_box_new(GTK_ORIENTATION_HORIZONTAL,6);gtk_box_pack_start(GTK_BOX(page),row,FALSE,FALSE,2);}
        GtkWidget *b=gtk_button_new_with_label(names[i]);
        g_object_set_data(G_OBJECT(b),"mode",(gpointer)modes[i]);
        g_signal_connect(b,"clicked",G_CALLBACK(wallpaper_choice),p);
        gtk_box_pack_start(GTK_BOX(row),b,FALSE,FALSE,0);
    }
}
