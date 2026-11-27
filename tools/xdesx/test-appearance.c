/* Exercise the panel publisher and a live GTK application in an isolated X11 session. */
#include <gtk/gtk.h>
#include <gdk/gdkx.h>
#include <X11/Xatom.h>
#include <string.h>

typedef struct { gboolean dark; Window dpi_owner; guint32 dpi_serial; } Panel;
#include "../../lorie-app/src/main/assets/xdesx/panel-dpi.h"

int main(int argc,char **argv) {
    gtk_init(&argc,&argv);
    GtkSettings *settings=gtk_settings_get_default();
    Panel panel={0};
    GSettings *guest=g_settings_new("org.gnome.desktop.interface");
    for(int i=0;i<3;++i) {
        panel.dark=i!=1;
        publish_desktop_settings(&panel,133);
        gint64 until=g_get_monotonic_time()+5*G_TIME_SPAN_SECOND;
        gboolean matched=FALSE;
        while(!matched && g_get_monotonic_time()<until) {
            while(g_main_context_iteration(NULL,FALSE)) {}
            gchar *theme=NULL,*scheme=g_settings_get_string(guest,"color-scheme");
            gint dpi=0;
            g_object_get(settings,"gtk-theme-name",&theme,"gtk-xft-dpi",&dpi,NULL);
            matched=!g_strcmp0(theme,panel.dark?"Adwaita-dark":"Adwaita") &&
                    !g_strcmp0(scheme,panel.dark?"prefer-dark":"prefer-light") && dpi==128*1024;
            g_free(theme);g_free(scheme);
            if(!matched) g_usleep(10000);
        }
        if(!matched){g_printerr("Theme, guest preference or DPI mismatch on transition %d\n",i);return 1;}
    }
    return 0;
}
