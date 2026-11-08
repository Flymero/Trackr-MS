import XCTest

final class SnapshotTests: XCTestCase {
    private func item(paused: Bool = false, updatedAt: Double = 1_000) -> SnapshotItem {
        SnapshotItem(id: "s1", title: "71路", subtitle: "站台", arrival: "2 分钟", url: "trackr://line/sh/71",
                     updatedAt: updatedAt, paused: paused)
    }
    func testStalenessBoundaryAndClockReversal() {
        XCTAssertEqual(item().display(at: Date(timeIntervalSince1970: 1_089)), "2 分钟")
        XCTAssertTrue(item().isStale(at: Date(timeIntervalSince1970: 1_090)))
        XCTAssertTrue(item().isStale(at: Date(timeIntervalSince1970: 999)))
        XCTAssertTrue(item(updatedAt: 0).isStale(at: .now))
    }
    func testPausedNeverClaimsLiveArrival() {
        XCTAssertEqual(item(paused: true).display(at: Date(timeIntervalSince1970: 1_001)), "已暂停 · 打开刷新")
    }
    func testSnapshotRoundTripAndAtomicReplacement() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent(TrackrSnapshot.filename)
        let snapshot = TrackrSnapshot(version: 1, favorites: [item()], sessions: [item(paused: true)])
        try JSONEncoder().encode(snapshot).write(to: file, options: .atomic)
        XCTAssertEqual(try JSONDecoder().decode(TrackrSnapshot.self, from: Data(contentsOf: file)).favorites.count, 1)
        try JSONEncoder().encode(TrackrSnapshot.empty).write(to: file, options: .atomic)
        XCTAssertTrue(try JSONDecoder().decode(TrackrSnapshot.self, from: Data(contentsOf: file)).favorites.isEmpty)
    }
    func testTimelineExpiresAtDataDeadlineRatherThanReloadDeadline() {
        let dates = TrackrSnapshot.timelineDates(items: [item(), item(updatedAt: 1_020)], now: Date(timeIntervalSince1970: 1_080))
        XCTAssertEqual(dates.map(\.timeIntervalSince1970), [1_080, 1_090, 1_110])
    }
    func testCorruptSnapshotCannotDecode() {
        XCTAssertThrowsError(try JSONDecoder().decode(TrackrSnapshot.self, from: Data("{broken".utf8)))
    }
}
