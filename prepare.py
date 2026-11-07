"""Remplace l'icône, la couleur et l'écran de démarrage du projet Android par ceux de GUI FLIX."""
import glob, os, shutil, sys
from PIL import Image, ImageDraw

RES = "android/app/src/main/res"
INK, GOLD = (15, 12, 26), (232, 184, 74)

def draw_icon(size, ring=0.30, bg=True, circle=False):
    S = size * 4
    im = Image.new("RGBA", (S, S), INK + (255,) if bg else (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    c, r = S / 2, S * ring
    d.ellipse([c - r, c - r, c + r, c + r], outline=GOLD + (255,), width=max(2, int(S * 0.045)))
    t = S * 0.13 * (ring / 0.30)
    d.polygon([(c - t * 0.7, c - t * 1.15), (c - t * 0.7, c + t * 1.15), (c + t * 1.25, c)], fill=GOLD + (255,))
    im = im.resize((size, size), Image.LANCZOS)
    if circle:
        m = Image.new("L", (size, size), 0)
        ImageDraw.Draw(m).ellipse([0, 0, size - 1, size - 1], fill=255)
        out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        out.paste(im, (0, 0), m)
        im = out
    return im

for name, (launcher, foreground) in {"mdpi": (48, 108), "hdpi": (72, 162), "xhdpi": (96, 216), "xxhdpi": (144, 324), "xxxhdpi": (192, 432)}.items():
    d = f"{RES}/mipmap-{name}"
    os.makedirs(d, exist_ok=True)
    draw_icon(launcher).save(f"{d}/ic_launcher.png")
    draw_icon(launcher, circle=True).save(f"{d}/ic_launcher_round.png")
    draw_icon(foreground, ring=0.24, bg=False).save(f"{d}/ic_launcher_foreground.png")

os.makedirs(f"{RES}/values", exist_ok=True)
with open(f"{RES}/values/ic_launcher_background.xml", "w", encoding="utf-8") as f:
    f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">#0F0C1A</color>\n</resources>\n')

for path in glob.glob(f"{RES}/drawable*/splash.png"):
    w, h = Image.open(path).size
    img = Image.new("RGB", (w, h), INK)
    S = int(min(w, h) * 0.34); k = 4; big = Image.new("RGBA", (S * k, S * k), (0, 0, 0, 0)); d = ImageDraw.Draw(big)
    c, r, sw = S * k / 2, S * k * 0.32, int(S * k * 0.13)
    d.arc([c - r, c - r, c + r, c + r], start=0, end=320, fill=GOLD + (255,), width=sw)
    d.rectangle([c + r * 0.02, c - sw / 2, c + r + sw / 2, c + sw / 2], fill=GOLD + (255,))
    g = big.resize((S, S), Image.LANCZOS)
    img.paste(g, ((w - S) // 2, (h - S) // 2), g)
    img.save(path)
print("Icônes et écran de démarrage prêts.")

# ---- Téléchargeur natif (pause, reprise, annulation, notification) ----
MAIN_PLAIN = """package %s;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {}
"""
MAIN_WITH = """package %s;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(GFDownloaderPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
"""
mains = glob.glob("android/app/src/main/java/**/MainActivity.java", recursive=True)
if mains:
    main = mains[0]; folder = os.path.dirname(main)
    pkg = folder.split("android/app/src/main/java/")[1].replace("/", ".")
    target = os.path.join(folder, "GFDownloaderPlugin.java")
    if "--without-downloader" in sys.argv:
        if os.path.exists(target): os.remove(target)
        open(main, "w", encoding="utf-8").write(MAIN_PLAIN % pkg)
        print("Version sans téléchargeur natif.")
    else:
        src = open("native/GFDownloaderPlugin.java", encoding="utf-8").read().replace("package com.guiflix.app;", "package " + pkg + ";", 1)
        open(target, "w", encoding="utf-8").write(src)
        open(main, "w", encoding="utf-8").write(MAIN_WITH % pkg)
        mf = "android/app/src/main/AndroidManifest.xml"
        t = open(mf, encoding="utf-8").read()
        if "POST_NOTIFICATIONS" not in t:
            t = t.replace("</manifest>", '    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />\n</manifest>')
            open(mf, "w", encoding="utf-8").write(t)
        print("Téléchargeur natif ajouté.")
