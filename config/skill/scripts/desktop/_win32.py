"""Windows 桌面截图、窗口枚举与键鼠注入的共享实现。

只使用 Python 标准库 ctypes，不要求安装 pyautogui / Pillow / pynput。脚本只在 Windows
交互式桌面中工作；通过 SSH、服务或非交互会话启动时会明确返回错误。
"""

import ctypes
import os
import struct
import time
import zlib

IS_WINDOWS = os.name == "nt"

_dpi_ready = False

SRCCOPY = 0x00CC0020
DIB_RGB_COLORS = 0
BI_RGB = 0

SM_XVIRTUALSCREEN = 76
SM_YVIRTUALSCREEN = 77
SM_CXVIRTUALSCREEN = 78
SM_CYVIRTUALSCREEN = 79
SM_CXSCREEN = 0
SM_CYSCREEN = 1

SW_RESTORE = 9

MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_MIDDLEDOWN = 0x0020
MOUSEEVENTF_MIDDLEUP = 0x0040
MOUSEEVENTF_WHEEL = 0x0800
MOUSEEVENTF_HWHEEL = 0x01000

KEYEVENTF_EXTENDEDKEY = 0x0001
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004

INPUT_MOUSE = 0
INPUT_KEYBOARD = 1

PROCESS_QUERY_LIMITED_INFORMATION = 0x1000

PS_DOT = 2
TRANSPARENT = 1

VK_BACK = 0x08
VK_TAB = 0x09
VK_RETURN = 0x0D
VK_SHIFT = 0x10
VK_CONTROL = 0x11
VK_MENU = 0x12
VK_PAUSE = 0x13
VK_CAPITAL = 0x14
VK_ESCAPE = 0x1B
VK_SPACE = 0x20
VK_PRIOR = 0x21
VK_NEXT = 0x22
VK_END = 0x23
VK_HOME = 0x24
VK_LEFT = 0x25
VK_UP = 0x26
VK_RIGHT = 0x27
VK_DOWN = 0x28
VK_INSERT = 0x2D
VK_DELETE = 0x2E
VK_LWIN = 0x5B
VK_RWIN = 0x5C
VK_APPS = 0x5D

ANSI_CHARSET = 0
DEFAULT_CHARSET = 1
OUT_DEFAULT_PRECIS = 0
CLIP_DEFAULT_PRECIS = 0
CLEARTYPE_QUALITY = 5
DEFAULT_PITCH = 0
FF_DONTCARE = 0
NULL_BRUSH = 5


def _rgb(red, green, blue):
    return red | (green << 8) | (blue << 16)


if IS_WINDOWS:
    from ctypes import wintypes

    ULONG_PTR = ctypes.c_uint64 if ctypes.sizeof(ctypes.c_void_p) == 8 else ctypes.c_uint32

    user32 = ctypes.WinDLL("user32", use_last_error=True)
    gdi32 = ctypes.WinDLL("gdi32", use_last_error=True)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

    class POINT(ctypes.Structure):
        _fields_ = [("x", wintypes.LONG), ("y", wintypes.LONG)]

    class BITMAPINFOHEADER(ctypes.Structure):
        _fields_ = [
            ("biSize", wintypes.DWORD),
            ("biWidth", wintypes.LONG),
            ("biHeight", wintypes.LONG),
            ("biPlanes", wintypes.WORD),
            ("biBitCount", wintypes.WORD),
            ("biCompression", wintypes.DWORD),
            ("biSizeImage", wintypes.DWORD),
            ("biXPelsPerMeter", wintypes.LONG),
            ("biYPelsPerMeter", wintypes.LONG),
            ("biClrUsed", wintypes.DWORD),
            ("biClrImportant", wintypes.DWORD),
        ]

    class BITMAPINFO(ctypes.Structure):
        _fields_ = [("bmiHeader", BITMAPINFOHEADER), ("bmiColors", wintypes.DWORD * 1)]

    class MOUSEINPUT(ctypes.Structure):
        _fields_ = [
            ("dx", wintypes.LONG),
            ("dy", wintypes.LONG),
            ("mouseData", wintypes.DWORD),
            ("dwFlags", wintypes.DWORD),
            ("time", wintypes.DWORD),
            ("dwExtraInfo", ULONG_PTR),
        ]

    class KEYBDINPUT(ctypes.Structure):
        _fields_ = [
            ("wVk", wintypes.WORD),
            ("wScan", wintypes.WORD),
            ("dwFlags", wintypes.DWORD),
            ("time", wintypes.DWORD),
            ("dwExtraInfo", ULONG_PTR),
        ]

    class HARDWAREINPUT(ctypes.Structure):
        _fields_ = [
            ("uMsg", wintypes.DWORD),
            ("wParamL", wintypes.WORD),
            ("wParamH", wintypes.WORD),
        ]

    class _INPUTUNION(ctypes.Union):
        _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]

    class INPUT(ctypes.Structure):
        _fields_ = [("type", wintypes.DWORD), ("data", _INPUTUNION)]

    WNDENUMPROC = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

    user32.GetDC.argtypes = [wintypes.HWND]
    user32.GetDC.restype = ctypes.c_void_p
    user32.ReleaseDC.argtypes = [wintypes.HWND, ctypes.c_void_p]
    user32.ReleaseDC.restype = ctypes.c_int
    user32.GetSystemMetrics.argtypes = [ctypes.c_int]
    user32.GetSystemMetrics.restype = ctypes.c_int
    user32.GetCursorPos.argtypes = [ctypes.POINTER(POINT)]
    user32.GetCursorPos.restype = wintypes.BOOL
    user32.SetCursorPos.argtypes = [ctypes.c_int, ctypes.c_int]
    user32.SetCursorPos.restype = wintypes.BOOL
    user32.GetForegroundWindow.restype = wintypes.HWND
    user32.IsWindow.argtypes = [wintypes.HWND]
    user32.IsWindow.restype = wintypes.BOOL
    user32.IsWindowVisible.argtypes = [wintypes.HWND]
    user32.IsWindowVisible.restype = wintypes.BOOL
    user32.IsIconic.argtypes = [wintypes.HWND]
    user32.IsIconic.restype = wintypes.BOOL
    user32.GetWindowTextLengthW.argtypes = [wintypes.HWND]
    user32.GetWindowTextLengthW.restype = ctypes.c_int
    user32.GetWindowTextW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]
    user32.GetWindowTextW.restype = ctypes.c_int
    user32.GetClassNameW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]
    user32.GetClassNameW.restype = ctypes.c_int
    user32.GetWindowRect.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.RECT)]
    user32.GetWindowRect.restype = wintypes.BOOL
    user32.GetWindowThreadProcessId.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.DWORD)]
    user32.GetWindowThreadProcessId.restype = wintypes.DWORD
    user32.EnumWindows.argtypes = [WNDENUMPROC, wintypes.LPARAM]
    user32.EnumWindows.restype = wintypes.BOOL
    user32.ShowWindow.argtypes = [wintypes.HWND, ctypes.c_int]
    user32.ShowWindow.restype = wintypes.BOOL
    user32.SetForegroundWindow.argtypes = [wintypes.HWND]
    user32.SetForegroundWindow.restype = wintypes.BOOL
    user32.BringWindowToTop.argtypes = [wintypes.HWND]
    user32.BringWindowToTop.restype = wintypes.BOOL
    user32.SetFocus.argtypes = [wintypes.HWND]
    user32.SetFocus.restype = wintypes.HWND
    user32.AttachThreadInput.argtypes = [wintypes.DWORD, wintypes.DWORD, wintypes.BOOL]
    user32.AttachThreadInput.restype = wintypes.BOOL
    user32.VkKeyScanW.argtypes = [wintypes.WCHAR]
    user32.VkKeyScanW.restype = wintypes.SHORT
    user32.SendInput.argtypes = [wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int]
    user32.SendInput.restype = wintypes.UINT
    gdi32.CreateCompatibleDC.argtypes = [ctypes.c_void_p]
    gdi32.CreateCompatibleDC.restype = ctypes.c_void_p
    gdi32.CreateCompatibleBitmap.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int]
    gdi32.CreateCompatibleBitmap.restype = ctypes.c_void_p
    gdi32.SelectObject.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
    gdi32.SelectObject.restype = ctypes.c_void_p
    gdi32.DeleteObject.argtypes = [ctypes.c_void_p]
    gdi32.DeleteObject.restype = wintypes.BOOL
    gdi32.DeleteDC.argtypes = [ctypes.c_void_p]
    gdi32.DeleteDC.restype = wintypes.BOOL
    gdi32.BitBlt.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int,
                             ctypes.c_void_p, ctypes.c_int, ctypes.c_int, wintypes.DWORD]
    gdi32.BitBlt.restype = wintypes.BOOL
    gdi32.GetDIBits.argtypes = [ctypes.c_void_p, ctypes.c_void_p, wintypes.UINT, wintypes.UINT,
                                ctypes.c_void_p, ctypes.POINTER(BITMAPINFO), wintypes.UINT]
    gdi32.GetDIBits.restype = ctypes.c_int
    gdi32.CreatePen.argtypes = [ctypes.c_int, ctypes.c_int, wintypes.DWORD]
    gdi32.CreatePen.restype = ctypes.c_void_p
    gdi32.CreateFontW.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int,
                                  wintypes.DWORD, wintypes.DWORD, wintypes.DWORD, wintypes.DWORD,
                                  wintypes.DWORD, wintypes.DWORD, wintypes.DWORD, wintypes.DWORD,
                                  wintypes.LPCWSTR]
    gdi32.CreateFontW.restype = ctypes.c_void_p
    gdi32.GetStockObject.argtypes = [ctypes.c_int]
    gdi32.GetStockObject.restype = ctypes.c_void_p
    gdi32.SetBkMode.argtypes = [ctypes.c_void_p, ctypes.c_int]
    gdi32.SetBkMode.restype = ctypes.c_int
    gdi32.SetTextColor.argtypes = [ctypes.c_void_p, wintypes.DWORD]
    gdi32.SetTextColor.restype = wintypes.DWORD
    gdi32.TextOutW.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, wintypes.LPCWSTR, ctypes.c_int]
    gdi32.TextOutW.restype = wintypes.BOOL
    gdi32.MoveToEx.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_void_p]
    gdi32.MoveToEx.restype = wintypes.BOOL
    gdi32.LineTo.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int]
    gdi32.LineTo.restype = wintypes.BOOL
    gdi32.Ellipse.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int]
    gdi32.Ellipse.restype = wintypes.BOOL

    kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel32.OpenProcess.restype = wintypes.HANDLE
    kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel32.CloseHandle.restype = wintypes.BOOL
    kernel32.QueryFullProcessImageNameW.argtypes = [wintypes.HANDLE, wintypes.DWORD,
                                                   wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD)]
    kernel32.QueryFullProcessImageNameW.restype = wintypes.BOOL
    kernel32.GetCurrentThreadId.restype = wintypes.DWORD


def _require_windows():
    if not IS_WINDOWS:
        raise RuntimeError("桌面操作工具仅支持 Windows 交互式桌面")


def enable_dpi_awareness():
    """统一截图和鼠标坐标到物理像素，避免高 DPI 下坐标漂移。"""
    global _dpi_ready
    if _dpi_ready or not IS_WINDOWS:
        return
    _dpi_ready = True
    try:
        user32.SetProcessDpiAwarenessContext.argtypes = [ctypes.c_void_p]
        user32.SetProcessDpiAwarenessContext.restype = wintypes.BOOL
        user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))
        return
    except (AttributeError, OSError):
        pass
    try:
        shcore = ctypes.WinDLL("shcore", use_last_error=True)
        shcore.SetProcessDpiAwareness.argtypes = [ctypes.c_int]
        shcore.SetProcessDpiAwareness.restype = ctypes.c_int
        shcore.SetProcessDpiAwareness(2)
        return
    except (AttributeError, OSError):
        pass
    try:
        user32.SetProcessDPIAware()
    except (AttributeError, OSError):
        pass


def virtual_screen_rect():
    _require_windows()
    enable_dpi_awareness()
    left = user32.GetSystemMetrics(SM_XVIRTUALSCREEN)
    top = user32.GetSystemMetrics(SM_YVIRTUALSCREEN)
    width = user32.GetSystemMetrics(SM_CXVIRTUALSCREEN)
    height = user32.GetSystemMetrics(SM_CYVIRTUALSCREEN)
    if width <= 0 or height <= 0:
        left, top = 0, 0
        width = user32.GetSystemMetrics(SM_CXSCREEN)
        height = user32.GetSystemMetrics(SM_CYSCREEN)
    return {"left": left, "top": top, "width": width, "height": height}


def cursor_position():
    _require_windows()
    point = POINT()
    if not user32.GetCursorPos(ctypes.byref(point)):
        raise OSError("读取鼠标位置失败")
    return {"x": int(point.x), "y": int(point.y)}


def point_in_virtual_screen(x, y):
    rect = virtual_screen_rect()
    return rect["left"] <= int(x) < rect["left"] + rect["width"] and \
        rect["top"] <= int(y) < rect["top"] + rect["height"]


def _window_title(hwnd):
    length = user32.GetWindowTextLengthW(hwnd)
    if length <= 0:
        return ""
    buffer = ctypes.create_unicode_buffer(length + 1)
    user32.GetWindowTextW(hwnd, buffer, length + 1)
    return buffer.value


def _window_class(hwnd):
    buffer = ctypes.create_unicode_buffer(256)
    user32.GetClassNameW(hwnd, buffer, 256)
    return buffer.value


def _process_name(pid):
    if not pid:
        return ""
    handle = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
    if not handle:
        return ""
    try:
        buffer = ctypes.create_unicode_buffer(2048)
        size = wintypes.DWORD(len(buffer))
        if kernel32.QueryFullProcessImageNameW(handle, 0, buffer, ctypes.byref(size)):
            return os.path.basename(buffer.value)
    finally:
        kernel32.CloseHandle(handle)
    return ""


def window_info(hwnd):
    _require_windows()
    hwnd = int(hwnd)
    if not hwnd or not user32.IsWindow(hwnd):
        return None
    rect = wintypes.RECT()
    has_rect = bool(user32.GetWindowRect(hwnd, ctypes.byref(rect)))
    pid = wintypes.DWORD()
    user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
    return {
        "handle": hwnd,
        "title": _window_title(hwnd),
        "class_name": _window_class(hwnd),
        "process_id": int(pid.value),
        "process_name": _process_name(pid.value),
        "visible": bool(user32.IsWindowVisible(hwnd)),
        "minimized": bool(user32.IsIconic(hwnd)),
        "foreground": int(user32.GetForegroundWindow() or 0) == hwnd,
        "rect": {
            "left": int(rect.left),
            "top": int(rect.top),
            "width": int(rect.right - rect.left),
            "height": int(rect.bottom - rect.top),
        } if has_rect else None,
    }


def foreground_window():
    _require_windows()
    hwnd = user32.GetForegroundWindow()
    return window_info(int(hwnd)) if hwnd else None


def list_windows(query="", limit=50):
    _require_windows()
    query = (query or "").casefold()
    result = []
    limit = max(1, min(int(limit), 200))

    @WNDENUMPROC
    def callback(hwnd, _lparam):
        if not user32.IsWindowVisible(hwnd):
            return True
        title = _window_title(hwnd)
        if not title or (query and query not in title.casefold()):
            return True
        result.append(window_info(hwnd))
        return len(result) < limit

    user32.EnumWindows(callback, 0)
    return result


def resolve_window(selector=None):
    _require_windows()
    if selector is None or (isinstance(selector, str) and not selector.strip()):
        info = foreground_window()
        if not info:
            raise RuntimeError("当前没有可交互的前台窗口（可能锁屏或处于安全桌面）")
        return info
    if isinstance(selector, int):
        info = window_info(selector)
        if info:
            return info
        raise ValueError("窗口句柄不存在：%s" % selector)
    text = str(selector).strip()
    try:
        handle = int(text, 0)
    except ValueError:
        handle = 0
    if handle:
        info = window_info(handle)
        if info:
            return info
    matches = list_windows(text, limit=20)
    if not matches:
        raise ValueError("找不到标题包含「%s」的窗口" % text)
    exact = [item for item in matches if item["title"].casefold() == text.casefold()]
    if len(exact) == 1:
        return exact[0]
    if len(matches) == 1:
        return matches[0]
    titles = "；".join(item["title"] for item in matches[:8])
    raise ValueError("窗口不唯一，请提供更完整的标题或窗口句柄。候选：%s" % titles)


def focus_window(selector=None):
    info = resolve_window(selector)
    hwnd = info["handle"]
    if info["minimized"]:
        user32.ShowWindow(hwnd, SW_RESTORE)
        time.sleep(0.08)
    foreground = user32.GetForegroundWindow()
    current_thread = kernel32.GetCurrentThreadId()
    attached = []
    target_pid = wintypes.DWORD()
    target_thread = user32.GetWindowThreadProcessId(hwnd, ctypes.byref(target_pid))
    foreground_thread = 0
    if foreground:
        foreground_pid = wintypes.DWORD()
        foreground_thread = user32.GetWindowThreadProcessId(foreground, ctypes.byref(foreground_pid))
    for thread in (foreground_thread, target_thread):
        if thread and thread != current_thread and thread not in attached:
            if user32.AttachThreadInput(current_thread, thread, True):
                attached.append(thread)
    try:
        user32.BringWindowToTop(hwnd)
        user32.SetForegroundWindow(hwnd)
        user32.SetFocus(hwnd)
    finally:
        for thread in attached:
            user32.AttachThreadInput(current_thread, thread, False)
    time.sleep(0.15)
    actual = foreground_window()
    if actual and actual.get("handle") == hwnd:
        return actual
    return window_info(hwnd)


def validate_region(left, top, width, height):
    screen = virtual_screen_rect()
    left = max(int(left), screen["left"])
    top = max(int(top), screen["top"])
    right = min(int(left) + int(width), screen["left"] + screen["width"])
    bottom = min(int(top) + int(height), screen["top"] + screen["height"])
    if right <= left or bottom <= top:
        raise ValueError("截图区域不在当前虚拟屏幕内")
    return {"left": left, "top": top, "width": right - left, "height": bottom - top}


def _select(dc, obj):
    return gdi32.SelectObject(dc, obj)


def _delete(obj):
    if obj:
        gdi32.DeleteObject(obj)


def _annotate(dc, width, height, left, top, show_cursor):
    old_pen = None
    old_font = None
    old_brush = None
    grid_pen = None
    cursor_pen = None
    font = None
    try:
        grid_pen = gdi32.CreatePen(PS_DOT, 1, _rgb(255, 0, 255))
        cursor_pen = gdi32.CreatePen(0, 2, _rgb(255, 40, 40))
        font = gdi32.CreateFontW(14, 0, 0, 0, 400, 0, 0, 0, DEFAULT_CHARSET,
                                 OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
                                 DEFAULT_PITCH | FF_DONTCARE, "Consolas")
        old_pen = _select(dc, grid_pen)
        old_font = _select(dc, font)
        old_brush = _select(dc, gdi32.GetStockObject(NULL_BRUSH))
        gdi32.SetBkMode(dc, TRANSPARENT)
        gdi32.SetTextColor(dc, _rgb(255, 255, 0))
        step = 100
        first_x = ((left + step - 1) // step) * step
        for global_x in range(first_x, left + width, step):
            x = global_x - left
            gdi32.MoveToEx(dc, x, 0, None)
            gdi32.LineTo(dc, x, height)
            label = str(global_x)
            label_x = x + 2 if x + 2 + len(label) * 7 < width else max(0, x - len(label) * 7 - 2)
            gdi32.TextOutW(dc, label_x, 2, label, len(label))
        first_y = ((top + step - 1) // step) * step
        for global_y in range(first_y, top + height, step):
            y = global_y - top
            gdi32.MoveToEx(dc, 0, y, None)
            gdi32.LineTo(dc, width, y)
            label = str(global_y)
            gdi32.TextOutW(dc, 2, y + 2, label, len(label))
        if show_cursor:
            cursor = cursor_position()
            x = cursor["x"] - left
            y = cursor["y"] - top
            if 0 <= x < width and 0 <= y < height:
                _select(dc, cursor_pen)
                gdi32.MoveToEx(dc, max(0, x - 18), y, None)
                gdi32.LineTo(dc, min(width - 1, x + 18), y)
                gdi32.MoveToEx(dc, x, max(0, y - 18), None)
                gdi32.LineTo(dc, x, min(height - 1, y + 18))
                gdi32.Ellipse(dc, x - 9, y - 9, x + 9, y + 9)
    finally:
        if old_brush:
            _select(dc, old_brush)
        if old_font:
            _select(dc, old_font)
        if old_pen:
            _select(dc, old_pen)
        _delete(grid_pen)
        _delete(cursor_pen)
        _delete(font)


def _encode_png(width, height, rgb):
    def chunk(tag, data):
        payload = tag + data
        return struct.pack(">I", len(data)) + payload + struct.pack(">I", zlib.crc32(payload) & 0xFFFFFFFF)

    raw = bytearray()
    stride = width * 3
    for row in range(height):
        raw.append(0)
        raw.extend(rgb[row * stride:(row + 1) * stride])
    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + \
        chunk(b"IDAT", zlib.compress(bytes(raw), 3)) + chunk(b"IEND", b"")


def capture_png(region, annotate=True, show_cursor=True):
    _require_windows()
    enable_dpi_awareness()
    left = int(region["left"])
    top = int(region["top"])
    width = int(region["width"])
    height = int(region["height"])
    screen_dc = user32.GetDC(None)
    if not screen_dc:
        raise OSError("获取屏幕设备上下文失败")
    memory_dc = gdi32.CreateCompatibleDC(screen_dc)
    bitmap = gdi32.CreateCompatibleBitmap(screen_dc, width, height)
    old_bitmap = None
    try:
        if not memory_dc or not bitmap:
            raise OSError("创建截图缓冲区失败")
        old_bitmap = _select(memory_dc, bitmap)
        if not gdi32.BitBlt(memory_dc, 0, 0, width, height, screen_dc, left, top, SRCCOPY):
            raise OSError("截取屏幕失败（可能处于锁屏、UAC 安全桌面或远程会话切换中）")
        if annotate:
            _annotate(memory_dc, width, height, left, top, show_cursor)
        header = BITMAPINFOHEADER()
        header.biSize = ctypes.sizeof(BITMAPINFOHEADER)
        header.biWidth = width
        header.biHeight = -height
        header.biPlanes = 1
        header.biBitCount = 32
        header.biCompression = BI_RGB
        info = BITMAPINFO()
        info.bmiHeader = header
        buffer = ctypes.create_string_buffer(width * height * 4)
        lines = gdi32.GetDIBits(memory_dc, bitmap, 0, height, buffer, ctypes.byref(info), DIB_RGB_COLORS)
        if lines != height:
            raise OSError("读取截图像素失败")
        bgra = buffer.raw
        rgb = bytearray(width * height * 3)
        rgb[0::3] = bgra[2::4]
        rgb[1::3] = bgra[1::4]
        rgb[2::3] = bgra[0::4]
        return _encode_png(width, height, rgb)
    finally:
        if memory_dc and old_bitmap:
            _select(memory_dc, old_bitmap)
        _delete(bitmap)
        if memory_dc:
            gdi32.DeleteDC(memory_dc)
        if screen_dc:
            user32.ReleaseDC(None, screen_dc)


def save_png(png, directory, keep=None):
    os.makedirs(directory, exist_ok=True)
    stamp = time.strftime("%Y%m%d_%H%M%S")
    millis = int(time.time() * 1000) % 1000
    path = os.path.join(directory, "shot_%s_%03d_%s.png" % (stamp, millis, os.getpid()))
    with open(path, "wb") as handle:
        handle.write(png)
    cleanup_screenshots(directory, keep or 50)
    return path


def cleanup_screenshots(directory, keep):
    try:
        names = [os.path.join(directory, name) for name in os.listdir(directory)
                 if name.startswith("shot_") and name.endswith(".png")]
        if len(names) <= keep:
            return
        names.sort(key=lambda item: os.path.getmtime(item))
        for path in names[:len(names) - keep]:
            try:
                os.remove(path)
            except OSError:
                pass
    except OSError:
        pass


def _mouse_input(dx, dy, data, flags):
    item = INPUT()
    item.type = INPUT_MOUSE
    item.data.mi.dx = int(dx)
    item.data.mi.dy = int(dy)
    item.data.mi.mouseData = ctypes.c_uint32(data).value
    item.data.mi.dwFlags = int(flags)
    item.data.mi.time = 0
    item.data.mi.dwExtraInfo = 0
    return item


def _key_input(vk, scan, flags):
    item = INPUT()
    item.type = INPUT_KEYBOARD
    item.data.ki.wVk = int(vk)
    item.data.ki.wScan = int(scan)
    item.data.ki.dwFlags = int(flags)
    item.data.ki.time = 0
    item.data.ki.dwExtraInfo = 0
    return item


def _send(inputs):
    _require_windows()
    if not inputs:
        return
    array = (INPUT * len(inputs))(*inputs)
    sent = user32.SendInput(len(inputs), array, ctypes.sizeof(INPUT))
    if sent != len(inputs):
        raise OSError("模拟输入失败，系统可能阻止了焦点窗口接收键鼠事件")


def move_cursor(x, y, duration_ms=0):
    _require_windows()
    if not point_in_virtual_screen(x, y):
        raise ValueError("目标坐标不在当前虚拟屏幕内：x=%s y=%s" % (x, y))
    start = cursor_position()
    duration_ms = max(0, min(int(duration_ms or 0), 5000))
    if duration_ms <= 0:
        if not user32.SetCursorPos(int(x), int(y)):
            raise OSError("移动鼠标失败")
        return cursor_position()
    steps = max(1, min(60, duration_ms // 16))
    for index in range(1, steps + 1):
        ratio = index / float(steps)
        user32.SetCursorPos(int(start["x"] + (x - start["x"]) * ratio),
                            int(start["y"] + (y - start["y"]) * ratio))
        time.sleep(duration_ms / 1000.0 / steps)
    return cursor_position()


_BUTTONS = {
    "left": (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP),
    "right": (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP),
    "middle": (MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP),
}


def click(button="left", clicks=1, interval_ms=60, x=None, y=None):
    _require_windows()
    down, up = _BUTTONS.get(str(button).lower(), _BUTTONS["left"])
    if x is not None and y is not None:
        move_cursor(x, y)
    clicks = max(1, min(int(clicks), 5))
    interval_ms = max(0, min(int(interval_ms), 1000))
    for index in range(clicks):
        _send([_mouse_input(0, 0, 0, down), _mouse_input(0, 0, 0, up)])
        if index + 1 < clicks:
            time.sleep(interval_ms / 1000.0)
    return cursor_position()


def drag(x, y, to_x, to_y, button="left", duration_ms=450):
    _require_windows()
    down, up = _BUTTONS.get(str(button).lower(), _BUTTONS["left"])
    move_cursor(x, y)
    _send([_mouse_input(0, 0, 0, down)])
    try:
        duration_ms = max(0, min(int(duration_ms), 10000))
        steps = max(2, min(80, duration_ms // 16 + 1))
        for index in range(1, steps + 1):
            ratio = index / float(steps)
            point_x = int(x + (to_x - x) * ratio)
            point_y = int(y + (to_y - y) * ratio)
            if not user32.SetCursorPos(point_x, point_y):
                raise OSError("拖拽过程中移动鼠标失败")
            time.sleep(duration_ms / 1000.0 / steps)
    finally:
        _send([_mouse_input(0, 0, 0, up)])
    return cursor_position()


def scroll(amount, horizontal=False, x=None, y=None):
    _require_windows()
    if not amount:
        raise ValueError("滚动量不能为 0")
    if x is not None and y is not None:
        move_cursor(x, y)
    flags = MOUSEEVENTF_HWHEEL if horizontal else MOUSEEVENTF_WHEEL
    _send([_mouse_input(0, 0, int(amount) * 120, flags)])
    return cursor_position()


KEY_ALIASES = {
    "backspace": VK_BACK, "bksp": VK_BACK,
    "tab": VK_TAB,
    "enter": VK_RETURN, "return": VK_RETURN,
    "shift": VK_SHIFT,
    "ctrl": VK_CONTROL, "control": VK_CONTROL,
    "alt": VK_MENU, "menu": VK_MENU,
    "pause": VK_PAUSE, "break": VK_PAUSE,
    "capslock": VK_CAPITAL, "caps": VK_CAPITAL,
    "esc": VK_ESCAPE, "escape": VK_ESCAPE,
    "space": VK_SPACE, "spacebar": VK_SPACE,
    "pageup": VK_PRIOR, "pgup": VK_PRIOR,
    "pagedown": VK_NEXT, "pgdn": VK_NEXT,
    "end": VK_END, "home": VK_HOME,
    "left": VK_LEFT, "up": VK_UP, "right": VK_RIGHT, "down": VK_DOWN,
    "insert": VK_INSERT, "ins": VK_INSERT,
    "delete": VK_DELETE, "del": VK_DELETE,
    "win": VK_LWIN, "lwin": VK_LWIN, "rwin": VK_RWIN,
    "apps": VK_APPS, "contextmenu": VK_APPS,
}
for _number in range(1, 25):
    KEY_ALIASES["f%d" % _number] = 0x6F + _number

_EXTENDED_KEYS = {
    VK_PRIOR, VK_NEXT, VK_END, VK_HOME, VK_LEFT, VK_UP, VK_RIGHT, VK_DOWN,
    VK_INSERT, VK_DELETE, VK_RWIN, VK_APPS,
}


def _key_token(token):
    text = str(token).strip()
    lowered = text.casefold()
    if lowered in KEY_ALIASES:
        return KEY_ALIASES[lowered], []
    if len(text) == 1:
        result = user32.VkKeyScanW(text)
        if result == -1:
            raise ValueError("无法识别按键：%s" % text)
        value = result & 0xFFFF
        vk = value & 0xFF
        modifiers = []
        if value & 0x100:
            modifiers.append(VK_SHIFT)
        if value & 0x200:
            modifiers.append(VK_CONTROL)
        if value & 0x400:
            modifiers.append(VK_MENU)
        return vk, modifiers
    raise ValueError("无法识别按键：%s" % text)


def _key_event(vk, down):
    flags = KEYEVENTF_EXTENDEDKEY if vk in _EXTENDED_KEYS else 0
    if not down:
        flags |= KEYEVENTF_KEYUP
    _send([_key_input(vk, 0, flags)])


def press_key(key):
    _require_windows()
    vk, modifiers = _key_token(key)
    for modifier in modifiers:
        _key_event(modifier, True)
    _key_event(vk, True)
    _key_event(vk, False)
    for modifier in reversed(modifiers):
        _key_event(modifier, False)


def hotkey(keys):
    _require_windows()
    if isinstance(keys, str):
        tokens = [item for item in keys.split("+") if item.strip()]
    else:
        tokens = [str(item) for item in (keys or [])]
    if not tokens:
        raise ValueError("快捷键不能为空")
    ordered = []
    for token in tokens:
        vk, modifiers = _key_token(token)
        for modifier in modifiers:
            if modifier not in ordered:
                ordered.append(modifier)
        if vk not in ordered:
            ordered.append(vk)
    for vk in ordered:
        _key_event(vk, True)
    for vk in reversed(ordered):
        _key_event(vk, False)


def type_text(text, interval_ms=8):
    _require_windows()
    if text is None:
        raise ValueError("text 不能为空")
    value = str(text).replace("\r\n", "\n").replace("\r", "\n")
    if len(value) > 50000:
        raise ValueError("单次输入文本过长，请拆成多次 type_text")
    interval_ms = max(0, min(int(interval_ms or 0), 100))
    buffer = []
    for char in value:
        if char == "\n" or char == "\r":
            buffer.extend([_key_input(VK_RETURN, 0, 0), _key_input(VK_RETURN, 0, KEYEVENTF_KEYUP)])
        elif char == "\t":
            buffer.extend([_key_input(VK_TAB, 0, 0), _key_input(VK_TAB, 0, KEYEVENTF_KEYUP)])
        else:
            encoded = char.encode("utf-16-le")
            units = struct.unpack("<%dH" % (len(encoded) // 2), encoded)
            for unit in units:
                buffer.append(_key_input(0, unit, KEYEVENTF_UNICODE))
                buffer.append(_key_input(0, unit, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP))
        if len(buffer) >= 400:
            _send(buffer)
            buffer = []
        if interval_ms:
            time.sleep(interval_ms / 1000.0)
    if buffer:
        _send(buffer)
    return len(value)
