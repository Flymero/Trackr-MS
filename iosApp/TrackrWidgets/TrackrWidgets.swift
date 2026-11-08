import SwiftUI
import WidgetKit
import ActivityKit

struct FavoritesEntry: TimelineEntry {
    let date: Date
    let items: [SnapshotItem]
}

struct FavoritesProvider: TimelineProvider {
    func placeholder(in context: Context) -> FavoritesEntry { FavoritesEntry(date: .now, items: []) }
    func getSnapshot(in context: Context, completion: @escaping (FavoritesEntry) -> Void) {
        completion(FavoritesEntry(date: .now, items: TrackrSnapshot.read().favorites))
    }
    func getTimeline(in context: Context, completion: @escaping (Timeline<FavoritesEntry>) -> Void) {
        let now = Date()
        let items = TrackrSnapshot.read().favorites
        // Include a stale entry even when the OS defers the next reload.
        let entries = TrackrSnapshot.timelineDates(items: items, now: now).map { FavoritesEntry(date: $0, items: items) }
        completion(Timeline(entries: entries, policy: .after(now.addingTimeInterval(900))))
    }
}

struct FavoritesView: View {
    let entry: FavoritesEntry
    @Environment(\.widgetFamily) private var family
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("追客 · 收藏").font(.headline)
            if entry.items.isEmpty {
                Text("打开追客收藏公交或路线").font(.caption).foregroundStyle(.secondary)
            }
            ForEach(entry.items.prefix(family == .systemSmall ? 1 : 3)) { item in
                if let url = URL(string: item.url) {
                    Link(destination: url) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("\(item.title) · \(item.subtitle)").font(.subheadline).lineLimit(1)
                            Text(item.display(at: entry.date)).font(.caption).foregroundStyle(.secondary)
                            if item.updatedAt > 0 { Text(Date(timeIntervalSince1970: item.updatedAt), style: .time).font(.caption2) }
                        }.frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
        }.containerBackground(.background, for: .widget)
            .widgetURL(entry.items.first.flatMap { URL(string: $0.url) })
    }
}

struct TrackrFavoritesWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "TrackrFavorites", provider: FavoritesProvider()) { FavoritesView(entry: $0) }
            .configurationDisplayName("收藏公交与路线")
            .description("最近一次到站快照；打开追客刷新。")
            .supportedFamilies([.systemSmall, .systemMedium])
    }
}

struct TrackingLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: TrackrActivityAttributes.self) { context in
            VStack(alignment: .leading, spacing: 6) {
                Text(context.state.item.title).font(.headline)
                Text(context.state.item.subtitle).font(.subheadline)
                Text(context.isStale ? "信息已过期 · 打开刷新" : context.state.item.display(at: .now))
                Text(Date(timeIntervalSince1970: context.state.item.updatedAt), style: .time).font(.caption)
            }.padding().widgetURL(URL(string: context.state.item.url))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) { Text(context.state.item.title) }
                DynamicIslandExpandedRegion(.trailing) { Text(context.state.item.subtitle).lineLimit(1) }
                DynamicIslandExpandedRegion(.bottom) {
                    Text(context.isStale ? "信息已过期 · 打开刷新" : context.state.item.display(at: .now))
                }
            } compactLeading: { Text(context.state.item.title).lineLimit(1) }
              compactTrailing: { Image(systemName: context.state.item.paused || context.isStale ? "pause.fill" : "bus.fill") }
              minimal: { Image(systemName: context.state.item.paused || context.isStale ? "pause.fill" : "bus.fill") }
              .widgetURL(URL(string: context.state.item.url))
        }
    }
}

@main struct TrackrWidgetBundle: WidgetBundle {
    var body: some Widget { TrackrFavoritesWidget(); TrackingLiveActivity() }
}
