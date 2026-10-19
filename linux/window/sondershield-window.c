// sondershield-window: SonderShield's window on Linux. One WebKitGTK view showing the
// interface the SonderShield service serves on 127.0.0.1. Started by the launcher (the
// AppImage) as `sondershield-window <url> [icon.png]`:
//
//   - links to other sites open in the normal browser;
//   - SIGUSR1 brings the window to the front (opening SonderShield again does this);
//   - closing the window ends this program; protection keeps running in the service.
//
// Links against the system's GTK 3 and WebKitGTK 4.1 (not bundled in the AppImage); the
// launcher falls back to a browser window when they are missing.
//
// Build: cc -O2 sondershield-window.c -o sondershield-window \
//          $(pkg-config --cflags --libs gtk+-3.0 webkit2gtk-4.1)

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <gtk/gtk.h>
#include <glib-unix.h>
#include <webkit2/webkit2.h>

static GtkWidget *win;
static char *origin;

// Same page or not: only the SonderShield interface stays inside the window.
static gboolean ours(const char *uri) {
	return uri && origin && strncmp(uri, origin, strlen(origin)) == 0;
}

static void open_outside(const char *uri) {
	if (uri && (g_str_has_prefix(uri, "http://") || g_str_has_prefix(uri, "https://")))
		g_app_info_launch_default_for_uri(uri, NULL, NULL);
}

static gboolean on_policy(WebKitWebView *v, WebKitPolicyDecision *d, WebKitPolicyDecisionType t, gpointer u) {
	if (t == WEBKIT_POLICY_DECISION_TYPE_NAVIGATION_ACTION || t == WEBKIT_POLICY_DECISION_TYPE_NEW_WINDOW_ACTION) {
		WebKitNavigationAction *a = webkit_navigation_policy_decision_get_navigation_action(WEBKIT_NAVIGATION_POLICY_DECISION(d));
		const char *uri = webkit_uri_request_get_uri(webkit_navigation_action_get_request(a));
		if (t == WEBKIT_POLICY_DECISION_TYPE_NEW_WINDOW_ACTION || !ours(uri)) {
			if (!(uri && (g_str_has_prefix(uri, "file:") || g_str_has_prefix(uri, "about:")))) {
				open_outside(uri);
				webkit_policy_decision_ignore(d);
				return TRUE;
			}
		}
	}
	return FALSE;
}

// No browser right-click menu: it's an app window (text fields keep their own menu).
static gboolean on_menu(WebKitWebView *v, WebKitContextMenu *m, GdkEvent *e, WebKitHitTestResult *h, gpointer u) {
	return !webkit_hit_test_result_context_is_editable(h);
}

static gboolean on_raise(gpointer u) {
	gtk_window_present(GTK_WINDOW(win));
	return G_SOURCE_CONTINUE;
}

static gboolean on_term(gpointer u) {
	gtk_main_quit();
	return G_SOURCE_REMOVE;
}

int main(int argc, char **argv) {
	if (argc < 2) { fprintf(stderr, "usage: sondershield-window <url> [icon]\n"); return 2; }
	const char *url = argv[1];
	const char *icon = argc > 2 ? argv[2] : NULL;
	// Blank-window bug with some GPU drivers (NVIDIA on Wayland) — the safe renderer.
	setenv("WEBKIT_DISABLE_DMABUF_RENDERER", "1", 0);
	g_set_prgname("sondershield"); // Wayland app id / X11 class, matches the .desktop file
	if (!gtk_init_check(NULL, NULL)) return 3;
	g_object_set(gtk_settings_get_default(), "gtk-application-prefer-dark-theme", TRUE, NULL);

	// The origin ("http://127.0.0.1:port/") of the interface.
	origin = g_strdup(url);
	char *p = strstr(origin, "://");
	if (p) { char *s = strchr(p + 3, '/'); if (s) s[1] = 0; }

	win = gtk_window_new(GTK_WINDOW_TOPLEVEL);
	gtk_window_set_title(GTK_WINDOW(win), "SonderShield");
	gtk_window_set_default_size(GTK_WINDOW(win), 1180, 800);
	gtk_window_set_position(GTK_WINDOW(win), GTK_WIN_POS_CENTER);
	if (icon && *icon) gtk_window_set_icon_from_file(GTK_WINDOW(win), icon, NULL);
	g_signal_connect(win, "destroy", G_CALLBACK(gtk_main_quit), NULL);

	WebKitWebView *view = WEBKIT_WEB_VIEW(webkit_web_view_new());
	GdkRGBA black = {0, 0, 0, 1}; // no white flash before the page paints
	webkit_web_view_set_background_color(view, &black);
	WebKitSettings *st = webkit_web_view_get_settings(view);
	webkit_settings_set_enable_developer_extras(st, FALSE);
	webkit_settings_set_javascript_can_open_windows_automatically(st, TRUE);
	g_signal_connect(view, "decide-policy", G_CALLBACK(on_policy), NULL);
	g_signal_connect(view, "context-menu", G_CALLBACK(on_menu), NULL);
	gtk_container_add(GTK_CONTAINER(win), GTK_WIDGET(view));
	webkit_web_view_load_uri(view, url);
	gtk_widget_show_all(win);

	g_unix_signal_add(SIGUSR1, on_raise, NULL);
	g_unix_signal_add(SIGTERM, on_term, NULL);
	g_unix_signal_add(SIGINT, on_term, NULL);
	gtk_main();
	return 0;
}
