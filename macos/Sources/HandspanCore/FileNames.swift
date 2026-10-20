import Foundation

/// Receiver-side file name rules from PROTOCOL §8.
public enum FileNames {
    public static func sanitize(_ name: String) -> String {
        var s = String(String.UnicodeScalarView(name.unicodeScalars.filter { $0.value >= 0x20 && $0.value != 0x7F }))
        s = s.replacingOccurrences(of: "\\", with: "/")
        if let last = s.split(separator: "/", omittingEmptySubsequences: false).last { s = String(last) }
        s = s.replacingOccurrences(of: ":", with: "_")
        s = s.trimmingCharacters(in: .whitespaces)
        while s.hasPrefix(".") { s.removeFirst() }
        s = truncate(s, maxBytes: 200)
        return s.isEmpty ? "file" : s
    }

    /// Returns `name` in `directory`, or `name (1).ext`, `name (2).ext`… if it is taken.
    public static func unique(_ name: String, in directory: URL, fileManager: FileManager = .default) -> URL {
        let base = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        var candidate = directory.appendingPathComponent(name)
        var n = 1
        while fileManager.fileExists(atPath: candidate.path) {
            candidate = directory.appendingPathComponent(ext.isEmpty ? "\(base) (\(n))" : "\(base) (\(n)).\(ext)")
            n += 1
        }
        return candidate
    }

    private static func truncate(_ s: String, maxBytes: Int) -> String {
        guard s.utf8.count > maxBytes else { return s }
        let ext = (s as NSString).pathExtension
        let suffix = ext.isEmpty || ext.utf8.count > 16 ? "" : "." + ext
        var base = suffix.isEmpty ? s : String(s.dropLast(suffix.count))
        while base.utf8.count + suffix.utf8.count > maxBytes { base.removeLast() }
        return base + suffix
    }
}
