// The class group is the Android package or the Linux application's WM_CLASS.
static void task_click(GtkButton *button, Panel *p) {
    GPtrArray *windows=g_object_get_data(G_OBJECT(button),"windows");
    if (windows->len==1) {
        WnckWindow *w=g_ptr_array_index(windows,0);
        if (wnck_window_is_active(w)) wnck_window_minimize(w);
        else activate_window(GTK_WIDGET(button),w);
        return;
    }
    GtkWidget *menu=gtk_menu_new();
    for (guint i=0;i<windows->len;i++) {
        WnckWindow *w=g_ptr_array_index(windows,i);
        g_signal_connect_object(item(menu,wnck_window_get_name(w)),"activate",G_CALLBACK(activate_window),w,0);
    }
    popup(menu,GTK_WIDGET(button));
}
static gboolean task_context(GtkWidget *button,GdkEventButton *event,Panel *p) {
    if (event->button!=3) return FALSE;
    GPtrArray *windows=g_object_get_data(G_OBJECT(button),"windows");
    WnckWindow *w=g_ptr_array_index(windows,0);
    GtkWidget *menu=wnck_action_menu_new(w);
    Display *d=GDK_DISPLAY_XDISPLAY(gdk_display_get_default());
    Atom type; int format; unsigned long count,remaining; unsigned char *key=NULL;
    if (XGetWindowProperty(d,wnck_window_get_xid(w),XInternAtom(d,"_XDESX_KEY",False),0,8,
            False,XA_STRING,&type,&format,&count,&remaining,&key)==Success && key && count==32) {
        char *op=g_strdup_printf("full-%.*s",32,key);
        command(menu,T("在外屏原生全屏打开"),op);
        g_free(op);
    }
    if (key) XFree(key);
    popup(menu,button);
    return TRUE;
}
static gboolean scroll_tasks(GtkWidget *widget,GdkEventScroll *e,gpointer unused) {
    GtkAdjustment *adj=gtk_scrolled_window_get_hadjustment(GTK_SCROLLED_WINDOW(widget));
    double dx=0,dy=0;
    if (!gdk_event_get_scroll_deltas((GdkEvent*)e,&dx,&dy))
        dy=(e->direction==GDK_SCROLL_UP || e->direction==GDK_SCROLL_LEFT)?-1:1;
    gtk_adjustment_set_value(adj,gtk_adjustment_get_value(adj)+(dx?dx:dy)*48);
    return TRUE;
}
static gint task_order(gconstpointer a,gconstpointer b) {
    return wnck_window_get_sort_order((WnckWindow*)a)-wnck_window_get_sort_order((WnckWindow*)b);
}
static void highlight_task(GtkWidget *button,gpointer active) {
    GPtrArray *members=g_object_get_data(G_OBJECT(button),"windows");
    GtkStyleContext *style=gtk_widget_get_style_context(button);
    if (active && g_ptr_array_find(members,active,NULL)) gtk_style_context_add_class(style,"active");
    else gtk_style_context_remove_class(style,"active");
}
static void active_task_changed(WnckScreen *screen,WnckWindow *previous,Panel *p) {
    update_fullscreen(p);
    gtk_container_foreach(GTK_CONTAINER(p->tasks),highlight_task,wnck_screen_get_active_window(screen));
}
static gboolean rebuild_tasks(gpointer data) {
    Panel *p=data;
    p->tasks_idle=0;
    gtk_container_foreach(GTK_CONTAINER(p->tasks),(GtkCallback)gtk_widget_destroy,NULL);
    GHashTable *groups=g_hash_table_new(g_direct_hash,g_direct_equal);
    GList *sorted=g_list_sort(g_list_copy(wnck_screen_get_windows(p->screen)),task_order);
    for (GList *l=sorted;l;l=l->next) {
        WnckWindow *w=l->data;
        if (wnck_window_is_skip_tasklist(w)) continue;
        WnckClassGroup *group=wnck_window_get_class_group(w);
        gpointer id=(group && *wnck_class_group_get_id(group))?(gpointer)group:(gpointer)w;
        GtkWidget *btn=g_hash_table_lookup(groups,id);
        if (!btn) {
            btn=gtk_button_new();
            gtk_widget_set_focus_on_click(btn,FALSE);
            gtk_style_context_add_class(gtk_widget_get_style_context(btn),"task");
            GPtrArray *members=g_ptr_array_new_with_free_func(g_object_unref);
            g_object_set_data_full(G_OBJECT(btn),"windows",members,(GDestroyNotify)g_ptr_array_unref);
            GtkWidget *box=gtk_box_new(GTK_ORIENTATION_HORIZONTAL,5);
            GdkPixbuf *original=wnck_window_get_icon(w);
            int side=MAX(16,(24*p->scale+50)/100);
            if (original) {
                GdkPixbuf *icon=gdk_pixbuf_scale_simple(original,side,side,GDK_INTERP_BILINEAR);
                gtk_box_pack_start(GTK_BOX(box),gtk_image_new_from_pixbuf(icon),FALSE,FALSE,0);
                g_object_unref(icon);
            }
            const char *title=wnck_window_get_name(w);
            GtkWidget *label=gtk_label_new(title);
            gtk_label_set_max_width_chars(GTK_LABEL(label),16);
            gtk_label_set_ellipsize(GTK_LABEL(label),PANGO_ELLIPSIZE_END);
            gtk_box_pack_start(GTK_BOX(box),label,FALSE,FALSE,0);
            g_object_set_data(G_OBJECT(btn),"label",label);
            gtk_container_add(GTK_CONTAINER(btn),box);
            gtk_widget_set_tooltip_text(btn,title);
            g_signal_connect(btn,"clicked",G_CALLBACK(task_click),p);
            g_signal_connect(btn,"button-press-event",G_CALLBACK(task_context),p);
            gtk_box_pack_start(GTK_BOX(p->tasks),btn,FALSE,FALSE,0);
            g_hash_table_insert(groups,id,btn);
        }
        GPtrArray *members=g_object_get_data(G_OBJECT(btn),"windows");
        g_ptr_array_add(members,g_object_ref(w));
        if (members->len>1) {
            gchar *label=g_strdup_printf("%s (%u)",wnck_class_group_get_name(group),members->len);
            gtk_label_set_text(g_object_get_data(G_OBJECT(btn),"label"),label);
            gtk_widget_set_tooltip_text(btn,label);
            g_free(label);
        }

    }
    gboolean any=g_hash_table_size(groups)>0;
    g_hash_table_unref(groups);
    g_list_free(sorted);
    gtk_container_foreach(GTK_CONTAINER(p->tasks),highlight_task,wnck_screen_get_active_window(p->screen));
    gtk_widget_show_all(p->tasks);
    gtk_widget_set_visible(p->separator,any);
    gtk_widget_set_visible(p->task_scroll,any);
    return G_SOURCE_REMOVE;
}
static void schedule_tasks(Panel *p) {
    if (!p->tasks_idle) p->tasks_idle=g_idle_add(rebuild_tasks,p);
}
static void task_list_changed(WnckScreen *s,WnckWindow *w,Panel *p) {
    update_fullscreen(p);
    schedule_tasks(p);
}
static void task_state(WnckWindow *w,WnckWindowState changed,WnckWindowState state,Panel *p) {
    if (changed & WNCK_WINDOW_STATE_SKIP_TASKLIST) schedule_tasks(p);
}
static void watch_task(WnckScreen *s,WnckWindow *w,Panel *p) {
    g_signal_connect_swapped(w,"name-changed",G_CALLBACK(schedule_tasks),p);
    g_signal_connect_swapped(w,"icon-changed",G_CALLBACK(schedule_tasks),p);
    g_signal_connect_swapped(w,"class-changed",G_CALLBACK(schedule_tasks),p);
    g_signal_connect(w,"state-changed",G_CALLBACK(task_state),p);
    schedule_tasks(p);
}
