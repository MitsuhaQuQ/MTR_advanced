"""Export the supplied Blockbench end gates into Minecraft 1.20 block/item resources."""
import base64
import copy
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
# The leaves are drawn at z 0..2 of the Blockbench models; platform screen door and gate glass stands at z 2..4 of its
# block (its model's x 2..4 turned by the blockstate), so the gates are moved back by this much to line up (0.65.29).
Z_OFFSET = 2
# The hinge the open leaf turns about, in the closed model as written: the jamb opposite the handle.
HINGE = (15, 1 + Z_OFFSET)
# The same hinge once the brush has turned the gate left to right (0.77.0)
MIRRORED_HINGE = (16 - HINGE[0], HINGE[1])
# The blockstate's turn of an open gate, after the gate's own facing, by whether it is turned left to right and whether
# it swings back into the block: the hinge at the other end turns the other way to swing to the same side
OPEN_TURN = {(False, False): 90, (False, True): 270, (True, False): 270, (True, True): 90}
ASSETS = ROOT / "resources/common/normal/assets/mtr"


# The leaf's own plane in the Blockbench models, which the door is mirrored about: the handle belongs outside the
# platform and the push bar inside, and every model had them the other way round (user, 2026-11-14).
LEAF_PLANE = 1


def mirrored(element):
    """The cube flipped front to back about the leaf's plane, faces and all."""
    low, high = element["from"][2], element["to"][2]
    element["from"][2], element["to"][2] = 2 * LEAF_PLANE - high, 2 * LEAF_PLANE - low
    faces = element["faces"]
    north, south = faces.pop("north", None), faces.pop("south", None)
    if north is not None:
        faces["south"] = north
    if south is not None:
        faces["north"] = south
    for side in ("east", "west"):
        if side in faces:
            uv = faces[side]["uv"]
            uv[0], uv[2] = uv[2], uv[0]
    for side in ("up", "down"):
        if side in faces:
            uv = faces[side]["uv"]
            uv[1], uv[3] = uv[3], uv[1]
    return element


def mirrored_across(element):
    """The cube flipped left to right about the block's middle (x 8), so the hinge goes to the other end (0.77.0): the
    brush changes a gate to this, and it still swings the same way."""
    low, high = element["from"][0], element["to"][0]
    element["from"][0], element["to"][0] = 16 - high, 16 - low
    faces = element["faces"]
    east, west = faces.pop("east", None), faces.pop("west", None)
    if east is not None:
        faces["west"] = east
    if west is not None:
        faces["east"] = west
    for side in ("north", "south", "up", "down"):
        if side in faces:
            uv = faces[side]["uv"]
            uv[0], uv[2] = uv[2], uv[0]
    if "rotation" in element:
        rotation = element["rotation"]
        rotation["origin"][0] = 16 - rotation["origin"][0]
        # About x the angle is unchanged; about y or z it turns the other way
        if rotation["axis"] != "x":
            rotation["angle"] = -rotation["angle"]
    return element


def open_offsets(hinge):
    """How far to move the leaf, as written before the gate's own z offset, so that the blockstate's turn keeps the hinge
    where it is: by 90 degrees, (x, z) to (16 - z, x) about (8, 8), and by 270 degrees, (x, z) to (z, 16 - x)."""
    x, z = hinge
    return {90: (z - x, Z_OFFSET + 16 - x - z), 270: (16 - x - z, Z_OFFSET + x - z)}


def write(relative, value):
    target = ASSETS / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


for name in ("platform_edge_door_low", "platform_edge_door", "platform_edge_door_low_no_handle", "platform_edge_door_no_handle"):
    source = json.loads((ROOT / "models" / (name + ".bbmodel")).read_text(encoding="utf-8"))
    texture = source["textures"][0]
    (ASSETS / "textures/block" / (name + ".png")).write_bytes(base64.b64decode(texture["source"].split(",", 1)[1]))
    scale_u, scale_v = 16 / texture["uv_width"], 16 / texture["uv_height"]
    base = {"ambientocclusion": False, "textures": {"0": "mtr:block/" + name, "particle": "mtr:block/" + name}}
    complete, halves = [], [[], []]
    for cube in source["elements"]:
        faces = {}
        for side, face in cube["faces"].items():
            if face.get("texture") is None:
                continue
            faces[side] = {"uv": [value * (scale_u if i % 2 == 0 else scale_v) for i, value in enumerate(face["uv"])], "texture": "#0"}
            if face.get("rotation"):
                faces[side]["rotation"] = face["rotation"]
        element = mirrored({"from": list(cube["from"]), "to": list(cube["to"]), "faces": faces})
        angles = cube.get("rotation", [0, 0, 0])
        active = [i for i, angle in enumerate(angles) if angle]
        if active:
            assert len(active) == 1 and angles[active[0]] in (-45, -22.5, 22.5, 45)
            axis = "xyz"[active[0]]
            # Mirrored with the cube: about x or y the angle turns the other way, the origin's z moves with it
            origin = list(cube["origin"])
            origin[2] = 2 * LEAF_PLANE - origin[2]
            angle = angles[active[0]] if axis == "z" else -angles[active[0]]
            element["rotation"] = {"origin": origin, "axis": axis, "angle": angle, "rescale": cube.get("rescale", False)}
        complete.append(element)
        if active:
            # Keep the angled supports intact; they extend less than a pixel across the split.
            half = 0 if (cube["from"][1] + cube["to"][1]) / 2 < 16 else 1
            part = copy.deepcopy(element)
            for key in ("from", "to"):
                part[key][1] -= 16 * half
            part["rotation"]["origin"][1] -= 16 * half
            halves[half].append(part)
            continue
        low, high = cube["from"][1], cube["to"][1]
        for half in (0, 1):
            bottom, top = max(low, 16 * half), min(high, 16 * (half + 1))
            if top <= bottom:
                continue
            part = copy.deepcopy(element)
            part["from"][1], part["to"][1] = bottom - 16 * half, top - 16 * half
            if top < high:
                part["faces"].pop("up", None)
            if bottom > low:
                part["faces"].pop("down", None)
            for side in ("north", "east", "south", "west"):
                if side in part["faces"]:
                    uv = part["faces"][side]["uv"]
                    v1, v2 = uv[1], uv[3]
                    uv[1] = v1 + (high - top) / (high - low) * (v2 - v1)
                    uv[3] = v1 + (high - bottom) / (high - low) * (v2 - v1)
            halves[half].append(part)
    def moved(parts, dx, dz):
        result = copy.deepcopy(parts)
        for part in result:
            for key in ("from", "to"):
                part[key][0] += dx
                part[key][2] += dz
            if "rotation" in part:
                part["rotation"]["origin"][0] += dx
                part["rotation"]["origin"][2] += dz
        return result

    for half, elements in zip(("lower", "upper"), halves):
        # The gate as drawn, and the same gate turned left to right by the brush (0.77.0), whose hinge is at the other end
        for suffix, parts, hinge, out, back in (("", elements, HINGE, 90, 270),
                                                 ("_mirrored", [mirrored_across(copy.deepcopy(part)) for part in elements], MIRRORED_HINGE, 270, 90)):
            prefix = "models/block/" + name + "_" + half + suffix
            offsets = open_offsets(hinge)
            write(prefix + ".json", {**base, "elements": moved(parts, 0, Z_OFFSET)})
            # Swinging out in front of the block: the leaf runs from the hinge towards the other end, so the turn that
            # takes it out in front is 90 degrees for the hinge at x 15 and 270 for the hinge at x 1
            write(prefix + "_open.json", {**base, "elements": moved(parts, *offsets[out])})
            # Swinging the other way, back into the block (0.65.29)
            write(prefix + "_open_in.json", {**base, "elements": moved(parts, *offsets[back])})
    write("models/block/" + name + ".json", {**base, "elements": moved(complete, 0, Z_OFFSET), "display": source.get("display", {})})

# The gates without the handle outside (user, 2026-11-14): the second screen door style, which until then was the same
# as the first, and a gate of its own for the half height one
for block, name in (("platform_end_apg", "platform_edge_door_low"), ("platform_end_psd", "platform_edge_door"),
                    ("platform_end_psd_2", "platform_edge_door_no_handle"), ("platform_end_apg_2", "platform_edge_door_low_no_handle")):
    variants = {}
    for facing, angle in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        for half in ("lower", "upper"):
            for opened in (False, True):
                for inward in (False, True):
                    for across in (False, True):
                        model = "mtr:block/" + name + "_" + half + ("_mirrored" if across else "")
                        if opened:
                            model += "_open_in" if inward else "_open"
                        variants[f"facing={facing},half={half},inward={str(inward).lower()},mirrored={str(across).lower()},open={str(opened).lower()}"] = {
                            "model": model,
                            "y": (angle + OPEN_TURN[across, inward] if opened else angle) % 360,
                        }
    write("blockstates/" + block + ".json", {"variants": variants})
    write("models/item/" + block + ".json", {"parent": "mtr:block/" + name})
