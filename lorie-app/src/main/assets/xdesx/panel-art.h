static gboolean draw_logo(GtkWidget *w,cairo_t *cr,Panel *p) {
    double width=gtk_widget_get_allocated_width(w), size=.85*width, ink=.2*size, edge=ink/2, mid=size/2;
    double gap=(ink+1)/sqrt(2.);
    cairo_translate(cr,(width-size)/2.,(gtk_widget_get_allocated_height(w)-size)/2.);
    cairo_set_source_rgb(cr,.02,.02,.025); cairo_set_line_width(cr,ink);
    cairo_set_line_cap(cr,CAIRO_LINE_CAP_ROUND);
    cairo_move_to(cr,edge,edge); cairo_line_to(cr,size-edge,size-edge);
    cairo_move_to(cr,edge,size-edge); cairo_line_to(cr,mid-gap,mid+gap);
    cairo_move_to(cr,mid+gap,mid-gap); cairo_line_to(cr,size-edge,edge); cairo_stroke(cr);
    return FALSE;
}
static gboolean draw_status(GtkWidget *w,cairo_t *cr,Panel *p) {
    double k=p->scale/100.;
    if(k<=0) return FALSE;
    cairo_translate(cr,0,(gtk_widget_get_allocated_height(w)-20*k)/2);
    cairo_scale(cr,k,k);
    cairo_set_source_rgb(cr,.1,.1,.12);cairo_set_line_width(cr,1.6);cairo_set_line_cap(cr,CAIRO_LINE_CAP_ROUND);
    if (g_str_has_prefix(p->network,"Wi-Fi")) {
        for (int i=4;i<=12;i+=4) { cairo_arc(cr,12,17,i,-2.36,-.78); cairo_stroke(cr); }
        cairo_arc(cr,12,17,1,0,2*G_PI);cairo_fill(cr);
    } else if (g_str_has_prefix(p->network,"移动")) {
        for(int i=0;i<4;i++) cairo_rectangle(cr,3+i*5,14-i*3,3,4+i*3);
        cairo_fill(cr);
    } else {
        cairo_rectangle(cr,3,4,17,10); cairo_move_to(cr,11,14);cairo_line_to(cr,11,17);
        cairo_move_to(cr,6,17);cairo_line_to(cr,17,17);cairo_stroke(cr);
        if(g_str_has_prefix(p->network,"离线")) { cairo_move_to(cr,2,2);cairo_line_to(cr,22,18);cairo_stroke(cr); }
    }
    cairo_rectangle(cr,37,5,21,11);cairo_stroke(cr);
    cairo_rectangle(cr,59,8,2,5);cairo_fill(cr);
    if (p->battery>=0) { cairo_rectangle(cr,39,7,17*p->battery/100.,7);cairo_fill(cr); }
    if(p->charging) { cairo_set_source_rgb(cr,.38,.42,.48);cairo_move_to(cr,49,3);cairo_line_to(cr,45,11);cairo_line_to(cr,50,10);cairo_line_to(cr,47,18);cairo_stroke(cr); }
    cairo_set_source_rgb(cr,.1,.1,.12);
    cairo_rectangle(cr,76,4,15,13);cairo_move_to(cr,76,8);cairo_line_to(cr,91,8);
    cairo_move_to(cr,80,2);cairo_line_to(cr,80,6);cairo_move_to(cr,87,2);cairo_line_to(cr,87,6);cairo_stroke(cr);
    return FALSE;
}
static void desktop_cursor(Panel *p) {
    Display *d=GDK_DISPLAY_XDISPLAY(gdk_display_get_default());
    int size=(24*p->scale+50)/100;
    XcursorSetTheme(d,"Xdesx"); XcursorSetDefaultSize(d,size);
    gdk_x11_display_set_cursor_theme(gdk_display_get_default(),"Xdesx",size);
    const char *names[]={"left_ptr","default","arrow","top_left_arrow","xterm","text","top_left_corner","top_right_corner",
        "bottom_left_corner","bottom_right_corner","top_side","bottom_side","left_side","right_side","ew-resize","ns-resize","nesw-resize","nwse-resize",NULL};
    for(int i=0;names[i];i++) {
        Cursor c=XcursorLibraryLoadCursor(d,names[i]);
        if (!c) continue;
        XFixesChangeCursorByName(d,c,names[i]);
        if (i==0) XDefineCursor(d,DefaultRootWindow(d),c);
        XFreeCursor(d,c);
    }
    XFlush(d);
}
