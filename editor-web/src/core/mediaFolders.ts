/**
 * Folders of uploaded audio and pictures (0.74.0, user 2026-11-13), as the game keeps them (mtr.media.MediaFolders): under
 * each uploader, folders inside folders, at most 8 deep, kept when empty. A folder is its path from the uploader's top,
 * names joined by "/" ("" is the top). A folder holds only its uploader's files, so files are only dragged between the
 * folders of their own uploader. Folders only sort files: their names (what speakers, announcers and paintings use) stay.
 */

export const MAX_DEPTH = 8
export const MAX_NAME_LENGTH = 32

/** A folder of an uploader's (path "" is the uploader's top) */
export type FolderRef = { owner: string; path: string }
/** A kept folder, as the game lists them */
export type KeptFolder = { owner: string; ownerName: string; path: string }
/** What the tree needs of a file */
export type FolderFile = { uploader?: string; uploaderName: string; folder?: string }
export type FolderNode = { owner: string; ownerName: string; path: string; name: string; children: FolderNode[]; files: number; total: number }

export const parentOf = (path: string) => (path.includes("/") ? path.slice(0, path.lastIndexOf("/")) : "")
export const nameOf = (path: string) => path.slice(path.lastIndexOf("/") + 1)
export const childOf = (parent: string, name: string) => (parent ? parent + "/" + name : name)
export const depthOf = (path: string) => (path ? path.split("/").length : 0)
/** Whether a path is a folder or inside it; names match without case, as the game's do */
export const within = (path: string, folder: string) => {
  const p = path.toLowerCase(), f = folder.toLowerCase()
  return !f || p === f || p.startsWith(f + "/")
}
/** As the game orders names: by character code, case aside (String.CASE_INSENSITIVE_ORDER), so both show the same order */
export const compareNames = (a: string, b: string) => { const x = a.toLowerCase(), y = b.toLowerCase(); return x < y ? -1 : x > y ? 1 : 0 }
export const sameFolder = (a: FolderRef | null, b: FolderRef | null) => Boolean(a && b && a.owner === b.owner && a.path.toLowerCase() === b.path.toLowerCase())

/**
 * Why a folder name can't be used, or "" if it can: as the game checks (MediaFolders.checkName), and not the name of
 * another folder in the same place.
 */
export function nameProblem(name: string, siblings: string[] = [], parentDepth = 0): "" | "empty" | "long" | "character" | "dots" | "deep" | "taken" {
  const trimmed = name.trim()
  if (!trimmed) return "empty"
  if (trimmed.length > MAX_NAME_LENGTH) return "long"
  // eslint-disable-next-line no-control-regex
  if (/[/\\\u0000-\u001f\u007f]/.test(trimmed)) return "character"
  if (/^\.+$/.test(trimmed)) return "dots"
  if (parentDepth + 1 > MAX_DEPTH) return "deep"
  if (siblings.some(sibling => sibling.toLowerCase() === trimmed.toLowerCase())) return "taken"
  return ""
}

/**
 * The tree: one node for each uploader (their top, path ""), with the kept folders and the folders their files are in,
 * each with its parents. Counts are the files directly in a folder and everything below. An uploader's name is the one
 * their files or folders carry; the player's own folders come first, then by name.
 */
export function buildTree(files: FolderFile[], folders: KeptFolder[], self = ""): FolderNode[] {
  const owners = new Map<string, FolderNode>()
  const ownerNode = (owner: string, ownerName: string) => {
    const key = owner || "name:" + ownerName
    let node = owners.get(key)
    if (!node) owners.set(key, node = { owner, ownerName: ownerName || owner || "?", path: "", name: ownerName || owner || "?", children: [], files: 0, total: 0 })
    return node
  }
  const nodeAt = (root: FolderNode, path: string) => {
    let node = root
    for (const name of path.split("/").filter(Boolean)) {
      let child = node.children.find(each => each.name.toLowerCase() === name.toLowerCase())
      if (!child) node.children.push(child = { owner: root.owner, ownerName: root.ownerName, path: childOf(node.path, name), name, children: [], files: 0, total: 0 })
      node = child
    }
    return node
  }
  folders.forEach(folder => nodeAt(ownerNode(folder.owner, folder.ownerName), folder.path))
  files.forEach(file => { nodeAt(ownerNode(file.uploader || "", file.uploaderName), file.folder || "").files++ })
  const finish = (node: FolderNode): number => {
    node.children.sort((a, b) => compareNames(a.name, b.name))
    node.total = node.files + node.children.reduce((sum, child) => sum + finish(child), 0)
    return node.total
  }
  const roots = [...owners.values()]
  roots.forEach(finish)
  return roots.sort((a, b) => (a.owner === self) !== (b.owner === self) ? (a.owner === self ? -1 : 1) : compareNames(a.name, b.name))
}

/** The node of a folder, or null if it isn't in the tree */
export function findNode(roots: FolderNode[], ref: FolderRef | null): FolderNode | null {
  if (!ref) return null
  const search = (node: FolderNode): FolderNode | null => (node.path.toLowerCase() === ref.path.toLowerCase() ? node : node.children.map(search).find(Boolean) || null)
  const root = roots.find(each => each.owner === ref.owner)
  return root ? search(root) : null
}

/** The files of a folder: directly in it, or (searching) anywhere below it; null shows every file */
export function filesIn<T extends FolderFile>(files: T[], ref: FolderRef | null, below = false): T[] {
  if (!ref) return files
  return files.filter(file => (file.uploader || "") === ref.owner && (below ? within(file.folder || "", ref.path) : (file.folder || "").toLowerCase() === ref.path.toLowerCase()))
}

/**
 * Whether files can be dropped into a folder: all of them the folder's uploader's, at least one not there already, and
 * the player allowed to organise that uploader's files.
 */
export function canDrop(files: FolderFile[], target: FolderRef, canOrganise: (owner: string) => boolean): boolean {
  return files.length > 0 && canOrganise(target.owner) && files.every(file => (file.uploader || "") === target.owner) && files.some(file => (file.folder || "").toLowerCase() !== target.path.toLowerCase())
}

/** The folders an upload can go to: the player's own, top first, in path order */
export function uploadTargets(roots: FolderNode[], self: string): string[] {
  const root = roots.find(each => each.owner === self)
  const paths: string[] = [""]
  const walk = (node: FolderNode) => node.children.forEach(child => { paths.push(child.path); walk(child) })
  if (root) walk(root)
  return paths
}

/** The drag data of files being moved between folders */
export const DRAG_TYPE = "application/x-mtr-media-files"
