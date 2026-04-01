#!/usr/bin/env swift
// ──────────────────────────────────────────────────────────────
// bootstrap-adocprojects.swift
// IKE Community — Adoc Studio sidecar generator
//
// Generates .adocproject files for each assembly module in
// ike-lab-documents, placing them in a sidecar directory outside
// the Maven/Syncthing tree. Each project's anchorFolder gets a
// macOS NSURL bookmark pointing back into the Maven source tree,
// so Adoc Studio edits land on the canonical sources.
//
// Usage:
//   swift bootstrap-adocprojects.swift <source-dir> <output-dir>
//
// Example:
//   swift bootstrap-adocprojects.swift \
//       ~/ike-dev/ike-lab-documents \
//       ~/Documents/ike-adoc-studio
// ──────────────────────────────────────────────────────────────

import Foundation

// ── CLI argument parsing ─────────────────────────────────────

guard CommandLine.arguments.count >= 3 else {
    fputs("""
          Usage: bootstrap-adocprojects <source-dir> <output-dir>
            source-dir : root of ike-lab-documents (contains assembly modules)
            output-dir : sidecar directory for .adocproject files
          
          """, stderr)
    exit(1)
}

let sourceDir = URL(fileURLWithPath: CommandLine.arguments[1])
                    .standardized
let outputDir = URL(fileURLWithPath: CommandLine.arguments[2])
                    .standardized

let fm = FileManager.default

// ── Assembly discovery ───────────────────────────────────────

/// An assembly module is a directory containing both pom.xml and
/// src/docs/asciidoc/ with at least one .adoc file.
struct Assembly {
    let name: String
    let moduleDir: URL
    let adocFiles: [String]   // relative to moduleDir
}

func discoverAssemblies() throws -> [Assembly] {
    let contents = try fm.contentsOfDirectory(
        at: sourceDir,
        includingPropertiesForKeys: [.isDirectoryKey],
        options: [.skipsHiddenFiles])

    var assemblies: [Assembly] = []

    for item in contents {
        guard (try? item.resourceValues(forKeys: [.isDirectoryKey])
                  .isDirectory) == true else { continue }

        let pom = item.appendingPathComponent("pom.xml")
        let adocDir = item.appendingPathComponent("src/docs/asciidoc")

        guard fm.fileExists(atPath: pom.path),
              fm.isDirectory(atPath: adocDir.path) else { continue }

        // Collect .adoc files recursively under src/docs/asciidoc
        var adocFiles: [String] = []
        if let enumerator = fm.enumerator(
            at: adocDir,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]) {
            for case let file as URL in enumerator {
                if file.pathExtension == "adoc" {
                    // Path relative to module root
                    let relative = file.path.replacingOccurrences(
                        of: item.path + "/", with: "")
                    adocFiles.append(relative)
                }
            }
        }

        guard !adocFiles.isEmpty else { continue }

        assemblies.append(Assembly(
            name: item.lastPathComponent,
            moduleDir: item,
            adocFiles: adocFiles.sorted()))
    }

    return assemblies.sorted { $0.name < $1.name }
}

// ── NSURL bookmark generation ────────────────────────────────

func createBookmark(for url: URL) throws -> Data {
    try url.bookmarkData(
        options: [],
        includingResourceValuesForKeys: nil,
        relativeTo: nil)
}

// ── .asciidoctorconfig parsing ───────────────────────────────

/// Reads key-value attribute pairs from .asciidoctorconfig.
/// Lines like `:key: value` become dictionary entries.
func parseAsciidoctorConfig(at moduleDir: URL) -> [String: String] {
    let configFile = moduleDir.appendingPathComponent(".asciidoctorconfig")
    guard let content = try? String(contentsOf: configFile, encoding: .utf8)
    else { return [:] }

    var attrs: [String: String] = [:]
    for line in content.components(separatedBy: .newlines) {
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        guard trimmed.hasPrefix(":"),
              let endColon = trimmed.dropFirst()
                  .firstIndex(of: ":") else { continue }
        let key = String(trimmed[trimmed.index(after: trimmed.startIndex)..<endColon])
        let value = String(trimmed[trimmed.index(after: endColon)...])
                        .trimmingCharacters(in: .whitespaces)
        if !key.isEmpty {
            attrs[key] = value
        }
    }
    return attrs
}

// ── JSON generation ──────────────────────────────────────────

func generateAdocProject(assembly: Assembly) throws -> Data {
    let bookmark = try createBookmark(for: assembly.moduleDir)
    let bookmarkBase64 = bookmark.base64EncodedString()

    let rootId = UUID().uuidString
    let anchorId = UUID().uuidString
    let mediaId = UUID().uuidString
    let windowConfigUserId = UUID().uuidString

    // Build file entries for each .adoc file
    var fileChildren: [[String: Any]] = []

    // Media folder
    fileChildren.append([
        "createIndexFile": false,
        "folderSubType": 1,
        "id": mediaId,
        "indexFileName": "index.adoc",
        "isHiddenInComposite": false,
        "name": "Media",
        "sortDirection": 0,
        "sortOrder": 1,
        "type": "folder"
    ])

    // AsciiDoc files
    for (index, adocFile) in assembly.adocFiles.enumerated() {
        let fileName = URL(fileURLWithPath: adocFile).lastPathComponent
        fileChildren.append([
            "id": UUID().uuidString,
            "isHiddenInComposite": false,
            "name": fileName,
            "type": "asciiDocFile"
        ])
    }

    // Read .asciidoctorconfig attributes
    let attrs = parseAsciidoctorConfig(at: assembly.moduleDir)

    let project: [String: Any] = [
        "exportProductsMode": "selected",
        "oneTimeExportOptions": [
            "appearance": "automatic",
            "attributes": attrs,
            "resourcesMode": "separateResources",
            "styleID": "app.adoc-studio.style.classic"
        ] as [String: Any],
        "oneTimeExportOptionsFormat": "html",
        "oneTimeExportUsesPreview": true,
        "products": [] as [Any],
        "root": [
            "children": [
                [
                    "children": fileChildren,
                    "createIndexFile": false,
                    "folderSubType": 0,
                    "id": anchorId,
                    "indexFileName": "index.adoc",
                    "isHiddenInComposite": false,
                    "location": [
                        bookmarkBase64
                    ],
                    "name": assembly.name,
                    "pathFromProjectFolder": "",
                    "sortDirection": 0,
                    "sortOrder": 0,
                    "type": "anchorFolder"
                ] as [String: Any]
            ],
            "createIndexFile": false,
            "folderSubType": 0,
            "id": rootId,
            "indexFileName": "index.adoc",
            "isHiddenInComposite": false,
            "name": assembly.name,
            "sortDirection": 0,
            "sortOrder": 0,
            "type": "root"
        ] as [String: Any],
        "windowConfigurationsByUser": [
            windowConfigUserId,
            [
                [
                    "collapsedWarningCategories": [] as [Any],
                    "couplesPreviewContent": true,
                    "editorNode": anchorId,
                    "expandedNodes": [rootId],
                    "focusedArea": "sidebar",
                    "focusedDocumentContentArea": "editor",
                    "geometries": [
                        [
                            "platform": "mac",
                            "sizes": [
                                ["height": 900, "width": 1400]
                            ]
                        ] as [String: Any],
                        [
                            "editorSplitterPosition": 0.5,
                            "frame": [[300, 200], [1400, 900]],
                            "isSidebarCollapsed": false,
                            "previewModeDisplayState": "sourceAndPreview",
                            "sidebarWidth": 200
                        ] as [String: Any]
                    ],
                    "navigationHistory": [
                        "nodes": [anchorId]
                    ] as [String: Any],
                    "nodeConfigurations": [] as [Any],
                    "partsCounterPart": "charactersWithoutSpacesAndMarkup",
                    "partsCounterTarget": "fullDocument",
                    "previewConfiguration": [
                        "format": "html",
                        "htmlOptions": [
                            "appearance": "automatic",
                            "attributes": attrs,
                            "resourcesMode": "separateResources",
                            "styleID": "app.adoc-studio.style.classic"
                        ] as [String: Any],
                        "htmlTextZoom": 1,
                        "pdfDisplayMode": "singlePageContinuous",
                        "pdfOptions": [
                            "appearance": "automatic",
                            "attributes": [:] as [String: String],
                            "margins": [
                                "bottom": 2, "left": 2,
                                "right": 2, "top": 2
                            ] as [String: Any],
                            "paperSize": [21, 29.7],
                            "styleID": "app.adoc-studio.style.classic"
                        ] as [String: Any],
                        "pdfZoom": ["fit": [:] as [String: Any]] as [String: Any],
                        "products": [] as [Any]
                    ] as [String: Any],
                    "previewNode": anchorId,
                    "selectedNodes": [anchorId],
                    "showsPartsCounter": false,
                    "syncsExpansionState": true,
                    "syncsScrolling": true
                ] as [String: Any]
            ]
        ] as [Any]
    ]

    return try JSONSerialization.data(
        withJSONObject: project,
        options: [.prettyPrinted, .sortedKeys])
}

// ── FileManager convenience ──────────────────────────────────

extension FileManager {
    func isDirectory(atPath path: String) -> Bool {
        var isDir: ObjCBool = false
        return fileExists(atPath: path, isDirectory: &isDir) && isDir.boolValue
    }
}

// ── Main ─────────────────────────────────────────────────────

do {
    let assemblies = try discoverAssemblies()

    if assemblies.isEmpty {
        fputs("No assembly modules found in \(sourceDir.path)\n", stderr)
        exit(1)
    }

    try fm.createDirectory(at: outputDir,
                           withIntermediateDirectories: true)

    var generated = 0
    for assembly in assemblies {
        let projectDir = outputDir.appendingPathComponent(assembly.name)
        try fm.createDirectory(at: projectDir,
                               withIntermediateDirectories: true)

        let projectFile = projectDir.appendingPathComponent(
            "\(assembly.name).adocproject")

        let json = try generateAdocProject(assembly: assembly)
        try json.write(to: projectFile)

        print("  \(assembly.name): \(assembly.adocFiles.count) doc(s)")
        generated += 1
    }

    print("")
    print("Generated \(generated) .adocproject file(s) in \(outputDir.path)")

} catch {
    fputs("Error: \(error.localizedDescription)\n", stderr)
    exit(1)
}
