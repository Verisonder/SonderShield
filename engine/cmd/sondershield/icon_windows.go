//go:build windows

package main

import (
	"bytes"
	"image"
	"image/color"
	"image/png"
	"unsafe"

	"golang.org/x/sys/windows"
)

// App icons for the Firewall and Connections lists: the program's own icon, read from its
// .exe the way Explorer does, and turned into a PNG for the window to show.

var (
	user32dll              = windows.NewLazySystemDLL("user32.dll")
	gdi32dll               = windows.NewLazySystemDLL("gdi32.dll")
	shell32dll             = windows.NewLazySystemDLL("shell32.dll")
	procPrivateExtractIcon = user32dll.NewProc("PrivateExtractIconsW")
	procGetIconInfo        = user32dll.NewProc("GetIconInfo")
	procDestroyIcon        = user32dll.NewProc("DestroyIcon")
	procCreateCompatibleDC = gdi32dll.NewProc("CreateCompatibleDC")
	procDeleteDC           = gdi32dll.NewProc("DeleteDC")
	procDeleteObject       = gdi32dll.NewProc("DeleteObject")
	procGetObjectW         = gdi32dll.NewProc("GetObjectW")
	procGetDIBits          = gdi32dll.NewProc("GetDIBits")
	procSHGetFileInfoW     = shell32dll.NewProc("SHGetFileInfoW")
)

type iconInfo struct {
	fIcon    int32
	xHotspot uint32
	yHotspot uint32
	hbmMask  uintptr
	hbmColor uintptr
}

type bitmapObj struct {
	typ, width, height, widthBytes int32
	planes, bitsPixel              uint16
	bits                           uintptr
}

type bitmapInfo struct {
	size          uint32
	width, height int32
	planes        uint16
	bitCount      uint16
	compression   uint32
	sizeImage     uint32
	xppm, yppm    int32
	clrUsed       uint32
	clrImportant  uint32
	colors        [256]uint32
}

type shFileInfo struct {
	hIcon       uintptr
	iIcon       int32
	attributes  uint32
	displayName [260]uint16
	typeName    [80]uint16
}

func (p *windowsPlatform) appIcon(path string) []byte {
	p16, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return nil
	}
	var hicon uintptr
	var id uint32
	n, _, _ := procPrivateExtractIcon.Call(uintptr(unsafe.Pointer(p16)), 0, 64, 64,
		uintptr(unsafe.Pointer(&hicon)), uintptr(unsafe.Pointer(&id)), 1, 0)
	if n == 0 || n == 0xFFFFFFFF || hicon == 0 {
		// No icon in the file itself: ask the shell (gives the generic program icon).
		var fi shFileInfo
		r, _, _ := procSHGetFileInfoW.Call(uintptr(unsafe.Pointer(p16)), 0,
			uintptr(unsafe.Pointer(&fi)), unsafe.Sizeof(fi), 0x100 /* SHGFI_ICON */)
		if r == 0 || fi.hIcon == 0 {
			return nil
		}
		hicon = fi.hIcon
	}
	defer procDestroyIcon.Call(hicon)
	return iconPNG(hicon)
}

// iconPNG reads an icon's colour (and, for old icons without alpha, its mask) into a PNG.
func iconPNG(hicon uintptr) []byte {
	var ii iconInfo
	if r, _, _ := procGetIconInfo.Call(hicon, uintptr(unsafe.Pointer(&ii))); r == 0 {
		return nil
	}
	if ii.hbmMask != 0 {
		defer procDeleteObject.Call(ii.hbmMask)
	}
	if ii.hbmColor == 0 {
		return nil
	}
	defer procDeleteObject.Call(ii.hbmColor)

	var bm bitmapObj
	if r, _, _ := procGetObjectW.Call(ii.hbmColor, unsafe.Sizeof(bm), uintptr(unsafe.Pointer(&bm))); r == 0 {
		return nil
	}
	w, h := int(bm.width), int(bm.height)
	if w <= 0 || h <= 0 || w > 512 || h > 512 {
		return nil
	}
	dc, _, _ := procCreateCompatibleDC.Call(0)
	if dc == 0 {
		return nil
	}
	defer procDeleteDC.Call(dc)

	read := func(hbm uintptr) []byte {
		bi := bitmapInfo{size: 40, width: int32(w), height: -int32(h), planes: 1, bitCount: 32}
		buf := make([]byte, w*h*4)
		r, _, _ := procGetDIBits.Call(dc, hbm, 0, uintptr(h), uintptr(unsafe.Pointer(&buf[0])), uintptr(unsafe.Pointer(&bi)), 0)
		if r == 0 {
			return nil
		}
		return buf
	}
	px := read(ii.hbmColor)
	if px == nil {
		return nil
	}
	hasAlpha := false
	for i := 3; i < len(px); i += 4 {
		if px[i] != 0 {
			hasAlpha = true
			break
		}
	}
	var mask []byte
	if !hasAlpha && ii.hbmMask != 0 {
		mask = read(ii.hbmMask)
	}
	img := image.NewNRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			i := (y*w + x) * 4
			a := px[i+3]
			if !hasAlpha {
				a = 255
				if mask != nil && mask[i] != 0 { // white in the mask = transparent
					a = 0
				}
			}
			img.SetNRGBA(x, y, color.NRGBA{R: px[i+2], G: px[i+1], B: px[i], A: a})
		}
	}
	var out bytes.Buffer
	if png.Encode(&out, img) != nil {
		return nil
	}
	return out.Bytes()
}
