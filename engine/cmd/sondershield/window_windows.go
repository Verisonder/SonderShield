//go:build windows

package main

import (
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"syscall"
	"time"
	"unsafe"

	webview2 "github.com/jchv/go-webview2"
	"golang.org/x/sys/windows"
)

// Resource ids in rsrc_windows_amd64.syso (made by tools/make_windows_icons.py + rsrc):
// manifest 1, app icon group 2 (10 sizes), tray "on" group 13, tray "off" group 20.
const (
	iconApp     = 2
	iconTrayOn  = 13
	iconTrayOff = 20
)

const (
	wmClose         = 0x0010
	wmGetMinMaxInfo = 0x0024
	wmSetIcon       = 0x0080
	wmNull          = 0x0000
	wmLButtonUp     = 0x0202
	wmLButtonDblClk = 0x0203
	wmRButtonUp     = 0x0205
	wmTray          = 0x8000 + 10 // WM_APP+10: tray icon events
	swHide          = 0
	swShow          = 5
	swRestore       = 9
	imageIcon       = 1
	smCxIcon        = 11
	smCyIcon        = 12
	smCxSmIcon      = 49
	smCySmIcon      = 50
	nimAdd          = 0
	nimModify       = 1
	nimDelete       = 2
	nifMessage      = 0x1
	nifIcon         = 0x2
	nifTip          = 0x4
	nifInfo         = 0x10
	niifInfo        = 0x1
	mfString        = 0x0
	mfGrayed        = 0x1
	mfSeparator     = 0x800
	tpmReturnCmd    = 0x100
	tpmRightButton  = 0x2
	swpNoZOrder     = 0x4
	dwmUseDarkMode  = 20
	dwmCaptionColor = 35
	dwmBorderColor  = 34
	menuOpen        = 1
	menuToggle      = 2
	menuQuit        = 3
)

var (
	user32   = windows.NewLazySystemDLL("user32.dll")
	shell32  = windows.NewLazySystemDLL("shell32.dll")
	dwmapi   = windows.NewLazySystemDLL("dwmapi.dll")
	kernel32 = windows.NewLazySystemDLL("kernel32.dll")

	pSetWindowLongPtr  = user32.NewProc("SetWindowLongPtrW")
	pCallWindowProc    = user32.NewProc("CallWindowProcW")
	pShowWindow        = user32.NewProc("ShowWindow")
	pIsIconic          = user32.NewProc("IsIconic")
	pIsWindowVisible   = user32.NewProc("IsWindowVisible")
	pSetForeground     = user32.NewProc("SetForegroundWindow")
	pLoadImage         = user32.NewProc("LoadImageW")
	pSendMessage       = user32.NewProc("SendMessageW")
	pPostMessage       = user32.NewProc("PostMessageW")
	pGetSystemMetrics  = user32.NewProc("GetSystemMetrics")
	pGetDpiForWindow   = user32.NewProc("GetDpiForWindow")
	pSetWindowPos      = user32.NewProc("SetWindowPos")
	pCreatePopupMenu   = user32.NewProc("CreatePopupMenu")
	pAppendMenu        = user32.NewProc("AppendMenuW")
	pSetMenuDefault    = user32.NewProc("SetMenuDefaultItem")
	pTrackPopupMenu    = user32.NewProc("TrackPopupMenu")
	pDestroyMenu       = user32.NewProc("DestroyMenu")
	pGetCursorPos      = user32.NewProc("GetCursorPos")
	pRegisterWindowMsg = user32.NewProc("RegisterWindowMessageW")
	pShellNotifyIcon   = shell32.NewProc("Shell_NotifyIconW")
	pSetAppUserModelID = shell32.NewProc("SetCurrentProcessExplicitAppUserModelID")
	pDwmSetAttr        = dwmapi.NewProc("DwmSetWindowAttribute")
	pGetModuleHandle   = kernel32.NewProc("GetModuleHandleW")
)

type notifyIconData struct {
	CbSize           uint32
	HWnd             uintptr
	UID              uint32
	UFlags           uint32
	UCallbackMessage uint32
	HIcon            uintptr
	SzTip            [128]uint16
	DwState          uint32
	DwStateMask      uint32
	SzInfo           [256]uint16
	UVersion         uint32
	SzInfoTitle      [64]uint16
	DwInfoFlags      uint32
	GuidItem         windows.GUID
	HBalloonIcon     uintptr
}

type point struct{ X, Y int32 }

type minMaxInfo struct {
	Reserved, MaxSize, MaxPosition, MinTrackSize, MaxTrackSize point
}

// desktopUI is the app window (WebView2 inside our own window) plus the tray icon.
type desktopUI struct {
	a               *App
	srv             *server
	w               webview2.WebView
	hwnd            uintptr
	old             uintptr // the window procedure we subclassed
	hinst           uintptr
	iconOn, iconOff uintptr
	trayOn          bool // what the tray icon currently shows
	taskbarCreated  uintptr
	toldHidden      bool
	mu              sync.Mutex
}

var theUI *desktopUI

// runFrontend shows the window (unless hidden) and runs the Windows message loop until Quit.
func runFrontend(a *App, srv *server, hidden bool) {
	runtime.LockOSThread()
	appID, _ := windows.UTF16PtrFromString("Verisonder.SonderShield")
	pSetAppUserModelID.Call(uintptr(unsafe.Pointer(appID)))
	allowDarkMenus()

	dataPath := filepath.Join(os.Getenv("LOCALAPPDATA"), "SonderShield", "WebView2")
	w := webview2.NewWithOptions(webview2.WebViewOptions{
		DataPath:  dataPath,
		AutoFocus: true,
		WindowOptions: webview2.WindowOptions{
			Title: "SonderShield", Width: 1180, Height: 800, IconId: iconApp, Center: true,
		},
	})
	if w == nil {
		// No WebView2 runtime (rare): fall back to a browser window.
		quit := make(chan struct{})
		srv.onQuit = func() { a.stop(); close(quit) }
		if !hidden {
			openWindow(srv.url())
		}
		<-quit
		return
	}
	u := &desktopUI{a: a, srv: srv, w: w, hwnd: uintptr(w.Window())}
	theUI = u
	u.hinst, _, _ = pGetModuleHandle.Call(0)
	if hidden {
		pShowWindow.Call(u.hwnd, swHide)
	}
	u.style()
	cb := windows.NewCallback(u.wndproc)
	u.old, _, _ = pSetWindowLongPtr.Call(u.hwnd, ^uintptr(3), cb) // GWLP_WNDPROC = -4 // close -> tray
	tc, _ := windows.UTF16PtrFromString("TaskbarCreated")
	u.taskbarCreated, _, _ = pRegisterWindowMsg.Call(uintptr(unsafe.Pointer(tc)))
	u.loadTrayIcons()
	u.trayAdd()

	srv.onQuit = func() { w.Dispatch(u.quit) }
	srv.onShow = func() { w.Dispatch(u.show) }
	go u.watch()

	w.Navigate(srv.url())
	w.Run()
}

// style: proper size for the screen's scaling, centred, dark title bar, crisp icons.
func (u *desktopUI) style() {
	dpi, _, _ := pGetDpiForWindow.Call(u.hwnd)
	if dpi == 0 {
		dpi = 96
	}
	scale := func(v int) int { return v * int(dpi) / 96 }
	sw, _, _ := pGetSystemMetrics.Call(0)
	sh, _, _ := pGetSystemMetrics.Call(1)
	ww, wh := scale(1180), scale(800)
	if ww > int(sw)-40 {
		ww = int(sw) - 40
	}
	if wh > int(sh)-80 {
		wh = int(sh) - 80
	}
	pSetWindowPos.Call(u.hwnd, 0, uintptr((int(sw)-ww)/2), uintptr((int(sh)-wh)/2), uintptr(ww), uintptr(wh), swpNoZOrder)

	on := uint32(1)
	pDwmSetAttr.Call(u.hwnd, dwmUseDarkMode, uintptr(unsafe.Pointer(&on)), 4)
	black := uint32(0x00000000) // COLORREF 0x00BBGGRR (Windows 11 only; ignored on 10)
	pDwmSetAttr.Call(u.hwnd, dwmCaptionColor, uintptr(unsafe.Pointer(&black)), 4)
	border := uint32(0x00202020)
	pDwmSetAttr.Call(u.hwnd, dwmBorderColor, uintptr(unsafe.Pointer(&border)), 4)

	big := u.loadIcon(iconApp, smCxIcon, smCyIcon)
	small := u.loadIcon(iconApp, smCxSmIcon, smCySmIcon)
	pSendMessage.Call(u.hwnd, wmSetIcon, 1, big)
	pSendMessage.Call(u.hwnd, wmSetIcon, 0, small)
}

func (u *desktopUI) loadIcon(id uintptr, mx, my uintptr) uintptr {
	cx, _, _ := pGetSystemMetrics.Call(mx)
	cy, _, _ := pGetSystemMetrics.Call(my)
	h, _, _ := pLoadImage.Call(u.hinst, id, imageIcon, cx, cy, 0)
	return h
}

func (u *desktopUI) wndproc(hwnd, msg, wp, lp uintptr) uintptr {
	switch {
	case msg == wmClose:
		// Closing the window keeps SonderShield running in the tray.
		pShowWindow.Call(hwnd, swHide)
		if !u.toldHidden {
			u.toldHidden = true
			u.balloon("SonderShield is still running", "It keeps working in the background. Open it from here.")
		}
		return 0
	case msg == wmGetMinMaxInfo:
		dpi, _, _ := pGetDpiForWindow.Call(hwnd)
		if dpi == 0 {
			dpi = 96
		}
		mmi := (*minMaxInfo)(ptrFrom(lp))
		mmi.MinTrackSize = point{int32(900 * dpi / 96), int32(620 * dpi / 96)}
		return 0
	case msg == wmTray:
		switch lp & 0xffff {
		case wmLButtonUp, wmLButtonDblClk:
			u.show()
		case wmRButtonUp:
			u.menu()
		}
		return 0
	case msg == u.taskbarCreated && u.taskbarCreated != 0:
		u.trayAdd() // Explorer restarted: put the icon back
	}
	r, _, _ := pCallWindowProc.Call(u.old, hwnd, msg, wp, lp)
	return r
}

func (u *desktopUI) show() {
	if r, _, _ := pIsIconic.Call(u.hwnd); r != 0 {
		pShowWindow.Call(u.hwnd, swRestore)
	} else {
		pShowWindow.Call(u.hwnd, swShow)
	}
	pSetForeground.Call(u.hwnd)
}

func (u *desktopUI) quit() {
	u.trayDelete()
	pShowWindow.Call(u.hwnd, swHide)
	go func() {
		u.a.stop()
		u.w.Dispatch(u.w.Terminate)
	}()
}

// --- tray ---

func (u *desktopUI) loadTrayIcons() {
	u.iconOn = u.loadIcon(iconTrayOn, smCxSmIcon, smCySmIcon)
	u.iconOff = u.loadIcon(iconTrayOff, smCxSmIcon, smCySmIcon)
}

func (u *desktopUI) nid() notifyIconData {
	var n notifyIconData
	n.CbSize = uint32(unsafe.Sizeof(n))
	n.HWnd = u.hwnd
	n.UID = 1
	return n
}

func setText(dst []uint16, s string) {
	u16, _ := windows.UTF16FromString(s)
	if len(u16) > len(dst) {
		u16 = u16[:len(dst)-1]
		u16 = append(u16, 0)
	}
	copy(dst, u16)
}

func (u *desktopUI) trayAdd() {
	on := u.a.running()
	n := u.nid()
	n.UFlags = nifMessage | nifIcon | nifTip
	n.UCallbackMessage = wmTray
	n.HIcon = u.iconFor(on)
	setText(n.SzTip[:], tipFor(on))
	pShellNotifyIcon.Call(nimAdd, uintptr(unsafe.Pointer(&n)))
	u.trayOn = on
}

func (u *desktopUI) trayUpdate(on bool) {
	n := u.nid()
	n.UFlags = nifIcon | nifTip
	n.HIcon = u.iconFor(on)
	setText(n.SzTip[:], tipFor(on))
	pShellNotifyIcon.Call(nimModify, uintptr(unsafe.Pointer(&n)))
	u.trayOn = on
}

func (u *desktopUI) trayDelete() {
	n := u.nid()
	pShellNotifyIcon.Call(nimDelete, uintptr(unsafe.Pointer(&n)))
}

func (u *desktopUI) balloon(title, text string) {
	n := u.nid()
	n.UFlags = nifInfo
	n.DwInfoFlags = niifInfo
	setText(n.SzInfoTitle[:], title)
	setText(n.SzInfo[:], text)
	pShellNotifyIcon.Call(nimModify, uintptr(unsafe.Pointer(&n)))
}

func (u *desktopUI) iconFor(on bool) uintptr {
	if on {
		return u.iconOn
	}
	return u.iconOff
}

func tipFor(on bool) string {
	if on {
		return "SonderShield: protection on"
	}
	return "SonderShield: protection off"
}

// watch keeps the tray icon in step with protection (turned on or off anywhere).
func (u *desktopUI) watch() {
	for range time.Tick(time.Second) {
		on := u.a.running()
		if on != u.trayOn {
			u.w.Dispatch(func() { u.trayUpdate(on) })
		}
	}
}

func (u *desktopUI) menu() {
	on := u.a.running()
	m, _, _ := pCreatePopupMenu.Call()
	add := func(flags, id uintptr, text string) {
		t, _ := windows.UTF16PtrFromString(text)
		pAppendMenu.Call(m, flags, id, uintptr(unsafe.Pointer(t)))
	}
	add(mfString, menuOpen, "Open SonderShield")
	pSetMenuDefault.Call(m, menuOpen, 0)
	add(mfSeparator, 0, "")
	if on {
		add(mfString|mfGrayed, 0, "Protection is on")
		add(mfString, menuToggle, "Turn protection off")
	} else {
		add(mfString|mfGrayed, 0, "Protection is off")
		add(mfString, menuToggle, "Turn protection on")
	}
	add(mfSeparator, 0, "")
	add(mfString, menuQuit, "Quit SonderShield")

	var pt point
	pGetCursorPos.Call(uintptr(unsafe.Pointer(&pt)))
	pSetForeground.Call(u.hwnd)
	cmd, _, _ := pTrackPopupMenu.Call(m, tpmReturnCmd|tpmRightButton, uintptr(pt.X), uintptr(pt.Y), 0, u.hwnd, 0)
	pPostMessage.Call(u.hwnd, wmNull, 0, 0)
	pDestroyMenu.Call(m)

	switch cmd {
	case menuOpen:
		u.show()
	case menuToggle:
		go func() {
			if u.a.running() {
				u.a.stop()
			} else {
				u.a.start()
			}
		}()
	case menuQuit:
		u.quit()
	}
}

// allowDarkMenus asks Windows to draw this app's menus dark when the system is in dark
// mode (uxtheme ordinals 135/136; quietly does nothing where they don't exist).
func allowDarkMenus() {
	h, err := windows.LoadLibrary("uxtheme.dll")
	if err != nil {
		return
	}
	if p, err := windows.GetProcAddressByOrdinal(h, 135); err == nil {
		syscallN(p, 1) // SetPreferredAppMode(AllowDark)
	}
	if p, err := windows.GetProcAddressByOrdinal(h, 136); err == nil {
		syscallN(p) // FlushMenuThemes
	}
}

func syscallN(p uintptr, args ...uintptr) { syscall.SyscallN(p, args...) }

// ptrFrom turns a pointer Windows handed us as a uintptr back into a Go pointer.
func ptrFrom(u uintptr) unsafe.Pointer { return *(*unsafe.Pointer)(unsafe.Pointer(&u)) }
