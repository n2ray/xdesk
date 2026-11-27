// Included from client.c before clientFrame. Saved geometry is the decorated outer frame.
static gboolean
desktopReadPlacement(Client *c, GdkRectangle *frame, int *maximized)
{
    char *state = NULL, *record = NULL;
    if (c->type != WINDOW_NORMAL || c->transient_for != None ||
        FLAG_TEST(c->flags, CLIENT_FLAG_SKIP_TASKBAR) || !c->class.res_class ||
        !g_file_get_contents("run/panel-state", &state, NULL, NULL))
        return FALSE;

    gboolean found = FALSE;
    gchar **lines = g_strsplit(state, "\n", 6);
    int remember = 0;
    if (g_strv_length(lines) >= 5 && sscanf(lines[3], "%*d %*d %*d %*d %d", &remember) == 1 &&
        remember && *lines[4] && strspn(lines[4], "0123456789abcdef-") == strlen(lines[4])) {
        char *key = g_compute_checksum_for_string(G_CHECKSUM_SHA256, c->class.res_class, -1);
        char *path = g_build_filename("config", "layout", lines[4], key, NULL);
        found = g_file_get_contents(path, &record, NULL, NULL) &&
                sscanf(record, "%d %d %d %d %d", &frame->x, &frame->y, &frame->width,
                       &frame->height, maximized) == 5 && frame->width > 0 && frame->height > 0;
        g_free(path);
        g_free(key);
    }
    g_strfreev(lines);
    g_free(record);
    g_free(state);
    return found;
}

static void
desktopRestorePlacement(Client *c)
{
    GdkRectangle saved;
    int maximized;
    if (!desktopReadPlacement(c, &saved, &maximized)) return;
    FLAG_UNSET(c->flags, CLIENT_FLAG_MAXIMIZED);
    GdkRectangle monitor;
    myScreenFindMonitorAtPoint(c->screen_info, saved.x, saved.y, &monitor);
    GdkRectangle area = clientMaxSpaceForGeometry(c, monitor);
    int left = frameLeft(c), top = frameTop(c);
    int borders = left + frameRight(c), title = top + frameBottom(c);
    int limitW = MAX(1, area.width - borders), limitH = MAX(1, area.height - title);
    int w = CLAMP(saved.width - borders, 1, limitW);
    int h = CLAMP(saved.height - title, 1, limitH);
    if (c->size->flags & PAspect) {
        double minRatio = (double)c->size->min_aspect.x / c->size->min_aspect.y;
        double maxRatio = (double)c->size->max_aspect.x / c->size->max_aspect.y;
        double ratio = CLAMP((double)w/h, minRatio, maxRatio);
        int keepWidthH = MIN(limitH, MAX(1, (int)MIN(w/ratio + .5, G_MAXINT)));
        int keepWidthW = MIN(w, MAX(1, (int)MIN(keepWidthH*ratio + .5, G_MAXINT)));
        int keepHeightW = MIN(limitW, MAX(1, (int)MIN(h*ratio + .5, G_MAXINT)));
        int keepHeightH = MIN(h, MAX(1, (int)MIN(keepHeightW/ratio + .5, G_MAXINT)));
        if (abs(keepWidthW-w)+abs(keepWidthH-h) <= abs(keepHeightW-w)+abs(keepHeightH-h)) {
            w=keepWidthW; h=keepWidthH;
        } else {
            w=keepHeightW; h=keepHeightH;
        }
        // Exact multiples for clients advertising an aspect-sized grid (e.g. fixed 1080p).
        if ((c->size->flags & PResizeInc) && !c->size->base_width && !c->size->base_height &&
            minRatio == maxRatio && (double)c->size->width_inc/c->size->height_inc == minRatio) {
            int units=MAX(1,MIN(w/c->size->width_inc,h/c->size->height_inc));
            w=units*c->size->width_inc; h=units*c->size->height_inc;
        }
    }
    c->width = clientCheckWidth(c, w, FALSE);
    c->height = clientCheckHeight(c, h, FALSE);
    c->x = CLAMP(saved.x, area.x, MAX(area.x, area.x+area.width-c->width-borders)) + left;
    c->y = CLAMP(saved.y, area.y, MAX(area.y, area.y+area.height-c->height-title)) + top;
    if (maximized && CLIENT_CAN_MAXIMIZE_WINDOW(c))
        FLAG_SET(c->flags, CLIENT_FLAG_MAXIMIZED);
}
