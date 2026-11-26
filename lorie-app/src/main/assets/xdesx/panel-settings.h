/* Draw the category marks with Cairo so the bundled desktop needs no icon theme. */
static gboolean category_mark(GtkWidget *widget,cairo_t *cr,gpointer data) {
    const char *id=data;
    double size=MIN(gtk_widget_get_allocated_width(widget),gtk_widget_get_allocated_height(widget));
    cairo_translate(cr,(gtk_widget_get_allocated_width(widget)-size)/2,(gtk_widget_get_allocated_height(widget)-size)/2);
    cairo_scale(cr,size/32,size/32);
    GdkRGBA color;
    gtk_style_context_get_color(gtk_widget_get_style_context(widget),GTK_STATE_FLAG_NORMAL,&color);
    gdk_cairo_set_source_rgba(cr,&color);
    cairo_set_line_width(cr,1.8);cairo_set_line_join(cr,CAIRO_LINE_JOIN_ROUND);cairo_set_line_cap(cr,CAIRO_LINE_CAP_ROUND);
    if(!strcmp(id,"display") || !strcmp(id,"mac")){
        cairo_rectangle(cr,4,5,24,17);cairo_move_to(cr,16,22);cairo_line_to(cr,16,27);
        cairo_move_to(cr,10,27);cairo_line_to(cr,22,27);
        if(!strcmp(id,"mac")){cairo_move_to(cr,11,16);cairo_line_to(cr,19,10);cairo_line_to(cr,19,15);}
    }else if(!strcmp(id,"appearance")){
        cairo_arc(cr,16,16,11,0,2*G_PI);cairo_stroke(cr);
        cairo_arc(cr,16,16,11,-G_PI/2,G_PI/2);cairo_fill(cr);
    }else if(!strcmp(id,"wallpaper")){
        cairo_rectangle(cr,4,6,24,20);cairo_move_to(cr,5,24);cairo_line_to(cr,13,15);cairo_line_to(cr,18,20);cairo_line_to(cr,22,16);cairo_line_to(cr,27,22);
        cairo_new_sub_path(cr);cairo_arc(cr,21,11,2,0,2*G_PI);
    }else if(!strcmp(id,"windows")){
        cairo_rectangle(cr,5,5,17,15);cairo_stroke(cr);cairo_rectangle(cr,11,12,16,14);
    }else if(!strcmp(id,"input")){
        cairo_arc(cr,16,12,8,G_PI,2*G_PI);cairo_arc(cr,16,20,8,0,G_PI);cairo_close_path(cr);
        cairo_move_to(cr,16,8);cairo_line_to(cr,16,13);
    }else if(!strcmp(id,"notifications")){
        cairo_move_to(cr,5,23);cairo_curve_to(cr,11,18,6,6,16,6);cairo_curve_to(cr,26,6,21,18,27,23);cairo_close_path(cr);
        cairo_move_to(cr,14,27);cairo_line_to(cr,18,27);
    }else if(!strcmp(id,"linux")){
        cairo_rectangle(cr,3,6,26,20);cairo_move_to(cr,8,12);cairo_line_to(cr,12,16);cairo_line_to(cr,8,20);
        cairo_move_to(cr,16,20);cairo_line_to(cr,23,20);
    }else if(!strcmp(id,"power")){
        cairo_rectangle(cr,3,9,23,14);cairo_move_to(cr,29,13);cairo_line_to(cr,29,19);
        cairo_move_to(cr,15,11);cairo_line_to(cr,11,17);cairo_line_to(cr,17,16);cairo_line_to(cr,14,21);
    }else{
        cairo_arc(cr,16,16,11,0,2*G_PI);cairo_move_to(cr,16,15);cairo_line_to(cr,16,22);
        cairo_move_to(cr,16,10);cairo_line_to(cr,16,11);
    }
    cairo_stroke(cr);return FALSE;
}
static GtkWidget *settings_card(GtkWidget *page) {
    GtkWidget *box=gtk_box_new(GTK_ORIENTATION_VERTICAL,10);
    gtk_style_context_add_class(gtk_widget_get_style_context(box),"content-card");
    gtk_box_pack_start(GTK_BOX(page),box,FALSE,FALSE,0);
    return box;
}
static void setting_row(GtkWidget *page,const char *label,const char *verb,const int *values,const char **names,int n,int *current) {
    GtkWidget *row=settings_card(page);
    GtkWidget *title=gtk_label_new(label);
    gtk_widget_set_halign(title,GTK_ALIGN_START);
    gtk_style_context_add_class(gtk_widget_get_style_context(title),"card-title");
    gtk_box_pack_start(GTK_BOX(row),title,FALSE,FALSE,0);
    GtkWidget *group=gtk_box_new(GTK_ORIENTATION_HORIZONTAL,6);
    gtk_box_pack_start(GTK_BOX(row),group,FALSE,FALSE,0);
    for(int i=0;i<n;i++) {
        GtkWidget *b=gtk_button_new_with_label(names[i]);
        g_object_set_data(G_OBJECT(b),"option-value",GINT_TO_POINTER(values[i]));
        g_object_set_data(G_OBJECT(b),"option-current",current);
        if(values[i]==*current) gtk_style_context_add_class(gtk_widget_get_style_context(b),"chosen");
        g_object_set_data_full(G_OBJECT(b),"action",g_strdup_printf("%s-%d",verb,values[i]),g_free);
        g_signal_connect(b,"clicked",G_CALLBACK(selected),NULL);
        gtk_box_pack_start(GTK_BOX(group),b,FALSE,FALSE,0);
    }
}
static GtkWidget *page(Panel *p,const char *id,const char *title) {
    GtkWidget *box=gtk_box_new(GTK_ORIENTATION_VERTICAL,12);
    gtk_container_set_border_width(GTK_CONTAINER(box),18);
    GtkWidget *heading=gtk_label_new(title);
    gtk_widget_set_halign(heading,GTK_ALIGN_START);
    gtk_style_context_add_class(gtk_widget_get_style_context(heading),"page-title");
    GtkWidget *header=gtk_box_new(GTK_ORIENTATION_HORIZONTAL,12);
    GtkWidget *mark=gtk_drawing_area_new();
    gtk_widget_set_size_request(mark,40*p->scale/100,40*p->scale/100);
    g_signal_connect(mark,"draw",G_CALLBACK(category_mark),(gpointer)id);
    gtk_box_pack_start(GTK_BOX(header),mark,FALSE,FALSE,0);
    gtk_box_pack_start(GTK_BOX(header),heading,FALSE,FALSE,0);
    gtk_box_pack_start(GTK_BOX(box),header,FALSE,FALSE,6);
    gtk_stack_add_titled(GTK_STACK(p->stack),box,id,title);
    return box;
}
static void bring_back(GtkButton *b,Panel *p) {
    int y=p->scale*36/100+12;
    for(GList *l=wnck_screen_get_windows(p->screen);l;l=l->next) {
        WnckWindow *w=l->data;
        if(wnck_window_is_skip_tasklist(w)||wnck_window_is_maximized(w)||wnck_window_is_fullscreen(w)) continue;
        int x,wy,width,height; wnck_window_get_geometry(w,&x,&wy,&width,&height);
        int nx=CLAMP(x,0,MAX(0,wnck_screen_get_width(p->screen)-width));
        int ny=CLAMP(wy,y,MAX(y,wnck_screen_get_height(p->screen)-height));
        if(nx!=x||ny!=wy) wnck_window_set_geometry(w,WNCK_WINDOW_GRAVITY_STATIC,WNCK_WINDOW_CHANGE_X|WNCK_WINDOW_CHANGE_Y,nx,ny,width,height);
    }
}
#include "panel-mac.h"
static void update_ubuntu_controls(Panel *p) {
    gchar *state=NULL;
    g_file_get_contents("run/ubuntu-state",&state,NULL,NULL);
    gboolean ready=state && g_str_has_prefix(state,"ready\n");
    gboolean busy=state && g_str_has_prefix(state,"busy\n");
    gboolean downloading=state && g_str_has_prefix(state,"busy-download\n");
    gboolean paused=state && g_str_has_prefix(state,"paused\n");
    const char *status=state?strchr(state,'\n'):NULL;
    gtk_label_set_text(GTK_LABEL(p->ubuntu_status),status?status+1:T("正在读取安装状态"));
    gtk_button_set_label(GTK_BUTTON(p->ubuntu_action),downloading?T("取消下载"):ready?T("刷新 Linux 应用列表"):paused?T("继续下载"):T("安装 Ubuntu 核心"));
    gtk_widget_set_sensitive(p->ubuntu_action,state && !busy);
    g_object_set_data(G_OBJECT(p->ubuntu_action),"action",downloading?"linux-cancel":ready?"linux-refresh":"linux-install");
    gtk_widget_set_sensitive(p->ubuntu_manage,ready);
    g_free(state);
}
static void chosen(GtkWidget *widget,gboolean on) {
    GtkStyleContext *css=gtk_widget_get_style_context(widget);
    if(on) gtk_style_context_add_class(css,"chosen"); else gtk_style_context_remove_class(css,"chosen");
}
static void update_option(GtkWidget *widget,gpointer unused) {
    int *current=g_object_get_data(G_OBJECT(widget),"option-current");
    if(current) chosen(widget,*current==GPOINTER_TO_INT(g_object_get_data(G_OBJECT(widget),"option-value")));
    else if(GTK_IS_CONTAINER(widget)) gtk_container_foreach(GTK_CONTAINER(widget),update_option,NULL);
}
static void update_setting_values(Panel *p) {
    update_option(p->stack,NULL);
    chosen(p->names_button,p->notify_names);
    chosen(p->preview_button,!p->notify_names);
    gchar *text=g_strdup_printf(T("桌面当前渲染尺寸   %d × %d"),wnck_screen_get_width(p->screen),wnck_screen_get_height(p->screen));
    gtk_label_set_text(GTK_LABEL(p->resolution_label),text);g_free(text);
    text=g_strdup_printf(T("已打开 %d 个应用窗口"),p->opened);
    gtk_label_set_text(GTK_LABEL(p->count_label),text);g_free(text);
}
static void refresh_mac_settings(Panel *p) {
    GList *children=gtk_container_get_children(GTK_CONTAINER(p->mac_page));
    for(GList *c=children;c;c=c->next) gtk_widget_destroy(c->data);
    g_list_free(children);
    mac_settings(p->mac_page,p);
    gtk_widget_show_all(p->mac_page);
}
static void confirm_linux(GtkButton *button,Panel *p) {
    gboolean removing=!strcmp(g_object_get_data(G_OBJECT(button),"action"),"linux-remove");
    GtkWidget *dialog=gtk_message_dialog_new(GTK_WINDOW(p->settings),GTK_DIALOG_MODAL|GTK_DIALOG_DESTROY_WITH_PARENT,
        GTK_MESSAGE_QUESTION,GTK_BUTTONS_NONE,"%s",removing?T("删除 Ubuntu 应用环境？"):T("重置 Ubuntu 系统？"));
    gtk_message_dialog_format_secondary_text(GTK_MESSAGE_DIALOG(dialog),"%s",removing?
        T("将关闭 Linux 应用，删除系统、主目录中的文件和已下载的镜像。"):T("将关闭 Linux 应用并重新安装系统。保留主目录中的文件，已安装的软件需要重新安装。"));
    gtk_dialog_add_buttons(GTK_DIALOG(dialog),T("取消"),GTK_RESPONSE_CANCEL,removing?T("删除"):T("重置"),GTK_RESPONSE_ACCEPT,NULL);
    gtk_dialog_set_default_response(GTK_DIALOG(dialog),GTK_RESPONSE_CANCEL);
    if(gtk_dialog_run(GTK_DIALOG(dialog))==GTK_RESPONSE_ACCEPT) action(removing?"linux-remove":"linux-reset");
    gtk_widget_destroy(dialog);
}
static void settings_contents(Panel *p) {
    GtkWidget *body=gtk_box_new(GTK_ORIENTATION_HORIZONTAL,1);
    p->stack=gtk_stack_new();
    GtkStack *s=GTK_STACK(p->stack);
    GtkWidget *nav=gtk_stack_sidebar_new();
    gtk_stack_set_transition_type(s,GTK_STACK_TRANSITION_TYPE_NONE);
    gtk_widget_set_size_request(nav,148*p->scale/100,-1);
    gtk_stack_sidebar_set_stack(GTK_STACK_SIDEBAR(nav),s);
    gtk_box_pack_start(GTK_BOX(body),nav,FALSE,FALSE,0);
    GtkWidget *scroll=gtk_scrolled_window_new(NULL,NULL);
    gtk_scrolled_window_set_policy(GTK_SCROLLED_WINDOW(scroll),GTK_POLICY_AUTOMATIC,GTK_POLICY_AUTOMATIC);
    gtk_container_add(GTK_CONTAINER(scroll),p->stack);
    gtk_box_pack_start(GTK_BOX(body),scroll,TRUE,TRUE,0);
    gtk_container_add(GTK_CONTAINER(p->settings),body);
    GtkWidget *view=page(p,"display",T("显示"));
    p->resolution_label=gtk_label_new(NULL);
    gtk_box_pack_start(GTK_BOX(view),p->resolution_label,FALSE,FALSE,4);
    const int scales[]={100,133,167,200},renders[]={100,75,50},speed[]={75,100,150},yesno[]={1,0},limits[]={2,3,4,6};
    const char *sc[]={T("原生"),T("小"),T("中"),T("大")},*rs[]={T("原生"),"75%","50%"},*sp[]={T("慢"),T("标准"),T("快")},*yn[]={T("开"),T("关")},*ls[]={"2","3","4","6"};
    setting_row(view,T("界面大小"),"scale",scales,sc,4,&p->scale);
    setting_row(view,T("渲染分辨率"),"render",renders,rs,3,&p->render);
    view=page(p,"appearance",T("外观与语言"));
    const int languages[]={0,1,2,3}, appearances[]={0,1,2};
    const char *langs[]={T("系统默认"),"English","简体中文","繁體中文"},*looks[]={T("跟随系统"),T("浅色"),T("深色")};
    setting_row(view,T("语言"),"lang",languages,langs,4,&p->language);
    setting_row(view,T("外观"),"appearance",appearances,looks,3,&p->appearance);
    wallpaper_controls(settings_card(page(p,"wallpaper",T("桌面背景"))),p);
    view=page(p,"windows",T("桌面与窗口"));
    setting_row(view,T("记住窗口布局"),"remember",yesno,yn,2,&p->remember);
    GtkWidget *b=gtk_button_new_with_label(T("将窗口移回屏幕内"));g_signal_connect(b,"clicked",G_CALLBACK(bring_back),p);gtk_widget_set_halign(b,GTK_ALIGN_START);gtk_box_pack_start(GTK_BOX(view),b,FALSE,FALSE,0);
    view=page(p,"input",T("输入与触摸板"));
    setting_row(view,T("鼠标速度"),"mouse",speed,sp,3,&p->mouse);
    setting_row(view,T("触摸板速度"),"pad",speed,sp,3,&p->pad);
    setting_row(view,T("反向滚动"),"reverse",yesno,yn,2,&p->reverse);
    view=settings_card(page(p,"notifications",T("通知")));
    GtkWidget *names=notify_button(T("仅显示应用名"),"notify-names"),*preview=notify_button(T("显示通知摘要"),"notify-summary");
    gtk_widget_set_halign(names,GTK_ALIGN_START);gtk_widget_set_halign(preview,GTK_ALIGN_START);
    p->names_button=names; p->preview_button=preview;
    gtk_box_pack_start(GTK_BOX(view),names,FALSE,FALSE,0);gtk_box_pack_start(GTK_BOX(view),preview,FALSE,FALSE,0);
    GtkWidget *access=notify_button(T("手机通知访问设置"),"notify-access");
    gtk_widget_set_halign(access,GTK_ALIGN_START);gtk_box_pack_start(GTK_BOX(view),access,FALSE,FALSE,0);
    p->mac_page=settings_card(page(p,"mac",T("Mac 屏幕共享")));
    mac_settings(p->mac_page,p);
    view=settings_card(page(p,"linux","Ubuntu / Linux"));
    GtkWidget *shared_hint=notification_label(T("共享文件：Ubuntu 在 /mnt/xdesx 保存，安卓在系统文件选择器中打开 xdesx。卸载前请另存需要保留的文件。"));
    gtk_box_pack_start(GTK_BOX(view),shared_hint,FALSE,FALSE,0);
    p->ubuntu_status=gtk_label_new(NULL);
    gtk_label_set_line_wrap(GTK_LABEL(p->ubuntu_status),TRUE);
    gtk_box_pack_start(GTK_BOX(view),p->ubuntu_status,FALSE,FALSE,0);
    gtk_label_set_xalign(GTK_LABEL(p->ubuntu_status),0);
    p->ubuntu_action=gtk_button_new();
    gtk_widget_set_halign(p->ubuntu_action,GTK_ALIGN_START);
    g_signal_connect(p->ubuntu_action,"clicked",G_CALLBACK(selected),NULL);
    gtk_box_pack_start(GTK_BOX(view),p->ubuntu_action,FALSE,FALSE,0);
    p->ubuntu_manage=gtk_box_new(GTK_ORIENTATION_HORIZONTAL,8);
    gtk_box_pack_start(GTK_BOX(view),p->ubuntu_manage,FALSE,FALSE,0);
    for(int i=0;i<2;i++){
        GtkWidget *button=gtk_button_new_with_label(i?T("删除 Ubuntu…"):T("重置系统…"));
        g_object_set_data(G_OBJECT(button),"action",i?"linux-remove":"linux-reset");
        g_signal_connect(button,"clicked",G_CALLBACK(confirm_linux),p);
        gtk_box_pack_start(GTK_BOX(p->ubuntu_manage),button,FALSE,FALSE,0);
    }
    update_ubuntu_controls(p);
    view=page(p,"power",T("性能与电源"));
    p->count_label=gtk_label_new(NULL);
    gtk_box_pack_start(GTK_BOX(view),p->count_label,FALSE,FALSE,4);
    setting_row(view,T("窗口上限"),"limit",limits,ls,4,&p->limit);
    view=settings_card(page(p,"about",T("关于与诊断")));
    gtk_box_pack_start(GTK_BOX(view),gtk_label_new("xdesx · xfwm4 4.20.0 · xfce4-panel 4.20"),FALSE,FALSE,2);
    b=gtk_button_new_with_label(T("恢复外屏原生分辨率和 1× 界面"));
    g_object_set_data(G_OBJECT(b),"action","recover");g_signal_connect(b,"clicked",G_CALLBACK(selected),NULL);
    gtk_widget_set_halign(b,GTK_ALIGN_START);gtk_box_pack_start(GTK_BOX(view),b,FALSE,FALSE,0);
    gtk_widget_show_all(body);
    update_setting_values(p);
}
static void settings_closed(GtkWidget *w,Panel *p) { p->settings=NULL;p->stack=NULL;p->ubuntu_status=NULL;p->ubuntu_action=NULL; }
static void settings_open(GtkWidget *unused,Panel *p) {
    if(!p->settings) {
        p->settings=gtk_window_new(GTK_WINDOW_TOPLEVEL);
        gtk_window_set_title(GTK_WINDOW(p->settings),T("全局设置"));
        gtk_window_set_wmclass(GTK_WINDOW(p->settings),"xdesx-settings","XdesxSettings");
        gtk_window_set_icon_from_file(GTK_WINDOW(p->settings),"share/xdesx/settings.png",NULL);
        gtk_widget_set_name(p->settings,"xdesx-settings");
        gtk_window_set_default_size(GTK_WINDOW(p->settings),MIN(wnck_screen_get_width(p->screen)-24,760*p->scale/100),MIN(wnck_screen_get_height(p->screen)-64,470*p->scale/100));
        g_signal_connect(p->settings,"destroy",G_CALLBACK(settings_closed),p);
        settings_contents(p);
    }
    gtk_widget_show(p->settings);gtk_window_present(GTK_WINDOW(p->settings));
}
