#define WNCK_I_KNOW_THIS_IS_UNSTABLE
#include <libxfce4panel/libxfce4panel.h>
#include <xfconf/xfconf.h>
#include <libwnck/libwnck.h>
#include <gdk/gdkx.h>
#include <X11/Xatom.h>
#include <X11/Xcursor/Xcursor.h>
#include <X11/extensions/Xfixes.h>
#include <math.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <stddef.h>
#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    GtkWidget *tasks, *task_scroll, *separator, *status, *logo, *icons, *settings, *stack;
    int mouse,pad,limit,reverse,remember,opened,battery,charging;
    char network[64],profile[64],options[96];
    char *mac_id;
    WnckScreen *screen;
    GFileMonitor *monitor;
    GtkCssProvider *css;
    GtkWidget *launcher, *main_button, *bell;
    int language, appearance, dark;
    GtkWidget *bell_art,*notification_popup;
    GtkWidget *ubuntu_status,*ubuntu_action,*ubuntu_manage;
    GtkWidget *resolution_label,*count_label,*names_button,*preview_button,*mac_page;
    int notify_access,notify_ready,notify_names,notify_unread;
    Pixmap wallpaper;
    int cursor_event;
    int scale, render, return_sequence;
    GtkWidget *fullscreen_bar, *fullscreen_reveal;
    guint tasks_idle;
    guint reveal_timer, hide_timer;
} Panel;

#include "panel-language.h"
#include "panel-art.h"


static int menu_connect(void) {
    const char *name = g_getenv("XDESX_MENU");
    struct sockaddr_un addr = {.sun_family = AF_UNIX};
    if (!name || strlen(name) >= sizeof(addr.sun_path)-1) return -1;
    strcpy(addr.sun_path+1, name);
    int fd = socket(AF_UNIX, SOCK_STREAM|SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    if(connect(fd,(void*)&addr,offsetof(struct sockaddr_un,sun_path)+1+strlen(name))<0){close(fd);return -1;}
    return fd;
}
static gboolean action(const char *value) {
    int fd=menu_connect();if(fd<0)return FALSE;
    char *line = g_strdup_printf("action %s\n", value);
    gboolean ok = send(fd, line, strlen(line), MSG_NOSIGNAL) == (ssize_t)strlen(line);
    g_free(line);
    close(fd);
    return ok;
}
/* X fullscreen stays inside X, so its edge and button must see X pointer events. */
static void cancel_timer(guint *timer) {
    if (*timer) g_source_remove(*timer);
    *timer = 0;
}
static gboolean reveal_bar(gpointer data) {
    Panel *p = data;
    p->reveal_timer = 0;
    gtk_revealer_set_reveal_child(GTK_REVEALER(p->fullscreen_reveal), TRUE);
    return G_SOURCE_REMOVE;
}
static gboolean hide_bar(gpointer data) {
    Panel *p = data;
    p->hide_timer = 0;
    gtk_revealer_set_reveal_child(GTK_REVEALER(p->fullscreen_reveal), FALSE);
    gtk_window_resize(GTK_WINDOW(p->fullscreen_bar), 1, 1);
    return G_SOURCE_REMOVE;
}
static gboolean bar_crossing(GtkWidget *widget, GdkEventCrossing *e, Panel *p) {
    if (e->detail == GDK_NOTIFY_INFERIOR) return FALSE;
    cancel_timer(&p->reveal_timer);
    cancel_timer(&p->hide_timer);
    if (e->type == GDK_ENTER_NOTIFY && !(e->state & GDK_BUTTON1_MASK))
        p->reveal_timer = g_timeout_add(300, reveal_bar, p);
    if (e->type == GDK_LEAVE_NOTIFY)
        p->hide_timer = g_timeout_add(200, hide_bar, p);
    return FALSE;
}
static void leave_fullscreen(GtkButton *button, Panel *p) {
    WnckWindow *w = wnck_screen_get_active_window(p->screen);
    if (w && wnck_window_is_fullscreen(w)) wnck_window_set_fullscreen(w, FALSE);
}
static void remove_fullscreen_bar(Panel *p) {
    cancel_timer(&p->reveal_timer);
    cancel_timer(&p->hide_timer);
    if (p->fullscreen_bar) gtk_widget_destroy(p->fullscreen_bar);
    p->fullscreen_bar = p->fullscreen_reveal = NULL;
}
static void update_fullscreen(Panel *p) {
    WnckWindow *w = wnck_screen_get_active_window(p->screen);
    if (!w || !wnck_window_is_fullscreen(w)) {
        remove_fullscreen_bar(p);
        return;
    }
    if (p->fullscreen_bar) return;
    GdkScreen *screen = gdk_screen_get_default();
    int width = MAX(100, 136 * p->scale / 100);
    GtkWidget *bar = gtk_window_new(GTK_WINDOW_POPUP);
    p->fullscreen_bar = bar;
    gtk_window_set_accept_focus(GTK_WINDOW(bar), FALSE);
    gtk_window_set_resizable(GTK_WINDOW(bar), FALSE);
    gtk_window_move(GTK_WINDOW(bar), (gdk_screen_get_width(screen) - width) / 2, 0);
    gtk_widget_set_size_request(bar, width, 2);
    GtkWidget *reveal = gtk_revealer_new();
    p->fullscreen_reveal = reveal;
    gtk_revealer_set_transition_type(GTK_REVEALER(reveal), GTK_REVEALER_TRANSITION_TYPE_SLIDE_DOWN);
    gtk_revealer_set_transition_duration(GTK_REVEALER(reveal), 160);
    GtkWidget *button = gtk_button_new_with_label(T("返回窗口"));
    gtk_widget_set_size_request(button, width, MAX(32, 38 * p->scale / 100));
    gtk_widget_set_can_focus(button, FALSE);
    gtk_container_add(GTK_CONTAINER(reveal), button);
    gtk_container_add(GTK_CONTAINER(bar), reveal);
    gtk_widget_add_events(bar, GDK_ENTER_NOTIFY_MASK | GDK_LEAVE_NOTIFY_MASK);
    g_signal_connect(bar, "enter-notify-event", G_CALLBACK(bar_crossing), p);
    g_signal_connect(bar, "leave-notify-event", G_CALLBACK(bar_crossing), p);
    g_signal_connect(button, "clicked", G_CALLBACK(leave_fullscreen), p);
    gtk_widget_show_all(bar);
}
#include "panel-layout.h"
static GtkWidget *item(GtkWidget *menu,const char *name) {
    GtkWidget *i=gtk_menu_item_new_with_label(name);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu),i);
    return i;
}
static gboolean timeout_dialog(gpointer d) {
    gtk_dialog_response(GTK_DIALOG(d),GTK_RESPONSE_CANCEL);
    return G_SOURCE_REMOVE;
}
static void selected(GtkWidget *i,gpointer unused) {
    const char *op=g_object_get_data(G_OBJECT(i),"action");
    if (!strcmp(op,"linux-install")) {
        GtkWidget *d=gtk_message_dialog_new(GTK_WINDOW(gtk_widget_get_toplevel(i)),GTK_DIALOG_MODAL,GTK_MESSAGE_QUESTION,GTK_BUTTONS_NONE,"%s",T("下载 Ubuntu 应用环境？"));
        gtk_message_dialog_format_secondary_text(GTK_MESSAGE_DIALOG(d),"%s",T("首次使用需要联网下载并校验 ARM64 镜像，再解压到本机。建议使用 Wi-Fi 并预留数 GB 空间。安装后即可从应用菜单启动 Linux 应用。"));
        gtk_dialog_add_buttons(GTK_DIALOG(d),T("取消"),GTK_RESPONSE_CANCEL,T("下载并安装"),GTK_RESPONSE_OK,NULL);
        gboolean install=gtk_dialog_run(GTK_DIALOG(d))==GTK_RESPONSE_OK;
        gtk_widget_destroy(d);
        if (!install) return;
    }
    if (!action(op) || !g_str_has_prefix(op,"render-")) return;
    char *confirm=g_strdup_printf("confirm-%s",op+7);
    GtkWidget *d=gtk_message_dialog_new(NULL,0,GTK_MESSAGE_QUESTION,GTK_BUTTONS_NONE,"%s",T("保留这个桌面渲染分辨率？"));
    gtk_message_dialog_format_secondary_text(GTK_MESSAGE_DIALOG(d),"%s",T("15 秒后自动恢复原设置。"));
    gtk_dialog_add_button(GTK_DIALOG(d),T("保留"),GTK_RESPONSE_OK);
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
#include "panel-wallpaper.h"
#include "panel-cursor.h"
#include "panel-notifications.h"
#include "panel-settings.h"

static void popup(GtkWidget *menu,GtkWidget *button) {
    g_signal_connect_swapped(menu,"selection-done",G_CALLBACK(gtk_widget_destroy),menu);
    gtk_widget_show_all(menu);
    gtk_menu_popup_at_widget(GTK_MENU(menu),button,GDK_GRAVITY_SOUTH_WEST,GDK_GRAVITY_NORTH_WEST,NULL);
}
#include "panel-launcher.h"
static void activate_window(GtkWidget *i,WnckWindow *w) {
    WnckWorkspace *ws=wnck_window_get_workspace(w);
    if (ws) wnck_workspace_activate(ws,gtk_get_current_event_time());
    wnck_window_activate(w,gtk_get_current_event_time());
}
#include "panel-tasks.h"
#include "panel-style.h"
static void refresh(Panel *p) {
    char *data=NULL;
    if (!g_file_get_contents("run/panel-state",&data,NULL,NULL)) return;
    char **fields=g_strsplit(data,"\n",6);
    if (g_strv_length(fields)>=5) {
        int scale=atoi(fields[0]),render=atoi(fields[1]);
        gboolean settings_changed=scale!=p->scale || render!=p->render || strcmp(fields[3],p->options)!=0;
        g_strlcpy(p->options,fields[3],sizeof(p->options));
        p->render=render;
        int seq=0;
        int lang=2, dark=0;
        sscanf(fields[3],"%d %d %d %d %d %d %d %d %d %d %d",&p->mouse,&p->pad,&p->limit,&p->reverse,&p->remember,&p->opened,&seq,&p->language,&lang,&p->appearance,&dark);
        gboolean language_changed=lang!=ui_language;
        gboolean palette_changed=dark!=p->dark;
        ui_language=lang; p->dark=dark;
        if(seq!=p->return_sequence) {
            p->return_sequence=seq;
            WnckWindow *w=wnck_screen_get_active_window(p->screen);
            if(w && wnck_window_is_fullscreen(w)) wnck_window_set_fullscreen(w,FALSE);
        }
        g_strlcpy(p->profile,fields[4],sizeof(p->profile));
        gchar **parts=g_strsplit(fields[2],"     ",3);
        if(g_strv_length(parts)==3) {
            g_strlcpy(p->network,parts[0],sizeof(p->network));
            p->battery=-1;p->charging=g_str_has_prefix(parts[1],"充电");
            const char *num=strchr(parts[1],' ');
            if(num) p->battery=atoi(num+1);
            gtk_label_set_text(GTK_LABEL(p->status),parts[2]);
            gtk_widget_set_tooltip_text(p->icons,fields[5]?fields[5]:fields[2]);gtk_widget_queue_draw(p->icons);
        }
        g_strfreev(parts);
        if ((scale!=p->scale || palette_changed || language_changed) && (scale==100 || scale==133 || scale==167 || scale==200)) {
            p->scale=scale;
            int icon=(24*scale+50)/100;
            gtk_widget_set_size_request(p->logo,icon,icon);gtk_widget_set_size_request(p->icons,94*scale/100,20*scale/100);
            style_desktop(p);
            xfconf_channel_set_uint(xfconf_channel_get("xfce4-panel"),"/panels/panel-1/size",(36*scale+50)/100);
            char theme[32];snprintf(theme,sizeof(theme),"Xdesx%s%d",dark?"Dark":"",scale);
            xfconf_channel_set_string(xfconf_channel_get("xfwm4"),"/general/theme",theme);
            xfconf_channel_set_string(xfconf_channel_get("xfwm4"),"/general/title_alignment","left");
            gtk_widget_set_size_request(p->bell_art,MAX(16,22*scale/100),MAX(16,22*scale/100));
            desktop_cursor(p);
            schedule_tasks(p);
            char font[80]; snprintf(font,sizeof(font),"Roboto,Noto Sans CJK SC Bold %d",(10*scale+50)/100);
            xfconf_channel_set_string(xfconf_channel_get("xfwm4"),"/general/title_font",font);
        }
        if(language_changed){
            gtk_widget_set_tooltip_text(p->main_button,T("应用"));
            gtk_widget_set_tooltip_text(p->bell,T("通知中心"));
            if(p->launcher) gtk_widget_hide(p->launcher);
            if(p->notification_popup) gtk_widget_hide(p->notification_popup);
            if(p->settings){
                gchar *id=g_strdup(gtk_stack_get_visible_child_name(GTK_STACK(p->stack)));
                gtk_widget_destroy(gtk_bin_get_child(GTK_BIN(p->settings)));
                gtk_window_set_title(GTK_WINDOW(p->settings),T("全局设置"));
                settings_contents(p);
                gtk_stack_set_visible_child_name(GTK_STACK(p->stack),id);g_free(id);
            }
        }
        if(p->settings && settings_changed) update_setting_values(p);
    }
    g_strfreev(fields); g_free(data);
}
static void state_changed(GFileMonitor *m,GFile *file,GFile *other,GFileMonitorEvent e,Panel *p) {
    char *name=g_file_get_basename(file);
    char *target=other?g_file_get_basename(other):NULL;
    if (g_strcmp0(name,"panel-state")==0 || g_strcmp0(target,"panel-state")==0) refresh(p);
    if (!g_strcmp0(name,"wallpaper-update") || !g_strcmp0(target,"wallpaper-update")) paint_wallpaper(p);
    if (!g_strcmp0(name,"notify-state") || !g_strcmp0(target,"notify-state")) {
        int previous=p->notify_names;notification_refresh(p);
        if(p->settings && previous!=p->notify_names)update_setting_values(p);
    }
    if (p->settings && (!g_strcmp0(name,"mac-hosts") || !g_strcmp0(target,"mac-hosts")))
        refresh_mac_settings(p);
    if (p->settings && (!g_strcmp0(name,"ubuntu-state") || !g_strcmp0(target,"ubuntu-state")))
        update_ubuntu_controls(p);
    g_free(name); g_free(target);
}
static void destroy(XfcePanelPlugin *plugin,Panel *p) {
    remove_fullscreen_bar(p);
    if(p->launcher) gtk_widget_hide(p->launcher);
    if(p->notification_popup) gtk_widget_hide(p->notification_popup);
    if(p->settings) gtk_widget_destroy(p->settings);
    Display *d=GDK_DISPLAY_XDISPLAY(gdk_display_get_default());
    g_signal_handlers_disconnect_by_data(gdk_screen_get_default(),p);
    Pixmap wallpaper=p->wallpaper;
    if(wallpaper) { XDeleteProperty(d,DefaultRootWindow(d),XInternAtom(d,"_XROOTPMAP_ID",False));XFreePixmap(d,wallpaper); }
    if(p->cursor_event) gdk_window_remove_filter(NULL,cursor_event,p);
    if (p->tasks_idle) g_source_remove(p->tasks_idle);
    for (GList *l=wnck_screen_get_windows(p->screen);l;l=l->next)
        g_signal_handlers_disconnect_by_data(l->data,p);
    stop_tracking(p);g_free(p->mac_id);
    g_signal_handlers_disconnect_by_data(p->screen,p);
    g_clear_object(&p->monitor);
    gtk_style_context_remove_provider_for_screen(gdk_screen_get_default(),GTK_STYLE_PROVIDER(p->css));
    g_object_unref(p->css); g_free(p);
}
static void construct(XfcePanelPlugin *plugin) {
    Panel *p=g_new0(Panel,1);
    GtkWidget *bar=gtk_box_new(GTK_ORIENTATION_HORIZONTAL,4);
    gtk_widget_set_name(bar,"xdesx-bar");
    GtkWidget *main=p->main_button=gtk_button_new();
    gtk_widget_set_focus_on_click(main,FALSE);
    p->logo=gtk_drawing_area_new();
    g_signal_connect(p->logo,"draw",G_CALLBACK(draw_logo),p);gtk_container_add(GTK_CONTAINER(main),p->logo);
    gtk_widget_set_tooltip_text(main,T("xdesx 菜单"));
    p->icons=gtk_drawing_area_new();g_signal_connect(p->icons,"draw",G_CALLBACK(draw_status),p);
    p->status=gtk_label_new("");
    gtk_box_pack_start(GTK_BOX(bar),main,FALSE,FALSE,0);
    p->separator=gtk_separator_new(GTK_ORIENTATION_VERTICAL);gtk_widget_set_valign(p->separator,GTK_ALIGN_CENTER);gtk_widget_set_size_request(p->separator,1,18);gtk_box_pack_start(GTK_BOX(bar),p->separator,FALSE,FALSE,0);
    p->tasks=gtk_box_new(GTK_ORIENTATION_HORIZONTAL,3);
    p->task_scroll=gtk_scrolled_window_new(NULL,NULL);
    gtk_scrolled_window_set_policy(GTK_SCROLLED_WINDOW(p->task_scroll),GTK_POLICY_EXTERNAL,GTK_POLICY_NEVER);
    gtk_container_add(GTK_CONTAINER(p->task_scroll),p->tasks);
    gtk_box_pack_start(GTK_BOX(bar),p->task_scroll,TRUE,TRUE,0);
    g_signal_connect(p->task_scroll,"scroll-event",G_CALLBACK(scroll_tasks),NULL);
    GtkWidget *bell=p->bell=gtk_button_new();p->bell_art=gtk_drawing_area_new();
    gtk_widget_set_focus_on_click(bell,FALSE);
    gtk_widget_set_tooltip_text(bell,T("通知中心"));gtk_container_add(GTK_CONTAINER(bell),p->bell_art);
    gtk_box_pack_end(GTK_BOX(bar),bell,FALSE,FALSE,0);
    gtk_box_pack_end(GTK_BOX(bar),p->status,FALSE,FALSE,8);
    gtk_box_pack_end(GTK_BOX(bar),p->icons,FALSE,FALSE,2);
    g_signal_connect(p->bell_art,"draw",G_CALLBACK(bell_draw),p);
    g_signal_connect(bell,"clicked",G_CALLBACK(show_notifications),p);
    gtk_container_add(GTK_CONTAINER(plugin),bar);
    xfce_panel_plugin_set_expand(plugin,TRUE);
    p->screen=wnck_screen_get_default(); wnck_screen_force_update(p->screen);
    g_signal_connect(p->screen,"active-window-changed",G_CALLBACK(active_task_changed),p);
    g_signal_connect(p->screen,"window-opened",G_CALLBACK(watch_task),p);
    g_signal_connect(p->screen,"window-closed",G_CALLBACK(task_list_changed),p);
    g_signal_connect(main,"clicked",G_CALLBACK(main_menu),p);
    for (GList *l=wnck_screen_get_windows(p->screen);l;l=l->next) watch_task(p->screen,l->data,p);
    p->css=gtk_css_provider_new();
    gtk_style_context_add_provider_for_screen(gdk_screen_get_default(),GTK_STYLE_PROVIDER(p->css),GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
    GFile *file=g_file_new_for_path("run");
    p->monitor=g_file_monitor_directory(file,G_FILE_MONITOR_WATCH_MOVES,NULL,NULL);
    if (p->monitor) g_signal_connect(p->monitor,"changed",G_CALLBACK(state_changed),p);
    g_object_unref(file);
    refresh(p);notification_refresh(p);
    g_signal_connect(p->screen,"window-opened",G_CALLBACK(watch_window),p);
    for(GList *l=wnck_screen_get_windows(p->screen);l;l=l->next) watch_window(p->screen,l->data,p);
    paint_wallpaper(p);watch_cursor(p);
    g_signal_connect(gdk_screen_get_default(),"size-changed",G_CALLBACK(wallpaper_size),p);
    gtk_widget_show_all(bar);
    gtk_widget_set_no_show_all(p->task_scroll,TRUE);
    gtk_widget_set_no_show_all(p->separator,TRUE);
    schedule_tasks(p);
    g_signal_connect(plugin,"free-data",G_CALLBACK(destroy),p);
}
XFCE_PANEL_PLUGIN_REGISTER(construct)
