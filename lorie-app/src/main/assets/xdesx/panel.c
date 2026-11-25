#define WNCK_I_KNOW_THIS_IS_UNSTABLE
#include <libxfce4panel/libxfce4panel.h>
#include <xfconf/xfconf.h>
#include <libwnck/libwnck.h>
#include <gdk/gdkx.h>
#include <X11/Xatom.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <stddef.h>
#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    GtkWidget *current, *status;
    WnckScreen *screen;
    GFileMonitor *monitor;
    GtkCssProvider *css;
    int scale, render;
} Panel;

static gboolean action(const char *value) {
    const char *name = g_getenv("XDESX_MENU");
    struct sockaddr_un addr = {.sun_family = AF_UNIX};
    if (!name || strlen(name) >= sizeof(addr.sun_path)-1) return FALSE;
    strcpy(addr.sun_path+1, name);
    int fd = socket(AF_UNIX, SOCK_STREAM|SOCK_CLOEXEC, 0);
    if (fd < 0) return FALSE;
    char *line = g_strdup_printf("action %s\n", value);
    gboolean ok = connect(fd, (void*)&addr, offsetof(struct sockaddr_un,sun_path)+1+strlen(name)) == 0 &&
            send(fd, line, strlen(line), MSG_NOSIGNAL) == (ssize_t)strlen(line);
    g_free(line);
    close(fd);
    return ok;
}
static GtkWidget *item(GtkWidget *menu,const char *name) {
    GtkWidget *i=gtk_menu_item_new_with_label(name);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu),i);
    return i;
}
static GtkWidget *submenu(GtkWidget *menu,const char *name) {
    GtkWidget *sub=gtk_menu_new();
    gtk_menu_item_set_submenu(GTK_MENU_ITEM(item(menu,name)),sub);
    return sub;
}
static gboolean timeout_dialog(gpointer d) {
    gtk_dialog_response(GTK_DIALOG(d),GTK_RESPONSE_CANCEL);
    return G_SOURCE_REMOVE;
}
static void selected(GtkWidget *i,gpointer unused) {
    const char *op=g_object_get_data(G_OBJECT(i),"action");
    if (!action(op) || !g_str_has_prefix(op,"render-")) return;
    char *confirm=g_strdup_printf("confirm-%s",op+7);
    GtkWidget *d=gtk_message_dialog_new(NULL,0,GTK_MESSAGE_QUESTION,GTK_BUTTONS_NONE,"保留这个桌面渲染分辨率？");
    gtk_message_dialog_format_secondary_text(GTK_MESSAGE_DIALOG(d),"15 秒后自动恢复原设置。");
    gtk_dialog_add_button(GTK_DIALOG(d),"保留",GTK_RESPONSE_OK);
    guint timer=g_timeout_add_seconds(14,timeout_dialog,d);
    if (gtk_dialog_run(GTK_DIALOG(d))==GTK_RESPONSE_OK) action(confirm);
    if (g_main_context_find_source_by_id(NULL,timer)) g_source_remove(timer);
    gtk_widget_destroy(d);
    g_free(confirm);
}
static void command(GtkWidget *menu,const char *name,const char *value) {
    GtkWidget *i=item(menu,name);
    g_object_set_data_full(G_OBJECT(i),"action",g_strdup(value),g_free);
    g_signal_connect(i,"activate",G_CALLBACK(selected),NULL);
}
static void launch(GtkMenuItem *i,gpointer data) {
    const char *id=data;
    char *title=g_build_filename(g_get_home_dir(),"titles",id,NULL);
    char *argv[]={"xdesx-window",(char*)id,title,NULL};
    g_spawn_async(NULL,argv,NULL,G_SPAWN_SEARCH_PATH,NULL,NULL,NULL,NULL);
    g_free(title);
}
static void popup(GtkWidget *menu,GtkWidget *button) {
    g_signal_connect_swapped(menu,"selection-done",G_CALLBACK(gtk_widget_destroy),menu);
    gtk_widget_show_all(menu);
    gtk_menu_popup_at_widget(GTK_MENU(menu),button,GDK_GRAVITY_SOUTH_WEST,GDK_GRAVITY_NORTH_WEST,NULL);
}
static void main_menu(GtkWidget *button,Panel *p) {
    GtkWidget *menu=gtk_menu_new(), *android=submenu(menu,"安卓应用");
    char *data=NULL;
    if (g_file_get_contents("apps.tsv",&data,NULL,NULL)) {
        char **lines=g_strsplit(data,"\n",-1);
        for (int n=0;lines[n];n++) {
            char **f=g_strsplit(lines[n],"\t",4);
            if (g_strv_length(f)==4) {
                GtkWidget *i=gtk_image_menu_item_new_with_label(f[1]);
                if (g_path_is_absolute(f[2])) {
                    int size=24*p->scale/100;
                    GdkPixbuf *icon=gdk_pixbuf_new_from_file_at_scale(f[2],size,size,TRUE,NULL);
                    if (icon) {
                        gtk_image_menu_item_set_image(GTK_IMAGE_MENU_ITEM(i),gtk_image_new_from_pixbuf(icon));
                        gtk_image_menu_item_set_always_show_image(GTK_IMAGE_MENU_ITEM(i),TRUE);
                        g_object_unref(icon);
                    }
                }
                gtk_menu_shell_append(GTK_MENU_SHELL(strcmp(f[3],"mac")==0?menu:android),i);
                g_object_set_data_full(G_OBJECT(i),"id",g_strdup(f[0]),g_free);
                g_signal_connect(i,"activate",G_CALLBACK(launch),g_object_get_data(G_OBJECT(i),"id"));
            }
            g_strfreev(f);
        }
        g_strfreev(lines); g_free(data);
    }
    GtkWidget *settings=submenu(menu,"全局设置");
    GtkWidget *ui=submenu(settings,"界面大小"), *res=submenu(settings,"桌面渲染分辨率");
    int scales[]={100,133,200}, sizes[]={100,75,50};
    const char *scale_names[]={"1×","1.33×","2×"}, *res_names[]={"原生","75%","50%"};
    for (int n=0;n<3;n++) {
        char label[64],op[32];
        snprintf(label,sizeof(label),"%s%s",scales[n]==p->scale?"✓  ":"",scale_names[n]);
        snprintf(op,sizeof(op),"scale-%d",scales[n]); command(ui,label,op);
        snprintf(label,sizeof(label),"%s%s",sizes[n]==p->render?"✓  ":"",res_names[n]);
        snprintf(op,sizeof(op),"render-%d",sizes[n]); command(res,label,op);
    }
    popup(menu,button);
}
static void activate_window(GtkWidget *i,WnckWindow *w) {
    WnckWorkspace *ws=wnck_window_get_workspace(w);
    if (ws) wnck_workspace_activate(ws,gtk_get_current_event_time());
    wnck_window_activate(w,gtk_get_current_event_time());
}
static void window_menu(GtkWidget *button,Panel *p) {
    GtkWidget *menu=gtk_menu_new();
    WnckWindow *active=wnck_screen_get_active_window(p->screen);
    if (active && !wnck_window_is_skip_tasklist(active)) {
        Display *d=GDK_DISPLAY_XDISPLAY(gdk_display_get_default());
        Atom type; int format; unsigned long count,remaining; unsigned char *key=NULL;
        if (XGetWindowProperty(d,wnck_window_get_xid(active),XInternAtom(d,"_XDESX_KEY",False),0,8,
                False,XA_STRING,&type,&format,&count,&remaining,&key)==Success && key && count==32) {
            char *op=g_strdup_printf("full-%.*s",32,key);
            command(menu,"在外屏原生全屏打开",op);
            g_free(op);
        }
        if (key) XFree(key);
    }
    for (GList *l=wnck_screen_get_windows(p->screen);l;l=l->next) {
        WnckWindow *w=l->data;
        if (wnck_window_is_skip_tasklist(w)) continue;
        GtkWidget *i=item(menu,wnck_window_get_name(w));
        g_signal_connect_object(i,"activate",G_CALLBACK(activate_window),w,0);
    }
    popup(menu,button);
}
static void current(WnckScreen *screen,WnckWindow *previous,Panel *p) {
    WnckWindow *w=wnck_screen_get_active_window(screen);
    if (w && wnck_window_is_skip_tasklist(w)) w=NULL;
    gtk_button_set_label(GTK_BUTTON(p->current),w?wnck_window_get_name(w):"桌面");
    GdkPixbuf *icon=w?wnck_window_get_mini_icon(w):NULL;
    gtk_button_set_image(GTK_BUTTON(p->current),icon?gtk_image_new_from_pixbuf(icon):NULL);
    gtk_button_set_always_show_image(GTK_BUTTON(p->current),TRUE);
    GtkWidget *child=gtk_bin_get_child(GTK_BIN(p->current));
    if (GTK_IS_LABEL(child)) {
        gtk_label_set_max_width_chars(GTK_LABEL(child),28);
        gtk_label_set_ellipsize(GTK_LABEL(child),PANGO_ELLIPSIZE_END);
    }
}
static void refresh(Panel *p) {
    char *data=NULL;
    if (!g_file_get_contents("run/panel-state",&data,NULL,NULL)) return;
    char **fields=g_strsplit(data,"\n",4);
    if (g_strv_length(fields)>=3) {
        int scale=atoi(fields[0]); p->render=atoi(fields[1]);
        gtk_label_set_text(GTK_LABEL(p->status),fields[2]);
        if (scale!=p->scale && (scale==100 || scale==133 || scale==200)) {
            p->scale=scale;
            char *css=g_strdup_printf("#xdesx-bar, #xdesx-bar button, menuitem {font-size:%dpx;} #xdesx-bar button {padding:0 %dpx;} #xdesx-bar label {margin:0 %dpx;}",14*scale/100,8*scale/100,3*scale/100);
            gtk_css_provider_load_from_data(p->css,css,-1,NULL); g_free(css);
            xfconf_channel_set_uint(xfconf_channel_get("xfce4-panel"),"/panels/panel-1/size",(36*scale+50)/100);
            char font[32]; snprintf(font,sizeof(font),"Sans Bold %d",(10*scale+50)/100);
            xfconf_channel_set_string(xfconf_channel_get("xfwm4"),"/general/title_font",font);
        }
    }
    g_strfreev(fields); g_free(data);
}
static void state_changed(GFileMonitor *m,GFile *file,GFile *other,GFileMonitorEvent e,Panel *p) {
    char *name=g_file_get_basename(file);
    char *target=other?g_file_get_basename(other):NULL;
    if (g_strcmp0(name,"panel-state")==0 || g_strcmp0(target,"panel-state")==0) refresh(p);
    g_free(name); g_free(target);
}
static void destroy(XfcePanelPlugin *plugin,Panel *p) {
    g_signal_handlers_disconnect_by_data(p->screen,p);
    g_clear_object(&p->monitor);
    gtk_style_context_remove_provider_for_screen(gdk_screen_get_default(),GTK_STYLE_PROVIDER(p->css));
    g_object_unref(p->css); g_free(p);
}
static void construct(XfcePanelPlugin *plugin) {
    Panel *p=g_new0(Panel,1);
    GtkWidget *bar=gtk_box_new(GTK_ORIENTATION_HORIZONTAL,4);
    gtk_widget_set_name(bar,"xdesx-bar");
    GtkWidget *main=gtk_button_new_with_label("xdesx  ▾"); p->current=gtk_button_new();
    gtk_widget_set_focus_on_click(main,FALSE); gtk_widget_set_focus_on_click(p->current,FALSE);
    p->status=gtk_label_new("");
    gtk_box_pack_start(GTK_BOX(bar),main,FALSE,FALSE,0);
    gtk_box_pack_start(GTK_BOX(bar),p->current,FALSE,FALSE,0);
    gtk_box_pack_end(GTK_BOX(bar),p->status,FALSE,FALSE,8);
    gtk_container_add(GTK_CONTAINER(plugin),bar);
    xfce_panel_plugin_set_expand(plugin,TRUE);
    p->screen=wnck_screen_get_default(); wnck_screen_force_update(p->screen);
    g_signal_connect(p->screen,"active-window-changed",G_CALLBACK(current),p);
    g_signal_connect(main,"clicked",G_CALLBACK(main_menu),p);
    g_signal_connect(p->current,"clicked",G_CALLBACK(window_menu),p);
    p->css=gtk_css_provider_new();
    gtk_style_context_add_provider_for_screen(gdk_screen_get_default(),GTK_STYLE_PROVIDER(p->css),GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
    GFile *file=g_file_new_for_path("run");
    p->monitor=g_file_monitor_directory(file,G_FILE_MONITOR_WATCH_MOVES,NULL,NULL);
    if (p->monitor) g_signal_connect(p->monitor,"changed",G_CALLBACK(state_changed),p);
    g_object_unref(file);
    refresh(p); current(p->screen,NULL,p);
    gtk_widget_show_all(bar);
    g_signal_connect(plugin,"free-data",G_CALLBACK(destroy),p);
}
XFCE_PANEL_PLUGIN_REGISTER(construct)
