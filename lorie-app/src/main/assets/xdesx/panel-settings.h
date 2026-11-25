static void setting_row(GtkWidget *page,const char *label,const char *verb,const int *values,const char **names,int n,int current) {
    GtkWidget *row=gtk_box_new(GTK_ORIENTATION_HORIZONTAL,10);
    gtk_box_pack_start(GTK_BOX(page),row,FALSE,FALSE,4);
    gtk_box_pack_start(GTK_BOX(row),gtk_label_new(label),FALSE,FALSE,0);
    GtkWidget *group=gtk_box_new(GTK_ORIENTATION_HORIZONTAL,3);
    gtk_box_pack_end(GTK_BOX(row),group,FALSE,FALSE,0);
    for(int i=0;i<n;i++) {
        GtkWidget *b=gtk_button_new_with_label(names[i]);
        if(values[i]==current) gtk_style_context_add_class(gtk_widget_get_style_context(b),"chosen");
        g_object_set_data_full(G_OBJECT(b),"action",g_strdup_printf("%s-%d",verb,values[i]),g_free);
        g_signal_connect(b,"clicked",G_CALLBACK(selected),NULL);
        gtk_box_pack_start(GTK_BOX(group),b,FALSE,FALSE,0);
    }
}
static GtkWidget *page(GtkStack *stack,const char *id,const char *title) {
    GtkWidget *box=gtk_box_new(GTK_ORIENTATION_VERTICAL,12);
    gtk_container_set_border_width(GTK_CONTAINER(box),18);
    gtk_stack_add_titled(stack,box,id,title);
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
static void settings_contents(Panel *p) {
    const char *last=p->stack?gtk_stack_get_visible_child_name(GTK_STACK(p->stack)):NULL;
    char *restore=g_strdup(last);
    if(p->stack) gtk_widget_destroy(gtk_widget_get_parent(p->stack));
    GtkWidget *body=gtk_box_new(GTK_ORIENTATION_HORIZONTAL,1);
    p->stack=gtk_stack_new();
    GtkStack *s=GTK_STACK(p->stack);
    GtkWidget *nav=gtk_stack_sidebar_new();
    gtk_stack_sidebar_set_stack(GTK_STACK_SIDEBAR(nav),s);
    gtk_box_pack_start(GTK_BOX(body),nav,FALSE,FALSE,0);
    gtk_box_pack_start(GTK_BOX(body),p->stack,TRUE,TRUE,0);
    gtk_container_add(GTK_CONTAINER(p->settings),body);
    GtkWidget *view=page(s,"display","显示");
    char *resolution=g_strdup_printf("桌面当前渲染尺寸   %d × %d",wnck_screen_get_width(p->screen),wnck_screen_get_height(p->screen));
    gtk_box_pack_start(GTK_BOX(view),gtk_label_new(resolution),FALSE,FALSE,4);g_free(resolution);
    const int scales[]={100,133,200},renders[]={100,75,50},speed[]={75,100,150},yesno[]={1,0},limits[]={2,3,4,6};
    const char *sc[]={"1×","1.33×","2×"},*rs[]={"原生","75%","50%"},*sp[]={"慢","标准","快"},*yn[]={"开","关"},*ls[]={"2","3","4","6"};
    setting_row(view,"界面大小","scale",scales,sc,3,p->scale);
    setting_row(view,"渲染分辨率","render",renders,rs,3,p->render);
    wallpaper_controls(page(s,"wallpaper","桌面背景"),p);
    view=page(s,"windows","桌面与窗口");
    setting_row(view,"记住窗口布局","remember",yesno,yn,2,p->remember);
    GtkWidget *b=gtk_button_new_with_label("将窗口移回屏幕内");g_signal_connect(b,"clicked",G_CALLBACK(bring_back),p);gtk_box_pack_start(GTK_BOX(view),b,FALSE,FALSE,0);
    view=page(s,"input","输入与触摸板");
    setting_row(view,"鼠标速度","mouse",speed,sp,3,p->mouse);
    setting_row(view,"触摸板速度","pad",speed,sp,3,p->pad);
    setting_row(view,"反向滚动","reverse",yesno,yn,2,p->reverse);
    view=page(s,"notifications","通知");
    GtkWidget *names=notify_button("仅显示应用名","notify-names"),*preview=notify_button("显示通知摘要","notify-summary");
    gtk_style_context_add_class(gtk_widget_get_style_context(p->notify_names?names:preview),"chosen");
    gtk_box_pack_start(GTK_BOX(view),names,FALSE,FALSE,0);gtk_box_pack_start(GTK_BOX(view),preview,FALSE,FALSE,0);
    gtk_box_pack_start(GTK_BOX(view),notify_button("手机通知访问设置","notify-access"),FALSE,FALSE,0);
    mac_settings(page(s,"mac","Mac 屏幕共享"),p);
    view=page(s,"linux","Ubuntu / Linux");
    gchar *ubuntu=NULL;
    g_file_get_contents("run/ubuntu-state",&ubuntu,NULL,NULL);
    gboolean ready=ubuntu && g_str_has_prefix(ubuntu,"ready\n");
    gboolean busy=ubuntu && g_str_has_prefix(ubuntu,"busy\n");
    char *status=ubuntu?strchr(ubuntu,'\n'):NULL;
    GtkWidget *summary=gtk_label_new(status?status+1:"正在读取安装状态");
    gtk_label_set_line_wrap(GTK_LABEL(summary),TRUE);
    gtk_box_pack_start(GTK_BOX(view),summary,FALSE,FALSE,0);
    b=gtk_button_new_with_label(ready?"刷新 Linux 应用列表":"安装 Ubuntu 核心");
    gtk_widget_set_sensitive(b,ubuntu && !busy);
    g_object_set_data(G_OBJECT(b),"action",ready?"linux-refresh":"linux-install");
    g_signal_connect(b,"clicked",G_CALLBACK(selected),NULL);
    gtk_box_pack_start(GTK_BOX(view),b,FALSE,FALSE,0);
    g_free(ubuntu);
    view=page(s,"power","性能与电源");
    char *count=g_strdup_printf("已打开 %d 个应用窗口",p->opened);
    gtk_box_pack_start(GTK_BOX(view),gtk_label_new(count),FALSE,FALSE,4);g_free(count);
    setting_row(view,"窗口上限","limit",limits,ls,4,p->limit);
    view=page(s,"about","关于与诊断");
    gtk_box_pack_start(GTK_BOX(view),gtk_label_new("xdesx · xfwm4 4.20.0 · xfce4-panel 4.20"),FALSE,FALSE,2);
    b=gtk_button_new_with_label("恢复外屏原生分辨率和 1× 界面");
    g_object_set_data(G_OBJECT(b),"action","recover");g_signal_connect(b,"clicked",G_CALLBACK(selected),NULL);
    gtk_box_pack_start(GTK_BOX(view),b,FALSE,FALSE,0);
    gtk_widget_show_all(body);
    if(restore) gtk_stack_set_visible_child_name(s,restore);
    g_free(restore);
}
static void settings_closed(GtkWidget *w,Panel *p) { p->settings=NULL;p->stack=NULL; }
static void settings_open(GtkWidget *unused,Panel *p) {
    if(!p->settings) {
        p->settings=gtk_window_new(GTK_WINDOW_TOPLEVEL);
        gtk_window_set_title(GTK_WINDOW(p->settings),"全局设置");
        gtk_widget_set_name(p->settings,"xdesx-settings");
        gtk_window_set_default_size(GTK_WINDOW(p->settings),550*p->scale/100,320*p->scale/100);
        g_signal_connect(p->settings,"destroy",G_CALLBACK(settings_closed),p);
        settings_contents(p);
    }
    gtk_widget_show(p->settings);gtk_window_present(GTK_WINDOW(p->settings));
}
