"""dockfit.py's logic against canned xfconf-query output, for
test/linux-dockfit-test.mjs: no X server and no xfconfd.

Loads the very file linux-rt.sh writes, which the test cuts out of the
script's heredoc. Its main() returns at once off Linux, where there is no
libX11 to load, and on a Linux with one it would wait on a display: so the
test runs it with DISPLAY unset, and XOpenDisplay fails first.
"""
import importlib.util
import os
import sys
import tempfile

spec = importlib.util.spec_from_file_location("dockfit", sys.argv[1])
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

passed = failed = 0


def check(name, ok, detail=""):
    global passed, failed
    if ok:
        passed += 1
    else:
        failed += 1
    print(("  ok   " if ok else "  FAIL ") + name + ("" if ok else " -- " + str(detail)))


def listing(dock_len="96.390000", dock_pos="p=8;x=0;y=0", dock_mode="1", top=True):
    rows = [("/configver", "2"), ("/panels", "<<UNSUPPORTED>>"), ("/panels/dark-mode", "false")]
    if top:
        rows += [("/panels/panel-1/icon-size", "16"), ("/panels/panel-1/length", "100.000000"),
                 ("/panels/panel-1/plugin-ids", "<<UNSUPPORTED>>"),
                 ("/panels/panel-1/position", "p=6;x=0;y=0"),
                 ("/panels/panel-1/position-locked", "true"), ("/panels/panel-1/size", "26")]
    rows += [("/panels/panel-2/autohide-behavior", "0"), ("/panels/panel-2/length", dock_len),
             ("/panels/panel-2/length-adjust", "false"), ("/panels/panel-2/mode", dock_mode),
             ("/panels/panel-2/plugin-ids", "<<UNSUPPORTED>>"),
             ("/panels/panel-2/position", dock_pos), ("/panels/panel-2/size", "48"),
             ("/plugins/plugin-1", "applicationsmenu")]
    # xfconf-query -l -v: "%-*s%s\n", the width the longest name plus 2.
    width = max(len(n) for n, _ in rows) + 2
    return "".join(n.ljust(width) + v + "\n" for n, v in rows)


class Result:
    def __init__(self, code, out=""):
        self.returncode = code
        self.stdout = out


def run(height, out, mark="4", state=None, set_code=0, list_code=0, start=0):
    """One fit() against this world; returns (calls, state file content or None).

    start is the session's start height, as linux-rt.sh hands it over.
    """
    m.START_H = start
    d = tempfile.mkdtemp()
    m.MARK = os.path.join(d, "dock-positioned")
    m.STATE = os.path.join(d, "dock-length")
    if mark is not None:
        with open(m.MARK, "w") as f:
            f.write(mark + "\n")
    if state is not None:
        with open(m.STATE, "w") as f:
            f.write(state + "\n")
    calls = []

    def fake(*args):
        calls.append(args)
        if args[:2] == ("-l", "-v"):
            return Result(list_code, out)
        return Result(set_code)

    m.xfconf = fake
    m.fit(height)
    kept = None
    if os.path.exists(m.STATE):
        with open(m.STATE) as f:
            kept = f.read().strip()
    return calls, kept


def sets(calls):
    return [c for c in calls if c[:1] == ("-p",)]


print("the panels, read")
m.xfconf = lambda *a: Result(0, listing())
found = m.panels()
check("both panels and their scalar properties", found.get("panel-2", {}).get("position") == "p=8;x=0;y=0"
      and found.get("panel-1", {}).get("size") == "26", found)
check("arrays and other channels left out", "plugin-1" not in found and all(
    "plugin-ids" not in p or p["plugin-ids"] == "<<UNSUPPORTED>>" for p in found.values()), found)
check("the top bar is panel-1's size", m.top_bar(found, "panel-2") == 26, m.top_bar(found, "panel-2"))
m.xfconf = lambda *a: Result(1, "")
check("a failed listing is no panels", m.panels() == {})
m.xfconf = lambda *a: None
check("xfconf-query missing is no panels", m.panels() == {})

print("a fresh guest")
calls, kept = run(720, listing("96.390000"))
check("at dock.py's own length: nothing set", sets(calls) == [], calls)
check("and the length recorded", kept == "96.39", kept)

print("the phone turned upright")
calls, kept = run(1760, listing("96.390000"), state="96.39")
want = "%.2f" % ((1760 - 26) * 100.0 / 1760)
check("the length set for 1760: " + want,
      sets(calls) == [("-p", "/panels/panel-2/length", "-n", "-t", "double", "-s", want)], calls)
check("and recorded", kept == want, kept)

print("and back on its side")
calls, kept = run(720, listing(want + "0000"), state=want)
check("the length set back to 96.39",
      sets(calls) == [("-p", "/panels/panel-2/length", "-n", "-t", "double", "-s", "96.39")], calls)

print("what is not ours")
calls, kept = run(1760, listing("50.000000"), state="96.39")
check("a length someone chose is left alone", sets(calls) == [] and kept == "96.39", (calls, kept))
calls, kept = run(1760, listing("96.390000", dock_pos="p=10;x=0;y=0"), state="96.39")
check("a dock someone moved is left alone", sets(calls) == [], calls)
calls, kept = run(1760, listing("96.390000", dock_mode="2"), state="96.39")
check("a deskbar is not the dock", sets(calls) == [], calls)
calls, kept = run(1760, listing("96.390000"), mark=None, state=None)
check("no record and no dock.py marker: left alone", sets(calls) == [] and kept is None, (calls, kept))
calls, kept = run(1760, listing("96.390000"), mark="3", state=None)
check("an older dock layout's marker: left alone", sets(calls) == [], calls)
calls, kept = run(1760, listing("96.390000", top=False), state="96.39")
check("no top bar: nothing to meet", sets(calls) == [], calls)

print("a guest from before this, with no record yet")
calls, kept = run(1760, listing("50.000000"), state=None, start=720)
check("a length someone chose (50 %) is left alone, and nothing recorded",
      sets(calls) == [] and kept is None, (calls, kept))
calls, kept = run(720, listing("97.590000"), state=None, start=720)
check("dock.py's length for the screen it first saw (1080 tall) is taken over, set for 720",
      sets(calls) == [("-p", "/panels/panel-2/length", "-n", "-t", "double", "-s", "96.39")]
      and kept == "96.39", (calls, kept))
calls, kept = run(1760, listing("94.170000"), state=None, start=446)
check("a new guest started 446 tall, under the old floor: its length is dock.py's",
      sets(calls) == [("-p", "/panels/panel-2/length", "-n", "-t", "double", "-s", "98.52")], calls)
calls, kept = run(1760, listing("94.170000"), state=None, start=720)
check("the same length with another start height: not one dock.py could have made, left alone",
      sets(calls) == [], calls)
calls, kept = run(1760, listing("98.000000"), state=None, start=720)
check("the one case nothing on a guest can tell apart: a chosen 98 % is also dock.py's for 1300 tall",
      len(sets(calls)) == 1, calls)

print("failures")
calls, kept = run(1760, listing("96.390000"), state="96.39", set_code=1)
check("a refused set is not recorded", kept == "96.39", kept)
calls, kept = run(1760, "", state="96.39", list_code=1)
check("a failed listing sets nothing", sets(calls) == [], calls)
calls, kept = run(20, listing("96.390000"), state="96.39")
check("a screen no taller than the top bar: nothing", sets(calls) == [], calls)

print("the X event, as Xlib lays it out on a 64-bit Linux")
import ctypes
if ctypes.sizeof(ctypes.c_long) == 8:
    check("XEvent is 192 bytes", ctypes.sizeof(m.XEvent) == 192, ctypes.sizeof(m.XEvent))
    check("width at 56 and height at 60", m.XConfigureEvent.width.offset == 56
          and m.XConfigureEvent.height.offset == 60,
          (m.XConfigureEvent.width.offset, m.XConfigureEvent.height.offset))
else:
    print("  (this Python's long is %d bytes: LP64 layout not checkable here)" % ctypes.sizeof(ctypes.c_long))

print("\n%d passed, %d failed" % (passed, failed))
sys.exit(1 if failed or not passed else 0)
