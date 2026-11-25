// Track normal geometry independently of maximization. Coalesce configure bursts.
typedef struct {
    Panel *panel;
    WnckWindow *window;
    char *file;
    guint timer;
    int x,y,w,h;
} Placement;
static gboolean save_position(gpointer data) {
    Placement *t=data;
    t->timer=0;
    if (!t->panel->remember || wnck_window_is_fullscreen(t->window) || wnck_window_is_minimized(t->window)) return G_SOURCE_REMOVE;
    gboolean max=wnck_window_is_maximized(t->window);
    if (!max) wnck_window_get_geometry(t->window,&t->x,&t->y,&t->w,&t->h);
    if (t->w>0 && t->h>0) {
        char line[128];
        snprintf(line,sizeof(line),"%d %d %d %d %d",t->x,t->y,t->w,t->h,max);
        g_file_set_contents(t->file,line,-1,NULL);
    }
    return G_SOURCE_REMOVE;
}
static void position_changed(WnckWindow *w,Placement *t) {
    if (t->timer) g_source_remove(t->timer);
    t->timer=g_timeout_add(450,save_position,t);
}
static void state_change(WnckWindow *w,WnckWindowState changed,WnckWindowState state,Placement *t) {
    if(changed&WNCK_WINDOW_STATE_FULLSCREEN) update_fullscreen(t->panel);
    position_changed(w,t);
}
static void free_position(gpointer data) {
    Placement *t=data;
    if (t->timer) g_source_remove(t->timer);
    g_free(t->file); g_free(t);
}
static void watch_window(WnckScreen *screen,WnckWindow *w,Panel *p) {
    const char *klass=wnck_window_get_class_group_name(w);
    if (!klass || !*klass || wnck_window_get_window_type(w)!=WNCK_WINDOW_NORMAL || wnck_window_is_skip_tasklist(w)
        || g_object_get_data(G_OBJECT(w),"xdesx-position") || !p->profile[0]) return;
    char *hash=g_compute_checksum_for_string(G_CHECKSUM_SHA256,klass,-1);
    char *dir=g_build_filename("config","layout",p->profile,NULL);
    g_mkdir_with_parents(dir,0700);
    Placement *t=g_new0(Placement,1);
    t->panel=p; t->window=w; t->file=g_build_filename(dir,hash,NULL);
    g_free(hash);g_free(dir);
    wnck_window_get_geometry(w,&t->x,&t->y,&t->w,&t->h);
    char *line=NULL; int maximized=0;
    if (p->remember && g_file_get_contents(t->file,&line,NULL,NULL) &&
        sscanf(line,"%d %d %d %d %d",&t->x,&t->y,&t->w,&t->h,&maximized)==5) {
        int width=wnck_screen_get_width(screen),height=wnck_screen_get_height(screen);
        int top=36*p->scale/100;
        t->w=CLAMP(t->w,120,width); t->h=CLAMP(t->h,100,MAX(100,height-top));
        t->x=CLAMP(t->x,0,MAX(0,width-t->w)); t->y=CLAMP(t->y,top,MAX(top,height-t->h));
        wnck_window_set_geometry(w,WNCK_WINDOW_GRAVITY_STATIC,WNCK_WINDOW_CHANGE_X|WNCK_WINDOW_CHANGE_Y|WNCK_WINDOW_CHANGE_WIDTH|WNCK_WINDOW_CHANGE_HEIGHT,t->x,t->y,t->w,t->h);
        if (maximized) wnck_window_maximize(w);
    }
    g_free(line);
    g_object_set_data_full(G_OBJECT(w),"xdesx-position",t,free_position);
    g_signal_connect(w,"geometry-changed",G_CALLBACK(position_changed),t);
    g_signal_connect(w,"state-changed",G_CALLBACK(state_change),t);
}
static void stop_tracking(Panel *p) {
    for (GList *l=wnck_screen_get_windows(p->screen);l;l=l->next) {
        Placement *t=g_object_get_data(G_OBJECT(l->data),"xdesx-position");
        if(t) { g_signal_handlers_disconnect_by_data(l->data,t);g_object_set_data(G_OBJECT(l->data),"xdesx-position",NULL); }
    }
}
