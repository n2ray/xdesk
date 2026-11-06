package com.termux.x11;

import android.content.Context;
import com.termux.x11.app.R;

/**
 * Text for the user from the shell-uid process (InjectorService and what it runs: TaskMover, AudioRouter,
 * DensityOverride, AppDisplay). That process runs through app_process with a system context and cannot rely on the
 * app's resources or the user's language, so it sends a stable code, "#code" or "#code:detail", and the app process
 * turns it into the user's language with {@link #localize}. Anything else it sends, such as an error from the system or
 * from am, is shown as it is. A server of another install's version is ended (see ShellActivation), so both sides know
 * the same codes; an unknown one is still shown, as the raw code.
 */
final class ShellText {
    static final String SESSION_OVER = "session_over";
    static final String INJECT_REFUSED = "inject_refused";
    static final String NO_PHYSICAL_INPUT = "no_physical_input";
    static final String NOT_CONNECTED = "not_connected";
    static final String TEXT_NOT_SENT = "text_not_sent";
    static final String AUDIO_RESULT = "audio_result";
    static final String AUDIO_UNSUPPORTED = "audio_unsupported";
    static final String AUDIO_NO_CLEAR = "audio_no_clear";
    static final String AUDIO_CLEAR_RESULT = "audio_clear_result";
    static final String PHONE_SCREEN = "phone_screen";
    static final String NO_TASK_LIST = "no_task_list";
    static final String NO_DISPLAY_MADE = "no_display_made";

    private static final String PREFIX = "#";

    private ShellText() { }

    /** In the shell process. */
    static String code(String code) {
        return PREFIX + code;
    }

    /** In the shell process. The detail is not translated, for example an exception. */
    static String code(String code, Object detail) {
        return PREFIX + code + ":" + detail;
    }

    /** In the app process: the user's language for a code, anything else unchanged. Null stays null. */
    static String localize(Context c, String text) {
        if (text == null || !text.startsWith(PREFIX)) return text;
        int colon = text.indexOf(':');
        String code = colon < 0 ? text.substring(PREFIX.length()) : text.substring(PREFIX.length(), colon);
        String detail = colon < 0 ? "" : text.substring(colon + 1);
        int id = resource(code);
        return id == 0 ? text : c.getString(id, detail);
    }

    private static int resource(String code) {
        switch (code) {
            case SESSION_OVER: return R.string.xd_sx_session_over;
            case INJECT_REFUSED: return R.string.xd_sx_inject_refused;
            case NO_PHYSICAL_INPUT: return R.string.xd_sx_no_physical_input;
            case NOT_CONNECTED: return R.string.xd_sx_not_connected;
            case TEXT_NOT_SENT: return R.string.xd_sx_text_not_sent;
            case AUDIO_RESULT: return R.string.xd_sx_audio_result;
            case AUDIO_UNSUPPORTED: return R.string.xd_sx_audio_unsupported;
            case AUDIO_NO_CLEAR: return R.string.xd_sx_audio_no_clear;
            case AUDIO_CLEAR_RESULT: return R.string.xd_sx_audio_clear_result;
            case PHONE_SCREEN: return R.string.xd_sx_phone_screen;
            case NO_TASK_LIST: return R.string.xd_sx_no_task_list;
            case NO_DISPLAY_MADE: return R.string.xd_sx_no_display_made;
            default: return 0;
        }
    }
}
