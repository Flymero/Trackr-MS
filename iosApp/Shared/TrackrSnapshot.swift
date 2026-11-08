import Foundation
import ActivityKit

struct SnapshotItem: Codable, Hashable, Identifiable {
    let id: String
    let title: String
    let subtitle: String
    let arrival: String
    let url: String
    let updatedAt: TimeInterval
    let paused: Bool

    func isStale(at date: Date) -> Bool { updatedAt <= 0 || date.timeIntervalSince1970 - updatedAt >= 90 || date.timeIntervalSince1970 < updatedAt }
    func display(at date: Date) -> String {
        if paused { return "已暂停 · 打开刷新" }
        if isStale(at: date) { return "信息已过期 · 打开刷新" }
        return arrival
    }
}

struct TrackrSnapshot: Codable {
    let version: Int
    let favorites: [SnapshotItem]
    let sessions: [SnapshotItem]
    static let empty = TrackrSnapshot(version: 1, favorites: [], sessions: [])
    static let filename = "snapshot.json"
    static func timelineDates(items: [SnapshotItem], now: Date) -> [Date] {
        [now] + Set(items.map { Date(timeIntervalSince1970: $0.updatedAt + 90) }.filter { $0 > now }).sorted()
    }

    static var directory: URL? {
        guard let group = Bundle.main.object(forInfoDictionaryKey: "TrackrAppGroup") as? String,
              !group.isEmpty else { return nil }
        return FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)
    }
    static func read() -> TrackrSnapshot {
        guard let directory, let data = try? Data(contentsOf: directory.appendingPathComponent(filename)),
              let snapshot = try? JSONDecoder().decode(Self.self, from: data), snapshot.version == 1 else { return .empty }
        return snapshot
    }
}

struct TrackrActivityAttributes: ActivityAttributes {
    let sessionID: String
    struct ContentState: Codable, Hashable {
        let item: SnapshotItem
    }
}
