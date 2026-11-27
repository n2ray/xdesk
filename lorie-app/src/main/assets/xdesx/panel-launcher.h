// The launcher and its thumbnails live only while the menu window is open.
typedef struct {
    Panel *panel;
    GtkWidget *popup, *grid, *search, *empty;
    int tab;
    GKeyFile *favorites;
} Launcher;

static gboolean matches_app(GtkFlowBoxChild *child,gpointer data) {
    Launcher *l=data;
    GtkWidget *tile=gtk_bin_get_child(GTK_BIN(child));
    int tab=l->tab;
    if(tab==4 && !g_key_file_has_key(l->favorites,"apps",g_object_get_data(G_OBJECT(tile),"favorite-key"),NULL)) return FALSE;
    if(tab && tab!=4 && tab!=GPOINTER_TO_INT(g_object_get_data(G_OBJECT(tile),"category"))) return FALSE;
    gchar *query=g_utf8_casefold(gtk_entry_get_text(GTK_ENTRY(l->search)),-1);
    gboolean match=strstr(g_object_get_data(G_OBJECT(tile),"search-name"),query)!=NULL;
    g_free(query);
    return match;
}
static void filter_apps(GtkWidget *widget,Launcher *l) {
    if(!l->grid)return;
    gtk_flow_box_invalidate_filter(GTK_FLOW_BOX(l->grid));
    GList *children=gtk_container_get_children(GTK_CONTAINER(l->grid));
    gboolean found=FALSE;
    for(GList *c=children;c;c=c->next)if(gtk_widget_get_child_visible(c->data)){found=TRUE;break;}
    g_list_free(children);
    gtk_label_set_text(GTK_LABEL(l->empty),l->tab==4 && !*gtk_entry_get_text(GTK_ENTRY(l->search))?T("暂无收藏。右键点按应用即可添加。"):T("没有匹配的应用"));
    gtk_widget_set_visible(l->empty,!found);
}
static void choose_tab(GtkToggleButton *button, Launcher *l) {
    if (!gtk_toggle_button_get_active(button)) return;
    l->tab=GPOINTER_TO_INT(g_object_get_data(G_OBJECT(button),"tab"));
    filter_apps(NULL,l);
}
static void toggle_favorite(GtkButton *button,GtkWidget *tile) {
    Launcher *l=g_object_get_data(G_OBJECT(tile),"launcher");
    const char *key=g_object_get_data(G_OBJECT(tile),"favorite-key");
    if(g_key_file_has_key(l->favorites,"apps",key,NULL)) g_key_file_remove_key(l->favorites,"apps",key,NULL);
    else g_key_file_set_boolean(l->favorites,"apps",key,TRUE);
    g_key_file_save_to_file(l->favorites,"config/favorites.ini",NULL);
    gtk_widget_destroy(gtk_widget_get_ancestor(GTK_WIDGET(button),GTK_TYPE_POPOVER));
    filter_apps(NULL,l);
}
static gboolean favorite_menu(GtkWidget *tile,GdkEventButton *event,Launcher *l) {
    if(event->button!=3)return FALSE;
    GtkWidget *menu=gtk_popover_new(tile);
    const char *key=g_object_get_data(G_OBJECT(tile),"favorite-key");
    GtkWidget *b=gtk_button_new_with_label(g_key_file_has_key(l->favorites,"apps",key,NULL)?T("移除收藏"):T("添加到收藏"));
    gtk_container_add(GTK_CONTAINER(menu),b);
    g_signal_connect(b,"clicked",G_CALLBACK(toggle_favorite),tile);
    g_signal_connect(menu,"closed",G_CALLBACK(gtk_widget_destroy),NULL);
    gtk_widget_show_all(menu);
    return TRUE;
}
static void launcher_free(gpointer data) {
    Launcher *l=data;
    g_key_file_unref(l->favorites);
    g_free(l);
}
static void open_tile(GtkButton *button,Launcher *l) {
    const char *id=g_object_get_data(G_OBJECT(button),"app-id");
    if(!strcmp(id,"mac-manager"))mac_manager_open(l->panel);
    else if(g_str_has_prefix(id,"linux-")) action(id);
    else launch(NULL,(gpointer)id);
    gtk_widget_hide(l->popup);
}
static void app_tile(Launcher *l,const char *name,const char *id,const char *icon,int category,const char *identity) {
    int side=40*l->panel->scale/100;
    GtkWidget *tile=gtk_button_new(), *box=gtk_box_new(GTK_ORIENTATION_VERTICAL,4);
    gtk_style_context_add_class(gtk_widget_get_style_context(tile),"app-tile");
    gtk_button_set_relief(GTK_BUTTON(tile),GTK_RELIEF_NONE);
    GdkPixbuf *image=icon && g_path_is_absolute(icon)?gdk_pixbuf_new_from_file_at_scale(icon,side,side,TRUE,NULL):NULL;
    GtkWidget *picture=image?gtk_image_new_from_pixbuf(image):gtk_image_new_from_icon_name(category==2?"utilities-terminal":"computer",GTK_ICON_SIZE_DIALOG);
    gtk_image_set_pixel_size(GTK_IMAGE(picture),side);
    if(image) g_object_unref(image);
    GtkWidget *label=gtk_label_new(name);
    gtk_label_set_max_width_chars(GTK_LABEL(label),10);
    gtk_label_set_ellipsize(GTK_LABEL(label),PANGO_ELLIPSIZE_END);
    gtk_widget_set_tooltip_text(tile,name);
    gtk_box_pack_start(GTK_BOX(box),picture,TRUE,TRUE,0);
    gtk_box_pack_start(GTK_BOX(box),label,FALSE,FALSE,0);
    gtk_container_add(GTK_CONTAINER(tile),box);
    g_object_set_data_full(G_OBJECT(tile),"search-name",g_utf8_casefold(name,-1),g_free);
    g_object_set_data_full(G_OBJECT(tile),"app-id",g_strdup(id),g_free);
    g_object_set_data(G_OBJECT(tile),"category",GINT_TO_POINTER(category));
    g_object_set_data(G_OBJECT(tile),"launcher",l);
    g_object_set_data_full(G_OBJECT(tile),"favorite-key",g_compute_checksum_for_string(G_CHECKSUM_SHA256,identity,-1),g_free);
    g_signal_connect(tile,"button-press-event",G_CALLBACK(favorite_menu),l);
    g_signal_connect(tile,"clicked",G_CALLBACK(open_tile),l);
    gtk_flow_box_insert(GTK_FLOW_BOX(l->grid),tile,-1);
}
static void first_result(GtkEntry *entry,Launcher *l) {
    GList *items=gtk_container_get_children(GTK_CONTAINER(l->grid));
    for(GList *i=items;i;i=i->next) if(gtk_widget_get_child_visible(i->data)){
        gtk_button_clicked(GTK_BUTTON(gtk_bin_get_child(GTK_BIN(i->data)))); break;
    }
    g_list_free(items);
}
static void launcher_settings(GtkButton *button,Launcher *l) {
    Panel *p=l->panel;
    gtk_widget_hide(l->popup);
    settings_open(NULL,p);
}
static void launcher_exit(GtkButton *b,Launcher *l) {
    gtk_widget_hide(l->popup);
    GtkWidget *d=gtk_message_dialog_new(NULL,GTK_DIALOG_MODAL,GTK_MESSAGE_QUESTION,GTK_BUTTONS_NONE,"%s",T("结束外屏桌面？"));
    gtk_message_dialog_format_secondary_text(GTK_MESSAGE_DIALOG(d),"%s",T("所有桌面窗口都将关闭。请先保存正在进行的工作。"));
    gtk_dialog_add_buttons(GTK_DIALOG(d),T("取消"),GTK_RESPONSE_CANCEL,T("结束桌面"),GTK_RESPONSE_ACCEPT,NULL);
    gtk_dialog_set_default_response(GTK_DIALOG(d),GTK_RESPONSE_CANCEL);
    if(gtk_dialog_run(GTK_DIALOG(d))==GTK_RESPONSE_ACCEPT) action("exit-desktop");
    gtk_widget_destroy(d);
}
static void main_menu(GtkWidget *button,Panel *p) {
    if(p->launcher){gtk_widget_hide(p->launcher);return;}
    if(p->notification_popup)gtk_widget_hide(p->notification_popup);
    Launcher *l=g_new0(Launcher,1);
    l->panel=p; l->popup=gtk_window_new(GTK_WINDOW_TOPLEVEL);
    p->launcher=l->popup;
    gtk_widget_set_name(l->popup,"xdesx-launcher");
    g_object_add_weak_pointer(G_OBJECT(l->popup),(gpointer*)&p->launcher);
    l->favorites=g_key_file_new();
    g_key_file_load_from_file(l->favorites,"config/favorites.ini",G_KEY_FILE_NONE,NULL);
    gtk_window_set_title(GTK_WINDOW(l->popup),T("xdesx 菜单"));
    g_object_set_data_full(G_OBJECT(l->popup),"launcher",l,launcher_free);
    GtkWidget *box=gtk_box_new(GTK_ORIENTATION_VERTICAL,8), *row=gtk_box_new(GTK_ORIENTATION_HORIZONTAL,6);
    gtk_container_set_border_width(GTK_CONTAINER(box),12);
    l->search=gtk_search_entry_new();
    gtk_entry_set_placeholder_text(GTK_ENTRY(l->search),T("搜索应用"));
    gtk_box_pack_start(GTK_BOX(box),l->search,FALSE,FALSE,0);
    const char *categories[]={T("全部"),T("收藏"),T("安卓"),"Linux","Mac"};
    const int filters[]={0,4,1,2,3};
    GtkRadioButton *group=NULL;
    for(int i=0;i<5;i++){
        GtkWidget *tab=gtk_radio_button_new_with_label_from_widget(group,categories[i]);
        if(!group)group=GTK_RADIO_BUTTON(tab);
        gtk_toggle_button_set_mode(GTK_TOGGLE_BUTTON(tab),FALSE);
        g_object_set_data(G_OBJECT(tab),"tab",GINT_TO_POINTER(filters[i]));
        g_signal_connect(tab,"toggled",G_CALLBACK(choose_tab),l);
        gtk_box_pack_start(GTK_BOX(row),tab,TRUE,TRUE,0);
    }
    GtkWidget *section=gtk_box_new(GTK_ORIENTATION_VERTICAL,0);
    gtk_box_pack_start(GTK_BOX(box),section,TRUE,TRUE,0);
    gtk_widget_set_name(row,"category-tabs");
    gtk_box_pack_start(GTK_BOX(section),row,FALSE,FALSE,0);
    GtkWidget *content=gtk_box_new(GTK_ORIENTATION_VERTICAL,0);
    gtk_widget_set_name(content,"category-content");
    gtk_box_pack_start(GTK_BOX(section),content,TRUE,TRUE,0);
    GtkWidget *scroll=gtk_scrolled_window_new(NULL,NULL);
    gtk_scrolled_window_set_policy(GTK_SCROLLED_WINDOW(scroll),GTK_POLICY_NEVER,GTK_POLICY_AUTOMATIC);
    gtk_widget_set_size_request(scroll,MIN(wnck_screen_get_width(p->screen)-48,520*p->scale/100),MIN(wnck_screen_get_height(p->screen)*2/3,390*p->scale/100));
    l->empty=gtk_label_new(NULL);gtk_box_pack_start(GTK_BOX(content),l->empty,FALSE,FALSE,4);
    l->grid=gtk_flow_box_new();
    gtk_flow_box_set_selection_mode(GTK_FLOW_BOX(l->grid),GTK_SELECTION_NONE);
    gtk_flow_box_set_max_children_per_line(GTK_FLOW_BOX(l->grid),5);
    gtk_flow_box_set_min_children_per_line(GTK_FLOW_BOX(l->grid),2);
    gtk_flow_box_set_column_spacing(GTK_FLOW_BOX(l->grid),6*p->scale/100);
    gtk_flow_box_set_row_spacing(GTK_FLOW_BOX(l->grid),6*p->scale/100);
    gtk_container_set_border_width(GTK_CONTAINER(l->grid),6*p->scale/100);
    gtk_flow_box_set_homogeneous(GTK_FLOW_BOX(l->grid),TRUE);
    gtk_widget_set_valign(l->grid,GTK_ALIGN_START);
    gtk_container_add(GTK_CONTAINER(scroll),l->grid);
    gtk_box_pack_start(GTK_BOX(content),scroll,TRUE,TRUE,0);
    gchar *data=NULL;
    if(g_file_get_contents("apps.tsv",&data,NULL,NULL)){
        gchar **lines=g_strsplit(data,"\n",-1);
        for(int i=0;lines[i];i++){
            gchar **f=g_strsplit(lines[i],"\t",5);
            if(g_strv_length(f)>=4){
                gboolean mac=!strcmp(f[3],"mac");
                app_tile(l,mac?T("共享桌面"):f[1],mac?"mac-manager":f[0],f[2],mac?3:1,mac?"xdesx.mac":f[4]?f[4]:f[1]);
            }
            g_strfreev(f);
        }
        g_strfreev(lines);g_free(data);data=NULL;
    }
    if(g_file_get_contents("run/ubuntu-apps",&data,NULL,NULL)){
        gchar **lines=g_strsplit(data,"\n",-1);
        for(int i=0;lines[i];i++){
            gchar **f=g_strsplit(lines[i],"\t",3);
            if(f[0] && f[1] && *f[1]){gchar *id=g_strconcat("linux-",f[1],NULL);app_tile(l,f[0],id,f[2],2,id);g_free(id);}
            g_strfreev(f);
        }
        g_strfreev(lines);g_free(data);
    }
    row=gtk_box_new(GTK_ORIENTATION_HORIZONTAL,6);
    GtkWidget *settings=gtk_button_new_with_label(T("全局设置"));
    int gear_size=(16*p->scale+50)/100;
    GdkPixbuf *gear=gdk_pixbuf_new_from_file_at_scale("share/xdesx/settings.png",gear_size,gear_size,TRUE,NULL);
    if (gear) {
        gtk_button_set_image(GTK_BUTTON(settings),gtk_image_new_from_pixbuf(gear));
        gtk_button_set_always_show_image(GTK_BUTTON(settings),TRUE);
        g_object_unref(gear);
    }
    GtkWidget *exit=gtk_button_new_with_label(T("退出 xdesx"));
    GdkPixbuf *power=gdk_pixbuf_new_from_file_at_scale("share/xdesx/power.png",gear_size,gear_size,TRUE,NULL);
    if(power){
        gtk_button_set_image(GTK_BUTTON(exit),gtk_image_new_from_pixbuf(power));
        gtk_button_set_always_show_image(GTK_BUTTON(exit),TRUE);
        g_object_unref(power);
    }
    gtk_box_pack_start(GTK_BOX(row),settings,FALSE,FALSE,0);
    gtk_box_pack_end(GTK_BOX(row),exit,FALSE,FALSE,0);
    gtk_box_pack_end(GTK_BOX(box),row,FALSE,FALSE,0);
    gtk_container_add(GTK_CONTAINER(l->popup),box);
    gtk_flow_box_set_filter_func(GTK_FLOW_BOX(l->grid),matches_app,l,NULL);
    g_signal_connect(l->search,"search-changed",G_CALLBACK(filter_apps),l);
    g_signal_connect(l->search,"activate",G_CALLBACK(first_result),l);
    g_signal_connect(settings,"clicked",G_CALLBACK(launcher_settings),l);
    g_signal_connect(exit,"clicked",G_CALLBACK(launcher_exit),l);
    gtk_widget_show_all(box);
    filter_apps(NULL,l);
    gtk_widget_grab_focus(l->search);
    XfcePanelPlugin *plugin=XFCE_PANEL_PLUGIN(gtk_widget_get_ancestor(button,XFCE_TYPE_PANEL_PLUGIN));
    xfce_panel_plugin_popup_window(plugin,GTK_WINDOW(l->popup),button);
    g_signal_connect_after(l->popup,"hide",G_CALLBACK(gtk_widget_destroy),NULL);
}
