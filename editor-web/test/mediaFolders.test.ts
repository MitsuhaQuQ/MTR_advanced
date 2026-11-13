import { describe, expect, it } from "vitest"
import { buildTree, canDrop, filesIn, findNode, nameProblem, uploadTargets, within } from "../src/core/mediaFolders"

// Folders of uploaded audio and pictures (0.74.0): the tree the media library shows, and its rules
const files = [
  { key: "steve:a.ogg", uploader: "u1", uploaderName: "Steve", folder: "1号线/上行" },
  { key: "steve:b.ogg", uploader: "u1", uploaderName: "Steve", folder: "" },
  { key: "alex:c.ogg", uploader: "u2", uploaderName: "Alex", folder: "站名" },
]
const folders = [{ owner: "u1", ownerName: "Steve", path: "1号线/下行" }, { owner: "u3", ownerName: "Zed", path: "空的" }]

describe("media folders", () => {
  it("builds a tree of uploaders and their folders, empty ones too, the player's own first", () => {
    const tree = buildTree(files, folders, "u2")
    expect(tree.map(root => root.name)).toEqual(["Alex", "Steve", "Zed"])
    const steve = tree[1]
    expect(steve.total).toBe(2)
    expect(steve.files).toBe(1)
    expect(steve.children.map(child => child.name)).toEqual(["1号线"])
    expect(steve.children[0].children.map(child => child.path)).toEqual(["1号线/上行", "1号线/下行"])
    expect(steve.children[0].total).toBe(1)
    expect(findNode(tree, { owner: "u3", path: "空的" })?.total).toBe(0)
    expect(findNode(tree, { owner: "u1", path: "没有" })).toBeNull()
  })
  it("lists a folder's own files, or everything below it while searching", () => {
    expect(filesIn(files, null).length).toBe(3)
    expect(filesIn(files, { owner: "u1", path: "" }).map(f => f.key)).toEqual(["steve:b.ogg"])
    expect(filesIn(files, { owner: "u1", path: "1号线" })).toEqual([])
    expect(filesIn(files, { owner: "u1", path: "1号线" }, true).map(f => f.key)).toEqual(["steve:a.ogg"])
    expect(within("1号线/上行", "1号线")).toBe(true)
    expect(within("1号线x", "1号线")).toBe(false)
  })
  it("lets files be dropped only into their own uploader's folders, by whoever may organise them", () => {
    const mine = (owner: string) => owner === "u1"
    expect(canDrop([files[0], files[1]], { owner: "u1", path: "1号线/下行" }, mine)).toBe(true)
    // Already there, someone else's file, someone else's folder
    expect(canDrop([files[1]], { owner: "u1", path: "" }, mine)).toBe(false)
    expect(canDrop([files[0], files[2]], { owner: "u1", path: "1号线" }, mine)).toBe(false)
    expect(canDrop([files[2]], { owner: "u2", path: "" }, mine)).toBe(false)
    expect(canDrop([files[2]], { owner: "u2", path: "" }, () => true)).toBe(true)
  })
  it("checks folder names as the game does", () => {
    expect(nameProblem("上行")).toBe("")
    expect(nameProblem("  ")).toBe("empty")
    expect(nameProblem("x".repeat(33))).toBe("long")
    expect(nameProblem("a/b")).toBe("character")
    expect(nameProblem("..")).toBe("dots")
    expect(nameProblem("新", [], 8)).toBe("deep")
    expect(nameProblem("上行", ["上行"])).toBe("taken")
    expect(nameProblem("Up", ["up"])).toBe("taken")
  })
  it("offers the player's own folders to upload into", () => {
    expect(uploadTargets(buildTree(files, folders, "u1"), "u1")).toEqual(["", "1号线", "1号线/上行", "1号线/下行"])
    expect(uploadTargets(buildTree(files, folders, "u9"), "u9")).toEqual([""])
  })
})
