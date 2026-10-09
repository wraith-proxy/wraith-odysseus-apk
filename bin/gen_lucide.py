#!/usr/bin/env python3
"""Writes ui/icons/Lucide.kt: the Lucide icons the app draws, as Compose
ImageVectors. Stdlib only.

    python3 -I bin/gen_lucide.py <lucide-static package dir>

The package is the npm tarball of lucide-static (ISC), extracted:
https://registry.npmjs.org/lucide-static/-/lucide-static-<version>.tgz
To add an icon, add a line to ICONS and run this again.
"""
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# Kotlin name, Lucide name.
ICONS = [
    ("Menu", "menu"), ("Plus", "plus"), ("Search", "search"),
    ("MessageSquare", "message-square"), ("Terminal", "terminal"),
    ("SquareTerminal", "square-terminal"), ("Mail", "mail"), ("Wrench", "wrench"),
    ("Calendar", "calendar"), ("Columns2", "columns-2"), ("BookOpen", "book-open"),
    ("ZoomIn", "zoom-in"), ("Image", "image"), ("Library", "library"), ("Brain", "brain"),
    ("NotebookPen", "notebook-pen"), ("Zap", "zap"), ("CalendarCheck", "calendar-check"),
    ("Palette", "palette"), ("Settings", "settings"), ("ListFilter", "list-filter"),
    ("ChevronDown", "chevron-down"), ("ChevronUp", "chevron-up"),
    ("ChevronLeft", "chevron-left"), ("ChevronRight", "chevron-right"), ("X", "x"),
    ("ArrowLeft", "arrow-left"), ("ArrowUp", "arrow-up"), ("Square", "square"),
    ("LogOut", "log-out"), ("User", "user"), ("ExternalLink", "external-link"),
    ("Folder", "folder"), ("FolderOpen", "folder-open"), ("FolderInput", "folder-input"),
    ("Archive", "archive"), ("ArchiveRestore", "archive-restore"), ("Bookmark", "bookmark"),
    ("Pencil", "pencil"), ("CircleDot", "circle-dot"), ("Copy", "copy"), ("Trash2", "trash-2"),
    ("Ellipsis", "ellipsis"), ("EllipsisVertical", "ellipsis-vertical"),
    ("RefreshCw", "refresh-cw"), ("GitFork", "git-fork"), ("RotateCcw", "rotate-ccw"),
    ("Shrink", "shrink"), ("Lightbulb", "lightbulb"), ("Share2", "share-2"),
    ("TextSelect", "text-select"), ("Volume2", "volume-2"), ("Globe", "globe"),
    ("Paperclip", "paperclip"), ("FileText", "file-text"), ("Sparkle", "sparkle"),
    ("Sparkles", "sparkles"), ("Clock", "clock"), ("Check", "check"), ("Play", "play"),
    ("Pin", "pin"), ("Info", "info"), ("TriangleAlert", "triangle-alert"),
    ("Reply", "reply"), ("ReplyAll", "reply-all"), ("Forward", "forward"),
    ("Inbox", "inbox"), ("Send", "send"), ("Languages", "languages"),
    ("ShieldAlert", "shield-alert"), ("UserPlus", "user-plus"), ("Eye", "eye"),
    ("EyeOff", "eye-off"), ("Download", "download"), ("CalendarPlus", "calendar-plus"),
    ("AlarmClock", "alarm-clock"), ("Tag", "tag"), ("Star", "star"), ("Undo2", "undo-2"),
    ("MailOpen", "mail-open"), ("StickyNote", "sticky-note"), ("ListChecks", "list-checks"),
    ("Telescope", "telescope"), ("Upload", "upload"), ("Heart", "heart"),
    ("KeyRound", "key-round"), ("ShieldCheck", "shield-check"), ("Bot", "bot"),
    ("Link", "link"),
]

OUT = Path(__file__).resolve().parent.parent / "app/src/main/java/com/thomaspeoples/wraithodysseus/ui/icons/Lucide.kt"


def num(v):
    f = float(v)
    s = f"{f:.4f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def to_path(tag, a):
    """One SVG element as path data. Arcs draw circles and rounded corners."""
    if tag == "path":
        return a["d"]
    if tag == "circle":
        cx, cy, r = float(a["cx"]), float(a["cy"]), float(a["r"])
        return f"M{num(cx - r)} {num(cy)}a{num(r)} {num(r)} 0 1 0 {num(2 * r)} 0a{num(r)} {num(r)} 0 1 0 {num(-2 * r)} 0"
    if tag == "ellipse":
        cx, cy, rx, ry = (float(a[k]) for k in ("cx", "cy", "rx", "ry"))
        return f"M{num(cx - rx)} {num(cy)}a{num(rx)} {num(ry)} 0 1 0 {num(2 * rx)} 0a{num(rx)} {num(ry)} 0 1 0 {num(-2 * rx)} 0"
    if tag == "rect":
        x, y = float(a.get("x", 0)), float(a.get("y", 0))
        w, h = float(a["width"]), float(a["height"])
        rx = a.get("rx", a.get("ry"))
        ry = a.get("ry", a.get("rx"))
        if rx is None:
            return f"M{num(x)} {num(y)}h{num(w)}v{num(h)}h{num(-w)}Z"
        rx, ry = min(float(rx), w / 2), min(float(ry), h / 2)
        return (
            f"M{num(x + rx)} {num(y)}h{num(w - 2 * rx)}a{num(rx)} {num(ry)} 0 0 1 {num(rx)} {num(ry)}"
            f"v{num(h - 2 * ry)}a{num(rx)} {num(ry)} 0 0 1 {num(-rx)} {num(ry)}"
            f"h{num(-(w - 2 * rx))}a{num(rx)} {num(ry)} 0 0 1 {num(-rx)} {num(-ry)}"
            f"v{num(-(h - 2 * ry))}a{num(rx)} {num(ry)} 0 0 1 {num(rx)} {num(-ry)}Z"
        )
    if tag == "line":
        return f"M{num(a['x1'])} {num(a['y1'])}L{num(a['x2'])} {num(a['y2'])}"
    if tag in ("polyline", "polygon"):
        pts = a["points"].replace(",", " ").split()
        pairs = [f"{num(pts[i])} {num(pts[i + 1])}" for i in range(0, len(pts), 2)]
        return "M" + "L".join(pairs) + ("Z" if tag == "polygon" else "")
    raise ValueError(f"unsupported element <{tag}>")


def main(package):
    package = Path(package)
    version = json.loads((package / "package.json").read_text())["version"]
    missing = [lucide for _, lucide in ICONS if not (package / "icons" / f"{lucide}.svg").is_file()]
    if missing:
        sys.exit(f"not in lucide-static {version}: {', '.join(missing)}")
    vals, refs = [], []
    for name, lucide in ICONS:
        # The SVG files are the package's real output; icon-nodes.json lacks
        # some icons. Children only, in document order, comments skipped.
        root = ET.parse(package / "icons" / f"{lucide}.svg").getroot()
        elements = [(child.tag.rsplit("}", 1)[-1], child.attrib) for child in root]
        paths = ", ".join(json.dumps(to_path(tag, attrs)) for tag, attrs in elements)
        vals.append(f'    val {name}: ImageVector by lazy {{ icon("{lucide}", {paths}) }}')
        refs.append(f'        "{name}" to {{ {name} }},')
    OUT.write_text(f"""package com.thomaspeoples.wraithodysseus.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// Generated by bin/gen_lucide.py from lucide-static {version} (ISC); do not
// edit by hand. The web UI draws the same 24x24, 2px round-stroke icons.
// Icon() tints the black stroke, so callers just write Icon(Lucide.Copy, "Copy").
object Lucide {{
{chr(10).join(vals)}

    /** Every icon, so a test can build them all. */
    internal val all: List<Pair<String, () -> ImageVector>> = listOf(
{chr(10).join(refs)}
    )
}}

private fun icon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(
        name = "Lucide.$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {{
        paths.forEach {{
            addPath(
                pathData = addPathNodes(it),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }}
    }}.build()
""")
    print(f"{len(ICONS)} icons from lucide-static {version} -> {OUT}")


if __name__ == "__main__":
    main(sys.argv[1])
