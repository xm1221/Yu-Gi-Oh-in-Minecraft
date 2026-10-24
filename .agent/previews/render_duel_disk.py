# -*- coding: utf-8 -*-
"""Import Minecraft item JSON model into Blender and render held-view previews (framed)."""
import bpy
import json
import math
import os
import shutil
from mathutils import Euler, Vector, Matrix

ROOT = r"E:\miemod\ygo\Yu-Gi-Oh in Minecraft"
MODEL_PATH = os.path.join(ROOT, r"common\src\main\resources\assets\ygomc\models\item\duel_disk.json")
TEX_PATH = os.path.join(ROOT, r"common\src\main\resources\assets\ygomc\textures\item\duel_disk.png")
OUT_DIR = os.path.join(ROOT, r".agent\previews")
DESKTOP_OUT = r"C:\Users\Administrator\Desktop\ygomc-previews"
os.makedirs(OUT_DIR, exist_ok=True)
os.makedirs(DESKTOP_OUT, exist_ok=True)

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene

with open(MODEL_PATH, "r", encoding="utf-8") as f:
    model = json.load(f)

TEX_UNITS = float(model.get("texture_size", [16, 16])[0])
print("texture_size units=", TEX_UNITS)

img = bpy.data.images.load(TEX_PATH)
img.pack()
img.colorspace_settings.name = "sRGB"

mat = bpy.data.materials.new(name="duel_disk")
mat.use_nodes = True
nt = mat.node_tree
nodes = nt.nodes
links = nt.links
nodes.clear()
tex = nodes.new("ShaderNodeTexImage")
tex.image = img
tex.interpolation = "Closest"
bsdf = nodes.new("ShaderNodeBsdfPrincipled")
bsdf.inputs["Roughness"].default_value = 0.45
out = nodes.new("ShaderNodeOutputMaterial")
links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
if "Alpha" in bsdf.inputs:
    links.new(tex.outputs["Alpha"], bsdf.inputs["Alpha"])
links.new(bsdf.outputs["BSDF"], out.inputs["Surface"])
try:
    mat.blend_method = "CLIP"
except Exception:
    pass

collection = bpy.data.collections.new("duel_disk")
scene.collection.children.link(collection)


def mc_uv_to_blender(uv, tex_size_units=TEX_UNITS):
    u1, v1, u2, v2 = uv
    return [
        (u1 / tex_size_units, 1.0 - v2 / tex_size_units),
        (u2 / tex_size_units, 1.0 - v2 / tex_size_units),
        (u2 / tex_size_units, 1.0 - v1 / tex_size_units),
        (u1 / tex_size_units, 1.0 - v1 / tex_size_units),
    ]


def apply_mc_rotation(obj, rot):
    """Apply Minecraft element rotation (angle/axis/origin) in Blender space."""
    if not rot:
        return
    angle = math.radians(float(rot.get("angle", 0)))
    axis = rot.get("axis", "y")
    ox, oy, oz = rot.get("origin", [8, 8, 8])
    # MC (x,y,z) Y-up -> Blender (x,z,y) Z-up
    origin_bl = Vector((ox, oz, oy))
    axis_map = {"x": "X", "y": "Z", "z": "Y"}
    bl_axis = axis_map.get(axis, "Z")
    # rotate around origin: T * R * T^-1
    T = Matrix.Translation(origin_bl)
    R = Matrix.Rotation(angle, 4, bl_axis)
    M = T @ R @ T.inverted()
    obj.matrix_world = M @ obj.matrix_world


def add_element(el, idx):
    fx, fy, fz = el["from"]
    tx, ty, tz = el["to"]
    name = el.get("name", f"el_{idx}")
    # MC (x,y,z) Y-up -> Blender (x,z,y) Z-up
    verts = [
        (fx, fz, fy), (tx, fz, fy), (tx, tz, fy), (fx, tz, fy),
        (fx, fz, ty), (tx, fz, ty), (tx, tz, ty), (fx, tz, ty),
    ]
    face_defs = {
        "down":  (0, 3, 2, 1),
        "up":    (4, 5, 6, 7),
        "north": (0, 1, 5, 4),
        "south": (3, 7, 6, 2),
        "west":  (0, 4, 7, 3),
        "east":  (1, 2, 6, 5),
    }
    faces_data = el.get("faces", {})
    face_indices = []
    face_uvs = []
    for fname, idxs in face_defs.items():
        if fname not in faces_data:
            continue
        face_indices.append(idxs)
        uv = faces_data[fname].get("uv", [0, 0, 2, 2])
        rot = int(faces_data[fname].get("rotation", 0))
        corners = mc_uv_to_blender(uv)
        steps = (rot // 90) % 4
        if steps:
            corners = corners[steps:] + corners[:steps]
        face_uvs.append(corners)

    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(verts, [], face_indices)
    mesh.update()
    uv_layer = mesh.uv_layers.new(name="UVMap")
    for poly in mesh.polygons:
        fuv = face_uvs[poly.index]
        for i, li in enumerate(poly.loop_indices):
            uv_layer.data[li].uv = fuv[i]

    obj = bpy.data.objects.new(name, mesh)
    collection.objects.link(obj)
    obj.data.materials.append(mat)
    apply_mc_rotation(obj, el.get("rotation"))
    return obj


objs = [add_element(el, i) for i, el in enumerate(model.get("elements", []))]

pivot = bpy.data.objects.new("pivot", None)
collection.objects.link(pivot)
pivot.location = (8.0, 8.0, 8.0)
for o in objs:
    o.parent = pivot
    o.matrix_parent_inverse = pivot.matrix_world.inverted()


def apply_display(display_key):
    d = model.get("display", {}).get(display_key, {})
    rot = d.get("rotation", [0, 0, 0])
    trans = d.get("translation", [0, 0, 0])
    scale = d.get("scale", [1, 1, 1])
    rx, ry, rz = [math.radians(a) for a in rot]
    e_mc = Euler((rx, ry, rz), "XYZ")
    R_mc = e_mc.to_matrix().to_4x4()
    P = Matrix(((1, 0, 0, 0), (0, 0, 1, 0), (0, 1, 0, 0), (0, 0, 0, 1)))
    R_bl = P @ R_mc @ P.inverted()
    pivot.rotation_mode = "XYZ"
    pivot.rotation_euler = R_bl.to_euler("XYZ")
    tx, ty, tz = trans
    pivot.location = (8.0 + tx, 8.0 + tz, 8.0 + ty)
    sx, sy, sz = scale
    pivot.scale = (sx, sz, sy)


def world_bbox_center_and_size():
    bpy.context.view_layer.update()
    mins = Vector((1e9, 1e9, 1e9))
    maxs = Vector((-1e9, -1e9, -1e9))
    for o in objs:
        for corner in o.bound_box:
            w = o.matrix_world @ Vector(corner)
            mins.x = min(mins.x, w.x); mins.y = min(mins.y, w.y); mins.z = min(mins.z, w.z)
            maxs.x = max(maxs.x, w.x); maxs.y = max(maxs.y, w.y); maxs.z = max(maxs.z, w.z)
    center = (mins + maxs) * 0.5
    size = (maxs - mins).length
    return center, size, mins, maxs


sun = bpy.data.lights.new("Sun", type="SUN"); sun.energy = 2.5
sun_o = bpy.data.objects.new("Sun", sun); scene.collection.objects.link(sun_o)
sun_o.rotation_euler = (math.radians(45), math.radians(15), math.radians(25))
fill = bpy.data.lights.new("Fill", type="AREA"); fill.energy = 120; fill.size = 40
fill_o = bpy.data.objects.new("Fill", fill); scene.collection.objects.link(fill_o)
fill_o.location = (-15, -18, 20)
rim = bpy.data.lights.new("Rim", type="AREA"); rim.energy = 60; rim.size = 25
rim_o = bpy.data.objects.new("Rim", rim); scene.collection.objects.link(rim_o)
rim_o.location = (18, 10, 12)

scene.world = bpy.data.worlds.new("World")
scene.world.use_nodes = True
bg = scene.world.node_tree.nodes["Background"]
bg.inputs[0].default_value = (0.18, 0.19, 0.22, 1)
bg.inputs[1].default_value = 1.0

cam_data = bpy.data.cameras.new("Cam")
cam = bpy.data.objects.new("Cam", cam_data)
scene.collection.objects.link(cam)
scene.camera = cam
cam_data.lens = 45


def aim_camera(elev_deg, azim_deg, margin=1.55):
    center, size, mins, maxs = world_bbox_center_and_size()
    fov = cam_data.angle
    dist = max(size * margin / (2.0 * math.tan(fov * 0.5)), 8.0)
    elev = math.radians(elev_deg)
    azim = math.radians(azim_deg)
    loc = Vector((
        center.x + dist * math.cos(elev) * math.sin(azim),
        center.y - dist * math.cos(elev) * math.cos(azim),
        center.z + dist * math.sin(elev),
    ))
    cam.location = loc
    direction = center - loc
    cam.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()
    print(f"bbox center={tuple(center)} size={size:.2f} dist={dist:.2f}")


scene.render.engine = "CYCLES"
scene.cycles.samples = 48
try:
    scene.cycles.use_denoising = True
except Exception:
    pass
scene.render.resolution_x = 1024
scene.render.resolution_y = 768
scene.render.image_settings.file_format = "PNG"
scene.render.film_transparent = False
try:
    prefs = bpy.context.preferences.addons["cycles"].preferences
    prefs.compute_device_type = "CUDA"
    for d in prefs.devices:
        d.use = True
    scene.cycles.device = "GPU"
except Exception:
    scene.cycles.device = "CPU"


def save_both(name):
    src = os.path.join(OUT_DIR, name)
    scene.render.filepath = src
    bpy.ops.render.render(write_still=True)
    shutil.copy2(src, os.path.join(DESKTOP_OUT, name))
    print("WROTE", src)
    return src


def render_view(display_key, out_name, elev, azim):
    apply_display(display_key)
    aim_camera(elev, azim)
    return save_both(out_name)


paths = []
paths.append(render_view("firstperson_righthand", "duel_disk_fp.png", elev=18, azim=50))
paths.append(render_view("thirdperson_righthand", "duel_disk_tp.png", elev=12, azim=95))
paths.append(render_view("gui", "duel_disk_gui.png", elev=32, azim=40))
pivot.rotation_euler = (0, 0, 0)
pivot.location = (8, 8, 8)
pivot.scale = (1, 1, 1)
aim_camera(28, 38)
paths.append(save_both("duel_disk_rest.png"))
pivot.rotation_euler = (0, 0, 0)
pivot.location = (8, 8, 8)
pivot.scale = (1, 1, 1)
aim_camera(70, 20)
paths.append(save_both("duel_disk_top.png"))

blend_path = os.path.join(OUT_DIR, "duel_disk_preview.blend")
bpy.ops.wm.save_as_mainfile(filepath=blend_path)
print("DONE", paths)
print("BLEND", blend_path)
