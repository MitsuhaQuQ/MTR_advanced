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
ASSETS = ROOT / "resources/common/normal/assets/mtr"


def write(relative, value):
    target = ASSETS / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


for name in ("platform_edge_door_low", "platform_edge_door"):
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
        element = {"from": cube["from"], "to": cube["to"], "faces": faces}
        angles = cube.get("rotation", [0, 0, 0])
        active = [i for i, angle in enumerate(angles) if angle]
        if active:
            assert len(active) == 1 and angles[active[0]] in (-45, -22.5, 22.5, 45)
            element["rotation"] = {"origin": cube["origin"], "axis": "xyz"[active[0]], "angle": angles[active[0]], "rescale": cube.get("rescale", False)}
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
        write("models/block/" + name + "_" + half + ".json", {**base, "elements": moved(elements, 0, Z_OFFSET)})
        # Blockstate rotation (90 degrees) takes (x, z) to (16 - z, x) about (8, 8). Offset the geometry so the open leaf
        # turns about the hinge instead: (16 - (z + dz), x + dx) is the hinge itself for dz = 16 - hinge x - hinge z, with
        # the closed leaf's own offset, and dx = hinge z - hinge x.
        write("models/block/" + name + "_" + half + "_open.json", {**base, "elements": moved(elements, HINGE[1] - HINGE[0], Z_OFFSET + 16 - HINGE[0] - HINGE[1])})
        # Swinging the other way, back into the block (0.65.29): turned 270 degrees, (x, z) to (z, 16 - x), which keeps
        # the hinge for dz = hinge x - hinge z, with the closed leaf's own offset, and dx = 16 - hinge x - hinge z.
        write("models/block/" + name + "_" + half + "_open_in.json", {**base, "elements": moved(elements, 16 - HINGE[0] - HINGE[1], Z_OFFSET + HINGE[0] - HINGE[1])})
    write("models/block/" + name + ".json", {**base, "elements": moved(complete, 0, Z_OFFSET), "display": source.get("display", {})})

for block, name in (("platform_end_apg", "platform_edge_door_low"), ("platform_end_psd", "platform_edge_door"), ("platform_end_psd_2", "platform_edge_door")):
    variants = {}
    for facing, angle in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        for half in ("lower", "upper"):
            for opened in (False, True):
                for inward in (False, True):
                    model = "mtr:block/" + name + "_" + half
                    if opened:
                        model += "_open_in" if inward else "_open"
                    variants[f"facing={facing},half={half},inward={str(inward).lower()},open={str(opened).lower()}"] = {
                        "model": model,
                        "y": (angle + (270 if inward else 90) if opened else angle) % 360,
                    }
    write("blockstates/" + block + ".json", {"variants": variants})
    write("models/item/" + block + ".json", {"parent": "mtr:block/" + name})
