// The launcher and its thumbnails live only while the menu window is open.
typedef struct {
    Panel *panel;
    GtkWidget *popup, *grid, *search, *tabs;
} Launcher;

static gboolean matches_app(GtkFlowBoxChild *child,gpointer data) {
    Launcher *l=data;
    GtkWidget *tile=gtk_bin_get_child(GTK_BIN(child));
    int tab=gtk_combo_box_get_active(GTK_COMBO_BOX(l->tabs));
    if(tab && tab!=GPOINTER_TO_INT(g_object_get_data(G_OBJECT(tile),"category"))) return FALSE;
    gchar *query=g_utf8_casefold(gtk_entry_get_text(GTK_ENTRY(l->search)),-1);
    gboolean match=strstr(g_object_get_data(G_OBJECT(tile),"search-name"),query)!=NULL;
    g_free(query);
    return match;
}
static void filter_apps(GtkWidget *widget,Launcher *l) {
    gtk_flow_box_invalidate_filter(GTK_FLOW_BOX(l->grid));
}
static void open_tile(GtkButton *button,Launcher *l) {
    const char *id=g_object_get_data(G_OBJECT(button),"app-id");
    if(g_str_has_prefix(id,"linux-")) action(id);
    else launch(NULL,(gpointer)id);
    gtk_widget_hide(l->popup);
}
static void app_tile(Launcher *l,const char *name,const char *id,const char *icon,int category) {
    int side=40*l->panel->scale/100;
    GtkWidget *tile=gtk_button_new(), *box=gtk_box_new(GTK_ORIENTATION_VERTICAL,4);
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
static void launcher_linux(GtkButton *button,Launcher *l) {
    Panel *p=l->panel;
    gtk_widget_hide(l->popup);
    settings_open(NULL,p);
    gtk_stack_set_visible_child_name(GTK_STACK(p->stack),"linux");
}
static void main_menu(GtkWidget *button,Panel *p) {
    Launcher *l=g_new0(Launcher,1);
    l->panel=p; l->popup=gtk_window_new(GTK_WINDOW_TOPLEVEL);
    gtk_window_set_title(GTK_WINDOW(l->popup),"xdesx 菜单");
    g_object_set_data_full(G_OBJECT(l->popup),"launcher",l,g_free);
    GtkWidget *box=gtk_box_new(GTK_ORIENTATION_VERTICAL,8), *row=gtk_box_new(GTK_ORIENTATION_HORIZONTAL,6);
    gtk_container_set_border_width(GTK_CONTAINER(box),12);
    l->search=gtk_search_entry_new();
    gtk_entry_set_placeholder_text(GTK_ENTRY(l->search),"搜索应用");
    l->tabs=gtk_combo_box_text_new();
    const char *categories[]={"全部","安卓","Linux","Mac"};
    for(int i=0;i<4;i++) gtk_combo_box_text_append_text(GTK_COMBO_BOX_TEXT(l->tabs),categories[i]);
    gtk_combo_box_set_active(GTK_COMBO_BOX(l->tabs),0);
    gtk_box_pack_start(GTK_BOX(row),l->search,TRUE,TRUE,0);
    gtk_box_pack_end(GTK_BOX(row),l->tabs,FALSE,FALSE,0);
    gtk_box_pack_start(GTK_BOX(box),row,FALSE,FALSE,0);
    GtkWidget *scroll=gtk_scrolled_window_new(NULL,NULL);
    gtk_scrolled_window_set_policy(GTK_SCROLLED_WINDOW(scroll),GTK_POLICY_NEVER,GTK_POLICY_AUTOMATIC);
    gtk_widget_set_size_request(scroll,MIN(wnck_screen_get_width(p->screen)-48,520*p->scale/100),MIN(wnck_screen_get_height(p->screen)*2/3,390*p->scale/100));
    l->grid=gtk_flow_box_new();
    gtk_flow_box_set_selection_mode(GTK_FLOW_BOX(l->grid),GTK_SELECTION_NONE);
    gtk_flow_box_set_max_children_per_line(GTK_FLOW_BOX(l->grid),5);
    gtk_flow_box_set_min_children_per_line(GTK_FLOW_BOX(l->grid),2);
    gtk_flow_box_set_homogeneous(GTK_FLOW_BOX(l->grid),TRUE);
    gtk_widget_set_valign(l->grid,GTK_ALIGN_START);
    gtk_container_add(GTK_CONTAINER(scroll),l->grid);
    gtk_box_pack_start(GTK_BOX(box),scroll,TRUE,TRUE,0);
    gchar *data=NULL;
    if(g_file_get_contents("apps.tsv",&data,NULL,NULL)){
        gchar **lines=g_strsplit(data,"\n",-1);
        for(int i=0;lines[i];i++){
            gchar **f=g_strsplit(lines[i],"\t",4);
            if(g_strv_length(f)==4){
                gboolean mac=!strcmp(f[3],"mac");
                if(mac){g_free(p->mac_id);p->mac_id=g_strdup(f[0]);}
                app_tile(l,f[1],f[0],f[2],mac?3:1);
            }
            g_strfreev(f);
        }
        g_strfreev(lines);g_free(data);data=NULL;
    }
    if(g_file_get_contents("run/ubuntu-apps",&data,NULL,NULL)){
        gchar **lines=g_strsplit(data,"\n",-1);
        for(int i=0;lines[i];i++){
            gchar **f=g_strsplit(lines[i],"\t",3);
            if(f[0] && f[1] && *f[1]){gchar *id=g_strconcat("linux-",f[1],NULL);app_tile(l,f[0],id,f[2],2);g_free(id);}
            g_strfreev(f);
        }
        g_strfreev(lines);g_free(data);
    }
    row=gtk_box_new(GTK_ORIENTATION_HORIZONTAL,6);
    gchar *state=NULL;
    g_file_get_contents("run/ubuntu-state",&state,NULL,NULL);
    gboolean installed=state && g_str_has_prefix(state,"ready");
    gboolean busy=state && g_str_has_prefix(state,"busy");
    GtkWidget *settings=gtk_button_new_with_label("全局设置");
    int gear_size=(16*p->scale+50)/100;
    GdkPixbuf *gear=gdk_pixbuf_new_from_file_at_scale("share/xdesx/settings.png",gear_size,gear_size,TRUE,NULL);
    if (gear) {
        gtk_button_set_image(GTK_BUTTON(settings),gtk_image_new_from_pixbuf(gear));
        gtk_button_set_always_show_image(GTK_BUTTON(settings),TRUE);
        g_object_unref(gear);
    }
    GtkWidget *manage_linux=gtk_button_new_with_label(installed?"Linux 应用管理":busy?"查看 Ubuntu 下载进度":"下载 Ubuntu 应用环境");
    g_free(state);
    gtk_box_pack_start(GTK_BOX(row),manage_linux,FALSE,FALSE,0);gtk_box_pack_end(GTK_BOX(row),settings,FALSE,FALSE,0);
    gtk_box_pack_end(GTK_BOX(box),row,FALSE,FALSE,0);
    gtk_container_add(GTK_CONTAINER(l->popup),box);
    gtk_flow_box_set_filter_func(GTK_FLOW_BOX(l->grid),matches_app,l,NULL);
    g_signal_connect(l->search,"search-changed",G_CALLBACK(filter_apps),l);
    g_signal_connect(l->search,"activate",G_CALLBACK(first_result),l);
    g_signal_connect(l->tabs,"changed",G_CALLBACK(filter_apps),l);
    g_signal_connect(settings,"clicked",G_CALLBACK(launcher_settings),l);
    g_signal_connect(manage_linux,"clicked",G_CALLBACK(launcher_linux),l);
    gtk_widget_show_all(box);
    gtk_widget_grab_focus(l->search);
    XfcePanelPlugin *plugin=XFCE_PANEL_PLUGIN(gtk_widget_get_ancestor(button,XFCE_TYPE_PANEL_PLUGIN));
    xfce_panel_plugin_popup_window(plugin,GTK_WINDOW(l->popup),button);
    g_signal_connect_after(l->popup,"hide",G_CALLBACK(gtk_widget_destroy),NULL);
}
