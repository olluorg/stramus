#!/usr/bin/env python3
"""
Generates the two icon libraries the collection-icon picker offers, straight into the source tree:

  ui-shared/src/jsMain/kotlin/stramus/ui/IconLibrary.kt   line glyphs  (Lucide, ISC)
  ui-shared/src/jsMain/kotlin/stramus/ui/EmojiLibrary.kt  emoji        (Twemoji, CC-BY 4.0)

Run by hand, not by Gradle, and the result is committed: a build that reaches for a CDN is a build that
fails on a train, and these change about as often as the icon set itself does — which is to say, when
somebody decides it should.

    python3 tools/icon-data/generate.py

Both libraries are inline SVG for the same reason the hand-drawn set in Icon.kt is: the picture has to be
the same picture on every machine. That is what a system emoji font cannot promise — each OS draws its
own, and half of them ignore the surrounding text colour — so the emoji here are drawings we ship, not
characters we hope for.

The emoji keywords come from CLDR by way of emojibase, in every UI language that has them; Turkish has
none there, and falls back to English. Glyph keywords are Lucide's own tags, which exist in English only.
"""

import json
import os
import re
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT_DIR = os.path.join(ROOT, "ui-shared", "src", "jsMain", "kotlin", "stramus", "ui")

LUCIDE = "https://cdn.jsdelivr.net/npm/lucide-static@1.31.0/icons/{name}.svg"
LUCIDE_TAGS = "https://cdn.jsdelivr.net/npm/lucide-static@1.31.0/tags.json"
TWEMOJI = "https://cdn.jsdelivr.net/npm/@twemoji/svg@15.0.0/{code}.svg"
EMOJIBASE = "https://cdn.jsdelivr.net/npm/emojibase-data@16.0.3/{lang}/data.json"

# The UI's languages, and the emojibase locale each takes its keywords from. Turkish is absent there,
# and gets English rather than nothing: a search box that matches nothing is worse than one in the
# wrong language.
LANG_TO_LOCALE = {
    "en": "en", "ru": "ru", "de": "de", "fr": "fr", "es": "es", "pt-BR": "pt",
    "ja": "ja", "ko": "ko", "zh-CN": "zh", "it": "it", "tr": "en",
}

# The glyphs, in the order the picker lays them out — roughly by what they are about, so that scrolling
# the grid is its own kind of browsing for somebody who does not know what to search for.
GLYPHS = [
    # Marks and containers
    "folder", "folder-open", "star", "heart", "bookmark", "flag", "tag", "tags",
    "smile",
    "pin", "inbox", "archive", "box", "package", "layers", "grid-2x2", "list",
    # Work
    "briefcase", "building-2", "store", "landmark", "presentation", "clipboard-list", "notebook-pen", "pencil",
    "chart-line", "chart-pie", "chart-column", "target", "trending-up", "calendar", "calendar-days", "clock",
    "alarm-clock", "hourglass", "handshake", "users", "user-round", "id-card", "mail", "message-circle",
    "phone", "megaphone", "bell", "printer", "paperclip", "scissors", "ruler", "calculator",
    # Home and life
    "house", "bed", "sofa", "lamp", "shirt", "shopping-bag", "shopping-cart", "gift",
    "cake", "pizza", "utensils", "coffee", "wine", "beer", "apple", "carrot",
    "baby", "dog", "cat", "bird", "fish", "paw-print", "leaf", "sprout",
    "trees", "flower", "mountain", "sun", "moon", "cloud", "umbrella", "snowflake",
    "droplet", "flame", "wind", "waves",
    # Getting about
    "plane", "car", "bus", "train-front", "bike", "ship", "fuel", "map",
    "map-pin", "compass", "globe", "tent", "luggage", "ticket", "hotel", "signpost",
    # Making things
    "code", "terminal", "bug", "git-branch", "database", "server", "cloud-upload", "wifi",
    "cpu", "hard-drive", "monitor", "laptop", "smartphone", "keyboard", "camera", "image",
    "video", "film", "music", "headphones", "mic", "radio", "tv", "gamepad-2",
    "palette", "brush", "pen-tool", "book", "book-open", "newspaper", "graduation-cap", "library",
    "lightbulb", "flask-conical", "microscope", "telescope", "atom", "brain", "puzzle", "wand-sparkles",
    # Money, health, keeping safe
    "banknote", "coins", "credit-card", "piggy-bank", "receipt", "wallet", "scale", "gavel",
    "heart-pulse", "stethoscope", "pill", "dumbbell", "footprints", "shield", "key", "lock",
    "trophy", "medal", "crown", "gem", "rocket", "zap", "sparkles", "infinity",
]

# The emoji, in the same spirit: a shortlist rather than the whole of Unicode, because every one of them
# is a drawing that ships with the app, and because a grid nobody can reach the end of is not a choice.
EMOJI = [
    # Faces and people
    "😀", "😄", "😁", "😊", "🙂", "😉", "😍", "🥰", "😎", "🤩", "🤔", "😴",
    "🥳", "😇", "🤯", "😅", "😂", "🙃", "😢", "😡", "🥺", "🤗", "🤠", "🤖",
    "👋", "👍", "👏", "🙏", "💪", "👀", "🧠", "👶", "🧑", "👩", "👨", "🧙",
    # Animals and nature
    "🐶", "🐱", "🐭", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁", "🐮", "🐷",
    "🐸", "🐵", "🐔", "🐧", "🦅", "🦉", "🐴", "🦄", "🐝", "🦋", "🐢", "🐍",
    "🐙", "🦈", "🐬", "🐳", "🌱", "🌲", "🌳", "🌴", "🌵", "🍀", "🌷", "🌸",
    "🌹", "🌻", "🍁", "🍄", "⭐", "🌟", "✨", "⚡", "🔥", "❄️", "🌈", "☀️",
    "🌙", "☁️", "🌊", "💧",
    # Food
    "🍏", "🍎", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🍒", "🥝", "🍅", "🥑",
    "🥦", "🥕", "🌽", "🍞", "🧀", "🥚", "🥞", "🥓", "🍔", "🍟", "🍕", "🌮",
    "🥗", "🍝", "🍜", "🍣", "🍱", "🍚", "🍦", "🍰", "🎂", "🧁", "🍫", "🍬",
    "🍯", "☕", "🍵", "🍺", "🍷", "🥂",
    # Activity and travel
    "⚽", "🏀", "🏈", "🎾", "🏐", "🎱", "🏓", "🏸", "🥊", "🏹", "🎣", "🛹",
    "⛸️", "🎿", "🏂", "🏋️", "🧘", "🏄", "🚴", "🧗", "🎪", "🎭", "🎨", "🎬",
    "🎤", "🎧", "🎹", "🥁", "🎸", "🎻", "🎲", "🎯", "🎳", "🎮", "🧩", "🚗",
    "🚕", "🚌", "🏎️", "🚑", "🚚", "🚲", "🛵", "🏍️", "🚀", "✈️", "🚁", "⛵",
    "🚢", "🚂", "🗺️", "🏔️", "🏕️", "🏖️", "🏝️", "🌋", "🏰", "🗽", "🎡", "🎢",
    "🏠", "🏡", "🏢", "🏭", "🏥", "🏦", "🏫", "🏪",
    # Objects and symbols
    "⌚", "📱", "💻", "🖥️", "🖨️", "🕹️", "💾", "📷", "🎥", "📺", "📻", "☎️",
    "⏰", "⌛", "🔋", "🔌", "💡", "🔦", "🕯️", "💰", "💵", "💳", "💎", "⚖️",
    "🔧", "🔨", "⚙️", "🔩", "⛓️", "🔪", "🗡️", "🛡️", "🔮", "🧿", "⚗️", "🔬",
    "🔭", "💊", "💉", "🩺", "🚪", "🛏️", "🛁", "🧹", "🛒", "📚", "📖", "📝",
    "✏️", "🖊️", "📐", "📌", "📍", "🔖", "📎", "🗂️", "📁", "📅", "📆", "📊",
    "📈", "📉", "📋", "🗒️", "🔍", "🔑", "🗝️", "🔒", "🎁", "🎈", "🎉", "🏆",
    "🥇", "👑", "💼", "🎒", "👓", "👕", "👟", "🧢", "❤️", "🧡", "💛", "💚",
    "💙", "💜", "🖤", "🤍", "💔", "💯", "✅", "❌", "⭕", "❓", "❗", "💬",
]


def fetch(url, binary=False):
    with urllib.request.urlopen(url, timeout=30) as response:
        data = response.read()
    return data if binary else data.decode("utf-8")


def kotlin_string(value):
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def glyph_body(svg):
    """Lucide's paths, with its own <svg> wrapper dropped — Icon.kt supplies one of its own."""
    inner = svg[svg.index(">", svg.index("<svg")) + 1:svg.rindex("</svg>")]
    inner = re.sub(r"<!--.*?-->", "", inner, flags=re.S)
    return re.sub(r"\s+", " ", inner).strip()


def emoji_code(char):
    """The name Twemoji files go by: codepoints in hex, joined by dashes, with FE0F dropped."""
    points = [f"{ord(c):x}" for c in char if ord(c) != 0xFE0F]
    return "-".join(points)


def emoji_body(svg):
    """The drawing inside Twemoji's own <svg>, whose viewBox is 36×36 rather than Icon.kt's 24×24."""
    return re.sub(r"\s+", " ", svg[svg.index(">", svg.index("<svg")) + 1:svg.rindex("</svg>")]).strip()


def write_glyphs():
    tags = json.loads(fetch(LUCIDE_TAGS))
    entries = []
    for name in GLYPHS:
        try:
            body = glyph_body(fetch(LUCIDE.format(name=name)))
        except Exception as error:  # a renamed icon is a line to fix here, not a broken build
            print(f"  ! {name}: {error}", file=sys.stderr)
            continue
        words = " ".join(dict.fromkeys(name.replace("-", " ").split() + tags.get(name, [])))
        entries.append((name, body, words))
    print(f"  {len(entries)} glyphs")

    with open(os.path.join(OUT_DIR, "IconLibrary.kt"), "w") as out:
        out.write(HEADER_GLYPHS)
        out.write("internal val LIBRARY_ICONS: Map<String, String> = mapOf(\n")
        for name, body, _ in entries:
            out.write(f"    {kotlin_string(name)} to {kotlin_string(body)},\n")
        out.write(")\n\n")
        out.write("internal val LIBRARY_ICON_ORDER: List<String> = LIBRARY_ICONS.keys.toList()\n\n")
        out.write("internal val LIBRARY_ICON_KEYWORDS: Map<String, String> = mapOf(\n")
        for name, _, words in entries:
            out.write(f"    {kotlin_string(name)} to {kotlin_string(words)},\n")
        out.write(")\n")


def write_emoji():
    codes = []
    bodies = {}
    for char in EMOJI:
        code = emoji_code(char)
        if code in bodies:
            continue
        try:
            bodies[code] = emoji_body(fetch(TWEMOJI.format(code=code)))
        except Exception as error:
            print(f"  ! {char} ({code}): {error}", file=sys.stderr)
            continue
        codes.append(code)
    print(f"  {len(codes)} emoji")

    keywords = {}
    for lang, locale in LANG_TO_LOCALE.items():
        data = json.loads(fetch(EMOJIBASE.format(lang=locale)))
        by_code = {}
        for item in data:
            key = "-".join(p for p in item["hexcode"].lower().split("-") if p != "fe0f")
            if key not in bodies or key in by_code:
                continue
            # The label first — it is what somebody actually types — then the tags CLDR gives it, kept
            # short: four is enough to catch the obvious synonyms and not enough to bloat the bundle.
            words = [item.get("label", "")] + list(item.get("tags") or [])[:4]
            by_code[key] = " ".join(dict.fromkeys(w.lower() for w in words if w))
        keywords[lang] = by_code

    with open(os.path.join(OUT_DIR, "EmojiLibrary.kt"), "w") as out:
        out.write(HEADER_EMOJI)
        out.write("internal val EMOJI_SVG: Map<String, String> = mapOf(\n")
        for code in codes:
            out.write(f"    {kotlin_string(code)} to {kotlin_string(bodies[code])},\n")
        out.write(")\n\n")
        out.write("internal val EMOJI_ORDER: List<String> = EMOJI_SVG.keys.toList()\n\n")
        out.write("internal val EMOJI_KEYWORDS: Map<String, Map<String, String>> = mapOf(\n")
        for lang in LANG_TO_LOCALE:
            out.write(f"    {kotlin_string(lang)} to mapOf(\n")
            for code in codes:
                words = keywords.get(lang, {}).get(code)
                if words:
                    out.write(f"        {kotlin_string(code)} to {kotlin_string(words)},\n")
            out.write("    ),\n")
        out.write(")\n")


HEADER_GLYPHS = """package stramus.ui

/*
 * Generated by tools/icon-data/generate.py — do not edit by hand.
 *
 * The line glyphs a collection can be marked with, beyond the ones Icon.kt draws for the app's own
 * furniture. They are Lucide's (https://lucide.dev, ISC licence, © Lucide Contributors), whose 24×24
 * round-capped stroke is the style Icon.kt was already written in — so the two sets sit beside each
 * other in the picker without either looking borrowed.
 *
 * Only the paths are kept: the stroke, the width and the caps come from Icon.kt's own wrapper, which is
 * what makes every glyph here obey `currentColor` and the collection's chosen colour.
 */

"""

HEADER_EMOJI = """package stramus.ui

/*
 * Generated by tools/icon-data/generate.py — do not edit by hand.
 *
 * The emoji a collection can be marked with, as drawings rather than characters: Twemoji
 * (https://github.com/jdecked/twemoji), © Twitter and contributors, graphics licensed CC-BY 4.0.
 *
 * Shipping the pictures is the whole point. A character is drawn by whatever font the machine happens
 * to have — a different picture on every OS, and on some of them no picture at all — which is exactly
 * the reason Icon.kt gives for not using emoji as UI glyphs in the first place. These are the same
 * drawing everywhere, and they survive a screenshot, an old browser and a Linux install with no emoji
 * font at all.
 *
 * The viewBox is 36×36 (Twemoji's own), not the 24×24 the line glyphs use, and they carry their own
 * colours rather than following `currentColor` — see `emojiSvg`.
 *
 * [EMOJI_KEYWORDS] is what the picker's search matches on, per UI language, from CLDR by way of
 * emojibase. Turkish has no CLDR annotations there and takes the English ones.
 */

"""


def main():
    print("glyphs…")
    write_glyphs()
    print("emoji…")
    write_emoji()
    print("done")


if __name__ == "__main__":
    main()
