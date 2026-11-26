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
    GtkWidget *d=gtk_dialog_new_with_buttons(row?T("设备设置"):T("添加 Mac"),GTK_WINDOW(p->settings),GTK_DIALOG_MODAL,
        T("取消"),GTK_RESPONSE_CANCEL,T("保存"),GTK_RESPONSE_OK,NULL);
    if(row) gtk_dialog_add_button(GTK_DIALOG(d),T("移除设备"),GTK_RESPONSE_REJECT);
    GtkGrid *grid=GTK_GRID(gtk_grid_new());
    gtk_grid_set_row_spacing(grid,10);gtk_grid_set_column_spacing(grid,12);
    gtk_container_set_border_width(GTK_CONTAINER(grid),18);
    gtk_container_add(GTK_CONTAINER(gtk_dialog_get_content_area(GTK_DIALOG(d))),GTK_WIDGET(grid));
    GtkWidget *name=mac_field(grid,0,T("设备名称"),v[1]),*host=mac_field(grid,1,T("Mac 地址"),v[2]),*account=mac_field(grid,2,T("账户"),v[3]);
    GtkWidget *scale=gtk_combo_box_text_new();
    const char *tiers[]={T("原生"),"1.33×","2×"};
    for(int i=0;i<3;i++) gtk_combo_box_text_append_text(GTK_COMBO_BOX_TEXT(scale),tiers[i]);
    gtk_combo_box_set_active(GTK_COMBO_BOX(scale),CLAMP(atoi(v[4]),0,2));
    gtk_grid_attach(grid,gtk_label_new(T("共享桌面缩放")),0,3,1,1);gtk_grid_attach(grid,scale,1,3,1,1);
    GtkWidget *fixed=gtk_check_button_new_with_label(T("固定 1080p")),*measure=gtk_check_button_new_with_label(T("记录性能数据"));
    gtk_toggle_button_set_active(GTK_TOGGLE_BUTTON(fixed),atoi(v[5]));
    gtk_toggle_button_set_active(GTK_TOGGLE_BUTTON(measure),atoi(v[6]));
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
static void mac_settings(GtkWidget *page,Panel *p){
    GtkWidget *add=gtk_button_new_with_label(T("添加 Mac"));
    gtk_widget_set_halign(add,GTK_ALIGN_START);
    g_signal_connect(add,"clicked",G_CALLBACK(edit_mac),p);
    gtk_box_pack_start(GTK_BOX(page),add,FALSE,FALSE,0);
    GtkWidget *list=gtk_box_new(GTK_ORIENTATION_VERTICAL,6);
    gtk_box_pack_start(GTK_BOX(page),list,FALSE,FALSE,0);
    gchar *data=NULL;if(!g_file_get_contents("run/mac-hosts",&data,NULL,NULL)) return;
    gchar **rows=g_strsplit(data,"\n",-1);
    for(int i=0;rows[i];i++){
        gchar **v=g_strsplit(rows[i],"\t",-1);
        if(g_strv_length(v)==7){
            gchar *label=g_strdup_printf("%s    ·    %s",*v[1]?v[1]:v[2],v[3]);
            GtkWidget *b=gtk_button_new_with_label(label);g_free(label);
            g_object_set_data_full(G_OBJECT(b),"device",g_strdup(rows[i]),g_free);
            gtk_widget_set_halign(b,GTK_ALIGN_START);
            g_signal_connect(b,"clicked",G_CALLBACK(edit_mac),p);
            gtk_box_pack_start(GTK_BOX(list),b,FALSE,FALSE,0);
        }
        g_strfreev(v);
    }
    g_strfreev(rows);g_free(data);
}
