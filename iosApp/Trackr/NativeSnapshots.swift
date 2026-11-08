import UIKit
import ActivityKit
import WidgetKit
import ComposeApp

/// Only a small, versioned snapshot crosses the extension boundary. No database access.
final class NativeSnapshots {
    static let shared = NativeSnapshots()
    private var previous: Task<Void, Never>?
    private var lastData: Data?

    func install() {
        IosNativeBridgeKt.installIosSnapshots(publish: { [weak self] payload in
            self?.receive(payload)
        }, activitiesAllowed: { KotlinBoolean(bool: ActivityAuthorizationInfo().areActivitiesEnabled) })
    }

    private func receive(_ payload: String) {
        guard let data = payload.data(using: .utf8), data != lastData else { return }
        let snapshot: TrackrSnapshot
        do { snapshot = try JSONDecoder().decode(TrackrSnapshot.self, from: data) }
        catch { NSLog("Trackr: invalid widget snapshot: %@", String(describing: error)); return }
        guard snapshot.version == 1 else { return }
        if let directory = TrackrSnapshot.directory {
            do {
                try data.write(to: directory.appendingPathComponent(TrackrSnapshot.filename), options: .atomic)
                var file = directory.appendingPathComponent(TrackrSnapshot.filename)
                var values = URLResourceValues()
                values.isExcludedFromBackup = true
                try file.setResourceValues(values)
                WidgetCenter.shared.reloadTimelines(ofKind: "TrackrFavorites")
            } catch {
                // Missing App Group entitlement is visible in the widget's empty state; never open the database here.
                NSLog("Trackr: unable to write widget snapshot")
            }
        }
        lastData = data
        let previous = self.previous
        let background = UIApplication.shared.beginBackgroundTask(withName: "Publish tracking snapshot")
        self.previous = Task { @MainActor in
            await previous?.value
            await reconcile(snapshot.sessions)
            if background != .invalid { UIApplication.shared.endBackgroundTask(background) }
        }
    }

    @MainActor private func reconcile(_ items: [SnapshotItem]) async {
        let ids = Set(items.map(\.id))
        for activity in Activity<TrackrActivityAttributes>.activities where !ids.contains(activity.attributes.sessionID) {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
        for item in items {
            let state = TrackrActivityAttributes.ContentState(item: item)
            let content = ActivityContent(state: state, staleDate: Date(timeIntervalSince1970: item.updatedAt + 90))
            if let activity = Activity<TrackrActivityAttributes>.activities.first(where: { $0.attributes.sessionID == item.id }) {
                await activity.update(content)
            } else if !item.paused && UIApplication.shared.applicationState == .active && ActivityAuthorizationInfo().areActivitiesEnabled {
                _ = try? Activity.request(attributes: TrackrActivityAttributes(sessionID: item.id), content: content, pushType: nil)
            }
        }
    }
}
