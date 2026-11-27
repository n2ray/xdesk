static GtkWidget *mac_field(GtkGrid *grid,int row,const char *name,const char *value) {
    GtkWidget *label=gtk_label_new(name),*entry=gtk_entry_new();
    gtk_widget_set_halign(label,GTK_ALIGN_START);
    gtk_entry_set_text(GTK_ENTRY(entry),value);
    gtk_entry_set_max_length(GTK_ENTRY(entry),200);
    gtk_grid_attach(grid,label,0,row,1,1);gtk_grid_attach(grid,entry,1,row,1,1);
    return entry;
}
static void edit_mac(GtkButton *button,Panel *p) {
    const char *row=g_object_get_data(G_OBJECT(button),"device");
    gchar **v=g_strsplit(row?row:"new\t\t\t\t0\t0\t0","\t",-1);
    GtkWidget *d=gtk_dialog_new_with_buttons(row?T("设备设置"):T("添加 Mac"),GTK_WINDOW(p->mac_manager),GTK_DIALOG_MODAL|GTK_DIALOG_DESTROY_WITH_PARENT,
        T("取消"),GTK_RESPONSE_CANCEL,T("保存"),GTK_RESPONSE_OK,NULL);
    if(row) gtk_dialog_add_button(GTK_DIALOG(d),T("移除设备"),GTK_RESPONSE_REJECT);
    GtkGrid *grid=GTK_GRID(gtk_grid_new());
    gtk_grid_set_row_spacing(grid,10);gtk_grid_set_column_spacing(grid,12);
    gtk_container_set_border_width(GTK_CONTAINER(grid),18);
    gtk_container_add(GTK_CONTAINER(gtk_dialog_get_content_area(GTK_DIALOG(d))),GTK_WIDGET(grid));
    GtkWidget *name=mac_field(grid,0,T("设备名称"),v[1]),*host=mac_field(grid,1,T("Mac 地址"),v[2]),*account=mac_field(grid,2,T("账户"),v[3]);
    GtkWidget *scale=gtk_combo_box_text_new();
    const char *tiers[]={T("跟随桌面"),"1.33×","2×","1×"};
    for(int i=0;i<4;i++) gtk_combo_box_text_append_text(GTK_COMBO_BOX_TEXT(scale),tiers[i]);
    gtk_combo_box_set_active(GTK_COMBO_BOX(scale),CLAMP(atoi(v[4]),0,3));
    gtk_grid_attach(grid,gtk_label_new(T("共享桌面缩放")),0,3,1,1);gtk_grid_attach(grid,scale,1,3,1,1);
    GtkWidget *fixed=gtk_check_button_new_with_label(T("固定 1080p")),*measure=gtk_check_button_new_with_label(T("记录性能数据"));
    gtk_toggle_button_set_active(GTK_TOGGLE_BUTTON(fixed),atoi(v[5]));
    gtk_toggle_button_set_active(GTK_TOGGLE_BUTTON(measure),atoi(v[6]));
    g_object_bind_property(fixed,"active",scale,"sensitive",G_BINDING_SYNC_CREATE|G_BINDING_INVERT_BOOLEAN);
    gtk_widget_set_tooltip_text(fixed,T("下次连接使用 1920×1080 原生桌面，窗口保持 16∶9。"));
    gtk_widget_set_tooltip_text(scale,T("下次连接时调整 Mac 桌面的文字和控件大小。"));
    gtk_grid_attach(grid,fixed,1,4,1,1);gtk_grid_attach(grid,measure,1,5,1,1);
    gtk_widget_show_all(d);
    for(;;){
        int response=gtk_dialog_run(GTK_DIALOG(d));
        if(response==GTK_RESPONSE_REJECT){
            GtkWidget *confirm=gtk_message_dialog_new(GTK_WINDOW(d),GTK_DIALOG_MODAL,GTK_MESSAGE_QUESTION,GTK_BUTTONS_NONE,"%s",T("移除此 Mac 及已保存的凭据？"));
            gtk_dialog_add_buttons(GTK_DIALOG(confirm),T("取消"),GTK_RESPONSE_CANCEL,T("移除"),GTK_RESPONSE_OK,NULL);
            int answer=gtk_dialog_run(GTK_DIALOG(confirm));gtk_widget_destroy(confirm);
            if(answer!=GTK_RESPONSE_OK)continue;
            gchar *op=g_strconcat("mac-remove-",v[0],NULL);action(op);g_free(op);
        }
        if(response==GTK_RESPONSE_OK){
            const char *address=gtk_entry_get_text(GTK_ENTRY(host)),*user=gtk_entry_get_text(GTK_ENTRY(account));
            if(!*address || !*user){gtk_widget_grab_focus(!*address?host:account);continue;}
            gchar *record=g_strdup_printf("%s\t%s\t%s\t%s\t%d\t%d\t%d",v[0],gtk_entry_get_text(GTK_ENTRY(name)),address,user,
                gtk_combo_box_get_active(GTK_COMBO_BOX(scale)),gtk_toggle_button_get_active(GTK_TOGGLE_BUTTON(fixed)),gtk_toggle_button_get_active(GTK_TOGGLE_BUTTON(measure)));
            gchar *encoded=g_base64_encode((guchar*)record,strlen(record)),*op=g_strconcat("mac-save-",encoded,NULL);
            action(op);g_free(op);g_free(encoded);g_free(record);
        }
        break;
    }
    gtk_widget_destroy(d);g_strfreev(v);
}
static GtkWidget *mac_label(const char *text,const char *style) {
    GtkWidget *label=gtk_label_new(text);
    gtk_label_set_justify(GTK_LABEL(label),GTK_JUSTIFY_CENTER);
    gtk_label_set_line_wrap(GTK_LABEL(label),TRUE);
    gtk_label_set_max_width_chars(GTK_LABEL(label),64);
    if(style)gtk_style_context_add_class(gtk_widget_get_style_context(label),style);
    return label;
}
static void connect_card(GtkFlowBox *box,GtkFlowBoxChild *child,gpointer unused) {
    const char *session=g_object_get_data(G_OBJECT(child),"session");
    if(session)launch_window(session,GPOINTER_TO_INT(g_object_get_data(G_OBJECT(child),"fixed-1080")));
}
/* Cap the content width on wide monitors. Let the flow box receive the full available
 * width inside these margins so GTK can wrap equal-width cards as the window resizes. */
typedef struct {GtkBox parent;Panel *panel;} MacPage;
typedef GtkBoxClass MacPageClass;
G_DEFINE_TYPE(MacPage,mac_page,GTK_TYPE_BOX)
static void mac_page_adjust(GtkWidget *widget,GtkOrientation axis,int *minimum,int *natural,int *pos,int *size) {
    GTK_WIDGET_CLASS(mac_page_parent_class)->adjust_size_allocation(widget,axis,minimum,natural,pos,size);
    if(axis==GTK_ORIENTATION_HORIZONTAL){
        MacPage *page=(MacPage*)widget;
        int limit=760*page->panel->scale/100;
        if(*size>limit){*pos+=(*size-limit)/2;*size=limit;}
    }
}
static void mac_page_class_init(MacPageClass *klass){GTK_WIDGET_CLASS(klass)->adjust_size_allocation=mac_page_adjust;}
static void mac_page_init(MacPage *page){gtk_orientable_set_orientation(GTK_ORIENTABLE(page),GTK_ORIENTATION_VERTICAL);}
static void guide_line(GtkWidget *guide,const char *marker,const char *text){
    GtkWidget *row=gtk_box_new(GTK_ORIENTATION_HORIZONTAL,12);
    GtkWidget *mark=gtk_label_new(marker);
    gtk_style_context_add_class(gtk_widget_get_style_context(mark),"guide-marker");
    gtk_widget_set_valign(mark,GTK_ALIGN_START);
    GtkWidget *body=gtk_label_new(T(text));
    gtk_label_set_line_wrap(GTK_LABEL(body),TRUE);
    gtk_label_set_xalign(GTK_LABEL(body),0);
    gtk_box_pack_start(GTK_BOX(row),mark,FALSE,FALSE,0);
    gtk_box_pack_start(GTK_BOX(row),body,TRUE,TRUE,0);
    gtk_container_add(GTK_CONTAINER(guide),row);
}
static void guide_heading(GtkWidget *guide,const char *text){
    GtkWidget *heading=gtk_label_new(T(text));
    gtk_label_set_xalign(GTK_LABEL(heading),0);
    gtk_style_context_add_class(gtk_widget_get_style_context(heading),"guide-heading");
    gtk_container_add(GTK_CONTAINER(guide),heading);
}
static void refresh_mac_settings(Panel *p) {
    GtkWidget *page=p->mac_page;
    GList *old=gtk_container_get_children(GTK_CONTAINER(page));
    for(GList *it=old;it;it=it->next)gtk_widget_destroy(it->data);
    g_list_free(old);
    gtk_box_pack_start(GTK_BOX(page),mac_label(T("共享桌面"),"page-title"),FALSE,FALSE,2);
    GtkWidget *grid=gtk_flow_box_new();
    gtk_flow_box_set_min_children_per_line(GTK_FLOW_BOX(grid),1);
    gtk_flow_box_set_max_children_per_line(GTK_FLOW_BOX(grid),3);
    gtk_flow_box_set_row_spacing(GTK_FLOW_BOX(grid),12);
    gtk_flow_box_set_column_spacing(GTK_FLOW_BOX(grid),12);
    gtk_flow_box_set_activate_on_single_click(GTK_FLOW_BOX(grid),FALSE);
    gtk_flow_box_set_selection_mode(GTK_FLOW_BOX(grid),GTK_SELECTION_SINGLE);
    g_signal_connect(grid,"child-activated",G_CALLBACK(connect_card),NULL);
    gtk_flow_box_set_homogeneous(GTK_FLOW_BOX(grid),TRUE);
    gtk_widget_set_halign(grid,GTK_ALIGN_FILL);
    gtk_box_pack_start(GTK_BOX(page),grid,FALSE,FALSE,0);
    gchar *data=NULL;
    g_file_get_contents("run/mac-hosts",&data,NULL,NULL);
    gchar **rows=g_strsplit(data?data:"","\n",-1);
    for(int i=0;rows[i];i++){
        gchar **v=g_strsplit(rows[i],"\t",-1);
        if(g_strv_length(v)==7){
            GtkWidget *child=gtk_flow_box_child_new(),*card=gtk_box_new(GTK_ORIENTATION_VERTICAL,5);
            gtk_style_context_add_class(gtk_widget_get_style_context(card),"content-card");
            int side=58*p->scale/100;
            GdkPixbuf *icon=gdk_pixbuf_new_from_file_at_scale("cache/imac-v1.png",side,side,TRUE,NULL);
            if(icon){gtk_container_add(GTK_CONTAINER(card),gtk_image_new_from_pixbuf(icon));g_object_unref(icon);}
            GtkWidget *name=mac_label(*v[1]?v[1]:v[2],"card-title");
            gtk_label_set_ellipsize(GTK_LABEL(name),PANGO_ELLIPSIZE_END);
            gtk_label_set_max_width_chars(GTK_LABEL(name),18);
            gtk_widget_set_size_request(child,168*p->scale/100,-1);
            gtk_container_add(GTK_CONTAINER(card),name);
            gchar *detail=g_strdup_printf("%s @ %s",v[3],v[2]);
            GtkWidget *address=mac_label(detail,NULL);g_free(detail);
            gtk_label_set_max_width_chars(GTK_LABEL(address),18);
            gtk_label_set_ellipsize(GTK_LABEL(address),PANGO_ELLIPSIZE_MIDDLE);
            gtk_container_add(GTK_CONTAINER(card),address);
            GtkWidget *edit=gtk_button_new_with_label(T("设备设置"));
            gtk_widget_set_halign(edit,GTK_ALIGN_CENTER);
            g_object_set_data_full(G_OBJECT(edit),"device",g_strdup(rows[i]),g_free);
            g_signal_connect(edit,"clicked",G_CALLBACK(edit_mac),p);
            gtk_container_add(GTK_CONTAINER(card),edit);
            g_object_set_data(G_OBJECT(child),"fixed-1080",GINT_TO_POINTER(atoi(v[5])));
            g_object_set_data_full(G_OBJECT(child),"session",g_strconcat("mac-",v[0],NULL),g_free);
            gtk_widget_set_tooltip_text(child,T("双击连接，或选中后按 Enter。"));
            gtk_container_add(GTK_CONTAINER(child),card);
            gtk_container_add(GTK_CONTAINER(grid),child);
        }
        g_strfreev(v);
    }
    g_strfreev(rows);g_free(data);
    GtkWidget *add=gtk_button_new(),*face=gtk_box_new(GTK_ORIENTATION_VERTICAL,6);
    gtk_widget_set_valign(face,GTK_ALIGN_CENTER);
    gtk_container_add(GTK_CONTAINER(face),mac_label("+","add-symbol"));
    gtk_container_add(GTK_CONTAINER(face),mac_label(T("添加 Mac"),"card-title"));
    gtk_container_add(GTK_CONTAINER(add),face);
    gtk_style_context_add_class(gtk_widget_get_style_context(add),"add-device");
    gtk_widget_set_size_request(add,168*p->scale/100,126*p->scale/100);
    g_signal_connect(add,"clicked",G_CALLBACK(edit_mac),p);
    gtk_container_add(GTK_CONTAINER(grid),add);
    GtkWidget *guide=settings_card(page);
    guide_heading(guide,"在 Mac 上开启屏幕共享");
    guide_line(guide,"1","在 Mac 上打开「系统设置 → 通用 → 共享」。");
    guide_line(guide,"2","开启「屏幕共享」，点详情按钮，允许你的 Mac 账户访问。");
    guide_line(guide,"3","在这里点「添加 Mac」，填写地址和账户。双击保存的设备即可连接。");
    GtkWidget *requirements=settings_card(page);
    guide_heading(requirements,"高性能共享要求");
    guide_line(requirements,"•","Apple 芯片，macOS Sonoma 14 或更新版本。");
    guide_line(requirements,"•","建议使用低延迟有线网络。每台 Mac 同时支持一个高性能会话。");
    gtk_widget_show_all(page);
}
static void mac_manager_closed(GtkWidget *w,Panel *p){p->mac_manager=NULL;p->mac_page=NULL;}
static void mac_manager_open(Panel *p){
    if(!p->mac_manager){
        p->mac_manager=gtk_window_new(GTK_WINDOW_TOPLEVEL);
        gtk_window_set_title(GTK_WINDOW(p->mac_manager),T("共享桌面"));
        gtk_window_set_wmclass(GTK_WINDOW(p->mac_manager),"xdesx-sharing","XdesxSharing");
        gtk_window_set_icon_from_file(GTK_WINDOW(p->mac_manager),"cache/imac-v1.png",NULL);
        gtk_widget_set_name(p->mac_manager,"xdesx-settings");
        gtk_window_set_default_size(GTK_WINDOW(p->mac_manager),MIN(wnck_screen_get_width(p->screen)-24,760*p->scale/100),MIN(wnck_screen_get_height(p->screen)-64,540*p->scale/100));
        GtkWidget *scroll=gtk_scrolled_window_new(NULL,NULL);
        gtk_container_add(GTK_CONTAINER(p->mac_manager),scroll);
        p->mac_page=g_object_new(mac_page_get_type(),"spacing",14,NULL);
        ((MacPage*)p->mac_page)->panel=p;
        gtk_widget_set_halign(p->mac_page,GTK_ALIGN_FILL);
        gtk_widget_set_valign(p->mac_page,GTK_ALIGN_START);
        gtk_container_set_border_width(GTK_CONTAINER(p->mac_page),20);
        gtk_scrolled_window_set_policy(GTK_SCROLLED_WINDOW(scroll),GTK_POLICY_NEVER,GTK_POLICY_AUTOMATIC);
        gtk_container_add(GTK_CONTAINER(scroll),p->mac_page);
        g_signal_connect(p->mac_manager,"destroy",G_CALLBACK(mac_manager_closed),p);
        refresh_mac_settings(p);
        gtk_widget_show_all(p->mac_manager);
    }
    gtk_window_present(GTK_WINDOW(p->mac_manager));
}
