static int notify_state(Panel *p){
    char *data=NULL;int unread=0,version=0;
    p->notify_access=p->notify_ready=0;p->notify_names=1;
    if(g_file_get_contents("run/notify-state",&data,NULL,NULL))
        sscanf(data,"%d %d %d %d %d",&p->notify_access,&p->notify_ready,&p->notify_names,&unread,&version);
    g_free(data);return unread;
}
static gchar *notification_rows(void){
    int fd=menu_connect();
    if(fd<0)return NULL;
    struct timeval timeout={1,0};setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));
    const char request[]="notify-list\n";
    GString *data=g_string_new(NULL);
    char buf[8192];ssize_t count=-1;
    if(send(fd,request,sizeof(request)-1,MSG_NOSIGNAL)==sizeof(request)-1){
        while((count=recv(fd,buf,sizeof(buf),0))>0 && data->len<4*1024*1024)
            g_string_append_len(data,buf,count);
    }
    close(fd);
    if(count!=0){g_string_free(data,TRUE);return NULL;}
    return g_string_free(data,FALSE);
}
static void notification_command(GtkWidget *button,gpointer unused){
    action(g_object_get_data(G_OBJECT(button),"notify-action"));
}
static GtkWidget *notify_button(const char *label,const char *op){
    GtkWidget *b=gtk_button_new_with_label(label);
    g_object_set_data_full(G_OBJECT(b),"notify-action",g_strdup(op),g_free);
    g_signal_connect(b,"clicked",G_CALLBACK(notification_command),NULL);return b;
}
static GtkWidget *notification_label(const char *text){
    GtkWidget *v=gtk_label_new(text);
    gtk_label_set_xalign(GTK_LABEL(v),0);gtk_label_set_line_wrap(GTK_LABEL(v),TRUE);
    gtk_label_set_line_wrap_mode(GTK_LABEL(v),PANGO_WRAP_WORD_CHAR);return v;
}
static void notification_contents(Panel *p){
    GtkWidget *old=gtk_bin_get_child(GTK_BIN(p->notification_popup));
    if(old)gtk_widget_destroy(old);
    GtkWidget *box=gtk_box_new(GTK_ORIENTATION_VERTICAL,8);
    gtk_container_set_border_width(GTK_CONTAINER(box),12);
    gtk_container_add(GTK_CONTAINER(p->notification_popup),box);
    GtkWidget *header=gtk_box_new(GTK_ORIENTATION_HORIZONTAL,8);
    gtk_box_pack_start(GTK_BOX(box),header,FALSE,FALSE,0);
    gtk_box_pack_start(GTK_BOX(header),notification_label(T("通知中心")),TRUE,TRUE,0);
    if(!p->notify_ready){
        gtk_box_pack_start(GTK_BOX(box),notify_button(p->notify_access?T("重新连接通知服务"):T("在手机上授权通知访问"),
            p->notify_access?"notify-rebind":"notify-access"),FALSE,FALSE,0);
    }else{
        gchar *data=notification_rows();
        if(!data || !*data) gtk_box_pack_start(GTK_BOX(box),notification_label(data?T("暂无通知"):T("暂时无法读取通知")),FALSE,FALSE,0);
        else{
            GtkWidget *scroll=gtk_scrolled_window_new(NULL,NULL),*items=gtk_box_new(GTK_ORIENTATION_VERTICAL,6);
            gtk_widget_set_size_request(scroll,MIN(wnck_screen_get_width(p->screen)*3/4,320*p->scale/100),
                MIN(wnck_screen_get_height(p->screen)/2,330*p->scale/100));
            gtk_scrolled_window_set_policy(GTK_SCROLLED_WINDOW(scroll),GTK_POLICY_NEVER,GTK_POLICY_AUTOMATIC);
            gtk_container_add(GTK_CONTAINER(scroll),items);gtk_box_pack_start(GTK_BOX(box),scroll,TRUE,TRUE,0);
            gchar **rows=g_strsplit(data,"\n",-1),*group=NULL;
            gboolean clearable=FALSE;
            for(int i=0;rows[i];i++){
                gchar **v=g_strsplit(rows[i],"\t",6);
                if(g_strv_length(v)==6){
                    if(g_strcmp0(group,v[0])){
                        g_free(group);group=g_strdup(v[0]);
                        gtk_box_pack_start(GTK_BOX(items),notification_label(v[1]),FALSE,FALSE,2);
                    }
                    GtkWidget *row=gtk_box_new(GTK_ORIENTATION_HORIZONTAL,8);
                    gchar *text=*v[4] && *v[5]?g_strconcat(v[4],"\n",v[5],NULL):g_strdup(*v[4]?v[4]:*v[5]?v[5]:T("通知"));
                    gtk_box_pack_start(GTK_BOX(row),notification_label(text),TRUE,TRUE,4);g_free(text);
                    if(!strcmp(v[3],"1")){
                        clearable=TRUE;gchar *op=g_strconcat("notify-",v[2],NULL);
                        GtkWidget *remove=notify_button("×",op);g_free(op);gtk_widget_set_tooltip_text(remove,T("清除此通知"));
                        gtk_box_pack_end(GTK_BOX(row),remove,FALSE,FALSE,0);
                    }
                    gtk_box_pack_start(GTK_BOX(items),row,FALSE,FALSE,0);
                }
                g_strfreev(v);
            }
            if(clearable)gtk_box_pack_end(GTK_BOX(header),notify_button(T("清除全部"),"notify-clear"),FALSE,FALSE,0);
            g_free(group);g_strfreev(rows);
        }
        if(data && *data && p->notify_unread)action("notify-seen");
        g_free(data);
    }
    gtk_widget_show_all(box);
}
static void notification_refresh(Panel *p){
    p->notify_unread=notify_state(p);
    gtk_widget_queue_draw(p->bell_art);
    if(p->notification_popup)notification_contents(p);
}
static gboolean bell_draw(GtkWidget *art,cairo_t *cr,Panel *p){
    double scale=MIN(gtk_widget_get_allocated_width(art),gtk_widget_get_allocated_height(art))/22.;
    cairo_translate(cr,(gtk_widget_get_allocated_width(art)-22*scale)/2,(gtk_widget_get_allocated_height(art)-22*scale)/2);
    cairo_scale(cr,scale,scale);cairo_set_line_width(cr,1.6);if(p->dark)cairo_set_source_rgb(cr,.93,.94,.96);else cairo_set_source_rgb(cr,.12,.14,.16);
    cairo_move_to(cr,4,16);cairo_curve_to(cr,8,13,4,5,11,5);cairo_curve_to(cr,18,5,14,13,18,16);cairo_close_path(cr);cairo_stroke(cr);
    cairo_arc(cr,11,17,2,0,G_PI);cairo_stroke(cr);
    if(p->notify_unread){cairo_set_source_rgb(cr,.82,.23,.21);cairo_arc(cr,17,5,3,0,2*G_PI);cairo_fill(cr);}
    return FALSE;
}
static void show_notifications(GtkWidget *button,Panel *p){
    if(p->notification_popup){gtk_widget_hide(p->notification_popup);return;}
    if(p->launcher)gtk_widget_hide(p->launcher);
    GtkWidget *w=gtk_window_new(GTK_WINDOW_TOPLEVEL);
    p->notification_popup=w;
    gtk_widget_set_name(w,"xdesx-notifications");
    gtk_window_set_title(GTK_WINDOW(w),T("通知中心"));
    g_object_add_weak_pointer(G_OBJECT(w),(gpointer*)&p->notification_popup);
    notification_refresh(p);
    XfcePanelPlugin *plugin=XFCE_PANEL_PLUGIN(gtk_widget_get_ancestor(button,XFCE_TYPE_PANEL_PLUGIN));
    xfce_panel_plugin_popup_window(plugin,GTK_WINDOW(w),button);
    g_signal_connect_after(w,"hide",G_CALLBACK(gtk_widget_destroy),NULL);
}
