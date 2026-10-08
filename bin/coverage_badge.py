#!/usr/bin/env python3
"""Render a coverage badge SVG from Kover's XML report.

genbadge, which the platform repo uses, only parses Cobertura XML (root tag
<coverage>). Kover emits JaCoCo format (root tag <report>), so genbadge dies
on it with a bare AssertionError. Converting between the two formats, or
adding a second badge library to the CI image, both cost more than the
twenty lines it takes to read one counter and emit one SVG -- and this way
the whole thing is stdlib, so the image needs nothing it does not already
have.

Reports LINE coverage, matching what the platform repo's own badge reports.

    python3 bin/coverage_badge.py app/build/reports/kover/report.xml docs/coverage.svg
"""

import sys
import xml.etree.ElementTree as ET

# Same thresholds genbadge uses, so this badge and the platform repo's read
# the same way at a glance.
BANDS = [
    (95.0, "#4c1"),      # brightgreen
    (90.0, "#97ca00"),   # green
    (75.0, "#a4a61d"),   # yellowgreen
    (60.0, "#dfb317"),   # yellow
    (40.0, "#fe7d37"),   # orange
    (0.0, "#e05d44"),    # red
]

SVG = '''<svg xmlns="http://www.w3.org/2000/svg" width="{total}" height="20" \
role="img" aria-label="coverage: {pct}%">
  <title>coverage: {pct}%</title>
  <linearGradient id="s" x2="0" y2="100%">
    <stop offset="0" stop-color="#bbb" stop-opacity=".1"/>
    <stop offset="1" stop-opacity=".1"/>
  </linearGradient>
  <clipPath id="r"><rect width="{total}" height="20" rx="3" fill="#fff"/></clipPath>
  <g clip-path="url(#r)">
    <rect width="{label_w}" height="20" fill="#555"/>
    <rect x="{label_w}" width="{value_w}" height="20" fill="{colour}"/>
    <rect width="{total}" height="20" fill="url(#s)"/>
  </g>
  <g fill="#fff" text-anchor="middle" \
font-family="Verdana,Geneva,DejaVu Sans,sans-serif" font-size="110">
    <text x="{label_x}" y="150" fill="#010101" fill-opacity=".3" \
transform="scale(.1)" textLength="{label_t}">coverage</text>
    <text x="{label_x}" y="140" transform="scale(.1)" \
textLength="{label_t}">coverage</text>
    <text x="{value_x}" y="150" fill="#010101" fill-opacity=".3" \
transform="scale(.1)" textLength="{value_t}">{pct}%</text>
    <text x="{value_x}" y="140" transform="scale(.1)" \
textLength="{value_t}">{pct}%</text>
  </g>
</svg>
'''


def line_coverage(report_path):
    """Percentage of lines covered, from the report's top-level LINE counter.

    Only direct children are read: every package and class carries its own
    <counter> elements too, and summing those would double-count.
    """
    root = ET.parse(report_path).getroot()
    for counter in root.findall("counter"):
        if counter.get("type") == "LINE":
            covered = int(counter.get("covered"))
            missed = int(counter.get("missed"))
            total = covered + missed
            return 100.0 * covered / total if total else 0.0
    raise SystemExit("no top-level LINE counter in %s" % report_path)


def render(pct):
    colour = next(c for threshold, c in BANDS if pct >= threshold)
    text = "%.1f" % pct
    label_w = 62
    value_w = 8 * len(text) + 18
    return SVG.format(
        pct=text,
        colour=colour,
        label_w=label_w,
        value_w=value_w,
        total=label_w + value_w,
        label_x=label_w * 5,
        label_t=(label_w - 10) * 10,
        value_x=(label_w + value_w / 2) * 10,
        value_t=(value_w - 10) * 10,
    )


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: coverage_badge.py <kover-report.xml> <out.svg>")
    pct = line_coverage(sys.argv[1])
    with open(sys.argv[2], "w") as out:
        out.write(render(pct))
    print("coverage %.1f%% -> %s" % (pct, sys.argv[2]))


def _self_check():
    assert next(c for t, c in BANDS if 100.0 >= t) == "#4c1"
    assert next(c for t, c in BANDS if 4.5 >= t) == "#e05d44"
    assert next(c for t, c in BANDS if 75.0 >= t) == "#a4a61d"
    svg = render(4.5)
    assert svg.startswith("<svg") and "4.5%" in svg and "#e05d44" in svg
    assert render(100.0).count("100.0%") == 4
    print("self-check OK")


if __name__ == "__main__":
    if len(sys.argv) == 2 and sys.argv[1] == "--self-check":
        _self_check()
    else:
        main()
