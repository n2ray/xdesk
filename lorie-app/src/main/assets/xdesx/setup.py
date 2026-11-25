"""Build a private panel profile from Android's launchable Activity list."""
import json
from pathlib import Path
import xml.etree.ElementTree as ET

base = Path.home() / ".local/share/xdesx"
apps = base / "data/applications"
apps.mkdir(parents=True, exist_ok=True)
catalog = json.loads((base / "catalog.json").read_text())
menu = ET.Element("Menu")
ET.SubElement(menu, "Name").text = "Android"
ET.SubElement(menu, "AppDir").text = str(apps)
include = ET.SubElement(menu, "Include")
for index, app in enumerate(catalog):
    name = f"xdesx-{index}.desktop"
    label = " ".join(app[1].splitlines()).replace("\\", "\\\\")
    (apps / name).write_text(f"[Desktop Entry]\nType=Application\nName={label}\n"
                            f"Exec=python {base}/window.py {index}\nIcon=applications-other\n")
    ET.SubElement(include, "Filename").text = name
ET.ElementTree(menu).write(base / "android.menu", encoding="utf-8")
profile = base / "config/xfce4/xfconf/xfce-perchannel-xml"
profile.mkdir(parents=True, exist_ok=True)
channel = ET.Element("channel", name="xfce4-panel", version="1.0")
def prop(parent, name, kind, value=None):
    attrs = dict(name=name, type=kind)
    if value is not None:
        attrs["value"] = str(value)
    return ET.SubElement(parent, "property", attrs)
prop(channel, "configver", "int", 2)
panels = prop(channel, "panels", "array")
ET.SubElement(panels, "value", type="int", value="1")
panel = prop(panels, "panel-1", "empty")
for key, kind, value in [("position","string","p=6;x=0;y=0"),("position-locked","bool","true"),
                         ("length","double","100.0"),("size","uint",36)]:
    prop(panel,key,kind,value)
ET.SubElement(prop(panel,"plugin-ids","array"),"value",type="int",value="1")
plugin = prop(prop(channel,"plugins","empty"),"plugin-1","string","applicationsmenu")
for key,kind,value in [("button-title","string","安卓应用"),("show-button-title","bool","true"),
                       ("custom-menu","bool","true"),("custom-menu-file","string",base / "android.menu")]:
    prop(plugin,key,kind,value)
ET.ElementTree(channel).write(profile / "xfce4-panel.xml",encoding="utf-8")
