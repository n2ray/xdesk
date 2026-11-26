static void style_desktop(Panel *p) {
    const char *colors=p->dark?
        "@define-color surface #222326; @define-color card #303136; @define-color ink #f2f3f5; @define-color hover #41444b; @define-color accent #355678; @define-color divider #45474c;":
        "@define-color surface #f2f3f6; @define-color card #ffffff; @define-color ink #20232a; @define-color hover #e4e7ec; @define-color accent #d6e8fc; @define-color divider #d6d8dd;";
    g_object_set(gtk_settings_get_default(),"gtk-application-prefer-dark-theme",p->dark,NULL);
    char *css=g_strdup_printf("%s * {font-family:'Source Han Sans CN','Noto Sans CJK SC',sans-serif;font-size:%dpx;}"
        ".xfce4-panel,#xdesx-bar,#xdesx-launcher,#xdesx-notifications,#xdesx-settings,menu,popover {background:@surface;color:@ink;}"
        "button,entry,menuitem {color:@ink;} #xdesx-launcher button,#xdesx-notifications button,#xdesx-settings button,#xdesx-bar button {background:@card;color:@ink;border:0;box-shadow:none;border-radius:6px;padding:4px %dpx;}"
        "#xdesx-bar button {background:transparent;padding:0 %dpx;min-height:0;}"
        "#xdesx-bar .task {background:@card;} #xdesx-bar .task.active,#xdesx-settings .chosen,#xdesx-launcher button:checked {background:@accent;}"
        "#xdesx-settings button:hover,#xdesx-launcher button:hover,#xdesx-notifications button:hover,#xdesx-bar button:hover,menuitem:hover {background:@hover;}"
        "#xdesx-settings stacksidebar list {background:@surface;} #xdesx-settings stacksidebar row {color:@ink;padding:6px;border-radius:7px;margin:2px 6px;}"
        "#xdesx-settings stacksidebar row:selected {background:@accent;} #xdesx-settings stack {background:@card;}"
        ".setting-row {padding:8px;border-bottom:1px solid @divider;} .page-title {font-weight:bold;font-size:%dpx;}"
        "#xdesx-launcher #xdesx-power {color:%s;font-weight:bold;} #xdesx-launcher entry {background:@card;color:@ink;}"
        "#category-content,#xdesx-launcher #category-tabs button:checked {background:@card;}"
        "#xdesx-launcher #category-tabs button {background:transparent;border-radius:6px 6px 0 0;}"
        "#xdesx-launcher button.app-tile {padding:%dpx;background:transparent;}"
        "#xdesx-launcher button.app-tile:hover,#xdesx-launcher button.app-tile:focus {background:@hover;}"
        "menuitem {padding:5px 9px;}",colors,14*p->scale/100,8*p->scale/100,8*p->scale/100,22*p->scale/100,p->dark?"#ff827b":"#ba322e",7*p->scale/100);
    gtk_css_provider_load_from_data(p->css,css,-1,NULL);
    g_free(css);
}
